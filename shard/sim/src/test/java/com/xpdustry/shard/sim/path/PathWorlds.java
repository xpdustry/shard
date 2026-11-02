package com.xpdustry.shard.sim.path;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Worlds of the vanilla epoch that run only the path systems, and the edits the tests make to them. Every kind is a
 * passthrough: pathing reads a building's team, health and tile. The tile grid records every edit for
 * {@link PathFlush}.
 */
final class PathWorlds{
    static final Epoch VANILLA = Epoch.vanilla();
    static final PathBlocks BLOCKS = PathBlocks.of(VANILLA);

    /** The path handlers and parts, plus {@code extra} handlers and parts. */
    static Systems systems(List<Class<? extends WorldPart>> parts, PhaseHandler... extra){
        var handlers = new ArrayList<PhaseHandler>(List.of(new PathFlush()));
        handlers.addAll(List.of(extra));
        var all = new ArrayList<Class<? extends WorldPart>>(List.of(PathService.class));
        all.addAll(parts);
        return Systems.of(handlers, all);
    }

    static World world(int width, int height, long seed, PhaseHandler... extra){
        return world(width, height, seed, List.of(), extra);
    }

    static World world(int width, int height, long seed, List<Class<? extends WorldPart>> parts, PhaseHandler... extra){
        return world(width, height, seed, systems(parts, extra));
    }

    static World world(int width, int height, long seed, Systems systems){
        return new World(WorldConfig.of(VANILLA, width, height, seed).withSystems(systems)
            .withKinds(KindRegistry.builtIn().withPassthrough()));
    }

    static int id(String name){
        return VANILLA.block(name).id();
    }

    /** A world floored with {@code floor} everywhere, flushed once. */
    static World filled(int width, int height, String floor, PhaseHandler... extra){
        World world = world(width, height, 1, extra);
        int id = id(floor);
        for(int pos = 0; pos < world.tiles().size(); pos++) world.tiles().floor(pos, id);
        paths(world).invalidateAll();
        world.tick();
        return world;
    }

    static PathService paths(World world){
        return world.part(PathService.class);
    }

    static void floor(World world, int x, int y, String name){
        int pos = world.tiles().pos(x, y);
        world.tiles().floor(pos, id(name));
    }

    static void overlay(World world, int x, int y, String name){
        int pos = world.tiles().pos(x, y);
        world.tiles().overlay(pos, id(name));
    }

    /** Sets a block without a building (a static wall, a boulder, or air), if no building covers the tile. */
    static boolean wall(World world, int x, int y, String name){
        int pos = world.tiles().pos(x, y);
        if(world.tiles().build(pos) != -1) return false;
        world.tiles().block(pos, id(name));
        world.tiles().team(pos, 0);
        return true;
    }

    /** Places a building of {@code name} centred on (x, y), or returns null if its footprint isn't clear. */
    static Building place(World world, String name, int team, int x, int y){
        BlockDef block = VANILLA.block(name);
        int offset = -(block.size() - 1) / 2;
        var tiles = world.tiles();
        for(int dx = 0; dx < block.size(); dx++){
            for(int dy = 0; dy < block.size(); dy++){
                int tx = x + dx + offset, ty = y + dy + offset;
                if(!tiles.inBounds(tx, ty) || tiles.build(tiles.pos(tx, ty)) != -1 || tiles.block(tiles.pos(tx, ty)) != 0) return null;
            }
        }
        Building building = world.buildings().create(block, team, x, y, 0, Cause.SERVER);
        building.health = building.maxHealth = block.health();
        return building;
    }

    static void remove(World world, Building building){
        var tiles = world.tiles();
        int size = building.block().size(), offset = -(size - 1) / 2, x = tiles.x(building.tile()), y = tiles.y(building.tile());
        world.buildings().remove(building, Cause.SERVER);
    }

    /** Every live building, in registry order. */
    static List<Building> buildings(World world){
        var out = new ArrayList<Building>();
        world.buildings().each(out::add);
        return out;
    }

    private PathWorlds(){}
}
