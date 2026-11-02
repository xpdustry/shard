package com.xpdustry.shard.sim.spatial;

import com.xpdustry.shard.sim.tile.VanillaWorlds;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The phase order of the spatial index and unit physics (the {@code sim.spatial} package comment), seen by probes: a
 * unit spawned in {@code WAVES} (before {@code PHYSICS}) and one spawned in {@code BULLETS} (after it), checked at the
 * end of {@code WAVES}, {@code UNITS} and {@code BULLETS} for three ticks.
 */
class PhaseOrderTest{
    static final List<String> SEEN = new ArrayList<>();

    @Test
    void midTickSpawnsJoinEachIndexAtItsNextFill(){
        SEEN.clear();
        var handlers = new ArrayList<PhaseHandler>();
        for(Phase phase : Phase.values()) handlers.addAll(Systems.builtIn().handlers(phase));
        handlers.add(new Probe(Phase.WAVES));
        handlers.add(new Probe(Phase.UNITS));
        handlers.add(new Probe(Phase.BULLETS));
        var systems = Systems.of(handlers, Systems.builtIn().parts());
        World world = UnitWorlds.world(32, 32, 1, systems);
        for(int i = 0; i < 3; i++) world.tick();
        assertEquals(List.of(
            // in Groups.unit and counted at once; the all-units grid from this tick's PHYSICS; nothing else yet
            "0 WAVES: units 1, counted 1, listed 0, in layers 0, all grid 0, team grid 0",
            "0 UNITS: units 1, counted 1, listed 0, in layers 0, all grid 1, team grid 0",
            "0 BULLETS: units 2, counted 2, listed 0, in layers 0, all grid 1, team grid 0",
            // next tick, TEAMS: physics flushed both bodies, the recount listed both and filled the team grid;
            // the all-units grid still has the members of the last PHYSICS fill until this tick's
            "1 WAVES: units 2, counted 2, listed 2, in layers 2, all grid 1, team grid 2",
            "1 UNITS: units 2, counted 2, listed 2, in layers 2, all grid 2, team grid 2",
            "1 BULLETS: units 2, counted 2, listed 2, in layers 2, all grid 2, team grid 2",
            "2 WAVES: units 2, counted 2, listed 2, in layers 2, all grid 2, team grid 2",
            "2 UNITS: units 2, counted 2, listed 2, in layers 2, all grid 2, team grid 2",
            "2 BULLETS: units 2, counted 2, listed 2, in layers 2, all grid 2, team grid 2"
        ), SEEN);
    }

    /** Spawns a dagger at tick 0 in WAVES and in BULLETS, and records what each index holds. Stateless. */
    record Probe(Phase phase) implements PhaseHandler{
        @Override
        public int order(){
            return 1000;
        }

        @Override
        public void run(World world){
            if(world.ticks() == 0 && phase != Phase.UNITS){
                float at = phase == Phase.WAVES ? 100f : 200f;
                Unit.spawn(world, VanillaWorlds.VANILLA.unit("dagger"), 1, at, at, 0f);
            }
            TeamUnits counts = world.part(TeamUnits.class);
            SpatialIndex index = world.part(SpatialIndex.class);
            int[] all = {0}, team = {0};
            index.intersect(-100f, -100f, 600f, 600f, unit -> all[0]++);
            index.nearby(1, -100f, -100f, 600f, 600f, unit -> team[0]++);
            SEEN.add(world.ticks() + " " + phase + ": units " + world.units().size() + ", counted " + counts.count(1)
                + ", listed " + counts.units(1).size() + ", in layers " + world.part(UnitPhysics.class).layered()
                + ", all grid " + all[0] + ", team grid " + team[0]);
        }
    }
}
