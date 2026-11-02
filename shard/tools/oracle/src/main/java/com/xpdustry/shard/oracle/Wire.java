package com.xpdustry.shard.oracle;

import arc.util.io.*;
import mindustry.net.*;
import mindustry.net.ArcNetProvider.*;

import java.io.*;
import java.nio.*;
import java.util.*;

/**
 * Packets as vanilla's Arc transport puts them on the wire, using vanilla's own {@code ArcNetProvider.PacketSerializer}.
 *
 * <pre>
 * serialized = u8 packetId, u16 bodyLength, u8 compression (0 none, 1 LZ4 when bodyLength >= 36), body or LZ4(body)
 * TCP        = u16 length of serialized, serialized
 * UDP        = serialized (one datagram)
 * </pre>
 * LZ4 output is that of the LZ4 implementation vanilla loads (native or Java); compare bodies, not compressed bytes.
 */
public final class Wire{
    private static final PacketSerializer serializer = new PacketSerializer();

    /** One packet: its body (what {@code Packet.write} produces) and the serialized frame. */
    public record Frame(int id, String packet, int priority, byte[] body, byte[] serialized){
        /** The TCP form: a big-endian u16 length prefix, then {@link #serialized()}. */
        public byte[] tcp(){
            return ByteBuffer.allocate(serialized.length + 2).putShort((short)serialized.length).put(serialized).array();
        }

        public boolean compressed(){
            return serialized[3] == 1;
        }

        @Override
        public String toString(){
            return packet + "#" + id + " body=" + Hex.of(body);
        }
    }

    /**
     * Serializes a packet. Generated packets of {@code Loc.both} methods write their player argument only on a server,
     * so call this inside {@link Vanilla#net} with the sender's mode.
     */
    public static Frame frame(Packet packet){
        Vanilla.boot();
        var bodyBytes = new ByteArrayOutputStream();
        packet.write(new Writes(new DataOutputStream(bodyBytes)));

        ByteBuffer buffer = ByteBuffer.allocate(1 << 17);
        serializer.write(buffer, packet);
        return new Frame(Net.getPacketId(packet) & 0xff, packet.getClass().getSimpleName(), packet.getPriority(),
            bodyBytes.toByteArray(), Arrays.copyOf(buffer.array(), buffer.position()));
    }

    /**
     * Reads a serialized frame (no TCP length prefix) with vanilla's serializer, decompressing LZ4 bodies. The packet's
     * fields are not parsed yet: generated packets parse in {@code handled()}. Vanilla rejects packet types that the
     * reading side does not accept, so call this inside {@link Vanilla#net} with the reader's mode.
     */
    public static Object read(byte[] serialized){
        Vanilla.boot();
        return serializer.read(ByteBuffer.wrap(serialized));
    }

    /** The body of a serialized frame, decompressed. */
    public static byte[] body(byte[] serialized){
        Vanilla.boot();
        ByteBuffer in = ByteBuffer.wrap(serialized);
        in.get();
        int length = in.getShort() & 0xffff;
        byte compression = in.get();
        if(compression == 0) return Arrays.copyOfRange(serialized, 4, 4 + length);
        byte[] out = new byte[length];
        net.jpountz.lz4.LZ4Factory.fastestInstance().safeDecompressor().decompress(serialized, 4, serialized.length - 4, out, 0, length);
        return out;
    }

    private Wire(){}
}
