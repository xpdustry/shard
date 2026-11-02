package com.xpdustry.shard.server.config;

import com.xpdustry.shard.server.config.ConfigType.*;

import java.net.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;

import static com.xpdustry.shard.server.config.ConfigKey.key;

/**
 * Every config key Shard reads, in reference order. Vanilla's {@code Administration.Config} keys map onto these or are
 * dropped with a reason in {@link VanillaKeys}. Defaults follow vanilla's headless server unless the key's
 * description says otherwise.
 */
public final class ConfigKeys{
    static final int TEXT = 1024;

    // server
    public static final ConfigKey<String> SERVER_NAME = key("server.name", new Text(100), "Server")
        .doc("The server name shown in the client's server list.");
    public static final ConfigKey<String> SERVER_DESCRIPTION = key("server.description", new Text(100), "")
        .doc("The line under the name in the server list; empty for none (vanilla wrote \"off\").");
    public static final ConfigKey<String> SERVER_MOTD = key("server.motd", new Text(TEXT), "").world()
        .doc("The message sent to a player when they join; empty for none (vanilla wrote \"off\").");

    // net
    public static final ConfigKey<InetAddress> NET_BIND = key("net.bind", new Address(false), InetAddress.ofLiteral("127.0.0.1")).restart()
        .doc("The game listener's address. The dev box binds 127.0.0.1 only; a VM or host may bind any address.");
    public static final ConfigKey<Integer> NET_PORT = key("net.port", new Int(0, 65535), 6567).restart()
        .doc("The game port, TCP and UDP; 0 picks a free one (tests).");
    public static final ConfigKey<Integer> NET_PLAYER_LIMIT = key("net.player-limit", new Int(0, Integer.MAX_VALUE), 30)
        .doc("The most players on the host at once; 0 for no limit. Admins may join past it.");
    public static final ConfigKey<Boolean> NET_ALLOW_CUSTOM_CLIENTS = key("net.allow-custom-clients", new Bool(), false)
        .doc("Whether clients that report a modified build may join.");
    public static final ConfigKey<Integer> NET_PACKET_SPAM_LIMIT = key("net.packet-spam-limit", new Int(1, Integer.MAX_VALUE), 300)
        .doc("Packets a connection may send in 3 s before it is blacklisted and kicked.");
    public static final ConfigKey<Integer> NET_CHAT_SPAM_LIMIT = key("net.chat-spam-limit", new Int(1, Integer.MAX_VALUE), 20)
        .doc("Chat packets a connection may send in 2 s before it is blacklisted and kicked.");
    public static final ConfigKey<Duration> NET_SNAPSHOT_INTERVAL = key("net.snapshot-interval", new Span(Duration.ofMillis(16), Duration.ofSeconds(5)), Duration.ofMillis(200)).world()
        .doc("How often entity snapshots go to clients.");

    // host
    public static final ConfigKey<List<String>> HOST_WORLDS = key("host.worlds", new TextList(1024, 200), List.<String>of()).restart()
        .doc("The worlds hosted at startup, one entry each: \"[<id>=]<map> [<mode>]\". The map is a name from maps.vanilla-jar or "
            + "maps.directory (its file name, or its name tag with _ for spaces, any case); the id defaults to the file name in lower case; "
            + "the mode is survival, sandbox, attack, pvp or editor (default survival). Empty: nothing until the host console command.");
    public static final ConfigKey<JoinPolicy> HOST_JOIN = key("host.join", new Choice<>(JoinPolicy.class), JoinPolicy.FEWEST)
        .doc("The world a new player joins: fewest (the running world with the fewest players), first (the first running world in "
            + "host.worlds order) or round-robin.");
    public static final ConfigKey<Integer> HOST_WORLD_PLAYER_LIMIT = key("host.world-player-limit", new Int(0, Integer.MAX_VALUE), 0).world()
        .doc("The most players in one world at once, counting transfers on their way in; 0 for no limit. New in Shard.");

    // maps
    public static final ConfigKey<String> MAPS_VANILLA_JAR = key("maps.vanilla-jar", new Text(TEXT), "").restart()
        .doc("A vanilla v160.7 server or desktop jar, whose 18 default maps (maps/default/*.msav) may then be hosted. Empty for none.");
    public static final ConfigKey<String> MAPS_DIRECTORY = key("maps.directory", new Text(TEXT), "maps").restart()
        .doc("A directory of custom .msav maps, relative to the working directory (vanilla's config/maps). It may be missing.");

