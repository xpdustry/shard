package com.xpdustry.shard.common.io;

import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** {@link Writes} and {@link Reads} against Arc's over {@code DataOutputStream} and {@code DataInputStream}. */
class ByteIoTest{
    static final String[] STRINGS = {"", "a", "conveyor", "\0", "nul\0inside", "é", "日本語", "😀 astral",
        "\uD800 lone high", "\uDC00 lone low", "߿ࠀ￿", "[accent]colored[]"};

    @Test
    void randomWriteSequencesProduceArcsBytes() throws IOException{
        var random = new SplittableRandom(11);
        for(int run = 0; run < 2000; run++){
            var ours = new Writes(random.nextInt(0, 16));
            var bytes = new ByteArrayOutputStream();
            var theirs = new arc.util.io.Writes(new DataOutputStream(bytes));
            for(int op = 0; op < 50; op++) write(random, ours, theirs);
            assertArrayEquals(bytes.toByteArray(), ours.toByteArray(), "run " + run);
            assertEquals(bytes.size(), ours.size());
        }
    }

    @Test
    void readsReturnWhatArcReturns() throws IOException{
        var random = new SplittableRandom(12);
        for(int run = 0; run < 2000; run++){
            var writes = new Writes();
            var script = new ArrayList<Integer>();
            for(int op = 0; op < 50; op++){
                int kind = random.nextInt(9);
                script.add(kind);
                writeKind(kind, random, writes, null);
            }
            byte[] bytes = writes.toByteArray();
            var ours = new Reads(bytes);
            var theirs = new arc.util.io.Reads(new DataInputStream(new ByteArrayInputStream(bytes)));
            for(int kind : script){
                switch(kind){
                    case 0 -> assertEquals(theirs.l(), ours.l());
                    case 1 -> assertEquals(theirs.i(), ours.i());
                    case 2 -> assertEquals(theirs.b(), ours.b());
                    case 3 -> assertEquals(theirs.bool(), ours.bool());
                    case 4 -> assertEquals(theirs.s(), ours.s());
                    case 5 -> assertEquals(Float.floatToRawIntBits(theirs.f()), Float.floatToRawIntBits(ours.f()));
                    case 6 -> assertEquals(Double.doubleToRawLongBits(theirs.d()), Double.doubleToRawLongBits(ours.d()));
                    case 7 -> assertEquals(theirs.str(), ours.str());
                    default -> assertArrayEquals(theirs.b(3), ours.b(3));
                }
            }
            assertEquals(theirs.checkEOF(), ours.checkEOF());
            assertEquals(0, ours.remaining());
        }
    }

    @Test
    void nanIsCanonicalizedLikeDataOutput() throws IOException{
        float payload = Float.intBitsToFloat(0x7FA00001);
        double wide = Double.longBitsToDouble(0x7FF4000000000001L);
        var ours = new Writes();
        ours.f(payload);
        ours.d(wide);
        var bytes = new ByteArrayOutputStream();
        var theirs = new arc.util.io.Writes(new DataOutputStream(bytes));
        theirs.f(payload);
        theirs.d(wide);
        assertArrayEquals(bytes.toByteArray(), ours.toByteArray());
        assertEquals(Float.floatToIntBits(Float.NaN), new Reads(ours.toByteArray()).i());
    }

    @Test
    void stringsOverTheLimitFailBeforeWriting(){
        var ours = new Writes();
        ours.str("x".repeat(65535));
        int size = ours.size();
        var error = assertThrows(UncheckedIOException.class, () -> ours.str("é".repeat(32768)));
        assertInstanceOf(UTFDataFormatException.class, error.getCause());
        assertEquals(size, ours.size());
        assertThrows(UTFDataFormatException.class, () -> new DataOutputStream(new ByteArrayOutputStream()).writeUTF("é".repeat(32768)));
        assertEquals("x".repeat(65535), new Reads(ours.toByteArray()).str());
    }

    @Test
    void edgeCasesMatchArc() throws IOException{
        byte[] bytes = {0, 5, 'a', 'b', 'c', 'd', 'e', 1, 2};
        var ours = new Reads(bytes);
        var theirs = new arc.util.io.Reads(new DataInputStream(new ByteArrayInputStream(bytes)));
        var mine = assertThrows(StringTooLongException.class, () -> ours.str(3));
        var arcs = assertThrows(RuntimeException.class, () -> theirs.str(3));
        assertEquals(arcs.getMessage(), mine.getMessage());
        assertEquals(5, mine.length());
        assertEquals(3, mine.limit());
        assertEquals(2, ours.position(), "the string's bytes are not read");
        ours.skip(-4);
        ours.skip(100);
        assertEquals(-1, ours.checkEOF());
        assertThrows(UncheckedIOException.class, ours::i);
        var malformed = new Reads(new byte[]{0, 2, (byte)0xC3, 0x41});
        assertInstanceOf(UTFDataFormatException.class, assertThrows(UncheckedIOException.class, malformed::str).getCause());
        assertThrows(UTFDataFormatException.class, () -> new DataInputStream(new ByteArrayInputStream(new byte[]{0, 2, (byte)0xC3, 0x41})).readUTF());
    }

    static void write(SplittableRandom random, Writes ours, arc.util.io.Writes theirs){
        writeKind(random.nextInt(10), random, ours, theirs);
    }

    static void writeKind(int kind, SplittableRandom random, Writes ours, arc.util.io.Writes theirs){
        switch(kind){
            case 0 -> {
                long v = random.nextLong();
                ours.l(v);
                if(theirs != null) theirs.l(v);
            }
            case 1 -> {
                int v = random.nextInt();
                ours.i(v);
                if(theirs != null) theirs.i(v);
            }
            case 2 -> {
                int v = random.nextInt();
                ours.b(v);
                if(theirs != null) theirs.b(v);
            }
            case 3 -> {
                boolean v = random.nextBoolean();
                ours.bool(v);
                if(theirs != null) theirs.bool(v);
            }
            case 4 -> {
                int v = random.nextInt();
                ours.s(v);
                if(theirs != null) theirs.s(v);
            }
            case 5 -> {
                float v = random.nextInt(4) == 0 ? Float.intBitsToFloat(0x7F800000 | random.nextInt(1, 1 << 23)) : Float.intBitsToFloat(random.nextInt());
                ours.f(v);
                if(theirs != null) theirs.f(v);
            }
            case 6 -> {
                double v = Double.longBitsToDouble(random.nextLong());
                ours.d(v);
                if(theirs != null) theirs.d(v);
            }
            case 7 -> {
                String v = STRINGS[random.nextInt(STRINGS.length)] + (char)random.nextInt(0, 0x10000);
                ours.str(v);
                if(theirs != null) theirs.str(v);
            }
            default -> {
                byte[] v = new byte[3];
                random.nextBytes(v);
                ours.b(v);
                if(theirs != null) theirs.b(v);
            }
        }
    }
}
