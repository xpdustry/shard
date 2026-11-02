package com.xpdustry.shard.server.store;

import java.sql.*;
import java.util.*;

/**
 * The {@code bans} table. A ban is never deleted: it expires or is revoked, and keeps who did what. Targets are
 * stored canonical; {@code server.admin} canonicalizes them and matches them.
 */
public final class Bans{
    static final String COLUMNS = "id, kind, target, player_id, reason, created_at, created_by, expires_at, revoked_at, revoked_by";

    public enum Kind{
        /** A client UUID. */
        UUID,
        /** One canonical IP address. */
        ADDRESS,
        /** A canonical CIDR block, {@code 10.0.0.0/8} or {@code 2001:db8::/32}. */
        SUBNET;

        String sql(){
            return name().toLowerCase(Locale.ROOT);
        }

        static Kind of(String sql){
            return valueOf(sql.toUpperCase(Locale.ROOT));
        }
    }

    /**
     * One ban.
     *
     * @param playerId the player the ban was aimed at, if known
     * @param expiresAt null for permanent
     */
    public record Ban(long id, Kind kind, String target, Long playerId, String reason, long createdAt, String createdBy,
                      Long expiresAt, Long revokedAt, String revokedBy){
        /** In force at {@code now}: not revoked and not expired. */
        public boolean activeAt(long now){
            return revokedAt == null && (expiresAt == null || expiresAt > now);
        }
    }

    public static Ban insert(Connection connection, Kind kind, String target, Long playerId, String reason, long now,
                             String by, Long expiresAt) throws SQLException{
        try(var insert = connection.prepareStatement("INSERT INTO bans(kind, target, player_id, reason, created_at, created_by, expires_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING " + COLUMNS)){
            Sql.bind(insert, kind.sql(), target, playerId, reason, now, by, expiresAt);
            try(var rows = insert.executeQuery()){
                rows.next();
                return read(rows);
            }
        }
    }

    /** Every ban in force at {@code now}, oldest first. */
    public static List<Ban> active(Connection connection, long now) throws SQLException{
        return many(connection, "SELECT " + COLUMNS + " FROM bans WHERE revoked_at IS NULL AND (expires_at IS NULL OR expires_at > ?) ORDER BY id", now);
    }

    /** Every ban on one target, in force or not, oldest first. */
    public static List<Ban> forTarget(Connection connection, Kind kind, String target) throws SQLException{
        return many(connection, "SELECT " + COLUMNS + " FROM bans WHERE kind = ? AND target = ? ORDER BY id", kind.sql(), target);
    }

    /** Every ban aimed at a player, oldest first. */
    public static List<Ban> forPlayer(Connection connection, long playerId) throws SQLException{
        return many(connection, "SELECT " + COLUMNS + " FROM bans WHERE player_id = ? ORDER BY id", playerId);
    }

    public static Optional<Ban> byId(Connection connection, long id) throws SQLException{
        var bans = many(connection, "SELECT " + COLUMNS + " FROM bans WHERE id = ?", id);
        return bans.isEmpty() ? Optional.empty() : Optional.of(bans.getFirst());
    }

    /** Revokes every ban in force on one target; returns how many. */
    public static int revokeTarget(Connection connection, Kind kind, String target, long now, String by) throws SQLException{
        try(var update = connection.prepareStatement("UPDATE bans SET revoked_at = ?, revoked_by = ? WHERE kind = ? AND target = ?"
            + " AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > ?)")){
            Sql.bind(update, now, by, kind.sql(), target, now);
            return update.executeUpdate();
        }
    }

    /** Revokes one ban if it is in force; returns whether it was. */
    public static boolean revoke(Connection connection, long id, long now, String by) throws SQLException{
        try(var update = connection.prepareStatement("UPDATE bans SET revoked_at = ?, revoked_by = ? WHERE id = ?"
            + " AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > ?)")){
            Sql.bind(update, now, by, id, now);
            return update.executeUpdate() == 1;
        }
    }

    private static List<Ban> many(Connection connection, String sql, Object... parameters) throws SQLException{
        try(var query = connection.prepareStatement(sql)){
            Sql.bind(query, parameters);
            var bans = new ArrayList<Ban>();
            try(var rows = query.executeQuery()){
                while(rows.next()) bans.add(read(rows));
            }
            return bans;
        }
    }

    private static Ban read(ResultSet rows) throws SQLException{
        return new Ban(rows.getLong(1), Kind.of(rows.getString(2)), rows.getString(3), Sql.nullableLong(rows, 4), rows.getString(5),
            rows.getLong(6), rows.getString(7), Sql.nullableLong(rows, 8), Sql.nullableLong(rows, 9), rows.getString(10));
    }

    private Bans(){}
}
