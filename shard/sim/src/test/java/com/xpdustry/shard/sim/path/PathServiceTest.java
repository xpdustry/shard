package com.xpdustry.shard.sim.path;

import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.toy.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static com.xpdustry.shard.sim.path.PathWorlds.*;
import static org.junit.jupiter.api.Assertions.*;

/** {@link PathService}'s API on small hand-made maps: descent, targets, RTS fields, line of sight, rules and layers. */
class PathServiceTest{
    static final PathRules WAVES = PathRules.DEFAULT.withWaves(true);

    /** A 20×20 stone map with team 1's core at (16, 10), rules applied. */
    static World coreMap(PathRules rules){
        World world = filled(20, 20, "stone");
        paths(world).rules(rules);
        place(world, "core-shard", 1, 16, 10);
        world.tick();
        return world;
    }

    static int pos(World world, int x, int y){
        return world.tiles().pos(x, y);
    }

    static int[] ints(IntSeq seq){
        return Arrays.copyOf(seq.items, seq.size);
    }

    @Test
    void nextDescendsToTheTarget(){
        World world = coreMap(WAVES);
        PathService paths = paths(world);
        int field = paths.field(2, PathCost.GROUND);
        assertFalse(paths.ready(field));
        assertEquals(pos(world, 2, 10), paths.next(field, pos(world, 2, 10)), "a pending field keeps units still");
        world.tick();
        assertTrue(paths.ready(field));
        int at = pos(world, 2, 3), steps = 0;
        while(paths.distance(field, at) > 0){
            int next = paths.next(field, at);
            assertTrue(paths.distance(field, next) < paths.distance(field, at), "step " + steps + " climbs");
            at = next;
            assertTrue(++steps < 40, "no arrival after 40 steps");
        }
        assertEquals(pos(world, 16, 10), at);
        assertEquals(at, paths.next(field, at), "a unit on its target stays");
    }

    @Test
    void nextCutsCornersOnlyPastPassableTiles(){
        World world = coreMap(WAVES);
        PathService paths = paths(world);
        int field = paths.field(2, PathCost.GROUND);
        world.tick();
        int from = pos(world, 12, 8);
        assertEquals(pos(world, 13, 9), paths.next(field, from), "open ground: the diagonal");
        wall(world, 13, 8, "stone-wall");
        world.tick();
        assertEquals(pos(world, 12, 9), paths.next(field, from), "a wall beside the diagonal: around the corner");
    }

    @Test
    void groundUnitsDoNotStepFromSafeGroundIntoDanger(){
        World world = coreMap(WAVES);
        for(int y = 0; y < 20; y++) floor(world, 8, y, "deep-water");
        world.tick();
        PathService paths = paths(world);
        int ground = paths.field(2, PathCost.GROUND), legs = paths.field(2, PathCost.LEGS);
        world.tick();
        int shore = pos(world, 7, 10);
        assertTrue(paths.distance(ground, shore) > 0, "the field crosses the deep water");
        assertEquals(shore, paths.next(ground, shore), "ground units hold at the shore");
        assertEquals(pos(world, 8, 10), paths.next(legs, shore), "legged units wade in");
        assertEquals(pos(world, 9, 10), paths.next(ground, pos(world, 8, 10)), "a ground unit already in danger moves on");
    }

    @Test
    void waveFieldsTargetEnemyCoresAndSpawns(){
        World world = filled(30, 20, "stone");
        PathService paths = paths(world);
        paths.rules(WAVES);
        place(world, "core-shard", 1, 5, 10);
        place(world, "core-shard", 2, 25, 10);
        place(world, "core-shard", 0, 15, 15);
        overlay(world, 15, 3, "spawn");
        int one = paths.field(1, PathCost.GROUND), two = paths.field(2, PathCost.GROUND), three = paths.field(3, PathCost.NAVAL);
        world.tick();
        int core1 = pos(world, 5, 10), core2 = pos(world, 25, 10), derelict = pos(world, 15, 15), spawn = pos(world, 15, 3);
        assertArrayEquals(new int[]{spawn, core2}, ints(paths.get(one).targets), "the default team also targets spawns");
        assertArrayEquals(new int[]{core1}, ints(paths.get(two).targets));
        assertArrayEquals(new int[]{core1, core2}, ints(paths.get(three).targets));
        assertEquals(0, paths.distance(one, spawn));
        assertTrue(paths.distance(two, spawn) > 0);

        paths.rules(WAVES.withCoreCapture(true));
        world.tick();
        assertArrayEquals(new int[]{core1, derelict}, ints(paths.get(two).targets), "core capture targets derelict cores");
        paths.rules(PathRules.DEFAULT);
        world.tick();
        assertArrayEquals(new int[]{core2}, ints(paths.get(one).targets), "no waves, no spawn targets");
        assertTrue(paths.distance(one, spawn) > 0, "the spawn is plain ground now");
    }

