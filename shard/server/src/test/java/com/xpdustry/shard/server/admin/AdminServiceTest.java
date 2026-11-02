package com.xpdustry.shard.server.admin;

import com.xpdustry.shard.server.Hang;
import com.xpdustry.shard.server.admin.AdminService.*;
import com.xpdustry.shard.server.config.*;
import com.xpdustry.shard.server.store.*;
import com.xpdustry.shard.server.store.Bans.*;
import com.xpdustry.shard.server.store.StoreFixture.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static com.xpdustry.shard.server.ServerTest.await;
import static org.junit.jupiter.api.Assertions.*;

class AdminServiceTest{
    @TempDir
    Path dir;

    final ManualClock clock = new ManualClock(1_800_000_000_000L);
    final AtomicReference<Config> config = new AtomicReference<>(Config.defaults());
    Store store;
    AdminService admin;

    @BeforeEach
    void open(){
        store = StoreFixture.open(dir, clock);
        admin = AdminService.start(store, config::get, clock);
    }

    @AfterEach
    void close(){
        store.close();
    }

    void restart(){
        store.close();
        open();
    }

    void configure(Map<String, String> env) throws ConfigException{
        config.set(ConfigLoader.load(null, null, env));
    }

    static Connect connect(String uuid, String address){
        return new Connect(uuid, "usid-" + uuid, "name " + uuid, address, 0);
    }

    Decision admit(String uuid, String address){
        return admin.admit(connect(uuid, address));
    }

    static void assertAdmitted(Decision decision){
        assertAdmitted(decision, "");
    }

    static void assertAdmitted(Decision decision, String why){
        assertInstanceOf(Decision.Admit.class, decision, why + ": " + decision);
    }

    static void assertKicked(KickReason reason, Decision decision){
        assertKicked(reason, decision, "");
    }

    static void assertKicked(KickReason reason, Decision decision, String why){
        var kick = assertInstanceOf(Decision.Kick.class, decision, why + ": " + decision);
        assertEquals(reason, kick.reason(), why + ": " + decision);
    }

    static void assertIgnored(Decision decision){
        assertInstanceOf(Decision.Ignore.class, decision, decision.toString());
    }

    void join(String uuid, String address) throws Exception{
        admin.join(connect(uuid, address), new Client("en", 146, false, false), "alpha").get(Hang.SECONDS, TimeUnit.SECONDS);
    }

