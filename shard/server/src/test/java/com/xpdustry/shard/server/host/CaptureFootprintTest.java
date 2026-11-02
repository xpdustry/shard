package com.xpdustry.shard.server.host;

import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.lang.ref.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static com.xpdustry.shard.server.ServerTest.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Review 39's capture-budget finding: what queued captures hold stays within what the budget reserved for them, and
 * that within its limits. A 500x500 uniform map streams in a few KB (its tiles go in runs), but every capture holds
 * three {@code short}s and an {@code int} per tile; buildings and units added after warmup make the footprint the
 * world warmed with stale. With every encode held, joins come over several ticks into two such worlds.
 */
class CaptureFootprintTest{
    static final int SIZE = 500, JOINS = 8;
    static final long GLOBAL = 64L << 20, WORLD = 40L << 20, CAPTURE = 32L << 20;

    @TempDir
    static Path temp;
    static Server server;

    @BeforeAll
    static void start() throws Exception{
        Path root = temp.resolve("main");
        Files.createDirectories(root.resolve("maps"));
        stoneMap(root.resolve("maps/stone.msav"));
        server = Server.start(new Server.Options(ServerTest.configure(root, """
            host:
              worlds: ["east=stone", "west=stone"]
              join: round-robin
            """), Map.of(), false));
    }

    @AfterAll
    static void stop(){
        if(server != null) server.close();
    }

    /**
     * {@code SIZE x SIZE} stone and nothing else, as tools/bots/load/StoneMap.java writes it, with {@code canGameOver}
     * off in its rules: without a core, a survival game on it ends at its first tick, as in vanilla, and the server
     * would reload it onto the next map every {@code game.round-extra-time}.
     */
    static void stoneMap(Path file) throws Exception{
        Epoch epoch = Epoch.vanilla();
        WorldConfig template = HostedSystems.template(epoch, 0, 256);
        var world = new World(new WorldConfig(template.content(), SIZE, SIZE, 0, template.systems(), template.kinds(), template.inboxCapacity(),
            template.journal()));
        int stone = epoch.block("stone").id();
        for(int pos = 0; pos < SIZE * SIZE; pos++) world.tiles().floor(pos, stone);
        var state = new MapState();
        state.tags.put("name", "Stone");
        Rules rules = world.part(WorldRules.class).rules;
        rules.canGameOver = false;
        int team = rules.defaultTeam;
        WorldStreamSource source = new WorldProjection(epoch, BuildingCodecs.builtIn()).capture(world, state, PlayerState.fresh(0, "", team));
        Files.write(file, MsavWriter.write(MsavWriter.meta(source, state.tags, team, true, 0L), source));
    }

    /** A capture, as weakly as its map: once nothing holds it (sent, or dropped unsent), the GC clears it. */
    record Held(WeakReference<MapImage> map, long payload){
    }

    /** The bytes a capture's arrays and encoded parts hold, without any object overhead: less than it really holds. */
    static long payload(WorldStreamSource source){
        MapImage map = source.map();
        long bytes = 10L * map.width() * map.height() + source.rules().length() + source.markers().length;
        for(byte[] chunk : map.chunks().values()) bytes += chunk.length;
        for(MapState.EntityChunk entity : source.entities()) bytes += entity.body().length;
        return bytes;
    }

