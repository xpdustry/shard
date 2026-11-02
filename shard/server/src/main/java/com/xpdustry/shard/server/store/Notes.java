package com.xpdustry.shard.server.store;

import java.sql.*;
import java.util.*;

/** The {@code notes} table: moderator notes on a player. */
public final class Notes{
    public record Note(long id, long playerId, String author, long createdAt, String body){
    }

    public static Note add(Connection connection, long playerId, String author, String body, long now) throws SQLException{
        try(var insert = connection.prepareStatement("INSERT INTO notes(player_id, author, created_at, body) VALUES (?, ?, ?, ?)"
            + " RETURNING id, player_id, author, created_at, body")){
            Sql.bind(insert, playerId, author, now, body);
            try(var rows = insert.executeQuery()){
                rows.next();
                return read(rows);
            }
        }
    }

    /** A player's notes, oldest first. */
    public static List<Note> forPlayer(Connection connection, long playerId) throws SQLException{
        try(var query = connection.prepareStatement("SELECT id, player_id, author, created_at, body FROM notes WHERE player_id = ? ORDER BY created_at, id")){
            query.setLong(1, playerId);
            var notes = new ArrayList<Note>();
            try(var rows = query.executeQuery()){
                while(rows.next()) notes.add(read(rows));
            }
            return notes;
        }
    }

    private static Note read(ResultSet rows) throws SQLException{
        return new Note(rows.getLong(1), rows.getLong(2), rows.getString(3), rows.getLong(4), rows.getString(5));
    }

    private Notes(){}
}
