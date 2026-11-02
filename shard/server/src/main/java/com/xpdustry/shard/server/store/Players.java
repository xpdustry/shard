package com.xpdustry.shard.server.store;

import java.sql.*;
import java.util.*;

/**
 * The {@code players}, {@code player_names} and {@code player_addresses} tables. Every method runs on the connection
 * it is given, so callers compose them inside one {@link Store#write} or {@link Store#read}.
 */
public final class Players{
    static final String COLUMNS = "id, uuid, usid_hash, last_name, last_address, first_seen, last_seen, times_joined, times_kicked";

    /**
     * A player record.
     *
     * @param usidHash the SHA-256 of the last USID, or null if the player never joined
     */
    public record Player(long id, String uuid, String usidHash, String lastName, String lastAddress, long firstSeen,
                         long lastSeen, int timesJoined, int timesKicked){
    }

    /** A player with their name and address history, oldest first. */
    public record Detail(Player player, List<String> names, List<String> addresses){
    }

    /**
     * Records a join: creates the player if new, counts the join, and adds the name and address to the history
     * (vanilla's {@code updatePlayerJoined}, {@code Administration.java:208}).
     *
     * @param plainName the name without colors, trimmed and in lower case (for search)
     * @param countJoin false to record the identity without counting a join, as vanilla does for a client the
     *     whitelist turns away ({@code NetServer.java:229}), so an admin can whitelist it afterwards
     */
    public static Player recordJoin(Connection connection, String uuid, String usidHash, String name, String plainName,
                                    String address, long now, boolean countJoin) throws SQLException{
        Player player;
        try(var upsert = connection.prepareStatement("""
            INSERT INTO players(uuid, usid_hash, last_name, last_address, first_seen, last_seen, times_joined)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET usid_hash = excluded.usid_hash, last_name = excluded.last_name,
                last_address = excluded.last_address, last_seen = max(last_seen, excluded.last_seen),
                times_joined = times_joined + excluded.times_joined
            RETURNING\s""" + COLUMNS)){
            Sql.bind(upsert, uuid, usidHash, name, address, now, now, countJoin ? 1 : 0);
            try(var rows = upsert.executeQuery()){
                rows.next();
                player = read(rows);
            }
        }
        try(var names = connection.prepareStatement("""
            INSERT INTO player_names(player_id, name, plain, first_seen, last_seen) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(player_id, name) DO UPDATE SET last_seen = max(last_seen, excluded.last_seen)""")){
            names.setLong(1, player.id());
            names.setString(2, name);
            names.setString(3, plainName);
            names.setLong(4, now);
            names.setLong(5, now);
            names.executeUpdate();
        }
        try(var addresses = connection.prepareStatement("""
            INSERT INTO player_addresses(player_id, address, first_seen, last_seen) VALUES (?, ?, ?, ?)
            ON CONFLICT(player_id, address) DO UPDATE SET last_seen = max(last_seen, excluded.last_seen)""")){
            addresses.setLong(1, player.id());
            addresses.setString(2, address);
            addresses.setLong(3, now);
            addresses.setLong(4, now);
            addresses.executeUpdate();
        }
        return player;
    }

    /**
     * The player with {@code uuid}, created with no history if unknown (vanilla's {@code getCreateInfo}, so a UUID
     * can be banned or noted before it ever joins).
     */
    public static Player ensure(Connection connection, String uuid, long now) throws SQLException{
        try(var insert = connection.prepareStatement("""
            INSERT INTO players(uuid, last_name, first_seen, last_seen) VALUES (?, '<unknown>', ?, ?)
            ON CONFLICT(uuid) DO NOTHING""")){
            insert.setString(1, uuid);
            insert.setLong(2, now);
            insert.setLong(3, now);
            insert.executeUpdate();
        }
        return byUuid(connection, uuid).orElseThrow();
    }

    public static Optional<Player> byUuid(Connection connection, String uuid) throws SQLException{
        return one(connection, "SELECT " + COLUMNS + " FROM players WHERE uuid = ?", uuid);
    }

