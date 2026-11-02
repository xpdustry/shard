package com.xpdustry.shard.compat.v160.blocks.defense;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.oracle.*;
import mindustry.game.*;
import mindustry.world.blocks.defense.*;
import org.junit.jupiter.api.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * WP39's and WP40's fixtures against vanilla, compared after every frame by {@link DefenseRun} (exact: floats bit for
 * bit, the random, entity ids, bullets, Calls), by save chunk at the end, and, where no scripted unit is left to lose its
 * script, on through vanilla's save loaded by both. Each fixture asserts that what it gates happened.
 */
class DefenseOracleTest{
    static final int SIZE = 48;

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    /** A phase wall sends a duo's copper back at the duo (10 / 9 damage: every one), on the wall's team. */
    @Test
    void phaseWallsDeflectDuoFireBackAtIt(){
        try(var run = new DefenseRun(SIZE)){
            run.place("duo", 10, 24, Team.crux);
            run.every(90, () -> run.items(10, 24, "copper", 3));
            run.place("phase-wall", 19, 24, Team.sharded);
            run.place("phase-wall-large", 19, 26, Team.sharded);
            run.place("titanium-wall", 19, 22, Team.sharded);
            run.run(900, () -> false);
            assertTrue(world.build(10, 24) == null || world.build(10, 24).health < world.build(10, 24).maxHealth, "deflected copper hit the duo");
            run.reload();
            run.run(300, () -> false);
        }
    }

    /** Surge walls under graphite arc lightning back along the shots (5% a hit), and lightning stops at no wall here. */
    @Test
    void surgeWallsArcLightningUnderFire(){
        try(var run = new DefenseRun(SIZE)){
            run.place("duo", 10, 24, Team.crux);
            run.place("duo", 10, 20, Team.crux);
            run.every(60, () -> {
                run.items(10, 24, "graphite", 2);
                run.items(10, 20, "graphite", 2);
            });
            run.place("surge-wall", 18, 24, Team.sharded);
            run.place("surge-wall-large", 18, 21, Team.sharded);
            run.run(3000, () -> DefenseRun.lightning() >= 4);
            assertTrue(DefenseRun.lightning() >= 1, "a surge wall arced lightning");
        }
    }

    /** A lancer's laser at a dagger stops at the plastanium walls in front of it: the walls take it, the dagger doesn't. */
    @Test
    void plastaniumWallsAbsorbTheLancersLaser(){
        try(var run = new DefenseRun(SIZE)){
            run.place("lancer", 10, 24, Team.crux);
            run.place("power-source", 12, 24, Team.crux);
            for(int y = 21; y <= 28; y += 2) run.place("plastanium-wall-large", 16, y, Team.sharded);
            run.walker("dagger", Team.sharded, 21 * 8f, 24.5f * 8f, 21 * 8f, 24.5f * 8f);
            run.run(900, () -> false);
            var dagger = run.vanillaUnits.getFirst();
            assertEquals(dagger.maxHealth, dagger.health, "the dagger behind plastanium took no laser");
            boolean hit = false;
            for(int y = 21; y <= 28; y += 2) hit |= world.build(16, y).health < world.build(16, y).maxHealth;
            assertTrue(hit, "the plastanium took the laser");
        }
    }

    /** Shielded walls under fire: the shield takes it, breaks, waits, regrows; some shots deflect. Also a thruster. */
    @Test
    void shieldedWallsAndAThrusterUnderFire(){
        try(var run = new DefenseRun(SIZE)){
            // both teams cheat: the walls' shields and the lancer run without power, which the lancer's piercing laser
            // would cut; three duos and the lancer, at six times the block damage, outpace the shields' regeneration
            run.cheat(Team.sharded);
            run.cheat(Team.crux);
            run.blockDamage(Team.crux, 6f);
            run.place("duo", 10, 24, Team.crux);
            run.place("duo", 10, 26, Team.crux);
            run.place("duo", 10, 28, Team.crux);
            run.place("lancer", 8, 22, Team.crux);
            run.place("duo", 30, 10, Team.crux);
            run.every(40, () -> {
                for(int y = 24; y <= 28; y += 2) run.items(10, y, "graphite", 2);
                run.items(30, 10, "graphite", 2);
            });
            // 2x2 each
            run.place("shielded-wall", 18, 24, Team.sharded);
            run.place("shielded-wall", 18, 26, Team.sharded);
            run.place("thruster", 37, 10, Team.sharded, 1);
            boolean[] broke = {false};
            float[] lowest = {Float.MAX_VALUE};
            run.every(1, () -> {
                for(int y = 24; y <= 26; y += 2){
                    if(!(world.build(18, y) instanceof ShieldWall.ShieldWallBuild w)) continue;
                    broke[0] |= w.breakTimer > 0;
                    lowest[0] = Math.min(lowest[0], w.shield);
                }
            });
            run.run(1500, () -> false);
            assertTrue(broke[0], () -> "a shield broke, lowest " + lowest[0] + ": " + String.join("; ", run.vanillaState()));
            assertTrue(world.build(37, 10) == null || world.build(37, 10).health < world.build(37, 10).maxHealth, "the thruster was hit");
            run.reload();
            run.run(300, () -> false);
        }
    }

