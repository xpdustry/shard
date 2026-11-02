package com.xpdustry.shard.server.host;

import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.server.host.HostInputs.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.sim.world.Observer;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** A session's player in a hosted world: created by its join, added by its confirm, removed by its leave, and checkpointed. */
class HostInputsTest{
    World world;
    Observer events;
    long sequence;

    @BeforeEach
    void world(){
        WorldConfig template = HostedSystems.template(Epoch.vanilla(), 3, 256);
        world = new World(config(template));
        events = world.observe(64, List.of(Joined.class, Left.class, Confirmed.class), null);
    }

    static WorldConfig config(WorldConfig template){
        return new WorldConfig(template.content(), 16, 16, 3, template.systems(), template.kinds(), template.inboxCapacity(), template.journal());
    }

    void submit(Command command){
        assertTrue(world.inbox().offer(new Submission(command, Cause.SERVER)));
    }

    void intent(long session, int generation, Intent intent){
        assertTrue(world.inbox().offer(new IntentEnvelope(session, generation, sequence++, 0, intent)));
    }

    List<WorldEvent> tick(){
        world.tick();
        return events.drain();
    }

    @Test
    void aJoinMakesAPlayerThatTheConfirmAdds(){
        submit(new Join(7, 1, "[accent]alpha", 0xd37fff00, true));
        List<WorldEvent> joined = tick();
        Player player = Roster.of(world).player(7);
        assertEquals(List.of(new Joined(7, 1, player.id())), joined);
        assertEquals("[accent]alpha", player.name);
        assertEquals(0xd37fffff, player.color, "made opaque");
        assertTrue(player.admin);
        assertFalse(player.isAdded(), "not until the confirm");
        assertNull(player.unit(), "no unit until WP23 spawns one");

        intent(7, 1, new Confirm(1));
        assertEquals(List.of(new Confirmed(7, player.id(), 1)), tick());
        assertTrue(player.isAdded());
        intent(7, 1, new Confirm(2));
        assertEquals(List.of(new Confirmed(7, player.id(), 2)), tick(), "a /sync's reload");
        assertTrue(player.isAdded());

        submit(new Leave(7));
        assertEquals(List.of(new Left(7, player.id(), true)), tick());
        assertFalse(player.isAdded());
        assertNull(Roster.of(world).player(7));
        assertEquals(0, world.members().size());
    }

    @Test
    void aLeaveBeforeTheConfirmWasNeverInTheWorld(){
        submit(new Join(7, 1, "alpha", -1, false));
        int player = ((Joined)tick().getFirst()).player();
        submit(new Leave(7));
        assertEquals(List.of(new Left(7, player, false)), tick());
    }

    @Test
    void aStaleRouteCantConfirm(){
        submit(new Join(7, 2, "alpha", -1, false));
        tick();
        intent(7, 1, new Confirm(1));
        assertEquals(List.of(), tick());
        assertFalse(Roster.of(world).player(7).isAdded());
    }

    @Test
    void thePlayersViewFollowsTheRosterInAForkAndARestore(){
        submit(new Join(1, 1, "alpha", -1, false));
        submit(new Join(2, 1, "beta", -1, false));
        tick();
        Roster roster = Roster.of(world);
        SortedMap<Long, Player> players = roster.players();
        assertSame(players, roster.players(), "one view, not a wrapper per call");
        assertEquals(List.of(1L, 2L), List.copyOf(players.keySet()));
        assertThrows(UnsupportedOperationException.class, () -> players.remove(1L));

        World fork = world.fork();
        World restored = Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
        submit(new Leave(1));
        tick();
        assertEquals(List.of(2L), List.copyOf(players.keySet()), "the view follows the leave");
        for(World other : List.of(fork, restored)){
            SortedMap<Long, Player> theirs = Roster.of(other).players();
            assertNotSame(players, theirs);
            assertEquals(List.of(1L, 2L), List.copyOf(theirs.keySet()));
            assertSame(Roster.of(other).player(1), theirs.get(1L));
        }
    }

    @Test
    void playersSurviveACheckpoint(){
        submit(new Join(1, 1, "alpha", 0x11223300, false));
        submit(new Join(2, 1, "beta", 0x44556600, true));
        tick();
        intent(1, 1, new Confirm(1));
        tick();
        WorldHash hash = world.hash();

        World restored = Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
        assertEquals(hash, restored.hash());
        Roster roster = Roster.of(restored);
        assertEquals(2, roster.size());
        assertEquals("alpha", roster.player(1).name);
        assertTrue(roster.player(1).isAdded());
        assertFalse(roster.player(2).isAdded());
        assertTrue(roster.player(2).admin);
        assertEquals(Roster.of(world).player(2).id(), roster.player(2).id());

        // the restored world goes on as the original does
        for(World w : List.of(world, restored)){
            w.inbox().offer(new Submission(new Leave(1), Cause.SERVER));
            w.tick();
        }
        assertEquals(world.hash(), restored.hash());
        assertEquals(1, Roster.of(restored).size());
    }
}
