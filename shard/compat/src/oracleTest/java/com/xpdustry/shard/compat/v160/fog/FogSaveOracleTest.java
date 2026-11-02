package com.xpdustry.shard.compat.v160.fog;

import arc.math.*;
import arc.struct.Seq;
import arc.util.pooling.*;
import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.compat.v160.fixtures.Worlds;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.sync.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.blocks.defense.Radar;
import com.xpdustry.shard.sim.blocks.turret.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.bullet.Bullets;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.fog.FogControl;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.Unit;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.Packet;
import mindustry.entities.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.world.blocks.defense.turrets.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Review 43's fog continuation, in its own class (its own JVM: a vanilla save load leaves vanilla's physics as the
 * loaded world's, which {@link FogOracleTest}'s frame-exact fixtures must not inherit). Pinned as {@link FogOracleTest}
 * is ({@link VanillaFog}).
 */
class FogSaveOracleTest extends FogFixture{
    final VanillaFog fog = new VanillaFog();

    /**
     * Review 43: a vanilla save made mid-redraw, loaded on both sides. Sharded's radar grows and its dagger and flare
     * walk east, crux's walk west; at the first frame from {@value #CONTINUE_SAVE_AT} on where a team's dynamic redraw
     * is due but not drawn yet ({@code FogData.dynamicUpdated}), vanilla saves. Vanilla loads those bytes
     * ({@code SaveIO.load}, its fog reset by the load and its discovered tiles read from {@code static-fog-data}) and
     * Shard loads the same bytes ({@code MapLoader}). The units get their beats back by id and each team its player.
     * Then, on the pinned schedule, at tolerance 0: the static chunk as loaded, and after every frame for
     * {@value #CONTINUE_FRAMES} frames the fog, the units and the random ({@link #compare}); every
     * {@value #SNAPSHOT_EVERY} frames the snapshots and every {@value #STREAM_EVERY} the world streams; the chunk at the end.
     */
    @Test
    void aVanillaSaveMadeMidRedrawLoadsOnBothSides(){
        worlds();
        place("power-source", 6, 25, Team.sharded);
        place("radar", 6, 24, Team.sharded);
        float[][] beats = {{10, 20, 50, 22}, {10, 40, 56, 38}, {86, 30, 44, 28}, {90, 10, 50, 12}};
        Team[] teams = {Team.sharded, Team.sharded, Team.crux, Team.crux};
        String[] types = {"dagger", "flare", "dagger", "flare"};
        for(int i = 0; i < beats.length; i++) patrol(types[i], teams[i], (int)beats[i][0], (int)beats[i][1], (int)beats[i][2], (int)beats[i][3], 100_000, 1f);
        player(Team.sharded);
        player(Team.crux);

        int frame = 0;
        boolean pending = false;
        for(; frame < CONTINUE_SAVE_AT + 120 && !pending; frame++){
            pinnedFrame(frame);
            compare("frame " + (frame + 1));
            pending = frame + 1 >= CONTINUE_SAVE_AT && VanillaFog.redrawPending();
        }
        assertTrue(pending, "a redraw was pending at a frame from " + CONTINUE_SAVE_AT);
        int savedAt = frame;
        int discoveredAtSave = VanillaFog.discovered(Team.sharded).cardinality();

        var bytes = new ByteArrayOutputStream();
        mindustry.io.SaveIO.write(bytes, new mindustry.io.SaveOptions());
        loadBoth(bytes.toByteArray());
        for(int i = 0; i < beats.length; i++){
            int id = shardIds.get(i);
            mindustry.gen.Unit theirs = mindustry.gen.Groups.unit.getByID(id);
            assertNotNull(theirs, "vanilla loaded unit " + id);
            theirs.controller(new Patrols.VanillaPatrol(beats[i][0] * 8f, beats[i][1] * 8f, beats[i][2] * 8f, beats[i][3] * 8f, 100_000, 1f));
            vanillaUnits.set(i, theirs);
            Unit ours = shardUnit(i);
            assertNotNull(ours, "Shard loaded unit " + id);
            ours.controller(new Patrols.ShardPatrol(beats[i][0] * 8f, beats[i][1] * 8f, beats[i][2] * 8f, beats[i][3] * 8f, 100_000, 1f));
        }
        players.clear();
        player(Team.sharded);
        player(Team.crux);
        sameChunk("loaded");

        for(int end = frame + CONTINUE_FRAMES; frame < end; frame++){
            pinnedFrame(frame);
            String at = "frame " + (frame + 1) + ", after the load";
            compare(at);
            if(frame % SNAPSHOT_EVERY == 0) sameSnapshots(at);
            if(frame % STREAM_EVERY == 0) keepStreams(at);
        }
        sameChunk("the end");
        int discoveredAtEnd = VanillaFog.discovered(Team.sharded).cardinality();
        System.out.println("fog save: saved mid-redraw after frame " + savedAt + ", sharded discovered " + discoveredAtSave + " -> " + discoveredAtEnd);
        assertTrue(discoveredAtEnd > discoveredAtSave, "the loaded world discovered more");
        assertTrue(((com.xpdustry.shard.sim.blocks.defense.Radar)shard.buildings().at(6, 24)).fogRadius() > 30f, "the loaded radar grew on");
        sameStreams();
    }

    static final int CONTINUE_SAVE_AT = 300, CONTINUE_FRAMES = 600;
    static final MsavReader READER = new MsavReader(EPOCH);
    static final MapLoader LOADER = new MapLoader(EPOCH, BuildingCodecs.builtIn());

    private void pinnedFrame(int frame){
        Patrols.frame = frame;
        Patrols.VANILLA.clear();
        Patrols.SHARD.clear();
        fog.before();
        Vanilla.frame();
        fog.after();
        shard.tick();
    }

    /** Both sides load {@code save}, as {@code CommandRun.saveAndLoad}: a fresh server's random, id counter and pools. */
    private void loadBoth(byte[] save){
        // both load from a generator seeded with 0, as Shard's loaded world is (Worlds.config(0))
        Mathf.rand.setSeed(0);
        setStatic(EntityGroup.class, "lastId", 0);
        mindustry.io.SaveIO.load(new ByteArrayInputStream(save), world.context);
        state.set(mindustry.core.GameState.State.playing);
        Vanilla.runPosted();
        HazardPools.empty();
        pool.clear();
        setStatic(mindustry.entities.Lightning.class, "lastSeed", 0);
        SaveFile file = READER.read(save);
        shard = LOADER.load(file, Worlds.config(0), LOADER.saveRules(file)).world();
        setStatic(EntityGroup.class, "lastId", shard.ids().peek());
        fog.loaded();
    }
}