    @Test
    void fieldsAreSharedAndRtsFieldsExpireThirtySecondsAfterTheLastRelease(){
        World world = filled(24, 24, "stone");
        PathService paths = paths(world);
        int dest = pos(world, 20, 20);
        int a = paths.field(1, PathCost.GROUND, dest), b = paths.field(1, PathCost.GROUND, dest);
        int core = paths.field(1, PathCost.GROUND);
        assertEquals(a, b, "one field per team, class and destination");
        assertNotEquals(a, paths.field(2, PathCost.GROUND, dest));
        assertNotEquals(a, paths.field(1, PathCost.LEGS, dest));
        assertEquals(4, paths.fields());
        paths.release(a);
        paths.release(b);
        assertThrows(IllegalStateException.class, () -> paths.release(a));
        paths.release(core);
        int alive = 0;
        while(paths.slots().get(a) != null){
            world.tick();
            assertTrue(++alive <= PathService.EXPIRY_TICKS + 1, "the field outlived its expiry");
        }
        assertTrue(alive >= PathService.EXPIRY_TICKS, "expired after " + alive + " ticks");
        assertThrows(IllegalArgumentException.class, () -> paths.distance(a, dest));
        assertNotNull(paths.slots().get(core), "wave-AI fields live as long as the world");
        // the other two never had their references dropped
        assertEquals(3, paths.fields());
        assertEquals(a, paths.field(1, PathCost.HOVER, dest), "a freed handle is reused");
    }

    @Test
    void reacquiringBeforeExpiryKeepsTheField(){
        World world = filled(24, 24, "stone");
        PathService paths = paths(world);
        int dest = pos(world, 3, 3), a = paths.field(1, PathCost.GROUND, dest);
        paths.release(a);
        for(int i = 0; i < PathService.EXPIRY_TICKS - 10; i++) world.tick();
        assertEquals(a, paths.field(1, PathCost.GROUND, dest));
        for(int i = 0; i < 2 * PathService.EXPIRY_TICKS; i++) world.tick();
        assertTrue(paths.ready(a));
        assertEquals(0, paths.distance(a, dest));
    }

    @Test
    void rtsFieldsShareThePopBudgetInRequestOrder(){
        World world = filled(64, 64, "stone");
        PathService paths = paths(world);
        paths.rules(PathRules.DEFAULT.withRtsPopBudget(500));
        world.tick();
        int destA = pos(world, 60, 60), destB = pos(world, 2, 2);
        int a = paths.field(1, PathCost.GROUND, destA), b = paths.field(1, PathCost.LEGS, destB);
        Flowfield fa = paths.get(a), fb = paths.get(b);
        world.tick();
        assertEquals(500, fa.pops);
        assertEquals(0, fb.pops, "the later request waits for the earlier one");
        assertEquals(0, paths.distance(a, destA), "a building field answers for its settled tiles");
        assertEquals(-1, paths.distance(a, destB), "and not for the rest");
        int near = pos(world, 58, 58);
        assertTrue(paths.distance(a, near) > 0);
        assertNotEquals(near, paths.next(a, near), "units on settled tiles move already");
        assertEquals(destB, paths.next(a, destB), "units on unsettled tiles wait");
        int ticks = 1;
        while(true){
            world.tick();
            ticks++;
            if(paths.ready(a)) break;
            assertEquals(500L * ticks, fa.pops + fb.pops, "tick " + ticks + ": the budget is spent whole");
        }
        assertEquals(0, fa.pops, "a finished build keeps no count");
        assertTrue(fb.pops > 0, "the budget left by the finished field goes to the next");
        while(!paths.ready(b)) world.tick();
        References.checkField(paths, fa, "a");
        References.checkField(paths, fb, "b");
    }

    @Test
    void anEditNearABuildingFieldFinishesItOnTheNewCosts(){
        World world = filled(64, 64, "stone");
        PathService paths = paths(world);
        paths.rules(PathRules.DEFAULT.withRtsPopBudget(300));
        world.tick();
        int dest = pos(world, 32, 32), a = paths.field(1, PathCost.GROUND, dest);
        world.tick();
        assertFalse(paths.ready(a));
        place(world, "copper-wall", 2, 33, 32);
        world.tick();
        assertTrue(paths.ready(a));
        References.checkField(paths, paths.get(a), "after the edit");
        // an edit far from what the build has explored leaves it building
        int b = paths.field(1, PathCost.GROUND, pos(world, 4, 4));
        world.tick();
        place(world, "copper-wall", 2, 60, 60);
        world.tick();
        assertFalse(paths.ready(b));
        References.checkField(paths, paths.get(b), "far edit");
    }

