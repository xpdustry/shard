package com.xpdustry.shard.server.game;

import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Sets a world's {@code Config.autoPause} ({@link Teams#autoPause(boolean)}, run by {@link EmptyAutoPause}) through its
 * inbox, so the command log and a replay see it change at the same tick. {@link AutoPauses} sends it from
 * {@code game.auto-pause}.
 */
public record SetAutoPause(boolean autoPause) implements Command.Admin{
    @Override
    public Verdict apply(World world, Cause cause){
        Teams teams = Teams.of(world);
        if(teams == null) return Verdict.rejected("the world has no teams");
        teams.autoPause(autoPause);
        return Verdict.ADMITTED;
    }
}
