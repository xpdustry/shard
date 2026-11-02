package com.xpdustry.shard.compat.v160;

import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.testkit.BundledMaps;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.util.*;

import static com.xpdustry.shard.compat.v160.OracleWorlds.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * L3 for the world stream: Shard loads every bundled map it hosts from its .msav and writes the world stream; a vanilla
 * client (the oracle) reads it; the result is compared with what the same client reads from vanilla. The 18 default
 * maps are also hosted and played on both sides.
 */
class WorldStreamOracleTest{
    static List<String> maps(){
        return BundledMaps.DEFAULT;
    }

    /** Every bundled map Shard hosts ({@code GenerationFiltersOracleTest.REFUSED} aside). */
    static List<String> loadedMaps(){
        var maps = new ArrayList<String>();
        for(String path : BundledMaps.all()) if(!GenerationFiltersOracleTest.REFUSED.containsKey(path)) maps.add(path);
        return maps;
    }

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    /** Against vanilla loading the same file as {@code World.loadMap} does, without {@code logic.play()}: no difference at all. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("loadedMaps")
    void streamEqualsVanillasLoadBeforePlay(String name){
        Hosted shard = shardHost(name);
        Decoded actual = decode(shard.raw());
        assertEquals(List.of(), actual.view().warnings(), name);
        Decoded expected = decode(vanillaLoaded(name));
        List<String> differences = differences(expected, actual);
        differences.forEach(line -> System.out.println(name + ": " + line));
        assertEquals(List.of(), differences, name);
    }

    /**
     * Against {@code Golden.worldStreamAtLoad(map)}: vanilla's real {@code host}, {@code logic.play()} included, before
     * any frame, and Shard's ({@link MapLoader#loadMap}, then {@link MapLoader#play}): no difference at all.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("maps")
    void streamEqualsVanillasHost(String name){
        Hosted shard = shardHost(name, true);
        Decoded actual = decode(shard.raw());
        Decoded golden = decode(Golden.worldStreamAtLoad(name).raw());
        List<String> differences = differences(golden, actual);
        differences.forEach(line -> System.out.println(name + " vs golden: " + line));
        assertEquals(List.of(), differences, name);
    }
}
