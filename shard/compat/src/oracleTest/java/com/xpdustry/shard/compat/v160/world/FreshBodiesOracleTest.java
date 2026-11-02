package com.xpdustry.shard.compat.v160.world;

import com.xpdustry.shard.oracle.*;
import mindustry.game.*;
import mindustry.world.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate for {@link FreshBodies}: its table is vanilla's, block for block. Each block with a building is placed with
 * {@code tile.setBlock} in a fresh world ({@code Golden.building}); its {@code version()} and {@code write()} bytes are
 * the table's line. They don't depend on the rotation, and only {@code landing-pad}'s depends on the generator. On a
 * mismatch the expected table is in {@code compat/build/fresh-bodies.txt}.
 */
class FreshBodiesOracleTest{
    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    static String hex(byte[] bytes){
        return bytes.length == 0 ? "-" : HexFormat.of().formatHex(bytes);
    }

    @Test
    void freshBodiesAreVanillas() throws IOException{
        var lines = new ArrayList<String>();
        var rotated = new ArrayList<String>();
        var seeded = new ArrayList<String>();
        for(Block block : content.blocks()){
            if(!block.hasBuilding()) continue;
            var fresh = Golden.building(block, 0, b -> {});
            assertNull(fresh.error(), block.name);
            lines.add(block.name + " " + fresh.version() + " " + hex(fresh.write()));
            if(!Arrays.equals(fresh.write(), Golden.building(block, 1, b -> {}).write())) rotated.add(block.name);
            Vanilla.emptyWorld(32, 32);
            arc.math.Mathf.rand.setSeed(987654321L);
            Tile tile = world.tile(16, 16);
            tile.setBlock(block, Team.sharded, 0);
            Vanilla.runPosted();
            if(!Arrays.equals(fresh.write(), Golden.building(tile.build).write())) seeded.add(block.name);
        }
        assertEquals(List.of(), rotated, "bodies that depend on the rotation");
        assertEquals(List.of("landing-pad"), seeded, "bodies that depend on the generator");

        String expected = String.join("\n", lines);
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/fresh-bodies.txt"), expected + "\n");
        String table;
        try(InputStream in = FreshBodies.class.getResourceAsStream("fresh-bodies.txt")){
            table = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String actual = String.join("\n", table.lines().filter(line -> !line.startsWith("#")).toList());
        assertEquals(expected, actual, "fresh-bodies.txt");
    }
}
