package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.intent.action.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What admission keeps between intents is world state: the players' input, their units' remote sync and the deny
 * rules come through a fork and a checkpoint, hash the same there, and the next intent admits alike on both.
 */
class IntentStateTest{
    static final long ALPHA = 7;

    IntentWorld w;
    Player player;
    Building router;

    @BeforeEach
    void world(){
        w = new IntentWorld();
        player = w.joinWith(ALPHA, "alpha", "alpha", 100f, 100f);
        router = w.place("router", 14, 12, player.team, 0);
        Unit unit = player.unit();
        w.admit(ALPHA, new Snapshot(new Policy(false, true, 6_000, 25, 60), 0, unit.id(), false, 120f, 104f, 0, 0, 30f, 0, 0, 0, -1, false, false,
            false, true, -1, 0, List.of(), 10f, 20f, 400f, 300f), 1_000);
        for(int i = 0; i < 30; i++) w.admit(ALPHA, new BlockIntents.RotateBlock(Policy.VANILLA, router.pos(), true), 1_100 + i);
        w.rule("no-config", ActionType.CONFIGURE);
        w.drain();
    }

    World restored(){
        byte[] bytes = Checkpoint.capture(w.world).encode(Compression.FAST);
        return Checkpoint.decode(bytes).restore(w.config());
    }

    static PlayerInput input(World world){
        return PlayerInput.of(Roster.of(world).player(ALPHA));
    }

    @Test
    void aCheckpointKeepsItAll(){
        World copy = restored();
        assertEquals(w.world.hash(), copy.hash());
        PlayerInput before = PlayerInput.of(player), after = input(copy);
        assertEquals(0, before.lastSnapshot);
        assertEquals(before.lastSnapshot, after.lastSnapshot);
        assertEquals(before.lastClientTime, after.lastClientTime);
        assertEquals(400f, after.viewWidth);
        assertEquals(before.interactions.occurrences, after.interactions.occurrences);
        assertEquals(before.interactions.lastTime, after.interactions.lastTime);
        assertEquals(before.lastMessageTick, after.lastMessageTick);
        assertEquals(before.snapshots, after.snapshots);
        RemoteSync sync = Roster.of(copy).player(ALPHA).unit().attachment(RemoteSync.KEY), original = player.unit().attachment(RemoteSync.KEY);
        assertNotNull(sync);
        assertEquals(120f, sync.targetX);
        assertEquals(original.lastUpdated, sync.lastUpdated);
        assertNotNull(ActionRules.of(copy).denying(ActionType.CONFIGURE, player.team, ALPHA), "the rule came along");
    }

    @Test
    void theNextIntentAdmitsAlikeOnAForkAndARestore(){
        World fork = w.world.fork(), copy = restored();
        var rotate = new BlockIntents.RotateBlock(Policy.VANILLA, router.pos(), true);
        for(World world : List.of(w.world, fork, copy)){
            assertTrue(world.inbox().offer(new IntentEnvelope(ALPHA, 1, 1_000, 1_200L * 1_000_000L, rotate)));
            world.tick();
            assertEquals(Verdict.rejected("denied by InteractionRate: rotate"), world.log().get(world.log().size() - 1).verdict(),
                "the window is still full");
        }
        assertEquals(w.world.hash(), fork.hash());
        assertEquals(w.world.hash(), copy.hash());
    }

    @Test
    void theHashCoversThem(){
        WorldHash before = w.world.hash();
        PlayerInput.of(player).interactions.occurrences++;
        assertNotEquals(before, w.world.hash());
        PlayerInput.of(player).interactions.occurrences--;
        RemoteSync sync = player.unit().attachment(RemoteSync.KEY);
        sync.targetX += 1;
        assertNotEquals(before, w.world.hash());
    }
}
