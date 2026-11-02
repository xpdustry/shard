package com.xpdustry.shard.wire;

import com.xpdustry.shard.common.io.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * String limits per codec and direction (ARCHITECTURE.md §6): {@code readObjectSafe} 1,200 encoded bytes,
 * {@code writeUTF} 65,535, MenuResult's mixed byte and character budget; NUL and astral characters on both
 * {@code readObject} paths. {@code HostileInputTest} checks the same bytes against vanilla.
 */
class StringLimitTest{
    static final String FROG = "\uD83D\uDC38";
    static final WireContext context = TestContext.instance;

    /** Tag 4 with a present string, written by {@code writeUTF}. */
    static byte[] tagged(String value){
        Writes write = new Writes();
        write.b(WireObjects.STRING);
        write.b(1);
        write.str(value);
        return write.toByteArray();
    }

    /** Tag 4 with these raw bytes as the string's body. */
    static byte[] raw(int... bytes){
        Writes write = new Writes();
        write.b(WireObjects.STRING);
        write.b(1);
        write.s(bytes.length);
        for(int b : bytes) write.b(b);
        return write.toByteArray();
    }

    static Object safe(byte[] bytes){
        return WireObjects.readObjectSafe(new Reads(bytes), context);
    }

    static Object unsafe(byte[] bytes){
        return WireObjects.readObject(new Reads(bytes), context);
    }

    @Test
    void encodedLengths(){
        assertEquals(1, Codecs.utfLength("a"));
        assertEquals(2, Codecs.utfLength("\u0000"));
        assertEquals(2, Codecs.utfLength("é"));
        assertEquals(3, Codecs.utfLength("日"));
        assertEquals(6, Codecs.utfLength(FROG));
        for(String s : List.of("a", "\u0000", "é", "日", FROG, "a\u0000é日" + FROG)){
            Writes write = new Writes();
            write.str(s);
            assertEquals(2 + Codecs.utfLength(s), write.size(), s);
        }
    }

    @Test
    void nulOnBothPaths(){
        //writeUTF encodes U+0000 as C0 80; a raw 00 byte decodes to it too
        byte[] encoded = tagged("a\u0000b");
        assertArrayEquals(new byte[]{'a', (byte)0xC0, (byte)0x80, 'b'}, Arrays.copyOfRange(encoded, 4, 8));
        for(byte[] bytes : List.of(encoded, raw('a', 0, 'b'))){
            assertEquals("a\u0000b", safe(bytes));
            assertEquals("a\u0000b", unsafe(bytes));
        }
    }

    @Test
    void astralOnBothPaths(){
        //an astral character is two 3-byte surrogates; 4-byte UTF-8 is malformed modified UTF-8
        byte[] encoded = tagged(FROG);
        assertEquals(4 + 6, encoded.length);
        assertEquals(FROG, safe(encoded));
        assertEquals(FROG, unsafe(encoded));
        byte[] utf8 = raw(0xF0, 0x9F, 0x90, 0xB8);
        assertThrows(WireException.class, () -> safe(utf8));
        assertThrows(WireException.class, () -> unsafe(utf8));
        //a lone surrogate is well-formed modified UTF-8
        assertEquals("\uD83D", safe(raw(0xED, 0xA0, 0xBD)));
    }

    @Test
    void safePathCountsEncodedBytes(){
        assertEquals(1200, ((String)safe(tagged("a".repeat(1200)))).length());
        assertThrows(WireException.class, () -> safe(tagged("a".repeat(1201))));
        //600 NULs and 200 astral characters are 1,200 bytes; one more of either is over
        assertEquals(600, ((String)safe(tagged("\u0000".repeat(600)))).length());
        assertThrows(WireException.class, () -> safe(tagged("\u0000".repeat(601))));
        assertEquals(400, ((String)safe(tagged(FROG.repeat(200)))).length());
        assertThrows(WireException.class, () -> safe(tagged(FROG.repeat(201))));
        //raw 00 bytes are one byte each
        assertEquals(1200, ((String)safe(raw(new int[1200]))).length());
        assertThrows(WireException.class, () -> safe(raw(new int[1201])));
    }

    @Test
    void unsafePathTakesTheWholeU16(){
        String longest = "\u0000".repeat(32767) + "a";
        assertEquals(longest, unsafe(tagged(longest)));
        assertEquals(1201, ((String)unsafe(raw(new int[1201]))).length());
    }

