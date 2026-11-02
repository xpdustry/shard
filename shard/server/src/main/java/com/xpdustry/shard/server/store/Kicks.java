package com.xpdustry.shard.server.store;

import java.sql.*;
import java.util.*;

/**
 * The {@code kicks} table: kicks with a cooldown (vanilla's {@code handleKicked}, {@code Administration.java:114}). A
 * kick is never deleted; it expires or is pardoned.
 */
public final class Kicks{
    static final String COLUMNS = "k.id, p.uuid, k.address, k.reason, k.created_at, k.created_by, k.expires_at, k.pardoned_at, k.pardoned_by";
    static final String FROM = " FROM kicks k JOIN players p ON p.id = k.player_id";

    /**
     * One kick.
     *
     * @param reason the kick reason's vanilla name ({@code kick}, {@code vote}...) or the text a custom kick showed
     */
    public record Kick(long id, String uuid, String address, String reason, long createdAt, String createdBy, long expiresAt,
                       Long pardonedAt, String pardonedBy){
        /** Still keeps its player out at {@code now}. */
        public boolean activeAt(long now){
            return pardonedAt == null && expiresAt > now;
        }
    }

    /** Records a kick and counts it on the player (vanilla's {@code timesKicked}). */
    public static Kick insert(Connection connection, long playerId, String address, String reason, long now, String by, long expiresAt) throws SQLException{
        long id;
        try(var insert = connection.prepareStatement("INSERT INTO kicks(player_id, address, reason, created_at, created_by, expires_at)"
            + " VALUES (?, ?, ?, ?, ?, ?) RETURNING id")){
            Sql.bind(insert, playerId, address, reason, now, by, expiresAt);
            try(var rows = insert.executeQuery()){
                rows.next();
                id = rows.getLong(1);
            }
        }
        try(var update = connection.prepareStatement("UPDATE players SET times_kicked = times_kicked + 1 WHERE id = ?")){
            update.setLong(1, playerId);
            update.executeUpdate();
        }
        return many(connection, "SELECT " + COLUMNS + FROM + " WHERE k.id = ?", id).getFirst();
    }

    /** Every kick still in force at {@code now}, oldest first: the cooldowns a restart restores. */
    public static List<Kick> active(Connection connection, long now) throws SQLException{
        return many(connection, "SELECT " + COLUMNS + FROM + " WHERE k.pardoned_at IS NULL AND k.expires_at > ? ORDER BY k.id", now);
    }

    /** A player's kicks, oldest first. */
    public static List<Kick> forPlayer(Connection connection, long playerId) throws SQLException{
        return many(connection, "SELECT " + COLUMNS + FROM + " WHERE k.player_id = ? ORDER BY k.id", playerId);
    }

    /** Pardons a player's kicks in force; returns how many. */
    public static int pardon(Connection connection, long playerId, long now, String by) throws SQLException{
        try(var update = connection.prepareStatement("UPDATE kicks SET pardoned_at = ?, pardoned_by = ? WHERE player_id = ?"
            + " AND pardoned_at IS NULL AND expires_at > ?")){
            Sql.bind(update, now, by, playerId, now);
            return update.executeUpdate();
        }
    }

    /** Pardons every kick in force; returns how many. */
    public static int pardonAll(Connection connection, long now, String by) throws SQLException{
        try(var update = connection.prepareStatement("UPDATE kicks SET pardoned_at = ?, pardoned_by = ? WHERE pardoned_at IS NULL AND expires_at > ?")){
            Sql.bind(update, now, by, now);
            return update.executeUpdate();
        }
    }

    private static List<Kick> many(Connection connection, String sql, Object... parameters) throws SQLException{
        try(var query = connection.prepareStatement(sql)){
            Sql.bind(query, parameters);
            var kicks = new ArrayList<Kick>();
            try(var rows = query.executeQuery()){
                while(rows.next()){
                    kicks.add(new Kick(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getLong(5), rows.getString(6),
                        rows.getLong(7), Sql.nullableLong(rows, 8), rows.getString(9)));
                }
            }
            return kicks;
        }
    }

    private Kicks(){}
}
