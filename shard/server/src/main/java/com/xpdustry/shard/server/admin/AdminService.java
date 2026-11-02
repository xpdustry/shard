package com.xpdustry.shard.server.admin;

import com.xpdustry.shard.server.config.*;
import com.xpdustry.shard.server.store.*;
import com.xpdustry.shard.server.store.Bans.*;
import com.xpdustry.shard.server.store.Grants.*;
import com.xpdustry.shard.server.store.Notes.*;
import com.xpdustry.shard.server.store.Players.*;
import com.xpdustry.shard.server.store.Sessions.*;

import java.net.*;
import java.nio.charset.*;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * The admin model (WP50): admission checks, bans, admins, the whitelist, kick cooldowns, player lookups, notes and
 * trace data. No packets: the connect flow calls {@link #admit} and acts on its {@link Decision}.
 *
 * <p><b>Bans are durable before they count.</b> A ban, unban, grant or revoke is an {@link Store.Priority#URGENT}
 * write. Inside that transaction it rebuilds the {@link ModerationIndex} from the rows the transaction sees, and the
 * returned future completes only after the commit and the index's publication (note 34). Admission reads only the
 * published index, never the database.
 *
 * <p><b>Where Shard differs from vanilla on purpose</b> (note 34 "Logical schema and authorization"):
 * <ul>
 *   <li>An address ban bans that address. Vanilla also flags every player who ever used it, so one ban takes out
 *       a whole NAT's history ({@code Administration.java:225}); and a UUID unban lifts every address that player
 *       used ({@code :280}). Here a UUID unban lifts the bans aimed at that player, and an address unban lifts the
 *       bans on that address and the bans that came with them ({@link #unbanAddress}).</li>
 *   <li>Subnet bans are CIDR blocks, not string prefixes ({@link Subnet}).</li>
 *   <li>Bans can expire and carry a reason, an author and a revoker.</li>
 *   <li>Kicks keep their reason and who kicked, and a pardon is recorded, not deleted.</li>
 *   <li>USIDs are stored as SHA-256 hashes.</li>
 *   <li>A name ban's regex gets a bounded number of steps per name ({@link ModerationIndex#nameBan}); vanilla's
 *       can backtrack for as long as the pattern makes it, on the thread that admits connections.</li>
 *   <li>The DOS list is checked at the connect packet, where vanilla closes the socket on accept.</li>
 * </ul>
 *
 * <p>Kick cooldowns are kept in the store and in memory: admission reads memory, and {@link #start} restores the
 * cooldowns still running.
 */
public final class AdminService{
    static final int LOOKUP_LIMIT = 50;
    static final int TRACKED_LIMIT = 100_000;

    private final Store store;
    private final Supplier<Config> config;
    private final InstantSource clock;
    private final AtomicReference<ModerationIndex> index = new AtomicReference<>(ModerationIndex.EMPTY);
    // assigned on the store's writer thread, so version order is commit order
    private final AtomicLong versions = new AtomicLong();
    private final ConcurrentHashMap<String, Cooldown> uuidCooldowns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> addressCooldowns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Encounters> encounters = new ConcurrentHashMap<>();
    /** Vanilla's {@code dosBlacklist}: canonical addresses, in memory only, as vanilla never saved it. */
    private final ConcurrentSkipListSet<String> dosBans = new ConcurrentSkipListSet<>();

    private AdminService(Store store, Supplier<Config> config, InstantSource clock){
        this.store = store;
        this.config = config;
        this.clock = clock;
    }

    /** Loads the moderation index and the kick cooldowns still running, and returns a ready service. */
    public static AdminService start(Store store, Supplier<Config> config, InstantSource clock){
        var service = new AdminService(store, config, clock);
        service.publish(store.write(Store.Priority.URGENT, service::loadIndex).join());
        for(var kick : store.read(c -> Kicks.active(c, clock.millis())).join()) service.cooldown(kick.uuid(), kick.address(), kick.expiresAt());
        return service;
    }

    /** The published moderation index. */
    public ModerationIndex index(){
        return index.get();
    }

    // admission

    /**
     * A connect attempt, as the connect packet and the transport report it.
     *
     * @param playersOnline players on the host now, for {@code net.player-limit}
     */
    public record Connect(String uuid, String usid, String name, String address, int playersOnline){
    }

    /** What to do with a connect attempt. */
    public sealed interface Decision{
        /**
         * Let it through to the session's own checks (version, duplicates, name).
         *
         * @param indexVersion the moderation index decided with; re-check {@link #isCurrent} before activation
         */
        record Admit(boolean admin, long indexVersion) implements Decision{
        }

        /** Send {@code kick(reason)} and close. {@code detail} is for the log, never the client. */
        record Kick(KickReason reason, String detail) implements Decision{
        }

        /** Close without answering: vanilla's treatment of a banned address ({@code NetServer.java:173}). */
        record Ignore(String detail) implements Decision{
        }
    }

    /** Whether nothing moderation-related committed since {@code indexVersion}. */
    public boolean isCurrent(long indexVersion){
        return index.get().version() == indexVersion;
    }

    /**
     * Runs the admin checks of vanilla's connect handler, in its order ({@code NetServer.java:158}-{@code 233}):
     * address and subnet bans, the UUID change limit, a missing UUID or USID, UUID bans, kick cooldowns, the
     * player limit (admins exempt), then the whitelist. Mods, versions, duplicate names and ids are the session's
     * checks. Never blocks on the database.
     */
    public Decision admit(Connect connect){
        long now = clock.millis();
        var index = this.index.get();
        var host = config.get();
        InetAddress address;
        try{
            address = Addresses.parse(connect.address());
        }catch(IllegalArgumentException e){
            return new Decision.Ignore("unparseable address " + connect.address());
        }
        String canonical = Addresses.format(address);

        if(dosBans.contains(canonical)) return new Decision.Ignore("DOS-banned address " + canonical);

        var addressBan = index.addressBan(address, now);
        if(addressBan.isPresent()) return new Decision.Ignore("address banned by ban #" + addressBan.get().id());

        if(connect.uuid() != null && uuidChangesExceeded(canonical, connect.uuid(), now, host)){
            ban(Kind.ADDRESS, canonical, "more than " + host.get(ConfigKeys.ADMIN_UUID_CHANGE_LIMIT) + " UUIDs in "
                + ConfigKeys.ADMIN_UUID_CHANGE_PERIOD.display(host.get(ConfigKeys.ADMIN_UUID_CHANGE_PERIOD)), null, "system");
            return new Decision.Kick(KickReason.BANNED, "too many UUID changes from " + canonical);
        }

        if(connect.uuid() == null || connect.usid() == null) return new Decision.Kick(KickReason.ID_IN_USE, "no UUID or USID");

        var uuidBan = index.uuidBan(connect.uuid(), now);
        if(uuidBan.isPresent()) return new Decision.Kick(KickReason.BANNED, "UUID banned by ban #" + uuidBan.get().id());
        // vanilla doesn't say why: "they may try to bypass it" (NetServer.java:195)
        var nameBan = index.nameBan(connect.name() == null ? "" : connect.name());
        if(nameBan.isPresent()) return new Decision.Kick(KickReason.BANNED, "name banned by /" + nameBan.get() + "/");

        if(now < kickedUntil(connect.uuid(), canonical)) return new Decision.Kick(KickReason.RECENT_KICK, "kick cooldown");

        String usidHash = usidHash(connect.usid());
        boolean admin = index.isAdmin(connect.uuid(), usidHash);
        int limit = host.get(ConfigKeys.NET_PLAYER_LIMIT);
        if(limit > 0 && connect.playersOnline() >= limit && !admin) return new Decision.Kick(KickReason.PLAYER_LIMIT, "player limit " + limit);

        if(host.get(ConfigKeys.ADMIN_WHITELIST) && !index.isWhitelisted(connect.uuid(), usidHash)){
            // vanilla records the rejected identity, so "whitelist add <uuid>" works afterwards
            String name = connect.name() == null ? "" : connect.name();
            store.write(c -> Players.recordJoin(c, connect.uuid(), usidHash, name, Names.plain(name), canonical, now, false));
            return new Decision.Kick(KickReason.WHITELIST, "not whitelisted");
        }
        return new Decision.Admit(admin, index.version());
    }

    /** Vanilla's {@code checkUuidChanges} ({@code Administration.java:292}). */
    private boolean uuidChangesExceeded(String address, String uuid, long now, Config host){
        int limit = host.get(ConfigKeys.ADMIN_UUID_CHANGE_LIMIT);
        if(limit <= 1) return false;
        long period = host.get(ConfigKeys.ADMIN_UUID_CHANGE_PERIOD).toMillis();
        if(encounters.size() > TRACKED_LIMIT) encounters.values().removeIf(e -> now - e.start >= period);
        var seen = encounters.compute(address, (key, old) -> old == null || now - old.start >= period ? new Encounters(now) : old);
        synchronized(seen){
            seen.uuids.add(uuid);
            return seen.uuids.size() > limit;
        }
    }

    private static final class Encounters{
        final long start;
        final Set<String> uuids = new HashSet<>();

        Encounters(long start){
            this.start = start;
        }
    }

    // sessions

    /**
     * What the client said about itself.
     *
     * @param version its build, or null if unknown
     */
    public record Client(String locale, Integer version, boolean mobile, boolean modded){
    }

    /** A recorded join. */
    public record Joined(Player player, Session session){
    }

    /**
     * Records a join that passed every check: the player's identity, name and address history, and a new session
     * (vanilla's {@code updatePlayerJoined}).
     */
    public CompletableFuture<Joined> join(Connect connect, Client client, String world){
        long now = clock.millis();
        String address = Addresses.canonical(connect.address());
        String usidHash = usidHash(connect.usid());
        return store.write(c -> {
            var player = Players.recordJoin(c, connect.uuid(), usidHash, connect.name(), Names.plain(connect.name()), address, now, true);
            var session = Sessions.start(c, player.id(), world, address, connect.name(), client.locale(), client.version(),
                client.mobile(), client.modded(), now);
            return new Joined(player, session);
        });
    }

    /** Ends a session; its player was last seen now. */
    public CompletableFuture<Boolean> left(long sessionId, String reason){
        long now = clock.millis();
        return store.write(c -> {
            boolean ended = Sessions.end(c, sessionId, now, reason);
            if(ended) Players.seenAtEnd(c, sessionId, now);
            return ended;
        });
    }

    // kicks

    /** Records a kick for {@code reason}: its cooldown, if it has one, and the kick count. */
    public CompletableFuture<Boolean> kicked(String uuid, String address, KickReason reason){
        return kicked(uuid, address, reason, "system");
    }

    /** {@link #kicked(String, String, KickReason)}, by {@code actor}. */
    public CompletableFuture<Boolean> kicked(String uuid, String address, KickReason reason, String actor){
        Duration cooldown = reason.hasCooldown() ? config.get().get(ConfigKeys.ADMIN_KICK_COOLDOWN) : Duration.ZERO;
        return kicked(uuid, address, cooldown, reason.vanillaName(), actor);
    }

    /** A kick with an explicit cooldown and no stated reason, by the system. */
    public CompletableFuture<Boolean> kicked(String uuid, String address, Duration cooldown){
        return kicked(uuid, address, cooldown, KickReason.KICK.vanillaName(), "system");
    }

    /**
     * Records a kick with an explicit cooldown (vanilla's {@code handleKicked}, {@code Administration.java:114}): the
     * UUID and the address wait {@code cooldown} at once, and the kick, with its reason and actor, is stored and
     * counted on the player when the returned write completes. A zero cooldown records nothing, as in vanilla.
     *
     * @param reason the kick reason's vanilla name, or the text a custom kick showed
     */
    public CompletableFuture<Boolean> kicked(String uuid, String address, Duration cooldown, String reason, String actor){
        if(cooldown.isZero() || cooldown.isNegative()) return CompletableFuture.completedFuture(false);
        String canonical, text;
        try{
            checkText(uuid, 64, "uuid", false);
            canonical = Addresses.canonical(address);
            checkText(actor, 128, "actor", false);
            text = reason == null ? "" : reason.length() > 1024 ? reason.substring(0, 1024) : reason;
        }catch(IllegalArgumentException e){
            return CompletableFuture.failedFuture(e);
        }
        long now = clock.millis(), until = now + cooldown.toMillis();
        cooldown(uuid, canonical, until);
        return store.write(c -> {
            Kicks.insert(c, Players.ensure(c, uuid, now).id(), canonical, text, now, actor, until);
            return true;
        });
    }

    private void cooldown(String uuid, String canonical, long until){
        long now = clock.millis();
        if(uuidCooldowns.size() > TRACKED_LIMIT) uuidCooldowns.values().removeIf(c -> c.until <= now);
        if(addressCooldowns.size() > TRACKED_LIMIT) addressCooldowns.values().removeIf(t -> t <= now);
        addressCooldowns.merge(canonical, until, Math::max);
        uuidCooldowns.merge(uuid, new Cooldown(until, canonical), (a, b) -> a.until >= b.until ? a : b);
    }

    /** When the UUID or the address may rejoin (vanilla's {@code getKickTime}); 0 if never kicked. */
    public long kickedUntil(String uuid, String address){
        var cooldown = uuidCooldowns.get(uuid);
        long byUuid = cooldown == null ? 0 : cooldown.until;
        return Math.max(byUuid, addressCooldowns.getOrDefault(Addresses.canonical(address), 0L));
    }

    /** Ends a UUID's cooldown and its last address's (vanilla's {@code pardon}); returns whether it had one. */
    public boolean pardon(String uuid){
        var cooldown = uuidCooldowns.remove(uuid);
        if(cooldown == null) return false;
        addressCooldowns.remove(cooldown.address);
        long now = clock.millis();
        store.write(c -> {
            var player = Players.byUuid(c, uuid);
            return player.isEmpty() ? 0 : Kicks.pardon(c, player.get().id(), now, "system");
        });
        return true;
    }

    /**
     * The console's {@code pardon <ID>} ({@code ServerControl.java:1072}): ends the cooldowns of a known player, if
     * any, and records the pardon on their kicks. Empty if nobody ever had that UUID.
     */
    public CompletableFuture<Optional<Player>> pardonPlayer(String uuid, String actor){
        try{
            checkText(actor, 128, "actor", false);
        }catch(IllegalArgumentException e){
            return CompletableFuture.failedFuture(e);
        }
        long now = clock.millis();
        return store.write(c -> {
            var player = Players.byUuid(c, uuid);
            if(player.isEmpty()) return player;
            Kicks.pardon(c, player.get().id(), now, actor);
            var cooldown = uuidCooldowns.remove(uuid);
            if(cooldown != null) addressCooldowns.remove(cooldown.address);
            return player;
        });
    }

    /** Ends every kick cooldown, for tests that share one server between cases. */
    public void pardonAll(){
        uuidCooldowns.clear();
        addressCooldowns.clear();
        long now = clock.millis();
        store.write(c -> Kicks.pardonAll(c, now, "system")).join();
    }

    /** A player's kicks, oldest first. */
    public CompletableFuture<List<Kicks.Kick>> kicks(String uuid){
        return store.read(c -> {
            var player = Players.byUuid(c, uuid);
            return player.isEmpty() ? List.<Kicks.Kick>of() : Kicks.forPlayer(c, player.get().id());
        });
    }

    private record Cooldown(long until, String address){
    }

    // bans

    /**
     * Bans a UUID, an address or a subnet, and returns once the ban is committed and in force.
     *
     * @param duration null for permanent
     * @param actor who did it: {@code console}, {@code api}, {@code player:<uuid>}, {@code system}
     */
    public CompletableFuture<Ban> ban(Kind kind, String target, String reason, Duration duration, String actor){
        String canonical;
        try{
            canonical = canonicalTarget(kind, target);
            checkText(reason, 1024, "reason", true);
            checkText(actor, 128, "actor", false);
            if(duration != null && (duration.isZero() || duration.isNegative())) throw new IllegalArgumentException("a ban lasts more than zero");
        }catch(IllegalArgumentException e){
            return CompletableFuture.failedFuture(e);
        }
        long now = clock.millis();
        Long expires = duration == null ? null : now + duration.toMillis();
        return moderate(c -> {
            Long playerId = kind == Kind.UUID ? Players.ensure(c, canonical, now).id() : null;
            return Bans.insert(c, kind, canonical, playerId, reason, now, actor, expires);
        });
    }

    /**
     * Bans a player by UUID and, with {@code alsoAddress}, their last address too, as the in-game ban action does
     * ({@code NetServer.java:927}). Both bans point at the player, so {@link #unbanPlayer} lifts both.
     */
    public CompletableFuture<List<Ban>> banPlayer(String uuid, boolean alsoAddress, String reason, Duration duration, String actor){
        return banPlayer(uuid, alsoAddress, null, reason, duration, actor);
    }

    /**
     * Bans a player by UUID and the address they are connected from, as the in-game ban action does
     * ({@code banPlayerID(con.uuid)}, {@code banPlayerIP(con.address)}, {@code NetServer.java:928}). Both bans point at
     * the player.
     */
    public CompletableFuture<List<Ban>> banPlayerAt(String uuid, String address, String reason, Duration duration, String actor){
        String canonical;
        try{
            canonical = Addresses.canonical(address);
        }catch(IllegalArgumentException e){
            return CompletableFuture.failedFuture(e);
        }
        return banPlayer(uuid, true, canonical, reason, duration, actor);
    }

    private CompletableFuture<List<Ban>> banPlayer(String uuid, boolean alsoAddress, String address, String reason, Duration duration, String actor){
        try{
            checkText(uuid, 64, "uuid", false);
            checkText(reason, 1024, "reason", true);
            checkText(actor, 128, "actor", false);
            if(duration != null && (duration.isZero() || duration.isNegative())) throw new IllegalArgumentException("a ban lasts more than zero");
        }catch(IllegalArgumentException e){
            return CompletableFuture.failedFuture(e);
        }
        long now = clock.millis();
        Long expires = duration == null ? null : now + duration.toMillis();
        return moderate(c -> {
            var player = Players.ensure(c, uuid, now);
            var bans = new ArrayList<Ban>();
            bans.add(Bans.insert(c, Kind.UUID, uuid, player.id(), reason, now, actor, expires));
            String at = address != null ? address : player.lastAddress();
            if(alsoAddress && at != null) bans.add(Bans.insert(c, Kind.ADDRESS, at, player.id(), reason, now, actor, expires));
            return List.copyOf(bans);
        });
    }

    /** Lifts every ban in force on one target; returns how many. */
    public CompletableFuture<Integer> unban(Kind kind, String target, String actor){
        String canonical;
        try{
            canonical = canonicalTarget(kind, target);
            checkText(actor, 128, "actor", false);
        }catch(IllegalArgumentException e){
            return CompletableFuture.failedFuture(e);
        }
        long now = clock.millis();
        return moderate(c -> Bans.revokeTarget(c, kind, canonical, now, actor));
    }

    /** Lifts the bans on a UUID and every ban aimed at that player; returns how many. */
    public CompletableFuture<Integer> unbanPlayer(String uuid, String actor){
        long now = clock.millis();
        return moderate(c -> {
            int lifted = Bans.revokeTarget(c, Kind.UUID, uuid, now, actor);
            var player = Players.byUuid(c, uuid);
            if(player.isPresent()){
                for(var ban : Bans.forPlayer(c, player.get().id())){
                    if(ban.activeAt(now) && Bans.revoke(c, ban.id(), now, actor)) lifted++;
                }
            }
            return lifted;
        });
    }

    /**
     * The console's {@code unban <ip>}, "completely unban a person by IP" ({@code ServerControl.java:1064}): lifts the
     * bans on the address, and every ban aimed at the players those bans were aimed at, so a player banned in game
     * (UUID and address together) is free again. Vanilla lifts every player who ever used the address
     * ({@code Administration.java:257}); bans that came separately stay. Returns how many bans were lifted.
     */
    public CompletableFuture<Integer> unbanAddress(String address, String actor){
        String canonical;
        try{
            canonical = Addresses.canonical(address);
            checkText(actor, 128, "actor", false);
        }catch(IllegalArgumentException e){
            return CompletableFuture.failedFuture(e);
        }
        long now = clock.millis();
        return moderate(c -> {
            var players = new TreeSet<Long>();
            for(var ban : Bans.forTarget(c, Kind.ADDRESS, canonical)) if(ban.activeAt(now) && ban.playerId() != null) players.add(ban.playerId());
            int lifted = Bans.revokeTarget(c, Kind.ADDRESS, canonical, now, actor);
            for(long player : players){
                for(var ban : Bans.forPlayer(c, player)) if(ban.activeAt(now) && Bans.revoke(c, ban.id(), now, actor)) lifted++;
            }
            return lifted;
        });
    }

    /** Lifts one ban by id; returns whether it was in force. */
    public CompletableFuture<Boolean> revoke(long banId, String actor){
        long now = clock.millis();
        return moderate(c -> Bans.revoke(c, banId, now, actor));
    }

    /** The bans in force now, oldest first. */
    public CompletableFuture<List<Ban>> activeBans(){
        long now = clock.millis();
        return store.read(c -> Bans.active(c, now));
    }

    /** A ban in force, with the player it was aimed at, or for an address the last player who joined from it. */
    public record BanDetail(Ban ban, Player player){
    }

    /** The bans in force now, oldest first, each with its player if one is known (vanilla's {@code findByIP}). */
    public CompletableFuture<List<BanDetail>> activeBanDetails(){
        long now = clock.millis();
        return store.read(c -> {
            var details = new ArrayList<BanDetail>();
            for(var ban : Bans.active(c, now)){
                Optional<Player> player = ban.playerId() != null ? Players.byId(c, ban.playerId())
                    : ban.kind() == Kind.ADDRESS ? Players.byAddress(c, ban.target(), 1).stream().findFirst() : Optional.empty();
                details.add(new BanDetail(ban, player.orElse(null)));
            }
            return details;
        });
    }

    /** The canonical stored form of a ban target; throws {@link IllegalArgumentException} for a bad one. */
    public static String canonicalTarget(Kind kind, String target){
        return switch(kind){
            case UUID -> {
                checkText(target, 64, "uuid", false);
                yield target;
            }
            case ADDRESS -> Addresses.canonical(target);
            case SUBNET -> Subnet.parse(target, false).toString();
        };
    }

    // name bans

    /**
     * Bans names that contain a match of {@code regex}, ignoring case (vanilla's {@code addNameBan},
     * {@code Administration.java:140}). Returns false if that pattern is banned already; fails for an invalid one.
     */
    public CompletableFuture<Boolean> banName(String regex, String actor){
        try{
            checkText(regex, 256, "name regex", false);
            checkText(actor, 128, "actor", false);
            ModerationIndex.compileName(regex);
        }catch(IllegalArgumentException e){
            return CompletableFuture.failedFuture(e);
        }
        long now = clock.millis();
        return moderate(c -> NameBans.add(c, regex, now, actor));
    }

    /** Lifts a name ban; returns whether the pattern was banned. */
    public CompletableFuture<Boolean> unbanName(String regex){
        return moderate(c -> NameBans.remove(c, regex));
    }

    /** Lifts every name ban; returns how many. */
    public CompletableFuture<Integer> clearNameBans(){
        return moderate(NameBans::clear);
    }

    /** The banned name patterns, oldest first. */
    public CompletableFuture<List<NameBans.NameBan>> nameBans(){
        return store.read(NameBans::all);
    }

    // the DOS list

    /** Vanilla's {@code blacklistDos}: connections from {@code address} are closed unanswered until the process ends. */
    public boolean dosBan(String address){
        return dosBans.add(Addresses.canonical(address));
    }

    public boolean dosUnban(String address){
        return dosBans.remove(Addresses.canonical(address));
    }

    /** The DOS-banned addresses, sorted. */
    public List<String> dosBans(){
        return List.copyOf(dosBans);
    }

    // admins and the whitelist

    /**
     * Makes a player an admin, pinned to {@code usid}, or with null to the USID of their last join (vanilla's
     * {@code adminPlayer}, {@code Administration.java:347}). Returns whether they weren't one already.
     */
    public CompletableFuture<Boolean> grantAdmin(String uuid, String usid, String actor){
        return grant(Grants.Table.ADMINS, uuid, usid, actor);
    }

    public CompletableFuture<Boolean> revokeAdmin(String uuid){
        return ungrant(Grants.Table.ADMINS, uuid);
    }

    public CompletableFuture<List<Grant>> admins(){
        return store.read(c -> Grants.all(c, Grants.Table.ADMINS));
    }

    /** Whitelists a player at the USID of their last attempt (vanilla's {@code whitelist}, {@code :382}). */
    public CompletableFuture<Boolean> whitelist(String uuid, String actor){
        return grant(Grants.Table.WHITELIST, uuid, null, actor);
    }

    public CompletableFuture<Boolean> unwhitelist(String uuid){
        return ungrant(Grants.Table.WHITELIST, uuid);
    }

    public CompletableFuture<List<Grant>> whitelisted(){
        return store.read(c -> Grants.all(c, Grants.Table.WHITELIST));
    }

    /** Vanilla's {@code isAdmin}: the UUID is an admin and this USID is the one pinned at grant time. */
    public boolean isAdmin(String uuid, String usid){
        return usid != null && index.get().isAdmin(uuid, usidHash(usid));
    }

    private CompletableFuture<Boolean> grant(Grants.Table table, String uuid, String usid, String actor){
        try{
            checkText(actor, 128, "actor", false);
        }catch(IllegalArgumentException e){
            return CompletableFuture.failedFuture(e);
        }
        long now = clock.millis();
        String pinned = usid == null ? null : usidHash(usid);
        return moderate(c -> {
            var player = Players.byUuid(c, uuid).orElseThrow(() -> new IllegalArgumentException("no player with UUID " + uuid));
            String hash = pinned != null ? pinned : player.usidHash();
            if(hash == null) throw new IllegalArgumentException("player " + uuid + " never joined, so there is no USID to pin");
            return Grants.grant(c, table, player.id(), hash, now, actor);
        });
    }

    private CompletableFuture<Boolean> ungrant(Grants.Table table, String uuid){
        return moderate(c -> {
            var player = Players.byUuid(c, uuid);
            return player.isPresent() && Grants.revoke(c, table, player.get().id());
        });
    }

    // lookups

    /** Players matching a UUID, an address or a name exactly (vanilla's {@code info} command). */
    public CompletableFuture<List<Detail>> info(String query){
        return store.read(c -> {
            var details = new ArrayList<Detail>();
            for(var player : Players.findExact(c, query, Names.plain(query), LOOKUP_LIMIT)) details.add(Players.detail(c, player));
            return details;
        });
    }

    /** Players who used a name containing {@code part}, colors ignored (vanilla's {@code search} command). */
    public CompletableFuture<List<Player>> search(String part){
        return store.read(c -> Players.searchNames(c, Names.plain(part), LOOKUP_LIMIT));
    }

    public CompletableFuture<Optional<Detail>> player(String uuid){
        return store.read(c -> {
            var player = Players.byUuid(c, uuid);
            return player.isEmpty() ? Optional.<Detail>empty() : Optional.of(Players.detail(c, player.get()));
        });
    }

    /** What the connection says live, for a trace. */
    public record Live(String address, String locale, boolean modded, boolean mobile){
    }

    /** The trace an admin's {@code trace} action shows ({@code NetServer.java:937}). */
    public CompletableFuture<TraceInfo> trace(String uuid, Live live){
        return store.read(c -> {
            var player = Players.byUuid(c, uuid);
            if(player.isEmpty()) return new TraceInfo(live.address(), uuid, live.locale(), live.modded(), live.mobile(), 0, 0, List.of(), List.of());
            var detail = Players.detail(c, player.get());
            return new TraceInfo(live.address(), uuid, live.locale(), live.modded(), live.mobile(), player.get().timesJoined(),
                player.get().timesKicked(), detail.addresses(), detail.names());
        });
    }

    // notes

    public CompletableFuture<Note> addNote(String uuid, String author, String body){
        try{
            checkText(author, 128, "author", false);
            checkText(body, 4000, "note", false);
        }catch(IllegalArgumentException e){
            return CompletableFuture.failedFuture(e);
        }
        long now = clock.millis();
        return store.write(c -> Notes.add(c, Players.ensure(c, uuid, now).id(), author, body, now));
    }

    public CompletableFuture<List<Note>> notes(String uuid){
        return store.read(c -> {
            var player = Players.byUuid(c, uuid);
            return player.isEmpty() ? List.<Note>of() : Notes.forPlayer(c, player.get().id());
        });
    }

    // internals

    /** The SHA-256 of a USID, hex. A USID is 8 random bytes, so its hash can't be reversed into a working USID. */
    public static String usidHash(String usid){
        try{
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(usid.getBytes(StandardCharsets.UTF_8)));
        }catch(NoSuchAlgorithmException e){
            throw new AssertionError(e);
        }
    }

    /** Runs a moderation change as an urgent write, and publishes the index it leaves before completing. */
    private <T> CompletableFuture<T> moderate(Store.Tx<T> change){
        return store.write(Store.Priority.URGENT, c -> {
            T result = change.run(c);
            return new Published<>(result, loadIndex(c));
        }).thenApply(published -> {
            publish(published.index);
            return published.value;
        });
    }

    private record Published<T>(T value, ModerationIndex index){
    }

    private ModerationIndex loadIndex(Connection connection) throws SQLException{
        return ModerationIndex.build(versions.incrementAndGet(), Bans.active(connection, clock.millis()),
            Grants.all(connection, Grants.Table.ADMINS), Grants.all(connection, Grants.Table.WHITELIST), NameBans.all(connection));
    }

    private void publish(ModerationIndex next){
        index.accumulateAndGet(next, (current, candidate) -> candidate.version() > current.version() ? candidate : current);
    }

    private static void checkText(String text, int max, String what, boolean emptyOk){
        if(text == null) throw new IllegalArgumentException("no " + what);
        if(!emptyOk && text.isEmpty()) throw new IllegalArgumentException("empty " + what);
        if(text.length() > max) throw new IllegalArgumentException(what + " is longer than " + max + " characters");
    }
}