    @Test
    void theSafeLimitIsCheckedBeforeDecoding(){
        //a 1,201-byte length is refused even when the body is cut short or malformed
        Writes write = new Writes();
        write.b(WireObjects.STRING);
        write.b(1);
        write.s(1201);
        write.b(0xFF);
        WireException e = assertThrows(WireException.class, () -> safe(write.toByteArray()));
        assertTrue(e.getMessage().contains("1200"), e.getMessage());
    }

    @Test
    void writersRefuseBeforeWriting(){
        Writes write = new Writes();
        assertThrows(WireException.class, () -> Codecs.writeString(write, "a".repeat(65536)));
        assertThrows(WireException.class, () -> Codecs.writeString(write, "日".repeat(21846)));
        assertThrows(WireException.class, () -> WireObjects.writeObject(write, "\u0000".repeat(32768)));
        assertThrows(WireException.class, () -> WireObjects.writeObjectSafe(write, "a".repeat(1201)));
        assertThrows(WireException.class, () -> WireObjects.writeObjectSafe(write, FROG.repeat(201)));
        assertThrows(WireException.class, () -> WireObjects.writeObjectSafe(write, new Object[]{"\u0000".repeat(601)}));
        assertEquals(0, write.size());

        Codecs.writeString(write, "a".repeat(65535));
        assertEquals(1 + 2 + 65535, write.size());
        write.reset();
        Codecs.writeString(write, "日".repeat(21845));
        assertEquals(1 + 2 + 65535, write.size());
        write.reset();
        WireObjects.writeObjectSafe(write, FROG.repeat(200));
        assertEquals(FROG.repeat(200), safe(write.toByteArray()));
    }

    @Test
    void readStringIsUnlimited(){
        Writes write = new Writes();
        Codecs.writeString(write, "日".repeat(21845));
        Codecs.writeString(write, null);
        Reads read = new Reads(write.toByteArray());
        assertEquals("日".repeat(21845), Codecs.readString(read));
        assertNull(Codecs.readString(read));
        //any non-zero presence byte means present
        assertEquals("x", Codecs.readString(new Reads(new byte[]{(byte)0x80, 0, 1, 'x'})));
    }

    //region MenuResult

    /** {@code count} entries with one- or two-character keys. */
    static SequencedMap<String, Object> values(int count, Object value){
        SequencedMap<String, Object> values = new LinkedHashMap<>();
        for(int i = 0; i < count; i++) values.put(Integer.toString(i, 36), value);
        return values;
    }

    static byte[] menuResult(String result, SequencedMap<String, Object> values){
        Writes write = new Writes();
        Codecs.writeMenuResult(write, new Codecs.MenuResult(7, result, values));
        return write.toByteArray();
    }

    @Test
    void menuResultLimits(){
        assertEquals("a".repeat(500), Codecs.readMenuResult(new Reads(menuResult("a".repeat(500), new LinkedHashMap<>()))).result());
        assertThrows(WireException.class, () -> Codecs.readMenuResult(new Reads(menuResult("a".repeat(501), new LinkedHashMap<>()))));
        assertThrows(WireException.class, () -> Codecs.readMenuResult(new Reads(menuResult(null, values(1, "a".repeat(1001))))));
        //over 500 entries is refused by the reader; the writer sends only the first 500
        assertEquals(500, Codecs.readMenuResult(new Reads(menuResult(null, values(501, true)))).values().size());
    }

    @Test
    void menuResultCountsCharactersNotBytes(){
        //quirk: 6,000 characters of budget, but each string's limit is in bytes. One-character keys, 1,000-byte values:
        //an ASCII entry takes 1,001 characters, so the sixth value fails; a two-byte one takes 501, so the eleventh does
        assertEquals(5, Codecs.readMenuResult(new Reads(menuResult(null, values(5, "a".repeat(1000))))).values().size());
        assertThrows(WireException.class, () -> Codecs.readMenuResult(new Reads(menuResult(null, values(6, "a".repeat(1000))))));
        assertEquals(10, Codecs.readMenuResult(new Reads(menuResult(null, values(10, "é".repeat(500))))).values().size());
        assertThrows(WireException.class, () -> Codecs.readMenuResult(new Reads(menuResult(null, values(11, "é".repeat(500))))));
    }

    //endregion
}