    // intents
    public static final ConfigKey<Integer> INTENTS_INBOX_CAPACITY = key("intents.inbox-capacity", new Int(64, 1 << 20), 4096)
        .doc("Inputs a world's inbox holds between two ticks. Worlds created after a reload read it. New in Shard.");
    public static final ConfigKey<Integer> INTENTS_MAILBOX_BOUND = key("intents.mailbox-bound", new Int(1, 1 << 16), 64)
        .doc("Reliable intents one session may have waiting for its world; past it the session is kicked, since reliable input is "
            + "never dropped. Worlds created after a reload read it. New in Shard.");
    public static final ConfigKey<Integer> INTENTS_MAILBOX_QUANTUM = key("intents.mailbox-quantum", new Int(1, 1 << 16), 16)
        .doc("Entries one session's mailbox moves into its world's inbox per turn of the round-robin pump. New in Shard.");
    public static final ConfigKey<Integer> INTENTS_INBOX_RESERVE = key("intents.inbox-reserve", new Int(0, 1 << 20), 512)
        .doc("Inbox slots intents never take, kept for joins, leaves and other sources: an eighth of the default capacity. It must "
            + "stay below intents.inbox-capacity. New in Shard.");

    // admin
    public static final ConfigKey<Boolean> ADMIN_WHITELIST = key("admin.whitelist", new Bool(), false)
        .doc("Whether only whitelisted players may join.");
    public static final ConfigKey<Integer> ADMIN_UUID_CHANGE_LIMIT = key("admin.uuid-change-limit", new Int(0, Integer.MAX_VALUE), 10)
        .doc("Distinct UUIDs one address may present within admin.uuid-change-period before the address is banned; 0 or 1 disables the check.");
    public static final ConfigKey<Duration> ADMIN_UUID_CHANGE_PERIOD = key("admin.uuid-change-period", new Span(Duration.ofMinutes(1), Duration.ofDays(30)), Duration.ofHours(3))
        .doc("The window of admin.uuid-change-limit.");
    public static final ConfigKey<Duration> ADMIN_KICK_COOLDOWN = key("admin.kick-cooldown", new Span(Duration.ZERO, Duration.ofDays(1)), Duration.ofSeconds(30))
        .doc("How long a kicked, banned or vote-kicked player must wait to rejoin (vanilla hard-codes 30 s).");

    // chat
    public static final ConfigKey<Boolean> CHAT_ANTI_SPAM = key("chat.anti-spam", new Bool(), true)
        .doc("Whether chat and block interaction are rate-limited, and repeat offenders kicked.");
    public static final ConfigKey<Duration> CHAT_MESSAGE_RATE_LIMIT = key("chat.message-rate-limit", new Span(Duration.ZERO, Duration.ofHours(1)), Duration.ZERO)
        .doc("The least time between two messages from one player; 0 to disable.");
    public static final ConfigKey<Integer> CHAT_MESSAGE_SPAM_KICK = key("chat.message-spam-kick", new Int(0, Integer.MAX_VALUE), 3)
        .doc("Messages inside the rate limit before the player is kicked; 0 to disable.");
    public static final ConfigKey<Boolean> CHAT_SHOW_CONNECT_MESSAGES = key("chat.show-connect-messages", new Bool(), true).world()
        .doc("Whether joins and leaves are announced in chat.");
    public static final ConfigKey<Boolean> CHAT_LOG_COMMANDS = key("chat.log-commands", new Bool(), true)
        .doc("Whether player commands are logged.");
    public static final ConfigKey<Duration> CHAT_RETENTION = key("chat.retention", new Span(Duration.ZERO, Duration.ofDays(3650)), Duration.ofDays(30))
        .doc("How long the chat log keeps messages; 0 to keep none. New in Shard.");

