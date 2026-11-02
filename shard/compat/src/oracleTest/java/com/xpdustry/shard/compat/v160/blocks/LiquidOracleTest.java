package com.xpdustry.shard.compat.v160.blocks;

import mindustry.entities.*;
import mindustry.game.*;
import mindustry.gen.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP36): liquid blocks move liquid as vanilla's do, frame by frame for {@value #FRAMES} frames per fixture, then
 * through vanilla's save for {@value LiquidRun#AFTER_LOAD} more ({@link LiquidRun} lists what is compared). Sources,
 * conduits (plain, pulse, plated), a liquid router, a container and a tank, a junction, a reinforced bridge conduit and
 * voids carry liquid from end to end; mixed liquids meet and react, set fires and boil off; conduits are broken
 * mid-flow and leak into puddles.
 */
class LiquidOracleTest{
    static final int FRAMES = 600;

    @BeforeAll
    static void recordVoids(){
        LiquidRun.recordVoids();
    }

    @AfterAll
    static void restoreVoids(){
        LiquidRun.restoreVoids();
    }

    /**
     * Water from a source through conduits, a router (which also fills a container beside it), a junction that an oil
     * line crosses, pulse conduits, a chain of reinforced bridge conduits, a tank and plated conduits into a void; the oil line
     * ends in its own void. The water source is cleared and set again, the oil source switches to slag, the last void is
     * disabled by logic for a while (the line backs up into the tank) and so is the junction (both lines stop there).
     */
    @Test
    void sourceToVoidThroughEveryKind(){
        var run = new LiquidRun(30, 12);
        run.place("liquid-source", 1, 5).line("conduit", 2, 5, 0, 4).place("liquid-router", 6, 5).place("liquid-container", 6, 3)
            .line("conduit", 7, 5, 0, 2).place("liquid-junction", 9, 5)
            .place("liquid-source", 9, 2).line("conduit", 9, 3, 1, 2).line("conduit", 9, 6, 1, 2).place("liquid-void", 9, 8)
            .place("conduit", 10, 5, 0).line("pulse-conduit", 11, 5, 0, 2)
            .place("reinforced-bridge-conduit", 13, 5, 0).place("reinforced-bridge-conduit", 15, 5, 0)
            .place("reinforced-bridge-conduit", 17, 5, 0)
            // a conduit into the middle bridge from the side its feeder occupies, which it refuses even while it drains
            .place("liquid-source", 14, 6).place("conduit", 14, 5, 0)
            .place("conduit", 18, 5, 0).place("liquid-tank", 20, 5)
            .line("plated-conduit", 22, 5, 0, 2).place("liquid-void", 24, 5);
        run.configure(1, 5, "water").configure(9, 2, "oil").configure(14, 6, "water");
        run.at(150, () -> run.configure(1, 5, null))
            .at(200, () -> run.configure(1, 5, "water"))
            .at(300, () -> run.configure(9, 2, "slag"))
            .at(380, () -> run.enabled(24, 5, false))
            .at(470, () -> run.enabled(24, 5, true))
            .at(500, () -> run.enabled(9, 5, false))
            .at(560, () -> run.enabled(9, 5, true));
        run.run(FRAMES);

        var arrivals = LiquidRun.vanillaArrivals;
        assertTrue(arrivals.stream().anyMatch(a -> a.x() == 24 && a.liquid().equals("water")), "water reached the far void");
        assertTrue(arrivals.stream().anyMatch(a -> a.x() == 9 && a.liquid().equals("oil")), "oil crossed the junction");
        assertTrue(arrivals.stream().anyMatch(a -> a.x() == 9 && a.liquid().equals("slag")), "then slag");
        var whileDisabled = arrivals.stream().filter(a -> a.x() == 24 && a.frame() > 380 && a.frame() < 470).toList();
        assertTrue(whileDisabled.isEmpty(), "a disabled void takes nothing: " + whileDisabled);
    }

