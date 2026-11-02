package com.xpdustry.shard.server.session;

import com.xpdustry.shard.server.Hang;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static com.xpdustry.shard.server.session.SessionTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Per-session mailboxes in front of a world's inbox (ARCHITECTURE.md §4, step 2): only input snapshots coalesce, the
 * bound kicks, and one session's traffic can't hold back another session's join or leave.
 */
class MailboxTest{
    LoopbackConns conns;
    final Sessions sessions = new Sessions();

    @BeforeEach
    void open() throws Exception{
        conns = new LoopbackConns(sessions::disconnected);
    }

    @AfterEach
    void close() throws Exception{
        conns.close();
    }

    Session playing(String name, Place place) throws Exception{
        Session session = sessions.open(conns.open(), identity(name));
        session.join(place);
        confirmed(session, 0);
        tick(place);
        return session;
    }

    /** The {@code Move} payloads admitted from {@code session}, in admission order. */
    static List<Integer> moves(List<CommandLog.Entry> entries, Session session){
        var out = new ArrayList<Integer>();
        for(CommandLog.Entry entry : entries){
            if(entry.input() instanceof IntentEnvelope envelope && envelope.session() == session.id() && envelope.payload() instanceof Move move){
                out.add(move.n());
            }
        }
        return out;
    }

    static long leaves(List<CommandLog.Entry> entries){
        return entries.stream().filter(entry -> entry.input() instanceof Submission submission && submission.command() instanceof Members.Leave).count();
    }

    @Test
    void onlyTheNewestWaitingSnapshotIsKept() throws Exception{
        var place = new Place("a", world(64));
        Session session = playing("alpha", place);
        for(int i = 0; i < 5; i++) assertEquals(Session.Offer.OFFERED, session.offerSnapshot(new Move(i), i));
        assertEquals(4, session.coalesced());
        assertEquals(List.of(4), moves(tick(place), session));
    }

    @Test
    void reliableIntentsAreNeverCoalescedAndKeepTheirPlace() throws Exception{
        var place = new Place("a", world(64));
        Session session = playing("alpha", place);
        session.offerSnapshot(new Move(0), 0);
        session.offer(new Move(100), 0);
        session.offer(new Move(101), 0);
        session.offerSnapshot(new Move(1), 0);
        session.offer(new Move(102), 0);
        session.offerSnapshot(new Move(2), 0);
        // the newest snapshot goes behind every reliable intent that came before it
        assertEquals(List.of(100, 101, 102, 2), moves(tick(place), session));
        assertEquals(2, session.coalesced());
    }

    @Test
    void aSessionOverItsBoundIsRefusedAndKicked() throws Exception{
        var place = new Place("a", world(4096));
        Session session = playing("alpha", place);
        for(int i = 0; i < WorldMailbox.DEFAULT_BOUND; i++) assertEquals(Session.Offer.OFFERED, session.offer(new Move(i), 0));
        assertEquals(Session.Offer.REFUSED, session.offer(new Move(-2), 0));
        // a snapshot that meets the full mailbox is only dropped
        assertEquals(Session.Offer.DROPPED, session.offerSnapshot(new Move(-3), 0));
        // the handler kicks; the disconnect queues the leave behind what was accepted
        session.conn().close("kicked: too many commands");
        var entries = tick(place);
        assertEquals(WorldMailbox.DEFAULT_BOUND, moves(entries, session).size());
        assertEquals(1, leaves(entries));
        assertEquals(0, sessions.size());
    }

    @Test
    void aNoisySessionCantHoldBackAnotherSessionsLeaveOrJoin() throws Exception{
        // 8 inbox slots, 2 of them reserved for control
        World world = world(8);
        var place = new Place("a", world, new WorldMailbox(world.inbox().capacity(), 64, 16, 2, 0));
        Session noisy = playing("noisy", place), quiet = playing("quiet", place);
        for(int i = 0; i < 64; i++) noisy.offer(new Move(i), 0);
        quiet.conn().close("closed by client");
        Session late = sessions.open(conns.open(), identity("late"));
        late.join(place);

        var entries = tick(place);
        assertEquals(6, moves(entries, noisy).size(), "intents stop at the reserve");
        assertEquals(1, leaves(entries), "the quiet session's leave goes in on the first tick");
        assertTrue(world.members().contains(late.id()), "and the new session's join");
        assertFalse(world.members().contains(quiet.id()));
        assertEquals(2, sessions.size(), "the quiet session is forgotten");

        // the noisy session's backlog drains over the next ticks, in order
        var rest = new ArrayList<Integer>(moves(entries, noisy));
        for(int i = 0; i < 20 && rest.size() < 64; i++) rest.addAll(moves(tick(place), noisy));
        var expected = new ArrayList<Integer>();
        for(int i = 0; i < 64; i++) expected.add(i);
        assertEquals(expected, rest);
    }

    /** Who the admitted intents came from, in order, as runs: {@code "first×4 second×4"}. */
    static String turns(List<CommandLog.Entry> entries, List<Session> sessions){
        var out = new StringJoiner(" ");
        String last = null;
        int run = 0;
        for(CommandLog.Entry entry : entries){
            if(!(entry.input() instanceof IntentEnvelope envelope)) continue;
            String name = sessions.stream().filter(session -> session.id() == envelope.session()).findFirst().orElseThrow().identity().name();
            if(name.equals(last)){
                run++;
                continue;
            }
            if(last != null) out.add(last + "×" + run);
            last = name;
            run = 1;
        }
        if(last != null) out.add(last + "×" + run);
        return out.toString();
    }

