package com.xpdustry.shard.compat.v160.m0;

import com.xpdustry.shard.sim.blocks.turret.TurretBuild;
import mindustry.world.blocks.logic.LogicBlock.*;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * L1 for the M0 rig scenario ({@code rig/scenarios/m0.rig}): its game on its own map, {@code m0.msav}
 * ({@code M0Map.java}), hosted by vanilla and by Shard and compared frame by frame ({@link M0Run}), with the actions
 * alpha takes on the rig as its client's queue. Its own class, so vanilla's statics start fresh.
 */
class M0RigMapOracleTest{
    /**
     * {@code m0.rig}'s game on {@code m0.msav} ({@code M0Map.java}), as its client's queue: alpha's line from the map's
     * unloader to a router with three duos, under the map's processor, which links to the line's third conveyor by
     * name; wave 1 dies to the duos while the lancer, which no node joins to the panels yet, can't fire; then alpha's
     * power node joins them, and the lancer fires on wave 2. Compared from the load until wave 2 is dead.
     */
    @Test
    void theRigScenarioOnItsMap() throws Exception{
        Path map = Path.of("..", "rig", "scenarios", "m0.msav").toAbsolutePath().normalize();
        var run = new M0Run(map.toString(), Files.readAllBytes(map));
        assertEquals(List.of(), run.removed);
        var processor = (LogicBuild)run.vanilla(17, 28);
        assertTrue(processor.links.contains(l -> !l.valid && l.name.equals("conveyor1") && l.x == 17 && l.y == 25), "the map's link waits for its conveyor");
        for(int x = 15; x <= 19; x++) run.plan("conveyor", x, 25, 0);
        run.plan("router", 20, 25, 0);
        run.plan("duo", 20, 26, 0);
        run.plan("duo", 20, 24, 0);
        run.plan("duo", 21, 25, 0);

        int[] toggles = {0};
        boolean[] lastEnabled = {true};
        Runnable watch = () -> {
            var conveyor = run.vanilla(17, 25);
            if(conveyor != null && conveyor.block == mindustry.content.Blocks.conveyor && conveyor.enabled != lastEnabled[0]){
                toggles[0]++;
                lastEnabled[0] = conveyor.enabled;
            }
            assertNotNull(state.rules.defaultTeam.core(), "the core stands at frame " + run.frame);
        };
        int first = run.until(12_000, () -> {
            watch.run();
            return state.wave == 2 && waveUnits() == 0 && mindustry.gen.Groups.bullet.isEmpty();
        });
        int duoShots = shots(run, 20, 26) + shots(run, 20, 24) + shots(run, 21, 25);
        assertTrue(duoShots > 0, "the duos fired on wave 1");
        assertEquals(0, shots(run, 22, 29), "the lancer had no power for wave 1");
        assertTrue(toggles[0] >= 20, "the processor turned the conveyor off and on: " + toggles[0]);
        assertTrue(processor.links.contains(l -> l.valid && l.name.equals("conveyor1") && l.x == 17 && l.y == 25), "the link found its conveyor");

        run.plan("power-node", 24, 31, 0);
        int last = run.until(24_000, () -> {
            watch.run();
            return state.wave == 3 && waveUnits() == 0 && mindustry.gen.Groups.bullet.isEmpty();
        });
        run.chunks();
        System.out.println("M0 on m0.msav: wave 1 dead at frame " + first + ", wave 2 at " + last + "; duo shots " + duoShots + " then "
            + (shots(run, 20, 26) + shots(run, 20, 24) + shots(run, 21, 25)) + ", lancer shots " + shots(run, 22, 29) + ", conveyor toggles "
            + toggles[0] + "; Calls " + run.calls);
        assertEquals(run.plans.size(), run.calls.getOrDefault("ConstructFinish", 0), "every plan was built: " + run.calls);
        assertTrue(run.vanilla(24, 31).power.links.size >= 2, "the node linked the panels and the lancer");
        assertTrue(shots(run, 22, 29) > 0, "the lancer fired on wave 2");
        assertTrue(run.calls.getOrDefault("UnitDeath", 0) >= 4, "both waves died: " + run.calls);
    }

    static int waveUnits(){
        return mindustry.gen.Groups.unit.count(u -> u.team == state.rules.waveTeam);
    }

    /** A turret's shots on Shard's side, which equal vanilla's as compared every frame; 0 if it isn't there. */
    static int shots(M0Run run, int x, int y){
        return run.ours(x, y) instanceof TurretBuild turret ? turret.totalShots : 0;
    }
}