    /**
     * A chain of three doors in a wall line: a dagger walking at it is blocked while it is shut, a processor reading a
     * switch opens the chain, and can't close the door the dagger then stands in; the server's closes skip that door, and
     * a player's opens the chain again.
     */
    @Test
    void doorsOpenForProcessorsAndPlayersAndBlockUnits(){
        try(var run = new DefenseRun(SIZE)){
            for(int y = 12; y <= 28; y++){
                if(y < 19 || y > 21) run.place("copper-wall", 20, y, Team.sharded);
            }
            run.place("door", 20, 19, Team.sharded);
            run.place("door", 20, 20, Team.sharded);
            run.place("door", 20, 21, Team.sharded);
            run.place("switch", 14, 14, Team.sharded);
            run.processor("micro-processor", 12, 14, Team.sharded, """
                sensor s switch1 @enabled
                control enabled door1 s 0 0 0
                """, 14, 14, 20, 20);
            run.walker("dagger", Team.sharded, 10 * 8f, 20 * 8f, 20 * 8f, 20 * 8f);
            run.at(240, () -> run.configure(14, 14, true, true));
            // the processor's close is refused while the dagger stands in its door
            run.at(600, () -> run.configure(14, 14, false, false));
            // the server's closes the chain but that door
            run.at(650, () -> run.configure(20, 19, false, false));
            run.at(700, () -> run.playerConfigure(20, 19, true, true, 0));
            run.at(800, () -> run.configure(20, 21, false, false));
            // standing short of the doorway, not moving, before the processor opens it
            float[] stood = {0f};
            boolean[] blocked = {false};
            run.at(200, () -> stood[0] = run.vanillaUnits.getFirst().x);
            run.at(239, () -> blocked[0] = run.vanillaUnits.getFirst().x == stood[0] && stood[0] < 20 * 8f);
            run.run(1000, () -> false);
            assertTrue(blocked[0], "the shut doors blocked the dagger");
            var dagger = run.vanillaUnits.getFirst();
            assertTrue(Math.abs(dagger.x - 20 * 8f) < 2f, "the dagger reached the doorway");
            assertTrue(((Door.DoorBuild)world.build(20, 20)).open, "the door with the dagger in it stayed open");
            assertFalse(((Door.DoorBuild)world.build(20, 21)).open, "the others closed");
        }
    }

    /**
     * Review 41: a cross of 17 doors, one chain, split by removals and joined again, with a processor toggling one arm
     * through its chain's origin's timer and players toggling the others. Shard goes on as a fork, then from a restored
     * checkpoint, then from vanilla's save: every door's chain, in order, its origin's timer and its state match
     * vanilla's on every frame.
     */
    @Test
    void doorChainsSplitAndJoinThroughForksAndRestores(){
        try(var run = new DefenseRun(SIZE)){
            for(int x = 14; x <= 22; x++) run.place("door", x, 20, Team.sharded);
            for(int y = 16; y <= 24; y++) if(y != 20) run.place("door", 18, y, Team.sharded);
            run.place("switch", 10, 16, Team.sharded);
            run.processor("micro-processor", 12, 16, Team.sharded, """
                sensor s switch1 @enabled
                control enabled door1 s 0 0 0
                """, 10, 16, 14, 20);
            // the players' unit, standing clear of the doors
            run.stand("dagger", Team.sharded, 30 * 8f, 30 * 8f);
            boolean[] on = {false};
            run.every(90, () -> {
                on[0] = !on[0];
                run.configure(10, 16, on[0], on[0]);
            });
            // the centre goes: four chains, each with a new origin
            run.at(300, () -> run.remove(18, 20));
            run.at(360, () -> run.playerConfigure(20, 20, true, true, 0));
            run.at(400, run::forkShard);
            run.at(450, () -> run.remove(16, 20));
            run.at(520, () -> run.playerConfigure(18, 23, true, true, 0));
            run.at(600, run::restoreShard);
            // the centre comes back and joins them, walking from itself
            run.at(650, () -> run.place("door", 18, 20, Team.sharded));
            run.at(700, () -> run.playerConfigure(22, 20, false, false, 0));
            run.at(800, run::forkShard);
            run.at(850, run::restoreShard);
            int[] toggles = {0};
            boolean[] was = {false};
            run.every(1, () -> {
                boolean open = ((Door.DoorBuild)world.build(14, 20)).open;
                if(open != was[0]) toggles[0]++;
                was[0] = open;
            });
            run.run(1000, () -> false);
            assertTrue(toggles[0] >= 6, "the processor toggled its arm through the run: " + toggles[0]);
            var centre = (Door.DoorBuild)world.build(18, 20);
            assertEquals(14, centre.chained.size, "the centre joined what was left");
            assertSame(centre, centre.chained.first(), "walking from itself");
            run.reload();
            run.run(200, () -> false);
        }
    }

