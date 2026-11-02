package com.xpdustry.shard.compat.v160.blocks.turret;

import com.xpdustry.shard.oracle.*;
import mindustry.content.*;
import mindustry.game.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WP60's duel fixtures: each turret against walking or standing enemies, compared with vanilla after every frame by
 * {@link TurretRun} (exact: floats bit for bit, the random, entity ids, bullets) and by save chunk at the end. Each
 * fixture asserts that the turret fired and killed (or, for the wave, hit or put a fire out), so no fixture passes by
 * nothing happening.
 */
class TurretOracleTest{
    static final int SIZE = 48;

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    @Test
    void duoKillsDaggersAndAFlare(){
        try(var run = new TurretRun(SIZE)){
            run.place("duo", 20, 20, Team.sharded);
            run.every(120, () -> run.items(20, 20, "copper", 4));
            run.walker("dagger", Team.crux, 44 * 8f, 20 * 8f, 26 * 8f, 20 * 8f);
            run.walker("dagger", Team.crux, 44 * 8f, 26 * 8f, 26 * 8f, 24 * 8f);
            run.walker("flare", Team.crux, 20 * 8f, 45 * 8f, 20 * 8f, 26 * 8f);
            run.run(1800, run::allDead);
            assertTrue(run.shots > 0, "the duo fired");
            assertEquals(3, run.deaths, "the duo killed every enemy");
        }
    }

    /** Air only: the scatter ignores the dagger, switches ammo from scrap to lead and shoots flak at flares. */
    @Test
    void scatterShootsFlaresAndIgnoresTheDagger(){
        try(var run = new TurretRun(SIZE)){
            run.place("scatter", 20, 20, Team.sharded);
            run.items(20, 20, "scrap", 6);
            run.every(200, () -> run.items(20, 20, "lead", 3));
            run.walker("dagger", Team.crux, 30 * 8f, 20 * 8f, 30 * 8f, 20 * 8f);
            run.walker("flare", Team.crux, 46 * 8f, 30 * 8f, 22 * 8f, 26 * 8f);
            run.walker("flare", Team.crux, 46 * 8f, 10 * 8f, 24 * 8f, 16 * 8f);
            run.walker("horizon", Team.crux, 10 * 8f, 46 * 8f, 18 * 8f, 24 * 8f);
            run.run(2400, () -> run.vanillaUnits.subList(1, 4).stream().noneMatch(mindustry.gen.Unit::isAdded));
            assertTrue(run.shots > 0, "the scatter fired");
            assertEquals(3, run.deaths, "the scatter killed every flyer");
            assertTrue(run.vanillaUnits.getFirst().isAdded(), "the dagger is left alone");
        }
    }

    /** Artillery at standing daggers, with water as coolant (its reload bonus and effect draws); the flare is ignored. */
    @Test
    void hailShellsStandingDaggers(){
        try(var run = new TurretRun(SIZE)){
            run.place("hail", 20, 20, Team.sharded);
            run.every(90, () -> run.items(20, 20, "graphite", 2));
            run.every(30, () -> run.liquid(20, 20, "water", 4f));
            run.walker("dagger", Team.crux, 40 * 8f, 22 * 8f, 40 * 8f, 22 * 8f);
            run.walker("dagger", Team.crux, 36 * 8f, 12 * 8f, 36 * 8f, 12 * 8f);
            run.walker("flare", Team.crux, 26 * 8f, 26 * 8f, 26 * 8f, 26 * 8f);
            run.run(3600, () -> !run.vanillaUnits.get(0).isAdded() && !run.vanillaUnits.get(1).isAdded());
            assertTrue(run.shots > 0, "the hail fired");
            assertEquals(2, run.deaths, "the hail killed both daggers");
        }
    }

    /** Four-shot bursts, ammo used per bullet, two ammo types (graphite arrives on top of copper). */
    @Test
    void salvoBurstsWithTwoAmmoTypes(){
        try(var run = new TurretRun(SIZE)){
            run.place("salvo", 20, 20, Team.sharded);
            run.items(20, 20, "copper", 5);
            run.every(150, () -> run.items(20, 20, "graphite", 2));
            run.walker("dagger", Team.crux, 44 * 8f, 22 * 8f, 30 * 8f, 22 * 8f);
            run.walker("mace", Team.crux, 44 * 8f, 16 * 8f, 32 * 8f, 18 * 8f);
            run.walker("dagger", Team.crux, 20 * 8f, 44 * 8f, 20 * 8f, 32 * 8f);
            run.run(2400, run::allDead);
            assertTrue(run.shots > 0, "the salvo fired");
            assertEquals(3, run.deaths, "the salvo killed every enemy");
        }
    }

    /** Four shells a salvo at the biggest cluster of ground units ({@code sort:grouped}), two ammo a shot. */
    @Test
    void rippleShellsTheCluster(){
        try(var run = new TurretRun(SIZE)){
            run.place("ripple", 20, 20, Team.sharded);
            run.every(60, () -> run.items(20, 20, "graphite", 2));
            run.walker("dagger", Team.crux, 26 * 8f, 40 * 8f, 26 * 8f, 40 * 8f);
            for(int i = 0; i < 4; i++) run.walker("dagger", Team.crux, (38 + i % 2) * 8f, (20 + i / 2) * 8f, (38 + i % 2) * 8f, (20 + i / 2) * 8f);
            run.walker("crawler", Team.crux, 44 * 8f, 6 * 8f, 34 * 8f, 12 * 8f);
            run.run(3600, run::allDead);
            assertTrue(run.shots > 0, "the ripple fired");
            assertEquals(6, run.deaths, "the ripple killed every enemy");
        }
    }

