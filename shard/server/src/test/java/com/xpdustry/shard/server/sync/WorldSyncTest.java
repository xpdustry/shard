package com.xpdustry.shard.server.sync;

import com.xpdustry.shard.compat.v160.sync.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.net.*;
import com.xpdustry.shard.server.sched.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.WireObjects;
import com.xpdustry.shard.wire.v160_7.Packets;
import org.junit.jupiter.api.*;

import java.nio.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A world's sync on a real Shard world, ticked as a hosted world ticks it: the tick's events through the Call
 * mappings, then the snapshots and batches that are due, in vanilla's order and cadence.
 */
class WorldSyncTest{
    static final Epoch EPOCH = Epoch.vanilla();
    static final long ID = 0x5eed;
    static final int SIZE = 32;

    Loopback loopback;
    World world;
    Outbox outbox;
    WorldSync sync;
    final TreeMap<Long, Player> roster = new TreeMap<>();
    final MapState state = new MapState();
    /** The ticks each sent frame went out after, by the frame's position in {@link Loopback#sent}. */
    final List<Long> ticks = new ArrayList<>();

    @BeforeEach
    void open() throws Exception{
        loopback = new Loopback();
        world = new World(WorldConfig.of(EPOCH, SIZE, SIZE, 1).withKinds(KindRegistry.builtIn().withPassthrough()));
        world.buildings().beginLoad();
        for(int pos = 0; pos < world.tiles().size(); pos++) world.tiles().floor(pos, EPOCH.block("stone").id());
        world.buildings().endLoad(false);
        // no cores: the unit cap would be 0 and kill every unit at spawn
        world.part(WorldRules.class).rules.disableUnitCap = true;
        outbox = new Outbox(SyncSettings.DEFAULT, (session, load) -> fail("overflow"), loopback);
        sync = new WorldSync(ID, SyncSettings.DEFAULT, outbox, EPOCH, BuildingCodecs.builtIn());
        sync.attached(world);
    }

    @AfterEach
    void close() throws Exception{
        loopback.close();
    }

    Session play(String name, int team) throws Exception{
        Session session = loopback.session(name);
        Player player = Player.create(world);
        player.name = name;
        player.team(team);
        player.add();
        roster.put(session.id(), player);
        outbox.member(session);
        outbox.capture(session, 1);
        outbox.confirmed(session.id(), 1);
        return session;
    }

    /** One tick as {@code HostedWorld.afterTick} runs it. */
    void tick(){
        world.tick();
        sync.beginTick(world, roster);
        assertFalse(sync.drain(), "nothing dropped");
        sync.endTick(world, state);
        while(ticks.size() < loopback.sent.size()) ticks.add(world.ticks());
    }

    void ticks(int n){
        for(int i = 0; i < n; i++) tick();
    }

    /** The ticks after which {@code session} got packet {@code id}. */
    List<Long> ticksOf(Session session, int id){
        var out = new ArrayList<Long>();
        for(int i = 0; i < loopback.sent.size(); i++){
            Loopback.Sent sent = loopback.sent.get(i);
            if(sent.session() == session.id() && sent.id() == id) out.add(ticks.get(i));
        }
        return out;
    }

    /** What {@code session} got after {@code tick}, by packet id. */
    List<Integer> idsAt(Session session, long tick){
        var out = new ArrayList<Integer>();
        for(int i = 0; i < loopback.sent.size(); i++){
            Loopback.Sent sent = loopback.sent.get(i);
            if(sent.session() == session.id() && ticks.get(i) == tick) out.add(sent.id());
        }
        return out;
    }

    static byte[] payload(Frame frame){
        byte[] tcp = frame.toByteArray();
        return Frame.decodePayload(tcp, 3, tcp.length - 3);
    }

    Unit spawn(String type, float x, float y){
        Unit unit = Unit.create(world, EPOCH.unit(type), 1);
        unit.set(x, y);
        unit.controller(new NoAi());
        unit.add();
        return unit;
    }

