package com.xpdustry.shard.server.console;

import com.xpdustry.shard.sim.blocks.storage.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.world.*;

/**
 * The console's {@code fillitems} (ServerControl.java:772): every item in the team's first core set to its storage
 * capacity, through the world's inbox.
 */
public record FillItems(int team) implements Command.Admin{
    @Override
    public Verdict apply(World world, Cause cause){
        Teams teams = Teams.of(world);
        if(teams == null || teams.cores(team).isEmpty()) return Verdict.rejected("That team has no cores.");
        Core core = teams.cores(team).getFirst();
        for(int item = 0; item < world.content().items(); item++) core.items.set(item, core.storageCapacity);
        return Verdict.ADMITTED;
    }
}