    /**
     * A blast door in a wall line opens for its own team's dagger and stays shut to an enemy's. Another opens for a
     * dagger standing still within its trigger margin: the dagger's hitbox ends 9 units short of the door's edge.
     */
    @Test
    void blastDoorsOpenForTheirTeam(){
        try(var run = new DefenseRun(SIZE)){
            for(int y = 10; y <= 30; y += 2){
                if(y != 20) run.place("copper-wall-large", 20, y, Team.sharded);
            }
            run.place("blast-door", 20, 20, Team.sharded);
            run.walker("dagger", Team.sharded, 10 * 8f, 20.5f * 8f, 32 * 8f, 20.5f * 8f);
            run.walker("dagger", Team.crux, 40 * 8f, 26.5f * 8f, 10 * 8f, 20.5f * 8f);
            // the door covers x 236..252; the dagger's hitbox 219..227
            run.place("blast-door", 30, 36, Team.sharded);
            run.walker("dagger", Team.sharded, 223f, 292f, 223f, 292f);
            run.run(1200, () -> false);
            assertTrue(run.calls.getOrDefault("AutoDoorToggle", 0) >= 3, "the doors opened, one closed: " + run.calls);
            assertTrue(run.vanillaUnits.get(0).x > 24 * 8f, "the sharded dagger passed");
            assertTrue(run.vanillaUnits.get(1).x > 21 * 8f, "the crux dagger didn't");
            assertTrue(((AutoDoor.AutoDoorBuild)world.build(30, 36)).open, "the margin reaches the standing dagger");
        }
    }

    /** A dagger walks over crux shock mines: ground lightning and the mines' own damage, every cooldown; a crux one doesn't. */
    @Test
    void shockMinesUnderADagger(){
        try(var run = new DefenseRun(SIZE)){
            for(int x = 18; x <= 26; x += 2) run.place("shock-mine", x, 20, Team.crux);
            run.walker("dagger", Team.sharded, 10 * 8f, 20 * 8f, 34 * 8f, 20 * 8f);
            // the mines' own team walks over one and sets nothing off
            run.walker("dagger", Team.crux, 22 * 8f, 12 * 8f, 22 * 8f, 28 * 8f);
            run.run(900, () -> false);
            assertTrue(DefenseRun.lightning() > 0, "a mine went off");
            var dagger = run.vanillaUnits.getFirst();
            assertTrue(!dagger.isAdded() || dagger.health < dagger.maxHealth, "the dagger was shocked");
        }
    }