    /** Review 39: the state snapshot carries the universe time the server loaded and the logic random as it is now. */
    @Test
    void theStateSnapshotCarriesTheUniverseTimeAndTheLiveRandom() throws Exception{
        var settings = SyncSettings.DEFAULT.withUniverseSeconds(4_321);
        outbox = new Outbox(settings, (session, load) -> fail("overflow"), loopback);
        sync = new WorldSync(ID, settings, outbox, EPOCH, BuildingCodecs.builtIn());
        sync.attached(world);
        Session alpha = play("alpha", 1);
        com.xpdustry.shard.sim.logic.LogicPart.require(world).logic().rand(new com.xpdustry.shard.common.math.Rand(5, 6));
        ticks(12);
        var snapshot = ByteBuffer.wrap(payload(loopback.to(alpha).stream().filter(s -> s.id() == Packets.STATE_SNAPSHOT).findFirst().orElseThrow().frame()));
        snapshot.getFloat();
        snapshot.getInt();
        snapshot.getInt();
        snapshot.get();
        snapshot.get();
        assertEquals(4_321, snapshot.getInt(), "universe seconds");
        snapshot.get();
        assertEquals(5, snapshot.getLong(), "GlobalVars.rand.seed0");
        assertEquals(6, snapshot.getLong(), "GlobalVars.rand.seed1");
    }

    /**
     * WP83: a paused world ({@code state.isPaused()}) still syncs at vanilla's cadence, as {@code NetServer.update}
     * runs {@code sync()} whatever the state, and its state snapshot says so; nothing in it moves meanwhile.
     */
    @Test
    void aPausedWorldStillSyncsAndItsStateSnapshotSaysPaused() throws Exception{
        Session alpha = play("alpha", 1);
        Unit dagger = spawn("dagger", 80f, 80f);
        dagger.vel.set(1f, 0f);
        ticks(24);
        world.paused(true);
        float x = dagger.x;
        int before = ticksOf(alpha, Packets.STATE_SNAPSHOT).size();
        ticks(120);
        assertEquals(x, dagger.x, "the unit stands still");
        List<Long> states = ticksOf(alpha, Packets.STATE_SNAPSHOT);
        assertEquals(10, states.size() - before, "state snapshots at the usual cadence while paused");
        assertEquals(states.subList(before, states.size()), ticksOf(alpha, Packets.ENTITY_SNAPSHOT).subList(before, states.size()));
        var last = loopback.to(alpha).stream().filter(sent -> sent.id() == Packets.STATE_SNAPSHOT).toList().getLast();
        var snapshot = ByteBuffer.wrap(payload(last.frame()));
        snapshot.getFloat();
        snapshot.getInt();
        snapshot.getInt();
        assertEquals(1, snapshot.get(), "paused");
        assertEquals(0, snapshot.get(), "not over");

        world.paused(false);
        ticks(24);
        var resumed = ByteBuffer.wrap(payload(loopback.to(alpha).stream().filter(sent -> sent.id() == Packets.STATE_SNAPSHOT).toList().getLast().frame()));
        resumed.getFloat();
        resumed.getInt();
        resumed.getInt();
        assertEquals(0, resumed.get(), "playing again");
        assertNotEquals(x, dagger.x);
    }

    @Test
    void stateAndEntitySnapshotsFollowTheWorldsPhase() throws Exception{
        Session alpha = play("alpha", 1);
        ticks(120);
        var due = new ArrayList<Long>();
        for(long tick = 1; tick <= 120; tick++) if(Stagger.snapshotDue(ID, tick, 12)) due.add(tick);
        assertEquals(10, due.size());
        assertEquals(due, ticksOf(alpha, Packets.STATE_SNAPSHOT));
        assertEquals(due, ticksOf(alpha, Packets.ENTITY_SNAPSHOT), "one packet each: only the player is synced");
        // the state first, then the entities (NetServer.java:1322)
        assertEquals(List.of(Packets.STATE_SNAPSHOT, Packets.ENTITY_SNAPSHOT), idsAt(alpha, due.getFirst()).subList(0, 2));
        var entities = ByteBuffer.wrap(payload(loopback.to(alpha).stream().filter(s -> s.id() == Packets.ENTITY_SNAPSHOT).findFirst().orElseThrow().frame()));
        assertEquals(1, entities.getShort());
        entities.getShort(); // the byte array's length
        assertEquals(roster.firstEntry().getValue().id(), entities.getInt(), "the player itself");
        assertEquals(new Viewer(alpha.id(), 1, 0f, 0f, 0f, 0f, false), alpha.viewer());
    }

