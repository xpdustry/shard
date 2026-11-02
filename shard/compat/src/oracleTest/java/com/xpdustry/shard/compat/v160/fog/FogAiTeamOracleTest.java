package com.xpdustry.shard.compat.v160.fog;

import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.Players;
import mindustry.game.*;
import mindustry.gen.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * W4.3's AI-only team ({@code Team.isOnlyAI}: AI, with no player), compared as {@link FogOracleTest} compares, with the
 * {@code static-fog-data} chunk and a restore of Shard's checkpoint halfway through. Its own class from
 * {@link FogGapsOracleTest}: run after another fixture in one JVM, vanilla's standing dagger drifted at frame 10.
 */
class FogAiTeamOracleTest extends FogFixture{
    final VanillaFog fog = new VanillaFog();
    static final int RUN = 1200, RESTORE_AT = 600;

    private void frame(int frame){
        Patrols.frame = frame;
        Patrols.VANILLA.clear();
        Patrols.SHARD.clear();
        fog.before();
        Vanilla.frame();
        fog.after();
        shard.tick();
        compare("frame " + (frame + 1));
    }

    private void schedule(int frame){
        String at = "frame " + (frame + 1);
        if(frame % STREAM_EVERY == 0) keepStreams(at);
        if(frame % SNAPSHOT_EVERY == 0) sameSnapshots(at);
        if(frame + 1 == RESTORE_AT){
            sameChunk(at);
            restoreShard();
            compare(at + ", restored");
        }
    }

    /**
     * Waves on, so crux is an AI team; with no crux player it is only AI and has no fog of its own, and its radar,
     * placed then (frame 50), discovers nothing: its {@code TileChangeEvent} is skipped. (A fog source standing at the
     * first fog update would give any team fog: {@code pushStaticBlocks} makes data for every team with one,
     * FogControl.java:155, and Shard does the same; so crux's radar comes after.) A crux player joins at frame 300, so
     * crux gets fog (still seeing everything, as an AI team does), and a second crux radar placed at frame 450 discovers
     * its corner; the player leaves at frame 800. Malis, only AI throughout, gets fog data that way: a radar standing
     * before the first update; a second malis radar, placed at frame 50, discovers nothing when placed
     * ({@code TileChangeEvent} skips a team that is only AI, FogControl.java:65), and only later as its radius grows
     * ({@code forceUpdate}, which asks for fog data alone, FogControl.java:177). Sharded, a player's team, has its radar and a dagger; a crux
     * dagger patrols into its view.
     */
    @Test
    void anAiTeamHasFogOnlyWhileAPlayerIsOnIt(){
        worlds();
        state.rules.waves = true;
        state.rules.waveTimer = false;
        com.xpdustry.shard.sim.rules.Rules rules = shard.part(WorldRules.class).rules;
        rules.waves = true;
        rules.waveTimer = false;
        place("power-source", 6, 25, Team.sharded);
        place("radar", 6, 24, Team.sharded);
        patrol("dagger", Team.sharded, 12, 24, 12, 24, 10_000, 60f);
        patrol("dagger", Team.crux, 80, 30, 30, 30, 450, 60f);
        place("power-source", 60, 3, Team.malis);
        place("radar", 60, 2, Team.malis);
        player(Team.sharded);

        boolean fogWhileAlone = false, fogWithPlayer = false, malisSkipped = false;
        for(int frame = 0; frame < RUN; frame++){
            if(frame == 50){
                place("power-source", 60, 45, Team.malis);
                place("radar", 60, 44, Team.malis);
                place("power-source", 82, 25, Team.crux);
                place("radar", 82, 24, Team.crux);
            }
            if(frame == 300) player(Team.crux);
            if(frame == 450){
                place("power-source", 90, 3, Team.crux);
                place("radar", 90, 2, Team.crux);
            }
            if(frame == 800) leave(Team.crux);
            frame(frame);
            if(frame == 50) malisSkipped = !VanillaFog.discovered(Team.malis).get(60 + 44 * WIDTH);
            boolean cruxFog = VanillaFog.visible(Team.crux) != null;
            if(frame < 300) fogWhileAlone |= cruxFog;
            if(frame > 310 && frame < 800) fogWithPlayer |= cruxFog;
            schedule(frame);
        }
        sameChunk("the end");
        sameStreams();
        assertFalse(fogWhileAlone, "crux had no fog while only AI");
        assertTrue(fogWithPlayer, "crux had fog once a player joined it");
        BitSet discovered = VanillaFog.discovered(Team.crux);
        assertTrue(discovered != null && discovered.get(90 + 2 * WIDTH), "the radar placed with a player on crux discovered its corner");
        BitSet malis = VanillaFog.discovered(Team.malis);
        assertTrue(malis != null && malis.get(60 + 2 * WIDTH), "malis's radar standing at the first update gave it fog data and discovered");
        assertTrue(malisSkipped, "malis's radar placed in play, while only AI, didn't discover its own tile when placed");
        assertTrue(malis.get(60 + 44 * WIDTH), "it did once its radius grew (Radar's forceUpdate checks only for fog data)");
    }

    /** The team's player leaves on both sides ({@code player.remove()}). */
    private void leave(Team team){
        Player vanilla = players.remove(team);
        vanilla.remove();
        Vanilla.runPosted();
        var list = Players.of(shard).list();
        for(int i = 0; i < list.size(); i++){
            if(list.get(i).id() == vanilla.id){
                list.get(i).remove();
                break;
            }
        }
    }
}
