package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code Groups.player}: the added players, in update order. {@link Persistence#AUTHORITATIVE}: the order;
 * each player's position in it is rebuilt.
 */
@Register
public final class Players implements WorldPart, Checkpointable{
    private final ActiveList<Player> list;

    public Players(){
        list = new ActiveList<>(Player.SLOT);
    }

    private Players(Players source, CopyContext context){
        list = source.list.copy(context);
    }

    /** The world's players, or null in a world built without them. */
    public static Players of(World world){
        return world.find(Players.class);
    }

    /** {@code Groups.player}. */
    public ActiveList<Player> list(){
        return list;
    }

    @Override
    public WorldPart copy(CopyContext context){
        return new Players(this, context);
    }

    @Override
    public void hash(Hasher hasher){
        hasher.i(list.size());
        for(int i = 0; i < list.size(); i++) list.get(i).hashState(hasher);
    }

    @Override
    public void write(CheckpointOut out){
        out.refs("list", list.toList().toArray(Player[]::new), list.size());
    }

    @Override
    public void read(CheckpointIn in){
        Player[] stored = in.refs("list", Player.class);
        if(stored == null || Arrays.asList(stored).contains(null)) throw new CheckpointException("Groups.player holds a missing player");
        list.restore(Arrays.asList(stored));
    }
}
