package com.xpdustry.shard.server.game;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.net.Frame;
import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.admin.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.server.world.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.Codecs;
import com.xpdustry.shard.wire.v160_7.Packets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static com.xpdustry.shard.server.ServerTest.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The game flow end to end with byte-level clients, one world per gamemode: a survival game lost with its cores, an
 * attack game won on the enemy's, a PvP game down to its last team, a sandbox that nothing ends while its core
 * stands; each followed by vanilla's game-over message, {@code updateGameOver}, and the world reloaded onto the
 * rotation's next map with every session moved over its connection. A world with no next map kicks its players with
 * {@code gameover} and stops.
 */
class GameFlowServerTest{
    @TempDir
    static Path temp;
    static Server server;
    final List<TestClient> clients = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception{
        Path root = ServerTest.configure(temp.resolve("main"), """
            host:
              join: first
            chat:
              show-connect-messages: false
            """);
        Files.createDirectories(root.resolve("worlds"));
        world(root, "survive", "[fortress, maze]", "all");
        world(root, "siege", "[veins, fortress]", "all");
        world(root, "arena", "[glacier, veins]", "all");
        world(root, "box", "[maze, fortress]", "all");
        world(root, "last", "[fortress]", "none");
        Files.writeString(root.resolve("worlds").resolve("lull.yml"), """
            game:
              maps: [fortress]
              auto-pause: true
            """);
        // every hostable map: more than three to draw from
        world(root, "drawn-a", "[]", "all");
        world(root, "drawn-b", "[]", "all");
        server = Server.start(new Server.Options(root, Map.of(), true));
    }

    static void world(Path root, String id, String maps, String shuffle) throws Exception{
        Files.writeString(root.resolve("worlds").resolve(id + ".yml"), """
            game:
              maps: %s
              shuffle: %s
              round-extra-time: 1s
            """.formatted(maps, shuffle));
    }

    @AfterAll
    static void stop(){
        if(server != null) server.close();
    }

    @AfterEach
    void settle() throws Exception{
        for(TestClient client : clients) client.close();
        await("the sessions to close", () -> server.sessions().size() == 0);
        for(HostedWorld world : server.host().worlds()) server.host().stop(world.id(), KickReason.SERVER_CLOSE).get(Hang.SECONDS, TimeUnit.SECONDS);
        server.admin().pardonAll();
    }

    HostedWorld host(String entry) throws Exception{
        return server.host().host(server.host().spec(entry)).get(Hang.SECONDS, TimeUnit.SECONDS);
    }

    HostedWorld world(String id){
        return server.host().world(id).orElseThrow();
    }

    /** Connects {@code name}, which loads {@code map} and plays. */
    TestClient join(String name, String map) throws Exception{
        var client = new TestClient(server.transport().address());
        clients.add(client);
        client.connect(name);
        assertEquals(List.of("load " + map), client.loads(1));
        client.confirm();
        await(name + " to play", () -> session(name) != null && session(name).state() == SessionState.IN_WORLD && added(session(name)));
        return client;
    }

    static Session session(String name){
        return server.sessions().all().stream().filter(s -> s.identity().plainName().equals(name)).findFirst().orElse(null);
    }

    static boolean added(Session session){
        if(!(session.world() instanceof HostedWorld world) || world.managed() == null) return false;
        var player = world.inspect(w -> Roster.of(w).player(session.id())).exceptionally(failure -> null).orTimeout(Hang.SECONDS, TimeUnit.SECONDS).join();
        return player != null && player.isAdded();
    }

    /** Destroys every core of {@code team}, as a logged admin command. */
    record KillCores(int team) implements Command.Admin{
        @Override
        public Verdict apply(World world, Cause cause){
            for(Building core : List.copyOf(Teams.of(world).cores(team))) core.kill();
            return Verdict.ADMITTED;
        }
    }

    static void kill(HostedWorld world, int team) throws Exception{
        assertTrue(world.managed().offer(new Submission(new KillCores(team), new Cause.Admin("test"))).get(Hang.SECONDS, TimeUnit.SECONDS));
    }

    static String info(TestClient client) throws InterruptedException{
        return Codecs.readString(new Reads(client.payload(Packets.InfoMessage.ID)));
    }

    static int winner(TestClient client) throws InterruptedException{
        byte[] payload = client.payload(Packets.UpdateGameOver.ID);
        return Packets.UpdateGameOver.read(new Reads(payload), true, null).winner();
    }