    @Test
    void thePumpGoesRoundRobin() throws Exception{
        World world = world(64);
        var place = new Place("a", world, new WorldMailbox(world.inbox().capacity(), 64, 4, 54, 0)); // 10 intent slots per tick
        Session first = playing("first", place), second = playing("second", place), third = playing("third", place);
        var all = List.of(first, second, third);
        for(int i = 0; i < 20; i++){
            for(Session session : all) session.offer(new Move(i), 0);
        }
        // turns of 4; the one the budget cut short goes first next tick
        assertEquals("first×4 second×4 third×2", turns(tick(place), all));
        assertEquals("third×4 first×4 second×2", turns(tick(place), all));
        var entries = tick(place);
        assertEquals("second×4 third×4 first×2", turns(entries, all));
        assertEquals(List.of(6, 7, 8, 9), moves(entries, second), "each in its own order");
    }

    @Test
    void theSignalWakesASleepingWorld() throws Exception{
        var place = new Place("a", world(64));
        var signals = new AtomicInteger();
        place.mailbox().signal(signals::incrementAndGet);
        Session session = playing("alpha", place);
        assertEquals(1, signals.get(), "the join made it ready");
        session.offerSnapshot(new Move(0), 0);
        session.offerSnapshot(new Move(1), 0);
        session.offer(new Move(2), 0);
        assertEquals(2, signals.get(), "once until the next pump");
        tick(place);
        session.offer(new Move(3), 0);
        assertEquals(3, signals.get());
    }

    /**
     * The wake runs once the offering thread holds no lock: a receive thread stuck in it (an executor that blocks, a
     * call site linking) holds back neither the tick thread's pump nor the session's other calls (RP2's 85-274 ms ticks).
     */
    @Test
    void aStalledWakeHoldsBackNeitherThePumpNorTheSession() throws Exception{
        var place = new Place("a", world(64));
        Session session = playing("alpha", place);
        var waking = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        place.mailbox().signal(() -> {
            waking.countDown();
            try{
                release.await();
            }catch(InterruptedException e){
                Thread.currentThread().interrupt();
            }
        });
        var receiver = Executors.newSingleThreadExecutor();
        var ticker = Executors.newSingleThreadExecutor();
        try{
            Future<Session.Offer> offered = receiver.submit(() -> session.offer(new Move(1), 0));
            assertTrue(waking.await(Hang.SECONDS, TimeUnit.SECONDS), "the offer made the world ready and woke it");
            assertEquals(List.of(1), ticker.submit(() -> moves(tick(place), session)).get(Hang.SECONDS, TimeUnit.SECONDS),
                "the pump took the intent while the wake was stuck");
            assertEquals(SessionState.IN_WORLD, ticker.submit(session::state).get(Hang.SECONDS, TimeUnit.SECONDS));
            assertFalse(offered.isDone(), "the offer is still in its wake");
            release.countDown();
            assertEquals(Session.Offer.OFFERED, offered.get(Hang.SECONDS, TimeUnit.SECONDS));
        }finally{
            release.countDown();
            receiver.shutdownNow();
            ticker.shutdownNow();
        }
    }

    @RepeatedTest(3)
    void concurrentSessionsAndPumpsLoseNothing() throws Exception{
        World world = world(256);
        var place = new Place("a", world, new WorldMailbox(world.inbox().capacity(), 64, 8, 16, 0));
        int count = 16, each = 2_000;
        var players = new ArrayList<Session>();
        for(int i = 0; i < count; i++) players.add(playing("p" + i, place));
        var done = new CountDownLatch(count);
        var refused = new AtomicInteger();
        try(var pool = Executors.newVirtualThreadPerTaskExecutor()){
            for(Session session : players){
                pool.submit(() -> {
                    for(int i = 0; i < each; i++){
                        // a client that backs off when its mailbox is full, so nothing is kicked
                        while(session.offer(new Move(i), 0) == Session.Offer.REFUSED){
                            refused.incrementAndGet();
                            java.util.concurrent.locks.LockSupport.parkNanos(50_000);
                        }
                        session.offerSnapshot(new Move(-1), 0);
                    }
                    session.conn().close("done");
                    done.countDown();
                });
            }
            var admitted = new HashMap<Long, List<Integer>>();
            long leaves = 0;
            while(done.getCount() > 0 || world.members().size() > 0){
                for(CommandLog.Entry entry : tick(place)){
                    assertTrue(entry.verdict().admitted(), entry.toString());
                    if(entry.input() instanceof IntentEnvelope envelope && envelope.payload() instanceof Move move && move.n() >= 0){
                        admitted.computeIfAbsent(envelope.session(), key -> new ArrayList<>()).add(move.n());
                    }
                    if(entry.input() instanceof Submission submission && submission.command() instanceof Members.Leave) leaves++;
                }
            }
            assertEquals(count, leaves, "every session left exactly once");
            for(Session session : players){
                List<Integer> moves = admitted.get(session.id());
                assertEquals(each, moves.size(), session + " lost reliable intents");
                for(int i = 0; i < each; i++) assertEquals(i, moves.get(i));
            }
        }
        assertEquals(0, sessions.size());
    }
}
