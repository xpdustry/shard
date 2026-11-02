package com.xpdustry.shard.compat.v160.m0;

import com.xpdustry.shard.sim.blocks.turret.TurretBuild;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * L1 for M2 (ARCHITECTURE.md §10): build, mine, convey, power and defend on a Serpulo map, end to end. Fortress is
 * hosted for survival by vanilla and by Shard and compared frame by frame ({@link M0Run}), with no tolerance, from the
 * load until wave 1 is dead, through a save in the middle:
 * <ul>
 *   <li>a player joins, and its core unit builds from its client's queue: two mechanical drills on the copper ore
 *   north of the core, a conveyor line from them down into the core, two drills on the coal ore east of it, four
 *   combustion generators the coal drills feed, a battery, a lancer south-east of the core, and a power node, placed
 *   last, which links the generators, the battery and the lancer;</li>
 *   <li>the drills mine; their copper rides the line into the core, their coal burns in the generators, which charge
 *   the battery;</li>
 *   <li>the player leaves, and the world is saved and loaded on both sides (vanilla's save, which Shard reads);</li>
 *   <li>the countdown runs out, wave 1's daggers walk to the core, and the lancer, on the generators' power, fires on
 *   them until they are dead.</li>
 * </ul>
 * {@code rig/scenarios/m2.rig} plays the same base with two stock clients.
 */
class M2OracleTest{
    /** Fortress's core-shard stands at (116, 73): it covers (115..117, 72..74). */
    static final int CX = 116, CY = 73;

    @Test
    void drillsAConveyorLineGeneratorsAndALancerAgainstFortressWaveOneThroughASave(){
        play("fortress", new M0Run("fortress"));
    }

    /**
     * The same base on {@code m2.msav}, the map of {@code m2.rig} ({@code M2Map.java}): fortress with the ores this
     * test's hosting generated, frozen, since each host of fortress itself scatters them anew.
     */
    @Test
    void theSameBaseOnTheRigsMap() throws Exception{
        Path map = Path.of("..", "rig", "scenarios", "m2.msav").toAbsolutePath().normalize();
        play("m2.msav", new M0Run(map.toString(), Files.readAllBytes(map)));
    }

    static void play(String name, M0Run run){
        assertEquals(CX, run.coreX);
        assertEquals(CY, run.coreY);
        run.items("copper", 1000);
        run.items("lead", 400);
        run.items("silicon", 300);
        run.items("titanium", 100);

        // mine and convey: two drills on copper (each 2x2 from its tile up and right), a line down the column west of
        // them into the core's top-right tile
        run.plan("mechanical-drill", 118, 79, 0);
        run.plan("mechanical-drill", 118, 81, 0);
        for(int y = 82; y >= 75; y--) run.plan("conveyor", 117, y, 3);
        // power: two drills on coal, four combustion generators on their top edges, a battery, the lancer and the node
        run.plan("mechanical-drill", 119, 75, 0);
        run.plan("mechanical-drill", 121, 75, 0);
        for(int x = 119; x <= 122; x++) run.plan("combustion-generator", x, 77, 0);
        run.plan("battery", 124, 74, 0);
        run.plan("lancer", 119, 70, 0);
        run.plan("power-node", 122, 73, 0);
        for(var plan : run.plans){
            var tile = world.tile(plan.x(), plan.y());
            assertTrue(tile.block() == mindustry.content.Blocks.air && !tile.solid(), plan + " stands on open ground");
        }
        assertEquals(mindustry.content.Items.copper, world.tile(118, 79).drop());
        assertEquals(mindustry.content.Items.coal, world.tile(119, 75).drop());

        // 1. the player builds the base; then the drills run until copper reached the core and coal charged the battery
        int built = run.until(8_000, () -> run.calls.getOrDefault("ConstructFinish", 0) == run.plans.size());
        int copperBuilt = coreCopper();
        int[] lineItems = {0}, burning = {0};
        int ran = run.until(built + 4_000, () -> {
            if(run.vanilla(117, 75) instanceof mindustry.world.blocks.distribution.Conveyor.ConveyorBuild c && c.len > 0) lineItems[0]++;
            for(int x = 119; x <= 122; x++) if(run.vanilla(x, 77) instanceof mindustry.world.blocks.power.PowerGenerator.GeneratorBuild g && g.productionEfficiency > 0) burning[0]++;
            return coreCopper() >= copperBuilt + 8 && run.vanilla(124, 74).power.status > 0.2f;
        });
        int copperBefore = coreCopper();
        float charge = run.vanilla(124, 74).power.status;
        // adjacent generators share a graph already, so the node links one of them, the battery and the lancer
        var graph = run.vanilla(122, 73).power.graph;
        for(int[] at : new int[][]{{119, 77}, {120, 77}, {121, 77}, {122, 77}, {124, 74}, {119, 70}}){
            assertSame(graph, run.vanilla(at[0], at[1]).power.graph, "the node's graph holds (" + at[0] + ", " + at[1] + ")");
        }
        assertEquals(3, run.vanilla(122, 73).power.links.size, "the node's links: " + run.vanilla(122, 73).power.links);
        assertEquals(0, run.calls.getOrDefault("SpawnEffect", 0), "no wave yet");

        // 2. the base runs on to frame 3,000, so each side of the save has 3,000 exact frames; then the player leaves
        // and vanilla's save of the world loads into both
        int leaves = Math.max(ran, 3_000 - 61);
        run.until(leaves + 1, () -> run.frame >= leaves);
        run.at(run.frame + 1, run::leave);
        run.until(leaves + 62, () -> run.frame >= leaves + 61);
        assertEquals(0, mindustry.gen.Groups.player.size(), "the player left");
        run.saveAndLoad();
        int loadedAt = run.frame;
        int copperLoaded = coreCopper();
        assertTrue(loadedAt >= 3_000, "the save comes after 3,000 exact frames of play: " + loadedAt);

        // 3. wave 1 walks in, and the lancer kills it on the generators' power
        var wave = new ArrayList<Integer>();
        int last = run.until(loadedAt + 20_000, () -> {
            for(int i = 0; i < run.shard.units().size(); i++){
                var unit = run.shard.units().get(i);
                if(unit.team == run.rules.waveTeam && !wave.contains(unit.id())) wave.add(unit.id());
            }
            assertNotNull(state.rules.defaultTeam.core(), "the core stands at frame " + run.frame);
            return !wave.isEmpty() && mindustry.gen.Groups.unit.count(u -> u.team == state.rules.waveTeam) == 0 && mindustry.gen.Groups.bullet.isEmpty();
        });
        run.chunks();

        int lancerShots = run.ours(119, 70) instanceof TurretBuild lancer ? lancer.totalShots : 0;
        System.out.println("M2 on " + name + ": built at frame " + built + ", copper " + copperBuilt + " -> " + copperBefore + ", loaded " + copperLoaded + " -> " + coreCopper()
            + ", battery " + charge + ", line busy " + lineItems[0] + " frames, generator-frames burning " + burning[0] + "; saved and loaded at "
            + loadedAt + "; wave 1 (" + wave.size() + " units) dead at " + last + " (" + (last - loadedAt) + " frames after the load); lancer shots "
            + lancerShots + "; Calls " + run.calls);

        assertEquals(1, run.saves, "the run went through a save");
        assertTrue(last - loadedAt >= 3_000, "exact for 3,000 frames after the load: " + (last - loadedAt));
        assertTrue(lineItems[0] > 0, "copper rode the line's last conveyor");
        assertTrue(copperBefore > copperBuilt, "the drills' copper reached the core: " + copperBuilt + " -> " + copperBefore);
        assertTrue(coreCopper() > copperLoaded, "the drills kept mining after the load: " + copperLoaded + " -> " + coreCopper());
        assertTrue(burning[0] > 0, "the generators burned coal");
        assertTrue(charge > 0.2f, "the generators charged the battery: " + charge);
        assertTrue(run.calls.getOrDefault("PlayerSpawn", 0) >= 1, "the player spawned: " + run.calls);
        assertTrue(run.calls.getOrDefault("UnitDespawn", 0) >= 1, "the player's core unit despawned when it left: " + run.calls);
        assertTrue(run.calls.getOrDefault("SpawnEffect", 0) >= 1, "wave 1 spawned: " + run.calls);
        assertTrue(wave.size() >= 2, "wave 1's units: " + wave.size());
        assertTrue(run.calls.getOrDefault("UnitDeath", 0) >= 1, "a wave unit died: " + run.calls);
        assertTrue(lancerShots > 0, "the lancer fired on the generators' power");
    }

    static int coreCopper(){
        return world.build(CX, CY).items.get(mindustry.content.Items.copper);
    }
}
