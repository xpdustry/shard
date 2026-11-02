package com.xpdustry.shard.compat.v160.blocks.defense;

import com.xpdustry.shard.oracle.*;
import mindustry.game.*;
import mindustry.world.blocks.defense.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.units.*;
import org.junit.jupiter.api.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * WP63's and WP64's fixtures: each projector and support turret in its setting, powered from a power graph that runs
 * dry where the fixture says so, compared with vanilla after every frame by {@link ProjectorRun} (exact: floats bit for
 * bit, the random, entity ids, bullets) and by save chunk at the end. Each fixture asserts what it gates happened
 * (heals, boosts, absorptions, shots, pulls, rebuilds), and that power ran out where it should, so none passes by
 * nothing happening.
 */
class ProjectorOracleTest{
    static final int SIZE = 48;

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    private static mindustry.gen.Building at(int x, int y){
        return world.build(x, y);
    }

    /** Whether the battery at (x, y) is as good as empty. */
    private static boolean dry(int x, int y){
        return at(x, y).power.status < 0.001f;
    }

    /** Menders on batteries heal damaged walls and a conveyor; one burns silicon for range and strength. Both run dry. */
    @Test
    void mendersHealWallsAndConveyorsWithAndWithoutSilicon(){
        try(var run = new ProjectorRun(SIZE)){
            // a plain mender, on a battery with a third of its charge
            run.place("mender", 10, 10, Team.sharded);
            run.place("battery", 9, 10, Team.sharded);
            run.charge(9, 10, 0.12f);
            run.place("copper-wall", 12, 10, Team.sharded);
            run.place("copper-wall", 10, 13, Team.sharded);
            run.place("conveyor", 13, 11, Team.sharded);
            // a mender with silicon, far from the first
            run.place("mender", 30, 30, Team.sharded);
            run.place("battery", 29, 30, Team.sharded);
            run.charge(29, 30, 0.12f);
            run.items(30, 30, "silicon", 3);
            run.place("copper-wall", 33, 30, Team.sharded);
            run.place("titanium-wall", 30, 34, Team.sharded);
            run.every(240, () -> {
                run.damage(12, 10, 40f);
                run.damage(10, 13, 30f);
                run.damage(13, 11, 3f);
                run.damage(33, 30, 40f);
                run.damage(30, 34, 50f);
            });
            run.watch("plain heal", () -> at(12, 10).wasRecentlyHealed(1f));
            run.watch("silicon heal", () -> at(30, 34).wasRecentlyHealed(1f));
            run.watch("conveyor heal", () -> at(13, 11).wasRecentlyHealed(1f));
            run.watch("phase warm", () -> ((MendProjector.MendBuild)at(30, 30)).phaseHeat > 0.5f);
            run.watch("out of power", () -> at(10, 10).efficiency == 0f && dry(9, 10));
            run.run(1800);
            assertTrue(run.seen("plain heal") > 0, "the plain mender healed the wall");
            assertTrue(run.seen("conveyor heal") > 0, "the plain mender healed the conveyor");
            assertTrue(run.seen("silicon heal") > 0, "the silicon mender reached the far wall");
            assertTrue(run.seen("phase warm") > 0, "silicon warmed the phase heat");
            assertTrue(at(30, 30).items.total() < 3, "the mender burnt silicon");
            assertTrue(run.seen("out of power") > 0, "the battery ran dry");
        }
    }

    /** A mend projector on phase fabric, with its range boost. */
    @Test
    void mendProjectorHealsWithPhaseFabric(){
        try(var run = new ProjectorRun(SIZE)){
            run.place("mend-projector", 20, 20, Team.sharded);
            run.place("power-source", 19, 20, Team.sharded);
            run.items(20, 20, "phase-fabric", 2);
            run.place("titanium-wall-large", 30, 20, Team.sharded);
            run.place("copper-wall", 20, 31, Team.sharded);
            run.every(300, () -> {
                run.damage(30, 20, 250f);
                run.damage(20, 31, 50f);
            });
            run.watch("far heal", () -> at(30, 20).wasRecentlyHealed(1f));
            run.run(1500);
            assertTrue(run.seen("far heal") > 0, "phase fabric's range reached the large wall");
        }
    }

