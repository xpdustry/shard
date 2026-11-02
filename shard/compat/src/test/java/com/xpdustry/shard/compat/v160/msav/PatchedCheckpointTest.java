package com.xpdustry.shard.compat.v160.msav;

import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.content.patch.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.testkit.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W6.2: a world of a map's patched content checkpoints and restores tick for tick. Maze with a data patch (the dagger's
 * and the flare's health), hosted in survival with waves every two seconds, is checkpointed after 300 ticks; the
 * checkpoint restores on the content a fresh process rebuilds from the map file alone (its patches applied anew:
 * what a restarted server's {@code Host.prepare} does before it resumes), and both worlds then run 600 ticks with equal hashes after every tick and
 * equal checkpoints at the end. By hash alone, the content resolves once remembered, and fails naming it before.
 */
class PatchedCheckpointTest{
    static final String PATCH = "{\"name\": \"tough\", \"unit.dagger.health\": 260, \"unit.flare.health\": 140}";
    static final BuildingCodecs CODECS = BuildingCodecs.builtIn();
    static final MsavReader READER = new MsavReader(Worlds.EPOCH);

    static byte[] patchedMaze(){
        var loader = new MapLoader(Worlds.EPOCH, CODECS);
        SaveFile save = READER.read(BundledMaps.read("maze"));
        MapLoader.Loaded loaded = loader.loadMap(save, Worlds.config(0), loader.hostRules(save, Gamemode.SURVIVAL));
        loaded.state().patches = new DataAssets(List.of(new DataAsset.PatchAsset("patch-tough.json", PATCH))).write(true);
        WorldStreamSource source = new WorldProjection(Worlds.EPOCH, CODECS).capture(loaded.world(), loaded.state(), PlayerState.fresh(0, "shard", 1));
        return MsavWriter.write(MsavWriter.meta(source, loaded.state().tags, 1, false, 0L), source);
    }

    @Test
    void aPatchedWorldRestoresTickForTick(){
        byte[] file = patchedMaze();
        var loader = new MapLoader(Worlds.EPOCH, CODECS);
        SaveFile save = READER.read(file);
        MapLoader.Loaded loaded = loader.loadMap(save, Worlds.config(7), loader.hostRules(save, Gamemode.SURVIVAL));
        MapLoader.play(loaded);
        World live = loaded.world();
        Epoch patched = (Epoch)live.content();
        assertTrue(patched.isPatched());
        assertEquals(260f, patched.unit("dagger").fields().f("health"));
        assertEquals(150f, Epoch.vanilla().unit("dagger").fields().f("health"), "vanilla's dagger is untouched");
        Rules rules = live.part(WorldRules.class).rules;
        rules.waves = rules.waveTimer = true;
        rules.waveSpacing = 120f;
        // the countdown play() started runs from the map's own spacing: the first wave comes in half a second instead
        com.xpdustry.shard.sim.waves.WaveState.of(live).wavetime = 30f;
        for(int i = 0; i < 300; i++) live.tick();
        byte[] checkpoint = Checkpoint.capture(live).encode(Compression.FAST);
        assertEquals(patched.hash(), Checkpoint.header(checkpoint).content(), "the checkpoint names the world's own content");

        // by hash alone: nothing remembers it yet, as in a process that hasn't loaded the map
        var missing = assertThrows(CheckpointException.class, () -> Checkpoint.decode(checkpoint).config());
        assertTrue(missing.getMessage().contains(patched.hash()), missing.getMessage());

        // in this process, MapLoader.contentOf gives the map's cached epoch (DataPatcher.of); a restarted process
        // applies the file's patches anew (DataPatcher.apply): another instance, with the same hash
        assertSame(patched, new MapLoader(Worlds.EPOCH, CODECS).contentOf(READER.read(file)), "one epoch per map in a process");
        Epoch rebuilt = DataPatcher.apply(Worlds.EPOCH, new MapLoader(Worlds.EPOCH, CODECS).filePatches(READER.read(file)).assets().patches(), "serpulo").epoch();
        assertNotSame(patched, rebuilt);
        assertEquals(patched.hash(), rebuilt.hash());
        WorldConfig c = live.config();
        World restored = Checkpoint.decode(checkpoint).restore(new WorldConfig(rebuilt, c.width(), c.height(), c.seed(), c.systems(), c.kinds(),
            c.inboxCapacity(), c.journal()));
        assertSame(rebuilt, restored.content());
        assertEquals(live.hash(), restored.hash(), "restored as checkpointed");
        int units = 0;
        float daggers = 0;
        for(int i = 1; i <= 600; i++){
            live.tick();
            restored.tick();
            WorldHash expected = live.hash(), actual = restored.hash();
            int tick = i;
            assertEquals(expected, actual, () -> "diverged " + tick + " ticks after the restore in " + expected.firstDifference(actual).orElseThrow());
            units = Math.max(units, live.units().size());
            for(int u = 0; u < restored.units().size(); u++){
                var unit = restored.units().get(u);
                if(unit.type().name().equals("dagger")) daggers = unit.maxHealth;
            }
        }
        assertTrue(units > 0, "the waves came: " + units + " units at most");
        assertEquals(260f, daggers, "the restored world's daggers have the patched health");
        assertArrayEquals(Checkpoint.capture(live).encode(Compression.FAST), Checkpoint.capture(restored).encode(Compression.FAST),
            "equal checkpoints after 600 ticks");

        ContentSource.remember(rebuilt);
        assertSame(rebuilt, Checkpoint.decode(checkpoint).config().content(), "resolved by hash once remembered");
    }
}
