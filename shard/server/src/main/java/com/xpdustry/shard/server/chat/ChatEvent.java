package com.xpdustry.shard.server.chat;

import com.xpdustry.shard.server.intent.*;
import com.xpdustry.shard.server.sync.*;
import com.xpdustry.shard.wire.Packet;
import com.xpdustry.shard.wire.v160_7.Packets;

/**
 * What an admitted chat line tells the server, after the tick commits. A {@link Said} line goes out to the world
 * through its sync, in the tick's Call order: vanilla's {@code sendChatMessage} handler sends it from the packet
 * handler, before {@code Logic.update}. A {@link Commanded} line is run by {@link Chat}'s command handler on the
 * world's thread once the tick's Calls are out.
 */
public sealed interface ChatEvent extends IntentEvent{
    long session();

    /** The speaker's player id in the world. */
    int player();

    /**
     * A plain line, filtered: {@code Call.sendMessage(chatFormatter.format(player, message), message, player)}, to
     * everyone in the world (NetClient.java:380).
     *
     * @param name the speaker's plain name, for the log
     */
    record Said(long session, int player, String name, String formatted, String message) implements ChatEvent, Outbound{
        @Override
        public Audience audience(){
            return Audience.ALL;
        }

        @Override
        public Packet packet(){
            return new Packets.SendMessage2(formatted, message, player);
        }
    }

    /** A line that starts with the command prefix; {@code /t}'s message has been through the filters. */
    record Commanded(long session, int player, String line) implements ChatEvent{
    }
}
