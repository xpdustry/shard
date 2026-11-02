package com.xpdustry.shard.server.session;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.net.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.Bootstrap;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** The route state machine, envelopes and stale-route dropping, against real worlds' mailboxes and admission. */
class SessionTest{
    record Air(int id, String name, KindKey kind, int size, float health, boolean update, boolean noUpdateDisabled,
               boolean hasItems, boolean hasLiquids, boolean hasPower) implements BlockDef{
    }

    /** Content with only air, which a tile grid needs as block 0: these worlds only admit inputs. */
    static final ContentEpoch EMPTY = new ContentEpoch(){
        final BlockDef air = new Air(0, "air", KindKey.of("air-block"), 1, 0f, false, false, false, false, false);

        @Override
        public String hash(){
            return "empty";
        }

        @Override
        public int blocks(){
            return 1;
        }

        @Override
        public BlockDef block(int id){
            Objects.checkIndex(id, 1);
            return air;
        }

        @Override
        public int items(){
            return 0;
        }

        @Override
        public int liquids(){
            return 0;
        }

        @Override
        public int units(){
            return 0;
        }

        @Override
        public UnitDef unit(int id){
            throw new IndexOutOfBoundsException(id);
        }
    };

    /** An intent that always admits; the payload tells envelopes apart. */
    record Move(int n) implements Intent{
        @Override
        public Verdict apply(World world, IntentEnvelope envelope){
            return Verdict.ADMITTED;
        }
    }

    /** Another input source (an admin, a plugin) that fills the inbox. */
    record Noop() implements Command.Internal{
        @Override
        public Verdict apply(World world, Cause cause){
            return Verdict.ADMITTED;
        }
    }

    /** A load's confirm, which the world admits. */
    record Loaded(int load) implements Intent{
        @Override
        public Verdict apply(World world, IntentEnvelope envelope){
            return Verdict.ADMITTED;
        }
    }

    /** A destination whose host pumps its mailbox before each {@link #tick}, as a world host does. */
    record Place(String name, World world, WorldMailbox mailbox) implements Destination{
        Place(String name, World world){
            this(name, world, new WorldMailbox(world.inbox()));
        }

        @Override
        public Intent confirm(Session session, Session.Load load){
            return new Loaded(load.generation());
        }
    }

    /** Stands for {@code worldDataBegin}. */
    static final Frame BEGIN = Frame.packet(3, new byte[0]);

    /** Sends the current load's stream, then confirms it as the client does once it has the stream whole. */
    static Session.Offer confirmed(Session session, long arrivalNanos){
        Session.Load load = session.load();
        assertNotNull(load, "a load to confirm");
        assertTrue(session.stream(load, 2, new byte[]{1}), "streamed");
        return session.confirm(arrivalNanos);
    }

    static World world(int inbox){
        return new World(WorldConfig.of(EMPTY, 4, 4, 1).withSystems(Systems.of(List.of(), List.of())).withInboxCapacity(inbox));
    }

    static Identity identity(String name){
        return new Identity(160, "official", name, "en", "usid", "AAAAAAAAAAAAAAAAAAAAAA==", false, -1, List.of());
    }

    LoopbackConns conns;
    Sessions sessions = new Sessions();
    Place a = new Place("a", world(64)), b = new Place("b", world(64));

    @BeforeEach
    void open() throws Exception{
        conns = new LoopbackConns();
    }

    @AfterEach
    void close() throws Exception{
        conns.close();
    }

    Session session(String name) throws Exception{
        return sessions.open(conns.open(), identity(name));
    }

    /** Pumps the mailbox, ticks the world and returns the verdicts of what it admitted in that tick. */
    static List<CommandLog.Entry> tick(Place place){
        int before = place.world().log().size();
        place.mailbox().pump(place.world().inbox());
        place.world().tick();
        var log = place.world().log().entries();
        return log.subList(before, log.size());
    }

    static String verdicts(List<CommandLog.Entry> entries){
        var out = new StringJoiner(" ");
        for(CommandLog.Entry entry : entries){
            String what = switch(entry.input()){
                case IntentEnvelope envelope -> "g" + envelope.routeGeneration() + "s" + envelope.sequence();
                case Submission submission -> submission.command().getClass().getSimpleName();
            };
            out.add(what + (entry.verdict().admitted() ? "" : "!" + ((Verdict.Rejected)entry.verdict()).reason()));
        }
        return out.toString();
    }

