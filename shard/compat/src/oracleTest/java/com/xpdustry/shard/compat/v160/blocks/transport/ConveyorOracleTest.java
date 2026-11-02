package com.xpdustry.shard.compat.v160.blocks.transport;

import mindustry.game.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP31): conveyors, armored and stack conveyors move items as vanilla's do. Each fixture runs {@link #FRAMES}
 * frames in vanilla and in Shard with the same edits, and {@link BlockRun} compares every building after every frame
 * (item ids and positions exactly, not to a tolerance), every item the voids take, and the save chunks at the end.
 * Rotations: 0 is +x, 1 is +y, 2 is -x, 3 is -y.
 */
class ConveyorOracleTest{
    static final int FRAMES = 600;

    @BeforeAll
    static void recordVoids(){
        BlockRun.recordVoids();
    }

    @AfterAll
    static void restoreVoids(){
        BlockRun.restoreVoids();
    }

    /** A basic and a titanium line from a source to a void, the titanium one fed lead and then sand. */
    @Test
    void straight(){
        var run = new BlockRun(14, 8);
        run.place("item-source", 1, 2).line("conveyor", 2, 2, 0, 9).place("item-void", 11, 2);
        run.place("item-source", 1, 5).line("titanium-conveyor", 2, 5, 0, 9).place("item-void", 11, 5);
        run.configure(1, 2, "copper").configure(1, 5, "lead");
        run.at(300, () -> run.configure(1, 5, "sand"));
        run.expectArrivals(FRAMES / 10).run(FRAMES);
    }

    /** A line that turns left, left again and then right, so it has curves both ways, ending in a void going down. */
    @Test
    void curve(){
        var run = new BlockRun(14, 14);
        run.place("item-source", 1, 2).line("conveyor", 2, 2, 0, 5);
        run.line("conveyor", 7, 2, 1, 5);
        run.line("conveyor", 7, 7, 2, 3);
        run.line("titanium-conveyor", 4, 7, 1, 3);
        run.line("titanium-conveyor", 4, 10, 0, 4);
        run.line("conveyor", 8, 10, 3, 3).place("item-void", 8, 7);
        run.configure(1, 2, "copper");
        run.expectArrivals(10).run(FRAMES);
    }

    /** Two lines joining a third from either side, and a fourth from behind through a curve: sides into the middle slot. */
    @Test
    void merge(){
        var run = new BlockRun(16, 14);
        run.place("item-source", 1, 6).line("conveyor", 2, 6, 0, 11).place("item-void", 13, 6);
        run.place("item-source", 6, 1).line("conveyor", 6, 2, 1, 4);
        run.place("item-source", 9, 12).line("titanium-conveyor", 9, 11, 3, 5);
        run.configure(1, 6, "copper").configure(6, 1, "lead").configure(9, 12, "silicon");
        run.at(200, () -> run.configure(1, 6, null));
        run.at(400, () -> run.configure(1, 6, "graphite"));
        run.expectArrivals(30).run(FRAMES);
    }

    /** Sources and a router beside a line feed its sides directly; the line ends in a void. */
    @Test
    void sideInput(){
        var run = new BlockRun(14, 8);
        run.line("conveyor", 2, 3, 0, 8).place("item-void", 10, 3);
        run.place("item-source", 3, 2).place("item-source", 5, 4).place("router", 7, 2).place("item-source", 7, 1);
        run.configure(3, 2, "copper").configure(5, 4, "lead").configure(7, 1, "scrap");
        run.expectArrivals(40).run(FRAMES);
    }

    /** An armored line takes from behind and from a conveyor on its side, but not from a source on its side. */
    @Test
    void armored(){
        var run = new BlockRun(14, 10);
        run.place("item-source", 1, 4).line("armored-conveyor", 2, 4, 0, 8).place("item-void", 10, 4);
        run.place("item-source", 4, 5).place("item-source", 6, 1).line("conveyor", 6, 2, 1, 2);
        run.place("router", 8, 3).place("item-source", 8, 2);
        run.configure(1, 4, "copper").configure(4, 5, "lead").configure(6, 1, "silicon").configure(8, 2, "titanium");
        run.at(150, () -> run.configure(1, 4, null));
        run.expectArrivals(FRAMES / 20).run(FRAMES);
    }

