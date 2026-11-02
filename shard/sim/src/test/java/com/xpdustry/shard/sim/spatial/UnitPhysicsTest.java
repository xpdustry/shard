package com.xpdustry.shard.sim.spatial;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.tile.VanillaWorlds;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit pushing with vanilla's timing: a body joins its layer at the next begin, the step computed there moves the unit
 * at the begin after, removed units lose their body, layers never interact. {@code PhysicsTraceOracleTest} compares
 * whole traces with vanilla.
 */
class UnitPhysicsTest{
    static final UnitDef DAGGER = VanillaWorlds.VANILLA.unit("dagger"), FLARE = VanillaWorlds.VANILLA.unit("flare");

    @Test
    void anOverlappingPairSeparatesOneTickLate(){
        World world = UnitWorlds.world(32, 32, 1);
        Unit a = Unit.spawn(world, DAGGER, 1, 100f, 100f, 0f), b = Unit.spawn(world, DAGGER, 1, 104f, 100f, 0f);
        world.tick();
        assertEquals(100f, a.x, "the first step is computed, not applied");
        assertEquals(104f, b.x);
        world.tick();
        // rs = 2 * 8 * 0.6 = 9.6, dst = 4, equal masses: each moves (rs - dst) / 2 / 1.25 = 2.24
        assertEquals(100f - 2.24f, a.x, 1e-4f);
        assertEquals(104f + 2.24f, b.x, 1e-4f);
        assertEquals(100f, a.y);
    }

    @Test
    void layersNeverInteract(){
        World world = UnitWorlds.world(32, 32, 2);
        Unit ground = Unit.spawn(world, DAGGER, 1, 100f, 100f, 0f), air = Unit.spawn(world, FLARE, 1, 102f, 100f, 0f);
        // inert, or the flare's command AI wobbles it (WP54)
        ground.controller(new NoAi());
        air.controller(new NoAi());
        world.tick();
        world.tick();
        assertEquals(100f, ground.x);
        assertEquals(102f, air.x);
        UnitPhysics physics = world.part(UnitPhysics.class);
        assertEquals(1, physics.bodies(UnitPhysics.LAYER_GROUND));
        assertEquals(1, physics.bodies(UnitPhysics.LAYER_FLYING));
        air.elevation = 0f;
        world.tick();
        assertEquals(2, physics.bodies(UnitPhysics.LAYER_GROUND), "a landed unit changes layer at the next begin");
        assertEquals(0, physics.bodies(UnitPhysics.LAYER_FLYING));
    }

    @Test
    void removedUnitsLoseTheirBodyAndUnitsWithoutPhysicsGetNone(){
        World world = UnitWorlds.world(32, 32, 3);
        Unit a = Unit.spawn(world, DAGGER, 1, 100f, 100f, 0f), b = Unit.spawn(world, DAGGER, 1, 104f, 100f, 0f);
        Unit missile = Unit.spawn(world, VanillaWorlds.VANILLA.unit("quell-missile"), 1, 104f, 100f, 0f);
        assertFalse(missile.hasPhysicsRef);
        UnitPhysics physics = world.part(UnitPhysics.class);
        world.tick();
        b.remove();
        world.tick();
        assertFalse(b.hasPhysicsRef);
        assertEquals(1, physics.bodies());
        assertEquals(1, physics.bodies(UnitPhysics.LAYER_GROUND));
        assertNotEquals(100f, a.x, "the push computed before the removal still lands");
        assertNotEquals(104f, b.x, "vanilla moves the stale unit too, then drops its body");
    }

    @Test
    void stackedUnitsSeparateTheSameWayInAFork(){
        World world = UnitWorlds.world(32, 32, 4);
        for(int i = 0; i < 6; i++) Unit.spawn(world, DAGGER, 1, 120f, 120f, 0f);
        world.tick();
        World fork = world.fork();
        for(int i = 0; i < 30; i++){
            world.tick();
            fork.tick();
            assertEquals(world.hash(), fork.hash(), "tick " + i);
        }
        float spread = 0f;
        for(int i = 0; i < world.units().size(); i++) spread += Math.abs(world.units().get(i).x - 120f);
        assertTrue(spread > 10f, "stacked units did not separate: " + spread);
    }
}
