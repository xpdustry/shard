package com.xpdustry.shard.compat.v160.fog;

import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.build.Build;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.fog.FogControl;
import com.xpdustry.shard.sim.world.Cause;
import mindustry.game.*;
import mindustry.gen.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * W4.3's untested fog cases, compared as {@link FogOracleTest} compares (fog bits, units, targets, bullets, snapshots,
 * world streams; vanilla's fog on Shard's schedule, {@link VanillaFog}), with the {@code static-fog-data} chunk and a
 * restore of Shard's checkpoint halfway through:
 * <ul>
 *   <li>a unit wider than 16 world units ({@code inFogTo} looks at the eight points half its hit size around it);</li>
 *   <li>a fog source removed ({@code TilePreChangeEvent} redraws its team);</li>
 *   <li>{@code Build.validPlace} refusing a tile its team hasn't discovered.</li>
 * </ul>
 * {@link FogAiTeamOracleTest} has the team that is only AI; it runs in its own class, so in its own JVM.
 */
class FogGapsOracleTest extends FogFixture{
    final VanillaFog fog = new VanillaFog();
    static final int RUN = 1200, RESTORE_AT = 600, PLACE_EVERY = 50;

    /** One frame on both sides, compared. */
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

    /** The comparisons {@link FogOracleTest} makes on its schedule, and the restore. */
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

    /** {@code tile.setAir()} on both sides: {@code TilePreChangeEvent}, then the removal. */
    private void remove(int x, int y){
        world.tile(x, y).setAir();
        Vanilla.runPosted();
        shard.buildings().setBlock(x, y, EPOCH.block("air"), 0, 0, Cause.SERVER);
        sameRandom("after removing at " + x + "," + y);
    }

    /**
     * {@code Build.validPlace(duo, sharded, x, y, 0)} on every tile, vanilla's against Shard's; returns how many tiles were
     * refused only for being undiscovered (discovered tiles are otherwise all placeable in this world) and how many
     * were allowed.
     */
    private int[] samePlacement(String at){
        int refused = 0, allowed = 0;
        var duo = content.block("duo");
        var ours = EPOCH.block("duo");
        BitSet discovered = VanillaFog.discovered(Team.sharded);
        var differ = new StringJoiner(" ");
        for(int y = 0; y < HEIGHT; y++){
            for(int x = 0; x < WIDTH; x++){
                boolean expected = mindustry.world.Build.validPlace(duo, Team.sharded, x, y, 0);
                boolean actual = Build.validPlace(shard, ours, Team.sharded.id, x, y, 0);
                if(expected != actual) differ.add(x + "," + y + "=" + expected);
                if(expected) allowed++;
                else if(discovered != null && !discovered.get(x + y * WIDTH) && world.tile(x, y).build == null && !mindustry.entities.Units.anyEntities(world.tile(x, y))) refused++;
            }
        }
        assertEquals("", differ.toString(), at + ": tiles where vanilla's validPlace differs (vanilla's answer)");
        return new int[]{refused, allowed};
    }

    /**
     * Sharded holds the west: a radar on a power source (its fog radius grows to 34 tiles over 600 frames) and a
     * standing dagger. A crux scepter (hit size 22) walks slowly from the east into the radar's reach and back out,
     * shooting nothing; its {@code inFogTo(sharded)} is compared every frame, and once its edges are seen while its
     * centre isn't. At frame 900 the radar is removed, and sharded sees only what its dagger sees. Every
     * {@value #PLACE_EVERY} frames, {@code validPlace} of a duo for sharded on every tile.
     */
    @Test
    void aLargeUnitARemovedRadarAndUndiscoveredTiles(){
        worlds();
        place("power-source", 6, 25, Team.sharded);
        place("radar", 6, 24, Team.sharded);
        patrol("dagger", Team.sharded, 10, 20, 10, 20, 10_000, 1f);
        patrol("scepter", Team.crux, 64, 24, 34, 24, 700, 1f);
        player(Team.sharded);

        int edgesOnly = 0, hidden = 0, seen = 0, refused = 0, allowed = 0, visibleBefore = -1, visibleAfter = -1;
        for(int frame = 0; frame < RUN; frame++){
            if(frame == 900){
                visibleBefore = VanillaFog.visible(Team.sharded).cardinality();
                remove(6, 24);
            }
            frame(frame);
            mindustry.gen.Unit scepter = vanillaUnits.get(1);
            boolean vanillaFog = scepter.inFogTo(Team.sharded);
            assertEquals(vanillaFog, FogControl.inFogTo(shardUnit(1), Team.sharded.id), "frame " + (frame + 1) + ": the scepter in fog to sharded");
            if(vanillaFog) hidden++;
            else seen++;
            if(!vanillaFog && !fogControl.isVisible(Team.sharded, scepter.x, scepter.y)) edgesOnly++;
            if(frame % PLACE_EVERY == 0){
                int[] counts = samePlacement("frame " + (frame + 1));
                refused += counts[0];
                allowed += counts[1];
            }
            if(frame == 920) visibleAfter = VanillaFog.visible(Team.sharded).cardinality();
            schedule(frame);
        }
        sameChunk("the end");
        sameStreams();
        System.out.println("large unit: hidden " + hidden + ", seen " + seen + ", seen by its edges only " + edgesOnly + "; radar removed: visible "
            + visibleBefore + " -> " + visibleAfter + "; validPlace refused " + refused + " undiscovered tiles, allowed " + allowed);
        assertTrue(hidden > 0 && seen > 0, "the scepter was in fog and out of it: " + hidden + ", " + seen);
        assertTrue(edgesOnly > 0, "sharded saw the scepter by its edges while its centre was in fog");
        assertTrue(visibleAfter < visibleBefore, "removing the radar shrank sharded's view: " + visibleBefore + " -> " + visibleAfter);
        assertTrue(refused > 0 && allowed > 0, "validPlace refused undiscovered tiles and allowed discovered ones: " + refused + ", " + allowed);
    }
}
