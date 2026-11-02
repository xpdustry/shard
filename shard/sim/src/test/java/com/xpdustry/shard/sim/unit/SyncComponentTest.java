package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The generated {@code interpolate()} a server runs on remote units (MechUnit, UnitEntity): the first read snaps the
 * unit to it; after two, each tick lerps the position and slerps the rotations from the last values toward the
 * targets, by the ticks since the read over the ticks between the reads, up to twice the way.
 */
class SyncComponentTest{
    static final ContentEpoch V = VanillaWorlds.VANILLA;

    World world;
    Unit dagger;

    @BeforeEach
    void world(){
        world = UnitWorlds.world(32, 32, 1);
        dagger = Unit.spawn(world, V.unit("dagger"), 1, 100f, 100f, 0f);
        // an added player, or the unit drops the control for its AI (isValidController)
        Player player = Player.create(world);
        player.team = 1;
        player.add();
        player.unit(dagger);
        world.tick();
        assertTrue(dagger.isRemote());
    }

    /** PlayerComp.java:308: a unit a player takes over stays where it is, though its velocity would move it. */
    @Test
    void takingControlPinsTheUnitWhereItIs(){
        Unit flare = Unit.spawn(world, V.unit("flare"), 1, 60f, 60f, 30f);
        world.tick();
        flare.vel.set(1.5f, -0.5f);
        float x = flare.x, y = flare.y, rotation = flare.rotation;
        dagger.player().unit(flare);
        for(int i = 0; i < 3; i++) world.tick();
        assertEquals(List.of(x, y, rotation), List.of(flare.x, flare.y, flare.rotation));
        assertNotEquals(0f, flare.vel.len(), "the velocity is still there, and drags as usual");
    }

    /** The first read after taking control interpolates from the snap, a tick of spacing later. */
    @Test
    void theFirstReadInterpolatesFromWhereControlWasTaken(){
        RemoteSync.read(dagger, 30f, 45f, 120f, 110f);
        assertEquals(1, dagger.attachment(RemoteSync.KEY).updateSpacing);
        var mech = (MoveShape.Mech)dagger.shape;
        float rotation = dagger.rotation, base = mech.baseRotation;
        for(int tick = 0; tick <= 3; tick++){
            world.tick();
            float alpha = Math.min(tick, 2f);
            assertEquals(Mathf.lerp(100f, 120f, alpha), dagger.x, 1e-3, "tick " + tick);
            assertEquals(Mathf.lerp(100f, 110f, alpha), dagger.y, 1e-3, "tick " + tick);
            assertEquals(Mathf.slerp(rotation, 45f, alpha), dagger.rotation, 1e-3, "tick " + tick);
            assertEquals(Mathf.slerp(base, 30f, alpha), mech.baseRotation, 1e-3, "tick " + tick);
        }
    }

    /** A read in the tick control was taken has no spacing: the generated code's other branch snaps to it. */
    @Test
    void aReadInTheTickOfTakingControlSnaps(){
        Unit dagger2 = Unit.spawn(world, V.unit("dagger"), 1, 60f, 60f, 0f);
        world.tick();
        dagger.player().unit(dagger2);
        RemoteSync.read(dagger2, 30f, 45f, 120f, 110f);
        world.tick();
        assertEquals(120f, dagger2.x);
        assertEquals(110f, dagger2.y);
        assertEquals(45f, dagger2.rotation);
        assertEquals(30f, ((MoveShape.Mech)dagger2.shape).baseRotation);
    }

    @Test
    void laterReadsInterpolateUpToTwiceTheWay(){
        var mech = (MoveShape.Mech)dagger.shape;
        // a first read where the unit already is, so its extrapolation (twice a one-tick step since the snap) is nil
        dagger.rotation = 350f;
        mech.baseRotation = 0f;
        RemoteSync.read(dagger, 0f, 350f, 100f, 100f);
        for(int i = 0; i < 4; i++) world.tick();
        RemoteSync.read(dagger, 90f, 10f, 140f, 80f);
        RemoteSync sync = dagger.attachment(RemoteSync.KEY);
        assertEquals(4, sync.updateSpacing);
        for(int tick = 0; tick <= 10; tick++){
            world.tick();
            float alpha = Math.min(tick / 4f, 2f);
            assertEquals(Mathf.lerp(100f, 140f, alpha), dagger.x, 1e-3, "tick " + tick);
            assertEquals(Mathf.lerp(100f, 80f, alpha), dagger.y, 1e-3, "tick " + tick);
            assertEquals(Mathf.slerp(350f, 10f, alpha), dagger.rotation, 1e-3, "the short way round, tick " + tick);
            assertEquals(Mathf.slerp(0f, 90f, alpha), mech.baseRotation, 1e-3, "tick " + tick);
        }
        assertEquals(180f, dagger.x, 1e-3, "twice the step, and no further");
    }

    @Test
    void onlyPlayersUnitsAreRemote(){
        Unit ai = Unit.spawn(world, V.unit("flare"), 1, 60f, 60f, 0f);
        world.tick();
        float x = ai.x;
        RemoteSync.read(ai, 0f, 0f, 200f, 200f);
        world.tick();
        assertNotEquals(200f, ai.x, "an AI unit moves by its AI: " + x + " -> " + ai.x);
    }

    @Test
    void aForkKeepsTheSync(){
        RemoteSync.read(dagger, 0f, 0f, 100f, 100f);
        world.tick();
        RemoteSync.read(dagger, 0f, 0f, 160f, 100f);
        World fork = world.fork();
        assertEquals(world.hash(), fork.hash());
        world.tick();
        fork.tick();
        assertEquals(world.hash(), fork.hash());
    }
}
