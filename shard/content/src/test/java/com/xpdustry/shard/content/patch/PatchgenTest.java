package com.xpdustry.shard.content.patch;

import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.content.load.*;
import com.xpdustry.shard.content.yaml.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.charset.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Patchgen without vanilla; {@code ContentGatesTest} in {@code :tools:content-extract} applies patches with vanilla's patcher. */
class PatchgenTest{
    static final Epoch vanilla = Epoch.vanilla();

    static Epoch world(String yaml){
        return Epoch.load(Pack.vanilla().with(List.of(new Pack.Source("world.yml", yaml))));
    }

    static List<String> refusals(String yaml){
        var diagnostics = new Diagnostics();
        assertNull(Patchgen.generate(vanilla, world(yaml), diagnostics));
        return diagnostics.list().stream().map(Diagnostic::toString).toList();
    }

    @Test
    void vanillaNeedsNoPatch(){
        Patch patch = Patchgen.of(vanilla, vanilla);
        assertTrue(patch.isEmpty());
        assertEquals("{\"name\":\"shard\"}", patch.json());
    }

    @Test
    void changesBecomeDottedPathsWithDerivedFields(){
        Epoch world = world("""
            - type: block
              id: duo
              overlay: true
              requirements: {copper: 50}
              reload: 15
            - type: bullet
              id: duo/ammoTypes.copper
              overlay: true
              speed: 3
            """);
        Patch patch = Patchgen.of(vanilla, world);
        assertSame(patch, Patchgen.of(vanilla, world), "cached by the two hashes");
        assertEquals(vanilla.hash(), patch.baseHash());
        assertEquals(world.hash(), patch.worldHash());
        // build time follows the requirements, recoil time the reload, range the speed
        assertEquals(List.of("block.duo.buildTime", "block.duo.requirements", "block.duo.reload",
            "block.duo.recoilTime", "block.duo.ammoTypes.copper.range", "block.duo.ammoTypes.copper.speed"), patch.entries());
        assertTrue(patch.json().contains("\"block.duo.requirements\":[\"copper/50\"]"), patch.json());
        assertTrue(patch.json().contains("\"block.duo.ammoTypes.copper.speed\":3.0"), patch.json());
    }

    @Test
    void whatVanillaCantPatchIsRefused(){
        assertTrue(refusals("""
            - type: item
              id: test-ore
              wire: added
              kind: item
            """).getFirst().contains("is new"));
        assertTrue(refusals("""
            - type: block
              id: duo
              overlay: true
              size: 2
            """).stream().anyMatch(e -> e.contains("block.duo.size") && e.contains("@NoPatch")));
        assertTrue(refusals("""
            - type: block
              id: duo
              overlay: true
              ammoTypes: {copper: duo/ammoTypes.graphite}
            """).getFirst().contains("can only name Bullets.* statics"));
        assertTrue(refusals("""
            - type: bullet
              id: duo/ammoTypes.copper
              overlay: true
              hitEffect: {kind: wave-effect, lifetime: 30}
            """).getFirst().contains("client-made id"));
        String refusal = refusals("""
            - type: block
              id: duo
              overlay: true
              health: 300
              ammoTypes: {copper: duo/ammoTypes.graphite}
            """).getFirst();
        assertTrue(refusal.startsWith("world.yml:1:3: can't patch block.duo.ammoTypes.copper"), refusal);
    }

    @Test
    void sectionHasVanillasLayout() throws IOException{
        Patch patch = Patchgen.of(vanilla, world("""
            - type: item
              id: copper
              overlay: true
              hardness: 2
            """));
        var bytes = new ByteArrayOutputStream();
        PatchSection.write(new DataOutputStream(bytes), List.of(Patchgen.of(vanilla, vanilla), patch));
        var in = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(2, in.readInt(), "DataPatcher.patchFormatVersion");
        assertEquals(1, in.readInt(), "empty patches are left out");
        assertEquals(0, in.readByte(), "DataAssetType.patch");
        assertEquals(patch.path(), in.readUTF());
        assertTrue(in.readBoolean(), "embedded");
        byte[] json = new byte[in.readInt()];
        in.readFully(json);
        assertEquals(patch.json(), new String(json, StandardCharsets.UTF_8));
        assertEquals(-1, in.read());
        assertEquals(32, patch.sha256().length);
    }
}
