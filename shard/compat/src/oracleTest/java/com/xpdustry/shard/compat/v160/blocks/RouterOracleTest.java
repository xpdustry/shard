package com.xpdustry.shard.compat.v160.blocks;

import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.KindKey;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.blocks.distribution.Router;
import com.xpdustry.shard.sim.blocks.sandbox.ItemSource;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.world.*;
import arc.func.*;
import arc.util.io.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.world.blocks.sandbox.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;
import java.util.function.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate: routers and distributors move items as vanilla's do. Each fixture is built in a vanilla world ({@link Sim})
 * and in a Shard one, run {@link #FRAMES} frames with the same edits (configs, logic {@code enabled}) on the same
 * frames, and compared: every router's and source's state after every frame, every item a void takes (the frame,
 * the void, the item, the building it came from, in order), and every building's save chunk at the end.
 *
 * <p>Shard's voids are {@link RecordingVoid}, {@code ItemVoid} with a record; vanilla's are its own
 * {@code ItemVoidBuild} with the same record, through {@code Blocks.itemVoid.buildType}.
 */
public class RouterOracleTest{
    static final int FRAMES = 900;
    static final Epoch EPOCH = Epoch.vanilla();
    static final BuildingCodecs CODECS = BuildingCodecs.builtIn();
    static final KindRegistry KINDS = KindRegistry.builtIn().withPassthrough().withKind(KindKey.of("item-void"), RecordingVoid.class, RecordingVoid::new);

    /** An item a void took: on which frame, which void, which item, from which building. */
    record Arrival(int frame, int x, int y, String item, int fromX, int fromY){
    }

    static final List<Arrival> vanillaArrivals = new ArrayList<>(), shardArrivals = new ArrayList<>();
    static int frame;
    static Prov<mindustry.gen.Building> voidType;

    /** {@code ItemVoidBuild} in Shard, recording what it takes: {@code ItemVoid}'s two methods. */
    public static final class RecordingVoid extends Building{
        @Override
        public boolean acceptItem(Building source, int item){
            return enabled;
        }

        @Override
        public void handleItem(Building source, int item){
            var tiles = world().tiles();
            shardArrivals.add(new Arrival(frame, tiles.x(tile()), tiles.y(tile()), world().content().item(item).name(),
                tiles.x(source.tile()), tiles.y(source.tile())));
        }
    }

    @BeforeAll
    static void recordVoids(){
        Vanilla.boot();
        voidType = Blocks.itemVoid.buildType;
        Blocks.itemVoid.buildType = () -> ((ItemVoid)Blocks.itemVoid).new ItemVoidBuild(){
            @Override
            public void handleItem(mindustry.gen.Building source, mindustry.type.Item item){
                super.handleItem(source, item);
                vanillaArrivals.add(new Arrival(frame, tile.x, tile.y, item.name, source.tile.x, source.tile.y));
            }
        };
    }

    @AfterAll
    static void restoreVoids(){
        Blocks.itemVoid.buildType = voidType;
    }

    /**
     * A line of routers between a copper source and a void, with a lead source halfway and a silicon one by the last
     * router, which keeps that router full until logic disables it: items go back and forth along the line. A crux
     * router beside it is no neighbour. One router is disabled by logic for a while.
     */
    @Test
    void chain(){
        var run = new Run(16, 6);
        run.place("item-source", 1, 2);
        for(int x = 2; x <= 10; x++) run.place("router", x, 2);
        run.place("item-void", 11, 2);
        run.place("item-source", 6, 1);
        run.place("item-source", 10, 3);
        run.place("router", 5, 3, Team.crux);
        run.configure(1, 2, "copper");
        run.configure(6, 1, "lead");
        run.configure(10, 3, "silicon");
        run.at(250, () -> run.enabled(8, 2, false));
        run.at(400, () -> run.enabled(8, 2, true));
        run.at(500, () -> run.enabled(10, 3, false));
        run.run();
    }

    /**
     * A router stack and a distributor, each with voids on its sides, fed by two sources that also feed voids
     * directly; one source changes item, is cleared, then set again.
     */
    @Test
    void fan(){
        var run = new Run(12, 12);
        run.place("item-source", 5, 3);
        run.place("item-void", 4, 4);
        run.place("router", 5, 4);
        run.place("item-void", 6, 4);
        run.place("item-void", 4, 5);
        run.place("router", 5, 5);
        run.place("item-source", 6, 5);
        run.place("distributor", 5, 6);
        for(int y = 6; y <= 7; y++){
            run.place("item-void", 4, y);
            run.place("item-void", 7, y);
        }
        run.place("item-void", 5, 8);
        run.place("item-void", 6, 8);
        run.configure(5, 3, "copper");
        run.configure(6, 5, "lead");
        run.at(200, () -> run.configure(5, 3, "silicon"));
        run.at(350, () -> run.configure(5, 3, null));
        run.at(450, () -> run.configure(5, 3, "copper"));
        run.run();
    }

    /**
     * A ring of four routers next to a distributor, which hands items back into the ring. Its first void is disabled
     * for a while, so everything backs up, and the second source starts late.
     */
    @Test
    void backFlow(){
        var run = new Run(12, 12);
        run.place("item-source", 2, 5);
        run.place("router", 3, 5);
        run.place("router", 4, 5);
        run.place("router", 4, 6);
        run.place("router", 3, 6);
        run.place("item-source", 3, 7);
        run.place("distributor", 5, 5);
        run.place("item-void", 7, 5);
        run.place("item-void", 5, 4);
        run.configure(2, 5, "copper");
        run.at(120, () -> run.configure(3, 7, "lead"));
        run.at(300, () -> run.enabled(7, 5, false));
        run.at(450, () -> run.enabled(7, 5, true));
        run.at(500, () -> run.enabled(5, 4, false));
        run.run();
    }

    /** One fixture in both worlds, built and edited in step. */
    static final class Run{
        final Sim sim;
        final World shard;
        final Map<Integer, List<Runnable>> edits = new TreeMap<>();

        Run(int width, int height){
            vanillaArrivals.clear();
            shardArrivals.clear();
            frame = 0;
            sim = Sim.empty(width, height);
            shard = new World(WorldConfig.of(EPOCH, width, height, 1).withKinds(KINDS));
            shard.buildings().beginLoad();
            for(int pos = 0; pos < shard.tiles().size(); pos++) shard.tiles().floor(pos, EPOCH.block("stone").id());
            shard.buildings().endLoad(false);
        }

        void place(String block, int x, int y){
            place(block, x, y, Team.sharded);
        }

        /** As a map editor places it: {@code tile.setBlock}, rotation 0. */
        void place(String block, int x, int y, Team team){
            sim.place(content.block(block), x, y, 0, team);
            shard.buildings().setBlock(x, y, EPOCH.block(block), team.id, 0, Cause.SERVER);
        }

        /** {@code configureAny}: an item by name, or null to clear. */
        void configure(int x, int y, String item){
            sim.build(x, y).configureAny(item == null ? null : content.item(item));
            Vanilla.runPosted();
            shard.buildings().at(x, y).configureAny(item == null ? null : ContentRef.item(EPOCH.item(item).id()));
        }

        /** What a processor's {@code control enabled} does to the building. */
        void enabled(int x, int y, boolean enabled){
            sim.build(x, y).control(mindustry.logic.LAccess.enabled, enabled ? 1 : 0, 0, 0, 0);
            shard.buildings().at(x, y).control(com.xpdustry.shard.logic.lang.LAccess.enabled, enabled ? 1 : 0, 0, 0, 0);
        }

        void at(int when, Runnable edit){
            edits.computeIfAbsent(when, k -> new ArrayList<>()).add(edit);
        }

        void run(){
            assertEquals(vanillaStates(), shardStates(), "as placed");
            for(frame = 1; frame <= FRAMES; frame++){
                for(Runnable edit : edits.getOrDefault(frame, List.of())) edit.run();
                sim.step(1);
                shard.tick();
                assertEquals(vanillaStates(), shardStates(), "after frame " + frame);
                assertEquals(vanillaArrivals, shardArrivals, "arrivals by frame " + frame);
            }
            assertTrue(vanillaArrivals.size() > FRAMES / 20, "items should flow: " + vanillaArrivals.size() + " arrivals");
            assertTrue(vanillaArrivals.stream().map(Arrival::item).distinct().count() > 1, "two items at least");

            var current = ContentHeader.of(EPOCH).mapping(EPOCH);
            for(mindustry.gen.Building expected : buildings()){
                Building actual = shard.buildings().at(expected.tile.x, expected.tile.y);
                assertEquals(hex(chunk(expected)), hex(CODECS.chunk(actual, EPOCH, current)),
                    expected.block.name + " at (" + expected.tile.x + ", " + expected.tile.y + "): save chunk");
            }
        }

        List<mindustry.gen.Building> buildings(){
            var out = new ArrayList<mindustry.gen.Building>();
            for(var tile : world.tiles) if(tile.build != null && tile.isCenter()) out.add(tile.build);
            return out;
        }

        List<String> vanillaStates(){
            var out = new ArrayList<String>();
            for(mindustry.gen.Building build : buildings()){
                String where = build.block.name + " (" + build.tile.x + ", " + build.tile.y + ")";
                String items = items(id -> build.items == null ? 0 : build.items.get(id));
                switch(build){
                    case mindustry.world.blocks.distribution.Router.RouterBuild router -> out.add(where + " items " + items
                        + " last " + (router.lastItem == null ? -1 : router.lastItem.id)
                        + " input " + (router.lastInput == null ? -1 : shard.tiles().pos(router.lastInput.x, router.lastInput.y))
                        + " time " + router.time + " cycles " + Arrays.toString(cycles(router)) + " enabled " + build.enabled);
                    case mindustry.world.blocks.sandbox.ItemSource.ItemSourceBuild source -> out.add(where + " counter " + source.counter
                        + " output " + (source.outputItem == null ? -1 : source.outputItem.id));
                    default -> out.add(where + " enabled " + build.enabled);
                }
            }
            return out;
        }

        List<String> shardStates(){
            var out = new ArrayList<String>();
            var tiles = shard.tiles();
            for(mindustry.gen.Building expected : buildings()){
                Building build = shard.buildings().at(expected.tile.x, expected.tile.y);
                String where = build.block().name() + " (" + tiles.x(build.tile()) + ", " + tiles.y(build.tile()) + ")";
                String items = items(id -> build.items == null ? 0 : build.items.get(id));
                switch(build){
                    case Router router -> out.add(where + " items " + items + " last " + router.lastItem
                        + " input " + router.lastInput + " time " + router.time + " cycles " + Arrays.toString(router.cycles)
                        + " enabled " + build.enabled);
                    case ItemSource source -> out.add(where + " counter " + source.counter + " output " + source.outputItem);
                    default -> out.add(where + " enabled " + build.enabled);
                }
            }
            return out;
        }
    }

    static String items(IntUnaryOperator amount){
        var out = new StringJoiner(",", "[", "]");
        for(int id = 0; id < EPOCH.items(); id++) if(amount.applyAsInt(id) != 0) out.add(EPOCH.item(id).name() + "=" + amount.applyAsInt(id));
        return out.toString();
    }

    static byte[] cycles(mindustry.world.blocks.distribution.Router.RouterBuild router){
        try{
            var field = mindustry.world.blocks.distribution.Router.RouterBuild.class.getDeclaredField("cycles");
            field.setAccessible(true);
            return (byte[])field.get(router);
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }

    /** What {@code writeMap} puts in a building's chunk: the revision, then {@code writeAll}. */
    static byte[] chunk(mindustry.gen.Building building){
        var bytes = new ByteArrayOutputStream();
        var out = new Writes(new DataOutputStream(bytes));
        out.b(building.version());
        building.writeAll(out);
        return bytes.toByteArray();
    }

    static String hex(byte[] bytes){
        return HexFormat.of().formatHex(bytes);
    }
}
