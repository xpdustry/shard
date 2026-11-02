package com.xpdustry.shard.compat.v160.build;

import com.xpdustry.shard.sim.blocks.transport.*;
import com.xpdustry.shard.sim.building.*;
import mindustry.world.blocks.ConstructBlock.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gates (WP30): construction against vanilla, frame by frame ({@link BuildRun}): an alpha that pays for its sites
 * from the core, waits for items it lacks, breaks blocks for refunds, upgrades blocks in place, and the rebuild plans
 * destruction leaves. Each fixture checks what it exercised, so a fixture that stops building can't pass.
 */
class ConstructionOracleTest{
    static final int SIZE = 48;

    BuildRun run;

    @BeforeEach
    void world(){
        run = new BuildRun(SIZE, SIZE);
        run.core("core-shard", 8, 8);
    }

    int alpha(int tileX, int tileY){
        return run.unit("alpha", tileX * 8f, tileY * 8f);
    }

    void stock(Object... itemsAndAmounts){
        for(int i = 0; i < itemsAndAmounts.length; i += 2) run.items((String)itemsAndAmounts[i], (Integer)itemsAndAmounts[i + 1]);
    }

    /** What stands with its origin at (x, y), on both sides: the same, as {@link BuildRun} compared every frame. */
    String block(int x, int y){
        var build = world.build(x, y);
        return build == null ? "air" : build.block.name;
    }

    /**
     * WP30: twenty blocks of every kind (transport, a configured sorter, an upgrade over a loaded conveyor, walls of
     * two sizes, power, a turret, crafters) and five breaks, from one alpha's queue, with the titanium for the last
     * block running out half-way.
     */
    @Test
    void anAlphaBuildsTwentyBlocksAndBreaksFive(){
        stock("copper", 1000, "lead", 1000, "silicon", 100, "graphite", 50, "titanium", 11);
        run.place("conveyor", 20, 14, 1);
        run.handleItem(20, 14, "copper");
        run.handleItem(20, 14, "lead");
        run.place("copper-wall", 12, 26, 0);
        run.place("copper-wall-large", 15, 26, 0);
        run.place("router", 18, 26, 0);
        run.place("conveyor", 20, 26, 3);
        run.place("battery", 22, 26, 0);
        int alpha = alpha(24, 22);

        run.build(alpha, "conveyor", 12, 14, 0);
        run.build(alpha, "conveyor", 13, 14, 0);
        run.build(alpha, "conveyor", 14, 14, 0);
        run.build(alpha, "junction", 15, 14, 0);
        // a router doesn't rotate: the plan's rotation becomes 0
        run.build(alpha, "router", 16, 14, 2);
        run.build(alpha, "sorter", 17, 14, 0, content.item("copper"), ContentRef.item(BuildRun.EPOCH.item("copper").id()));
        run.build(alpha, "bridge-conveyor", 12, 16, 0);
        run.build(alpha, "bridge-conveyor", 15, 16, 0);
        run.build(alpha, "distributor", 18, 16, 0);
        run.build(alpha, "titanium-conveyor", 20, 14, 1);
        run.build(alpha, "copper-wall", 22, 14, 0);
        run.build(alpha, "copper-wall", 23, 14, 0);
        run.build(alpha, "copper-wall-large", 25, 14, 0);
        run.build(alpha, "battery", 28, 14, 0);
        run.build(alpha, "power-node", 30, 14, 0);
        run.build(alpha, "duo", 12, 20, 1);
        run.build(alpha, "solar-panel", 14, 20, 0);
        run.build(alpha, "combustion-generator", 16, 20, 0);
        run.build(alpha, "graphite-press", 19, 20, 0);
        run.breaking(alpha, 12, 26);
        run.breaking(alpha, 16, 27);
        run.breaking(alpha, 18, 26);
        run.breaking(alpha, 20, 26);
        run.breaking(alpha, 22, 26);
        run.build(alpha, "titanium-wall-large", 22, 20, 0);

        run.run(900);

        for(String done : List.of("conveyor@12,14", "conveyor@14,14", "junction@15,14", "router@16,14", "sorter@17,14", "bridge-conveyor@15,16",
            "distributor@18,16", "titanium-conveyor@20,14", "copper-wall@23,14", "copper-wall-large@25,14", "battery@28,14", "power-node@30,14",
            "duo@12,20", "solar-panel@14,20", "combustion-generator@16,20", "graphite-press@19,20")){
            String[] at = done.split("[@,]");
            assertEquals(at[0], block(Integer.parseInt(at[1]), Integer.parseInt(at[2])), done);
        }
        for(int[] gone : new int[][]{{12, 26}, {15, 26}, {18, 26}, {20, 26}, {22, 26}}) assertEquals("air", block(gone[0], gone[1]));
        var stuck = assertInstanceOf(ConstructBuild.class, world.build(22, 20), "the titanium ran out");
        assertTrue(stuck.progress > 0.2f && stuck.progress < 0.5f, "half-way: " + stuck.progress);
        assertEquals(2, ((Conveyor)run.ours(20, 14)).len, "the titanium conveyor kept the belt's items");
        assertTrue(((com.xpdustry.shard.sim.blocks.power.PowerNode)run.ours(30, 14)).power.links.size > 0, "the node linked the battery");
        assertEquals(20, run.calls.get("BeginPlace"), run.calls::toString);
        assertEquals(19, run.calls.get("ConstructFinish"), run.calls::toString);
        assertEquals(5, run.calls.get("BeginBreak"), run.calls::toString);
        assertEquals(5, run.calls.get("DeconstructFinish"), run.calls::toString);
        assertTrue(run.calls.getOrDefault("TileConfig", 0) > 0, run.calls::toString);
        assertTrue(run.siteFrames > 300, "sites stood on " + run.siteFrames + " frames");
    }

