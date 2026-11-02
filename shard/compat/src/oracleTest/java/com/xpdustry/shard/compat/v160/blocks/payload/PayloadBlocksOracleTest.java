package com.xpdustry.shard.compat.v160.blocks.payload;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.sim.building.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static com.xpdustry.shard.compat.v160.blocks.payload.PayloadRun.*;
import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The advanced payload blocks against vanilla, frame by frame ({@link PayloadRun}): constructors, deconstructors, the
 * payload mass driver, loaders and unloaders, the payload source and void, with every block's state, the carried
 * buildings' and units' whole state, the random, entity ids and the payload Calls, through vanilla's save.
 */
class PayloadBlocksOracleTest{
    private static final int Y = 10;

    /**
     * A constructor fed tungsten by an item source builds large tungsten walls, which two payload conveyors carry to a
     * small deconstructor; it takes each apart and dumps the tungsten into a container. Both are powered by power
     * sources. At frame 500 the constructor is cleared and set again, which resets its progress. Vanilla saves at frame
     * 700 and both run on to 1,400.
     */
    @Test
    void aConstructorsWallsRideToADeconstructor(){
        var run = new PayloadRun(30, Map.of()).planet("erekir");
        run.place("item-source", 3, Y, 0).place("constructor", 5, Y, 0).place("power-source", 5, Y + 2, 0);
        run.place("payload-conveyor", 8, Y, 0).place("payload-conveyor", 11, Y, 0);
        run.place("small-deconstructor", 14, Y, 0).place("power-source", 14, Y + 2, 0).place("container", 16, Y, 0);
        run.configure(3, Y, mindustry.content.Items.tungsten, ContentRef.item(EPOCH.item("tungsten").id()));
        Object vanillaWall = mindustry.content.Blocks.tungstenWallLarge;
        Object ourWall = ContentRef.block(EPOCH.block("tungsten-wall-large").id());
        run.configure(5, Y, vanillaWall, ourWall);
        run.at(500, () -> run.configure(5, Y, null, null));
        run.at(503, () -> run.configure(5, Y, vanillaWall, ourWall));
        run.chunksEvery(50).saveAt(700).run(1400);

        assertEquals(1, run.seen("loaded"), "through a save");
        assertTrue(run.seen("in constructor tungsten-wall-large") > 0, "the constructor built walls: " + run.seen);
        assertTrue(run.seen("on payload-conveyor") > 0, "walls rode the conveyors: " + run.seen);
        assertTrue(run.seen("deconstructing tungsten-wall-large") > 0, "the deconstructor took walls apart: " + run.seen);
        assertTrue(world.build(16, Y).items.get(mindustry.content.Items.tungsten) > 0, "the tungsten came back");
    }

    /**
     * A payload source makes containers, which three payload mass drivers throw across two 20-tile gaps to a payload
     * loader. An item source fills each container with copper there, and an unloader empties it into an item void
     * before a payload void destroys it. The first driver unlinks the first time it waits on the second (from frame
     * 400) and links again 60 frames later. Save chunks are compared every 50 frames. At frame 300 the source switches to daggers, which the drivers throw on too;
     * the last driver keeps the first one, as the loader takes no units. Vanilla saves at frame 1,000 and both run on to
     * 2,200.
     */
    @Test
    void massDriversThrowBuildingsAndUnitsToALoader(){
        var run = new PayloadRun(64, Map.of());
        run.place("payload-source", 2, Y, 0);
        int[] drivers = {6, 26, 46};
        for(int x : drivers) run.place("payload-mass-driver", x, Y, 0).place("power-source", x, Y + 2, 0);
        run.place("payload-loader", 49, Y, 0).place("item-source", 49, Y + 2, 0).place("power-source", 49, Y - 2, 0);
        run.place("payload-unloader", 52, Y, 0).place("power-source", 52, Y - 2, 0).place("item-void", 52, Y + 2, 0);
        run.place("payload-void", 56, Y, 0);
        run.configure(49, Y + 2, mindustry.content.Items.copper, ContentRef.item(EPOCH.item("copper").id()));
        run.configure(2, Y, mindustry.content.Blocks.container, ContentRef.block(EPOCH.block("container").id()));
        for(int i = 0; i < 2; i++){
            int link = Point2.pack(drivers[i + 1], Y);
            run.configure(drivers[i], Y, link, link);
        }
        run.at(300, () -> run.configure(2, Y, mindustry.content.UnitTypes.dagger,
            new ContentRef(com.xpdustry.shard.content.ContentType.UNIT, EPOCH.unit("dagger").id())));
        // the first time the first driver waits on the second, it unlinks, so the second drops it from its queue;
        // it links again 60 frames later
        int relink = Point2.pack(drivers[1], Y);
        int[] unlinked = {-1};
        for(int f = 400; f <= 1400; f++){
            int frame = f;
            run.at(f, () -> {
                var second = (mindustry.world.blocks.payloads.PayloadMassDriver.PayloadDriverBuild)world.build(drivers[1], Y);
                if(unlinked[0] == -1 && second.waitingShooters.contains(world.build(drivers[0], Y))){
                    unlinked[0] = frame;
                    run.configure(drivers[0], Y, -1, -1);
                    run.count("unlinked while queued");
                }else if(unlinked[0] != -1 && frame == unlinked[0] + 60){
                    run.configure(drivers[0], Y, relink, relink);
                }
            });
        }
        run.chunksEvery(50).saveAt(1000).run(2200);

        assertEquals(1, run.seen("loaded"), "through a save");
        assertTrue(run.seen("charging") > 0, "the drivers charged: " + run.seen);
        assertTrue(run.seen("in payload-mass-driver container") > 0 && run.seen("in payload-mass-driver dagger") > 0,
            "the drivers carried containers and daggers: " + run.seen);
        assertTrue(run.seen("in payload-loader container") > 0, "the loader filled a container: " + run.seen);
        assertTrue(run.seen("in payload-unloader container") > 0, "the unloader emptied one: " + run.seen);
        assertEquals(1, run.seen("unlinked while queued"), "the first driver left the second's queue: " + run.seen);
        assertTrue(run.seen("in payload-void container") > 0, "the void destroyed one: " + run.seen);
        assertTrue(world.build(drivers[2], Y).getPayload() instanceof mindustry.world.blocks.payloads.UnitPayload,
            "a dagger crossed both gaps");
    }

