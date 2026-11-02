package com.xpdustry.shard.server.store;

import java.sql.*;
import java.util.*;

/**
 * The {@code name_bans} table: vanilla's {@code bannedNames} ({@code Administration.java:33}), case-insensitive
 * regular expressions, in the order they were added. {@code server.admin} compiles and matches them.
 */
public final class NameBans{
    public record NameBan(long id, String pattern, long createdAt, String createdBy){
    }

    /** Adds a pattern; returns false if it is banned already. */
    public static boolean add(Connection connection, String pattern, long now, String by) throws SQLException{
        try(var insert = connection.prepareStatement("INSERT INTO name_bans(pattern, created_at, created_by) VALUES (?, ?, ?) ON CONFLICT(pattern) DO NOTHING")){
            Sql.bind(insert, pattern, now, by);
            return insert.executeUpdate() == 1;
        }
    }

    /** Removes a pattern; returns whether it was banned. */
    public static boolean remove(Connection connection, String pattern) throws SQLException{
        try(var delete = connection.prepareStatement("DELETE FROM name_bans WHERE pattern = ?")){
            delete.setString(1, pattern);
            return delete.executeUpdate() == 1;
        }
    }

    /** Removes every pattern; returns how many. */
    public static int clear(Connection connection) throws SQLException{
        try(var statement = connection.createStatement()){
            return statement.executeUpdate("DELETE FROM name_bans");
        }
    }

    /** Every pattern, oldest first. */
    public static List<NameBan> all(Connection connection) throws SQLException{
        try(var statement = connection.createStatement();
            var rows = statement.executeQuery("SELECT id, pattern, created_at, created_by FROM name_bans ORDER BY id")){
            var bans = new ArrayList<NameBan>();
            while(rows.next()) bans.add(new NameBan(rows.getLong(1), rows.getString(2), rows.getLong(3), rows.getString(4)));
            return bans;
        }
    }

    private NameBans(){}
}
