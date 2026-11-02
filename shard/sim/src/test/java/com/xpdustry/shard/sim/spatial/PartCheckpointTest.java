package com.xpdustry.shard.sim.spatial;

import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checkpoints carry the physics bodies in order with each layer's random, the grids' members (and their placement,
 * when a query placed them after the last tick), the bullet columns and the id pools; a restored world continues
 * like the original.
 */
class PartCheckpointTest{
    static World restore(World world){
        return Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
    }

    @Test
    void aBusyWorldRestoresAndContinuesAlike(){
        World world = PartStateTest.busy(21);
        assertTrue(world.ids().queuedFrees() + world.ids().free(Pooled.BULLET) > 0, "the bullet pool is in use");
        World restored = restore(world);
        assertEquals(world.hash(), restored.hash(), () -> world.hash().firstDifference(restored.hash()).orElse("?"));
        UnitPhysics physics = world.part(UnitPhysics.class), copy = restored.part(UnitPhysics.class);
        assertEquals(physics.bodies(), copy.bodies());
        for(int layer = 0; layer < UnitPhysics.LAYERS; layer++) assertEquals(physics.bodies(layer), copy.bodies(layer));
        assertEquals(world.ids().free(Pooled.BULLET), restored.ids().free(Pooled.BULLET));
        assertEquals(world.ids().peek(), restored.ids().peek());
        assertEquals(world.bullets().size(), restored.bullets().size());

        // the grids were placed between ticks: queries see the same units in the same order
        assertEquals(visits(world), visits(restored));

        var a = new SplittableRandom(5);
        var b = new SplittableRandom(5);
        for(int tick = 0; tick < 60; tick++){
            churn(world, a);
            churn(restored, b);
            world.tick();
            restored.tick();
            assertEquals(world.hash(), restored.hash(), "tick " + tick + ": " + world.hash().firstDifference(restored.hash()).orElse("?"));
        }
        assertEquals(visits(world), visits(restored));
    }

    @Test
    void aCheckpointBetweenTicksWithoutQueriesRestoresAlikeToo(){
        World world = PartStateTest.busy(22);
        world.tick();
        World restored = restore(world);
        assertEquals(world.hash(), restored.hash());
        assertEquals(visits(world), visits(restored));
    }

    static void churn(World world, SplittableRandom random){
        world.bullets().add(world.ids(), 0, 1, random.nextFloat() * 384f, random.nextFloat() * 384f, 1f, 1f, 30f, 5f,
            world.units().get(random.nextInt(world.units().size())));
        if(random.nextInt(3) == 0) world.bullets().remove(random.nextInt(world.bullets().size()));
        if(random.nextInt(10) == 0) world.units().get(random.nextInt(world.units().size())).remove();
    }

    /** The ids every query kind visits, in visit order. */
    static List<Integer> visits(World world){
        var out = new ArrayList<Integer>();
        SpatialIndex index = world.part(SpatialIndex.class);
        index.intersect(0f, 0f, 400f, 400f, unit -> out.add(unit.id()));
        out.add(-1);
        for(int team = 1; team <= 3; team++){
            index.nearby(team, 0f, 0f, 400f, 400f, unit -> out.add(unit.id()));
            out.add(-1);
        }
        return out;
    }
}
