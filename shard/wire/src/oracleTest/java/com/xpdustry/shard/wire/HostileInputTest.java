package com.xpdustry.shard.wire;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.wire.ServerDecode.*;
import com.xpdustry.shard.wire.v160_7.*;
import mindustry.io.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.io.*;
import java.util.function.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hostile client input: vanilla's server and Shard must give the same verdict, and consume the same bytes when they
 * accept. The 17 {@code tileConfig} bodies of note 25 §7 probe the string, array and nesting limits, malformed UTF,
 * truncation and unknown tags; the {@code readObject} cases check NUL and astral characters on both read paths.
 */
class HostileInputTest{
    static final VanillaContext context = new VanillaContext();

    @BeforeAll
    static void world(){
        //emptyWorld(w, h) reads Blocks.stone before it boots vanilla
        Vanilla.boot();
        Vanilla.emptyWorld(16, 16);
    }

    /** A client's tileConfig body: the building's position, then a writeObject value. */
    static byte[] tileConfig(Consumer<Writes> value){
        Writes write = new Writes();
        write.i(Point2.pack(3, 3));
        value.accept(write);
        return write.toByteArray();
    }

    static Arguments tileConfig(String name, Consumer<Writes> value){
        return Arguments.of(name, tileConfig(value));
    }

    static void string(Writes write, String value){
        write.b(WireObjects.STRING);
        write.b(1);
        write.str(value);
    }

