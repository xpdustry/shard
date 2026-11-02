package com.xpdustry.shard.server.sync;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.net.Frame;
import com.xpdustry.shard.server.admin.KickReason;
import com.xpdustry.shard.wire.Packet;
import com.xpdustry.shard.wire.WireContext;
import com.xpdustry.shard.wire.v160_7.Packets;

/**
 * Server-to-client {@code Call}s as transport frames: wire's generated packets, encoded once so a frame can go to
 * many connections. A world's frames go out through its {@link Outbox}; connection-level ones (kicks before a world,
 * pings) straight to the connection.
 */
public final class Calls{
    /** For packets whose bodies read nothing from a world: any lookup is a bug. */
    static final WireContext NO_WORLD = new WireContext(){
        @Override
        public boolean statusDynamic(int status){
            throw new IllegalStateException("this packet has no world");
        }

        @Override
        public boolean blockRotates(int block){
            throw new IllegalStateException("this packet has no world");
        }

        @Override
        public boolean blockExists(int block){
            throw new IllegalStateException("this packet has no world");
        }

        @Override
        public boolean unlockable(int type, int id){
            throw new IllegalStateException("this packet has no world");
        }

        @Override
        public boolean tileExists(int pos){
            throw new IllegalStateException("this packet has no world");
        }

        @Override
        public int syncedClass(int id){
            throw new IllegalStateException("this packet has no world");
        }
    };

    /** An entity snapshot of nothing: it resets the client's 20 s snapshot timeout ({@code NetClient.java:665}). */
    public static final Frame EMPTY_ENTITY_SNAPSHOT = frame(new Packets.EntitySnapshot((short)0, new byte[0]));
    public static final Frame WORLD_DATA_BEGIN = frame(new Packets.WorldDataBegin());

    /** The frame of a packet that reads nothing from a world. */
    public static Frame frame(Packet packet){
        return frame(packet, NO_WORLD);
    }

    public static Frame frame(Packet packet, WireContext context){
        return frame(packet, context, new Writes(32));
    }

    /** Encodes into {@code buffer}, which it resets first: the frame copies the bytes, so the buffer can be reused. */
    static Frame frame(Packet packet, WireContext context, Writes buffer){
        buffer.reset();
        packet.write(buffer, true, context);
        return Frame.packet(packet.packetId(), buffer.buffer(), 0, buffer.size(), packet.packetId() != Frame.STREAM_CHUNK, null);
    }

    /** {@code Call.kick(con, reason)}: the client shows the reason's text. */
    public static Frame kick(KickReason reason){
        return frame(new Packets.Kick2(com.xpdustry.shard.wire.v160_7.KickReason.of(reason.id())));
    }

    /** {@code Call.kick(con, text)}. */
    public static Frame kick(String text){
        return frame(new Packets.Kick(text));
    }

    /** {@code Call.sendMessage(text)}: a server line in chat. */
    public static Frame message(String text){
        return frame(new Packets.SendMessage(text));
    }

    /** {@code Call.sendMessage(formatted, raw, sender)}: a player's chat line. */
    public static Frame chat(String formatted, String raw, int sender){
        return frame(new Packets.SendMessage2(formatted, raw, sender));
    }

    /** {@code Call.infoMessage}: a dialog. */
    public static Frame infoMessage(String text){
        return frame(new Packets.InfoMessage(text));
    }

    public static Frame playerDisconnect(int player){
        return frame(new Packets.PlayerDisconnect(player));
    }

    public static Frame pingResponse(long time){
        return frame(new Packets.PingResponse(time));
    }

    private Calls(){}
}