    /** M0: a conveyor line and a router from the core's items; nodes autolink a battery either way round. */
    @Test
    void aConveyorLineFromCoreItemsAndNodesThatAutolink(){
        stock("copper", 200, "lead", 200);
        int alpha = alpha(20, 32);
        for(int x = 10; x < 20; x++) run.build(alpha, "conveyor", x, 30, 0);
        run.build(alpha, "router", 20, 30, 0);
        // node after battery: the node's placed() links the battery
        run.build(alpha, "battery", 24, 30, 0);
        run.build(alpha, "power-node", 26, 30, 0);
        // battery after node: the battery's placed() asks the node to link it
        run.build(alpha, "power-node", 36, 40, 0);
        run.build(alpha, "battery", 38, 40, 0);

        int last = run.until(400, () -> run.shardUnits.getFirst().plans.isEmpty());
        assertTrue(last < 400, "the queue emptied");
        run.chunks();

        for(int x = 10; x < 20; x++) assertEquals("conveyor", block(x, 30));
        assertEquals("router", block(20, 30));
        assertEquals(2, run.calls.get("TileConfig"), run.calls::toString);
        assertEquals(15, run.calls.get("ConstructFinish"), run.calls::toString);
        assertEquals(List.of(1, 1), List.of(world.build(26, 30).power.links.size, world.build(36, 40).power.links.size));
    }

    /**
     * Rebuild plans: destroyed blocks are planned newest first with their configs, building over a planned tile drops
     * its plan, a site destroyed while building is planned as its block, and the alpha rebuilds a plan.
     */
    @Test
    void destructionLeavesRebuildPlans(){
        stock("copper", 500, "lead", 500);
        run.place("copper-wall", 14, 14, 0);
        run.place("sorter", 16, 14, 0);
        run.place("conveyor", 18, 14, 1);
        run.place("power-node", 20, 14, 0);
        run.place("power-node", 23, 14, 0);
        int alpha = alpha(22, 20);
        run.at(1, () -> {
            run.configure(16, 14, content.item("lead"), ContentRef.item(BuildRun.EPOCH.item("lead").id()));
            run.link(20, 14, 23, 14);
        });
        run.at(3, () -> {
            run.kill(14, 14);
            run.kill(16, 14);
            run.kill(18, 14);
            run.kill(20, 14);
        });
        run.at(5, () -> {
            // something else over the conveyor's tile; the sorter as planned
            run.build(alpha, "router", 18, 14, 0);
            run.build(alpha, "sorter", 16, 14, 0, content.item("lead"), ContentRef.item(BuildRun.EPOCH.item("lead").id()));
            run.build(alpha, "copper-wall-large", 24, 18, 0);
        });
        boolean[] killed = {false};
        for(int frame = 6; frame < 200; frame++){
            run.at(frame, () -> {
                if(!killed[0] && world.build(24, 18) instanceof ConstructBuild site && site.progress > 0.3f){
                    killed[0] = true;
                    run.kill(24, 18);
                }
            });
        }

        run.run(240);

        assertTrue(killed[0], "the site was destroyed half-way");
        assertEquals("sorter", block(16, 14));
        assertEquals("router", block(18, 14));
        var plans = new ArrayList<String>();
        for(var plan : state.teams.get(mindustry.game.Team.sharded).plans) plans.add(plan.block.name);
        assertEquals(List.of("copper-wall-large", "power-node", "copper-wall"), plans);
        assertEquals(5, run.calls.get("BuildDestroyed"), run.calls::toString);
    }

    /** {@code overwrote}: an upgraded conveyor keeps its items, a plastanium conveyor takes them as a stack, and a large battery keeps the charge of the batteries it covers. */
    @Test
    void upgradesKeepWhatTheyReplace(){
        stock("copper", 300, "lead", 300, "titanium", 100, "silicon", 100, "graphite", 50, "plastanium", 10);
        run.place("conveyor", 14, 14, 0);
        run.place("conveyor", 14, 18, 0);
        for(int y : new int[]{14, 18}){
            for(String item : List.of("copper", "lead", "copper")) run.handleItem(14, y, item);
        }
        for(int x = 20; x <= 22; x++){
            for(int y = 14; y <= 16; y++){
                run.place("battery", x, y, 0);
                run.status(x, y, 0.25f + 0.05f * (x - 20) + 0.1f * (y - 14));
            }
        }
        int alpha = alpha(18, 20);
        run.build(alpha, "titanium-conveyor", 14, 14, 0);
        run.build(alpha, "plastanium-conveyor", 14, 18, 0);
        run.build(alpha, "battery-large", 21, 15, 0);

        int last = run.until(600, () -> run.shardUnits.getFirst().plans.isEmpty());
        assertTrue(last < 600, "the queue emptied");
        run.chunks();

        assertEquals("titanium-conveyor", block(14, 14));
        assertEquals("plastanium-conveyor", block(14, 18));
        assertEquals("battery-large", block(21, 15));
        assertEquals(3, ((Conveyor)run.ours(14, 14)).len);
        assertTrue(world.build(14, 18).items.total() > 0, "the stack took the belt's items");
        assertTrue(world.build(21, 15).power.status > 0.1f, "the charge carried over");
    }
}