    /** An overdrive projector on batteries boosts a conveyor line, then runs dry and the line slows again. */
    @Test
    void overdriveSpeedsAConveyorLine(){
        try(var run = new ProjectorRun(SIZE)){
            run.place("item-source", 4, 14, Team.sharded);
            run.configure(4, 14, "copper");
            for(int x = 5; x < 24; x++) run.place("conveyor", x, 14, 0, Team.sharded);
            run.place("item-void", 24, 14, Team.sharded);
            // a second line, a titanium conveyor, under the phase-boosted range only
            run.place("item-source", 4, 22, Team.sharded);
            run.configure(4, 22, "lead");
            for(int x = 5; x < 24; x++) run.place("titanium-conveyor", x, 22, 0, Team.sharded);
            run.place("item-void", 24, 22, Team.sharded);
            run.place("overdrive-projector", 14, 10, Team.sharded);
            run.place("battery", 13, 10, Team.sharded);
            run.place("battery", 13, 11, Team.sharded);
            run.charge(13, 10, 0.3f);
            run.charge(13, 11, 0f);
            run.at(300, () -> run.items(14, 10, "phase-fabric", 1));
            run.watch("boosted", () -> at(10, 14).timeScale() > 1.4f);
            run.watch("phase boosted", () -> at(14, 22).timeScale() > 1.4f);
            run.watch("out of power", () -> at(14, 10).efficiency == 0f && dry(13, 10));
            run.run(1200);
            assertTrue(run.seen("boosted") > 0, "the conveyor line was overdriven");
            assertTrue(run.seen("phase boosted") > 0, "phase fabric reached the second line");
            assertTrue(run.seen("out of power") > 0, "the batteries ran dry");
            assertEquals(1f, at(10, 14).timeScale(), "the boost wore off");
        }
    }

    /**
     * Six enemy duos shoot a force projector on a power source until its shield breaks; then their ammo runs out, the
     * buildup cools with cryofluid and the shield comes back. The projector is made tough so the broken shield's hits
     * don't destroy it.
     */
    @Test
    void forceProjectorAbsorbsUntilItBreaksThenRecovers(){
        try(var run = new ProjectorRun(SIZE)){
            run.place("force-projector", 24, 24, Team.sharded);
            run.toughen(24, 24, 100000f);
            run.place("power-source", 22, 24, Team.sharded);
            int[][] duos = {{37, 24}, {37, 26}, {37, 22}, {11, 24}, {11, 26}, {24, 37}};
            for(int[] duo : duos) run.place("duo", duo[0], duo[1], Team.crux);
            boolean[] broken = {false};
            run.every(20, () -> {
                if(((ForceProjector.ForceBuild)at(24, 24)).broken && run.frame > 10) broken[0] = true;
                if(!broken[0]) for(int[] duo : duos) run.items(duo[0], duo[1], "graphite", 1);
            });
            run.every(30, () -> run.liquid(24, 24, "cryofluid", 4f));
            run.watch("absorbed", () -> ((ForceProjector.ForceBuild)at(24, 24)).hit == 1f);
            run.watch("broken", () -> broken[0]);
            run.watch("recovered", () -> broken[0] && !((ForceProjector.ForceBuild)at(24, 24)).broken);
            run.run(3000, () -> run.seen("recovered") > 30);
            assertTrue(run.seen("absorbed") > 0, "the shield absorbed bullets");
            assertTrue(run.seen("broken") > 0, "the shield broke");
            assertTrue(run.seen("recovered") > 0, "the shield came back");
        }
    }

    /** A force projector on a draining battery and phase fabric: its shield grows, then shrinks away when power is gone. */
    @Test
    void forceProjectorShrinksWithoutPower(){
        try(var run = new ProjectorRun(SIZE)){
            run.place("force-projector", 24, 24, Team.sharded);
            run.place("battery", 22, 24, Team.sharded);
            run.place("battery", 22, 25, Team.sharded);
            run.charge(22, 24, 0.4f);
            run.charge(22, 25, 0f);
            run.items(24, 24, "phase-fabric", 2);
            run.place("duo", 38, 24, Team.crux);
            run.every(40, () -> run.items(38, 24, "copper", 2));
            run.watch("phase", () -> ((ForceProjector.ForceBuild)at(24, 24)).phaseHeat > 0.5f);
            run.watch("absorbed", () -> ((ForceProjector.ForceBuild)at(24, 24)).hit == 1f);
            run.watch("out of power", () -> at(24, 24).efficiency == 0f && dry(22, 24));
            run.run(900);
            assertTrue(run.seen("phase") > 0, "phase fabric boosted the shield");
            assertTrue(run.seen("absorbed") > 0, "the shield absorbed bullets");
            assertTrue(run.seen("out of power") > 0, "the batteries ran dry");
        }
    }

