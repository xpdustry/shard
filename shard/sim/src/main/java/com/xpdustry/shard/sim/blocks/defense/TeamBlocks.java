package com.xpdustry.shard.sim.blocks.defense;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;
import java.util.function.*;

/**
 * Vanilla's {@code indexer.eachBlock} for one team (BlockIndexer.java:323, :364), which projectors heal, boost and
 * watch through: the team's {@code buildingTree} in its quadtree order ({@link Buildings#tree}), filtered first and
 * then visited, so a visit that changes the tree doesn't change what is visited. Privileged blocks are skipped.
 */
public final class TeamBlocks{
    private TeamBlocks(){
    }

    /**
     * {@code eachBlock(team, wx, wy, range, pred, cons)}: every building of {@code team} whose centre is within
     * {@code range} plus half its size of (wx, wy). Returns whether any was visited.
     */
    public static boolean eachBlock(World world, int team, float wx, float wy, float range, Predicate<Building> pred, Consumer<Building> action){
        BuildingTree tree = world.buildings().tree(team);
        if(tree == null) return false;
        var table = world.tiles().table();
        var found = new ArrayList<Building>();
        tree.intersect(wx - range, wy - range, range * 2f, range * 2f, b -> {
            if(Mathf.within(b.x(), b.y(), wx, wy, range + b.hitSize() / 2f) && pred.test(b) && !table.privileged(b.block().id())){
                found.add(b);
            }
        });
        for(Building b : found) action.accept(b);
        return !found.isEmpty();
    }

    /** {@code eachBlock(team, rect, pred, cons)}: every building of {@code team} whose box overlaps the rectangle. */
    public static boolean eachBlock(World world, int team, float x, float y, float width, float height, Predicate<Building> pred,
                                    Consumer<Building> action){
        BuildingTree tree = world.buildings().tree(team);
        if(tree == null) return false;
        var table = world.tiles().table();
        var found = new ArrayList<Building>();
        tree.intersect(x, y, width, height, b -> {
            if(pred.test(b) && !table.privileged(b.block().id())) found.add(b);
        });
        for(Building b : found) action.accept(b);
        return !found.isEmpty();
    }
}
