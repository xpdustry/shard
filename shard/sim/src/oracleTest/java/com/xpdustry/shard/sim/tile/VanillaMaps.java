package com.xpdustry.shard.sim.tile;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.world.*;
import mindustry.content.*;
import mindustry.world.Tile;

import static mindustry.Vars.*;

/**
 * The map the oracle hosts, loaded into a Shard world as the msav loader will: floors, then blocks at their centres,
 * then the data bytes, with darkened tiles' {@code data} zeroed so Shard's {@code addDarkness} has to recompute it.
 */
public final class VanillaMaps{
    /** {@code LogicBlock.accessible()} without the block's {@code privileged} check. */
    public static boolean accessible(){
        return state.rules.editor || state.playtestingMap != null || state.rules.allowEditWorldProcessors;
    }

    /** Loads the hosted map into {@code shard}, a fresh world of its size on the vanilla blocks, and returns it. */
    public static World load(World shard){
        ContentEpoch blocks = shard.content();
        TileGrid tiles = shard.tiles();
        Buildings buildings = shard.buildings();
        buildings.beginLoad();
        for(Tile tile : world.tiles){
            int pos = tiles.pos(tile.x, tile.y);
            tiles.floor(pos, tile.floorID());
            tiles.overlay(pos, tile.overlayID());
        }
        for(Tile tile : world.tiles){
            if(tile.block() == Blocks.air || !tile.isCenter()) continue;
            var build = tile.build;
            buildings.setBlock(tile.x, tile.y, blocks.block(tile.blockID()), build == null ? 0 : build.team.id,
                build == null ? 0 : build.rotation, Cause.SERVER);
        }
        for(Tile tile : world.tiles){
            int pos = tiles.pos(tile.x, tile.y);
            tiles.data(pos, tile.block().isDarkened(tile) ? 0 : tile.data);
            tiles.floorData(pos, tile.floorData);
            tiles.overlayData(pos, tile.overlayData);
            tiles.extraData(pos, tile.extraData);
        }
        buildings.endLoad(accessible());
        return shard;
    }

    private VanillaMaps(){}
}
