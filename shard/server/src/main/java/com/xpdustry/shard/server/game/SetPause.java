package com.xpdustry.shard.server.game;

import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.world.*;

/**
 * The console's {@code pause <on/off>} (ServerControl.java:682): {@code autoPaused = false}, then
 * {@code state.set(paused or playing)}, through the world's inbox like every admin action, admitted before the tick's
 * pause check as vanilla's posted command runs before {@code Logic.update}: the tick it is admitted in is the first
 * paused (or running) one. Clearing the empty-server flag ({@link Teams#clearAutoPaused()}) keeps a pause made by hand
 * when a player joins; the next {@link EmptyAutoPause} check still pauses an empty world with {@code autoPause} on.
 */
public record SetPause(boolean pause) implements Command.Admin{
    @Override
    public Verdict apply(World world, Cause cause){
        Teams teams = Teams.of(world);
        if(teams != null) teams.clearAutoPaused();
        world.paused(pause);
        return Verdict.ADMITTED;
    }
}
