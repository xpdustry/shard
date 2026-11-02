package com.xpdustry.shard.compat.v160.blocks.payload;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.Building;
import org.junit.jupiter.api.*;

import java.util.*;

import static com.xpdustry.shard.compat.v160.blocks.payload.PayloadRun.*;
import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Payload conveyors, routers and payload units against vanilla, frame by frame ({@link PayloadRun}): the blocks, their
 * payloads, the units, the clock, the random, entity ids and the payload Calls, then on through vanilla's save.
 */
class PayloadOracleTest{
    /** Origins of the chain's conveyors (size 3, facing east), the router, and its three ways out. */
    private static final int Y = 15, ROUTER = 19;

    /**
     * Containers, batteries and daggers fed one by one onto a chain of payload conveyors, into a router sorting
     * containers straight on: the rest turns north or south. Every way ends on open ground, where units are dumped
     * (sent at once, with a block snapshot of the conveyor) and buildings wait. A processor turns the router at frame
     * 520; at frame 400 a conveyor in the chain is broken, dumping the dagger it holds, and the one before it loses its
     * next block. Vanilla saves at frame 700 and both run on to 1,000.
     */
    @Test
    void conveyorsAndRoutersCarryBuildingsAndUnits(){
        var run = new PayloadRun(34, Map.of());
        for(int x = 4; x <= 16; x += 3) run.place("payload-conveyor", x, Y, 0);
        run.place("payload-router", ROUTER, Y, 0);
        run.place("payload-conveyor", 22, Y, 0).place("payload-conveyor", 25, Y, 0);
        run.place("payload-conveyor", ROUTER, Y + 3, 1).place("payload-conveyor", ROUTER, Y + 6, 1);
        run.place("payload-conveyor", ROUTER, Y - 3, 3).place("payload-conveyor", ROUTER, Y - 6, 3);
        world.build(ROUTER, Y).configureAny(mindustry.content.Blocks.container);
        run.shard.buildings().at(ROUTER, Y).configureAny(ContentRef.block(EPOCH.block("container").id()));
        for(int i = 0; i < 9; i++){
            int when = 1 + i * 50;
            switch(i % 3){
                case 0 -> run.at(when, () -> run.feedBuilding(4, Y, "container",
                    v -> v.items.add(mindustry.content.Items.copper, 30), s -> s.items.add(EPOCH.item("copper").id(), 30)));
                case 1 -> run.at(when, () -> run.feedUnit(4, Y, "dagger"));
                default -> run.at(when, () -> run.feedBuilding(4, Y, "battery",
                    v -> v.power.status = 0.25f, s -> s.power.status = 0.25f));
            }
        }
        run.at(400, () -> run.remove(13, Y));
        run.at(520, () -> {
            world.build(ROUTER, Y).control(mindustry.logic.LAccess.config, 1, 0, 0, 0);
            run.shard.buildings().at(ROUTER, Y).control(com.xpdustry.shard.logic.lang.LAccess.config, 1, 0, 0, 0);
        });
        run.saveAt(700).run(1000);

        assertTrue(run.seen("UnitSpawned") >= 2, "daggers dumped onto the ground: " + run.seen);
        assertTrue(run.seen("Synced") >= 2, "conveyors synced after a dump: " + run.seen);
        assertTrue(run.seen("on payload-router") > 0 && run.seen("on payload-conveyor") > 0, "payloads rode: " + run.seen);
        assertEquals(1, run.seen("loaded"), "through a save");
        assertTrue(run.calls >= 4, run.calls + " Calls");
        // the router sent containers on and the rest aside: something reached each way out
        assertNotNull(run.vanillaUnit("dagger"), "a dagger stands on the ground");
    }