    /** After the game over: every client gets {@code worldDataBegin} and {@code next}'s stream, confirms and plays there. */
    void reloaded(String id, String next, String... names) throws Exception{
        for(String name : names){
            TestClient client = client(name);
            int seen = loads.merge(name, 2, Integer::sum);
            List<String> history = client.loads(seen);
            assertEquals(List.of("begin", "load " + next), history.subList(seen - 2, seen), name + ": " + history);
            client.confirm();
        }
        HostedWorld world = world(id);
        assertEquals(next, world.map().name());
        for(String name : names){
            await(name + " to play in " + next, () -> session(name).world() == world && session(name).state() == SessionState.IN_WORLD && added(session(name)));
        }
        // a new game: the next map's own, not the one that ended
        assertFalse(world.inspect(w -> WaveState.of(w).gameOver).get(Hang.SECONDS, TimeUnit.SECONDS));
        assertEquals(1, server.host().worlds().stream().filter(w -> w.id().name().equals(id)).count());
    }

    final Map<String, TestClient> byName = new HashMap<>();
    /** How many loads each client's history has: its join's, then two per reload. */
    final Map<String, Integer> loads = new HashMap<>();

    TestClient client(String name){
        return byName.get(name);
    }

    TestClient player(String name, String map) throws Exception{
        TestClient client = join(name, map);
        byName.put(name, client);
        loads.put(name, 1);
        return client;
    }

    @Test
    void aSurvivalGameLostReloadsEveryoneOntoTheNextMap() throws Exception{
        HostedWorld world = host("survive=fortress survival");
        TestClient alpha = player("alpha", "Fortress"), beta = player("beta", "Fortress");
        kill(world, Team.SHARDED);
        String message = "[scarlet]Game over![]\n\nNext selected map: [accent]Maze[white] by[accent] Anuke[white].\nNew game begins in 1 seconds.";
        for(TestClient client : List.of(alpha, beta)){
            assertEquals(Team.CRUX, winner(client), "the wave team wins");
            assertEquals(message, info(client));
        }
        reloaded("survive", "Maze", "alpha", "beta");
        await("the reload to finish", () -> !server.game().waiting(world.id()));

        // the console's gameover: the wave team wins again, and the rotation goes back to fortress
        HostedWorld maze = world("survive");
        assertTrue(server.game().force(maze).get(Hang.SECONDS, TimeUnit.SECONDS));
        assertEquals(Team.CRUX, winner(alpha));
        assertTrue(info(alpha).contains("Next selected map: [accent]Fortress[white] by[accent] Anuke[white]."));
        reloaded("survive", "Fortress", "alpha", "beta");
    }

