package com.xpdustry.shard.server.sync;

import com.xpdustry.shard.net.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** The Outbox's seats, audiences and join buffering, recorded by {@link Loopback} instead of sent. */
class OutboxTest{
    static final Packets.UnitDeath DEATH = new Packets.UnitDeath(7);
    static final Packets.EntitySnapshot SNAPSHOT = new Packets.EntitySnapshot((short)0, new byte[0]);
    static final Packets.Kick KICK = new Packets.Kick("bye");

    Loopback loopback;
    final List<String> overflows = new ArrayList<>();
    Outbox outbox;

    @BeforeEach
    void open() throws Exception{
        loopback = new Loopback();
        outbox = new Outbox(SyncSettings.DEFAULT.withJoinBuffer(4, 1 << 20), (session, load) -> overflows.add(session.identity().name() + " " + load),
            loopback);
    }

    @AfterEach
    void close() throws Exception{
        loopback.close();
    }

    Session playing(String name) throws Exception{
        Session session = loopback.session(name);
        outbox.member(session);
        outbox.capture(session, 1);
        outbox.confirmed(session.id(), 1);
        return session;
    }

    @Test
    void aWaitingSeatGetsNothingUntilItsCapture() throws Exception{
        Session alpha = loopback.session("alpha");
        outbox.member(alpha);
        outbox.send(Audience.ALL, DEATH);
        outbox.send(Audience.ALL, KICK);
        assertEquals(List.of(), loopback.ids(alpha), "the capture will hold what happened before it");
        assertEquals(Outbox.Stage.WAITING, outbox.stage(alpha.id()));
    }

    @Test
    void aLoadingSeatHoldsCallsDropsSnapshotsAndFlushesInOrderOnConfirm() throws Exception{
        Session beta = playing("beta");
        Session alpha = loopback.session("alpha");
        outbox.member(alpha);
        outbox.capture(alpha, 1);
        outbox.send(Audience.ALL, DEATH);
        outbox.send(Audience.ALL, SNAPSHOT);
        outbox.send(Audience.ALL, new Packets.BuildDestroyed(3));
        outbox.send(Audience.only(alpha.id()), KICK);
        // high priority goes at once, as a loading client handles it at once; the rest waits
        assertEquals(List.of(Packets.KICK), loopback.ids(alpha));
        assertEquals(List.of(Packets.UNIT_DEATH, Packets.ENTITY_SNAPSHOT, Packets.BUILD_DESTROYED), loopback.ids(beta));

        assertEquals(2, outbox.confirmed(alpha.id(), 1));
        outbox.send(Audience.ALL, new Packets.UnitDestroy(9));
        assertEquals(List.of(Packets.KICK, Packets.UNIT_DEATH, Packets.BUILD_DESTROYED, Packets.UNIT_DESTROY), loopback.ids(alpha));
        assertEquals(Outbox.Stage.PLAYING, outbox.stage(alpha.id()));
        assertEquals(1, outbox.stats().dropped());
        assertEquals(2, outbox.stats().flushed());
    }

    @Test
    void aPacketIsEncodedOnceForEveryone() throws Exception{
        Session alpha = playing("alpha"), beta = playing("beta");
        Session gamma = loopback.session("gamma");
        outbox.member(gamma);
        outbox.capture(gamma, 1);
        outbox.send(Audience.ALL, DEATH);
        outbox.confirmed(gamma.id(), 1);
        Frame frame = loopback.to(alpha).getFirst().frame();
        assertSame(frame, loopback.to(beta).getFirst().frame());
        assertSame(frame, loopback.to(gamma).getFirst().frame(), "the held copy is the same frame");
    }