    static Stream<Arguments> tileConfigs(){
        return Stream.of(
            tileConfig("string of 1200 ASCII bytes", w -> string(w, "a".repeat(1200))),
            tileConfig("string of 1201 ASCII bytes", w -> string(w, "a".repeat(1201))),
            tileConfig("600 x é, 1200 bytes", w -> string(w, "é".repeat(600))),
            tileConfig("700 x é, 1400 bytes in 700 chars", w -> string(w, "é".repeat(700))),
            tileConfig("malformed C0 41", w -> {
                w.b(WireObjects.STRING);
                w.b(1);
                w.s(2);
                w.b(0xC0);
                w.b(0x41);
            }),
            tileConfig("string cut short", w -> {
                w.b(WireObjects.STRING);
                w.b(1);
                w.s(10);
                w.b('a');
            }),
            tileConfig("Object[] of 1000 nulls", w -> {
                w.b(WireObjects.OBJECT_ARRAY);
                w.i(1000);
                for(int i = 0; i < 1000; i++) w.b(0);
            }),
            tileConfig("Object[] of 1001 nulls", w -> {
                w.b(WireObjects.OBJECT_ARRAY);
                w.i(1001);
                for(int i = 0; i < 1001; i++) w.b(0);
            }),
            tileConfig("Object[] in an Object[]", w -> {
                w.b(WireObjects.OBJECT_ARRAY);
                w.i(1);
                w.b(WireObjects.OBJECT_ARRAY);
                w.i(0);
            }),
            tileConfig("IntSeq of 1000", w -> {
                w.b(WireObjects.INT_SEQ);
                w.s(1000);
                for(int i = 0; i < 1000; i++) w.i(i);
            }),
            tileConfig("IntSeq of 1001", w -> {
                w.b(WireObjects.INT_SEQ);
                w.s(1001);
                for(int i = 0; i < 1001; i++) w.i(i);
            }),
            tileConfig("IntSeq of -1", w -> {
                w.b(WireObjects.INT_SEQ);
                w.s(-1);
            }),
            tileConfig("byte[] of 40000", w -> {
                w.b(WireObjects.BYTES);
                w.i(40_000);
                w.b(new byte[40_000]);
            }),
            tileConfig("byte[] of 40001", w -> {
                w.b(WireObjects.BYTES);
                w.i(40_001);
                w.b(new byte[40_001]);
            }),
            tileConfig("Vec2[] of 1001", w -> {
                w.b(WireObjects.VEC2_ARRAY);
                w.s(1001);
                for(int i = 0; i < 2002; i++) w.f(i);
            }),
            //vanilla's writer refuses this one, so Shard's needn't write it back
            tileConfig("int[] of 5000, which has no limit when read", w -> {
                w.b(WireObjects.INT_ARRAY);
                w.s(5000);
                for(int i = 0; i < 5000; i++) w.i(i);
            }),
            tileConfig("unknown tag 24", w -> w.b(24))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tileConfigs")
    void tileConfigVerdicts(String name, byte[] body){
        Verdict vanilla = ServerDecode.vanilla(Packets.TILE_CONFIG, body), shard = ServerDecode.shard(Packets.TILE_CONFIG, body, context);
        assertTrue(vanilla.same(shard), () -> name + ": vanilla " + vanilla + ", Shard " + shard);
        if(shard.accepted() && !name.startsWith("int[]")){
            //an accepted body re-encodes to the same bytes
            var config = (Packets.TileConfig)Packets.read(Packets.TILE_CONFIG, new Reads(body), false, context);
            Writes write = new Writes();
            config.write(write, false, context);
            assertArrayEquals(body, write.toByteArray(), name);
        }
    }

    @Test
    void vanillaRejectsElevenOfThe17(){
        //accepted: 1200 bytes, 600 x é, Object[1000], IntSeq 1000, byte[40000] and int[5000]; the rest go over a limit
        long rejected = tileConfigs().filter(a -> !ServerDecode.vanilla(Packets.TILE_CONFIG, (byte[])a.get()[1]).accepted()).count();
        assertEquals(17, tileConfigs().count());
        assertEquals(11, rejected);
    }

    //region readObject: NUL and astral characters on both paths

    /** A tag-4 string with these raw bytes after its u16 length. */
    static byte[] rawString(int... bytes){
        Writes write = new Writes();
        write.b(WireObjects.STRING);
        write.b(1);
        write.s(bytes.length);
        for(int b : bytes) write.b(b);
        return write.toByteArray();
    }

    static byte[] utfString(String value){
        Writes write = new Writes();
        string(write, value);
        return write.toByteArray();
    }

    static Stream<Arguments> objects(){
        return Stream.of(
            Arguments.of("NUL as C0 80", utfString("a\u0000b")),
            Arguments.of("NUL as a raw 00 byte", rawString('a', 0, 'b')),
            Arguments.of("astral U+1F438 as two 3-byte surrogates", utfString("\uD83D\uDC38")),
            Arguments.of("astral U+1F438 as 4-byte UTF-8", rawString(0xF0, 0x9F, 0x90, 0xB8)),
            Arguments.of("lone high surrogate", rawString(0xED, 0xA0, 0xBD)),
            Arguments.of("200 astral characters, 1200 bytes", utfString("\uD83D\uDC38".repeat(200))),
            Arguments.of("201 astral characters, 1206 bytes", utfString("\uD83D\uDC38".repeat(201))),
            Arguments.of("600 NULs, 1200 bytes", utfString("\u0000".repeat(600))),
            Arguments.of("601 NULs, 1202 bytes", utfString("\u0000".repeat(601))),
            Arguments.of("1201 raw 00 bytes", rawString(new int[1201])),
            Arguments.of("65535 bytes", utfString("\u0000".repeat(32767) + "a")),
            Arguments.of("absent string", new byte[]{WireObjects.STRING, 0}),
            Arguments.of("tech node of an item", new byte[]{WireObjects.TECH_NODE, 0, 0, 0}),
            Arguments.of("tech node of a bullet", new byte[]{WireObjects.TECH_NODE, 3, 0, 0}),
            Arguments.of("tech node of no content", new byte[]{WireObjects.TECH_NODE, 0, 0x7F, 0}),
            Arguments.of("content type 18", new byte[]{WireObjects.CONTENT, 18, 0, 0}),
            Arguments.of("LAccess 77", new byte[]{WireObjects.LACCESS, 0, 77}),
            Arguments.of("IntSeq of 201", intSeq(201)),
            Arguments.of("legacy tag 15", new byte[]{WireObjects.LEGACY, 9})
        );
    }

    static byte[] intSeq(int length){
        Writes write = new Writes();
        write.b(WireObjects.INT_SEQ);
        write.s(length);
        for(int i = 0; i < length; i++) write.i(i);
        return write.toByteArray();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("objects")
    void readObjectSafeAsVanilla(String name, byte[] bytes){
        compare(name, bytes, true);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("objects")
    void readObjectAsVanilla(String name, byte[] bytes){
        compare(name, bytes, false);
    }

    static void compare(String name, byte[] bytes, boolean safe){
        var input = new ByteArrayInputStream(bytes);
        Object vanilla;
        try{
            var read = new arc.util.io.Reads(new DataInputStream(input));
            vanilla = safe ? TypeIO.readObjectSafe(read) : TypeIO.readObject(read);
        }catch(Throwable t){
            vanilla = t;
        }
        int vanillaConsumed = bytes.length - input.available();

        Reads read = new Reads(bytes);
        Object shard;
        try{
            shard = safe ? WireObjects.readObjectSafe(read, context) : WireObjects.readObject(read, context);
        }catch(WireException e){
            shard = e;
        }
        String path = safe ? "readObjectSafe" : "readObject";
        Object v = vanilla, s = shard;
        assertEquals(v instanceof Throwable, s instanceof Throwable, () -> name + " (" + path + "): vanilla " + v + ", Shard " + s);
        if(!(v instanceof Throwable)){
            assertEquals(vanillaConsumed, read.position(), name + " (" + path + "): consumed");
            if(v instanceof String || v == null) assertEquals(v, s, name + " (" + path + ")");
        }
    }

    @Test
    void stringsDecodeAlike(){
        //C0 80 and a raw 00 both decode to U+0000; surrogates decode to the astral character
        assertEquals("a\u0000b", WireObjects.readObjectSafe(new Reads(rawString('a', 0, 'b')), context));
        assertEquals("a\u0000b", WireObjects.readObjectSafe(new Reads(utfString("a\u0000b")), context));
        assertEquals("\uD83D\uDC38", WireObjects.readObject(new Reads(utfString("\uD83D\uDC38")), context));
        assertEquals(6, utfString("\uD83D\uDC38").length - 4);
    }

    //endregion
    //region entity casts

    static byte[] adminRequest(int other){
        Writes write = new Writes();
        write.i(other);
        write.b(0);
        WireObjects.writeObjectSafe(write, null);
        return write.toByteArray();
    }

    @Test
    void adminRequestTargetsMustBePlayers(){
        //vanilla casts the read entity to Player: a unit's id fails, an unknown id is a null player
        var player = mindustry.gen.Player.create();
        player.add();
        var unit = mindustry.content.UnitTypes.dagger.spawn(mindustry.game.Team.sharded, 40f, 40f);
        for(int other : new int[]{player.id, unit.id, -1, 123_456}){
            byte[] body = adminRequest(other);
            Verdict vanilla = ServerDecode.vanilla(Packets.ADMIN_REQUEST, body), shard = ServerDecode.shard(Packets.ADMIN_REQUEST, body, context);
            assertTrue(vanilla.same(shard), () -> "other " + other + ": vanilla " + vanilla + ", Shard " + shard);
        }
        assertFalse(ServerDecode.vanilla(Packets.ADMIN_REQUEST, adminRequest(unit.id)).accepted());
    }

    //endregion
    //region MenuResult: 500 entries, 500-byte result, 1,000-byte strings and 6,000 characters in all

    /** A client's menuBuilderChoose body with {@code entries} string entries of one-character keys, uncapped. */
    static byte[] menuBuilderChoose(String result, int entries, String value){
        Writes write = new Writes();
        write.i(9);
        write.l(7);
        Codecs.writeString(write, result);
        write.s(entries);
        for(int i = 0; i < entries; i++){
            Codecs.writeUtf(write, Integer.toString(i % 36, 36));
            write.b(2);
            Codecs.writeUtf(write, value);
        }
        return write.toByteArray();
    }

    static Stream<Arguments> menuResults(){
        return Stream.of(
            Arguments.of("a 500-byte result", menuBuilderChoose("a".repeat(500), 0, "")),
            Arguments.of("a 501-byte result", menuBuilderChoose("a".repeat(501), 0, "")),
            Arguments.of("250 x \u00E9 result, 500 bytes", menuBuilderChoose("\u00E9".repeat(250), 0, "")),
            Arguments.of("a 1001-byte value", menuBuilderChoose(null, 1, "a".repeat(1001))),
            Arguments.of("500 entries", menuBuilderChoose(null, 500, "")),
            Arguments.of("501 entries", menuBuilderChoose(null, 501, "")),
            Arguments.of("5 ASCII entries of 1001 characters", menuBuilderChoose(null, 5, "a".repeat(1000))),
            Arguments.of("6 ASCII entries of 1001 characters", menuBuilderChoose(null, 6, "a".repeat(1000))),
            Arguments.of("10 x 500 \u00E9 entries", menuBuilderChoose(null, 10, "\u00E9".repeat(500))),
            Arguments.of("11 x 500 \u00E9 entries", menuBuilderChoose(null, 11, "\u00E9".repeat(500))),
            Arguments.of("11 entries that spend the budget exactly", menuBuilderChoose("a".repeat(500), 11, "a".repeat(499))),
            Arguments.of("a 12th key after the budget is spent", menuBuilderChoose("a".repeat(500), 12, "a".repeat(499)))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("menuResults")
    void menuResultVerdicts(String name, byte[] body){
        Verdict vanilla = ServerDecode.vanilla(Packets.MENU_BUILDER_CHOOSE, body), shard = ServerDecode.shard(Packets.MENU_BUILDER_CHOOSE, body, context);
        assertTrue(vanilla.same(shard), () -> name + ": vanilla " + vanilla + ", Shard " + shard);
    }

    //endregion
}