    static <T> T get(CompletableFuture<T> future) throws Exception{
        return future.get(Hang.SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void uuidBans() throws Exception{
        var ban = get(admin.ban(Kind.UUID, "aaaa", "griefing", null, "console"));
        assertKicked(KickReason.BANNED, admit("aaaa", "1.2.3.4"));
        assertAdmitted(admit("bbbb", "1.2.3.4"));
        assertEquals("griefing", ban.reason());
        assertEquals("console", ban.createdBy());
        assertNotNull(ban.playerId(), "a UUID ban creates the player record, as vanilla's getCreateInfo");
        assertNull(ban.expiresAt());
    }

    @Test
    void addressBansDropTheConnectionSilently() throws Exception{
        get(admin.ban(Kind.ADDRESS, "1.2.3.4", "", null, "console"));
        assertIgnored(admit("aaaa", "1.2.3.4"));
        assertIgnored(admit("aaaa", "::ffff:1.2.3.4"));
        assertAdmitted(admit("aaaa", "1.2.3.5"));

        get(admin.ban(Kind.ADDRESS, "2001:DB8:0:0::1", "", null, "console"));
        assertIgnored(admit("cccc", "2001:db8::1"));
        assertEquals("2001:db8::1", get(admin.activeBans()).get(1).target(), "stored canonical");
    }

    /** Unlike vanilla, an address ban doesn't ban every player who ever used that address. */
    @Test
    void anAddressBanBansTheAddressNotItsHistory() throws Exception{
        join("aaaa", "1.2.3.4");
        join("bbbb", "1.2.3.4");
        get(admin.ban(Kind.ADDRESS, "1.2.3.4", "", null, "console"));
        assertAdmitted(admit("bbbb", "5.6.7.8"));
        assertIgnored(admit("bbbb", "1.2.3.4"));
    }

    @Test
    void subnetBansAreCidrBlocks() throws Exception{
        get(admin.ban(Kind.SUBNET, "10.0.0.0/8", "", null, "console"));
        get(admin.ban(Kind.SUBNET, "2001:db8::/32", "", null, "console"));
        assertIgnored(admit("aaaa", "10.0.0.1"));
        assertIgnored(admit("aaaa", "10.255.255.255"));
        assertAdmitted(admit("aaaa", "11.0.0.1"));
        assertAdmitted(admit("aaaa", "1.10.0.1"), "a CIDR block, not vanilla's string prefix");
        assertIgnored(admit("aaaa", "2001:db8:1234::5"));
        assertAdmitted(admit("aaaa", "2001:db9::5"));

        // vanilla's prefix form and host bits are refused, with a hint
        var prefix = assertThrows(ExecutionException.class, () -> get(admin.ban(Kind.SUBNET, "192.168.", "", null, "console")));
        assertTrue(prefix.getCause().getMessage().contains("vanilla's prefix form"), prefix.getCause().getMessage());
        var hostBits = assertThrows(ExecutionException.class, () -> get(admin.ban(Kind.SUBNET, "10.1.0.0/8", "", null, "console")));
        assertTrue(hostBits.getCause().getMessage().contains("the block is 10.0.0.0/8"), hostBits.getCause().getMessage());
        assertEquals(2, get(admin.activeBans()).size());
    }

    @Test
    void bansExpireWithoutARebuild() throws Exception{
        get(admin.ban(Kind.UUID, "aaaa", "cool off", Duration.ofHours(1), "console"));
        get(admin.ban(Kind.SUBNET, "10.0.0.0/8", "", Duration.ofMinutes(30), "console"));
        long version = admin.index().version();
        assertKicked(KickReason.BANNED, admit("aaaa", "1.2.3.4"));
        assertIgnored(admit("bbbb", "10.1.1.1"));

        clock.advance(Duration.ofMinutes(30));
        assertAdmitted(admit("bbbb", "10.1.1.1"));
        assertKicked(KickReason.BANNED, admit("aaaa", "1.2.3.4"));

        clock.advance(Duration.ofMinutes(30));
        assertAdmitted(admit("aaaa", "1.2.3.4"));
        assertEquals(version, admin.index().version(), "expiry needs no new index");
        assertTrue(get(admin.activeBans()).isEmpty());

        var refused = assertThrows(ExecutionException.class, () -> get(admin.ban(Kind.UUID, "x", "", Duration.ZERO, "console")));
        assertInstanceOf(IllegalArgumentException.class, refused.getCause());
    }

    @Test
    void theLongestBanWins() throws Exception{
        var permanent = get(admin.ban(Kind.UUID, "aaaa", "", null, "console"));
        get(admin.ban(Kind.UUID, "aaaa", "", Duration.ofHours(1), "console"));
        assertNull(admin.index().uuidBan("aaaa", clock.millis()).orElseThrow().expiresAt());
        assertTrue(get(admin.revoke(permanent.id(), "console")));
        assertFalse(get(admin.revoke(permanent.id(), "console")), "already revoked");
        assertKicked(KickReason.BANNED, admit("aaaa", "1.2.3.4"));
        clock.advance(Duration.ofHours(1));
        assertAdmitted(admit("aaaa", "1.2.3.4"));
    }

    @Test
    void unbanKeepsTheHistory() throws Exception{
        get(admin.ban(Kind.ADDRESS, "1.2.3.4", "spam", null, "console"));
        assertEquals(1, get(admin.unban(Kind.ADDRESS, "1.2.3.4", "api")));
        assertEquals(0, get(admin.unban(Kind.ADDRESS, "1.2.3.4", "api")));
        assertAdmitted(admit("aaaa", "1.2.3.4"));
        var history = get(store.read(c -> Bans.forTarget(c, Kind.ADDRESS, "1.2.3.4")));
        assertEquals(1, history.size());
        assertEquals("api", history.getFirst().revokedBy());
        assertEquals(clock.millis(), history.getFirst().revokedAt());
        assertEquals("spam", history.getFirst().reason());
    }

    @Test
    void banningAPlayerAlsoBansTheirAddressAndUnbanningLiftsBoth() throws Exception{
        join("aaaa", "1.2.3.4");
        var bans = get(admin.banPlayer("aaaa", true, "x", null, "player:admin"));
        assertEquals(List.of(Kind.UUID, Kind.ADDRESS), bans.stream().map(Ban::kind).toList());
        assertKicked(KickReason.BANNED, admit("aaaa", "9.9.9.9"));
        assertIgnored(admit("other", "1.2.3.4"));
        assertEquals(2, get(admin.unbanPlayer("aaaa", "console")));
        assertAdmitted(admit("aaaa", "1.2.3.4"));
    }

    @Test
    void aBanIsDurableAndInForceWhenItCompletes() throws Exception{
        long before = admin.index().version();
        get(admin.ban(Kind.UUID, "aaaa", "", null, "console"));
        assertTrue(admin.index().version() > before);
        assertFalse(admin.isCurrent(before), "an admission decided before the ban must be re-checked");
        assertKicked(KickReason.BANNED, admit("aaaa", "1.2.3.4"), "in force with no wait");
        restart();
        assertKicked(KickReason.BANNED, admit("aaaa", "1.2.3.4"), "and after a restart");
    }

    @Test
    void concurrentBansAllLand() throws Exception{
        var futures = new ArrayList<CompletableFuture<Ban>>();
        for(int i = 0; i < 200; i++) futures.add(admin.ban(Kind.UUID, "u" + i, "", null, "console"));
        for(var future : futures) get(future);
        // one address each: 200 UUIDs from one address would trip admin.uuid-change-limit
        for(int i = 0; i < 200; i++) assertKicked(KickReason.BANNED, admit("u" + i, "1.2.3." + i));
        assertEquals(200, admin.index().uuidBans().size(), "the newest index wins, whatever order futures complete in");
    }

    @Test
    void kickCooldowns() throws Exception{
        join("aaaa", "1.2.3.4");
        assertTrue(get(admin.kicked("aaaa", "1.2.3.4", KickReason.KICK)));
        assertKicked(KickReason.RECENT_KICK, admit("aaaa", "1.2.3.4"));
        assertKicked(KickReason.RECENT_KICK, admit("other", "1.2.3.4"), "the address waits too");
        assertKicked(KickReason.RECENT_KICK, admit("aaaa", "5.5.5.5"), "and the UUID");
        clock.advance(Duration.ofSeconds(30));
        assertAdmitted(admit("aaaa", "1.2.3.4"));

        assertFalse(get(admin.kicked("aaaa", "1.2.3.4", KickReason.GAMEOVER)));
        assertAdmitted(admit("aaaa", "1.2.3.4"), "only kick, ban and vote have a cooldown");

        assertTrue(get(admin.kicked("aaaa", "1.2.3.4", Duration.ofMinutes(2))));
        assertTrue(admin.pardon("aaaa"));
        assertAdmitted(admit("aaaa", "1.2.3.4"));
        assertFalse(admin.pardon("aaaa"));

        var player = get(admin.player("aaaa")).orElseThrow().player();
        assertEquals(2, player.timesKicked(), "zero-cooldown kicks aren't counted, as in vanilla");
    }

    @Test
    void theCheckOrderIsVanillas() throws Exception{
        get(admin.ban(Kind.UUID, "aaaa", "", null, "console"));
        admin.kicked("aaaa", "1.2.3.4", KickReason.KICK);
        assertKicked(KickReason.BANNED, admit("aaaa", "1.2.3.4"), "bans before cooldowns");
        assertKicked(KickReason.ID_IN_USE, admin.admit(new Connect("aaaa", null, "n", "1.2.3.4", 0)), "a missing USID before bans");
        get(admin.ban(Kind.ADDRESS, "1.2.3.4", "", null, "console"));
        assertIgnored(admin.admit(new Connect(null, null, "n", "1.2.3.4", 0)));
        assertIgnored(admin.admit(new Connect("x", "y", "n", "not an address", 0)));
    }

    @Test
    void adminsPassThePlayerLimitAndArePinnedToTheirUsid() throws Exception{
        configure(Map.of("SHARD__NET__PLAYER_LIMIT", "2"));
        var failed = assertThrows(ExecutionException.class, () -> get(admin.grantAdmin("aaaa", null, "console")));
        assertTrue(failed.getCause().getMessage().contains("no player"), failed.getCause().getMessage());

        join("aaaa", "1.2.3.4");
        assertTrue(get(admin.grantAdmin("aaaa", null, "console")));
        assertFalse(get(admin.grantAdmin("aaaa", null, "console")), "already an admin");
        assertTrue(admin.isAdmin("aaaa", "usid-aaaa"));
        assertFalse(admin.isAdmin("aaaa", "another usid"), "vanilla checks the USID too");

        var full = new Connect("aaaa", "usid-aaaa", "n", "1.2.3.4", 2);
        var decision = assertInstanceOf(Decision.Admit.class, admin.admit(full));
        assertTrue(decision.admin());
        assertKicked(KickReason.PLAYER_LIMIT, admin.admit(new Connect("aaaa", "stolen", "n", "1.2.3.4", 2)));
        assertKicked(KickReason.PLAYER_LIMIT, admin.admit(new Connect("bbbb", "usid-bbbb", "n", "1.2.3.4", 2)));
        assertAdmitted(admin.admit(new Connect("bbbb", "usid-bbbb", "n", "1.2.3.4", 1)));

        assertEquals(List.of("aaaa"), get(admin.admins()).stream().map(g -> g.player().uuid()).toList());
        assertTrue(get(admin.revokeAdmin("aaaa")));
        assertFalse(admin.isAdmin("aaaa", "usid-aaaa"));
        restart();
        assertFalse(admin.isAdmin("aaaa", "usid-aaaa"));
    }

    @Test
    void theWhitelistRecordsWhoItTurnsAway() throws Exception{
        configure(Map.of("SHARD__ADMIN__WHITELIST", "true"));
        assertKicked(KickReason.WHITELIST, admit("aaaa", "1.2.3.4"));
        // the rejected identity is recorded (without counting a join), so it can be whitelisted by UUID
        await("the rejection to be recorded", () -> admin.player("aaaa").join().isPresent());
        assertTrue(get(admin.whitelist("aaaa", "console")));
        assertEquals(0, get(admin.player("aaaa")).orElseThrow().player().timesJoined());
        assertAdmitted(admit("aaaa", "1.2.3.4"));
        assertKicked(KickReason.WHITELIST, admin.admit(new Connect("aaaa", "other usid", "n", "1.2.3.4", 0)), "keyed by UUID and USID");
        assertTrue(get(admin.unwhitelist("aaaa")));
        assertKicked(KickReason.WHITELIST, admit("aaaa", "1.2.3.4"));
    }

    @Test
    void tooManyUuidsFromOneAddressBansIt() throws Exception{
        configure(Map.of("SHARD__ADMIN__UUID_CHANGE_LIMIT", "3"));
        for(int i = 0; i < 3; i++) assertAdmitted(admit("u" + i, "7.7.7.7"));
        assertAdmitted(admit("u0", "7.7.7.7"), "a UUID seen before doesn't count again");
        assertKicked(KickReason.BANNED, admit("u3", "7.7.7.7"));
        await("the address ban", () -> admin.index().addressBan(Addresses.parse("7.7.7.7"), clock.millis()).isPresent());
        assertIgnored(admit("u0", "7.7.7.7"));
        var ban = get(admin.activeBans()).getFirst();
        assertEquals("system", ban.createdBy());
        assertEquals("more than 3 UUIDs in 3h", ban.reason());
        assertAdmitted(admit("u9", "7.7.7.8"));
    }

    @Test
    void theUuidWindowResets() throws Exception{
        configure(Map.of("SHARD__ADMIN__UUID_CHANGE_LIMIT", "2", "SHARD__ADMIN__UUID_CHANGE_PERIOD", "1h"));
        assertAdmitted(admit("u0", "7.7.7.7"));
        assertAdmitted(admit("u1", "7.7.7.7"));
        clock.advance(Duration.ofHours(1));
        assertAdmitted(admit("u2", "7.7.7.7"));
        assertAdmitted(admit("u3", "7.7.7.7"));
    }

    @Test
    void lookupsAndTraces() throws Exception{
        admin.join(new Connect("aaaa", "usid", "[red]Foo[]Bar", "1.2.3.4", 0), new Client("fr", 146, true, false), "alpha").get(Hang.SECONDS, TimeUnit.SECONDS);
        clock.advance(Duration.ofMinutes(1));
        var second = admin.join(new Connect("aaaa", "usid", "Renamed", "::ffff:5.6.7.8", 0), new Client("fr", 146, true, false), "beta").get(Hang.SECONDS, TimeUnit.SECONDS);
        clock.advance(Duration.ofMinutes(1));
        admin.join(new Connect("bbbb", "usid2", "someone", "1.2.3.4", 0), new Client("en", 146, false, false), "alpha").get(Hang.SECONDS, TimeUnit.SECONDS);
        assertEquals("5.6.7.8", second.session().address(), "addresses are stored canonical");

        assertEquals(List.of("aaaa"), uuids(get(admin.search("foo"))), "colors are ignored");
        assertEquals(List.of("aaaa"), uuids(get(admin.search("OBA"))), "case too");
        assertTrue(get(admin.search("%")).isEmpty(), "LIKE wildcards are literal");
        assertEquals(List.of("bbbb", "aaaa"), get(admin.info("1.2.3.4")).stream().map(d -> d.player().uuid()).toList(), "most recent first");
        assertEquals(List.of("aaaa"), get(admin.info("[red]Foo[]Bar")).stream().map(d -> d.player().uuid()).toList());
        assertEquals(List.of("aaaa"), get(admin.info("foobar")).stream().map(d -> d.player().uuid()).toList());
        assertEquals(List.of("aaaa"), get(admin.info("aaaa")).stream().map(d -> d.player().uuid()).toList());

        var detail = get(admin.player("aaaa")).orElseThrow();
        assertEquals(List.of("[red]Foo[]Bar", "Renamed"), detail.names());
        assertEquals(List.of("1.2.3.4", "5.6.7.8"), detail.addresses());
        assertEquals(2, detail.player().timesJoined());
        assertEquals("Renamed", detail.player().lastName());
        assertEquals(AdminService.usidHash("usid"), detail.player().usidHash());

        var trace = get(admin.trace("aaaa", new Live("5.6.7.8", "fr", false, true)));
        assertEquals(new TraceInfo("5.6.7.8", "aaaa", "fr", false, true, 2, 0, List.of("1.2.3.4", "5.6.7.8"), List.of("[red]Foo[]Bar", "Renamed")), trace);

        assertTrue(get(admin.left(second.session().id(), "disconnected")));
        assertFalse(get(admin.left(second.session().id(), "disconnected")));
    }

    @Test
    void notes() throws Exception{
        get(admin.addNote("aaaa", "console", "warned for spam"));
        clock.advance(Duration.ofSeconds(1));
        get(admin.addNote("aaaa", "player:mod", "second warning"));
        assertEquals(List.of("warned for spam", "second warning"), get(admin.notes("aaaa")).stream().map(Notes.Note::body).toList());
        assertTrue(get(admin.notes("nobody")).isEmpty());
        assertThrows(ExecutionException.class, () -> get(admin.addNote("aaaa", "console", "")));
    }

    @Test
    void kicksAreStoredWithTheirReasonAndOutliveARestart() throws Exception{
        join("aaaa", "1.2.3.4");
        assertTrue(get(admin.kicked("aaaa", "1.2.3.4", Duration.ofMinutes(10), "You have been kicked for spamming.", "system")));
        assertTrue(get(admin.kicked("aaaa", "1.2.3.4", KickReason.VOTE, "player:bbbb")));
        var kicks = get(admin.kicks("aaaa"));
        assertEquals(List.of("You have been kicked for spamming.", "vote"), kicks.stream().map(Kicks.Kick::reason).toList());
        assertEquals(List.of("system", "player:bbbb"), kicks.stream().map(Kicks.Kick::createdBy).toList());
        assertEquals(clock.millis() + Duration.ofMinutes(10).toMillis(), kicks.getFirst().expiresAt());
        assertEquals(2, get(admin.player("aaaa")).orElseThrow().player().timesKicked());

        restart();
        assertKicked(KickReason.RECENT_KICK, admit("aaaa", "9.9.9.9"), "vanilla saved lastKicked; so does Shard");
        assertKicked(KickReason.RECENT_KICK, admit("other", "1.2.3.4"), "and ip-kicks");

        assertTrue(get(admin.pardonPlayer("aaaa", "console")).isPresent());
        assertAdmitted(admit("aaaa", "1.2.3.4"));
        assertTrue(get(admin.kicks("aaaa")).stream().allMatch(k -> "console".equals(k.pardonedBy()) && k.pardonedAt() == clock.millis()));
        restart();
        assertAdmitted(admit("aaaa", "1.2.3.4"), "a pardon is stored too");
        assertTrue(get(admin.pardonPlayer("nobody", "console")).isEmpty(), "That ID can't be found.");

        clock.advance(Duration.ofMinutes(11));
        assertTrue(get(store.read(c -> Kicks.active(c, clock.millis()))).isEmpty());
    }

    @Test
    void nameBansMatchAnywhereIgnoringCase() throws Exception{
        assertTrue(get(admin.banName("gr[i1]ef", "console")));
        assertFalse(get(admin.banName("gr[i1]ef", "console")), "already banned");
        var invalid = assertThrows(ExecutionException.class, () -> get(admin.banName("(", "console")));
        assertTrue(invalid.getCause().getMessage().startsWith("Invalid regex: "), invalid.getCause().getMessage());

        assertKicked(KickReason.BANNED, admin.admit(new Connect("aaaa", "u", "[red]GR1EFer", "1.2.3.4", 0)), "find(), not matches(), on the raw name");
        assertAdmitted(admin.admit(new Connect("aaaa", "u", "builder", "1.2.3.4", 0)));
        restart();
        assertKicked(KickReason.BANNED, admin.admit(new Connect("aaaa", "u", "griefer", "1.2.3.4", 0)));
        assertEquals(List.of("gr[i1]ef"), get(admin.nameBans()).stream().map(NameBans.NameBan::pattern).toList());

        assertTrue(get(admin.unbanName("gr[i1]ef")));
        assertFalse(get(admin.unbanName("gr[i1]ef")));
        assertAdmitted(admin.admit(new Connect("aaaa", "u", "griefer", "1.2.3.4", 0)));
        get(admin.banName("a", "console"));
        get(admin.banName("b", "console"));
        assertEquals(2, get(admin.clearNameBans()));
        assertTrue(admin.index().nameBans().isEmpty());
    }

    /** Vanilla runs a name ban's regex unbounded on the connect thread; here a backtracking one stops early and refuses. */
    @Test
    void aBacktrackingNameBanIsBounded() throws Exception{
        // the JDK memoizes simple nested loops such as (a|aa)+; a counted group of .* it can't
        get(admin.banName("(.*a){40}", "console"));
        var name = "a".repeat(39) + "!";
        // the bound is in steps: "out of steps counts as a match" is the kick. Unbounded, the regex would not end, so a
        // failure detector (and not a 1 s budget, which a loaded machine can spend) tells the two apart
        assertTimeoutPreemptively(Hang.TIMEOUT,
            () -> assertKicked(KickReason.BANNED, admin.admit(new Connect("aaaa", "u", name, "1.2.3.4", 0)), "out of steps counts as a match"));
        assertAdmitted(admin.admit(new Connect("aaaa", "u", "bbbb", "1.2.3.4", 0)));
    }

    @Test
    void nameBansComeAfterUuidBansAndBeforeCooldowns() throws Exception{
        get(admin.banName("bad", "console"));
        get(admin.ban(Kind.UUID, "aaaa", "uuid", null, "console"));
        var byUuid = assertInstanceOf(Decision.Kick.class, admin.admit(new Connect("aaaa", "u", "bad", "1.2.3.4", 0)));
        assertTrue(byUuid.detail().startsWith("UUID banned"), byUuid.detail());
        admin.kicked("bbbb", "1.2.3.4", KickReason.KICK);
        var byName = assertInstanceOf(Decision.Kick.class, admin.admit(new Connect("bbbb", "u", "bad", "1.2.3.4", 0)));
        assertEquals("name banned by /bad/", byName.detail());
    }

    @Test
    void dosBansCloseUnansweredUntilLifted(){
        assertTrue(admin.dosBan("::ffff:1.2.3.4"));
        assertFalse(admin.dosBan("1.2.3.4"), "canonical");
        assertIgnored(admit("aaaa", "1.2.3.4"));
        assertAdmitted(admit("aaaa", "1.2.3.5"));
        assertEquals(List.of("1.2.3.4"), admin.dosBans());
        assertTrue(admin.dosUnban("1.2.3.4"));
        assertAdmitted(admit("aaaa", "1.2.3.4"));
    }

    @Test
    void unbanningAnAddressFreesThePlayersItWasAimedAt() throws Exception{
        join("aaaa", "1.2.3.4");
        get(admin.banPlayerAt("aaaa", "5.6.7.8", "in game", null, "player:admin"));
        get(admin.ban(Kind.UUID, "bbbb", "separate", null, "console"));
        get(admin.ban(Kind.ADDRESS, "9.9.9.9", "no player", null, "console"));
        assertEquals(List.of("5.6.7.8"), get(admin.activeBans()).stream().filter(b -> b.kind() == Kind.ADDRESS && b.playerId() != null).map(Ban::target).toList(),
            "the live address, not the last recorded one");

        assertEquals(2, get(admin.unbanAddress("5.6.7.8", "console")), "the address and its player's UUID");
        assertAdmitted(admit("aaaa", "5.6.7.8"));
        assertKicked(KickReason.BANNED, admit("bbbb", "1.1.1.1"), "a ban that came separately stays");
        assertEquals(1, get(admin.unbanAddress("9.9.9.9", "console")));
        assertEquals(0, get(admin.unbanAddress("9.9.9.9", "console")));
    }

    @Test
    void banDetailsNameTheirPlayer() throws Exception{
        join("aaaa", "1.2.3.4");
        get(admin.ban(Kind.UUID, "aaaa", "", null, "console"));
        get(admin.ban(Kind.ADDRESS, "1.2.3.4", "", null, "console"));
        get(admin.ban(Kind.ADDRESS, "4.4.4.4", "", null, "console"));
        var details = get(admin.activeBanDetails());
        assertEquals("aaaa", details.get(0).player().uuid());
        assertEquals("aaaa", details.get(1).player().uuid(), "vanilla's findByIP: the player who joined from it");
        assertNull(details.get(2).player());
    }

    @Test
    void aLeaveIsWhenThePlayerWasLastSeen() throws Exception{
        var joined = admin.join(connect("aaaa", "1.2.3.4"), new Client("en", 146, false, false), "alpha").get(Hang.SECONDS, TimeUnit.SECONDS);
        long joinedAt = clock.millis();
        clock.advance(Duration.ofMinutes(5));
        assertTrue(get(admin.left(joined.session().id(), "disconnected")));
        var player = get(admin.player("aaaa")).orElseThrow().player();
        assertEquals(joinedAt, player.firstSeen());
        assertEquals(clock.millis(), player.lastSeen());
    }

    static List<String> uuids(List<Players.Player> players){
        return players.stream().map(Players.Player::uuid).toList();
    }
}