    /**
     * Explosion shields: sharded duos inside a force field shoot down weakened crux flares hovering in it, whose wrecks'
     * crash damage the field absorbs ({@code absorbExplosion}). The flares don't shoot, so every hit is a crash; a full
     * flare would die on its way in, outside the field.
     */
    @Test
    void forceProjectorAbsorbsCrashes(){
        try(var run = new ProjectorRun(SIZE)){
            run.place("force-projector", 24, 24, Team.sharded);
            run.place("power-source", 22, 24, Team.sharded);
            run.place("duo", 26, 22, Team.sharded);
            run.place("duo", 22, 26, Team.sharded);
            run.every(30, () -> {
                run.items(26, 22, "graphite", 1);
                run.items(22, 26, "graphite", 1);
            });
            run.at(120, () -> {
                run.unitHealth(run.walker("flare", Team.crux, 31 * 8f, 28 * 8f, 30 * 8f, 28 * 8f), 5f);
                run.unitHealth(run.walker("flare", Team.crux, 19 * 8f, 30 * 8f, 20 * 8f, 30 * 8f), 5f);
                run.unitHealth(run.walker("flare", Team.crux, 28 * 8f, 17 * 8f, 28 * 8f, 18 * 8f), 5f);
            });
            run.watch("crash absorbed", () -> ((ForceProjector.ForceBuild)at(24, 24)).hit > 0f);
            run.run(1500, () -> run.frame > 200 && run.vanillaUnits.stream().noneMatch(mindustry.gen.Unit::isAdded) && run.seen("crash absorbed") > 0);
            assertTrue(run.vanillaUnits.stream().noneMatch(mindustry.gen.Unit::isAdded), "the duos killed the flares");
            assertTrue(run.seen("crash absorbed") > 0, "the shield absorbed a crash");
        }
    }

    /** A segment on batteries shoots down an enemy hail's artillery shells aimed at it, until its power runs out. */
    @Test
    void segmentShootsDownArtillery(){
        try(var run = new ProjectorRun(SIZE)){
            run.place("segment", 14, 24, Team.sharded);
            run.place("battery", 13, 24, Team.sharded);
            run.place("battery", 13, 25, Team.sharded);
            run.charge(13, 24, 0.3f);
            run.charge(13, 25, 0.2f);
            run.toughen(14, 24, 100000f);
            run.place("hail", 40, 24, Team.crux);
            run.place("hail", 40, 28, Team.crux);
            run.every(30, () -> {
                run.items(40, 24, "graphite", 1);
                run.items(40, 28, "silicon", 1);
            });
            run.watch("shot", () -> {
                var s = (PointDefenseTurret.PointDefenseBuild)at(14, 24);
                return s.target != null && s.reloadCounter == 0f;
            });
            run.watch("out of power", () -> dry(13, 24) && dry(13, 25));
            run.run(1800);
            assertTrue(run.seen("shot") > 0, "the segment shot shells");
            assertTrue(run.seen("out of power") > 0, "the batteries ran dry");
        }
    }

    /** A parallax on batteries pulls flares in and wears them down, until power runs out. */
    @Test
    void parallaxPullsFlares(){
        try(var run = new ProjectorRun(SIZE)){
            run.place("parallax", 24, 24, Team.sharded);
            run.place("battery", 23, 24, Team.sharded);
            run.charge(23, 24, 0.15f);
            run.walker("flare", Team.crux, 40 * 8f, 40 * 8f, 44 * 8f, 10 * 8f);
            run.walker("flare", Team.crux, 8 * 8f, 40 * 8f, 4 * 8f, 4 * 8f);
            run.walker("flare", Team.crux, 40 * 8f, 6 * 8f, 6 * 8f, 6 * 8f);
            // a dagger it doesn't target
            run.walker("dagger", Team.crux, 30 * 8f, 24 * 8f, 30 * 8f, 24 * 8f);
            run.watch("pulling", () -> ((TractorBeamTurret.TractorBeamBuild)at(24, 24)).any);
            run.watch("out of power", () -> dry(23, 24));
            run.run(1500);
            assertTrue(run.seen("pulling") > 0, "the parallax pulled a flare");
            assertTrue(run.seen("out of power") > 0, "the battery ran dry");
            assertTrue(run.vanillaUnits.get(3).isAdded() && run.vanillaUnits.get(3).health == run.vanillaUnits.get(3).maxHealth,
                "the dagger is left alone");
        }
    }