    @Test
    void theViewerFollowsTheClientsReportedView() throws Exception{
        Session alpha = play("alpha", 1);
        // what clientSnapshot admission (Snapshot.admit) records of the client's view
        var input = com.xpdustry.shard.server.intent.PlayerInput.of(roster.get(alpha.id()));
        input.lastSnapshot = 0;
        input.viewX = 120f;
        input.viewY = 80f;
        input.viewWidth = 640f;
        input.viewHeight = 360f;
        ticks(24);
        assertEquals(new Viewer(alpha.id(), 1, 120f, 80f, 640f, 360f, false), alpha.viewer());
        input.viewWidth = 1280f;
        ticks(24);
        assertEquals(1280f, alpha.viewer().width(), "the next snapshot's view");
    }

    @Test
    void aTicksCallsGoOutBeforeItsSnapshots() throws Exception{
        Session alpha = play("alpha", 1);
        Unit unit = spawn("dagger", 40f, 40f);
        long due = 1;
        while(!Stagger.snapshotDue(ID, due, 12)) due++;
        ticks((int)due - 1);
        unit.kill();
        tick();
        assertEquals(due, world.ticks());
        List<Integer> sent = idsAt(alpha, due);
        assertEquals(Packets.UNIT_DEATH, sent.getFirst(), sent.toString());
        assertTrue(sent.indexOf(Packets.UNIT_DEATH) < sent.indexOf(Packets.STATE_SNAPSHOT));
    }

    @Test
    void entitySnapshotsCloseOncePast800Bytes() throws Exception{
        Session alpha = play("alpha", 1);
        for(int i = 0; i < 60; i++) spawn(i % 2 == 0 ? "dagger" : "flare", 16f + i * 4f, 120f);
        ticks(24);
        long tick = ticksOf(alpha, Packets.STATE_SNAPSHOT).getFirst();
        var chunks = new ArrayList<byte[]>();
        for(int i = 0; i < loopback.sent.size(); i++){
            Loopback.Sent sent = loopback.sent.get(i);
            if(sent.session() == alpha.id() && sent.id() == Packets.ENTITY_SNAPSHOT && ticks.get(i) == tick) chunks.add(payload(sent.frame()));
        }
        assertTrue(chunks.size() > 2, chunks.size() + " packets");
        int amounts = 0;
        for(int i = 0; i < chunks.size(); i++){
            var in = ByteBuffer.wrap(chunks.get(i));
            amounts += in.getShort();
            int data = in.getShort() & 0xffff;
            if(i < chunks.size() - 1) assertTrue(data > 800, "a packet closes only past 800 bytes: " + data);
            else assertTrue(data <= 800 + 200, "the rest: " + data);
        }
        assertEquals(61, amounts, "60 units and the player");
    }

    @Test
    void healthGoesOutAtOnceThenAtMostEveryHalfSecond() throws Exception{
        Session alpha = play("alpha", 1);
        Building router = world.buildings().setBlock(10, 10, EPOCH.block("router"), 1, 0, Cause.SERVER);
        ticks(5);
        router.damage(5f);
        tick();
        long first = world.ticks();
        for(int i = 0; i < 40; i++){
            router.damage(1f);
            tick();
        }
        List<Long> sent = ticksOf(alpha, Packets.BUILD_HEALTH_UPDATE);
        assertEquals(List.of(first, first + 30), sent);
        var update = ByteBuffer.wrap(payload(loopback.to(alpha).stream().filter(s -> s.id() == Packets.BUILD_HEALTH_UPDATE).findFirst().orElseThrow().frame()));
        assertEquals(2, update.getInt(), "one (pos, health) pair");
        assertEquals(router.pos(), update.getInt());
        assertEquals(Float.floatToRawIntBits(router.maxHealth - 5f), update.getInt());
    }