    @Test
    void joinLoadConfirmPlay() throws Exception{
        Session session = session("alpha");
        assertEquals(SessionState.CONNECTED, session.state());
        assertSame(session, sessions.of(session.conn()));
        assertEquals(Session.Offer.STALE, session.offerSnapshot(new Move(0), 1), "no world yet");

        assertTrue(session.join(a));
        assertEquals(SessionState.LOADING, session.state());
        assertEquals(1, session.routeGeneration());
        assertSame(a, session.world());
        assertEquals(Session.Offer.STALE, session.offerSnapshot(new Move(1), 2), "loading drops gameplay input");

        assertEquals(Session.Offer.OFFERED, confirmed(session, 3));
        assertEquals(SessionState.IN_WORLD, session.state());
        assertEquals(Session.Offer.OFFERED, session.offerSnapshot(new Move(3), 4));
        assertEquals(Session.Offer.OFFERED, session.offer(new Move(4), 5));
        assertEquals(Session.Offer.STALE, session.confirm(6), "a second confirm is stale");
        assertEquals(3, session.staleDropped());

        assertEquals("Join g1s0 g1s1 g1s2", verdicts(tick(a)));
        var envelope = (IntentEnvelope)a.world().log().get(1).input();
        assertEquals(session.id(), envelope.session());
        assertEquals(3, envelope.arrivalNanos(), "the recorded arrival time");
        assertEquals(new Loaded(1), envelope.payload());
    }

    @Test
    void aTransferFencesTheOldRoute() throws Exception{
        Session session = session("alpha");
        session.join(a);
        confirmed(session, 0);
        session.offerSnapshot(new Move(1), 0);

        Session.Transfer transfer = session.beginTransfer(b);
        assertNotNull(transfer);
        assertEquals(new Session.Transfer(session, a, b, 2), transfer);
        assertEquals(SessionState.TRANSFERRING, session.state());
        assertNull(session.world());
        assertEquals(Session.Offer.STALE, session.offerSnapshot(new Move(2), 0), "the client still plays the old world");
        assertEquals(Session.Offer.STALE, session.confirm(0));

        // the source admits what came before the fence, then removes the member
        assertEquals("Join g1s0 g1s1 Leave", verdicts(tick(a)));
        assertFalse(a.world().members().contains(session.id()));

        assertTrue(session.arrive(transfer, BEGIN));
        assertFalse(session.arrive(transfer, BEGIN), "arrives once");
        assertEquals(SessionState.LOADING, session.state());
        assertSame(b, session.world());
        assertEquals(Session.Offer.OFFERED, confirmed(session, 0));
        session.offerSnapshot(new Move(5), 0);
        assertEquals("Join g2s0 g2s1", verdicts(tick(b)), "sequences restart on the new route");
        assertEquals("", verdicts(tick(a)), "nothing reaches the old world");

        // back to the first world: a newer generation again
        Session.Transfer back = session.beginTransfer(a);
        assertEquals(3, back.generation());
        assertTrue(session.arrive(back, BEGIN));
        confirmed(session, 0);
        assertEquals("Leave", verdicts(tick(b)));
        assertEquals("Join g3s0", verdicts(tick(a)));
    }

    @Test
    void worldsRejectEnvelopesFromAnOldRoute() throws Exception{
        Session session = session("alpha");
        session.join(a);
        confirmed(session, 0);
        Session.Transfer away = session.beginTransfer(b);
        session.arrive(away, BEGIN);
        confirmed(session, 0);
        Session.Transfer back = session.beginTransfer(a);
        session.arrive(back, BEGIN);
        confirmed(session, 0);
        tick(a);
        tick(b);
        // a late envelope of generation 1 (an old packet the fences missed) reaches each world
        a.world().inbox().offer(new IntentEnvelope(session.id(), 1, 99, 0, new Move(9)));
        b.world().inbox().offer(new IntentEnvelope(session.id(), 1, 99, 0, new Move(9)));
        assertEquals("g1s99!stale route", verdicts(tick(a)));
        assertEquals("g1s99!not a member", verdicts(tick(b)));
    }