    /**
     * Mixed liquids meeting. Oil pushed into a router full of slag burns both (vanilla's flammable-and-hot reaction)
     * until the oil conduit breaks, explodes and spills; water pushed into slag boils off; cryofluid pushed into a tank
     * of oil does nothing; an oil leak and a slag leak meet on one tile, and the puddle catches fire, which spreads to
     * conduits that carry oil.
     */
    @Test
    void mixedLiquidsReactAndBurn(){
        var run = new LiquidRun(30, 30);
        // oil into slag
        run.place("liquid-source", 2, 10).line("conduit", 3, 10, 0, 5).place("liquid-router", 8, 10)
            .place("liquid-source", 8, 13).line("conduit", 8, 12, 3, 2);
        // water into slag
        run.place("liquid-source", 2, 20).line("conduit", 3, 20, 0, 4).place("liquid-router", 7, 20)
            .place("liquid-source", 7, 23).line("conduit", 7, 22, 3, 2);
        // cryofluid into oil
        run.place("liquid-source", 2, 26).line("conduit", 3, 26, 0, 3).place("liquid-container", 6, 26)
            .place("liquid-source", 8, 26);
        // an oil leak and a slag leak on one tile, next to oil conduits
        run.place("liquid-source", 18, 5).line("conduit", 19, 5, 0, 2)
            .place("liquid-source", 21, 9).line("conduit", 21, 8, 3, 3)
            .place("liquid-source", 24, 7).line("conduit", 23, 7, 2, 1).place("conduit", 22, 7, 3);
        run.configure(8, 13, "slag").configure(7, 23, "slag").configure(8, 26, "oil");
        run.at(40, () -> {
            run.configure(2, 10, "oil");
            run.configure(2, 20, "water");
            run.configure(2, 26, "cryofluid");
            run.configure(18, 5, "oil");
            run.configure(24, 7, "oil");
        });
        run.at(120, () -> run.configure(21, 9, "slag"));
        run.at(260, () -> run.configure(21, 9, null));

        var burnt = new TreeSet<Integer>();
        var damaged = new TreeSet<String>();
        for(int frame = 41; frame <= FRAMES; frame++){
            run.at(frame, () -> {
                for(var entity : Groups.all) if(entity instanceof Fire f) burnt.add(f.tile.pos());
                for(var build : run.buildings()) if(build.health < build.maxHealth) damaged.add(build.block.name + "@" + build.tile.x + "," + build.tile.y);
            });
        }
        run.run(FRAMES);

        assertNull(world.build(7, 10), "the oil conduit burnt with the slag router and broke");
        assertTrue(damaged.contains("liquid-router@8,10"), "the slag router burnt too: " + damaged);
        assertTrue(damaged.stream().noneMatch(name -> name.endsWith("@6,20")), "water and slag don't burn: " + damaged);
        assertTrue(burnt.size() > 2, "the oil caught fire and the fire spread: " + burnt);
    }

