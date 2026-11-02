package com.xpdustry.shard.wire;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.oracle.Vanilla.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import mindustry.gen.*;
import mindustry.net.Packets.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Frame headers against vanilla's {@code ArcNetProvider.PacketSerializer}: lengths, the LZ4 threshold, stream chunks. */
class FrameTest{
    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    static void sameHeader(Wire.Frame vanilla){
        Frame shard = Frame.read(new Reads(vanilla.serialized()));
        assertEquals(vanilla.id(), shard.id(), vanilla.packet());
        assertEquals(vanilla.body().length, shard.length(), vanilla.packet());
        assertEquals(vanilla.compressed(), shard.compressed(), vanilla.packet() + " of " + vanilla.body().length + " bytes");
        assertEquals(Frame.of(vanilla.id(), vanilla.body().length), shard);
        Writes write = new Writes();
        shard.write(write);
        assertArrayEquals(Arrays.copyOf(vanilla.serialized(), Frame.HEADER), write.toByteArray());
        if(!shard.compressed()) assertArrayEquals(vanilla.serialized(), Frame.raw(shard.id(), vanilla.body()));
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4, 34, 35, 36, 37, 1000, 32767})
    void compressedFrom36Bytes(int length){
        var packet = new SendMessageCallPacket();
        packet.message = "a".repeat(length - 3);
        Wire.Frame vanilla = Vanilla.net(NetMode.server, () -> Wire.frame(packet));
        assertEquals(Packets.SEND_MESSAGE, vanilla.id());
        assertEquals(length, vanilla.body().length);
        sameHeader(vanilla);
    }

    @Test
    void anEmptyBody(){
        Wire.Frame vanilla = Vanilla.net(NetMode.client, () -> Wire.frame(new ConnectConfirmCallPacket()));
        assertEquals(0, vanilla.body().length);
        sameHeader(vanilla);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 30, 1000, 32761})
    void streamChunksAreNeverCompressed(int data){
        var chunk = new StreamChunk();
        chunk.id = 5;
        chunk.data = new byte[data];
        Wire.Frame vanilla = Wire.frame(chunk);
        assertEquals(Frame.STREAM_CHUNK, vanilla.id());
        assertFalse(vanilla.compressed());
        sameHeader(vanilla);
    }

    @Test
    void vanillaReadsUncompressedFramesOfAnyLength(){
        //Shard may skip LZ4 for a body vanilla would compress: the flag decides
        byte[] body = Vanilla.net(NetMode.server, () -> {
            var packet = new SendMessageCallPacket();
            packet.message = "a".repeat(500);
            return Wire.frame(packet).body();
        });
        Object read = Vanilla.net(NetMode.client, () -> Wire.read(Frame.raw(Packets.SEND_MESSAGE, body)));
        assertInstanceOf(SendMessageCallPacket.class, read);
    }

    @Test
    void headersVanillaWouldNotRead(){
        assertThrows(WireException.class, () -> Frame.read(new Reads(new byte[]{(byte)0xFE, 0, 0, 0})));
        assertThrows(WireException.class, () -> Frame.read(new Reads(new byte[]{1, 0, 0})));
        assertThrows(WireException.class, () -> Frame.read(new Reads(new byte[]{1, (byte)0x80, 1, 0})));
        assertEquals(new Frame(1, Frame.MAX_BODY, false), Frame.read(new Reads(new byte[]{1, (byte)0x80, 0, 0})));
        assertTrue(Frame.read(new Reads(new byte[]{9, 0, 40, 2})).compressed(), "any non-zero compression byte is LZ4");
        assertThrows(IllegalArgumentException.class, () -> Frame.of(9, Frame.MAX_BODY));
    }
}