    @Test
    void anAttackGameIsWonOnTheEnemyCores() throws Exception{
        HostedWorld world = host("siege=veins attack");
        // veins is a PvP map, which an attack shuffle skips, and the other default maps have no enemy core (an attack
        // game on one ends at once, in vanilla too): the console's nextmap brings veins back
        server.game().rotation().next(world.id(), server.host().maps().find("veins").orElseThrow());
        TestClient alpha = player("alpha", "Veins");
        kill(world, Team.CRUX);
        assertEquals(Team.SHARDED, winner(alpha));
        assertEquals("[scarlet]Game over![]\n\nNext selected map: [accent]Veins[white].\nNew game begins in 1 seconds.", info(alpha));
        reloaded("siege", "Veins", "alpha");
        // the new attack game runs: the enemy's cores stand again
        assertFalse(world("siege").inspect(w -> Teams.of(w).cores(Team.CRUX).isEmpty()).get(Hang.SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void aPvpGameEndsWithItsLastTeam() throws Exception{
        HostedWorld world = host("arena=glacier pvp");
        TestClient alpha = player("alpha", "Glacier");
        // a plugin hears vanilla's CoreChangeEvent for each core the kill destroys
        var heard = new ConcurrentLinkedQueue<CoreChangeEvent>();
        server.host().listen(new HostListener(){
            @Override
            public void event(HostedWorld from, World state, WorldEvent event){
                if(from == world && event instanceof CoreChangeEvent change) heard.add(change);
            }
        });
        List<Integer> cores = world.inspect(w -> Teams.of(w).cores(Team.CRUX).stream().map(Building::pos).toList()).get(Hang.SECONDS, TimeUnit.SECONDS);
        assertFalse(cores.isEmpty());
        kill(world, Team.CRUX);
        assertEquals(Team.SHARDED, winner(alpha));
        assertEquals("[accent]The [#ffd37fff]sharded[] team is victorious![]\n\nNext selected map: [accent]Veins[white].\nNew game begins in 1 seconds.", info(alpha));
        var destroyed = heard.stream().filter(e -> e.change() == CoreChangeEvent.Change.DESTROYED).toList();
        assertEquals(cores, destroyed.stream().map(CoreChangeEvent::pos).toList(), "heard: " + heard);
        assertTrue(destroyed.stream().allMatch(e -> e.team() == Team.CRUX), "heard: " + heard);
        reloaded("arena", "Veins", "alpha");
    }

    /** {@code game.auto-pause}: vanilla's {@code autoPause}, per world (ServerControl.java:320-335). */
    @Test
    void anEmptyWorldWithAutoPauseWaitsForItsFirstPlayer() throws Exception{
        HostedWorld world = host("lull=fortress survival");
        await("the empty world to pause", () -> world.inspect(w -> w.paused() && Teams.of(w).autoPaused()).join());
        long ticks = world.inspect(World::ticks).get(Hang.SECONDS, TimeUnit.SECONDS);
        float countdown = world.inspect(w -> WaveState.of(w).wavetime).get(Hang.SECONDS, TimeUnit.SECONDS);
        await("ticks while paused", () -> world.inspect(World::ticks).join() > ticks + 30);
        assertEquals(countdown, world.inspect(w -> WaveState.of(w).wavetime).get(Hang.SECONDS, TimeUnit.SECONDS), "the wave countdown stood still");
        player("alpha", "Fortress");
        await("the first player to resume it", () -> world.inspect(w -> !w.paused() && !Teams.of(w).autoPaused()).join());
        // the console's pause is not the auto-pause's: a player in the world doesn't lift it
        assertTrue(world.managed().offer(new Submission(new SetPause(true), new Cause.Admin("test"))).get(Hang.SECONDS, TimeUnit.SECONDS));
        await("the console's pause", () -> world.inspect(World::paused).join());
        assertFalse(world.inspect(w -> Teams.of(w).autoPaused()).get(Hang.SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void nothingEndsASandboxWhileItsCoreStands() throws Exception{
        HostedWorld world = host("box=maze sandbox");
        TestClient alpha = player("alpha", "Maze");
        long tick = world.inspect(World::ticks).get(Hang.SECONDS, TimeUnit.SECONDS);
        await("a few seconds of play", () -> world.inspect(World::ticks).orTimeout(Hang.SECONDS, TimeUnit.SECONDS).join() > tick + 120);
        assertFalse(alpha.receives(Packets.UpdateGameOver.ID, 0));
        assertFalse(alpha.receives(Packets.InfoMessage.ID, 0));
        // vanilla's checkGameState has no sandbox case: losing every core still ends it
        kill(world, Team.SHARDED);
        assertEquals(Team.CRUX, winner(alpha));
        reloaded("box", "Fortress", "alpha");
    }

    @Test
    void withoutANextMapThePlayersAreKickedAndTheWorldStops() throws Exception{
        HostedWorld world = host("last=fortress survival");
        TestClient alpha = player("alpha", "Fortress");
        kill(world, Team.SHARDED);
        assertEquals(KickReason.GAMEOVER.id(), alpha.kickReason());
        assertTrue(alpha.closedWithin(Hang.MILLIS));
        await("the world to stop", () -> server.host().world("last").isEmpty());
    }

    @Test
    void aPlayerWhoJoinsDuringTheWaitMovesToo() throws Exception{
        var held = new LinkedBlockingQueue<Runnable>();
        server.game().after((delay, task) -> held.add(task));
        try{
            HostedWorld world = host("survive=fortress survival");
            server.game().rotation().next(world.id(), server.host().maps().find("labyrinth").orElseThrow());
            TestClient alpha = player("alpha", "Fortress");
            kill(world, Team.SHARDED);
            assertTrue(info(alpha).contains("Next selected map: [accent]Labyrinth[white]"), "nextmap goes first");
            player("late", "Fortress");
            Runnable reload = held.poll(Hang.SECONDS, TimeUnit.SECONDS);
            assertNotNull(reload);
            reload.run();
            reloaded("survive", "Labyrinth", "alpha", "late");
        }finally{
            server.game().after(null);
        }
    }

    @Test
    void aWorldStoppedDuringTheWaitIsNotReloadedAndIsNewOnceHostedAgain() throws Exception{
        var held = new LinkedBlockingQueue<Runnable>();
        server.game().after((delay, task) -> held.add(task));
        try{
            HostedWorld world = host("survive=fortress survival");
            kill(world, Team.SHARDED);
            await("the reload to wait", () -> server.game().waiting(world.id()));
            server.host().stop(world.id(), KickReason.SERVER_CLOSE).get(Hang.SECONDS, TimeUnit.SECONDS);
            assertFalse(server.game().waiting(world.id()), "the stop drops the reload");
            Runnable stale = held.poll(Hang.SECONDS, TimeUnit.SECONDS);
            assertNotNull(stale);

            // hosted again, the world is a new one: its game over is its own, and the old wait's task does nothing
            HostedWorld again = host("survive=fortress survival");
            stale.run();
            assertSame(again, world("survive"));
            TestClient alpha = player("alpha", "Fortress");
            kill(again, Team.SHARDED);
            assertEquals(Team.CRUX, winner(alpha));
            assertTrue(info(alpha).startsWith("[scarlet]Game over![]"));
            Runnable reload = held.poll(Hang.SECONDS, TimeUnit.SECONDS);
            assertNotNull(reload);
            reload.run();
            reloaded("survive", "Maze", "alpha");
        }finally{
            server.game().after(null);
        }
    }

    @Test
    void aReloadInWaitingNeverStartsOnceTheServerClosed() throws Exception{
        Path root = ServerTest.configure(temp.resolve("closing"), "");
        Files.createDirectories(root.resolve("worlds"));
        world(root, "ending", "[maze]", "all");
        var held = new LinkedBlockingQueue<Runnable>();
        Server closing = Server.start(new Server.Options(root, Map.of(), true));
        try{
            closing.game().after((delay, task) -> held.add(task));
            HostedWorld world = closing.host().host(closing.host().spec("ending=fortress survival")).get(Hang.SECONDS, TimeUnit.SECONDS);
            kill(world, Team.SHARDED);
            await("the reload to wait", () -> closing.game().waiting(world.id()));
            closing.close();
            assertFalse(closing.game().waiting(world.id()));
            // the wait ends after the close, as CaptureFootprintTest's did: nothing loads into the closed server
            Runnable reload = held.poll(Hang.SECONDS, TimeUnit.SECONDS);
            assertNotNull(reload);
            reload.run();
            assertSame(world, closing.host().world("ending").orElseThrow(), "no next map took its place");
        }finally{
            closing.close();
        }
    }

    @Test
    void aWorldsMapsDoNotDependOnTheOtherWorldsDraws(){
        var a = WorldId.of("drawn-a");
        var b = WorldId.of("drawn-b");
        MapCatalog.Entry fortress = server.host().maps().find("fortress").orElseThrow();
        var alone = new Rotation(server.host(), 42);
        var interleaved = new Rotation(server.host(), 42);
        var drawnAlone = new ArrayList<String>();
        var drawnInterleaved = new ArrayList<String>();
        for(int i = 0; i < 12; i++){
            drawnAlone.add(alone.next(a, fortress, Gamemode.SURVIVAL).file());
            // b's game overs between a's: with one random for both, they would move a's draws
            for(int j = 0; j <= i % 3; j++) interleaved.next(b, fortress, Gamemode.SURVIVAL);
            drawnInterleaved.add(interleaved.next(a, fortress, Gamemode.SURVIVAL).file());
        }
        assertEquals(drawnAlone, drawnInterleaved);
        assertTrue(new HashSet<>(drawnAlone).size() > 1, "a draws among the maps: " + drawnAlone);
        assertNotEquals(drawnAlone, draws(new Rotation(server.host(), 43), a, fortress), "another seed, another sequence");
    }

    static List<String> draws(Rotation rotation, WorldId id, MapCatalog.Entry prev){
        var out = new ArrayList<String>();
        for(int i = 0; i < 12; i++) out.add(rotation.next(id, prev, Gamemode.SURVIVAL).file());
        return out;
    }
}