    /** A charged laser (40 frames' charge, no turning while charging) on a power source. */
    @Test
    void lancerChargesAndFires(){
        try(var run = new TurretRun(SIZE)){
            run.place("lancer", 20, 20, Team.sharded);
            run.place("power-source", 22, 20, Team.sharded);
            run.walker("dagger", Team.crux, 44 * 8f, 20 * 8f, 30 * 8f, 22 * 8f);
            run.walker("dagger", Team.crux, 40 * 8f, 34 * 8f, 28 * 8f, 28 * 8f);
            run.run(2400, run::allDead);
            assertTrue(run.shots > 0, "the lancer fired");
            assertEquals(2, run.deaths, "the lancer killed both daggers");
        }
    }

    /**
     * The power turret gate: a lancer on two large solar panels (3.2 a frame against its 6), so efficiency scales its
     * reload, then its 40-frame charge, which turns it not at all, and the shot. Compared through the first shot's frame;
     * the laser's own draws and hits are {@link #lancerChargesAndFires}'.
     */
    @Test
    void lancerReloadsOnPartialPowerAndCharges(){
        try(var run = new TurretRun(SIZE)){
            run.place("lancer", 20, 20, Team.sharded);
            run.place("solar-panel-large", 23, 21, Team.sharded);
            run.place("solar-panel-large", 20, 23, Team.sharded);
            run.walker("dagger", Team.crux, 32 * 8f, 26 * 8f, 32 * 8f, 26 * 8f);
            var lancer = (mindustry.world.blocks.defense.turrets.Turret.TurretBuild)mindustry.Vars.world.build(20, 20);
            var efficiencies = new java.util.TreeSet<Float>();
            var charged = new boolean[1];
            run.every(1, () -> {
                efficiencies.add(lancer.efficiency);
                charged[0] |= lancer.charging();
            });
            run.runUntilShot(1200);
            assertTrue(lancer.totalShots > 0, "the lancer fired");
            assertTrue(charged[0], "the lancer charged");
            assertTrue(efficiencies.stream().anyMatch(e -> e > 0f && e < 1f), "power was short: " + efficiencies);
        }
    }

    @Test
    void arcStrikesCloseDaggers(){
        try(var run = new TurretRun(SIZE)){
            run.place("arc", 20, 20, Team.sharded);
            run.place("power-source", 21, 20, Team.sharded);
            run.walker("dagger", Team.crux, 40 * 8f, 20 * 8f, 28 * 8f, 20 * 8f);
            run.walker("dagger", Team.crux, 20 * 8f, 40 * 8f, 20 * 8f, 28 * 8f);
            run.run(2400, run::allDead);
            assertTrue(run.shots > 0, "the arc fired");
            assertEquals(2, run.deaths, "the arc killed both daggers");
        }
    }

    @Test
    void fuseShredsDaggers(){
        try(var run = new TurretRun(SIZE)){
            run.place("fuse", 20, 20, Team.sharded);
            run.every(120, () -> run.items(20, 20, "titanium", 3));
            run.walker("dagger", Team.crux, 40 * 8f, 22 * 8f, 28 * 8f, 22 * 8f);
            run.walker("mace", Team.crux, 40 * 8f, 16 * 8f, 29 * 8f, 18 * 8f);
            run.run(2400, run::allDead);
            assertTrue(run.shots > 0, "the fuse fired");
            assertEquals(2, run.deaths, "the fuse killed both");
        }
    }

    /** Water at walking daggers: knockback, wet and puddles. */
    @Test
    void waveSoaksDaggers(){
        try(var run = new TurretRun(SIZE)){
            run.place("wave", 20, 20, Team.sharded);
            run.every(20, () -> run.liquid(20, 20, "water", 5f));
            run.walker("dagger", Team.crux, 40 * 8f, 22 * 8f, 26 * 8f, 22 * 8f);
            run.walker("dagger", Team.crux, 22 * 8f, 40 * 8f, 22 * 8f, 26 * 8f);
            var wet = new boolean[1];
            run.run(900, () -> {
                for(mindustry.gen.Unit u : run.vanillaUnits) wet[0] |= u.hasEffect(StatusEffects.wet);
                return false;
            });
            assertTrue(run.shots > 0, "the wave fired");
            assertTrue(wet[0], "the wave wet a dagger");
        }
    }

    /**
     * A wave puts out a fire beside it: a container of coal on its team, set alight, would keep burning (and spreading);
     * the wave aims at the nearest fire on its own team's tiles before any enemy, and its water puts every fire out.
     */
    @Test
    void waveExtinguishesAFire(){
        try(var run = new TurretRun(SIZE)){
            run.place("wave", 20, 20, Team.sharded);
            run.place("container", 26, 20, Team.sharded);
            run.items(26, 20, "coal", 200);
            run.every(20, () -> run.liquid(20, 20, "water", 5f));
            run.ignite(26, 20);
            var wave = (mindustry.world.blocks.defense.turrets.Turret.TurretBuild)mindustry.Vars.world.build(20, 20);
            var aimed = new boolean[1];
            run.run(1800, () -> {
                aimed[0] |= wave.target instanceof mindustry.gen.Fire;
                return aimed[0] && fires() == 0;
            });
            assertTrue(aimed[0], "the wave aimed at a fire");
            assertTrue(run.shots > 0, "the wave fired");
            assertEquals(0, fires(), "the wave put every fire out");
        }
    }

    static int fires(){
        int count = 0;
        for(var entity : mindustry.gen.Groups.all) if(entity instanceof mindustry.gen.Fire) count++;
        return count;
    }
}
