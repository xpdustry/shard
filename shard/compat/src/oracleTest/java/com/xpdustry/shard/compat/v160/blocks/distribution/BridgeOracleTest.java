package com.xpdustry.shard.compat.v160.blocks.distribution;

import com.xpdustry.shard.compat.v160.blocks.transport.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP33): bridge conveyors and phase conveyors move items as vanilla's do, compared after every frame by
 * {@link BlockRun} (each building's state with its links, incoming bridges, buffers and timers; every item the voids
 * take; the random) and by save chunk at the end, over {@link #FRAMES} frames; then vanilla saves, both load that
 * save and run on for {@link #AFTER_LOAD} frames, compared as before. Links are configured as the server's
 * {@code configureAny} sends them: an {@code Integer} (packed) or a {@code Point2} (relative).
 */
class BridgeOracleTest{
    static final int FRAMES = 600;
    /** Frames both run on after loading vanilla's save of the fixture. */
    static final int AFTER_LOAD = 300;

    @BeforeAll
    static void recordVoids(){
        BlockRun.recordVoids();
    }

    @AfterAll
    static void restoreVoids(){
        BlockRun.restoreVoids();
    }

    /** {@code configureAny(Point2.pack(tx, ty))}: the absolute link, as an {@code Integer}. */
    static void link(BlockRun run, int x, int y, int tx, int ty){
        run.configureAny(x, y, arc.math.geom.Point2.pack(tx, ty), com.xpdustry.shard.common.geom.Point2.pack(tx, ty));
    }

    /** {@code configureAny(new Point2(dx, dy))}: the link relative to the bridge. */
    static void relative(BlockRun run, int x, int y, int dx, int dy){
        run.configureAny(x, y, new arc.math.geom.Point2(dx, dy), new com.xpdustry.shard.common.geom.Point2(dx, dy));
    }

    static void compareRandom(BlockRun run){
        run.pinRandom().compareAlso(
            () -> "random " + arc.math.Mathf.rand.getState(0) + "," + arc.math.Mathf.rand.getState(1),
            () -> "random " + run.shard.rand().getState(0) + "," + run.shard.rand().getState(1));
    }

    /**
     * Two bridge-conveyor chains carrying items opposite ways: three links east along y = 2 and two links west along
     * y = 6. The east chain also takes a side feed into a middle bridge, and that bridge refuses a conveyor pushing at
     * it from its link's side until it links elsewhere. Mid-run a middle bridge relinks onto the west chain (so the two merge), then back by a relative
     * {@code Point2}; logic disables a bridge for a while; then a bridge of the east chain is removed (its feeder
     * holds a broken link and dumps), and placed again unlinked.
     */
    @Test
    void bridgeConveyorChains(){
        var run = new BlockRun(16, 10);
        run.place("router", 15, 9);
        run.place("item-source", 1, 2).place("bridge-conveyor", 2, 2).place("bridge-conveyor", 5, 2).place("bridge-conveyor", 8, 2)
            .place("bridge-conveyor", 11, 2).place("item-void", 12, 2);
        run.place("item-source", 8, 0).place("conveyor", 8, 1, 1);
        // under the span, pushing at (8, 2) from the side its link is on: refused until it links elsewhere
        run.place("item-source", 10, 2).place("conveyor", 9, 2, 2);
        run.place("item-source", 13, 6).place("bridge-conveyor", 12, 6).place("bridge-conveyor", 8, 6).place("bridge-conveyor", 4, 6)
            .place("item-void", 3, 6).place("item-void", 4, 7);
        run.configure(1, 2, "copper").configure(8, 0, "lead").configure(10, 2, "sand").configure(13, 6, "silicon");
        link(run, 2, 2, 5, 2);
        link(run, 5, 2, 8, 2);
        link(run, 8, 2, 11, 2);
        link(run, 12, 6, 8, 6);
        relative(run, 8, 6, -4, 0);
        compareRandom(run);
        run.at(150, () -> link(run, 8, 2, 8, 6));
        run.at(260, () -> relative(run, 8, 2, 3, 0));
        run.at(300, () -> run.enabled(11, 2, false, 15, 9));
        run.at(360, () -> run.enabled(11, 2, true, 15, 9));
        run.at(420, () -> run.remove(5, 2));
        run.at(500, () -> run.place("bridge-conveyor", 5, 2));
        run.expectArrivals(80).run(FRAMES);
        run.continueFromSave(AFTER_LOAD);
        var arrivals = run.arrivals();
        assertTrue(arrivals.stream().anyMatch(a -> a.x() == 12 && a.item().equals("lead")), "the side feed crossed the east chain");
        assertTrue(arrivals.stream().anyMatch(a -> a.x() == 3 && a.item().equals("copper")), "the relink sent copper west");
        assertTrue(arrivals.stream().noneMatch(a -> a.item().equals("sand") && a.frame() <= 150), "the bridge refused sand from its link's side");
        assertTrue(arrivals.stream().anyMatch(a -> a.item().equals("sand")), "linked south, the bridge took sand from the east");
    }

    /**
     * Phase conveyors, each powered by a power source beside it: a chain of four links east along y = 2 (twelve tiles
     * each at most), and two links west along y = 6. The power source of a middle bridge goes away for a while, so it
     * keeps its link but stops (its warmup falls, items queue behind it), then comes back. A bridge's link to a
     * different block, to a bridge out of line and back onto itself are invalid and ignored.
     */
    @Test
    void phaseConveyorsLosePower(){
        var run = new BlockRun(30, 10);
        run.place("router", 29, 9);
        int[] east = {2, 9, 15, 22, 27};
        run.place("item-source", 1, 2).place("item-void", 28, 2);
        for(int x : east) run.place("phase-conveyor", x, 2).place("power-source", x, 3);
        for(int i = 0; i < east.length - 1; i++) link(run, east[i], 2, east[i + 1], 2);
        run.place("item-source", 26, 6).place("phase-conveyor", 25, 6).place("phase-conveyor", 14, 6).place("phase-conveyor", 4, 6)
            .place("item-void", 3, 6);
        for(int x : new int[]{25, 14, 4}) run.place("power-source", x, 7);
        link(run, 25, 6, 14, 6);
        relative(run, 14, 6, -10, 0);
        run.configure(1, 2, "titanium").configure(26, 6, "graphite");
        // invalid links: a router, out of line, itself
        run.at(100, () -> link(run, 4, 6, 3, 6));
        run.at(130, () -> link(run, 4, 6, 9, 2));
        run.at(160, () -> relative(run, 4, 6, 0, 0));
        compareRandom(run);
        run.at(200, () -> run.remove(15, 3));
        run.at(380, () -> run.place("power-source", 15, 3));
        run.expectArrivals(100).run(FRAMES);
        run.continueFromSave(AFTER_LOAD);
        var arrivals = run.arrivals();
        assertTrue(arrivals.stream().anyMatch(a -> a.x() == 28 && a.frame() > 400), "the east chain moves again with power back");
        assertTrue(arrivals.stream().noneMatch(a -> a.x() == 28 && a.frame() > 260 && a.frame() < 380), "the east chain stalls without power");
        assertTrue(arrivals.stream().anyMatch(a -> a.x() == 3), "the west chain delivers");
    }

    /**
     * A world processor builds a bridge pair and a mass driver with {@code setblock}, then every update tries
     * {@code control config} on them (a building, a packed position, a number): vanilla ignores it, as neither block is
     * {@code logicConfigurable} (only content configs make one, Block.java:1482), so nothing links. It toggles one
     * bridge with {@code control enabled} and writes the driver's {@code @progress} and a bridge's {@code @totalItems}
     * to a cell, compared every frame. At 200 the server links the placed pair, and items cross.
     */
    @Test
    void worldProcessorSetblockAndControl(){
        var run = new BlockRun(16, 12);
        run.sim.rules(r -> {
            r.allowEditWorldProcessors = true;
            r.worldProcessorPlayerLink = true;
        });
        var rules = run.shard.buildings().rules();
        rules.allowEditWorldProcessors = true;
        rules.worldProcessorPlayerLink = true;
        run.place("item-source", 2, 2).place("item-void", 3, 3).place("item-void", 7, 2).place("world-cell", 1, 10);
        run.configure(2, 2, "copper");
        String code = """
            setblock block @bridge-conveyor 3 2 @sharded 0
            setblock block @bridge-conveyor 6 2 @sharded 0
            setblock block @mass-driver 10 6 @sharded 0
            getblock building a 3 2
            getblock building b 6 2
            getblock building d 10 6
            control config a b
            control config a 393218
            control config d 5
            op mod t @tick 240
            op lessThan e t 120
            control enabled b e
            sensor p d @progress
            sensor n a @totalItems
            write p cell1 0
            write n cell1 1
            """;
        run.place("world-processor", 14, 10);
        run.configureAny(14, 10, mindustry.world.blocks.logic.LogicBlock.compress(code, new arc.struct.Seq<>()),
            com.xpdustry.shard.logic.LogicConfig.compress(code, java.util.List.of()));
        link(run, 14, 10, 1, 10);
        run.compareAlso(
            () -> "cell " + java.util.Arrays.toString(numberMemory(run.sim.build(1, 10))),
            () -> "cell " + java.util.Arrays.toString(((com.xpdustry.shard.sim.blocks.logic.MemoryBlock)run.shard.buildings().at(1, 10)).numbers));
        run.at(150, () -> {
            assertEquals(-1, ((mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild)run.sim.build(3, 2)).link, "vanilla: control config does nothing");
            assertEquals(-1, ((com.xpdustry.shard.sim.blocks.distribution.ItemBridgeBuild)run.shard.buildings().at(3, 2)).link, "shard: control config does nothing");
        });
        run.at(200, () -> link(run, 3, 2, 6, 2));
        run.expectArrivals(20).run(FRAMES);
        run.continueFromSave(AFTER_LOAD);
        var arrivals = run.arrivals();
        assertTrue(arrivals.stream().anyMatch(a -> a.x() == 7 && a.frame() > 200), "the placed pair carried items once linked");
        assertTrue(arrivals.stream().noneMatch(a -> a.x() == 7 && a.frame() <= 200), "nothing crossed before the server linked them");
    }

    /** A vanilla memory cell's private {@code numberMemory}. */
    static double[] numberMemory(mindustry.gen.Building cell){
        try{
            var field = mindustry.world.blocks.logic.MemoryBlock.MemoryBuild.class.getDeclaredField("numberMemory");
            field.setAccessible(true);
            return (double[])field.get(cell);
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }
}
