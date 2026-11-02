package com.xpdustry.shard.sim.waves;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * The team data the spawner reads (WP23's {@code Teams}): a team's cores, whether it is active, and a core's command
 * position. A world finds it as the first {@link WorldPart} implementing it; without one, {@link #INDEXED} answers
 * from the block index.
 */
public interface WaveCores{
    /**
     * Cores from the block index's {@code core} flag lists, which follow tile order at load and placement order after
     * it. Vanilla's {@code TeamData.cores} follows the order cores first update their proximity, which is the same
     * unless a core's neighbour comes earlier in tile order than its first tile. No core has a command position.
     */
    WaveCores INDEXED = (world, team) -> world.buildings().flagged(team, BlockFlag.core);

    /** {@code state.teams.cores(team)}: in the team's order. Don't modify it. */
    List<Building> cores(World world, int team);

    /**
     * {@code state.teams.isActive(team)} ({@code TeamData.active()}): the wave team while waves are on, or a team with
     * cores or buildings, or neoplastic units.
     */
    default boolean isActive(World world, int team){
        Rules rules = UnitCalls.rules(world);
        if((team == rules.waveTeam && rules.waves) || !cores(world, team).isEmpty()) return true;
        TileGrid tiles = world.tiles();
        for(int pos = 0; pos < tiles.size(); pos++){
            if(tiles.build(pos) != -1 && (tiles.team(pos) & 0xff) == team) return true;
        }
        if(team == Team.NEOPLASTIC){
            TeamUnits counts = TeamUnits.of(world);
            return counts != null && counts.count(team) > 0;
        }
        return false;
    }

    /** {@code CoreBuild.commandPos}: where a player sent the core's spawns, in world units, or null. */
    default Vec2 commandPos(Building core){
        return null;
    }

    static WaveCores of(World world){
        WaveCores cores = world.find(WaveCores.class);
        return cores == null ? INDEXED : cores;
    }
}
