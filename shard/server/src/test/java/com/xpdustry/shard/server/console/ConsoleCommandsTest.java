package com.xpdustry.shard.server.console;

import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.admin.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.server.store.Bans;
import com.xpdustry.shard.sim.blocks.storage.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.waves.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static com.xpdustry.shard.server.ServerTest.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Each of vanilla's 43 console commands ({@code ServerControl.java:477}-{@code 1320}) on a running host: its lines,
 * and for the moderation ones the store's state after it. {@code ServerTest} has {@code version}, {@code say},
 * {@code yes}, {@code players} and {@code status} too. Players join from their own loopback addresses, so one test's
 * cooldowns and address bans don't reach the next.
 */
class ConsoleCommandsTest{
    /** Every {@code handler.register} in v160.7's {@code ServerControl}, in its order. */
    static final List<String> VANILLA = List.of("help", "version", "exit", "stop", "host", "maps", "reloadassets", "reloadmaps", "status",
        "mods", "mod", "js", "say", "pause", "rules", "dumpsettings", "fillitems", "playerlimit", "config", "subnet-ban", "name-ban", "whitelist",
        "shuffle", "nextmap", "kick", "ban", "bans", "unban", "pardon", "admin", "admins", "players", "runwave", "loadautosave", "load", "save",
        "saves", "gameover", "info", "search", "gc", "yes", "dos-ban");

    @TempDir
    static Path temp;
    static Server server;
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final Console console = new Console(server, new PrintStream(bytes, true, StandardCharsets.UTF_8), () -> {});
    final List<TestClient> clients = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception{
        server = Server.start(new Server.Options(ServerTest.configure(temp.resolve("main"), """
            host:
              worlds: [maze, fortress]
              join: first
            chat:
              show-connect-messages: false
            """), Map.of(), true));
    }

    @AfterAll
    static void stop(){
        if(server != null) server.close();
    }

    @AfterEach
    void settle() throws Exception{
        for(TestClient client : clients) client.close();
        await("the sessions to close", () -> server.sessions().size() == 0);
        AdminService admin = server.admin();
        for(var ban : admin.activeBans().join()) admin.revoke(ban.id(), "test").join();
        for(var grant : admin.admins().join()) admin.revokeAdmin(grant.player().uuid()).join();
        for(var grant : admin.whitelisted().join()) admin.unwhitelist(grant.player().uuid()).join();
        admin.clearNameBans().join();
        for(String address : admin.dosBans()) admin.dosUnban(address);
        admin.pardonAll();
    }

    /** Runs a line and returns what it printed. */
    String run(String line){
        bytes.reset();
        console.run(line);
        return bytes.toString(StandardCharsets.UTF_8);
    }

    TestClient join(String name, String local) throws Exception{
        var client = new TestClient(server.transport().address(), InetAddress.getByName(local));
        clients.add(client);
        client.connect(name);
        client.world();
        client.confirm();
        await(name + " to play", () -> session(name) != null && session(name).state() == SessionState.IN_WORLD);
        await(name + "'s join to be recorded", () -> server.admin().player(uuid(name)).join().isPresent());
        return client;
    }

    static Session session(String name){
        return server.sessions().all().stream().filter(s -> s.identity().plainName().equals(name)).findFirst().orElse(null);
    }

    static String uuid(String name){
        return TestClient.Connect.named(name).packet().uuidString();
    }

    static HostedWorld world(String id){
        return server.host().world(id).orElseThrow();
    }

    static List<Bans.Ban> bans(){
        return server.admin().activeBans().join();
    }

    static void contains(String text, String... lines){
        for(String line : lines) assertTrue(text.contains(line), "'" + line + "' in:\n" + text);
    }

    // ---- every command answers

    @Test
    void everyVanillaCommandIsKnown(){
        assertEquals(43, VANILLA.size());
        String help = run("help");
        for(String name : VANILLA){
            assertTrue(help.lines().anyMatch(line -> line.startsWith("  " + name + " ") || line.equals("  " + name)), name + " in:\n" + help);
            assertFalse(run("help " + name).contains("not found"), name);
        }
    }