    /**
     * A client's own config is forwarded by its intent ({@code ConfigForwardServerTest}), so its event sends nothing;
     * every other config, a player's cause or not, is the server's {@code Call.tileConfig(null, ...)} to everyone.
     */
    @Test
    void onlyTheServersConfigsAreCallsOfTheirOwn() throws Exception{
        Session alpha = play("alpha", 1), beta = play("beta", 1);
        Building router = world.buildings().setBlock(10, 10, EPOCH.block("router"), 1, 0, Cause.SERVER);
        world.buildings().configureForwarded(router, null, 3, new Cause.Player(alpha.id()));
        world.buildings().configure(router, null, 4, new Cause.Player(alpha.id()));
        world.buildings().configure(router, null, 5, Cause.SERVER);
        tick();
        assertEquals(2, ticksOf(alpha, Packets.TILE_CONFIG).size(), "alpha gets the server's two, not its own");
        assertEquals(2, ticksOf(beta, Packets.TILE_CONFIG).size());
        for(Session session : List.of(alpha, beta)){
            var configs = loopback.to(session).stream().filter(s -> s.id() == Packets.TILE_CONFIG).toList();
            for(int i = 0; i < configs.size(); i++){
                var config = ByteBuffer.wrap(payload(configs.get(i).frame()));
                assertEquals(-1, config.getInt(), "configureAny names no player");
                assertEquals(router.pos(), config.getInt());
                assertEquals(WireObjects.INT, config.get());
                assertEquals(4 + i, config.getInt());
            }
        }
    }

    @Test
    void aDestroyedBuildingIsOneCall() throws Exception{
        Session alpha = play("alpha", 1);
        Building router = world.buildings().setBlock(10, 10, EPOCH.block("router"), 1, 0, Cause.SERVER);
        tick();
        world.buildings().kill(router, Cause.SERVER);
        tick();
        assertEquals(List.of(world.ticks()), ticksOf(alpha, Packets.BUILD_DESTROYED));
        assertEquals(List.of(), ticksOf(alpha, Packets.SET_TILE), "its tile changes are the client's to make");
        assertEquals(List.of(), ticksOf(alpha, Packets.REMOVE_TILE));
    }

    @Test
    void teammatesGetEachOthersPlanPreviewsEveryHalfSecond() throws Exception{
        Session alpha = play("alpha", 1), beta = play("beta", 1), gamma = play("gamma", 2);
        ticks(61);
        assertEquals(List.of(1L, 31L, 61L), ticksOf(alpha, Packets.CLIENT_PLAN_SNAPSHOT_RECEIVED));
        assertEquals(List.of(1L, 31L, 61L), ticksOf(beta, Packets.CLIENT_PLAN_SNAPSHOT_RECEIVED));
        assertEquals(List.of(), ticksOf(gamma, Packets.CLIENT_PLAN_SNAPSHOT_RECEIVED), "no teammate");
        var preview = ByteBuffer.wrap(payload(loopback.to(alpha).stream().filter(s -> s.id() == Packets.CLIENT_PLAN_SNAPSHOT_RECEIVED).findFirst()
            .orElseThrow().frame()));
        assertEquals(roster.get(beta.id()).id(), preview.getInt());
        assertEquals(1, preview.getInt(), "the first group");
        assertEquals(0, preview.getShort(), "no plans");
    }

    record Preview(long session, int groupId, List<com.xpdustry.shard.wire.Codecs.ClientPlan> plans) implements PlanPreviews{
    }

    record Request(long session, int pos) implements SnapshotRequest{
    }

    @Test
    void previewBatchesAreAssembledThenShownAsVanillaCommitsThem() throws Exception{
        Session alpha = play("alpha", 1), beta = play("beta", 1);
        var plan = new com.xpdustry.shard.wire.Codecs.ClientPlan(3, 4, EPOCH.block("router").id(), 0, null);
        // group 1 in two batches, then a late batch of an older group
        world.publish(new Preview(beta.id(), 1, List.of(plan, plan)));
        world.publish(new Preview(beta.id(), 1, List.of(plan)));
        world.publish(new Preview(beta.id(), 0, List.of(plan)));
        ticks(31);
        List<Integer> sizes = new ArrayList<>();
        for(Loopback.Sent sent : loopback.to(alpha)){
            if(sent.id() != Packets.CLIENT_PLAN_SNAPSHOT_RECEIVED) continue;
            var preview = ByteBuffer.wrap(payload(sent.frame()));
            assertEquals(roster.get(beta.id()).id(), preview.getInt());
            preview.getInt();
            sizes.add((int)preview.getShort());
        }
        // tick 1 is inside the 100 ms window: nothing yet; by tick 31 the group is committed
        assertEquals(List.of(0, 3), sizes);
    }

