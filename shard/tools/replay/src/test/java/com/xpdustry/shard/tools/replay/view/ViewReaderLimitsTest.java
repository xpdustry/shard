package com.xpdustry.shard.tools.replay.view;

import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.charset.*;
import java.util.zip.*;

import static com.xpdustry.shard.tools.replay.view.ViewWriter.*;
import static org.junit.jupiter.api.Assertions.*;

/** The reader refuses hostile files (FORMAT.md, "Limits") before allocating for them. */
class ViewReaderLimitsTest{
    static final String HEADER = """
        {"format":1,"width":2,"height":1,"blocks":["air","stone"],"units":["dagger"],"effects":["unit-death"],"players":[],
         "bullets":[],"segments":[{}]}""";

    @Test
    void aDeflateBombStopsAtTheLimit() throws IOException{
        var bomb = new ByteArrayOutputStream();
        try(var out = new DeflaterOutputStream(bomb, new Deflater(9, true))){
            byte[] zeros = new byte[1 << 20];
            for(int i = 0; i <= ViewReader.MAX_INFLATED >> 20; i++) out.write(zeros);
        }
        var reader = new ViewReader(file(HEADER, bomb.toByteArray()));
        var error = assertThrows(IllegalArgumentException.class, () -> reader.play(0, state -> {}));
        assertTrue(error.getMessage().contains("inflating past"), error.getMessage());
    }

    @Test
    void aVarintOver64BitsIsRefused(){
        byte[] tooLong = {(byte)0x80, (byte)0x80, (byte)0x80, (byte)0x80, (byte)0x80, (byte)0x80, (byte)0x80, (byte)0x80, (byte)0x80, 0x02};
        assertThrows(IllegalArgumentException.class, () -> new Bytes.In(tooLong).varint());
        byte[] max = {(byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, 0x01};
        assertEquals(-1L, new Bytes.In(max).varint());
    }

    @Test
    void aBlockOutsideTheTableIsRefused(){
        // a keyframe with one building of block 5, in a table of 2
        var segment = new Bytes(32).u8(KEYFRAME).varint(0).varint(0).varint(1).varint(0).varint(5);
        assertRefused(segment, "outside a table");
    }

    @Test
    void aCountBeyondTheBytesLeftIsRefused(){
        var segment = new Bytes(32).u8(KEYFRAME).varint(0).varint(1L << 40);
        assertRefused(segment, "bytes left");
    }

    @Test
    void anAdditionOverASurvivorIsRefused(){
        // a keyframe with unit 1, then a frame adding unit 1 again without removing it
        var segment = new Bytes(64).u8(KEYFRAME).varint(0).varint(0).varint(0)
            .varint(1).varint(1).varint(0).u8(1).svarint(0).svarint(0).u16(0).u8(255).u8(0).u8(0)
            .varint(0).varint(0).varint(0).varint(0)
            .u8(FRAME).varint(3).u8(UNITS).varint(0).varint(1).varint(1).varint(0).u8(1).svarint(0).svarint(0).u16(0).u8(255).u8(0).u8(0);
        assertRefused(segment, "survives");
    }

    @Test
    void anUnknownSectionIsRefused(){
        var segment = new Bytes(32).u8(KEYFRAME).varint(0).varint(0).varint(0).varint(0).varint(0).varint(0).varint(0).varint(0)
            .u8(FRAME).varint(3).u8(64);
        assertRefused(segment, "unknown frame sections");
    }

    @Test
    void aSegmentMustStartWithAKeyframe(){
        assertRefused(new Bytes(8).u8(FRAME).varint(3).u8(0), "keyframe");
    }

    static void assertRefused(Bytes segment, String why){
        var reader = new ViewReader(file(HEADER, deflate(segment.toArray())));
        var error = assertThrows(IllegalArgumentException.class, () -> reader.play(0, state -> {}));
        assertTrue(error.getMessage().contains(why), error.getMessage());
    }

    /** A whole file around one compressed segment, with a 2×1 map of stone. */
    static byte[] file(String header, byte[] compressedSegment){
        byte[] json = header.getBytes(StandardCharsets.UTF_8);
        byte[] terrain = deflate(new Bytes(16).varint(1).varint(2).varint(1).varint(1).varint(2).varint(0).varint(1).varint(2).varint(0).toArray());
        return new Bytes(64).bytes(MAGIC).u8(FORMAT).u32(json.length).bytes(json).u32(terrain.length).bytes(terrain)
            .u32(compressedSegment.length).bytes(compressedSegment).toArray();
    }

    static byte[] deflate(byte[] raw){
        var deflater = new Deflater(6, true);
        deflater.setInput(raw);
        deflater.finish();
        var out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1 << 16];
        while(!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer));
        deflater.end();
        return out.toByteArray();
    }
}
