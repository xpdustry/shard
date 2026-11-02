package com.xpdustry.shard.compat.v160.blocks.distribution;

import com.xpdustry.shard.compat.v160.blocks.transport.*;
import org.junit.jupiter.api.*;

/**
 * Gate (WP32): junctions, sorters, overflow gates and unloaders route items as vanilla's do, compared after every
 * frame by {@link BlockRun} (each building's state, every item the voids take) and by save chunk at the end. Rotations:
 * 0 is +x, 1 is +y, 2 is -x, 3 is -y.
 */
class DistributionOracleTest{
    static final int FRAMES = 600;

    @BeforeAll
    static void recordVoids(){
        BlockRun.recordVoids();
    }

    @AfterAll
    static void restoreVoids(){
        BlockRun.restoreVoids();
    }

    /**
     * Three lines cross through two junctions; the east void turns off so the east queues fill and wait, then on again,
     * and logic disables one junction for a while.
     */
    @Test
    void junctions(){
        var run = new BlockRun(16, 12);
        run.place("router", 15, 11);
        run.place("item-source", 1, 5).line("conveyor", 2, 5, 0, 3).place("junction", 5, 5).line("conveyor", 6, 5, 0, 3)
            .place("junction", 9, 5).line("titanium-conveyor", 10, 5, 0, 3).place("item-void", 13, 5);
        run.place("item-source", 5, 1).line("conveyor", 5, 2, 1, 3).line("conveyor", 5, 6, 1, 3).place("item-void", 5, 9);
        run.place("item-source", 9, 10).line("conveyor", 9, 9, 3, 3).line("conveyor", 9, 4, 3, 2).place("junction", 9, 2)
            .place("item-void", 9, 1);
        run.configure(1, 5, "copper").configure(5, 1, "lead").configure(9, 10, "silicon");
        run.at(200, () -> run.enabled(13, 5, false, 15, 11));
        run.at(350, () -> run.enabled(13, 5, true, 15, 11));
        run.at(400, () -> run.enabled(5, 5, false, 15, 11));
        run.at(480, () -> run.enabled(5, 5, true, 15, 11));
        run.expectArrivals(45).run(FRAMES);
    }

    /**
     * A sorter and an inverted sorter, each fed two items, with a conveyor ahead and voids on both sides; their item
     * changes, is cleared and set again, and logic disables each for a while, which swaps the cases. A third line ends
     * in a chain of three sorters, which instant-transfer blocks can't pass through.
     */
    @Test
    void sorters(){
        var run = new BlockRun(16, 14);
        run.place("router", 15, 13);
        for(int y : new int[]{3, 9}){
            String sorter = y == 3 ? "sorter" : "inverted-sorter";
            run.place("item-source", 1, y).line("conveyor", 2, y, 0, 3).place("item-source", 3, y - 1);
            run.place(sorter, 5, y).line("conveyor", 6, y, 0, 2).place("item-void", 8, y);
            run.line("conveyor", 5, y + 1, 1, 1).place("item-void", 5, y + 2).place("item-void", 5, y - 1);
            run.configure(1, y, "copper").configure(3, y - 1, "lead").configure(5, y, "copper");
        }
        run.at(150, () -> run.configure(5, 3, "lead"));
        run.at(200, () -> run.configure(5, 9, null));
        run.at(260, () -> run.configure(5, 3, null));
        run.at(320, () -> run.configure(5, 9, "lead"));
        run.at(380, () -> run.configure(5, 3, "copper"));
        run.at(420, () -> run.enabled(5, 3, false, 15, 13));
        run.at(440, () -> run.enabled(5, 9, false, 15, 13));
        run.at(520, () -> run.enabled(5, 3, true, 15, 13));
        run.place("item-source", 9, 6).line("conveyor", 10, 6, 0, 1);
        run.place("sorter", 11, 6).place("sorter", 12, 6).place("sorter", 13, 6).place("item-void", 14, 6);
        run.place("item-void", 12, 7).place("item-void", 12, 5);
        run.configure(9, 6, "scrap").configure(11, 6, "scrap").configure(12, 6, "scrap").configure(13, 6, "scrap");
        run.expectArrivals(60).run(FRAMES);
    }

