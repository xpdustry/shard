package com.xpdustry.shard.compat.v160.fog;

import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.blocks.defense.Radar;
import com.xpdustry.shard.sim.fog.FogControl;
import mindustry.game.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.locks.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review 43: {@link FogOracleTest} pins vanilla's fog to Shard's schedule ({@link VanillaFog}), so it shows parity under
 * that schedule only. This gate runs vanilla's fog unmodified (its static and dynamic threads, and the 40 ms of wall
 * clock between a team's redraws) with frames paced at 60 a second, as a server runs them, and compares the outcome
 * once the scene settles, not the frames.
 *
 * <p>Sharded holds the west with a radar on a power source (its fog radius grows for 600 frames), crux the east with
 * another; each team walks a dagger and flies a flare toward the middle, where they stop, and crux (in its far corner,
 * out of sharded's sight) and malis each have a dagger standing. No unit or building targets anything, so the fog's timing can't change the fight, and every frame
 * still checks that both sides run the same scene (units, the random, the entity ids). After {@value #SCRIPT} frames
 * both sides run on until vanilla's threads are idle (nothing queued, no redraw due, at most {@value #SETTLE_LIMIT}
 * frames, 3 minutes), and then, at tolerance 0:
 * <ul>
 *   <li>each team's visible and discovered tiles, and which teams have fog;</li>
 *   <li>the {@code static-fog-data} chunk, byte for byte;</li>
 *   <li>each team's entity snapshots and hidden ids, byte for byte, and the units a world stream to each team holds.</li>
 * </ul>
 * Exact is the right tolerance here: the discovered tiles are drawn from a queue of every event, whatever the timing,
 * and a settled scene's visible tiles are its last redraw, from the same positions on both sides. What the frames in
 * between show is the pinned gate's.
 */
class FogOutcomeOracleTest extends FogFixture{
    static final int SCRIPT = 900, SETTLE_MIN = 6, SETTLE_LIMIT = 60 * 180;
    static final long FRAME_NANOS = 1_000_000_000L / 60;

    @Test
    void settledFogMatchesUnmodifiedVanilla(){
        worlds();
        place("power-source", 6, 25, Team.sharded);
        place("radar", 6, 24, Team.sharded);
        place("power-source", 89, 25, Team.crux);
        place("radar", 89, 24, Team.crux);
        // every beat's period is past the run: each unit goes to its far end and stays; range 1 targets nothing
        patrol("dagger", Team.sharded, 10, 20, 40, 22, 100_000, 1f);
        patrol("flare", Team.sharded, 10, 40, 30, 38, 100_000, 1f);
        patrol("dagger", Team.crux, 86, 30, 58, 28, 100_000, 1f);
        patrol("flare", Team.crux, 90, 10, 56, 12, 100_000, 1f);
        patrol("dagger", Team.crux, 90, 44, 90, 44, 100_000, 1f);
        patrol("dagger", Team.malis, 30, 6, 30, 6, 100_000, 1f);
        player(Team.sharded);
        player(Team.crux);

        int discoveredAtStart = -1, frame = 0, settled = 0;
        long next = System.nanoTime();
        for(; frame < SCRIPT + SETTLE_LIMIT; frame++){
            Patrols.frame = frame;
            Patrols.VANILLA.clear();
            Patrols.SHARD.clear();
            Vanilla.frame();
            shard.tick();
            String at = "frame " + (frame + 1);
            sameRandom(at);
            sameWorld(at);
            if(frame == 0) discoveredAtStart = VanillaFog.discovered(Team.sharded).cardinality();
            // paced as a server's frames, so vanilla's threads and its 40 ms gate see a server's wall clock
            next += FRAME_NANOS;
            LockSupport.parkNanos(next - System.nanoTime());
            if(frame >= SCRIPT && VanillaFog.idle() && ++settled >= SETTLE_MIN) break;
        }
        assertTrue(frame < SCRIPT + SETTLE_LIMIT, "vanilla's fog threads never went idle after " + SETTLE_LIMIT + " frames");
        assertTrue(VanillaFog.threaded(), "vanilla drew on its own threads");

        String at = "settled at frame " + (frame + 1);
        sameFog(at);
        sameChunk(at);
        int[] counts = sameSnapshots(at);
        int leftOut = keepStreams(at);
        sameStreams();

        var radar = (Radar)shard.buildings().at(6, 24);
        int discoveredAtEnd = BitSet.valueOf(FogControl.of(shard).discovered(Team.sharded.id)).cardinality();
        System.out.println("fog outcome: settled at frame " + (frame + 1) + ", radar " + radar.fogRadius() + ", " + counts[0] + " crux units hidden from sharded and "
            + counts[1] + " shown, " + leftOut + " units left out of world streams, sharded discovered " + discoveredAtStart + " -> " + discoveredAtEnd);
        assertTrue(radar.fogRadius() > 30f, "the radar grew: " + radar.fogRadius());
        assertTrue(discoveredAtEnd > discoveredAtStart, "the units discovered tiles on the way");
        assertTrue(counts[0] > 0, "sharded's snapshot hid a crux unit");
        assertTrue(counts[1] > 0, "sharded's snapshot showed a crux unit");
        assertTrue(leftOut > 0, "a world stream left a unit out");
    }
}
