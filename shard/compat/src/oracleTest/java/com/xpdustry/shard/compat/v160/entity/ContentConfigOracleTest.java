package com.xpdustry.shard.compat.v160.entity;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.blocks.distribution.Sorter;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.unit.BuildPlan;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.io.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Review 38: content-valued plan configs cross the wire as vanilla's do. A client's plans ({@code TypeIO.writePlans},
 * as {@code clientSnapshot} sends them) with item, liquid and nested content configs decode through
 * {@link Codecs#readPlan} and {@link WirePlans} to the sim values vanilla's {@code readPlan} gives, configure a real
 * sorter as vanilla's does (an item sets it, a liquid has no handler, an array copies nothing), and encode back to
 * vanilla's bytes. {@code EntitySyncOracleTest} covers the same configs in unit sync and saves, and {@code
 * TransportTest} in checkpoints and forks.
 */
class ContentConfigOracleTest{
    static final Epoch EPOCH = Epoch.vanilla();

    @Test
    void clientPlansDecodeConfigureASorterAndEncodeBack(){
        Sim sim = Sim.empty(12, 12);
        var world = new World(WorldConfig.of(EPOCH, 12, 12, 1).withKinds(KindRegistry.builtIn().withPassthrough()));
        world.buildings().beginLoad();
        for(int pos = 0; pos < world.tiles().size(); pos++) world.tiles().floor(pos, EPOCH.block("stone").id());
        world.buildings().endLoad(false);
        sim.place(Blocks.sorter, 5, 5, 0, Team.sharded);
        var sorter = (Sorter)world.buildings().setBlock(5, 5, EPOCH.block("sorter"), Team.sharded.id, 0, Cause.SERVER);

        Object[] configs = {Items.copper, Liquids.water, Items.titanium, new Object[]{Items.lead, Liquids.oil, 7}, null, Items.sand};
        var plans = new mindustry.entities.units.BuildPlan[configs.length];
        for(int i = 0; i < configs.length; i++) plans[i] = new mindustry.entities.units.BuildPlan(5, 5, 0, Blocks.sorter, configs[i]);
        byte[] bytes = written(out -> TypeIO.writePlans(out, plans));

        mindustry.entities.units.BuildPlan[] expected = TypeIO.readPlans(reads(bytes));
        var in = new Reads(bytes);
        int count = in.s();
        assertEquals(configs.length, count);
        var context = WorldWireContext.capture(world);
        var ids = WirePlans.ContentIds.of(world.content());
        mindustry.gen.Building vanillaSorter = sim.build(5, 5);
        for(int i = 0; i < count; i++){
            BuildPlan plan = WirePlans.plan(Codecs.readPlan(in, context), ids);
            assertEquals(describe(expected[i].config), describe(plan.config), "plan " + i + ": decoded config");

            vanillaSorter.configured(null, expected[i].config);
            sorter.configured(null, plan.config);
            var sortItem = ((mindustry.world.blocks.distribution.Sorter.SorterBuild)vanillaSorter).sortItem;
            assertEquals(sortItem == null ? -1 : sortItem.id, sorter.sortItem, "plan " + i + ": the sorter's item after its config");

            var original = expected[i];
            byte[] vanillaPlan = written(out -> TypeIO.writePlan(out, original));
            var shardPlan = new Writes();
            Codecs.writePlan(shardPlan, WirePlans.of(plan, ids));
            assertArrayEquals(vanillaPlan, shardPlan.toByteArray(), "plan " + i + ": encoded back");
        }
        assertEquals(0, in.remaining());
        assertEquals(EPOCH.item("sand").id(), sorter.sortItem);
    }

    /** A content id vanilla's {@code getByID} has nothing for is null; a type no epoch holds survives the round trip. */
    @Test
    void unknownContentIsNullAndForeignTypesRoundTrip(){
        Vanilla.boot();
        var ids = WirePlans.ContentIds.of(EPOCH);
        assertNull(WirePlans.value(new WireObjects.ContentRef(0, EPOCH.items()), ids), "an item past the content");
        assertNull(WirePlans.value(new WireObjects.ContentRef(4, -1), ids), "a negative liquid");
        int unitCommand = mindustry.ctype.ContentType.unitCommand.ordinal();
        Object foreign = WirePlans.value(new WireObjects.ContentRef(unitCommand, 2), ids);
        assertEquals(new ContentRef.Foreign(unitCommand, 2), foreign);
        assertEquals(new WireObjects.ContentRef(unitCommand, 2), WirePlans.config(foreign, ids));
        assertNotNull(content.getByID(mindustry.ctype.ContentType.unitCommand, 2), "vanilla has that command");
    }

    /** A config as text, the same for vanilla's objects and Shard's values: content by type and name. */
    static String describe(Object config){
        return switch(config){
            case null -> "null";
            case mindustry.ctype.UnlockableContent content -> content.getContentType().name() + ":" + content.name;
            case ContentRef ref -> ref.type().yamlName() + ":" + EPOCH.get(ref.type(), ref.id()).name();
            case Object[] values -> {
                var out = new StringJoiner(", ", "[", "]");
                for(Object value : values) out.add(describe(value));
                yield out.toString();
            }
            default -> config.getClass().getSimpleName() + ":" + config;
        };
    }

    static byte[] written(java.util.function.Consumer<arc.util.io.Writes> writer){
        var bytes = new java.io.ByteArrayOutputStream();
        writer.accept(new arc.util.io.Writes(new java.io.DataOutputStream(bytes)));
        return bytes.toByteArray();
    }

    static arc.util.io.Reads reads(byte[] bytes){
        return new arc.util.io.Reads(new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes)));
    }
}