    @Test
    void queuedCapturesHoldNoMoreThanTheyReservedWithinTheLimits() throws Exception{
        var budget = new CaptureBudget(GLOBAL, WORLD, CAPTURE, 32);
        server.host().captureBudget(budget);
        var worlds = List.of(server.host().world("east").orElseThrow(), server.host().world("west").orElseThrow());
        var held = new LinkedBlockingQueue<Runnable>();
        // the captures each world made; those still held after a GC are those whose encodes wait in held
        var captured = new ConcurrentHashMap<HostedWorld, List<Held>>();
        for(HostedWorld world : worlds){
            captured.put(world, new CopyOnWriteArrayList<>());
            world.encodeOn(held::add);
            world.captureWith((w, state, players) -> {
                List<WorldStreamSource> sources = server.host().sends().capture(w, state, players);
                captured.get(world).add(new Held(new WeakReference<>(sources.getFirst().map()), payload(sources.getFirst())));
                return sources;
            });
        }
        var clients = new ArrayList<TestClient>();
        try{
            // after warmup, east grows: buildings and units its warm footprint doesn't know
            HostedWorld east = worlds.getFirst();
            long warm = east.footprint().capture();
            east.inspect(world -> {
                var router = world.content().block("router");
                for(int i = 0; i < 4000; i++) world.buildings().setBlock(10 + i % 100, 10 + i / 100, router, 1, 0, Cause.SERVER);
                var dagger = world.content().unit("dagger");
                for(int i = 0; i < 500; i++) Unit.spawn(world, dagger, 1, 800f + i, 800f, 0f);
                return null;
            }).get(Hang.SECONDS, TimeUnit.SECONDS);

            for(int i = 0; i < JOINS; i++){
                var client = new TestClient(server.transport().address());
                clients.add(client);
                client.connect("join" + i);
                // over several ticks: each join waits for a tick after it was routed
                int routed = i + 1;
                await("join " + i + " to be routed", () -> loading() == routed);
                long ticks = ServerTest.ticks(server);
                await("a tick", () -> ServerTest.ticks(server) > ticks);
                check(budget, worlds, captured);
            }
            await("some joins to wait for memory", () -> budget.refused() > 0);
            check(budget, worlds, captured);
            assertTrue(east.footprint().capture() > warm, "east's footprint grew with it: " + warm + " -> " + east.footprint().capture());

            // the encodes go, one by one; every join loads, and the budget empties
            long deadline = System.nanoTime() + Hang.NANOS;
            int loaded = 0;
            while(loaded < JOINS){
                assertTrue(System.nanoTime() < deadline, "only " + loaded + " loaded");
                Runnable next = held.poll(100, TimeUnit.MILLISECONDS);
                if(next != null) next.run();
                check(budget, worlds, null);
                loaded = 0;
                for(TestClient client : clients) loaded += client.loads().size();
            }
            await("the budget to empty", () -> budget.reserved() == 0 && budget.inFlight() == 0);
        }finally{
            for(HostedWorld world : worlds){
                world.encodeOn(null);
                world.captureWith((w, state, players) -> server.host().sends().capture(w, state, players));
            }
            server.host().captureBudget(CaptureBudget.defaults());
            for(TestClient client : clients) client.close();
        }
    }

    private static long loading(){
        return server.sessions().all().stream().filter(s -> s.state() == SessionState.LOADING).count();
    }

    /** Each world's reservation covers what its held captures hold, and no reservation passes a limit. */
    private static void check(CaptureBudget budget, List<HostedWorld> worlds, Map<HostedWorld, List<Held>> captured){
        if(captured != null){
            System.gc();
            System.gc();
        }
        long total = budget.reserved();
        assertTrue(total <= GLOBAL, "reserved " + total + " of " + GLOBAL);
        for(HostedWorld world : worlds){
            long reserved = budget.reserved(world);
            assertTrue(reserved <= WORLD, world.id() + " reserved " + reserved + " of " + WORLD);
            if(captured == null) continue;
            long holds = 0;
            for(Held capture : captured.get(world)) if(capture.map().get() != null) holds += capture.payload();
            assertTrue(holds <= reserved, world.id() + "'s held captures hold at least " + holds + " B, with " + reserved + " B reserved");
        }
    }

    @Test
    void theCorelessWorldsPlayOn() throws Exception{
        for(String id : List.of("east", "west")){
            HostedWorld world = server.host().world(id).orElseThrow();
            long tick = world.inspect(World::ticks).get(Hang.SECONDS, TimeUnit.SECONDS);
            await(id + " to tick", () -> world.inspect(World::ticks).orTimeout(Hang.SECONDS, TimeUnit.SECONDS).join() > tick + 2);
            assertFalse(world.inspect(w -> WaveState.of(w).gameOver).get(Hang.SECONDS, TimeUnit.SECONDS), id + "'s game is not over");
            assertFalse(server.game().waiting(world.id()), id + " waits for no next map");
        }
    }
}
