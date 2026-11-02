package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.tile.VanillaWorlds;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The movement shapes' hooks, one rule each. {@code ShapeTraceOracleTest} holds whole traces of every shape to vanilla;
 * these pin the rules a reader looks for.
 */
class MoveShapeTest{
    static final ContentEpoch V = VanillaWorlds.VANILLA;

    @Test
    void aMechsLegsWalkOnlyAfterAControlledMove(){
        World world = UnitWorlds.world(32, 32, 1);
        Unit pushed = Unit.spawn(world, V.unit("dagger"), 1, 100f, 100f, 0f);
        Unit walking = Unit.spawn(world, V.unit("dagger"), 1, 100f, 160f, 0f);
        walking.controller(new Steer(new Vec2(0f, 0.5f)));
        for(int i = 0; i < 40; i++){
            pushed.vel.set(0f, 0.5f);
            world.tick();
        }
        var legs = (MoveShape.Mech)pushed.shape;
        assertEquals(0f, legs.baseRotation, "pushed, not walked: the legs stay");
        assertEquals(0f, legs.walkTime);
        var walked = (MoveShape.Mech)walking.shape;
        assertEquals(90f, walked.baseRotation, 1e-3f, "the legs turned toward the walk");
        assertTrue(walked.walkTime > 5f);
        assertEquals(0f, walking.rotation, "the body only turns by lookAt");
    }

    @Test
    void aMechRotatesItsLegsNotItsBody(){
        World world = UnitWorlds.world(32, 32, 2);
        Unit dagger = Unit.spawn(world, V.unit("dagger"), 1, 100f, 100f, 0f);
        dagger.rotateMove(new Vec2(0f, 0.5f));
        assertEquals(0f, dagger.rotation);
        assertEquals(V.unit("dagger").rotateSpeed(), ((MoveShape.Mech)dagger.shape).baseRotation);
    }

    @Test
    void unloadedLegsAreResetShortAndDrawTheStepPhase(){
        World world = UnitWorlds.world(32, 32, 3);
        Unit atrax = Unit.spawn(world, V.unit("atrax"), 1, 100f, 100f, 0f);
        Rand expected = world.rand().copy();
        atrax.unloaded();
        var legs = (MoveShape.Legs)atrax.shape;
        assertEquals(expected.random(100f), legs.totalLength);
        for(MoveShape.Leg leg : legs.legs) assertTrue(leg.base.dst(atrax.x, atrax.y) < V.unit("atrax").legBaseOffset() + 1.01f);
        Unit dagger = Unit.spawn(world, V.unit("dagger"), 1, 50f, 50f, 0f);
        Rand before = world.rand().copy();
        dagger.unloaded();
        assertEquals(before.getState(0), world.rand().getState(0), "other shapes draw nothing when unloaded");
    }

    @Test
    void impulsesDivideByMassAndMoveRemoteUnitsAtOnce(){
        World world = UnitWorlds.world(32, 32, 4);
        Unit dagger = Unit.spawn(world, V.unit("dagger"), 1, 100f, 100f, 0f);
        float mass = dagger.mass();
        dagger.impulse(mass * 2f, -mass);
        assertEquals(2f, dagger.vel.x, 1e-5f);
        assertEquals(-1f, dagger.vel.y, 1e-5f);
        dagger.impulseNet(new Vec2(mass, 0f));
        assertEquals(100f, dagger.x, "an AI unit only gets the velocity");
        dagger.controller(new PlayerControl(Player.create(world)));
        dagger.impulseNet(new Vec2(mass, 0f));
        assertEquals(101f, dagger.x, 1e-4f, "a player's unit moves as its client will");
    }

    @Test
    void navalUnitsAreFasterExceptOnShallowFloors(){
        World world = VanillaWorlds.of(32, 32, "deep-water");
        UnitWorlds.uncapped(world);
        Unit risso = Unit.spawn(world, V.unit("risso"), 1, 100f, 100f, 0f);
        assertEquals(1.3f, risso.floorSpeedMultiplier());
        world.buildings().setFloor(risso.tileX(), risso.tileY(), V.block("sand-water"), Cause.SERVER);
        assertEquals(1f, risso.floorSpeedMultiplier(), "sand water is shallow");
        world.buildings().setFloor(risso.tileX(), risso.tileY(), V.block("stone"), Cause.SERVER);
        assertTrue(risso.onSolid(), "land is solid for a boat");
    }

    @Test
    void tanksSlowOverWallsAndCrawlersOverDeepFloors(){
        World world = UnitWorlds.world(32, 32, 5);
        Unit stell = Unit.spawn(world, V.unit("stell"), 1, 100f, 100f, 0f);
        float free = stell.floorSpeedMultiplier();
        world.tick();
        assertEquals(free, stell.floorSpeedMultiplier(), "nothing solid under it");
        world.buildings().setBlock(stell.tileX() + 1, stell.tileY(), V.block("stone-wall"), 0, 0, Cause.SERVER);
        world.tick();
        // r = 1: one solid tile in nine, 1/9 / 0.55 of the way to crawlSlowdown 0.5
        assertEquals(free * Mathf.lerp(1f, 0.5f, (1f / 9f) / 0.55f), stell.floorSpeedMultiplier(), 1e-6f);
    }

    /** Moves its unit with one vector, every tick. */
    record Steer(Vec2 vector) implements UnitController{
        @Override
        public void updateUnit(Unit unit){
            unit.moveAt(vector);
        }

        @Override
        public UnitController copy(CopyContext context){
            return this;
        }

        @Override
        public void hash(Hasher hasher){
        }
    }
}
