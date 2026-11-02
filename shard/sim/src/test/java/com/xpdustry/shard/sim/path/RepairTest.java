package com.xpdustry.shard.sim.path;

import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.util.*;

import static com.xpdustry.shard.sim.path.PathWorlds.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The repair gate (ARCHITECTURE.md §9): after every flush of a random edit sequence, every field equals a full
 * rebuild and a textbook Dijkstra, distances and parents alike, for every cost class and both families, with the
 * optional layers off and on. The edits wall off pockets, so unreachable regions are compared too.
 */
class RepairTest{
    static List<Arguments> cases(){
        PathRules waves = PathRules.DEFAULT.withWaves(true);
        return List.of(
            Arguments.of("plain", 64, 64, 11L, waves),
            Arguments.of("tall", 37, 70, 12L, waves),
            Arguments.of("layers", 60, 48, 13L, waves.withSizeAwarePaths(true).withThreatPaths(true)),
            Arguments.of("capture-limit", 56, 56, 14L, waves.withCoreCapture(true).withLimitMapArea(6, 4, 40, 44)),
            Arguments.of("no-waves", 50, 50, 15L, PathRules.DEFAULT)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void repairEqualsRebuildOverRandomEdits(String name, int width, int height, long seed, PathRules rules){
        World world = world(width, height, seed);
        Churn.generate(world);
        PathService paths = paths(world);
        paths.rules(rules.withRtsPopBudget(700));
        paths.invalidateAll();
        for(int team = 1; team <= 3; team++){
            for(PathCost cls : PathCost.values()) paths.field(team, cls);
        }
        var rts = new ArrayDeque<Integer>();
        int unreached = 0, repairs = 0;
        for(int tick = 0; tick < 260; tick++){
            int edits = 1 + world.rand().random(3);
            for(int i = 0; i < edits; i++) Churn.edit(world);
            if(tick % 97 == 50) Churn.flood(world);
            // RTS orders come and go; a small budget keeps some building across ticks and edits
            if(tick % 9 == 0){
                int dest = world.rand().random(world.tiles().size() - 1);
                rts.add(paths.field(1 + world.rand().random(2), PathCost.of(world.rand().random(4)), dest));
            }
            if(rts.size() > 4) paths.release(rts.poll());
            if(tick == 130) paths.rules(paths.rules().withSizeAwarePaths(!paths.rules().sizeAwarePaths()).withWave(7));
            world.tick();
            String where = name + " tick " + tick;
            References.checkGrid(world, paths, where);
            for(Flowfield field : paths.slots()){
                if(field == null) continue;
                if(!field.rts) assertEquals(paths.targetsOf(world, field), field.targets, where + ": targets");
                unreached += References.checkField(paths, field, where + " " + field.cls + (field.rts ? " rts " : " core ") + field.team);
                if(field.state == Flowfield.State.READY) repairs++;
            }
        }
        FieldEngine engine = paths.engine();
        assertTrue(unreached > 0, "no field had an unreachable region: the test lost its pockets");
        assertTrue(repairs > 3000, "too few ready fields checked: " + repairs);
        assertTrue(engine.repairs > 2000 && engine.invalidated > 10_000, "too little repaired: " + engine.repairs + " repairs, "
            + engine.invalidated + " tiles invalidated");
        assertTrue(paths.fallbacks > 2, "the floods and the rule change should have forced full rebuilds: " + paths.fallbacks);
    }
}