    @Test
    void aSnapshotRequestIsAnsweredToItsAskerOnly() throws Exception{
        Session alpha = play("alpha", 1), beta = play("beta", 1);
        Building router = world.buildings().setBlock(10, 10, EPOCH.block("router"), 1, 0, Cause.SERVER);
        tick();
        int before = ticksOf(beta, Packets.BLOCK_SNAPSHOT).size();
        world.publish(new Request(alpha.id(), router.pos()));
        world.publish(new Request(alpha.id(), com.xpdustry.shard.common.geom.Point2.pack(20, 20)));
        tick();
        List<Loopback.Sent> answers = loopback.to(alpha).stream().filter(s -> s.id() == Packets.BLOCK_SNAPSHOT).toList();
        var answer = ByteBuffer.wrap(payload(answers.getLast().frame()));
        assertEquals(1, answer.getShort(), "one building; none for an empty tile");
        answer.getShort();
        assertEquals(router.pos(), answer.getInt());
        assertEquals(before, ticksOf(beta, Packets.BLOCK_SNAPSHOT).size(), "beta asked for nothing");
    }

    @Test
    void aRecorderSeesEveryFrameWithTheEventsSinceTheLast() throws Exception{
        play("alpha", 1);
        var frames = new ArrayList<Long>();
        var seen = new ArrayList<List<WorldEvent>>();
        var attached = new ArrayList<Long>();
        Unit unit = spawn("dagger", 40f, 40f);
        ticks(1);
        sync.record(new Recorder(){
            @Override
            public int frameEvery(){
                return 3;
            }

            @Override
            public void attached(World world){
                attached.add(world.ticks());
            }

            @Override
            public void frame(World world, List<WorldEvent> events){
                frames.add(world.ticks());
                seen.add(events);
            }
        });
        ticks(3);
        unit.kill();
        ticks(5);
        assertEquals(List.of(3L), attached, "joins at its first frame boundary");
        assertEquals(List.of(3L, 6L, 9L), frames);
        assertEquals(List.of(), seen.get(0));
        assertEquals(List.of(new UnitEvent.Death(unit.id())), seen.get(1), "the death at tick 5 shows in the frame at 6");
        assertEquals(List.of(), seen.get(2));
    }

    @Test
    void aCallThatCantBeEncodedIsLeftOutWithoutFailingTheTick() throws Exception{
        Session alpha = play("alpha", 1);
        Building router = world.buildings().setBlock(10, 10, EPOCH.block("router"), 1, 0, Cause.SERVER);
        // a value the sim keeps but TypeIO has no tag for
        world.buildings().configure(router, null, (short)3, Cause.SERVER);
        router.damage(1f);
        tick();
        assertEquals(1, sync.failedCalls());
        assertEquals(List.of(world.ticks()), ticksOf(alpha, Packets.BUILD_HEALTH_UPDATE), "the rest of the tick goes out");
    }

    @Test
    void nothingGoesOutWithNobodyPlaying() throws Exception{
        Building router = world.buildings().setBlock(10, 10, EPOCH.block("router"), 1, 0, Cause.SERVER);
        router.damage(1f);
        ticks(60);
        assertEquals(List.of(), loopback.sent);
        Session alpha = play("alpha", 1);
        tick();
        assertEquals(List.of(world.ticks()), ticksOf(alpha, Packets.BUILD_HEALTH_UPDATE), "the queue waited, as vanilla's does");
    }

    record Reply(long session, Audience audience, com.xpdustry.shard.wire.Packet packet) implements Outbound{
    }

    @Test
    void anOutboundEventGoesToItsOwnAudience() throws Exception{
        Session alpha = play("alpha", 1), beta = play("beta", 1);
        world.publish(new Reply(alpha.id(), Audience.only(alpha.id()), new Packets.SetPosition(1f, 2f)));
        tick();
        assertEquals(List.of(world.ticks()), ticksOf(alpha, Packets.SET_POSITION));
        assertEquals(List.of(), ticksOf(beta, Packets.SET_POSITION));
    }
}