    /** A repair point on a battery and a repair turret on water heal damaged units of their team; the battery runs dry. */
    @Test
    void repairTurretsHealUnits(){
        try(var run = new ProjectorRun(SIZE)){
            run.place("repair-point", 10, 10, Team.sharded);
            run.place("battery", 9, 10, Team.sharded);
            run.charge(9, 10, 0.15f);
            int a = run.walker("dagger", Team.sharded, 13 * 8f, 13 * 8f, 13 * 8f, 13 * 8f);
            int b = run.walker("dagger", Team.sharded, 10 * 8f, 14 * 8f, 10 * 8f, 14 * 8f);
            run.unitHealth(a, 20f);
            run.unitHealth(b, 40f);
            run.place("repair-turret", 30, 30, Team.sharded);
            run.place("power-source", 29, 30, Team.sharded);
            run.every(60, () -> run.liquid(30, 30, "water", 5f));
            int c = run.walker("mace", Team.sharded, 40 * 8f, 34 * 8f, 36 * 8f, 34 * 8f);
            int d = run.walker("flare", Team.sharded, 26 * 8f, 26 * 8f, 26 * 8f, 26 * 8f);
            run.unitHealth(c, 50f);
            run.unitHealth(d, 10f);
            // an enemy it doesn't heal
            int e = run.walker("dagger", Team.crux, 32 * 8f, 28 * 8f, 32 * 8f, 28 * 8f);
            run.unitHealth(e, 30f);
            run.every(400, () -> run.unitHealth(a, 20f));
            run.watch("out of power", () -> dry(9, 10));
            run.run(1500);
            assertTrue(run.vanillaUnits.get(b).health > 40f, "the repair point healed a dagger");
            assertTrue(run.vanillaUnits.get(c).health > 50f, "the repair turret healed the mace");
            assertTrue(run.vanillaUnits.get(d).health > 10f, "the repair turret healed the flare");
            assertEquals(30f, run.vanillaUnits.get(e).health, "the enemy is left alone");
            assertTrue(run.seen("out of power") > 0, "the battery ran dry");
        }
    }

    /** A unit repair tower on ozone heals damaged units around it. */
    @Test
    void repairTowerHealsUnits(){
        try(var run = new ProjectorRun(SIZE)){
            run.place("unit-repair-tower", 20, 20, Team.sharded);
            run.place("battery", 19, 20, Team.sharded);
            run.charge(19, 20, 0.04f);
            run.every(60, () -> run.liquid(20, 20, "ozone", 4f));
            int a = run.walker("dagger", Team.sharded, 24 * 8f, 22 * 8f, 24 * 8f, 22 * 8f);
            int b = run.walker("flare", Team.sharded, 16 * 8f, 26 * 8f, 22 * 8f, 26 * 8f);
            run.every(300, () -> {
                run.unitHealth(a, 30f);
                run.unitHealth(b, 20f);
            });
            run.watch("healing", () -> ((RepairTower.RepairTowerBuild)at(20, 20)).warmup > 0.5f);
            run.watch("out of power", () -> dry(19, 20));
            run.run(1200);
            assertTrue(run.seen("healing") > 0, "the tower healed");
            assertTrue(run.seen("out of power") > 0, "the battery ran dry");
        }
    }