    @Test
    void help(){
        assertTrue(run("help").startsWith("Commands:\n"));
        assertEquals("kick:\n  kick <name|session-id...> - Kick a person by name.\n", run("help kick"));
        assertEquals("Command nope not found!\n", run("help nope"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"reloadassets", "reloadmaps", "mods", "mod", "js", "rules", "loadautosave"})
    void refusedCommandsSayWhy(String name){
        String text = run(name + " anything");
        assertTrue(text.startsWith(name + " isn't available in Shard: ") && text.length() > name.length() + 40, text);
    }

    // ---- the host

    @Test
    void exitClosesTheServer() throws Exception{
        var own = Server.start(new Server.Options(ServerTest.configure(temp.resolve("exit"), """
            host:
              worlds: [maze]
            """), Map.of(), true));
        var out = new ByteArrayOutputStream();
        var exited = new CompletableFuture<Void>();
        new Console(own, new PrintStream(out, true, StandardCharsets.UTF_8), () -> exited.complete(null)).run("exit");
        assertEquals("Shutting down server.\n", out.toString(StandardCharsets.UTF_8));
        assertTrue(own.closed());
        assertTrue(exited.isDone());
    }

    @Test
    void hostAddsAWorld() throws Exception{
        try{
            contains(run("host labyrinth survival extra"), "Loading map...", "Map loaded: ");
            assertTrue(server.host().world("extra").isPresent());
        }finally{
            server.host().stop(server.host().world("extra").orElseThrow().id(), KickReason.SERVER_CLOSE).get(Hang.SECONDS, TimeUnit.SECONDS);
        }
    }

    @Test
    void stopStopsAWorld() throws Exception{
        server.host().host(server.host().spec("extra=labyrinth")).get(Hang.SECONDS, TimeUnit.SECONDS);
        assertEquals("Stopped extra.\n", run("stop extra"));
        assertTrue(server.host().world("extra").isEmpty());
        contains(run("stop nowhere"), "no world nowhere");
    }

    /**
     * {@code save}, {@code saves} and {@code load} (ServerControl.java:1183-1232): a world saved to a slot as a vanilla
     * msav and hosted back from it as a new world, with what it had built; a slot that would leave the directory is
     * refused, where vanilla's {@code saveDirectory.child(arg + ".msav")} takes it.
     */
    @Test
    void saveThenLoadHostsTheSameWorldAgain() throws Exception{
        HostedWorld maze = server.host().world("maze").orElseThrow();
        // something the map didn't have: a wall at a free tile next to the core
        int[] wall = maze.inspect(w -> {
            Building core = Teams.of(w).cores(w.part(com.xpdustry.shard.sim.rules.WorldRules.class).rules.defaultTeam).getFirst();
            return new int[]{core.tileX() + 4, core.tileY() + 4, core.team};
        }).get(Hang.SECONDS, TimeUnit.SECONDS);
        offer(maze, new SetWall(wall[0], wall[1], wall[2], true));
        await("the wall", () -> maze.inspect(w -> Console.describeTile(w, wall[0], wall[1])).join().contains("block=copper-wall"));
        contains(run("save slot-1"), "several worlds are hosted");
        contains(run("save slot-1 maze"), "Saved maze to ");
        Path file = temp.resolve("main/data/saves/slot-1.msav");
        assertTrue(Files.isRegularFile(file), "the slot's file");
        assertArrayEquals(new byte[]{'M', 'S', 'A', 'V'}, java.util.Arrays.copyOf(new java.util.zip.InflaterInputStream(Files.newInputStream(file)).readNBytes(4), 4));
        contains(run("saves"), "Save files: ", "| slot-1");

        for(String bad : List.of("../escape", "a/b", "..", "x".repeat(65))){
            contains(run("save " + bad + " maze"), "save slot");
        }
        contains(run("load ../escape"), "save slot");
        assertFalse(Files.exists(temp.resolve("main/data/escape.msav")));
        contains(run("load nothing-here"), "no save in slot 'nothing-here'");
        contains(run("load slot-1 maze"), "already hosted");

        try{
            contains(run("load slot-1 again"), "Save loaded: ");
            HostedWorld again = server.host().world("again").orElseThrow();
            String tile = again.inspect(w -> Console.describeTile(w, wall[0], wall[1])).get(Hang.SECONDS, TimeUnit.SECONDS);
            assertTrue(tile.contains("block=copper-wall"), tile);
            assertTrue(again.inspect(w -> Teams.of(w).playing()).get(Hang.SECONDS, TimeUnit.SECONDS), "a game: state.set(playing)");
        }finally{
            server.host().world("again").ifPresent(w -> server.host().stop(w.id(), KickReason.SERVER_CLOSE).join());
            offer(maze, new SetWall(wall[0], wall[1], wall[2], false));
        }
    }

    /** Builds or removes a copper wall, as a logged admin command. */
    record SetWall(int x, int y, int team, boolean build) implements com.xpdustry.shard.sim.world.Command.Admin{
        @Override
        public com.xpdustry.shard.sim.world.Verdict apply(com.xpdustry.shard.sim.world.World world, com.xpdustry.shard.sim.world.Cause cause){
            var block = world.content().block(build ? "copper-wall" : "air");
            world.buildings().setBlock(x, y, block, team, 0, cause);
            return com.xpdustry.shard.sim.world.Verdict.ADMITTED;
        }
    }

    static void offer(HostedWorld world, com.xpdustry.shard.sim.world.Command command) throws Exception{
        var submission = new com.xpdustry.shard.sim.world.Submission(command, new com.xpdustry.shard.sim.world.Cause.Admin("test"));
        assertTrue(world.managed().offer(submission).get(Hang.SECONDS, TimeUnit.SECONDS));
    }

    @Test
    void mapsShowsCustomMapsUnlessAsked(){
        assertEquals("No custom maps loaded. To display built-in maps, use the \"all\" argument.\n", run("maps"));
        contains(run("maps all"), "Maps:", "  maze: Maze", "  fortress: Fortress");
        contains(run("maps default"), "  maze: Maze");
    }

    @Test
    void dumpsettingsExplainsEachSetting(){
        contains(run("dumpsettings"), "net.player-limit");
    }

    @Test
    void playerlimitShowsTheSetting(){
        assertEquals("Player limit is currently 30.\n", run("playerlimit"));
        contains(run("playerlimit 5"), "net.player-limit in shard.yml");
        assertEquals(30, server.config().current().get(com.xpdustry.shard.server.config.ConfigKeys.NET_PLAYER_LIMIT));
    }

    @Test
    void configReadsByEitherNameAndReloads(){
        contains(run("config"), "All config values:", "| playerlimit (net.player-limit): 30", "| autoUpdate: dropped: ");
        assertEquals("'playerlimit' is currently 30.\n", run("config playerlimit"));
        assertEquals("'net.player-limit' is currently 30.\n", run("config net.player-limit"));
        contains(run("config autoUpdate"), "'autoUpdate' is dropped in Shard: ");
        contains(run("config nope"), "Unknown config: 'nope'.");
        contains(run("config name Other"), "set server.name there");
        assertEquals("Config reloaded: nothing changed.\n", run("config reload"));
    }

    @Test
    void runwaveSpawnsTheNextWave() throws Exception{
        int before = world("maze").inspect(w -> WaveState.of(w).wave).join();
        assertEquals("Wave spawned in maze.\n", run("runwave maze"));
        await("the next wave", () -> world("maze").inspect(w -> WaveState.of(w).wave).join() == before + 1);
        contains(run("runwave nowhere"), "no world nowhere");
    }

    @Test
    void fillitemsFillsTheFirstCore() throws Exception{
        assertEquals("Core filled.\n", run("fillitems maze"));
        await("the core to be full", () -> world("maze").inspect(w -> {
            Core core = Teams.of(w).cores(Team.SHARDED).getFirst();
            for(int item = 0; item < w.content().items(); item++) if(core.items.get(item) != core.storageCapacity) return false;
            return true;
        }).join());
        assertEquals("No team with that name found.\n", run("fillitems maze nosuch"));
        boolean malis = world("maze").inspect(w -> Teams.of(w).cores(Team.MALIS).isEmpty()).join();
        assertEquals(malis ? "That team has no cores.\n" : "Core filled.\n", run("fillitems maze malis"));
    }

    @Test
    void nextmapAndShuffleAreRotationSettings(){
        assertEquals("Next map of maze set to 'Fortress'.\n", run("nextmap maze Fortress"));
        assertEquals("No map 'Nowhere' found.\n", run("nextmap Nowhere"));
        assertEquals("Shuffle mode of fortress set to 'none'.\n", run("shuffle fortress none"));
        assertEquals("Shuffle mode of fortress currently set to 'none'.\n", run("shuffle fortress"));
        assertEquals("Invalid shuffle mode.\n", run("shuffle fortress sideways"));
    }

    @Test
    void gameoverEndsAWorldsGame() throws Exception{
        server.host().host(server.host().spec("extra=labyrinth")).get(Hang.SECONDS, TimeUnit.SECONDS);
        try{
            assertEquals("Core destroyed: extra.\n", run("gameover extra"));
        }finally{
            server.host().stop(server.host().world("extra").orElseThrow().id(), KickReason.SERVER_CLOSE).get(Hang.SECONDS, TimeUnit.SECONDS);
        }
    }

    @Test
    void gcReportsMemory(){
        assertTrue(run("gc").matches("-?\\d+ MB collected\\. Memory usage now at \\d+ MB\\.\n"));
    }

    // ---- moderation

    @Test
    void kickKicksByNameWithACooldown() throws Exception{
        var alpha = join("alpha", "127.0.0.2");
        int before = server.admin().kicks(uuid("alpha")).join().size();
        assertEquals("It is done.\n", run("kick alpha"));
        assertEquals(KickReason.KICK.id(), alpha.kickReason());
        await("the kick", () -> server.admin().kicks(uuid("alpha")).join().size() == before + 1);
        var kick = server.admin().kicks(uuid("alpha")).join().getLast();
        assertEquals(List.of("kick", "console", "127.0.0.2"), List.of(kick.reason(), kick.createdBy(), kick.address()));
        assertEquals("Nobody with that name could be found...\n", run("kick alpha"));
    }

    @Test
    void banById() throws Exception{
        var alpha = join("alpha", "127.0.0.2");
        int before = server.admin().kicks(uuid("alpha")).join().size();
        assertEquals("Banned.\n", run("ban id " + uuid("alpha")));
        assertEquals(KickReason.BANNED.id(), alpha.kickReason());
        assertEquals(List.of(uuid("alpha")), bans().stream().map(Bans.Ban::target).toList());
        assertEquals("console", bans().getFirst().createdBy());
        await("the ban's kick", () -> server.admin().kicks(uuid("alpha")).join().size() == before + 1);
    }

    @Test
    void banByNameBansTheUuidAndTheAddress() throws Exception{
        join("alpha", "127.0.0.2");
        var beta = join("beta", "127.0.0.3");
        assertEquals("Banned.\n", run("ban name BETA"));
        assertEquals(KickReason.BANNED.id(), beta.kickReason());
        assertEquals(List.of(uuid("beta"), "127.0.0.3"), bans().stream().map(Bans.Ban::target).toList());
        assertNotNull(session("alpha"), "alpha stays");
        assertEquals("No matches found.\n", run("ban name nobody"));
        assertEquals("Invalid type.\n", run("ban who x"));
    }

    @Test
    void banByAddressKicksEveryoneThere() throws Exception{
        var alpha = join("alpha", "127.0.0.2");
        assertEquals("Banned.\n", run("ban ip 127.0.0.2"));
        assertEquals(KickReason.BANNED.id(), alpha.kickReason());
        assertEquals(List.of(Bans.Kind.ADDRESS), bans().stream().map(Bans.Ban::kind).toList());
    }

    @Test
    void bansListsIdsThenAddresses() throws Exception{
        join("alpha", "127.0.0.2");
        assertEquals("No ID-banned players have been found.\nNo IP-banned players have been found.\n", run("bans"));
        run("ban name alpha");
        run("ban ip 4.4.4.4");
        run("subnet-ban add 10.0.0.0/8");
        String text = run("bans");
        contains(text, "Banned players [ID]:\n " + uuid("alpha") + " / Last known name: 'alpha' (#",
            "Banned players [IP]:\n  '127.0.0.2' / Last known name: 'alpha' / ID: '" + uuid("alpha") + "' (#",
            "  '4.4.4.4' (No known name or info) (#", "Banned subnets:\n  10.0.0.0/8 (#");
    }

    @Test
    void unbanTakesAnAddressOrAnId() throws Exception{
        join("alpha", "127.0.0.2");
        run("ban name alpha");
        assertEquals("Unbanned player: " + uuid("alpha") + "\n", run("unban " + uuid("alpha")));
        assertTrue(bans().isEmpty(), "a UUID unban lifts the player's address ban too, as vanilla's");
        run("ban ip 4.4.4.4");
        assertEquals("Unbanned player: 4.4.4.4\n", run("unban 4.4.4.4"));
        assertTrue(bans().isEmpty());
        assertEquals("That IP/ID is not banned!\n", run("unban 4.4.4.4"));
    }

    @Test
    void pardonEndsAKickCooldown() throws Exception{
        join("alpha", "127.0.0.2");
        int before = server.admin().kicks(uuid("alpha")).join().size();
        run("kick alpha");
        await("the kick", () -> server.admin().kicks(uuid("alpha")).join().size() == before + 1);
        assertTrue(server.admin().kickedUntil(uuid("alpha"), "127.0.0.2") > System.currentTimeMillis());
        assertEquals("Pardoned player: alpha\n", run("pardon " + uuid("alpha")));
        assertEquals(0, server.admin().kickedUntil(uuid("alpha"), "127.0.0.2"));
        assertEquals("console", server.admin().kicks(uuid("alpha")).join().getLast().pardonedBy());
        assertEquals("That ID can't be found.\n", run("pardon nobody"));
        join("alpha", "127.0.0.2");
    }

    @Test
    void adminAddsAndRemovesOnlineAndKnownPlayers() throws Exception{
        join("alpha", "127.0.0.2");
        assertEquals("Changed admin status of player: alpha\n", run("admin add [red]ALPHA"));
        assertTrue(session("alpha").admin());
        assertTrue(server.admin().isAdmin(uuid("alpha"), session("alpha").identity().usid()), "pinned to the live USID");
        long alpha = session("alpha").id();
        await("alpha's player to be an admin", () -> world("maze").inspect(w -> Roster.of(w).player(alpha).admin).join());

        assertEquals("Changed admin status of player: alpha\n", run("admin remove " + uuid("alpha")));
        assertFalse(session("alpha").admin(), "found by UUID, still online");
        assertTrue(server.admin().admins().join().isEmpty());
        await("alpha's player not to be an admin", () -> !world("maze").inspect(w -> Roster.of(w).player(alpha).admin).join());

        clients.getFirst().close();
        await("alpha to leave", () -> session("alpha") == null);
        assertEquals("Changed admin status of player: alpha\n", run("admin add " + uuid("alpha")));
        assertEquals(List.of(uuid("alpha")), server.admin().admins().join().stream().map(g -> g.player().uuid()).toList(), "offline, by UUID");

        assertEquals("Nobody with that name or ID could be found. If adding an admin by name, make sure they're online; otherwise, use their UUID.\n",
            run("admin add nobody"));
        assertEquals("Second parameter must be either 'add' or 'remove'.\n", run("admin make alpha"));
    }

    @Test
    void adminsListsThem() throws Exception{
        assertEquals("No admins have been found.\n", run("admins"));
        join("alpha", "127.0.0.2");
        run("admin add alpha");
        assertEquals("Admins:\n  alpha /  ID: '" + uuid("alpha") + "' / IP: '127.0.0.2'\n", run("admins"));
    }

    @Test
    void whitelistAddsKnownIds() throws Exception{
        assertEquals("No whitelisted players found.\n", run("whitelist"));
        assertEquals("Player ID not found. You must use the ID displayed when a player joins a server.\n", run("whitelist add nobody"));
        join("alpha", "127.0.0.2");
        assertEquals("Player 'alpha' has been whitelisted.\n", run("whitelist add " + uuid("alpha")));
        assertEquals("Whitelist:\n- Name: alpha / UUID: " + uuid("alpha") + "\n", run("whitelist"));
        assertEquals(List.of(uuid("alpha")), server.admin().whitelisted().join().stream().map(g -> g.player().uuid()).toList());
        assertEquals("Player 'alpha' has been un-whitelisted.\n", run("whitelist remove " + uuid("alpha")));
        assertTrue(server.admin().whitelisted().join().isEmpty());
        assertEquals("Incorrect usage. Provide an ID to add or remove.\n", run("whitelist add"));
        assertEquals("Incorrect usage. Provide add/remove as the second argument.\n", run("whitelist drop " + uuid("alpha")));
    }

    @Test
    void subnetBanTakesCidrBlocksAndVanillaPrefixes(){
        assertEquals("Subnets banned: <none>\n", run("subnet-ban"));
        assertEquals("Banned 10.0.0.0/8\n", run("subnet-ban add 10.0.0.0/8"));
        assertEquals("That subnet is already banned.\n", run("subnet-ban add 10.0.0.0/8"));
        assertEquals("Banned 192.168.0.0/16\n", run("subnet-ban add 192.168."));
        assertEquals("Subnets banned: \n\t10.0.0.0/8\n\t192.168.0.0/16\n", run("subnet-ban"));
        assertEquals(List.of("10.0.0.0/8", "192.168.0.0/16"), bans().stream().map(Bans.Ban::target).toList());
        assertInstanceOf(AdminService.Decision.Ignore.class, server.admin().admit(new AdminService.Connect("x", "y", "n", "192.168.4.5", 0)));
        assertEquals("Unbanned 10.0.0.0/8\n", run("subnet-ban remove 10.0.0.0/8"));
        assertEquals("That subnet isn't banned.\n", run("subnet-ban remove 10.0.0.0/8"));
        assertEquals("You must provide a subnet to add or remove.\n", run("subnet-ban add"));
        assertEquals("Incorrect usage. Provide add/remove as the second argument.\n", run("subnet-ban drop 10.0.0.0/8"));
        contains(run("subnet-ban add 1.2.3"), "expected a CIDR block");
    }

    @Test
    void nameBanAddsRemovesAndClears() throws Exception{
        assertEquals("Name regexes banned: <none>\n", run("name-ban"));
        assertEquals("Banned names by regex: gr[i1]ef\n", run("name-ban add gr[i1]ef"));
        assertEquals("That name regex is already banned.\n", run("name-ban add gr[i1]ef"));
        assertTrue(run("name-ban add (").startsWith("Invalid regex: "));
        assertEquals("Name regexes banned: \n\tgr[i1]ef\n", run("name-ban"));
        assertEquals(KickReason.BANNED.id(), kicked("Griefer", "127.0.0.4"));
        assertEquals("Unbanned regex: gr[i1]ef\n", run("name-ban remove gr[i1]ef"));
        assertEquals("That name isn't banned.\n", run("name-ban remove gr[i1]ef"));
        run("name-ban add a");
        assertEquals("Name regexes unbanned: 1\n", run("name-ban clear"));
        assertTrue(server.admin().nameBans().join().isEmpty());
        assertEquals("You must provide a name regex to add or remove.\n", run("name-ban add"));
    }

    /** The kick reason a connect as {@code name} from {@code local} earns. */
    int kicked(String name, String local) throws Exception{
        try(var client = new TestClient(server.transport().address(), InetAddress.getByName(local))){
            client.connect(name);
            return client.kickReason();
        }
    }

    /** W3.13's request: packet spam and chat spam DOS-ban the address, as vanilla's blacklist does, and nobody else's. */
    @Test
    void packetAndChatSpamDosBanTheAddress() throws Exception{
        TestClient spammer = join("spammer", "127.0.0.71");
        try{
            for(int i = 0; i < 400; i++) spammer.tcp(com.xpdustry.shard.wire.v160_7.Packets.PING, new byte[8]);
        }catch(IOException reset){
            // closed while it wrote
        }
        await("the packet spammer's DOS ban", () -> server.admin().dosBans().contains("127.0.0.71"));
        await("the packet spammer to leave", () -> session("spammer") == null);

        TestClient talker = join("talker", "127.0.0.72");
        try{
            for(int i = 0; i < 40; i++) talker.chat("spam " + i);
        }catch(IOException reset){
            // closed while it wrote
        }
        await("the chat spammer's DOS ban", () -> server.admin().dosBans().contains("127.0.0.72"));
        assertEquals(List.of("127.0.0.71", "127.0.0.72"), server.admin().dosBans());
        assertInstanceOf(AdminService.Decision.Ignore.class, server.admin().admit(new AdminService.Connect("x", "y", "n", "127.0.0.71", 0)),
            "the address is refused from now on");
    }

    @Test
    void dosBanIsAnInMemoryList(){
        assertEquals("DOS bans: <none>\n", run("dos-ban"));
        assertEquals("Dos banned: 5.5.5.5\n", run("dos-ban add 5.5.5.5"));
        assertEquals("DOS bans: \n\t5.5.5.5\n", run("dos-ban"));
        assertInstanceOf(AdminService.Decision.Ignore.class, server.admin().admit(new AdminService.Connect("x", "y", "n", "5.5.5.5", 0)));
        assertEquals("Removed dos ban: 5.5.5.5\n", run("dos-ban remove 5.5.5.5"));
        assertTrue(server.admin().dosBans().isEmpty());
        assertEquals("Expected either zero or two parameters, but only got one parameter.\n", run("dos-ban add"));
        assertEquals("Unrecognized action: drop\n", run("dos-ban drop 5.5.5.5"));
    }

    @Test
    void infoShowsEveryNameAndAddress() throws Exception{
        join("alpha", "127.0.0.2");
        var stored = server.admin().player(uuid("alpha")).join().orElseThrow().player();
        String text = run("info alpha");
        contains(text, "Players found: 1\n[0] Trace info for player 'alpha' / UUID " + uuid("alpha") + " / RAW alpha\n",
            "  all names used: [alpha]\n  IP: 127.0.0.2\n  all IPs used: [127.0.0.2]\n  times joined: " + stored.timesJoined()
                + "\n  times kicked: " + stored.timesKicked() + "\n  last seen: ");
        assertTrue(run("info 127.0.0.2").startsWith("Players found: "), "by address too");
        assertEquals("Nobody with that name could be found.\n", run("info nobody"));
    }

    @Test
    void searchFindsPartsOfNames() throws Exception{
        join("alphabet", "127.0.0.2");
        assertEquals("Players found: 1\n- [0] 'alphabet' / " + uuid("alphabet") + "\n", run("search PHAB"));
        assertEquals("Nobody with that name could be found.\n", run("search zzz"));
    }
}