    @Test
    void audiencesPickTheirSeats() throws Exception{
        Session alpha = playing("alpha"), beta = playing("beta"), gamma = playing("gamma");
        outbox.team(alpha.id(), 1);
        outbox.team(beta.id(), 1);
        outbox.team(gamma.id(), 2);
        outbox.send(Audience.team(1), new Packets.UnitDeath(1));
        outbox.send(new Audience.Team(1, alpha.id()), new Packets.UnitDeath(2));
        outbox.send(Audience.except(beta.id()), new Packets.UnitDeath(3));
        outbox.send(Audience.only(gamma.id()), new Packets.UnitDeath(4));
        outbox.send(new Audience.At(10f, 10f), new Packets.UnitDeath(5));
        assertEquals(List.of(1, 3, 5), deaths(alpha));
        assertEquals(List.of(1, 2, 5), deaths(beta));
        assertEquals(List.of(3, 4, 5), deaths(gamma));
        assertEquals(List.of(1, 2), List.copyOf(outbox.playingTeams()));
    }

    List<Integer> deaths(Session session){
        return loopback.to(session).stream().map(sent -> {
            byte[] tcp = sent.frame().toByteArray();
            byte[] payload = Frame.decodePayload(tcp, 3, tcp.length - 3);
            return java.nio.ByteBuffer.wrap(payload).getInt();
        }).toList();
    }

    @Test
    void aNewerLoadDropsTheOlderOnesFramesAndOnlyItsConfirmCounts() throws Exception{
        Session alpha = loopback.session("alpha");
        outbox.member(alpha);
        outbox.capture(alpha, 1);
        outbox.send(Audience.ALL, DEATH);
        outbox.capture(alpha, 2);
        outbox.send(Audience.ALL, new Packets.UnitDestroy(1));
        assertEquals(-1, outbox.confirmed(alpha.id(), 1), "the older load's confirm is stale");
        assertEquals(Outbox.Stage.LOADING, outbox.stage(alpha.id()));
        assertEquals(1, outbox.confirmed(alpha.id(), 2), "only what came after the newer capture");
        assertEquals(List.of(Packets.UNIT_DESTROY), loopback.ids(alpha));
        assertEquals(0, outbox.confirmed(alpha.id(), 2), "a second confirm changes nothing");
    }

    @Test
    void aFencedStreamIsNotSent() throws Exception{
        Session alpha = loopback.session("alpha");
        outbox.member(alpha);
        loopback.fenced.add(1);
        assertFalse(outbox.stream(alpha, new Session.Load(1, null, Session.Load.Kind.JOIN), new byte[]{1}));
        assertTrue(outbox.stream(alpha, new Session.Load(2, null, Session.Load.Kind.SYNC), new byte[]{2}));
        assertEquals(List.of(-1), loopback.ids(alpha));
        assertEquals(1, outbox.stats().staleStreams());
    }

    @Test
    void anOverflowingBufferIsDroppedAndReportedOnce() throws Exception{
        Session alpha = loopback.session("alpha");
        outbox.member(alpha);
        outbox.capture(alpha, 3);
        for(int i = 0; i < 6; i++) outbox.send(Audience.ALL, new Packets.UnitDeath(i));
        assertEquals(List.of("alpha 3"), overflows);
        assertEquals(0, outbox.confirmed(alpha.id(), 3), "nothing left to flush");
        assertEquals(1, outbox.stats().overflows());
    }

    @Test
    void theBufferIsBoundedInBytesToo() throws Exception{
        outbox = new Outbox(SyncSettings.DEFAULT.withJoinBuffer(100, 64), (session, load) -> overflows.add(session.identity().name()), loopback);
        Session alpha = loopback.session("alpha");
        outbox.member(alpha);
        outbox.capture(alpha, 1);
        // random text, so LZ4 can't shrink it under the bound
        var random = new Random(1);
        var text = new StringBuilder();
        for(int i = 0; i < 200; i++) text.append((char)('!' + random.nextInt(90)));
        outbox.send(Audience.ALL, new Packets.SendMessage(text.toString()));
        assertEquals(List.of("alpha"), overflows);
    }

