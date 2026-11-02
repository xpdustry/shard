package com.xpdustry.shard.server.config;

import java.util.*;

import static com.xpdustry.shard.server.config.ConfigKeys.*;

/**
 * What became of each vanilla server setting: every {@code Administration.Config} constant
 * ({@code Administration.java:534}-{@code 567}), the two other settings {@code ServerControl} reads, and the
 * universe's age ({@code utimei}, {@code Universe.java:299}). A test checks the constants against the vanilla jar, so
 * a key can't be forgotten.
 *
 * @param field the {@code Administration.Config} field, or null for a plain {@code Core.settings} key
 * @param name the name vanilla's {@code config} command takes (or the settings key)
 * @param key the Shard key it maps to, or null when Shard drops it
 * @param note how the mapping differs, or why it is dropped
 */
public record VanillaKeys(String field, String name, ConfigKey<?> key, String note){
    public static final List<VanillaKeys> ALL = List.of(
        mapped("serverName", "name", SERVER_NAME, "stored as servername"),
        mapped("desc", "desc", SERVER_DESCRIPTION, "\"off\" becomes empty"),
        mapped("port", "port", NET_PORT, ""),
        dropped("autoUpdate", "autoUpdate", "Shard never updates itself; deploy a new build instead"),
        mapped("showConnectMessages", "showConnectMessages", CHAT_SHOW_CONNECT_MESSAGES, "per world"),
        mapped("enableVotekick", "enableVotekick", GAME_VOTEKICK, "per world"),
        mapped("startCommands", "startCommands", CONSOLE_START_COMMANDS, "a YAML list, not a comma-separated string"),
        mapped("logging", "logging", LOG_FILE, "a file is written when log.file is set"),
        mapped("strict", "strict", GAME_STRICT, "per world"),
        mapped("antiSpam", "antiSpam", CHAT_ANTI_SPAM, ""),
        mapped("interactRateWindow", "interactRateWindow", GAME_INTERACT_RATE_WINDOW, "a duration (vanilla: seconds); per world"),
        mapped("interactRateLimit", "interactRateLimit", GAME_INTERACT_RATE_LIMIT, "per world"),
        mapped("interactRateKick", "interactRateKick", GAME_INTERACT_RATE_KICK, "per world"),
        mapped("messageRateLimit", "messageRateLimit", CHAT_MESSAGE_RATE_LIMIT, "a duration (vanilla: seconds)"),
        mapped("messageSpamKick", "messageSpamKick", CHAT_MESSAGE_SPAM_KICK, ""),
        mapped("packetSpamLimit", "packetSpamLimit", NET_PACKET_SPAM_LIMIT, ""),
        mapped("uuidChangeLimit", "uuidChangeLimit", ADMIN_UUID_CHANGE_LIMIT, ""),
        mapped("uuidChangeTimePeriod", "uuidChangeTimePeriod", ADMIN_UUID_CHANGE_PERIOD, "a duration (vanilla: hours)"),
        mapped("chatSpamLimit", "chatSpamLimit", NET_CHAT_SPAM_LIMIT, ""),
        mapped("socketInput", "socketInput", OPS_ENABLED, "the raw TCP console is gone; the loopback REST/SSE API replaces it"),
        mapped("socketInputPort", "socketInputPort", OPS_PORT, "the ops API's port"),
        mapped("socketInputAddress", "socketInputAddress", OPS_BIND, "loopback only"),
        mapped("allowCustomClients", "allowCustomClients", NET_ALLOW_CUSTOM_CLIENTS, "stored as allow-custom"),
        mapped("whitelist", "whitelist", ADMIN_WHITELIST, ""),
        mapped("motd", "motd", SERVER_MOTD, "\"off\" becomes empty; per world"),
        dropped("autosave", "autosave", "checkpoints are always on; see persistence.autosave-interval"),
        mapped("autosaveAmount", "autosaveAmount", PERSISTENCE_KEEP_RECENT, "checkpoint generations, per world (vanilla: 10 shared files)"),
        mapped("autosaveSpacing", "autosaveSpacing", PERSISTENCE_AUTOSAVE_INTERVAL, "a duration, default 60 s (vanilla: 300 s); per world"),
        mapped("debug", "debug", LOG_LEVEL, "debug=true is log.level: debug"),
        mapped("snapshotInterval", "snapshotInterval", NET_SNAPSHOT_INTERVAL, "a duration (vanilla: ms); per world"),
        mapped("autoPause", "autoPause", GAME_AUTO_PAUSE, "per world; apart from it, an empty world's time freezes unless game.simulate-when-empty"),
        mapped("roundExtraTime", "roundExtraTime", GAME_ROUND_EXTRA_TIME, "a duration (vanilla: seconds); per world"),
        mapped("maxLogLength", "maxLogLength", LOG_MAX_FILE_SIZE, "a size (vanilla: bytes)"),
        mapped("logCommands", "logCommands", CHAT_LOG_COMMANDS, ""),
        mapped(null, "playerlimit", NET_PLAYER_LIMIT, "ServerControl's playerlimit command; 0 is no limit"),
        mapped(null, "utimei", GAME_UNIVERSE_SECONDS, "universe.seconds(), which state snapshots send; read at startup"),
        mapped(null, "shufflemode", GAME_SHUFFLE, "per world, over the world's game.maps")
    );

    public boolean isDropped(){
        return key == null;
    }

    /** The {@code Administration.Config} fields this list covers. */
    public static Set<String> fields(){
        var fields = new TreeSet<String>();
        for(var entry : ALL) if(entry.field != null) fields.add(entry.field);
        return fields;
    }

    private static VanillaKeys mapped(String field, String name, ConfigKey<?> key, String note){
        return new VanillaKeys(field, name, Objects.requireNonNull(key, name), note);
    }

    private static VanillaKeys dropped(String field, String name, String note){
        return new VanillaKeys(field, name, null, note);
    }
}
