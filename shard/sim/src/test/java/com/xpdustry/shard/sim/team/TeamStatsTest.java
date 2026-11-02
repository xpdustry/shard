package com.xpdustry.shard.sim.team;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.storage.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code updateTeamStats}' cores part ({@link Teams#updateStats}): each team with cores gets its first core as its last,
 * whichever team it is, as cores come and go, in a fork too; a team that lost its cores keeps the last one.
 */
class TeamStatsTest{
    @Test
    void eachTeamsLastCoreIsItsFirstCore(){
        World world = UnitWorlds.world(80, 80, 3);
        Teams teams = Teams.of(world);
        // a team past the first 64, so the stats reach every word of the team set
        int far = 200;
        Core a = core(world, 10, 10, Team.SHARDED), b = core(world, 20, 10, Team.SHARDED), c = core(world, 40, 40, Team.CRUX);
        Core d = core(world, 60, 60, far);
        world.tick();
        assertSame(a, teams.get(Team.SHARDED).lastCore());
        assertSame(c, teams.get(Team.CRUX).lastCore());
        assertSame(d, teams.get(far).lastCore());

        world.buildings().remove(a, Cause.SERVER);
        world.buildings().remove(c, Cause.SERVER);
        world.tick();
        assertSame(b, teams.get(Team.SHARDED).lastCore());
        assertSame(c, teams.get(Team.CRUX).lastCore(), "a team without cores keeps its last one");

        World fork = world.fork();
        Teams forked = Teams.of(fork);
        Core e = core(fork, 40, 40, Team.CRUX);
        fork.buildings().remove(fork.buildings().at(20, 10), Cause.SERVER);
        Core f = core(fork, 30, 30, Team.SHARDED);
        fork.tick();
        assertSame(e, forked.get(Team.CRUX).lastCore());
        assertSame(f, forked.get(Team.SHARDED).lastCore());
        assertSame(forked.get(far).cores().getFirst(), forked.get(far).lastCore());
    }

    static Core core(World world, int x, int y, int team){
        BlockDef block = world.content().block("core-shard");
        return (Core)world.buildings().setBlock(x, y, block, team, 0, Cause.SERVER);
    }
}