    @Test
    void aSyncReloadWaitsForItsCaptureThenHolds() throws Exception{
        Session alpha = playing("alpha");
        outbox.reloading(alpha);
        outbox.send(Audience.ALL, DEATH);
        outbox.capture(alpha, 2);
        outbox.send(Audience.ALL, new Packets.UnitDestroy(1));
        outbox.send(Audience.ALL, SNAPSHOT);
        assertEquals(List.of(), loopback.ids(alpha), "nothing before the capture, held after it");
        assertEquals(1, outbox.confirmed(alpha.id(), 2));
        assertEquals(List.of(Packets.UNIT_DESTROY), loopback.ids(alpha));
    }

    @Test
    void framesFromOtherThreadsGoOutWhenTheTickDrains() throws Exception{
        Session alpha = playing("alpha");
        Thread.ofVirtual().start(() -> outbox.offer(Audience.ALL, Calls.message("from afar"))).join();
        assertEquals(List.of(), loopback.ids(alpha));
        outbox.drainOffered();
        assertEquals(List.of(Packets.SEND_MESSAGE), loopback.ids(alpha));
    }

    @Test
    void aLeftSeatGetsNothing() throws Exception{
        Session alpha = playing("alpha");
        outbox.left(alpha.id());
        outbox.send(Audience.ALL, DEATH);
        assertEquals(List.of(), loopback.ids(alpha));
        assertNull(outbox.stage(alpha.id()));
    }

    @Test
    void snapshotsTravelOverUdpAndCallsAsVanillaSendsThem() throws Exception{
        Session alpha = playing("alpha");
        outbox.send(Audience.ALL, SNAPSHOT);
        outbox.send(Audience.ALL, DEATH);
        outbox.send(Audience.ALL, new Packets.SpawnEffect(0f, 0f, 0f, (short)1));
        assertEquals(List.of(false, true, false), loopback.to(alpha).stream().map(Loopback.Sent::reliable).toList());
    }

    @Test
    void offersAreSafeFromManyThreads() throws Exception{
        Session alpha = playing("alpha");
        try(var pool = Executors.newVirtualThreadPerTaskExecutor()){
            for(int i = 0; i < 100; i++) pool.submit(() -> outbox.offer(Audience.ALL, Calls.message("x")));
        }
        outbox.drainOffered();
        assertEquals(100, loopback.ids(alpha).size());
    }

    @Test
    void offersPastTheFrameBoundAreRefusedAndTheRestKeepTheirOrder() throws Exception{
        outbox = new Outbox(SyncSettings.DEFAULT, (session, load) -> {
        }, loopback, 3, 1 << 20);
        Session alpha = playing("alpha");
        var lines = new ArrayList<Frame>();
        for(int i = 0; i < 5; i++) lines.add(Calls.message("line " + i));
        for(int i = 0; i < 5; i++) assertEquals(i < 3, outbox.offer(Audience.ALL, lines.get(i)), "offer " + i);
        assertEquals(2, outbox.stats().refused());
        outbox.drainOffered();
        assertEquals(lines.subList(0, 3), loopback.to(alpha).stream().map(Loopback.Sent::frame).toList());
        // a drain makes room again
        assertTrue(outbox.offer(Audience.ALL, Calls.message("line 5")));
        outbox.drainOffered();
        assertEquals(4, loopback.ids(alpha).size());
    }

    @Test
    void offersPastTheByteBoundAreRefused() throws Exception{
        Frame line = Calls.message("x".repeat(100));
        outbox = new Outbox(SyncSettings.DEFAULT, (session, load) -> {
        }, loopback, 1000, 3L * line.size());
        Session alpha = playing("alpha");
        for(int i = 0; i < 3; i++) assertTrue(outbox.offer(Audience.ALL, line));
        assertFalse(outbox.offer(Audience.ALL, line));
        assertEquals(1, outbox.stats().refused());
        outbox.drainOffered();
        assertTrue(outbox.offer(Audience.ALL, line), "drained bytes are released");
    }