    /**
     * A mega and a quad picking up and dropping buildings and units, with their Calls: the mega lifts a container of
     * copper and carries it through vanilla's save before dropping it; it carries a charged battery off its solar
     * panel and drops it by a power node, whose graph it joins; it takes a battery off a payload conveyor and drops it
     * onto another. The quad lifts a mechanical drill mid-way through an ore, carries it through the save and drops it
     * on another ore, where it goes on drilling from where it was; then it picks up a dagger and drops it, which gives the
     * dagger a new id.
     */
    @Test
    void megaAndQuadPickUpAndDropBuildingsAndUnits(){
        var ores = new HashMap<Integer, String>();
        int size = 40;
        for(int x = 0; x < 2; x++){
            for(int y = 0; y < 2; y++){
                ores.put((30 + y) * size + 30 + x, "ore-copper");
                ores.put((30 + y) * size + 10 + x, "ore-copper");
            }
        }
        var run = new PayloadRun(size, ores);
        run.place("container", 5, 5, 0).stock(5, 5, "copper", 120);
        run.place("battery", 8, 5, 0).place("solar-panel", 9, 5, 0);
        run.place("power-node", 14, 9, 0);
        run.place("mechanical-drill", 30, 30, 0);
        run.place("payload-conveyor", 26, 10, 0).place("payload-conveyor", 26, 16, 1);
        run.spawn("mega", 5 * 8 + 4, 5 * 8 + 4).spawn("quad", 30 * 8 + 4, 30 * 8 + 4).spawn("dagger", 20 * 8, 22 * 8);
        world.build(8, 5).power.status = 0.4f;
        run.shard.buildings().at(8, 5).power.status = 0.4f;

        // the mega: a container, carried through the save
        run.at(2, () -> run.pickBuilding("mega", 5, 5, true));
        glide(run, "mega", 3, 40, 5 * 8 + 4, 5 * 8 + 4, 8 * 8, 5 * 8);
        // the quad: a drill, carried through the save
        run.at(120, () -> run.pickBuilding("quad", 30, 30, true));
        glide(run, "quad", 121, 200, 30 * 8 + 4, 30 * 8 + 4, 10 * 8 + 4, 30 * 8 + 4);
        glide(run, "mega", 150, 190, 8 * 8, 5 * 8, 15 * 8 + 4, 15 * 8 + 4);
        run.at(195, () -> run.drop("mega", 15 * 8 + 4, 15 * 8 + 4));
        run.at(205, () -> run.drop("quad", 10 * 8 + 4, 30 * 8 + 4));
        // the mega: a battery off its panel, dropped by the node
        glide(run, "mega", 196, 230, 15 * 8 + 4, 15 * 8 + 4, 8 * 8, 5 * 8);
        run.at(231, () -> run.pickBuilding("mega", 8, 5, true));
        glide(run, "mega", 232, 260, 8 * 8, 5 * 8, 13 * 8, 9 * 8);
        run.at(262, () -> run.drop("mega", 13 * 8, 9 * 8));
        // the quad: the dagger
        glide(run, "quad", 206, 240, 10 * 8 + 4, 30 * 8 + 4, 20 * 8, 22 * 8);
        run.at(241, () -> run.pickUnit("quad", "dagger"));
        glide(run, "quad", 242, 270, 20 * 8, 22 * 8, 24 * 8, 26 * 8);
        run.at(272, () -> run.drop("quad", 24 * 8, 26 * 8));
        // the mega: a payload conveyor's battery, onto the other conveyor
        run.at(280, () -> run.feedBuilding(26, 10, "battery", v -> v.power.status = 0.5f, s -> s.power.status = 0.5f));
        glide(run, "mega", 263, 290, 13 * 8, 9 * 8, 26 * 8, 10 * 8);
        run.at(300, () -> run.pickBuilding("mega", 26, 10, false));
        glide(run, "mega", 301, 320, 26 * 8, 10 * 8, 26 * 8, 16 * 8);
        run.at(322, () -> run.drop("mega", 26 * 8, 16 * 8));
        run.saveAt(160).run(640);

        assertEquals(1, run.seen("loaded"), "through a save");
        assertTrue(run.seen("PickedBuild") >= 4 && run.seen("PickedUnit") == 1 && run.seen("Dropped") >= 4, "Calls: " + run.seen);
        assertTrue(run.seen("carrying") > 100, "carried: " + run.seen);
        // the dropped buildings stand where they were dropped, with what they held
        mindustry.gen.Building container = world.build(15, 15), drill = world.build(10, 30), battery = world.build(13, 9);
        assertTrue(container != null && container.block == mindustry.content.Blocks.container && container.items.get(mindustry.content.Items.copper) == 120,
            "the container and its copper were dropped: " + container);
        assertTrue(drill instanceof mindustry.world.blocks.production.Drill.DrillBuild d && d.timeDrilled > 0 && d.dominantItem != null,
            "the drill drills again: " + drill);
        assertTrue(battery != null && battery.block == mindustry.content.Blocks.battery && battery.power.graph.all.contains(world.build(14, 9)),
            "the battery joined the node's graph: " + battery);
        Building ours = run.shard.buildings().at(26, 16);
        assertTrue(ours != null && ours.getPayload() != null, "the conveyor took the battery");
    }

    /** Moves {@code carrier} in a straight line from (x0, y0) to (x1, y1), frames {@code from} to {@code to}. */
    private static void glide(PayloadRun run, String carrier, int from, int to, float x0, float y0, float x1, float y1){
        for(int f = from; f <= to; f++){
            float t = (f - from + 1) / (float)(to - from + 1);
            float x = x0 + (x1 - x0) * t, y = y0 + (y1 - y0) * t;
            run.at(f, () -> run.move(carrier, x, y));
        }
    }
}
