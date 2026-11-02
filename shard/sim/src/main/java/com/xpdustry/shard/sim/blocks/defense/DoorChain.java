package com.xpdustry.shard.sim.blocks.defense;

import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code chained} {@code Seq}: the doors a breadth-first walk reached when it was last rebuilt, one object
 * every door in the chain holds. A fork maps it once for all of them and a checkpoint stores it once, so both cost the
 * chain's length, not its square. Nothing changes it after the walk that made it ({@link Door#updateChained()}).
 */
public final class DoorChain implements Copyable, Checkpointable{
    static final Door[] NONE = {};

    /** The chain in walk order; the first is its origin, whose timer gates the processors. */
    public Door[] doors = NONE;

    public DoorChain(){
    }

    DoorChain(Door[] doors){
        this.doors = doors;
    }

    @Override
    public Copyable blank(){
        return new DoorChain();
    }

    @Override
    public void copyTo(Copyable into, CopyContext context){
        var out = (DoorChain)into;
        out.doors = new Door[doors.length];
        for(int i = 0; i < doors.length; i++) out.doors[i] = context.map(doors[i]);
    }

    @Override
    public void write(CheckpointOut out){
        out.refs("doors", doors, doors.length);
    }

    @Override
    public void read(CheckpointIn in){
        Door[] read = in.refs("doors", Door.class);
        if(read == null) throw new CheckpointException("a door chain without doors");
        doors = read;
    }
}