    @Test
    void aFullMailboxRefusesReliableIntentsAndDropsSnapshots() throws Exception{
        World world = world(64);
        var small = new Place("small", world, new WorldMailbox(world.inbox().capacity(), 2, 16, 8, 0));
        Session session = session("alpha");
        session.join(small);
        confirmed(session, 0);
        assertEquals(Session.Offer.OFFERED, session.offer(new Move(1), 0));
        assertEquals(Session.Offer.REFUSED, session.offer(new Move(2), 0), "a reliable intent is refused, never dropped");
        assertEquals(Session.Offer.DROPPED, session.offerSnapshot(new Move(3), 0), "an input snapshot is dropped");
        assertEquals(1, session.fullDropped());
        assertEquals("Join g1s0 g1s1", verdicts(tick(small)));
        assertEquals(Session.Offer.OFFERED, session.offerSnapshot(new Move(4), 0), "the route still works");
        assertEquals("g1s2", verdicts(tick(small)), "a refused offer used no sequence");

        // the leave is no intent: it fits however full the mailbox is
        session.offer(new Move(5), 0);
        session.offer(new Move(6), 0);
        Session.Transfer transfer = session.beginTransfer(b);
        assertNotNull(transfer);
        assertEquals("g1s3 g1s4 Leave", verdicts(tick(small)));
    }

    @Test
    void closingLeavesTheWorldOnce() throws Exception{
        Session loading = session("loading"), transferring = session("transferring");
        loading.join(a);
        transferring.join(a);
        confirmed(transferring, 0);
        Session.Transfer transfer = transferring.beginTransfer(b);
        tick(a);

        loading.close();
        assertEquals(SessionState.CLOSED, loading.state());
        assertTrue(loading.leaving(), "until the world takes its leave");
        transferring.close();
        assertFalse(transferring.leaving(), "its source took its leave already");
        assertEquals(1, sessions.size(), "only the session whose leave is pending stays");
        assertFalse(transferring.arrive(transfer, BEGIN), "a closed session doesn't arrive");
        assertEquals(Session.Offer.CLOSED, loading.offer(new Move(1), 0));
        loading.close(); // closing again is a no-op
        assertEquals("Leave", verdicts(tick(a)), "only the member leaves; the transferring one already left");
        assertFalse(loading.leaving());
        assertEquals("", verdicts(tick(b)));
        assertEquals(0, sessions.size());
        assertNull(sessions.open(loading.conn(), identity("again")), "one session per connection");
    }

    @Test
    void aLeaveThatMeetsAFullInboxWaitsUntilTheWorldTakesIt() throws Exception{
        var small = new Place("small", world(4));
        Session session = session("alpha");
        session.join(small);
        confirmed(session, 0);
        tick(small);
        Inbox inbox = small.world().inbox();
        while(inbox.offer(new Submission(new Noop(), Cause.SERVER))){
            // saturated by other inputs
        }
        session.close();
        assertEquals(SessionState.CLOSED, session.state());
        int leaves = 0;
        for(int i = 0; i < 6; i++){
            for(CommandLog.Entry entry : tick(small)){
                if(entry.input() instanceof Submission submission && !(submission.command() instanceof Noop)){
                    assertTrue(entry.verdict().admitted(), entry.toString());
                    leaves++;
                }
            }
            if(i < 2){
                assertTrue(small.world().members().contains(session.id()), "still a member while the inbox stays full");
                assertTrue(session.leaving());
                assertEquals(1, sessions.size(), "remembered until its leave is in");
                while(inbox.offer(new Submission(new Noop(), Cause.SERVER))){
                    // and again
                }
            }
        }
        assertEquals(1, leaves, "the leave goes in exactly once");
        assertFalse(small.world().members().contains(session.id()));
        assertEquals(0, sessions.size());
    }