    // game
    public static final ConfigKey<Boolean> GAME_STRICT = key("game.strict", new Bool(), true).world()
        .doc("Whether player positions are corrected and duplicate names or ids are refused.");
    public static final ConfigKey<Boolean> GAME_VOTEKICK = key("game.votekick", new Bool(), true).world()
        .doc("Whether players may vote to kick.");
    public static final ConfigKey<Duration> GAME_INTERACT_RATE_WINDOW = key("game.interact-rate-window", new Span(Duration.ofSeconds(1), Duration.ofHours(1)), Duration.ofSeconds(6)).world()
        .doc("The window of the block interaction rate limit.");
    public static final ConfigKey<Integer> GAME_INTERACT_RATE_LIMIT = key("game.interact-rate-limit", new Int(1, Integer.MAX_VALUE), 25).world()
        .doc("Block interactions allowed per window.");
    public static final ConfigKey<Integer> GAME_INTERACT_RATE_KICK = key("game.interact-rate-kick", new Int(1, Integer.MAX_VALUE), 60).world()
        .doc("Block interactions inside one window that get the player kicked.");
    public static final ConfigKey<Duration> GAME_ROUND_EXTRA_TIME = key("game.round-extra-time", new Span(Duration.ZERO, Duration.ofMinutes(10)), Duration.ofSeconds(12)).world()
        .doc("The wait between game over and the next map.");
    public static final ConfigKey<Shuffle> GAME_SHUFFLE = key("game.shuffle", new Choice<>(Shuffle.class), Shuffle.CUSTOM).world()
        .doc("How a world picks its next map after a game over (vanilla's shufflemode): none ends the game by kicking its players and "
            + "stopping the world; all, custom and builtin pick at random among game.maps of that kind (custom falls back to the default "
            + "maps when there is no custom one), never the map just played unless it is the only one.");
    public static final ConfigKey<List<String>> GAME_MAPS = key("game.maps", new TextList(1024, 200), List.<String>of()).world()
        .doc("A world's map registry: the maps its rotation picks from, by name as in host.worlds. Empty: every map the server can "
            + "host (vanilla's Maps.all()). New in Shard.");
    public static final ConfigKey<Boolean> GAME_SIMULATE_WHEN_EMPTY = key("game.simulate-when-empty", new Bool(), false).world()
        .doc("Whether an empty world keeps simulating at 60 Hz. By default its time freezes (ARCHITECTURE.md §7); vanilla's autoPause=false is true here.");
    public static final ConfigKey<Boolean> GAME_AUTO_PAUSE = key("game.auto-pause", new Bool(), false).world()
        .doc("Whether a world pauses its game while nobody is in it, as vanilla's autoPause: players see it paused, and the first "
            + "one in resumes it, unless an admin paused it by hand.");
    public static final ConfigKey<Integer> GAME_UNIVERSE_SECONDS = key("game.universe-seconds", new Int(0, Integer.MAX_VALUE), 0).restart()
        .doc("The universe's age in seconds that state snapshots send (vanilla's universe.seconds(), saved as utimei); outside a campaign nothing advances it.");

    // persistence
    public static final ConfigKey<Duration> PERSISTENCE_AUTOSAVE_INTERVAL = key("persistence.autosave-interval", new Span(Duration.ofSeconds(5), Duration.ofDays(1)), Duration.ofSeconds(60)).world()
        .doc("How often a running world writes a checkpoint. Always on (vanilla: autosave=false, every 300 s).");
    public static final ConfigKey<String> PERSISTENCE_DIRECTORY = key("persistence.directory", new Text(TEXT), "data/worlds").restart()
        .doc("Where world checkpoints and crash bundles go, one directory per world id, relative to the working directory.");
    public static final ConfigKey<String> PERSISTENCE_SAVES_DIRECTORY = key("persistence.saves-directory", new Text(TEXT), "data/saves").restart()
        .doc("Where the console's save and load keep their slots, as <slot>.msav vanilla saves, relative to the working directory (vanilla: config/saves).");
    public static final ConfigKey<Integer> PERSISTENCE_KEEP_RECENT = key("persistence.keep-recent", new Int(1, 1000), 3).world()
        .doc("Checkpoint generations kept per world.");
    public static final ConfigKey<Duration> PERSISTENCE_HIBERNATE_AFTER = key("persistence.hibernate-after", new Span(Duration.ZERO, Duration.ofDays(30)), Duration.ofMinutes(10)).world()
        .doc("How long a world stays empty before it hibernates; 0 never hibernates. New in Shard.");
    public static final ConfigKey<Duration> PERSISTENCE_MATCH_CHECKPOINTS = key("persistence.match-checkpoints", new Span(Duration.ZERO, Duration.ofHours(1)), Duration.ZERO).world()
        .doc("How often a world also writes a checkpoint it keeps for the whole match, for replays; 0 writes none. Rounded up to a multiple of 10 s (600 ticks, the replay keyframe). New in Shard.");
    public static final ConfigKey<Boolean> PERSISTENCE_RESUME = key("persistence.resume", new Bool(), false).restart()
        .doc("Whether a world hosted again after a restart, from the same map and mode, goes on from its last checkpoint instead of starting over: the match continues. A match that ended (a game over's reload, a stop) never resumes. New in Shard.");