    @Test
    void lineOfSightFollowsControlPathfinderCosts(){
        World world = filled(20, 20, "stone");
        PathService paths = paths(world);
        int from = pos(world, 2, 10), to = pos(world, 17, 10);
        assertTrue(paths.lineOfSight(1, PathCost.GROUND, from, to));
        assertTrue(paths.lineOfSight(1, PathCost.GROUND, from, to, 16, 80, 136, 80, 20));
        wall(world, 9, 12, "stone-wall");
        world.tick();
        assertTrue(paths.lineOfSight(1, PathCost.GROUND, from, to), "a wall two tiles off the line");
        wall(world, 9, 11, "stone-wall");
        world.tick();
        assertFalse(paths.lineOfSight(1, PathCost.GROUND, from, to), "tiles next to walls cost more than open ground");
        assertTrue(paths.lineOfSight(1, PathCost.GROUND, from, to, 16, 80, 136, 80, 8), "a thin hitbox passes the wall");
        assertFalse(paths.lineOfSight(1, PathCost.GROUND, from, to, 16, 80, 136, 80, 20), "a wide one doesn't");
        wall(world, 9, 10, "stone-wall");
        world.tick();
        assertFalse(paths.lineOfSight(1, PathCost.GROUND, from, to, 16, 80, 136, 80, 8));
        assertTrue(paths.lineOfSight(1, PathCost.LEGS, from, to), "legs step over walls");
    }

    @Test
    void rulesTakeEffectAtTheNextFlush(){
        World world = filled(20, 20, "stone");
        PathService paths = paths(world);
        PathRules next = WAVES.withSizeAwarePaths(true);
        paths.rules(next);
        assertEquals(PathRules.DEFAULT, paths.rules());
        world.tick();
        assertEquals(next, paths.rules());
    }

    @Test
    void theThreatLayerChargesForEnemyTurretsOnly(){
        World world = filled(40, 40, "stone");
        PathService paths = paths(world);
        paths.rules(WAVES.withThreatPaths(true));
        Building duo = place(world, "duo", 1, 10, 10);
        place(world, "duo", 0, 30, 30);
        place(world, "core-shard", 1, 20, 35);
        int two = paths.field(2, PathCost.GROUND), one = paths.field(1, PathCost.GROUND), rts = paths.field(2, PathCost.GROUND, pos(world, 5, 5));
        world.tick();
        world.tick();
        FieldEngine engine = paths.engine();
        Flowfield enemy = paths.get(two), own = paths.get(one);
        int covered = pos(world, 10, 14), derelictCovered = pos(world, 30, 26);
        assertEquals(engine.baseCost(enemy, covered) + Threat.COST, engine.cost(enemy, covered));
        assertEquals(engine.baseCost(own, covered), engine.cost(own, covered), "a team's own turrets are free");
        assertEquals(engine.baseCost(enemy, derelictCovered), engine.cost(enemy, derelictCovered), "derelict turrets don't count");
        assertEquals(engine.baseCost(paths.get(rts), covered), engine.cost(paths.get(rts), covered), "RTS fields ignore threat");
        remove(world, duo);
        world.tick();
        assertEquals(engine.baseCost(enemy, covered), engine.cost(enemy, covered));
        References.checkField(paths, enemy, "after the turret went");
    }

    @Test
    void softClearanceChargesLargeGroundUnitsNearWalls(){
        World world = filled(20, 20, "stone");
        PathService paths = paths(world);
        paths.rules(WAVES.withSizeAwarePaths(true));
        wall(world, 10, 10, "stone-wall");
        int large = paths.field(2, PathCost.GROUND_LARGE), ground = paths.field(2, PathCost.GROUND);
        world.tick();
        FieldEngine engine = paths.engine();
        Flowfield field = paths.get(large);
        int beside = pos(world, 11, 11), open = pos(world, 5, 5), edge = pos(world, 0, 5);
        assertEquals(engine.baseCost(field, beside) + PathCost.CLEARANCE_COST, engine.cost(field, beside), "a diagonal neighbour of a wall");
        assertEquals(engine.baseCost(field, edge) + PathCost.CLEARANCE_COST, engine.cost(field, edge), "the map's edge");
        assertEquals(engine.baseCost(field, open), engine.cost(field, open));
        assertFalse(paths.get(ground).large);
        paths.rules(WAVES);
        world.tick();
        assertEquals(engine.baseCost(field, beside), engine.cost(field, beside), "off by default");
    }

    @Test
    void toyWorldsHaveNoPaths(){
        World world = Worlds.quiet(1);
        PathService paths = world.part(PathService.class);
        assertFalse(paths.active());
        assertEquals(Persistence.DERIVED, paths.persistence(), "an inactive service adds nothing to the hash");
        assertThrows(IllegalStateException.class, () -> paths.field(1, PathCost.GROUND));
        paths.tileChanged(3);
        world.tick();
        assertEquals(0, paths.fields());
    }
}
