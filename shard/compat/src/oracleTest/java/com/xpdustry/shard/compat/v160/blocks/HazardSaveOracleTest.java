package com.xpdustry.shard.compat.v160.blocks;

import arc.math.*;
import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.puddle.Puddles;
import com.xpdustry.shard.sim.world.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP61 with WP22): fires and puddles through a save. Both worlds burn and spill for {@value #BEFORE} frames
 * (compared every frame), then vanilla saves; vanilla loads its save as the server's {@code load} command does, and Shard
 * loads the same bytes ({@link MapLoader}). After the load the fires and puddles are alive on both sides with the same
 * ids, state, tile maps, random draws and entity ids (vanilla's frees of the world it had before are flushed and
 * its pools emptied first, since they are one per JVM); the world stream's entity chunks ({@link WorldProjection}) are
 * the chunks vanilla's next save writes, in vanilla's order and byte for byte; and the worlds go on alike for
 * {@value #AFTER} frames.
 */
class HazardSaveOracleTest{
    static final int SIZE = 32, BEFORE = 90, AFTER = 150;
    static final MsavReader READER = new MsavReader(Worlds.EPOCH);
    static final MapLoader LOADER = new MapLoader(Worlds.EPOCH, BuildingCodecs.builtIn());

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    @Test
    void savedFiresAndPuddlesComeToLife(){
        Sim sim = Sim.empty(SIZE, SIZE);
        World shard = new World(WorldConfig.of(HazardStates.EPOCH, SIZE, SIZE, 0).withKinds(KindRegistry.builtIn().withPassthrough()));
        shard.buildings().beginLoad();
        for(int pos = 0; pos < shard.tiles().size(); pos++) shard.tiles().floor(pos, HazardStates.EPOCH.block("stone").id());
        shard.buildings().endLoad(false);
        HazardPools.empty();
        PuddleOracleTest.place(sim, shard, "container", 10, 10, 0);
        sim.build(10, 10).items.add(content.item("coal"), 200);
        shard.buildings().at(10, 10).items.add(HazardStates.EPOCH.item("coal").id(), 200);
        for(int x = 4; x <= 8; x++) PuddleOracleTest.place(sim, shard, "copper-wall", x, 14, 0);
        for(int frame = 1; frame <= BEFORE; frame++){
            if(frame == 1){
                FireOracleTest.ignite(shard, 10, 10);
                FireOracleTest.ignite(shard, 6, 14);
            }
            if(frame <= 40){
                PuddleOracleTest.deposit(shard, 20, 20, "water", 12f);
                PuddleOracleTest.deposit(shard, 24, 8, "oil", 12f);
            }
            if(frame == 30) PuddleOracleTest.deposit(shard, 24, 8, "slag", 20f);
            sim.step(1);
            shard.tick();
            HazardStates.same("frame " + frame, shard);
        }
        int fires = 0, puddles = 0;
        for(var entity : Groups.all){
            if(entity instanceof Fire) fires++;
            if(entity instanceof Puddle) puddles++;
        }
        assertTrue(fires > 2 && puddles > 4, "the save holds fires and puddles: " + fires + ", " + puddles);

        // vanilla's save of this world, loaded by both, each from a generator seeded with 0 and fresh ids and pools
        var file = new ByteArrayOutputStream();
        SaveIO.write(file, new SaveOptions());
        Mathf.rand.setSeed(0);
        HazardStates.lastId(0);
        HazardPools.empty();
        SaveIO.load(new ByteArrayInputStream(file.toByteArray()), world.context);
        state.set(mindustry.core.GameState.State.playing);
        Vanilla.runPosted();
        // the load's Groups.clear removed the old world's fires, puddles and bullets, whose resets would draw on the next
        // frame, and whose objects the next ones would reuse: history of vanilla's JVM, not of the file, which a fresh
        // Shard world can't have
        HazardPools.empty();
        int ids = HazardStates.lastId();
        Bullet.create();
        arc.util.pooling.Pools.get(Bullet.class, () -> null).clear();
        HazardStates.lastId(ids);
        SaveFile save = READER.read(file.toByteArray());
        MapLoader.Loaded loaded = LOADER.load(save, Worlds.config(0), LOADER.saveRules(save));
        World restored = loaded.world();
        assertNull(loaded.state().unknownDraws, "draws Shard doesn't count");
        HazardStates.same("loaded", restored);
        assertEquals(fires + puddles, HazardStates.sameBytes("loaded", restored), "every fire and puddle came to life");

        // the stream's entities are vanilla's next save's
        var again = new ByteArrayOutputStream();
        SaveIO.write(again, new SaveOptions());
        var expected = chunks(READER.read(again.toByteArray()).entities());
        var actual = chunks(new WorldProjection(Worlds.EPOCH, BuildingCodecs.builtIn()).entities(restored, loaded.state(),
            save.header().mapping(Worlds.EPOCH)));
        assertEquals(expected, actual, "the stream's entity chunks");

        for(int frame = 1; frame <= AFTER; frame++){
            sim.step(1);
            restored.tick();
            HazardStates.same("frame " + frame + " after the load", restored);
        }
        assertTrue(Puddles.of(restored) != null);
    }

    static List<String> chunks(List<MapState.EntityChunk> chunks){
        var out = new ArrayList<String>();
        for(MapState.EntityChunk chunk : chunks) out.add(chunk.classId() + " #" + chunk.id() + " " + HexFormat.of().formatHex(chunk.body()));
        return out;
    }
}
