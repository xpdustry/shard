package com.xpdustry.shard.server.store;

import java.sql.*;
import java.util.*;

/**
 * The {@code chat_log} table. Chat stays out of the general logs (note 34); {@code chat.retention} bounds it, pruned in
 * small batches so the writer never holds a long transaction.
 */
public final class ChatLog{
    public enum Channel{ GLOBAL, TEAM, WHISPER, COMMAND, SYSTEM }

    /**
     * One message.
     *
     * @param id 0 before it is stored
     * @param delivered false when a filter or rate limit stopped it
     */
    public record Message(long id, long at, String world, Long playerId, Long sessionId, Channel channel, String message,
                          boolean delivered){
    }

    /** Appends messages in order (their ids are ignored); returns how many. */
    public static int append(Connection connection, List<Message> messages) throws SQLException{
        try(var insert = connection.prepareStatement("INSERT INTO chat_log(at, world, player_id, session_id, channel, message, delivered)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)")){
            for(var message : messages){
                Sql.bind(insert, message.at(), message.world(), message.playerId(), message.sessionId(),
                    message.channel().name().toLowerCase(Locale.ROOT), message.message(), message.delivered());
                insert.addBatch();
            }
            return Arrays.stream(insert.executeBatch()).sum();
        }
    }

    /** The latest messages of a world (or of every world, for null), oldest first. */
    public static List<Message> recent(Connection connection, String world, int limit) throws SQLException{
        String where = world == null ? "" : " WHERE world = ?";
        try(var query = connection.prepareStatement("SELECT id, at, world, player_id, session_id, channel, message, delivered FROM"
            + " (SELECT * FROM chat_log" + where + " ORDER BY id DESC LIMIT ?) ORDER BY id")){
            if(world == null) Sql.bind(query, limit);
            else Sql.bind(query, world, limit);
            var messages = new ArrayList<Message>();
            try(var rows = query.executeQuery()){
                while(rows.next()){
                    messages.add(new Message(rows.getLong(1), rows.getLong(2), rows.getString(3), Sql.nullableLong(rows, 4),
                        Sql.nullableLong(rows, 5), Channel.valueOf(rows.getString(6).toUpperCase(Locale.ROOT)), rows.getString(7), rows.getInt(8) == 1));
                }
            }
            return messages;
        }
    }

    /** Deletes up to {@code limit} messages older than {@code before}; returns how many. Call until it returns 0. */
    public static int prune(Connection connection, long before, int limit) throws SQLException{
        try(var delete = connection.prepareStatement("DELETE FROM chat_log WHERE id IN (SELECT id FROM chat_log WHERE at < ? ORDER BY id LIMIT ?)")){
            Sql.bind(delete, before, limit);
            return delete.executeUpdate();
        }
    }

    private ChatLog(){}
}