    /** A duo shoots a target dummy's unit: the dummy counts the hits; configs change its armor, size and team. */
    @Test
    void targetDummyCountsHits(){
        try(var run = new DefenseRun(SIZE)){
            run.place("target-dummy", 20, 20, Team.sharded);
            run.place("duo", 13, 20, Team.crux);
            run.every(60, () -> run.items(13, 20, "copper", 3));
            run.at(300, () -> run.configure(20, 20, new int[]{0, 0, 5, 200, Float.floatToIntBits(20f)}, new ConfigValue.IntArray(new int[]{0, 0, 5, 200, Float.floatToIntBits(20f)})));
            run.at(500, () -> run.configure(20, 20, new int[]{0, 1, 0, 120, Float.floatToIntBits(12f)}, new ConfigValue.IntArray(new int[]{0, 1, 0, 120, Float.floatToIntBits(12f)})));
            run.at(700, () -> run.configure(20, 20, new int[]{1, 0, 0, 120, Float.floatToIntBits(12f)}, new ConfigValue.IntArray(new int[]{1, 0, 0, 120, Float.floatToIntBits(12f)})));
            int[] hits = {0};
            run.every(1, () -> hits[0] = Math.max(hits[0], ((TargetDummy.TargetDummyBuild)world.build(20, 20)).hits));
            run.run(800, () -> false);
            assertTrue(hits[0] > 0, "the dummy counted hits");
            assertEquals(1, run.calls.getOrDefault("UnitTetherBlockSpawned", 0), "one dummy unit");
            run.reload();
            run.run(300, () -> false);
        }
    }

    /**
     * Landing pads off the campaign: a crux pad with water calls copper down again and again and dumps it into a void;
     * a pad of the default team and an Erekir item refuse their configs.
     */
    @Test
    void landingPadsCallPodsDown(){
        try(var run = new DefenseRun(SIZE)){
            run.place("landing-pad", 20, 20, Team.crux);
            run.place("item-void", 23, 20, Team.crux);
            run.place("landing-pad", 30, 30, Team.sharded);
            var copper = ContentRef.item(DefenseRun.EPOCH.item("copper").id());
            var beryllium = ContentRef.item(DefenseRun.EPOCH.item("beryllium").id());
            run.at(0, () -> {
                run.configure(20, 20, content.item("beryllium"), beryllium);
                run.configure(20, 20, content.item("copper"), copper);
                run.configure(30, 30, content.item("copper"), copper);
            });
            run.every(100, () -> run.liquid(20, 20, "water", 600f));
            run.run(1200, () -> false);
            assertTrue(run.calls.getOrDefault("LandingPadLanded", 0) >= 2, "pods came down: " + run.calls);
            run.reload();
            run.run(300, () -> false);
        }
    }

    /**
     * A powered launch pad fills and sends its items up in a payload, which flies 120 ticks, through a save made in flight;
     * an accelerator builds its core.
     */
    @Test
    void launchPadsLaunchAndAcceleratorsBuild(){
        try(var run = new DefenseRun(SIZE)){
            run.place("launch-pad", 20, 20, Team.sharded);
            run.place("power-source", 22, 20, Team.sharded);
            run.items(20, 20, "copper", 60);
            run.every(200, () -> run.items(20, 20, "copper", 30));
            run.place("interplanetary-accelerator", 32, 32, Team.sharded);
            run.place("power-source", 36, 32, Team.sharded);
            for(var stack : mindustry.content.Blocks.coreNucleus.requirements) run.give(32, 32, stack.item.name, stack.amount);
            int[] flying = {0};
            run.run(1500, () -> mindustry.gen.Groups.all.count(e -> e instanceof mindustry.gen.LaunchPayload) > 0 && ++flying[0] >= 30);
            assertEquals(30, flying[0], "a payload flew");
            assertTrue(((mindustry.world.blocks.campaign.Accelerator.AcceleratorBuild)world.build(32, 32)).progress > 0f, "the core grew");
            // saved in flight: the payload comes to life on both sides and lands its last 90 ticks
            run.reload();
            run.run(300, () -> false);
            assertEquals(0, mindustry.gen.Groups.all.count(e -> e instanceof mindustry.gen.LaunchPayload), "it went");
        }
    }

    /** A power void on a graph takes everything, so the lancer on it gets nothing. */
    @Test
    void powerVoidsDrainTheirGraph(){
        try(var run = new DefenseRun(SIZE)){
            // the panel covers x 19..21, the lancer 22..23
            run.place("solar-panel-large", 20, 20, Team.sharded);
            run.place("lancer", 22, 20, Team.sharded);
            run.place("battery", 24, 20, Team.sharded);
            run.place("power-void", 25, 20, Team.sharded);
            run.run(300, () -> false);
            assertSame(world.build(20, 20).power.graph, world.build(25, 20).power.graph, "one graph");
            assertTrue(world.build(20, 20).power.graph.getLastPowerProduced() > 0f, "the panel makes power");
            assertTrue(world.build(22, 20).power.status < 0.01f, "the void took the power");
            run.reload();
            run.run(120, () -> false);
        }
    }
}
