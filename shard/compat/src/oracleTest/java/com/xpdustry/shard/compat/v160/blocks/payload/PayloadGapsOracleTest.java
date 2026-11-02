package com.xpdustry.shard.compat.v160.blocks.payload;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.sim.building.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static com.xpdustry.shard.compat.v160.blocks.payload.PayloadRun.*;
import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * W4.4's untested payload cases against vanilla, frame by frame through vanilla's save ({@link PayloadRun}, as
 * {@link PayloadBlocksOracleTest}): a deconstructor pouring a carried building's liquid (Q225), a loader charging a
 * battery and an unloader draining it, and the large variants (large constructor, deconstructor, large payload mass
 * driver).
 */
class PayloadGapsOracleTest{
    private static final int Y = 10;

    /**
     * Q225: a deconstructor pours a carried building's liquid on its own tiles, by its own size. A liquid container
     * holding 200 water is handed to a small deconstructor (3×3), and a liquid tank holding 1,000 to a deconstructor
     * (5×5, the large variant); as each starts, it deposits {@code amount / size² × 2} on every one of its tiles
     * (PayloadDeconstructor.java:194), 44.4 and 80 (over a puddle's 70, so it spreads). The puddles are compared as
     * the hazard oracles compare them; the items go into containers. Vanilla saves at frame 150 and both run to 700.
     */
    @Test
    void deconstructorsPourABuildingsLiquidByTheirOwnSize(){
        var run = new PayloadRun(30, Map.of()).hazards();
        run.place("small-deconstructor", 8, Y, 0).place("power-source", 8, Y + 2, 0).place("container", 10, Y, 0);
        run.place("deconstructor", 8, 22, 0).place("power-source", 8, 25, 0).place("container", 11, 22, 0);
        int water = EPOCH.liquid("water").id();
        run.at(2, () -> {
            run.feedBuilding(8, Y, "liquid-container", v -> v.liquids.add(mindustry.content.Liquids.water, 200f), b -> b.liquids.add(water, 200f));
            run.feedBuilding(8, 22, "liquid-tank", v -> v.liquids.add(mindustry.content.Liquids.water, 1000f), b -> b.liquids.add(water, 1000f));
        });
        float[] firstPuddle = {-1f};
        for(int f = 3; f <= 140; f++){
            run.at(f, () -> {
                for(var entity : mindustry.gen.Groups.all) if(entity instanceof mindustry.gen.Puddle puddle && firstPuddle[0] < 0f) firstPuddle[0] = puddle.amount;
            });
        }
        run.saveAt(150).run(700);

        assertEquals(1, run.seen("loaded"), "through a save");
        assertTrue(run.seen("deconstructing liquid-container") > 0 && run.seen("deconstructing liquid-tank") > 0,
            "both deconstructors took their tank apart: " + run.seen);
        assertTrue(run.seen("puddles") > 100, "the water lay in puddles: " + run.seen);
        assertTrue(firstPuddle[0] > 0f, "a puddle was poured as the deconstruction began");
        assertTrue(world.build(10, Y).items.total() > 0 && world.build(11, 22).items.total() > 0, "the tanks' items came back");
    }

    /**
     * A payload source makes batteries; a payload loader on a power source charges each, by its spare power over its
     * base use, and exports it once full; a payload unloader drains it into its own graph and passes it on to a
     * payload void. The carried batteries' power status is compared every frame. Vanilla saves at frame 300 and both
     * run to 700.
     */
    @Test
    void aLoaderChargesABatteryAndAnUnloaderDrainsIt(){
        var run = new PayloadRun(24, Map.of());
        run.place("payload-source", 4, Y, 0);
        run.place("payload-loader", 8, Y, 0).place("power-source", 8, Y + 2, 0);
        run.place("payload-unloader", 11, Y, 0).place("power-source", 11, Y + 2, 0);
        run.place("payload-void", 15, Y, 0);
        run.configure(4, Y, mindustry.content.Blocks.battery, ContentRef.block(EPOCH.block("battery").id()));
        boolean[] charging = {false}, draining = {false};
        for(int f = 1; f <= 700; f++){
            run.at(f, () -> {
                var loader = (mindustry.world.blocks.payloads.PayloadLoader.PayloadLoaderBuild)world.build(8, Y);
                if(loader.payload != null && loader.payload.build.power.status > 0f && loader.payload.build.power.status < 1f) charging[0] = true;
                var unloader = (mindustry.world.blocks.payloads.PayloadUnloader.PayloadUnloaderBuild)world.build(11, Y);
                if(unloader.payload != null && unloader.payload.build.power.status > 0f && unloader.payload.build.power.status < 1f) draining[0] = true;
            });
        }
        run.saveAt(300).run(700);

        assertEquals(1, run.seen("loaded"), "through a save");
        assertTrue(charging[0], "the loader charged a battery part way");
        assertTrue(draining[0], "the unloader drained one part way");
        assertTrue(run.seen("in payload-void battery") > 0, "batteries went through to the void: " + run.seen);
    }

    /**
     * The large variants: a large constructor fed tungsten, thorium and beryllium builds reinforced vaults (3×3), which
     * a payload conveyor carries to a deconstructor (5×5) that takes them apart into a container; a payload source's
     * reinforced containers are thrown 26 tiles by a large payload mass driver to another, which passes them to a
     * payload void. Save chunks every 50 frames. Vanilla saves at frame 700 and both run to 1,400.
     */
    @Test
    void theLargeVariantsBuildThrowAndTakeApart(){
        var run = new PayloadRun(48, Map.of()).planet("erekir");
        String[] items = {"tungsten", "thorium", "beryllium"};
        for(int i = 0; i < items.length; i++){
            run.place("item-source", 2, Y - 1 + i, 0);
            run.configure(2, Y - 1 + i, mindustry.Vars.content.item(items[i]), ContentRef.item(EPOCH.item(items[i]).id()));
        }
        run.place("large-constructor", 5, Y, 0).place("power-source", 5, Y + 3, 0);
        run.configure(5, Y, mindustry.content.Blocks.reinforcedVault, ContentRef.block(EPOCH.block("reinforced-vault").id()));
        run.place("payload-conveyor", 9, Y, 0);
        run.place("deconstructor", 13, Y, 0).place("power-source", 13, Y + 3, 0).place("container", 16, Y, 0);

        int y = 40;
        run.place("payload-source", 4, y, 0);
        run.configure(4, y, mindustry.content.Blocks.reinforcedContainer, ContentRef.block(EPOCH.block("reinforced-container").id()));
        run.place("large-payload-mass-driver", 9, y, 0).place("power-source", 9, y + 3, 0);
        run.place("large-payload-mass-driver", 35, y, 0).place("power-source", 35, y + 3, 0);
        run.place("payload-void", 40, y, 0);
        int link = Point2.pack(35, y);
        run.configure(9, y, link, link);
        run.chunksEvery(50).saveAt(700).run(1400);

        assertEquals(1, run.seen("loaded"), "through a save");
        assertTrue(run.seen("in large-constructor reinforced-vault") > 0, "the large constructor built vaults: " + run.seen);
        assertTrue(run.seen("deconstructing reinforced-vault") > 0, "the deconstructor took one apart: " + run.seen);
        assertTrue(world.build(16, Y).items.get(mindustry.content.Items.thorium) > 0, "a vault's thorium came back");
        assertTrue(run.seen("charging") > 0, "the large driver charged: " + run.seen);
        assertTrue(run.seen("in payload-void reinforced-container") > 0, "a container crossed to the void: " + run.seen);
    }
}
