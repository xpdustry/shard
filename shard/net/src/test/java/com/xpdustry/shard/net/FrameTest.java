package com.xpdustry.shard.net;

import org.junit.jupiter.api.*;

import java.nio.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Frame layouts, byte for byte as vanilla's {@code PacketSerializer} and Arc's framing write them. */
class FrameTest{
    static byte[] bytes(int... values){
        byte[] out = new byte[values.length];
        for(int i = 0; i < values.length; i++) out[i] = (byte)values[i];
        return out;
    }

    @Test
    void aShortPacketGoesUncompressed(){
        Frame frame = Frame.packet(84, bytes(1, 2, 3, 4, 5, 6, 7, 8));
        // u16 body length, id, u16 raw length, compression 0, payload (ArcNetProvider.java:560-569)
        assertArrayEquals(bytes(0, 12, 84, 0, 8, 0, 1, 2, 3, 4, 5, 6, 7, 8), frame.toByteArray());
        assertEquals(84, frame.id());
        assertEquals(14, frame.size());
        assertEquals(12, frame.bodySize());
        assertEquals(12, frame.udp().remaining());
        assertEquals(14, frame.tcp().remaining());
    }

    @Test
    void eachUdpViewIsTheBodyReadOnlyWithItsOwnPosition(){
        Frame frame = Frame.packet(84, bytes(1, 2, 3, 4, 5, 6, 7, 8));
        ByteBuffer first = frame.udp(), second = frame.udp();
        byte[] body = new byte[first.remaining()];
        first.get(body);
        assertArrayEquals(Arrays.copyOfRange(frame.toByteArray(), 2, frame.size()), body);
        assertFalse(first.hasRemaining());
        // a send drains its view; the next recipient's starts over
        assertEquals(0, second.position());
        assertEquals(frame.bodySize(), second.remaining());
        assertTrue(second.isReadOnly());
        assertThrows(ReadOnlyBufferException.class, () -> second.put(0, (byte)9));
    }

    @Test
    void packetsOf36BytesOrMoreAreCompressed(){
        for(int length : new int[]{35, 36, 37, 800, 20_000}){
            byte[] payload = new byte[length];
            for(int i = 0; i < length; i++) payload[i] = (byte)(i % 7);
            byte[] frame = Frame.packet(49, payload).toByteArray();
            int body = (frame[0] & 0xff) << 8 | frame[1] & 0xff;
            assertEquals(frame.length - 2, body);
            assertEquals(49, frame[2]);
            assertEquals(length, (frame[3] & 0xff) << 8 | frame[4] & 0xff, "raw length is the uncompressed size");
            assertEquals(length >= 36 ? 1 : 0, frame[5], "compression flag at " + length);
            assertArrayEquals(payload, Frame.decodePayload(frame, 3, frame.length - 3));
        }
    }

    @Test
    void incompressiblePayloadsStayCompressedAsInVanilla(){
        byte[] payload = new byte[200];
        new Random(3).nextBytes(payload);
        byte[] frame = Frame.packet(49, payload).toByteArray();
        assertEquals(1, frame[5]);
        assertTrue(frame.length > 2 + 4 + 200, "LZ4 of noise is a little longer than the noise");
        assertArrayEquals(payload, Frame.decodePayload(frame, 3, frame.length - 3));
    }

    @Test
    void streamChunksAreNeverCompressed(){
        byte[] data = new byte[3000];
        Frame chunk = Frame.streamChunk(7, data, 1000, 2000);
        byte[] frame = chunk.toByteArray();
        assertEquals(Frame.STREAM_CHUNK, frame[2]);
        assertEquals(0, frame[5]);
        ByteBuffer payload = ByteBuffer.wrap(Frame.decodePayload(frame, 3, frame.length - 3));
        assertEquals(7, payload.getInt());
        assertEquals(2000, payload.getShort());
        assertEquals(2000, payload.remaining());
        // and by id: vanilla checks the packet class, not the call site (ArcNetProvider.java:564)
        assertEquals(0, Frame.packet(Frame.STREAM_CHUNK, new byte[100]).toByteArray()[5]);
    }

    @Test
    void streamBeginCarriesIdTotalAndType(){
        byte[] frame = Frame.streamBegin(-5, 70_000, 2).toByteArray();
        assertArrayEquals(bytes(0, 13, 0, 0, 9, 0, -1, -1, -1, -5, 0, 1, 0x11, 0x70, 2), frame);
    }

    @Test
    void aPayloadOfExactly32KiBGoesUncompressed(){
        // vanilla compares (short)length < 36, and (short)32768 is negative
        byte[] payload = new byte[Frame.MAX_PAYLOAD];
        assertThrows(IllegalArgumentException.class, () -> Frame.packet(49, payload), "uncompressed it is over the client's buffer");
        Frame below = Frame.packet(49, payload, 0, Frame.MAX_PAYLOAD - 1, true, new Lz4());
        assertEquals(1, below.toByteArray()[5]);
        assertThrows(IllegalArgumentException.class, () -> Frame.packet(49, new byte[Frame.MAX_PAYLOAD + 1]));
    }

    @Test
    void framesOverTheClientsReadBufferAreRefused(){
        byte[] noise = new byte[26_000];
        new Random(4).nextBytes(noise);
        assertThrows(IllegalArgumentException.class, () -> Frame.packet(49, noise));
    }

    @Test
    void frameworkMessages(){
        // ArcNetProvider.writeFramework: -2, type, fields
        assertArrayEquals(bytes(0, 6, -2, 4, -1, -1, -1, -2), Frame.register(Frame.REGISTER_TCP, -2).toByteArray());
        assertArrayEquals(bytes(0, 6, -2, 3, 0, 0, 0, 0), Frame.register(Frame.REGISTER_UDP, 0).toByteArray());
        assertArrayEquals(bytes(0, 7, -2, 0, 0, 0, 1, 2, 1), Frame.ping(258, true).toByteArray());
        assertArrayEquals(bytes(0, 7, -2, 0, 0, 0, 0, 9, 0), Frame.ping(9, false).toByteArray());
        assertArrayEquals(bytes(0, 2, -2, 2), Frame.KEEP_ALIVE_FRAME.toByteArray());
        assertArrayEquals(bytes(0, 2, -2, 1), Frame.discoverHost().toByteArray());
        assertEquals(Frame.FRAMEWORK, Frame.KEEP_ALIVE_FRAME.id());
        assertThrows(IllegalArgumentException.class, () -> Frame.register(Frame.PING, 1));
    }

    @Test
    void decodingIsStrictAboutLengths(){
        byte[] good = Frame.packet(10, bytes(1, 2, 3)).toByteArray();
        assertArrayEquals(bytes(1, 2, 3), Frame.decodePayload(good, 3, good.length - 3));
        assertThrows(IllegalArgumentException.class, () -> Frame.decodePayload(good, 3, good.length - 4), "short payload");
        byte[] trailing = Arrays.copyOf(good, good.length + 1);
        assertThrows(IllegalArgumentException.class, () -> Frame.decodePayload(trailing, 3, trailing.length - 3), "trailing byte");
        assertThrows(IllegalArgumentException.class, () -> Frame.decodePayload(bytes(0, 0), 0, 2), "no header");
        assertThrows(IllegalArgumentException.class, () -> Frame.decodePayload(bytes(0x80, 1, 1, 0), 0, 4), "raw length over 32 KiB");
        assertThrows(IllegalArgumentException.class, () -> Frame.decodePayload(bytes(0, 40, 1, 0xff, 0xff), 0, 5), "bad LZ4");
    }
}