    /**
     * Stack conveyors: a plastanium line loaded from a conveyor and a side source, unloading into a router and a void
     * (its dump end), and a surge line, unpowered so at base efficiency, moving its stacks forward into a conveyor.
     */
    @Test
    void stack(){
        var run = new BlockRun(18, 10);
        run.place("item-source", 1, 2).line("conveyor", 2, 2, 0, 2).line("plastanium-conveyor", 4, 2, 0, 8);
        run.place("item-source", 4, 1).place("item-void", 12, 2).place("item-void", 11, 1).place("item-void", 11, 3);
        run.place("item-source", 1, 6).line("conveyor", 2, 6, 0, 2).line("surge-conveyor", 4, 6, 0, 7)
            .line("conveyor", 11, 6, 0, 3).place("item-void", 14, 6);
        run.configure(1, 2, "copper").configure(4, 1, "copper").configure(1, 6, "lead");
        run.at(250, () -> run.configure(1, 2, "coal"));
        run.at(260, () -> run.configure(4, 1, "coal"));
        run.expectArrivals(FRAMES / 30).run(FRAMES);
    }

    /**
     * M0's "a processor controls a conveyor line": a processor disables one conveyor of a line, so it and the line
     * behind it stop on that frame, and enables it again later; then the same for a stack conveyor. The disabler is
     * a router standing in for the processor, saved as {@code lastDisabler}.
     */
    @Test
    void logicEnabled(){
        var run = new BlockRun(16, 8);
        run.place("router", 14, 7);
        run.place("item-source", 1, 2).line("titanium-conveyor", 2, 2, 0, 10).place("item-void", 12, 2);
        run.place("item-source", 1, 5).line("conveyor", 2, 5, 0, 2).line("plastanium-conveyor", 4, 5, 0, 6).place("item-void", 10, 5);
        run.configure(1, 2, "copper").configure(1, 5, "lead");
        run.at(120, () -> run.enabled(6, 2, false, 14, 7));
        run.at(300, () -> run.enabled(6, 2, true, 14, 7));
        run.at(200, () -> run.enabled(7, 5, false, 14, 7));
        run.at(420, () -> run.enabled(7, 5, true, 14, 7));
        run.at(500, () -> run.enabled(11, 2, false, 14, 7));
        run.expectArrivals(FRAMES / 20).run(FRAMES);
    }

    /**
     * Lines that clog: the void at the end of a straight line and of a curved one turns off, so items back up and the
     * clog heat rises; a conveyor is removed from the middle of a line and placed again, and an empty line sleeps.
     */
    @Test
    void clogAndRemove(){
        var run = new BlockRun(14, 12);
        run.place("router", 13, 11);
        run.place("item-source", 1, 2).line("conveyor", 2, 2, 0, 6).place("item-void", 8, 2);
        run.place("item-source", 1, 5).line("conveyor", 2, 5, 0, 3).line("conveyor", 5, 5, 1, 3).place("item-void", 5, 8);
        run.line("conveyor", 2, 10, 0, 5);
        run.configure(1, 2, "copper").configure(1, 5, "lead");
        run.at(100, () -> run.enabled(8, 2, false, 13, 11));
        run.at(150, () -> run.enabled(5, 8, false, 13, 11));
        run.at(330, () -> run.enabled(8, 2, true, 13, 11));
        run.at(380, () -> run.remove(4, 2));
        run.at(440, () -> run.place("conveyor", 4, 2, 0));
        run.at(460, () -> run.place("conveyor", 3, 5, 1, Team.sharded));
        run.expectArrivals(15).run(FRAMES);
    }

    /**
     * Conveyors carry the ground units on them ({@code unitOn}): two daggers ride an empty line round a curve, a mace
     * rides a line until its items fill it, which then stops pushing, and logic disables a conveyor under one dagger
     * for a while. Units are compared every frame too.
     */
    @Test
    void carriesUnits(){
        var run = new BlockRun(20, 12);
        run.place("router", 19, 11);
        run.line("conveyor", 2, 3, 0, 8).line("titanium-conveyor", 10, 3, 1, 6);
        run.place("item-source", 1, 8).line("conveyor", 2, 8, 0, 8);
        run.configure(1, 8, "copper");
        run.spawn("dagger", 2, 3).spawn("dagger", 5, 3).spawn("mace", 6, 8);
        run.at(60, () -> run.enabled(7, 3, false, 19, 11));
        run.at(140, () -> run.enabled(7, 3, true, 19, 11));
        run.expectArrivals(0).run(FRAMES);
        var rider = run.vanillaUnits.get(0);
        assertTrue(rider.x > 9 * 8f && rider.y > 4 * 8f, "the first dagger rode round the curve: " + rider.x + "," + rider.y);
        assertEquals(0f, run.vanillaUnits.get(2).vel.len(), 1e-3f, "once its line is full, the mace isn't pushed any more");
    }
}