    /**
     * An overflow and an underflow gate between a line and three voids: the front void and one side void turn off
     * and on, and logic disables each gate for a while. A gate feeding a sorter shows two instant-transfer blocks
     * refusing each other.
     */
    @Test
    void gates(){
        var run = new BlockRun(16, 14);
        run.place("router", 15, 13);
        for(int y : new int[]{3, 9}){
            String gate = y == 3 ? "overflow-gate" : "underflow-gate";
            run.place("item-source", 1, y).line("conveyor", 2, y, 0, 3).place(gate, 5, y).line("conveyor", 6, y, 0, 2).place("item-void", 8, y);
            run.place("item-void", 5, y + 1).line("conveyor", 5, y - 1, 3, 1).place("item-void", 5, y - 2);
            run.configure(1, y, y == 3 ? "copper" : "lead");
            int front = y;
            run.at(100, () -> run.enabled(8, front, false, 15, 13));
            run.at(220, () -> run.enabled(5, front + 1, false, 15, 13));
            run.at(300, () -> run.enabled(8, front, true, 15, 13));
            run.at(380, () -> run.enabled(5, front, false, 15, 13));
            run.at(460, () -> run.enabled(5, front, true, 15, 13));
        }
        run.place("item-source", 10, 6).line("conveyor", 11, 6, 0, 1).place("overflow-gate", 12, 6).place("sorter", 13, 6)
            .place("item-void", 14, 6).place("item-void", 12, 7).place("item-void", 13, 5);
        run.configure(10, 6, "silicon").configure(13, 6, "silicon");
        run.expectArrivals(60).run(FRAMES);
    }

    /**
     * Unloader rotation fairness, unloading containers (WP23's {@code Storage}). A nulloader between two
     * containers and two conveyors to voids rotates through copper, lead and titanium and alternates outlets; a second,
     * configured to copper, gives one container's copper to three conveyors in turn by {@code lastUsed}; a third feeds
     * a router. Mid-run the first is configured, cleared and disabled by logic; a neighbour of the second is removed and
     * placed again, so its stats return to the pool and come back with their old {@code lastUsed}; the second is cleared
     * and its container restocked with silicon.
     */
    @Test
    void unloaders(){
        var run = new BlockRun(18, 12);
        run.place("router", 17, 11);
        run.place("unloader", 6, 6).place("container", 4, 6).place("container", 6, 7);
        run.line("conveyor", 7, 6, 0, 1).place("item-void", 8, 6).line("conveyor", 6, 5, 3, 1).place("item-void", 6, 4);
        run.stock(4, 6, "copper", 40).stock(4, 6, "lead", 30).stock(6, 7, "lead", 20).stock(6, 7, "titanium", 10);
        run.place("unloader", 12, 6).place("container", 10, 6).stock(10, 6, "copper", 120);
        run.line("conveyor", 12, 7, 1, 1).place("item-void", 12, 8).line("titanium-conveyor", 13, 6, 0, 1).place("item-void", 14, 6)
            .line("conveyor", 12, 5, 3, 1).place("item-void", 12, 4);
        run.configure(12, 6, "copper");
        run.place("unloader", 14, 2).place("container", 12, 2).stock(12, 2, "graphite", 60).place("router", 15, 2)
            .place("item-void", 16, 2).place("item-void", 15, 1);
        run.at(150, () -> run.configure(6, 6, "lead"));
        run.at(250, () -> run.configure(6, 6, null));
        run.at(300, () -> run.enabled(6, 6, false, 17, 11));
        run.at(360, () -> run.enabled(6, 6, true, 17, 11));
        run.at(400, () -> run.remove(13, 6));
        run.at(430, () -> run.place("conveyor", 13, 6, 0));
        run.at(460, () -> run.configure(12, 6, null));
        run.at(500, () -> run.stock(10, 6, "silicon", 50));
        run.expectArrivals(150).run(FRAMES);
    }
}