    /**
     * {@code PayloadCosts} against vanilla's {@code getTotalRequirements()} and {@code getBuildTime()} of every unit type
     * and every block's sorted requirements and build time, which the deconstructor takes apart by.
     */
    @Test
    void payloadCostsAreVanillas(){
        new PayloadRun(8, Map.of());
        int units = 0;
        for(var type : content.units()){
            var expected = new StringJoiner(",");
            for(var stack : type.getTotalRequirements()) expected.add(stack.item.name + "=" + stack.amount);
            int id = EPOCH.unit(type.name).id();
            var stacks = com.xpdustry.shard.sim.blocks.payload.PayloadCosts.unitRequirements(EPOCH, id);
            var actual = new StringJoiner(",");
            for(int i = 0; i < stacks.size(); i++) actual.add(EPOCH.item(stacks.id(i)).name() + "=" + stacks.amount(i));
            assertEquals(expected.toString(), actual.toString(), type.name + " requirements");
            assertEquals(type.getBuildTime(), com.xpdustry.shard.sim.blocks.payload.PayloadCosts.unitBuildTime(EPOCH, id), type.name + " build time");
            if(stacks.size() > 0) units++;
        }
        assertTrue(units > 20, units + " unit types with a cost");
        for(var block : content.blocks()){
            var info = com.xpdustry.shard.sim.build.PlaceInfo.of(EPOCH.block(block.id), EPOCH);
            var expected = new StringJoiner(",");
            for(var stack : block.requirements) expected.add(stack.item.name + "=" + stack.amount);
            var actual = new StringJoiner(",");
            for(int i = 0; i < info.items.length; i++) actual.add(EPOCH.item(info.items[i]).name() + "=" + info.amounts[i]);
            assertEquals(expected.toString(), actual.toString(), block.name + " requirements");
            assertEquals(block.buildTime, info.buildTime, block.name + " build time");
        }
    }

    /**
     * A carrier loaded from a save holding payload blocks that hold payloads: an oct lifts an unpowered payload mass
     * driver holding a dagger and a payload loader holding a container of copper, carries both through vanilla's save
     * (which writes each nested payload inside the carried building's bytes), then drops them; the driver moves its
     * dagger out onto the ground. A payload block that was carried keeps its payload when lifted ({@code carried});
     * a payload conveyor would dump its item instead (PayloadConveyor.java:310), so none is carried here.
     */
    @Test
    void aLoadedCarrierKeepsThePayloadsItsBlocksHold(){
        var run = new PayloadRun(40, Map.of());
        run.place("payload-mass-driver", 10, 10, 0).place("payload-loader", 13, 10, 0);
        run.spawn("oct", 10 * 8, 10 * 8);
        run.feedUnit(10, 10, "dagger");
        run.feedBuilding(13, 10, "container", v -> v.items.add(mindustry.content.Items.copper, 20),
            s -> s.items.add(EPOCH.item("copper").id(), 20));
        run.at(2, () -> run.pickBuilding("oct", 10, 10, true));
        run.at(3, () -> run.move("oct", 13 * 8, 10 * 8));
        run.at(4, () -> run.pickBuilding("oct", 13, 10, true));
        for(int f = 60; f <= 90; f++){
            float t = (f - 59) / 31f;
            run.at(f, () -> run.move("oct", 13 * 8, (10 + 14 * t) * 8));
        }
        run.at(95, () -> run.drop("oct", 13 * 8, 24 * 8));
        run.at(130, () -> run.move("oct", 20 * 8, 24 * 8));
        run.at(131, () -> run.drop("oct", 20 * 8, 24 * 8));
        run.saveAt(40).run(600);

        assertEquals(1, run.seen("loaded"), "through a save");
        assertTrue(run.seen("carrying") > 30, "carried through the save: " + run.seen);
        assertTrue(run.seen("Dropped") >= 2, "both dropped: " + run.seen);
        assertTrue(run.seen("UnitSpawned") >= 1, "the driver moved its dagger out: " + run.seen);
    }
}
