package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.world.*;

/** Vanilla's {@code Groups.player.update()} in {@code Logic.updateEntities}: each added player, in order. */
@Register
public final class PlayerUpdate implements PhaseHandler{
    @Override
    public Phase phase(){
        return Phase.PLAYERS;
    }

    @Override
    public void run(World world){
        Players players = Players.of(world);
        if(players != null) players.list().each(Player::update);
    }
}