    /** Two overlapping regen projectors on hydrogen heal damaged walls once, not twice; one burns phase fabric. */
    @Test
    void regenProjectorsHealWithoutStacking(){
        try(var run = new ProjectorRun(SIZE)){
            run.place("regen-projector", 20, 20, Team.sharded);
            run.place("regen-projector", 24, 20, Team.sharded);
            run.place("battery", 18, 20, Team.sharded);
            run.place("battery", 26, 20, Team.sharded);
            run.charge(18, 20, 0.1f);
            run.charge(26, 20, 1f);
            run.items(24, 20, "phase-fabric", 2);
            run.every(60, () -> {
                run.liquid(20, 20, "hydrogen", 3f);
                run.liquid(24, 20, "hydrogen", 3f);
            });
            run.place("beryllium-wall", 22, 24, Team.sharded);
            run.place("beryllium-wall-large", 20, 16, Team.sharded);
            run.place("copper-wall", 27, 24, Team.sharded);
            run.every(300, () -> {
                run.damage(22, 24, 100f);
                run.damage(20, 16, 300f);
                run.damage(27, 24, 50f);
            });
            run.at(500, () -> run.place("copper-wall", 23, 25, Team.sharded));
            run.watch("healed", () -> at(22, 24).wasRecentlyHealed(1f));
            run.watch("out of power", () -> dry(18, 20));
            run.run(1500);
            assertTrue(run.seen("healed") > 0, "the walls were healed");
            assertTrue(run.seen("out of power") > 0, "a battery ran dry");
        }
    }

    /** A shockwave tower on cyanogen wipes out enemy duo bullets aimed at walls near it. */
    @Test
    void shockwaveTowerWipesBullets(){
        try(var run = new ProjectorRun(SIZE)){
            run.place("shockwave-tower", 20, 20, Team.sharded);
            run.place("power-source", 18, 20, Team.sharded);
            run.every(60, () -> run.liquid(20, 20, "cyanogen", 4f));
            run.place("copper-wall", 26, 20, Team.sharded);
            run.toughen(26, 20, 100000f);
            run.toughen(20, 20, 100000f);
            run.place("duo", 36, 20, Team.crux);
            run.place("duo", 36, 22, Team.crux);
            run.every(30, () -> {
                run.items(36, 20, "copper", 2);
                run.items(36, 22, "graphite", 1);
            });
            run.watch("wave", () -> ((ShockwaveTower.ShockwaveTowerBuild)at(20, 20)).heat > 0.98f);
            run.run(1200);
            assertTrue(run.seen("wave") > 0, "the tower released a wave");
        }
    }

    /** A radar discovers its radius on a battery, then loses efficiency once it is dry. */
    @Test
    void radarDiscovers(){
        try(var run = new ProjectorRun(SIZE)){
            run.place("radar", 20, 20, Team.sharded);
            run.place("battery", 19, 20, Team.sharded);
            run.charge(19, 20, 0.1f);
            run.watch("out of power", () -> at(20, 20).efficiency == 0f && dry(19, 20));
            run.run(900);
            assertTrue(((Radar.RadarBuild)at(20, 20)).progress > 0.5f, "the radar discovered");
            assertTrue(run.seen("out of power") > 0, "the battery ran dry");
        }
    }

    /** A shield projector absorbs enemy duo bullets and pushes enemy daggers out of its radius. */
    @Test
    void shieldProjectorAbsorbsAndRepels(){
        try(var run = new ProjectorRun(64)){
            run.place("shield-projector", 32, 32, Team.sharded);
            run.place("power-source", 30, 32, Team.sharded);
            run.toughen(32, 32, 100000f);
            run.place("copper-wall", 32, 50, Team.sharded);
            run.toughen(32, 50, 100000f);
            run.place("duo", 32, 59, Team.crux);
            run.every(40, () -> run.items(32, 59, "copper", 2));
            run.walker("dagger", Team.crux, 60 * 8f, 32 * 8f, 33 * 8f, 32 * 8f);
            run.walker("dagger", Team.crux, 32 * 8f, 6 * 8f, 32 * 8f, 31 * 8f);
            run.watch("enemy bullets", () -> ProjectorRun.vanillaBullets(Team.crux) > 0);
            run.watch("repelled", () -> run.vanillaUnits.get(0).isAdded() && run.vanillaUnits.get(0).x > 32 * 8f + 150f
                && run.vanillaUnits.get(0).x < 32 * 8f + 210f);
            run.run(1200);
            assertTrue(run.seen("enemy bullets") > 0, "the duo fired");
            assertTrue(run.seen("repelled") > 0, "the dagger was held at the shield");
            assertTrue(((BaseShield.BaseShieldBuild)at(32, 32)).radius() > 150f, "the shield grew");
        }
    }
}