    @Test
    void concurrentOffersKeepSequenceOrder() throws Exception{
        World world = world(100_000);
        var big = new Place("big", world, new WorldMailbox(world.inbox().capacity(), 100_000, 16, 1, 0));
        Session session = session("alpha");
        session.join(big);
        confirmed(session, 0);
        int threads = 4, each = 5_000;
        var start = new CountDownLatch(1);
        try(var pool = Executors.newVirtualThreadPerTaskExecutor()){
            for(int t = 0; t < threads; t++){
                pool.submit(() -> {
                    start.await();
                    for(int i = 0; i < each; i++){
                        assertEquals(Session.Offer.OFFERED, session.offer(new Move(i), System.nanoTime()));
                    }
                    return null;
                });
            }
            start.countDown();
        }
        var entries = tick(big);
        assertEquals(1 + 1 + threads * each, entries.size());
        for(CommandLog.Entry entry : entries) assertTrue(entry.verdict().admitted(), entry.toString());
    }

    @Test
    void identityReadsTheBootstrapConnectPacket(){
        byte[] id = {1, 2, 3, 4, 5, 6, 7, 8};
        var connect = Bootstrap.Connect.of(160, "official", "  [red]alpha\t", null, "usid", id, true, 0xffd37fff, List.of("mod:1.0"));
        var out = new Writes();
        Bootstrap.write(out, connect);
        byte[] payload = out.toByteArray();

        Identity identity = Identity.of(Identity.read(payload));
        assertEquals(new Identity(160, "official", "[red]alpha", "en", "usid", connect.uuidString(), true, 0xffd37fff, List.of("mod:1.0")),
            identity);
        assertEquals(16, Base64.getDecoder().decode(identity.uuid()).length, "vanilla keys by all 16 bytes");
        assertEquals("alpha", identity.plainName());
        assertThrows(IllegalArgumentException.class, () -> Identity.read(Arrays.copyOf(payload, payload.length - 1)));
    }

    @Test
    void viewersComeFromTheWorldsPlayer(){
        var player = new Player();
        player.team = 2;
        player.admin = true;
        Viewer viewer = new PlayerEntity(7, player).viewer(100, 50, 40, 20);
        assertEquals(new Viewer(7, 2, 100, 50, 40, 20, true), viewer);
        assertTrue(viewer.sees(119, 59, 0));
        assertFalse(viewer.sees(121, 50, 0));
        assertTrue(viewer.sees(121, 50, 2));
    }

    @Test
    void aFullWorldTurnsJoinsAndTransfersAway() throws Exception{
        World world = world(64);
        var one = new Place("one", world, new WorldMailbox(world.inbox().capacity(), 64, 16, 8, 1));
        Session first = session("first"), second = session("second");
        assertTrue(first.join(one));
        assertFalse(second.join(one), "one seat");
        assertEquals(SessionState.CONNECTED, second.state());
        assertTrue(second.join(a));
        tick(a);
        confirmed(second, 0);
        assertNull(second.beginTransfer(one), "still full");
        assertEquals(SessionState.IN_WORLD, second.state(), "nothing changed");

        first.close();
        assertEquals(1, one.mailbox().seats(), "until the world takes the leave");
        tick(one);
        assertEquals(0, one.mailbox().seats());
        Session.Transfer transfer = second.beginTransfer(one);
        assertNotNull(transfer);
        assertEquals(1, one.mailbox().seats(), "held while the source removes the player");
        second.close();
        assertEquals(0, one.mailbox().seats(), "an abandoned transfer gives its seat back");
    }

    @Test
    void aReleasedWorldDeliversEveryLeave() throws Exception{
        Session waiting = session("waiting"), idle = session("idle");
        assertTrue(waiting.join(a));
        assertTrue(idle.join(a));
        tick(a);
        confirmed(idle, 0);
        tick(a);
        confirmed(waiting, 0);
        waiting.close();
        assertTrue(waiting.leaving(), "queued, not yet taken");

        a.mailbox().release();
        assertFalse(waiting.leaving(), "a failed world never takes it: it counts as delivered");
        assertNull(sessions.get(waiting.id()), "so the closed session is forgotten");
        assertEquals(Session.Offer.STALE, idle.offer(new Move(1), 0), "intents for a failed world are dropped");
        idle.close();
        assertFalse(idle.leaving());
        assertEquals(0, sessions.size());
        assertEquals(0, a.mailbox().seats());

        Session late = session("late");
        assertFalse(late.join(a), "no seat in a released world");
        a.mailbox().reopen();
        assertTrue(late.join(a));
    }
}
