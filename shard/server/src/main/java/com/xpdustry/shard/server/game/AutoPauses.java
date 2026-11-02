package com.xpdustry.shard.server.game;

import com.xpdustry.shard.server.config.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Keeps each hosted world's {@code Config.autoPause} ({@link Teams#autoPause()}) equal to its {@code game.auto-pause}:
 * after a tick where they differ, a {@link SetAutoPause} goes to the world's inbox, which the next tick admits before
 * its {@link EmptyAutoPause} check. A new world plays its first tick with it off; a {@code config reload} reaches a
 * world at its next tick.
 */
public final class AutoPauses implements HostListener{
    private final Host host;

    public AutoPauses(Host host){
        this.host = host;
    }

    @Override
    public void ticked(HostedWorld world, World state){
        Teams teams = Teams.of(state);
        if(teams == null) return;
        boolean wanted = host.config(world.id()).get(ConfigKeys.GAME_AUTO_PAUSE);
        // a full inbox takes it after a later tick
        if(teams.autoPause() != wanted) state.inbox().offer(new Submission(new SetAutoPause(wanted), Cause.SERVER));
    }
}
