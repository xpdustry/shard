package com.xpdustry.shard.sim.building;

import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.tile.*;

/**
 * {@link BuildingTargets} over {@link Buildings}' indexes: vanilla's {@code BlockIndexer.findEnemyTile}, which is
 * {@link Targets#findEnemyTile} over each team's {@code buildingTree} in its quadtree order, and {@code turretTree.any}
 * over the index's attacking buildings.
 *
 * <p>{@code isDiscovered} is the map-area darkness test, then the viewer's discovered tiles under static fog
 * ({@link com.xpdustry.shard.sim.fog.FogControl#isDiscovered(Building, int)}).
 */
final class ChunkTargets implements BuildingTargets{
    private final Buildings buildings;

    ChunkTargets(Buildings buildings){
        this.buildings = buildings;
    }

    @Override
    public Building findEnemyTile(int team, float x, float y, float range, BuildingFilter filter, int source){
        return Targets.findEnemyTile(buildings.world(), team, x, y, range, Targets.BuildingPriority.DEFAULT, filter, source);
    }

    @Override
    public boolean anyEnemyTurret(int team, float x, float y, float width, float height){
        TileGrid tiles = buildings.world().tiles();
        // the tree's root bounds are the map
        float mapWidth = tiles.width() * Footprint.TILE_SIZE, mapHeight = tiles.height() * Footprint.TILE_SIZE;
        if(!(0 < x + width && mapWidth > x && 0 < y + height && mapHeight > y)) return false;
        for(int t = 0; t < VanillaIds.TEAMS; t++){
            if(t == team || t == VanillaIds.DERELICT) continue;
            if(buildings.anyTurret(t, turret -> {
                float r = turret.range(), bx = turret.x(), by = turret.y();
                return bx - r < x + width && bx + r > x && by - r < y + height && by + r > y;
            })) return true;
        }
        return false;
    }

    /**
     * {@code isDiscovered(viewer)} (BuildingComp.java:315): not in the dark outside a limited map area, then, under
     * static fog, discovered by the viewer.
     */
    boolean isDiscovered(Building building, int viewer){
        Rules rules = buildings.rules();
        if(rules.limitMapArea && darkness(building.tileX(), building.tileY()) >= 3) return false;
        return com.xpdustry.shard.sim.fog.FogControl.isDiscovered(building, viewer);
    }

    /**
     * {@code World.getDarkness(x, y)} (World.java:484) without the sector term, which a server's custom maps never
     * have: border darkness, then a darkened tile's {@code data}.
     */
    float darkness(int x, int y){
        Rules rules = buildings.rules();
        TileGrid tiles = buildings.world().tiles();
        float dark = 0;
        if(rules.borderDarkness){
            int edgeBlend = 2;
            int edgeDst;
            if(!rules.limitMapArea){
                edgeDst = Math.min(x, Math.min(y, Math.min(-(x - (tiles.width() - 1)), -(y - (tiles.height() - 1)))));
            }else{
                edgeDst = Math.min(x - rules.limitX, Math.min(y - rules.limitY,
                    Math.min(-(x - (rules.limitX + rules.limitWidth - 1)), -(y - (rules.limitY + rules.limitHeight - 1)))));
            }
            if(edgeDst <= edgeBlend) dark = Math.max((edgeBlend - edgeDst) * (4f / edgeBlend), dark);
        }
        if(tiles.inBounds(x, y)){
            int pos = tiles.pos(x, y);
            if(tiles.isDarkened(pos, rules.editor || rules.allowEditWorldProcessors)) dark = Math.max(dark, tiles.data(pos));
        }
        return dark;
    }
}
