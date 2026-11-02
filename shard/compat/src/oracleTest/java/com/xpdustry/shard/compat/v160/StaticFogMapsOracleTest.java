package com.xpdustry.shard.compat.v160;

import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.oracle.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.util.*;

import static com.xpdustry.shard.compat.v160.OracleWorlds.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * W5.4's recheck of the maps vanilla writes static fog for ({@code FogControl}'s {@code static-fog-data} chunk), now
 * that W4.3 writes it from live fog: {@code WorldStreamOracleTest} compares custom chunks by name, this compares their
 * bytes. Every map Shard hosts: the world stream's custom chunk section is byte-equal to vanilla's at load
 * ({@link OracleWorlds#vanillaLoaded}); the maps with static fog are also hosted and played on both sides
 * ({@link Golden#worldStreamAtLoad}) and compared again.
 */
class StaticFogMapsOracleTest{
    static final Set<String> withFog = Collections.synchronizedSet(new TreeSet<>());

    static List<String> maps(){
        return WorldStreamOracleTest.loadedMaps();
    }

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    @AfterAll
    static void theStaticFogMapsAreIn(){
        System.out.println("maps with static fog: " + withFog.size() + " " + withFog);
        assertTrue(withFog.size() >= 6, "the six Erekir maps with static fog: " + withFog);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("maps")
    void customChunksEqualVanillasAtLoad(String name){
        Hosted shard = shardHost(name);
        byte[] expected = vanillaLoaded(name);
        assertEquals(hex(customChunks(expected)), hex(customChunks(shard.raw())), name);
        boolean fog = names(shard.source()).contains(StaticFog.CHUNK);
        if(!fog) return;
        withFog.add(name);

        // hosted, logic.play() included
        Hosted played = shardHost(name, true);
        byte[] golden = Golden.worldStreamAtLoad(name).raw();
        assertEquals(hex(customChunks(golden)), hex(customChunks(played.raw())), name + " hosted");
    }

    static List<String> names(WorldStreamSource source){
        return source.customChunks().stream().map(MapState.CustomChunk::name).toList();
    }

    /** The custom chunk section's bytes, where vanilla's reader finds it. */
    static byte[] customChunks(byte[] raw){
        Oracle.Section section = Oracle.decodeWorldStream(raw).section("custom chunks");
        assertNotNull(section, "a custom chunk section");
        Vanilla.reset();
        return Arrays.copyOfRange(raw, section.offset(), section.offset() + section.length());
    }

    static String hex(byte[] bytes){
        return HexFormat.of().formatHex(bytes);
    }
}
