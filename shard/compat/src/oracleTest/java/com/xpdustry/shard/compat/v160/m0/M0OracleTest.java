package com.xpdustry.shard.compat.v160.m0;

import arc.struct.*;
import com.xpdustry.shard.logic.LogicConfig;
import com.xpdustry.shard.sim.blocks.turret.TurretBuild;
import com.xpdustry.shard.sim.building.ContentRef;
import com.xpdustry.shard.sim.unit.Unit;
import mindustry.world.blocks.logic.LogicBlock.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * L1 for M0 (ARCHITECTURE.md §10), the composite gate: fortress hosted for survival by vanilla and by Shard, compared
 * frame by frame ({@link M0Run}) from the load until its first wave is dead, with no tolerance. In it:
 * <ul>
 *   <li>a player joins, and its core unit builds from the queue its client sends in {@code clientSnapshot}s (W2.14's
 *   {@code Snapshot} on Shard's side): an unloader under the core, a conveyor line down to a router, three duos around
 *   the router, a micro processor, six solar panels, a lancer and a power node, which links them as it is placed;</li>
 *   <li>the processor comes with its program and a link to the line's second conveyor in its plan's config, as a
 *   schematic's processor does, and turns that conveyor off for a second, then on for two, as long as it stands;</li>
 *   <li>the lancer reloads on the panels' power;</li>
 *   <li>the countdown runs out, wave 1's daggers walk the flowfield to the core (W2.17), and the turrets kill them.</li>
 * </ul>
 * {@link M0RigMapOracleTest} plays {@code rig/scenarios/m0.rig} on its own map the same way. The test then checks
 * that each piece did what it is there for, so a fixture that stops exercising one can't pass.
 */
class M0OracleTest{
    static final String PROGRAM = """
        control enabled conveyor1 0
        wait 1
        control enabled conveyor1 1
        wait 2
        """;

    @Test
    void aPlayersLineAProcessorAndAPoweredLancerAgainstFortressWaveOne(){
        var run = new M0Run("fortress");
        int cx = run.coreX, cy = run.coreY;
        assertEquals(List.of(), run.removed, "fortress's unported buildings: its mechanical drill is ported (W3.3)");
        run.items("copper", 1000);
        run.items("lead", 400);
        run.items("silicon", 300);
        run.items("titanium", 100);

        // the line: an unloader under the core, four conveyors down, a router with a duo on three sides
        run.plan("unloader", cx, cy - 2, 0, content.item("copper"), ContentRef.item(M0Run.EPOCH.item("copper").id()));
        for(int y = cy - 3; y >= cy - 6; y--) run.plan("conveyor", cx, y, 3);
        run.plan("router", cx, cy - 7, 0);
        run.plan("duo", cx - 1, cy - 7, 0);
        run.plan("duo", cx + 1, cy - 7, 0);
        run.plan("duo", cx, cy - 8, 0);
        // the processor, linked to the conveyor two tiles to its left: links are relative in a plan's config
        int px = cx + 2, py = cy - 4;
        run.plan("micro-processor", px, py, 0, mindustry.world.blocks.logic.LogicBlock.compress(PROGRAM, Seq.with(new LogicLink(-2, 0, "conveyor1", true))),
            LogicConfig.compress(PROGRAM, List.of(new LogicConfig.Link("conveyor1", -2, 0))));
        // power: six panels in a row, the lancer above them, and a node placed last, which links both graphs
        for(int x = cx + 4; x <= cx + 9; x++) run.plan("solar-panel", x, cy - 11, 0);
        run.plan("lancer", cx + 5, cy - 9, 0);
        run.plan("power-node", cx + 3, cy - 10, 0);

        int lineX = cx, lineY = py;
        int[] toggles = {0};
        boolean[] lastEnabled = {true};
        int[] spawned = {0};
        var wave = new ArrayList<Integer>();
        int last = run.until(30_000, () -> {
            var conveyor = run.vanilla(lineX, lineY);
            if(conveyor != null && conveyor.block == mindustry.content.Blocks.conveyor && conveyor.enabled != lastEnabled[0]){
                toggles[0]++;
                lastEnabled[0] = conveyor.enabled;
            }
            for(int i = 0; i < run.shard.units().size(); i++){
                Unit unit = run.shard.units().get(i);
                if(unit.team == run.rules.waveTeam && !wave.contains(unit.id())) wave.add(unit.id());
            }
            spawned[0] = wave.size();
            assertNotNull(state.rules.defaultTeam.core(), "the core stands at frame " + run.frame);
            return spawned[0] > 0 && mindustry.gen.Groups.unit.count(u -> u.team == state.rules.waveTeam) == 0 && mindustry.gen.Groups.bullet.isEmpty();
        });
        run.chunks();

        int duoShots = 0, lancerShots = 0;
        for(int[] duo : new int[][]{{cx - 1, cy - 7}, {cx + 1, cy - 7}, {cx, cy - 8}}){
            if(run.ours(duo[0], duo[1]) instanceof TurretBuild turret) duoShots += turret.totalShots;
        }
        if(run.ours(cx + 5, cy - 9) instanceof TurretBuild lancer) lancerShots = lancer.totalShots;
        var processor = (LogicBuild)run.vanilla(px, py);
        System.out.println("M0 on fortress: " + last + " frames; wave 1 (" + spawned[0] + " units) dead; duo shots " + duoShots + ", lancer shots "
            + lancerShots + ", conveyor toggles " + toggles[0] + "; Calls " + run.calls);

        assertEquals(run.plans.size(), run.calls.getOrDefault("ConstructFinish", 0), "every plan was built: " + run.calls);
        assertTrue(processor.links.contains(l -> l.valid && l.name.equals("conveyor1")), "the processor's link followed the conveyor it was built for");
        assertTrue(toggles[0] >= 20, "the processor turned the conveyor off and on: " + toggles[0]);
        assertTrue(run.calls.getOrDefault("PlayerSpawn", 0) >= 1, "the player spawned: " + run.calls);
        assertTrue(run.calls.getOrDefault("SpawnEffect", 0) >= 2, "wave 1 spawned: " + run.calls);
        assertTrue(spawned[0] >= 2, "wave 1's units: " + spawned[0]);
        assertTrue(run.calls.getOrDefault("UnitDeath", 0) >= 1, "a wave unit died: " + run.calls);
        assertTrue(duoShots > 0, "the duos, fed by the line, fired");
        assertTrue(lancerShots > 0, "the lancer fired on solar power");
        assertTrue(run.vanilla(cx + 3, cy - 10).power.links.size >= 2, "the node linked the panels and the lancer");
    }
}
