package com.xpdustry.shard.sim.tile;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checkpoints carry the tile state (ARCHITECTURE.md §4): the columns and data bytes, load-time darkness, the pending
 * change list, the registry, proximity in its historical order and passthrough bytes are stored; the predicate
 * bitsets and chunk buckets rebuild to the same hash. The worlds run no parts, so this stands on the tile state alone.
 */
class TileCheckpointTest{
    /** {@link TileForkTest}'s blocks, whose churn this test runs: the cargo loader stays a passthrough. */
    static final ContentEpoch BLOCKS = TileForkTest.BLOCKS;

    static WorldConfig config(){
        return new WorldConfig(BLOCKS, 40, 40, 1, Systems.of(List.of(), List.of()), KindRegistry.builtIn().withPassthrough(),
            WorldConfig.DEFAULT_INBOX_CAPACITY, new MemoryJournal());
    }

    /**
     * A loaded map with a band of static walls (so darkness), random data bytes, then live churn that leaves changes
     * pending.
     */
    static World loaded(long seed){
        World world = new World(config());
        var rand = new Rand(seed);
        TileGrid tiles = world.tiles();
        world.buildings().beginLoad();
        for(int pos = 0; pos < tiles.size(); pos++){
            tiles.floor(pos, BLOCKS.block("stone").id());
            if(rand.chance(0.1)) tiles.data(pos, rand.random(255));
            if(tiles.x(pos) < 12 || rand.chance(0.1)) tiles.block(pos, BLOCKS.block("stone-wall").id());
            if(rand.chance(0.1)) tiles.floorData(pos, rand.random(255));
            if(rand.chance(0.1)) tiles.overlayData(pos, rand.random(255));
            if(rand.chance(0.1)) tiles.extraData(pos, rand.nextInt());
        }
        world.buildings().endLoad(false);
        TileForkTest.churn(world, rand, 600);
        return world;
    }

    static byte[] file(World world){
        return Checkpoint.capture(world).encode(Compression.FAST);
    }

    static World restore(World world){
        return Checkpoint.decode(file(world)).restore(world.config());
    }

    @Test
    void aRestoredGridAndRegistryAreTheSameAndContinueAlike(){
        World live = loaded(3);
        assertTrue(live.tiles().pendingChanges() > 0, "the boundary has changes pending");
        World restored = restore(live);
        assertEquals(live.hash(), restored.hash(), () -> live.hash().firstDifference(restored.hash()).orElse("?"));

        TileGrid a = live.tiles(), b = restored.tiles();
        int dark = 0;
        for(int pos = 0; pos < a.size(); pos++){
            assertEquals(a.packed(pos), b.packed(pos), "tile " + pos);
            assertEquals(a.data(pos), b.data(pos), "data at " + pos);
            assertEquals(a.floorData(pos), b.floorData(pos), "floorData at " + pos);
            assertEquals(a.overlayData(pos), b.overlayData(pos), "overlayData at " + pos);
            assertEquals(a.extraData(pos), b.extraData(pos), "extraData at " + pos);
            assertEquals(a.buildSolid(pos), b.buildSolid(pos), "buildSolid at " + pos);
            if(a.staticDarkness(pos) > 0) dark++;
        }
        assertTrue(dark > 0, "the map has darkened walls");
        assertEquals(a.pendingChanges(), b.pendingChanges());
        int passthroughs = 0;
        for(int i = 0; i < live.buildings().indexLimit(); i++){
            Building building = live.buildings().get(i), copy = restored.buildings().get(i);
            assertEquals(building == null, copy == null, "slot " + i);
            if(building == null) continue;
            assertEquals(ids(building.proximityList()), ids(copy.proximityList()), "proximity of " + building);
            if(building instanceof PassthroughBuilding passthrough && passthrough.hasBody()){
                assertArrayEquals(passthrough.body(BLOCKS), ((PassthroughBuilding)copy).body(BLOCKS));
                passthroughs++;
            }
        }
        assertTrue(passthroughs > 0, "some passthroughs carry bytes");
        assertEquals(live.journal().entries(), restored.journal().entries(), "the journal keeps the data bytes");
        assertArrayEquals(file(live), file(restored), "a restored world checkpoints to the same bytes");

        var ra = new Rand(4);
        var rb = new Rand(4);
        for(int i = 0; i < 10; i++){
            TileForkTest.churn(live, ra, 40);
            TileForkTest.churn(restored, rb, 40);
            live.tick();
            restored.tick();
            assertEquals(live.hash(), restored.hash(), "step " + i + ": " + live.hash().firstDifference(restored.hash()).orElse("?"));
        }
    }

    @Test
    void proximityKeepsItsHistoricalOrder(){
        World live = new World(config());
        var router = BLOCKS.block("router");
        Building centre = live.buildings().setBlock(5, 5, router, 1, 0, Cause.SERVER);
        Building above = live.buildings().setBlock(5, 6, router, 1, 0, Cause.SERVER);
        Building right = live.buildings().setBlock(6, 5, router, 1, 0, Cause.SERVER);
        // neighbours placed later append; a rebuild in edge order would put the right one first
        assertEquals(List.of(above, right), centre.proximityList());
        World restored = restore(live);
        assertEquals(ids(centre.proximityList()), ids(restored.buildings().at(5, 5).proximityList()));
        assertEquals(live.hash(), restored.hash());
    }

    @Test
    void aWorldLoadingAMapCantBeCheckpointed(){
        World world = new World(config());
        world.buildings().beginLoad();
        assertThrows(IllegalStateException.class, () -> Checkpoint.capture(world));
        world.buildings().endLoad(false);
        assertDoesNotThrow(() -> Checkpoint.capture(world));
    }

    static List<Integer> ids(List<Building> buildings){
        return buildings.stream().map(Building::id).toList();
    }
}
