package com.xpdustry.shard.tools.fuzz;

import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;

import java.util.*;

/**
 * What a structure-aware packet can aim at in one world, read on its tick thread: the map's size, live unit, player
 * and building ids and positions, the content's id ranges. The fuzzer reads it from the harness, not the wire: it
 * needs only plausible targets, and a world's own ids next to another world's.
 *
 * @param world the world's id name
 */
record Targets(String world, int width, int height, int[] units, int[] unitTeams, int[] players, int[] buildPositions, int[] buildIds,
               int[] teams, int blocks, int items, int liquids, int unitTypes, WireContext wire){
    static final int MAX = 256;
    static final Targets NONE = new Targets("", 50, 50, new int[0], new int[0], new int[0], new int[0], new int[0], new int[]{1}, 1, 1, 1, 1, null);

    /** Reads {@code world}'s targets; call on its tick thread ({@link HostedWorld#inspect}). */
    static Targets of(String name, World world){
        var units = new ArrayList<Unit>();
        for(int i = 0, n = world.units().size(); i < n && units.size() < MAX; i++) units.add(world.units().get(i));
        var builds = new ArrayList<Building>();
        int stride = Math.max(1, world.builds().size() / MAX);
        for(int i = 0, n = world.builds().size(); i < n && builds.size() < MAX; i += stride) builds.add(world.builds().get(i));
        var players = new ArrayList<Integer>();
        for(Player player : Roster.of(world).players().values()) players.add(player.id());
        var teams = new TreeSet<Integer>();
        for(Unit unit : units) teams.add(unit.team);
        for(Building build : builds) teams.add(build.team);
        if(teams.isEmpty()) teams.add(1);
        var content = world.content();
        return new Targets(name, world.tiles().width(), world.tiles().height(), units.stream().mapToInt(Unit::id).toArray(),
            units.stream().mapToInt(u -> u.team).toArray(), players.stream().mapToInt(Integer::intValue).toArray(),
            builds.stream().mapToInt(Building::pos).toArray(), builds.stream().mapToInt(Building::id).toArray(),
            teams.stream().mapToInt(Integer::intValue).toArray(), content.blocks(), content.items(), content.liquids(), content.units(),
            WorldWireContext.of(world.config()));
    }
}
