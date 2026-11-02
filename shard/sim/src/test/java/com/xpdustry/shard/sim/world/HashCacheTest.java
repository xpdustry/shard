package com.xpdustry.shard.sim.world;

import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.toy.*;
import org.junit.jupiter.api.*;

import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link World#hash()} reuses the tiles' section while the grid stands still: after every kind of tile change it
 * equals the hash of a fork, which has nothing cached.
 */
class HashCacheTest{
    @Test
    void everyTileChangeIsHashedAgain(){
        World world = Worlds.churned(31, 100);
        TileGrid tiles = world.tiles();
        int pos = tiles.pos(5, 7);
        var changes = new java.util.LinkedHashMap<String, Consumer<TileGrid>>();
        changes.put("floor", grid -> grid.floor(pos, grid.floor(pos) + 1));
        changes.put("overlay", grid -> grid.overlay(pos, grid.overlay(pos) + 1));
        changes.put("block", grid -> grid.block(pos, grid.block(pos) == 0 ? 1 : 0));
        changes.put("team", grid -> grid.team(pos, grid.team(pos) + 1));
        changes.put("build", grid -> grid.build(pos, grid.build(pos) + 7));
        changes.put("data", grid -> grid.data(pos, grid.data(pos) + 1));
        changes.put("floorData", grid -> grid.floorData(pos, grid.floorData(pos) + 1));
        changes.put("overlayData", grid -> grid.overlayData(pos, grid.overlayData(pos) + 1));
        changes.put("extraData", grid -> grid.extraData(pos, grid.extraData(pos) + 1));
        changes.put("buildSolid", grid -> grid.buildSolid(pos, !grid.buildSolid(pos)));
        changes.put("clearChanges", TileGrid::clearChanges);
        changes.put("a tick", grid -> world.tick());
        changes.put("a quiet tick", grid -> world.tick());
        changes.put("rebuildDerived", TileGrid::rebuildDerived);
        for(var change : changes.entrySet()){
            world.hash();
            change.getValue().accept(tiles);
            assertEquals(world.fork().hash(), world.hash(), "after " + change.getKey());
        }
    }

    @Test
    void anUnchangedGridKeepsItsVersion(){
        World world = Worlds.churned(32, 50);
        world.part(Chaos.class).enabled = false;
        world.tick();
        long version = world.tiles().version();
        world.tick();
        assertEquals(version, world.tiles().version(), "a tick that changes no tile");
    }
}