    // replay (note 37; the defaults answer its open questions)
    public static final ConfigKey<Boolean> REPLAY_RECORD = key("replay.record", new Bool(), false).world()
        .doc("Whether a world records its matches: a view stream to watch in a browser and a replay bundle (match checkpoints every 10 s, the inputs, the hashes). Turns persistence.match-checkpoints on at 10 s if it is off. Reloads live: a world starts recording at its next match checkpoint, in the match under way, and stops (closing the replay) when turned off. New in Shard.");
    public static final ConfigKey<String> REPLAY_DIRECTORY = key("replay.directory", new Text(TEXT), "data/replays").restart()
        .doc("Where replays go, relative to the working directory: bundles/<build>/<match>/ and views/<match>.sview, plus views/<match>/ split for static hosting. Serving them is the web host's job.");
    public static final ConfigKey<BundleRetention> REPLAY_KEEP_BUNDLES = key("replay.keep-bundles", new Choice<>(BundleRetention.class), BundleRetention.BUILD).restart()
        .doc("How long replay bundles stay. build: while the Shard build that recorded them runs; a later build deletes them once their view stream is baked (a bundle only replays on its own build). forever: never deleted.");
    public static final ConfigKey<Duration> REPLAY_KEEP_VIEWS = key("replay.keep-views", new Span(Duration.ZERO, Duration.ofDays(36_500)), Duration.ZERO).restart()
        .doc("How long published view streams (.sview) stay; 0 keeps them until someone deletes them.");
    public static final ConfigKey<PvpViews> REPLAY_PVP_VIEWS = key("replay.pvp-views", new Choice<>(PvpViews.class), PvpViews.AFTER_MATCH).world()
        .doc("When a PvP match's view stream is published for viewing. after-match: only once the match ends, so nobody watches the other team live. live: segment by segment, like other matches.");
    public static final ConfigKey<InGameReplays> REPLAY_IN_GAME = key("replay.in-game", new Choice<>(InGameReplays.class), InGameReplays.NONE).restart()
        .doc("In-game replays, watched with the vanilla client in a replay world (note 37 P4). none: browsers only; v1 has no other choice.");

    // storage
    public static final ConfigKey<String> STORAGE_DATABASE = key("storage.database", new Text(TEXT), "data/shard.db").restart()
        .doc("The SQLite database file, relative to the working directory. It must be on a local file system (WAL).");
    public static final ConfigKey<Synchronous> STORAGE_SYNCHRONOUS = key("storage.synchronous", new Choice<>(Synchronous.class), Synchronous.FULL).restart()
        .doc("SQLite's synchronous mode. full syncs every commit; normal may lose the last commits on power loss.");
    public static final ConfigKey<Integer> STORAGE_WRITER_QUEUE = key("storage.writer-queue", new Int(16, 1 << 20), 4096).restart()
        .doc("Writes that may wait for the writer thread; past it, writes fail fast.");
    public static final ConfigKey<Integer> STORAGE_READERS = key("storage.readers", new Int(1, 64), 4).restart()
        .doc("Reader connections; reads past it wait their turn.");
    public static final ConfigKey<Duration> STORAGE_BUSY_TIMEOUT = key("storage.busy-timeout", new Span(Duration.ZERO, Duration.ofMinutes(1)), Duration.ofSeconds(5)).restart()
        .doc("How long a connection waits on a lock before it fails.");
    public static final ConfigKey<String> STORAGE_BACKUP_DIR = key("storage.backup-dir", new Text(TEXT), "data/backups")
        .doc("Where online backups go, including the one taken before a migration.");

    // ops
    public static final ConfigKey<Boolean> OPS_ENABLED = key("ops.enabled", new Bool(), true).restart()
        .doc("Whether the REST/SSE ops API runs (metrics, health, events).");
    public static final ConfigKey<InetAddress> OPS_BIND = key("ops.bind", new Address(true), InetAddress.ofLiteral("127.0.0.1")).restart()
        .doc("The ops API's address: loopback only. Reach it remotely through an authenticated TLS reverse proxy.");
    public static final ConfigKey<Integer> OPS_PORT = key("ops.port", new Int(1, 65535), 7810).restart()
        .doc("The ops API's port.");

    // log
    public static final ConfigKey<LogLevel> LOG_LEVEL = key("log.level", new Choice<>(LogLevel.class), LogLevel.INFO)
        .doc("The root log level (vanilla: debug=true is debug).");
    public static final ConfigKey<LogFormat> LOG_FORMAT = key("log.format", new Choice<>(LogFormat.class), LogFormat.JSON).restart()
        .doc("json: one JSON object per line; text: a human pattern.");
    public static final ConfigKey<String> LOG_FILE = key("log.file", new Text(TEXT), "").restart()
        .doc("A log file, rolled by size, besides stdout; empty for stdout only.");
    public static final ConfigKey<Long> LOG_MAX_FILE_SIZE = key("log.max-file-size", new Size(64 << 10, 1L << 40), 5L << 20).restart()
        .doc("The size at which log.file rolls over.");

