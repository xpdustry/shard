package com.xpdustry.shard.compat.v160.blocks.transport;

import org.junit.jupiter.api.*;

/**
 * Erekir's ducts move items as vanilla's do: ducts and armored ducts, duct routers, overflow and underflow ducts and
 * duct bridges, compared after every frame by {@link BlockRun} and by save chunk at the end, over {@link #FRAMES}
 * frames. Rotations: 0 is +x, 1 is +y, 2 is -x, 3 is -y.
 */
class DuctOracleTest{
    static final int FRAMES = 600, AFTER_LOAD = 300;

    @BeforeAll
    static void recordVoids(){
        BlockRun.recordVoids();
    }

    @AfterAll
    static void restoreVoids(){
        BlockRun.restoreVoids();
    }

    /**
     * A duct line with a turn and a side feed, and an armored line that refuses a source on its side but takes a duct
     * pointing into it; logic disables one duct for a while.
     */
    @Test
    void ducts(){
        var run = new BlockRun(16, 12);
        run.place("router", 15, 11);
        run.place("item-source", 1, 2).line("duct", 2, 2, 0, 4).line("duct", 6, 2, 1, 3).line("duct", 6, 5, 0, 3).place("item-void", 9, 5);
        run.place("item-source", 4, 3);
        run.place("item-source", 1, 8).line("armored-duct", 2, 8, 0, 6).place("item-void", 8, 8);
        run.place("item-source", 3, 9).place("item-source", 5, 6).line("duct", 5, 7, 1, 1);
        run.configure(1, 2, "beryllium").configure(4, 3, "graphite").configure(1, 8, "sand").configure(3, 9, "silicon")
            .configure(5, 6, "tungsten");
        run.at(200, () -> run.enabled(6, 3, false, 15, 11));
        run.at(320, () -> run.enabled(6, 3, true, 15, 11));
        run.expectArrivals(60).run(FRAMES);
    }

    /**
     * Duct unloaders: one takes any item from a container in turn, one only the item it is set to, then cleared; one
     * behind a core takes nothing (no {@code allowCoreUnload}). Vanilla saves; both load it and unload on, its offset
     * and timer from the save, for {@link #AFTER_LOAD} frames.
     */
    @Test
    void unloaders(){
        var run = new BlockRun(16, 14);
        run.place("router", 15, 13);
        run.place("container", 2, 2).stock(2, 2, "beryllium", 300).stock(2, 2, "graphite", 300).stock(2, 2, "sand", 300);
        run.place("duct-unloader", 4, 2, 0).line("duct", 5, 2, 0, 3).place("item-void", 8, 2);
        run.place("duct-unloader", 2, 4, 1).line("duct", 2, 5, 1, 2).place("item-void", 2, 7);
        run.configure(2, 4, "graphite");
        run.at(240, () -> run.configure(2, 4, null));
        run.place("core-bastion", 11, 9).stock(11, 9, "beryllium", 200);
        run.place("duct-unloader", 14, 9, 0).place("item-void", 15, 9);
        run.expectArrivals(60).run(FRAMES);
        run.continueFromSave(AFTER_LOAD);
    }

    /**
     * A duct router sorting one item forward and the rest sideways, reconfigured and cleared; an overflow and an
     * underflow duct whose outlets turn off and on; and a chain of duct bridges over a gap, fed from behind, into
     * a middle bridge from the side, and from under the span, which that bridge refuses.
     */
    @Test
    void routing(){
        var run = new BlockRun(18, 16);
        run.place("router", 17, 15);
        run.place("item-source", 1, 2).line("duct", 2, 2, 0, 2).place("duct-router", 4, 2).place("item-void", 5, 2)
            .place("item-void", 4, 3).place("item-void", 4, 1);
        run.place("item-source", 3, 3);
        run.configure(1, 2, "beryllium").configure(3, 3, "graphite").configure(4, 2, "beryllium");
        run.at(200, () -> run.configure(4, 2, "graphite"));
        run.at(350, () -> run.configure(4, 2, null));
        for(int y : new int[]{6, 10}){
            String duct = y == 6 ? "overflow-duct" : "underflow-duct";
            run.place("item-source", 1, y).line("duct", 2, y, 0, 2).place(duct, 4, y).place("item-void", 5, y)
                .place("item-void", 4, y + 1).place("item-void", 4, y - 1);
            run.configure(1, y, "silicon");
            int at = y;
            run.at(150, () -> run.enabled(5, at, false, 17, 15));
            run.at(250, () -> run.enabled(4, at + 1, false, 17, 15));
            run.at(400, () -> run.enabled(5, at, true, 17, 15));
        }
        run.place("item-source", 7, 13).line("duct", 8, 13, 0, 1).place("duct-bridge", 9, 13).place("duct-bridge", 12, 13)
            .place("duct-bridge", 15, 13).place("item-void", 16, 13);
        run.place("item-source", 12, 11).line("duct", 12, 12, 1, 1);
        // under the span: the middle bridge refuses it, as the side its feeder links from is occupied
        run.place("item-source", 11, 13);
        run.configure(7, 13, "sand").configure(12, 11, "tungsten").configure(11, 13, "graphite");
        run.at(300, () -> run.remove(12, 13));
        run.at(360, () -> run.place("duct-bridge", 12, 13));
        run.expectArrivals(60).run(FRAMES);
    }

    /**
     * A surge router, unpowered so at its base efficiency, filled through a duct and unloading whole stacks to a void
     * ahead and one to the side, which turns off for a while; then it is configured to send one item forward only.
     */
    @Test
    void surgeRouter(){
        var run = new BlockRun(12, 10);
        run.place("router", 11, 9);
        run.place("item-source", 1, 4).line("duct", 2, 4, 0, 2).place("surge-router", 4, 4).place("item-void", 5, 4)
            .place("item-void", 4, 5).line("duct", 4, 3, 3, 1).place("item-void", 4, 2);
        run.place("item-source", 3, 5);
        run.configure(1, 4, "silicon").configure(3, 5, "graphite");
        run.at(150, () -> run.enabled(4, 5, false, 11, 9));
        run.at(300, () -> run.enabled(4, 5, true, 11, 9));
        run.at(350, () -> run.configure(4, 4, "silicon"));
        run.expectArrivals(40).run(FRAMES);
    }
}
