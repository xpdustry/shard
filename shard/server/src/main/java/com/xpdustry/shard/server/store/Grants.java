package com.xpdustry.shard.server.store;

import com.xpdustry.shard.server.store.Players.*;

import java.sql.*;
import java.util.*;

/**
 * The {@code admins} and {@code whitelist} tables: a player, pinned to the USID hash they had when granted, as vanilla
 * keys both by UUID and USID ({@code Administration.java:378}, {@code :412}).
 */
public final class Grants{
    public enum Table{
        ADMINS("admins", "granted_at", "granted_by"),
        WHITELIST("whitelist", "added_at", "added_by");

        final String name, at, by;

        Table(String name, String at, String by){
            this.name = name;
            this.at = at;
            this.by = by;
        }
    }

    /** One grant, with its player. */
    public record Grant(Player player, String usidHash, long at, String by){
    }

    /** Grants or re-pins; returns whether the player had no grant before. */
    public static boolean grant(Connection connection, Table table, long playerId, String usidHash, long now, String by) throws SQLException{
        boolean existed = get(connection, table, playerId).isPresent();
        try(var upsert = connection.prepareStatement("INSERT INTO " + table.name + "(player_id, usid_hash, " + table.at + ", " + table.by + ")"
            + " VALUES (?, ?, ?, ?) ON CONFLICT(player_id) DO UPDATE SET usid_hash = excluded.usid_hash")){
            Sql.bind(upsert, playerId, usidHash, now, by);
            upsert.executeUpdate();
        }
        return !existed;
    }

    /** Removes a grant; returns whether there was one. */
    public static boolean revoke(Connection connection, Table table, long playerId) throws SQLException{
        try(var delete = connection.prepareStatement("DELETE FROM " + table.name + " WHERE player_id = ?")){
            delete.setLong(1, playerId);
            return delete.executeUpdate() == 1;
        }
    }

    public static Optional<Grant> get(Connection connection, Table table, long playerId) throws SQLException{
        var grants = query(connection, table, " WHERE g.player_id = ?", playerId);
        return grants.isEmpty() ? Optional.empty() : Optional.of(grants.getFirst());
    }

    /** Every grant in a table, by player id. */
    public static List<Grant> all(Connection connection, Table table) throws SQLException{
        return query(connection, table, " ORDER BY g.player_id");
    }

    private static List<Grant> query(Connection connection, Table table, String where, Object... parameters) throws SQLException{
        String sql = "SELECT p." + Players.COLUMNS.replace(", ", ", p.") + ", g.usid_hash, g." + table.at + ", g." + table.by
            + " FROM " + table.name + " g JOIN players p ON p.id = g.player_id" + where;
        try(var query = connection.prepareStatement(sql)){
            Sql.bind(query, parameters);
            var grants = new ArrayList<Grant>();
            try(var rows = query.executeQuery()){
                while(rows.next()) grants.add(new Grant(Players.read(rows), rows.getString(10), rows.getLong(11), rows.getString(12)));
            }
            return grants;
        }
    }

    private Grants(){}
}
