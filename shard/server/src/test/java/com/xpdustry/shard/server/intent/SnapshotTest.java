package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.server.session.Viewer;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code clientSnapshot}'s admission (NetServer.java:754-903): the unit moves toward the client's report by at most
 * {@code 1.1 × speed} per elapsed frame, through walls on the ground; strict servers keep their own position and
 * correct a client over 14 tiles off; the player's flags follow the client; the builder's queue becomes the client's.
 */
class SnapshotTest{
    static final long ALPHA = 7;
    static final Policy STRICT = Policy.VANILLA, LOOSE = new Policy(false, true, 6_000, 25, 60);

    IntentWorld w;
    Player player;
    Unit unit;
    int id;

    @BeforeEach
    void world(){
        w = new IntentWorld();
        player = w.joinWith(ALPHA, "alpha", "alpha", 100f, 100f);
        unit = player.unit();
    }

    Snapshot at(Policy policy, float x, float y){
        return new Snapshot(policy, id++, unit.id(), false, x, y, x + 40, y, 0, 0, 0, 0, -1, false, false, false, true, -1, 0, List.of(),
            x, y, 400, 300);
    }

    @Test
    void aReachableReportIsTheServersPosition(){
        // the first snapshot counts 16 ms: 0.96 frames at 3 × 1.1
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, at(STRICT, 102f, 101f), 1_000));
        assertEquals(102f, unit.x, 1e-4);
        assertEquals(101f, unit.y, 1e-4);
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, at(STRICT, 112f, 101f), 1_066));
        assertEquals(112f, unit.x, 1e-4);
        for(int i = 0; i < 4; i++) w.world.tick();
        assertEquals(112f, unit.x, 1e-4, "interpolated toward the server's own position");
        PlayerInput input = PlayerInput.of(player);
        assertEquals(0, input.clamped);
        assertEquals(0, input.corrections);
        assertEquals(0f, input.maxDeviation, 1e-4);
        assertEquals(400f, input.viewWidth);
        assertEquals(List.of(), w.drain());
    }

    @Test
    void theViewerSeesTheReportedView(){
        assertEquals(new com.xpdustry.shard.server.session.Viewer(ALPHA, player.team, player.x, player.y, 0f, 0f, player.admin),
            PlayerInput.viewer(ALPHA, player), "before any snapshot: a point at the player");
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, at(STRICT, 102f, 101f), 1_000));
        assertEquals(new com.xpdustry.shard.server.session.Viewer(ALPHA, player.team, 102f, 101f, 400f, 300f, player.admin),
            PlayerInput.viewer(ALPHA, player));
    }

    @Test
    void theViewerIsReusedUntilTheViewMoves(){
        // before its first snapshot a player has no input state to keep one in, and asking makes none (it's hashed)
        assertNull(player.attachment(PlayerInput.KEY));
        PlayerInput.viewer(ALPHA, player);
        assertNull(player.attachment(PlayerInput.KEY));
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, at(STRICT, 102f, 101f), 1_000));
        Viewer first = PlayerInput.viewer(ALPHA, player);
        assertSame(first, PlayerInput.viewer(ALPHA, player), "nothing changed: the same viewer");
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, at(STRICT, 110f, 101f), 1_066));
        Viewer moved = PlayerInput.viewer(ALPHA, player);
        assertNotSame(first, moved);
        assertEquals(new Viewer(ALPHA, player.team, 110f, 101f, 400f, 300f, player.admin), moved);
    }

    static float maxMove(long elapsed){
        return elapsed / 1000f * 60f * 3f * 1.1f;
    }

    @Test
    void aFarReportIsCutToTheUnitsSpeed(){
        w.admit(ALPHA, at(STRICT, 100f, 100f), 1_000);
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, at(STRICT, 160f, 100f), 1_066));
        assertEquals(100f + maxMove(66), unit.x, 1e-3);
        PlayerInput input = PlayerInput.of(player);
        assertEquals(1, input.clamped);
        assertEquals(0, input.corrections, "under 14 tiles off");
        assertEquals(List.of(), w.drain());
    }

    @Test
    void overFourteenTilesOffTheClientIsCorrected(){
        w.admit(ALPHA, at(STRICT, 100f, 100f), 1_000);
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, at(STRICT, 300f, 100f), 1_066));
        float x = 100f + maxMove(66);
        assertEquals(List.of(new IntentCall.To(ALPHA, new Packets.SetPosition(x, 100f))), w.drain());
        assertEquals(1, PlayerInput.of(player).corrections);
    }

    @Test
    void theGapIsCappedAt1500ms(){
        // 1500 ms is 297 units at alpha's speed: from x = 30, inside the 384-unit map
        unit.x = 30f;
        w.admit(ALPHA, at(STRICT, 30f, 100f), 1_000);
        w.admit(ALPHA, at(STRICT, 370f, 100f), 100_000);
        assertEquals(30f + maxMove(1500), unit.x, 1e-3);
    }

    @Test
    void withoutStrictTheClientsPositionIsTaken(){
        w.admit(ALPHA, at(LOOSE, 100f, 100f), 1_000);
        for(int i = 0; i < 3; i++) w.world.tick();
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, at(LOOSE, 200f, 100f), 1_066));
        assertEquals(List.of(), w.drain(), "no cut, no correction");
        RemoteSync sync = unit.attachment(RemoteSync.KEY);
        assertEquals(4, sync.updateSpacing, "the reports were 4 ticks apart");
        assertEquals(200f, sync.targetX);
        // vanilla's interpolate(): from where it was, a quarter of the way per tick, up to twice the step
        assertEquals(100f, unit.x, 1e-3, "the tick that admits it is alpha 0");
        for(int tick = 1; tick <= 10; tick++){
            w.world.tick();
            assertEquals(Mathf.lerp(100f, 200f, Math.min(tick / 4f, 2f)), unit.x, 1e-3, "tick " + tick);
        }
        assertEquals(1, PlayerInput.of(player).clamped, "the report outran the unit, which counts though the client's position is taken");
        assertEquals(0, PlayerInput.of(player).corrections);
    }

    @Test
    void anOutOfDateReportIsIgnored(){
        w.admit(ALPHA, at(STRICT, 102f, 100f), 1_000);
        id = 0;
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, at(STRICT, 102.5f, 100f), 1_033), "the same id is still current");
        id = -1;
        assertEquals(Verdict.rejected("out of date"), w.admit(ALPHA, at(STRICT, 103f, 100f), 1_066));
        assertEquals(102.5f, unit.x, 1e-4);
    }

    @Test
    void aDeadClientOrAnotherUnitMovesNothing(){
        var dead = new Snapshot(STRICT, id++, unit.id(), true, 130f, 100f, 0, 0, 0, 0, 0, 0, -1, false, false, false, true, -1, 0, List.of(), 0, 0, 0, 0);
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, dead, 1_000));
        assertEquals(100f, unit.x, 1e-4);
        var other = new Snapshot(STRICT, id++, unit.id() + 1000, false, 130f, 100f, 0, 0, 0, 0, 0, 0, -1, false, false, false, true, -1, 0, List.of(), 0, 0, 0, 0);
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, other, 1_066));
        assertEquals(100f, unit.x, 1e-4);
    }

    @Test
    void nonFiniteNumbersAreZero(){
        w.admit(ALPHA, at(LOOSE, Float.NaN, Float.POSITIVE_INFINITY), 1_000);
        RemoteSync sync = unit.attachment(RemoteSync.KEY);
        assertEquals(0f, sync.targetX);
        assertEquals(0f, sync.targetY);
    }

    @Test
    void flagsFollowTheClient(){
        var snapshot = new Snapshot(STRICT, id++, unit.id(), false, 100f, 100f, 300f, 90f, 45f, 0, 0, 0, -1, true, true, true, false,
            IntentWorld.VANILLA.block("router").id(), 2, List.of(), 0, 0, 0, 0);
        w.admit(ALPHA, snapshot, 1_000);
        assertTrue(player.typing);
        assertTrue(player.shooting);
        assertFalse(player.boosting, "alpha can't boost");
        assertEquals(IntentWorld.VANILLA.block("router").id(), player.selectedBlock);
        assertEquals(2, player.selectedRotation);
        assertEquals(300f, player.mouseX);
        assertTrue(unit.isShooting);
        assertEquals(300f, unit.aimX, 1e-4);
        assertFalse(unit.updateBuilding);
    }

    @Test
    void aGroundUnitStopsAtWalls(){
        Unit dagger = w.give(player, "dagger", 100f, 100f);
        w.place("copper-wall", 15, 12, player.team, 0);
        unit = dagger;
        w.admit(ALPHA, at(STRICT, 100f, 100f), 1_000);
        w.admit(ALPHA, at(STRICT, 150f, 100f), 2_400);
        assertTrue(dagger.x < 15 * 8f - 4f, "stopped by the wall: " + dagger.x);
        assertEquals(0, PlayerInput.of(player).corrections, "not 14 tiles off");
    }

    @Test
    void theQueueBecomesTheClients(){
        int conveyor = IntentWorld.VANILLA.block("conveyor").id(), router = IntentWorld.VANILLA.block("router").id();
        unit.plans.addLast(BuildPlan.place(30, 30, 0, conveyor, null));
        w.place("router", 20, 21, player.team, 0);
        var plans = List.of(BuildPlan.place(20, 20, 1, conveyor, null), BuildPlan.breaking(25, 25), BuildPlan.place(20, 21, 0, router, null),
            BuildPlan.place(1000, 20, 0, conveyor, null), BuildPlan.place(21, 20, 0, 9999, null), BuildPlan.breaking(20, 21));
        var snapshot = new Snapshot(STRICT, id++, unit.id(), false, 100f, 100f, 0, 0, 0, 0, 0, 0, -1, false, false, false, true, -1, 0, plans,
            0, 0, 0, 0);
        w.admit(ALPHA, snapshot, 1_000);
        // the router stands already, the air tile has nothing to break, one plan is off the map and one is no block
        assertEquals(2, unit.plans.size());
        assertEquals(20, unit.plans.get(0).x);
        assertEquals(20, unit.plans.get(0).y);
        assertTrue(unit.plans.get(1).breaking);
        assertNotSame(snapshot.plans().getFirst(), unit.plans.get(0), "the queue holds copies; the logged intent stays as it came");
    }

    @Test
    void plansWithContentConfigsKeepThem(){
        int sorter = IntentWorld.VANILLA.block("sorter").id(), copper = IntentWorld.item("copper").id();
        var wire = new Packets.ClientSnapshot(0, unit.id(), false, 100f, 100f, 0, 0, 0, 0, 0, 0, -1, false, false, false, true, (short)-1, 0,
            List.of(new Codecs.Plan(false, com.xpdustry.shard.common.geom.Point2.pack(20, 20), sorter, 0, new WireObjects.ContentRef(0, copper))),
            0, 0, 0, 0);
        w.admit(ALPHA, Intents.snapshot(wire, Intents.Context.of(w.world.config(), STRICT)), 1_000);
        assertEquals(1, unit.plans.size());
        assertEquals(ContentRef.item(copper), unit.plans.first().config);
    }
}
