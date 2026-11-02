package com.xpdustry.shard.server.store;

import java.sql.*;
import java.util.*;

/** The {@code sessions} table: one row per connection that got past admission. */
public final class Sessions{
    static final String COLUMNS = "id, player_id, world, address, name, locale, client_version, mobile, modded, started_at, ended_at, end_reason";

    /**
     * One session.
     *
     * @param world the world it is in, or null before it has one
     * @param endedAt null while it is open
     */
    public record Session(long id, long playerId, String world, String address, String name, String locale,
                          Integer clientVersion, boolean mobile, boolean modded, long startedAt, Long endedAt,
                          String endReason){
    }

    public static Session start(Connection connection, long playerId, String world, String address, String name,
                                String locale, Integer clientVersion, boolean mobile, boolean modded, long now) throws SQLException{
        try(var insert = connection.prepareStatement("INSERT INTO sessions(player_id, world, address, name, locale, client_version,"
            + " mobile, modded, started_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING " + COLUMNS)){
            Sql.bind(insert, playerId, world, address, name, locale, clientVersion, mobile, modded, now);
            try(var rows = insert.executeQuery()){
                rows.next();
                return read(rows);
            }
        }
    }

    /** Ends an open session; returns whether it was open. */
    public static boolean end(Connection connection, long id, long now, String reason) throws SQLException{
        try(var update = connection.prepareStatement("UPDATE sessions SET ended_at = max(?, started_at), end_reason = ? WHERE id = ? AND ended_at IS NULL")){
            Sql.bind(update, now, reason, id);
            return update.executeUpdate() == 1;
        }
    }

    /** Ends every session still open, as at startup after a crash; returns how many. */
    public static int endAllOpen(Connection connection, long now, String reason) throws SQLException{
        try(var update = connection.prepareStatement("UPDATE sessions SET ended_at = max(?, started_at), end_reason = ? WHERE ended_at IS NULL")){
            Sql.bind(update, now, reason);
            return update.executeUpdate();
        }
    }

    /** A player's latest sessions, newest first. */
    public static List<Session> recent(Connection connection, long playerId, int limit) throws SQLException{
        try(var query = connection.prepareStatement("SELECT " + COLUMNS + " FROM sessions WHERE player_id = ? ORDER BY started_at DESC, id DESC LIMIT ?")){
            Sql.bind(query, playerId, limit);
            var sessions = new ArrayList<Session>();
            try(var rows = query.executeQuery()){
                while(rows.next()) sessions.add(read(rows));
            }
            return sessions;
        }
    }

    private static Session read(ResultSet rows) throws SQLException{
        Long version = Sql.nullableLong(rows, 7);
        return new Session(rows.getLong(1), rows.getLong(2), rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6),
            version == null ? null : version.intValue(), rows.getInt(8) == 1, rows.getInt(9) == 1, rows.getLong(10),
            Sql.nullableLong(rows, 11), rows.getString(12));
    }

    private Sessions(){}
}