    public static Optional<Player> byId(Connection connection, long id) throws SQLException{
        return one(connection, "SELECT " + COLUMNS + " FROM players WHERE id = ?", id);
    }

    /** Players who ever joined from {@code address}, most recent first. */
    public static List<Player> byAddress(Connection connection, String address, int limit) throws SQLException{
        return many(connection, "SELECT " + prefixed() + " FROM players p JOIN player_addresses a ON a.player_id = p.id"
            + " WHERE a.address = ? ORDER BY a.last_seen DESC, p.id LIMIT ?", address, limit);
    }

    /**
     * Players matching {@code query} exactly as a UUID, an address, or any name they used, raw or plain
     * (vanilla's {@code findByName}, {@code Administration.java:418}; this also matches plain historical names).
     */
    public static List<Player> findExact(Connection connection, String query, String plainQuery, int limit) throws SQLException{
        return many(connection, "SELECT " + COLUMNS + " FROM players p WHERE p.uuid = ?"
            + " OR EXISTS(SELECT 1 FROM player_names n WHERE n.player_id = p.id AND (n.name = ? OR n.plain = ?))"
            + " OR EXISTS(SELECT 1 FROM player_addresses a WHERE a.player_id = p.id AND a.address = ?)"
            + " ORDER BY p.last_seen DESC, p.id LIMIT ?", query, query, plainQuery, query, limit);
    }

    /** Players who used a name containing {@code plainPart} (vanilla's {@code searchNames}, {@code Administration.java:433}). */
    public static List<Player> searchNames(Connection connection, String plainPart, int limit) throws SQLException{
        String pattern = "%" + plainPart.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return many(connection, "SELECT " + COLUMNS + " FROM players p WHERE EXISTS(SELECT 1 FROM player_names n"
            + " WHERE n.player_id = p.id AND n.plain LIKE ? ESCAPE '\\') ORDER BY p.last_seen DESC, p.id LIMIT ?", pattern, limit);
    }

    public static Detail detail(Connection connection, Player player) throws SQLException{
        var names = new ArrayList<String>();
        try(var query = connection.prepareStatement("SELECT name FROM player_names WHERE player_id = ? ORDER BY first_seen, name")){
            query.setLong(1, player.id());
            try(var rows = query.executeQuery()){
                while(rows.next()) names.add(rows.getString(1));
            }
        }
        var addresses = new ArrayList<String>();
        try(var query = connection.prepareStatement("SELECT address FROM player_addresses WHERE player_id = ? ORDER BY first_seen, address")){
            query.setLong(1, player.id());
            try(var rows = query.executeQuery()){
                while(rows.next()) addresses.add(rows.getString(1));
            }
        }
        return new Detail(player, List.copyOf(names), List.copyOf(addresses));
    }

    /** A session ended at {@code now}: its player was last seen then (joins set it too). */
    public static void seenAtEnd(Connection connection, long sessionId, long now) throws SQLException{
        try(var update = connection.prepareStatement("UPDATE players SET last_seen = max(last_seen, ?)"
            + " WHERE id = (SELECT player_id FROM sessions WHERE id = ?)")){
            Sql.bind(update, now, sessionId);
            update.executeUpdate();
        }
    }

    private static String prefixed(){
        return "p." + COLUMNS.replace(", ", ", p.");
    }

    private static Optional<Player> one(Connection connection, String sql, Object... parameters) throws SQLException{
        var players = many(connection, sql, parameters);
        return players.isEmpty() ? Optional.empty() : Optional.of(players.getFirst());
    }

    private static List<Player> many(Connection connection, String sql, Object... parameters) throws SQLException{
        try(var query = connection.prepareStatement(sql)){
            Sql.bind(query, parameters);
            var players = new ArrayList<Player>();
            try(var rows = query.executeQuery()){
                while(rows.next()) players.add(read(rows));
            }
            return players;
        }
    }

    static Player read(ResultSet rows) throws SQLException{
        return new Player(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5),
            rows.getLong(6), rows.getLong(7), rows.getInt(8), rows.getInt(9));
    }

    private Players(){}
}