    /**
     * Conduits broken mid-flow. A water line loses a conduit, so the one behind leaks into a puddle; another is killed
     * (its water splashes) and the gap rebuilt. A killed oil conduit explodes into fire. A plated line loses a conduit
     * and doesn't leak; a router beside it can't feed it from the side, a conduit can.
     */
    @Test
    void brokenConduitsLeak(){
        var run = new LiquidRun(30, 24);
        run.place("liquid-source", 1, 5).line("conduit", 2, 5, 0, 19).place("liquid-void", 21, 5);
        run.place("liquid-source", 1, 10).line("conduit", 2, 10, 0, 12);
        run.place("liquid-source", 1, 15).line("plated-conduit", 2, 15, 0, 12).place("liquid-void", 14, 15)
            .place("liquid-source", 5, 17).place("conduit", 5, 16, 3)
            .place("liquid-router", 8, 16).place("liquid-source", 8, 17);
        run.configure(1, 5, "water").configure(1, 10, "oil").configure(1, 15, "water").configure(5, 17, "water").configure(8, 17, "water");
        run.at(200, () -> run.remove(10, 5))
            .at(200, () -> run.remove(11, 15))
            .at(260, () -> {
                // overfilled, so its explosion throws fireballs (flammability / 4 of them)
                run.pour(8, 10, "oil", 20f);
                run.kill(8, 10);
            })
            .at(300, () -> run.kill(6, 5))
            .at(420, () -> run.place("conduit", 10, 5, 0))
            .at(450, () -> run.place("conduit", 6, 5, 0));

        var leaked = new boolean[3];
        run.at(250, () -> leaked[0] = world.tiles.getPuddle(world.tile(10, 5).array()) != null);
        run.at(250, () -> leaked[1] = world.tiles.getPuddle(world.tile(11, 15).array()) != null);
        // nothing in this fixture is hot, so a fire after the kill is the explosion's fireball
        run.every(261, 400, 1, () -> {
            for(var entity : Groups.all) if(entity instanceof Fire || entity instanceof Bullet b && b.type == mindustry.content.Bullets.fireball) leaked[2] = true;
        });
        run.run(FRAMES);

        assertTrue(leaked[0], "the conduit behind the gap leaked into a puddle");
        assertFalse(leaked[1], "a plated conduit doesn't leak");
        assertTrue(leaked[2], "the killed oil conduit threw a fireball or set a fire");
        assertTrue(LiquidRun.vanillaArrivals.stream().anyMatch(a -> a.x() == 21 && a.frame() > 460), "the line flows again once rebuilt");
    }

    /**
     * Bridge conduits in a chain from a water source: the first two linked, the last unlinked, dumping into a conduit
     * toward a void and into a tank beside it. The middle link is cut and made again, the middle bridge is disabled by
     * logic for a while, and a crux bridge in range can't take a link. A powered phase conduit carries oil across the map
     * until its power source is removed, and its warmup decays.
     */
    @Test
    void bridgesCarryAcrossGaps(){
        var run = new LiquidRun(30, 16);
        run.place("liquid-source", 1, 5).place("conduit", 2, 5, 0).place("bridge-conduit", 3, 5).place("bridge-conduit", 7, 5)
            .place("bridge-conduit", 10, 5).line("conduit", 11, 5, 0, 2).place("liquid-void", 13, 5).place("liquid-container", 10, 6)
            .place("bridge-conduit", 10, 2, 0, Team.crux);
        run.place("liquid-source", 1, 10).place("phase-conduit", 2, 10).place("power-source", 2, 11).place("phase-conduit", 13, 10)
            .place("liquid-void", 14, 10);
        run.configure(1, 5, "water").configure(1, 10, "oil");
        run.link(3, 5, 7, 5).link(7, 5, 10, 5).link(2, 10, 13, 10);
        run.at(150, () -> run.link(7, 5, -1, -1))
            .at(150, () -> run.link(10, 5, 10, 2))
            .at(220, () -> run.link(7, 5, 10, 5))
            .at(300, () -> run.enabled(7, 5, false))
            .at(380, () -> run.enabled(7, 5, true))
            .at(450, () -> run.remove(2, 11));
        run.run(FRAMES);

        var arrivals = LiquidRun.vanillaArrivals;
        assertTrue(arrivals.stream().anyMatch(a -> a.x() == 13 && a.y() == 5 && a.liquid().equals("water")), "water crossed the bridges");
        assertTrue(arrivals.stream().anyMatch(a -> a.x() == 14 && a.liquid().equals("oil") && a.fromX() == 13), "oil crossed the phase conduit");
        assertTrue(arrivals.stream().noneMatch(a -> a.x() == 14 && a.frame() > 520), "an unpowered phase conduit stops: "
            + arrivals.stream().filter(a -> a.x() == 14 && a.frame() > 520).limit(3).toList());
        assertTrue(run.vanilla(10, 6).liquids.currentAmount() > 0f, "the last bridge dumped into the container too");
    }
}
