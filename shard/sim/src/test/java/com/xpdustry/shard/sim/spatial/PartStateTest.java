package com.xpdustry.shard.sim.spatial;

import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.tile.VanillaWorlds;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The checkpoint contract of the new world state: {@link TeamUnits}, {@link UnitPhysics}, {@link SpatialIndex}, the
 * {@code Bullets} table and the {@link EntityIds} pools are copied field by field by a fork, and their hashes cover
 * every authoritative field. The skipped fields are derived placement and scratch, rebuilt on demand.
 */
class PartStateTest{
    @Test
    void forksCopyEveryPartFieldByField(){
        World world = busy(21);
        World fork = world.fork();
        for(Class<? extends WorldPart> part : List.of(TeamUnits.class, UnitPhysics.class, SpatialIndex.class)){
            StateCoverage.assertForked(world, fork, world.part(part), fork.part(part), part.getSimpleName());
        }
        StateCoverage.assertForked(world, fork, world.bullets(), fork.bullets(), "bullets");
        StateCoverage.assertForked(world, fork, world.ids(), fork.ids(), "ids");
        assertEquals(world.hash(), fork.hash());
        for(int i = 0; i < 100; i++){
            world.tick();
            fork.tick();
            assertEquals(world.hash(), fork.hash(), "tick " + i + ": " + world.hash().firstDifference(fork.hash()).orElse("?"));
        }
    }

    @Test
    void hashesCoverEveryAuthoritativeField(){
        // inert units: AIs that fire drain the bullet pool, whose first entry the walk below needs (WP54)
        World world = busy(22, true);
        // a cap and a doomed unit, so those fields hold something
        TeamUnits counts = world.part(TeamUnits.class);
        UnitWorlds.cap(world, 1);
        world.part(com.xpdustry.shard.sim.rules.WorldRules.class).rules.waveTeam = 7;
        Unit.spawn(world, VanillaWorlds.VANILLA.unit("dagger"), 2, 30f, 30f, 0f);
        Unit.spawn(world, VanillaWorlds.VANILLA.unit("dagger"), 2, 30f, 30f, 0f);
        assertTrue(counts.doomed() > 0);

        check(counts, counts::hash, Set.of("typeIds", "byType", "touched", "touchedTeams", "touchedCount"));
        UnitPhysics physics = world.part(UnitPhysics.class);
        check(physics, physics::hash, Set.of("tilesWide", "tilesHigh", "tree", "xs", "ys", "radii", "order", "candidates"),
            Set.of(UnitPhysics.Body.class));
        SpatialIndex index = world.part(SpatialIndex.class);
        check(index, index::hash, Set.of("tilesWide", "tilesHigh", "cells", "sorted", "maxSize", "xs", "ys", "closestEnemies",
            "closestDepth", "tree", "treeBuilt"));
        var bullets = world.bullets();
        // a removed bullet waits in the limbo until the next tick frees it
        bullets.remove(0);
        check(bullets, bullets::hash, Set.of("index", "ids", "tilesWide", "tilesHigh",
            "rows", "objectRows", "types", "fillObjects", "fillCount", "quad", "quadBuilt"));
        var ids = world.ids();
        for(Pooled pool : Pooled.values()){
            ids.obtain(pool);
            ids.queueFree(pool);
        }
        ids.flushFrees();
        ids.queueFree(Pooled.BULLET);
        check(ids, ids::hash, Set.of("ordered"));
    }

    static void check(Object part, Consumer<Hasher> hash, Set<String> skip){
        check(part, hash, skip, Set.of());
    }

    static void check(Object part, Consumer<Hasher> hash, Set<String> skip, Set<Class<?>> into){
        int fields = StateCoverage.assertHashed(part, () -> {
            var hasher = new Hasher();
            hash.accept(hasher);
            return hasher.value();
        }, skip, into);
        assertTrue(fields > 3, part.getClass().getSimpleName() + ": only " + fields + " fields");
    }

    /** A world with units of every layer pushing each other, bullets fired and freed, and ticks run. */
    static World busy(long seed){
        return busy(seed, false);
    }

    static World busy(long seed, boolean inert){
        World world = UnitWorlds.world(48, 48, seed);
        var random = new SplittableRandom(seed);
        String[] types = {"dagger", "flare", "atrax", "risso", "mega", "elude"};
        for(int i = 0; i < 80; i++){
            Unit unit = Unit.spawn(world, VanillaWorlds.VANILLA.unit(types[i % types.length]), 1 + i % 3, 100f + random.nextFloat() * 60f,
                100f + random.nextFloat() * 60f, 0f);
            unit.vel.set(random.nextFloat(), random.nextFloat());
            if(inert) unit.controller(new NoAi());
        }
        for(int tick = 0; tick < 20; tick++){
            for(int i = 0; i < 10; i++){
                world.bullets().add(world.ids(), 0, 1, random.nextFloat() * 384f, random.nextFloat() * 384f, 1f, 1f, 30f, 5f,
                    world.units().get(random.nextInt(world.units().size())));
            }
            for(int i = 0; i < 4 && world.bullets().size() > 0; i++) world.bullets().remove(random.nextInt(world.bullets().size()));
            if(tick % 5 == 0) world.units().get(random.nextInt(world.units().size())).remove();
            world.tick();
        }
        // queries between ticks re-place the expired grids
        world.part(SpatialIndex.class).intersect(0f, 0f, 400f, 400f, unit -> {});
        return world;
    }
}