    // console
    public static final ConfigKey<List<String>> CONSOLE_START_COMMANDS = key("console.start-commands", new TextList(64, TEXT), List.<String>of()).restart()
        .doc("Console commands run once at startup, in order.");

    /** Every key, in reference order. */
    public static final List<ConfigKey<?>> ALL = List.of(
        SERVER_NAME, SERVER_DESCRIPTION, SERVER_MOTD,
        NET_BIND, NET_PORT, NET_PLAYER_LIMIT, NET_ALLOW_CUSTOM_CLIENTS, NET_PACKET_SPAM_LIMIT, NET_CHAT_SPAM_LIMIT, NET_SNAPSHOT_INTERVAL,
        HOST_WORLDS, HOST_JOIN, HOST_WORLD_PLAYER_LIMIT,
        MAPS_VANILLA_JAR, MAPS_DIRECTORY,
        INTENTS_INBOX_CAPACITY, INTENTS_MAILBOX_BOUND, INTENTS_MAILBOX_QUANTUM, INTENTS_INBOX_RESERVE,
        ADMIN_WHITELIST, ADMIN_UUID_CHANGE_LIMIT, ADMIN_UUID_CHANGE_PERIOD, ADMIN_KICK_COOLDOWN,
        CHAT_ANTI_SPAM, CHAT_MESSAGE_RATE_LIMIT, CHAT_MESSAGE_SPAM_KICK, CHAT_SHOW_CONNECT_MESSAGES, CHAT_LOG_COMMANDS, CHAT_RETENTION,
        GAME_STRICT, GAME_VOTEKICK, GAME_INTERACT_RATE_WINDOW, GAME_INTERACT_RATE_LIMIT, GAME_INTERACT_RATE_KICK, GAME_ROUND_EXTRA_TIME, GAME_SHUFFLE, GAME_MAPS, GAME_SIMULATE_WHEN_EMPTY,
        GAME_AUTO_PAUSE,
        GAME_UNIVERSE_SECONDS,
        PERSISTENCE_AUTOSAVE_INTERVAL, PERSISTENCE_DIRECTORY, PERSISTENCE_SAVES_DIRECTORY, PERSISTENCE_KEEP_RECENT, PERSISTENCE_HIBERNATE_AFTER, PERSISTENCE_MATCH_CHECKPOINTS,
        PERSISTENCE_RESUME,
        REPLAY_RECORD, REPLAY_DIRECTORY, REPLAY_KEEP_BUNDLES, REPLAY_KEEP_VIEWS, REPLAY_PVP_VIEWS, REPLAY_IN_GAME,
        STORAGE_DATABASE, STORAGE_SYNCHRONOUS, STORAGE_WRITER_QUEUE, STORAGE_READERS, STORAGE_BUSY_TIMEOUT, STORAGE_BACKUP_DIR,
        OPS_ENABLED, OPS_BIND, OPS_PORT,
        LOG_LEVEL, LOG_FORMAT, LOG_FILE, LOG_MAX_FILE_SIZE,
        CONSOLE_START_COMMANDS
    );

    private static final Map<String, ConfigKey<?>> BY_PATH = index();

    /** The key at a dotted path, or null. */
    public static ConfigKey<?> byPath(String path){
        return BY_PATH.get(path);
    }

    /** World ids: lower case, digits and dashes, as in file names and {@code SHARD__WORLDS__<ID>__}. */
    public static final Pattern WORLD_ID = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    public enum Synchronous{ FULL, NORMAL }

    public enum JoinPolicy{ FEWEST, FIRST, ROUND_ROBIN }

    /** Vanilla's {@code Maps.ShuffleMode}. */
    public enum Shuffle{ NONE, ALL, CUSTOM, BUILTIN }

    public enum LogLevel{ TRACE, DEBUG, INFO, WARN, ERROR }

    public enum LogFormat{ JSON, TEXT }

    public enum BundleRetention{ BUILD, FOREVER }

    public enum PvpViews{ AFTER_MATCH, LIVE }

    public enum InGameReplays{ NONE }

    private static Map<String, ConfigKey<?>> index(){
        var map = new LinkedHashMap<String, ConfigKey<?>>();
        for(var key : ALL){
            if(map.put(key.path(), key) != null) throw new ExceptionInInitializerError("duplicate config key " + key.path());
        }
        return Collections.unmodifiableMap(map);
    }

    private ConfigKeys(){}
}
