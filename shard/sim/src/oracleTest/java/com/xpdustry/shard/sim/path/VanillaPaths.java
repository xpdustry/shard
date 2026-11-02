package com.xpdustry.shard.sim.path;

import arc.struct.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;
import mindustry.ai.*;
import mindustry.game.*;
import mindustry.world.*;
import mindustry.world.Tile;

import java.lang.reflect.*;
import java.util.*;

import static mindustry.Vars.*;

/**
 * Vanilla's {@code Pathfinder}, driven by reflection on the oracle's hosted map, and a Shard world that mirrors that
 * map. Vanilla's own pathfinder thread never sees the fields made here: they are not registered with it.
 */
final class VanillaPaths{
    /** Shard's class for vanilla's {@code Pathfinder.costTypes} index, for the classes vanilla units use. */
    static final Map<PathCost, Integer> COSTS = new TreeMap<>(Map.of(
        PathCost.GROUND, Pathfinder.costGround, PathCost.LEGS, Pathfinder.costLegs,
        PathCost.NAVAL, Pathfinder.costNaval, PathCost.HOVER, Pathfinder.costHover));

    private static final Field TILES, TEAM, COST, FRONTIER, TARGETS, HAS_COMPLETE;
    private static final Method SETUP, GET_POSITIONS, UPDATE_FRONTIER, UPDATE_TARGETS;

    static{
        try{
            TILES = open(Pathfinder.class.getDeclaredField("tiles"));
            Class<?> flowfield = Pathfinder.Flowfield.class;
            TEAM = open(flowfield.getDeclaredField("team"));
            COST = open(flowfield.getDeclaredField("cost"));
            FRONTIER = open(flowfield.getDeclaredField("frontier"));
            TARGETS = open(flowfield.getDeclaredField("targets"));
            HAS_COMPLETE = open(flowfield.getDeclaredField("hasComplete"));
            SETUP = open(flowfield.getDeclaredMethod("setup"));
            GET_POSITIONS = open(Pathfinder.EnemyCoreField.class.getDeclaredMethod("getPositions", IntSeq.class));
            UPDATE_FRONTIER = open(Pathfinder.class.getDeclaredMethod("updateFrontier", flowfield, long.class));
            UPDATE_TARGETS = open(Pathfinder.class.getDeclaredMethod("updateTargets", flowfield));
        }catch(ReflectiveOperationException e){
            throw new ExceptionInInitializerError(e);
        }
    }

    private static <T extends AccessibleObject> T open(T member){
        member.setAccessible(true);
        return member;
    }

    /** Vanilla's refresh of a built field: {@code updateTargets}, then a full {@code updateFrontier}. */
    static void refresh(Pathfinder.Flowfield field){
        try{
            UPDATE_TARGETS.invoke(pathfinder, field);
            UPDATE_FRONTIER.invoke(pathfinder, field, -1L);
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }

    /** Repacks every tile with {@code packTile} under the hosted rules, as {@code WorldLoadEvent} does, and returns them. */
    static int[] repack(){
        try{
            int[] tiles = (int[])TILES.get(pathfinder);
            for(int i = 0; i < tiles.length; i++) tiles[i] = pathfinder.packTile(world.tiles.geti(i));
            return tiles;
        }catch(IllegalAccessException e){
            throw new IllegalStateException(e);
        }
    }

    /** A vanilla enemy-core field: its first build, then the build after one refresh. */
    record Built(Pathfinder.Flowfield field, int[] first, int[] refreshed, int[] targets){
    }

    /**
     * Builds {@code team}'s {@code EnemyCoreField} for vanilla cost {@code cost} the way the pathfinder thread does:
     * {@code registerPath} and a full {@code updateFrontier}, then {@code updateTargets} and {@code updateFrontier}
     * again, which is what every refresh after a tile change runs.
     */
    static Built build(int team, int cost){
        try{
            var field = new Pathfinder.EnemyCoreField();
            TEAM.set(field, Team.get(team));
            COST.set(field, Pathfinder.costTypes.get(cost));
            var targets = (IntSeq)TARGETS.get(field);
            targets.clear();
            GET_POSITIONS.invoke(field, targets);
            SETUP.invoke(field);
            // registerPath (Pathfinder.java:477), without the thread lists
            Arrays.fill(field.weights, -1);
            var frontier = (IntQueue)FRONTIER.get(field);
            for(int i = 0; i < targets.size; i++){
                field.weights[targets.get(i)] = 0;
                frontier.addFirst(targets.get(i));
            }
            UPDATE_FRONTIER.invoke(pathfinder, field, -1L);
            if(!HAS_COMPLETE.getBoolean(field)) throw new IllegalStateException("vanilla's first search overflowed its frontier");
            int[] first = field.completeWeights.clone();
            UPDATE_TARGETS.invoke(pathfinder, field);
            UPDATE_FRONTIER.invoke(pathfinder, field, -1L);
            int[] sorted = targets.toArray();
            Arrays.sort(sorted);
            return new Built(field, first, field.completeWeights.clone(), sorted);
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }

    /**
     * A Shard world with the hosted map, loaded as a map ({@link VanillaMaps}: static walls get their darkness), and
     * each building's health; not yet flushed.
     */
    static World mirror(){
        World shard = VanillaMaps.load(PathWorlds.world(world.width(), world.height(), 1));
        var grid = shard.tiles();
        for(Tile tile : world.tiles){
            if(tile.build != null && tile.build.tile == tile) shard.buildings().at(grid.pos(tile.x, tile.y)).health = tile.build.health;
        }
        return shard;
    }

    /** The path rules of the hosted map. */
    static PathRules rules(){
        Rules r = state.rules;
        return new PathRules(r.waves, r.attackMode, r.pvp, r.waveTeam.id, r.defaultTeam.id, state.wave, r.coreCapture,
            r.randomWaveAI, r.limitMapArea, r.limitX, r.limitY, r.limitWidth, r.limitHeight, false, false,
            PathRules.DEFAULT.rtsPopBudget());
    }

    /** The PathBlocks table, read from the live vanilla content. */
    static PathBlocks liveBlocks(){
        var all = content.blocks();
        int[] flags = new int[all.size];
        float[] ranges = new float[all.size];
        for(Block block : all){
            int bits = 0;
            if(block.solid) bits |= PathBlocks.SOLID;
            if(block instanceof mindustry.world.blocks.environment.Floor floor){
                if(floor.isLiquid) bits |= PathBlocks.LIQUID;
                if(floor.isDeep()) bits |= PathBlocks.DEEP;
                if(floor.damages()) bits |= PathBlocks.DAMAGES;
            }
            if(block.teamPassable) bits |= PathBlocks.TEAM_PASSABLE;
            if(block instanceof mindustry.world.blocks.storage.CoreBlock) bits |= PathBlocks.CORE;
            if(block == mindustry.content.Blocks.spawn) bits |= PathBlocks.SPAWN;
            if(block.targetable) bits |= PathBlocks.TARGETABLE;
            if(block == mindustry.content.Blocks.air) bits |= PathBlocks.AIR;
            for(int i = 0; i < PathBlocks.RANDOM_TARGETS.size(); i++){
                if(block.flags.contains(mindustry.world.meta.BlockFlag.valueOf(PathBlocks.RANDOM_TARGETS.get(i)))){
                    bits |= 1 << (PathBlocks.RANDOM_TARGET_SHIFT + i);
                }
            }
            flags[block.id] = bits;
            ranges[block.id] = block instanceof mindustry.world.blocks.defense.turrets.Turret turret ? turret.range : 0f;
        }
        return new PathBlocks(flags, ranges);
    }

    private VanillaPaths(){}
}