    @Test
    void offersFromManyThreadsStayWithinTheBound() throws Exception{
        outbox = new Outbox(SyncSettings.DEFAULT, (session, load) -> {
        }, loopback, 50, 1 << 20);
        Session alpha = playing("alpha");
        var accepted = new java.util.concurrent.atomic.AtomicInteger();
        try(var pool = Executors.newVirtualThreadPerTaskExecutor()){
            for(int i = 0; i < 200; i++) pool.submit(() -> {
                if(outbox.offer(Audience.ALL, Calls.message("x"))) accepted.incrementAndGet();
            });
        }
        assertEquals(50, accepted.get());
        assertEquals(150, outbox.stats().refused());
        outbox.drainOffered();
        assertEquals(50, loopback.ids(alpha).size());
    }

    @Test
    void theTickFlushesEachSeatThatGotFramesOnceInTheOrderItFirstGotOne() throws Exception{
        Session alpha = playing("alpha"), beta = playing("beta"), gamma = playing("gamma");
        loopback.flushes.clear();
        outbox.send(Audience.only(beta.id()), DEATH);
        outbox.send(Audience.ALL, SNAPSHOT);
        outbox.send(Audience.ALL, DEATH);
        assertEquals(List.of(), loopback.flushes, "sends only queue");
        outbox.left(gamma.id());
        outbox.flush();
        assertEquals(List.of(beta.id(), alpha.id(), gamma.id()), loopback.flushes, "a seat that left still gets what it was sent");
        loopback.flushes.clear();
        outbox.flush();
        assertEquals(List.of(), loopback.flushes, "nothing new, no wakes");
    }

    @Test
    void aDirectFrameIsFlushedAtOnce() throws Exception{
        Session alpha = loopback.session("alpha");
        outbox.direct(alpha, Calls.kick("bye"));
        assertEquals(List.of(alpha.id()), loopback.flushes);
    }

    /**
     * {@link Outbox#teams} sets what {@link Outbox#team} for every roster entry sets: a team switch, a joining player,
     * a replaced player and a departed one, each seen at the call and not before.
     */
    @Test
    void teamsFollowTheRosterAtEachCall() throws Exception{
        var epoch = com.xpdustry.shard.content.epoch.Epoch.vanilla();
        var world = new com.xpdustry.shard.sim.world.World(com.xpdustry.shard.sim.world.WorldConfig.of(epoch, 8, 8, 1)
            .withKinds(com.xpdustry.shard.sim.building.KindRegistry.builtIn().withPassthrough()));
        Session alpha = playing("alpha"), beta = playing("beta"), gamma = playing("gamma");
        var roster = new TreeMap<Long, com.xpdustry.shard.sim.unit.Player>();
        com.xpdustry.shard.sim.unit.Player a = player(world, 1, true), b = player(world, 1, false), c = player(world, 2, true);
        roster.put(alpha.id(), a);
        roster.put(beta.id(), b);
        roster.put(gamma.id(), c);
        outbox.teams(roster);
        outbox.send(Audience.team(1), new Packets.UnitDeath(1));
        a.team(2);
        b.team(2);
        outbox.send(Audience.team(2), new Packets.UnitDeath(2));
        outbox.teams(roster);
        outbox.send(Audience.team(2), new Packets.UnitDeath(3));
        // gamma's player replaced by a joining one, beta's gone; alpha's switches back
        c.remove();
        roster.put(gamma.id(), player(world, 1, false));
        roster.remove(beta.id());
        b.remove();
        a.team(1);
        outbox.teams(roster);
        outbox.send(Audience.team(1), new Packets.UnitDeath(4));
        outbox.send(Audience.team(2), new Packets.UnitDeath(5));
        assertEquals(List.of(1, 3, 4), deaths(alpha));
        assertEquals(List.of(1, 3, 5), deaths(beta), "a seat with no player keeps its team");
        assertEquals(List.of(2, 3, 4), deaths(gamma));
    }

    static com.xpdustry.shard.sim.unit.Player player(com.xpdustry.shard.sim.world.World world, int team, boolean added){
        var player = com.xpdustry.shard.sim.unit.Player.create(world);
        player.team(team);
        if(added) player.add();
        return player;
    }
}
