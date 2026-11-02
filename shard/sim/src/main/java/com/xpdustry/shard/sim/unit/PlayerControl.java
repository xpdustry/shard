package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * A unit controlled by a {@link Player}: vanilla's {@code unit.controller() == player}. The player is a reference,
 * mapped on fork and stored as one, never copied with the unit. Valid while the player is added
 * ({@code Player.isValidController}).
 */
public final class PlayerControl implements UnitController, Checkpointable{
    public Player player;

    /** A blank control for a checkpoint to fill. */
    public PlayerControl(){
    }

    public PlayerControl(Player player){
        this.player = player;
    }

    @Override
    public void updateUnit(Unit unit){
    }

    @Override
    public boolean isValidController(){
        return player != null && player.isAdded();
    }

    @Override
    public UnitController copy(CopyContext context){
        return new PlayerControl(context.map(player));
    }

    @Override
    public void hash(Hasher hasher){
        hasher.i(player == null ? -1 : player.id());
    }

    @Override
    public void write(CheckpointOut out){
        out.ref("player", player);
    }

    @Override
    public void read(CheckpointIn in){
        player = in.ref("player", Player.class);
    }
}
