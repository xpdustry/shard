package com.xpdustry.shard.server.game;

import com.xpdustry.shard.sim.game.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.world.*;

/**
 * The console's {@code gameover} (ServerControl.java:1234): {@code GameOverEvent(state.rules.waveTeam)}, through the
 * world's inbox like every admin action. It ends the game as {@code checkGameState} does: {@code state.gameOver} is
 * set, which vanilla's listener sets when a next map follows, and the event is published.
 */
public record ForceGameOver() implements Command.Admin{
    @Override
    public Verdict apply(World world, Cause cause){
        WaveState state = WaveState.of(world);
        WorldRules rules = world.find(WorldRules.class);
        if(state == null || rules == null) return Verdict.rejected("no game");
        state.gameOver = true;
        world.publish(new GameEvent.GameOver(world.ticks(), rules.rules.waveTeam));
        return Verdict.ADMITTED;
    }
}
