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
 * Gate (WP73): vanilla's fog, frame by frame, on a fog map with two teams that fight. Sharded holds the west with a
 * radar on a power source, whose fog radius grows to 34 tiles over its first 600 frames, a duo fed copper and then
 * graphite (whose {@code rangeChange} widens its fog radius, the turret refresh) and an alpha, whose {@code fogRadius}
 * is 0, that targets anything within 30 tiles its team sees; a dagger patrols east. Crux holds the east with a duo and
 * patrols west with two daggers and a flare, and places a duo in a far corner at frame 400. Two malis daggers stand still, one in the crux duo's reach. Every unit picks the closest target it may each frame and fires at it
 * ({@link Patrols}). Compared after every frame for {@value #FRAMES} frames, with vanilla's fog on Shard's schedule
 * ({@link VanillaFog}):
 * <ul>
 *   <li>both teams' visible and discovered tiles, and which teams have fog;</li>
 *   <li>every unit's target, position, health and shooting flag; every turret's target, aim, reload and ammo; the live
 *   bullets; the random state and the entity id counter;</li>
 *   <li>every {@value #STREAM_EVERY} frames, the units in a world stream to each team's player
 *   ({@code NetworkIO.writeWorld}'s {@code !u.inFogTo(player.team())}), by id;</li>
 *   <li>every {@value #SNAPSHOT_EVERY} frames, each team's entity snapshots and hidden ids
 *   ({@code writeEntitySnapshotsTeam}), byte for byte;</li>
 *   <li>at frame {@value #SAVE_AT} and at the end, the {@code static-fog-data} save chunk, byte for byte; at
 *   {@value #SAVE_AT} Shard carries on from a checkpoint of its world, encoded and restored.</li>
 * </ul>
 * The fixture asserts that fog did its work: the radar grew, units in the alpha's range were hidden from it, the alpha
 * targeted a unit once its team saw one, snapshots hid units and showed them, and the discovered area grew in play.
 */
class FogOracleTest extends FogFixture{
    final VanillaFog fog = new VanillaFog();

    @Test
    void twoTeamsFightInFogAsInVanilla(){
        worlds();
        // sharded, west
        place("power-source", 6, 25, Team.sharded);
        place("radar", 6, 24, Team.sharded);
        place("duo", 14, 24, Team.sharded);
        items(14, 24, "copper", 12);
        patrol("alpha", Team.sharded, 10, 28, 10, 28, 1000, ALPHA_RANGE);
        patrol("dagger", Team.sharded, 16, 16, 70, 16, 500, 60f);
        // crux, east
        place("duo", 82, 24, Team.crux);
        items(82, 24, "copper", 12);
        patrol("dagger", Team.crux, 80, 30, 24, 30, 450, 60f);
        patrol("dagger", Team.crux, 86, 20, 40, 22, 600, 60f);
        patrol("flare", Team.crux, 90, 40, 12, 36, 350, 40f);
        // malis, standing: one in the crux duo's reach, which kills it, one far off; with no unit moving, only the
        // death redraws malis's fog
        patrol("dagger", Team.malis, 82, 36, 82, 36, 1000, 1f);
        patrol("dagger", Team.malis, 60, 46, 60, 46, 1000, 1f);
        player(Team.sharded);
        player(Team.crux);

        var radar = (Radar)shard.buildings().at(6, 24);
        int hiddenInRange = 0, alphaTargets = 0, hiddenSent = 0, shownSent = 0, refreshes = 0;
        int discoveredAtStart = -1, streamsLeftOut = 0;
        float lastRange = 0f;
        for(int frame = 0; frame < FRAMES; frame++){
            if(frame == 200) items(14, 24, "graphite", 6);
            if(frame % 150 == 0) items(82, 24, "copper", 4);
            if(frame == 900) items(14, 24, "copper", 6);
            // a source placed in play, in a corner no crux unit sees: only its TileChangeEvent discovers it
            if(frame == 400) place("duo", 2, 2, Team.crux);
            Patrols.frame = frame;
            Patrols.VANILLA.clear();
            Patrols.SHARD.clear();
            fog.before();
            Vanilla.frame();
            fog.after();
            shard.tick();
            String at = "frame " + (frame + 1);
            compare(at);
            if(frame == 0) discoveredAtStart = VanillaFog.discovered(Team.sharded).cardinality();

            var duo = (Turret.TurretBuild)world.build(14, 24);
            float range = ((TurretBuild)shard.buildings().at(14, 24)).lastRangeChange;
            if(range != lastRange) refreshes++;
            lastRange = range;
            mindustry.gen.Unit alpha = vanillaUnits.getFirst();
            for(mindustry.gen.Unit unit : vanillaUnits){
                if(unit.team == Team.crux && unit.isAdded() && unit.within(alpha, ALPHA_RANGE) && unit.inFogTo(Team.sharded)) hiddenInRange++;
            }
            if(Patrols.VANILLA.getOrDefault(alpha.id - vanillaBase, "-").startsWith("unit")) alphaTargets++;

            if(frame % STREAM_EVERY == 0) streamsLeftOut += keepStreams(at);
            if(frame % SNAPSHOT_EVERY == 0){
                int[] counts = sameSnapshots(at);
                hiddenSent += counts[0];
                shownSent += counts[1];
            }
            if(frame + 1 == SAVE_AT){
                sameChunk(at);
                restoreShard();
                compare(at + ", restored");
            }
            assertNotNull(duo);
        }
        sameChunk("the end");
        int discoveredAtEnd = VanillaFog.discovered(Team.sharded).cardinality();
        System.out.println("fog: radar " + radar.fogRadius() + ", " + hiddenInRange + " unit-frames hidden in the alpha's range, the alpha"
            + " targeted a unit on " + alphaTargets + " frames, " + hiddenSent + " hidden and " + shownSent + " shown crux units sent,"
            + " " + refreshes + " turret refreshes, " + streamsLeftOut + " units left out of world streams, sharded discovered " + discoveredAtStart + " -> " + discoveredAtEnd + " tiles");
        assertTrue(radar.fogRadius() > 30f, "the radar grew: " + radar.fogRadius());
        assertTrue(hiddenInRange > 0, "fog hid crux units in the alpha's range");
        assertTrue(alphaTargets > 0, "the alpha targeted a unit its team saw");
        assertTrue(hiddenSent > 0, "a snapshot to sharded hid a crux unit");
        assertTrue(shownSent > 0, "a snapshot to sharded showed a crux unit");
        assertTrue(refreshes >= 2, "the duo's ammo changed its fog radius both ways: " + refreshes);
        assertTrue(discoveredAtEnd > discoveredAtStart, "static fog grew in play");
        assertFalse(vanillaUnits.get(5).isAdded(), "the crux duo killed the standing malis dagger");
        assertTrue(vanillaUnits.get(6).isAdded(), "the far malis dagger stood");
        assertTrue(VanillaFog.discovered(Team.crux).get(2 + 2 * WIDTH + 10), "the duo placed in play discovered its corner for crux");
        assertTrue(streamsLeftOut > 0, "a world stream left a unit out: " + streamsLeftOut);
        sameStreams();
    }

    /**
     * Static fog in building targeting ({@code BlockIndexer.findEnemyTile}'s {@code isDiscovered}): a sharded dagger
     * whose closest-target range reaches 45 tiles stands 34 tiles from a crux duo, past its own 21-tile sight, so it
     * may not target it; a sharded flare flies by and discovers the duo, and from then on the dagger targets it.
     * Compared after every frame as {@link #twoTeamsFightInFogAsInVanilla}, for {@value #TARGETING_FRAMES} frames.
     */
    @Test
    void undiscoveredBuildingsAreNoTargets(){
        worlds();
        place("duo", 40, 16, Team.crux);
        patrol("dagger", Team.sharded, 6, 16, 6, 16, 10_000, 45 * 8f);
        patrol("flare", Team.sharded, 6, 40, 44, 30, 10_000, 1f);
        int before = 0, after = 0;
        for(int frame = 0; frame < TARGETING_FRAMES; frame++){
            Patrols.frame = frame;
            Patrols.VANILLA.clear();
            Patrols.SHARD.clear();
            fog.before();
            Vanilla.frame();
            fog.after();
            shard.tick();
            compare("frame " + (frame + 1));
            String target = Patrols.VANILLA.get(vanillaUnits.getFirst().id - vanillaBase);
            if(target.equals("build 40,16")) after++;
            else if(after == 0 && target.equals("-")) before++;
        }
        System.out.println("targeting: the dagger had no target for " + before + " frames, then the duo for " + after);
        assertTrue(before > 0, "the undiscovered duo was no target");
        assertTrue(after > 0, "the discovered duo was");
    }


}
