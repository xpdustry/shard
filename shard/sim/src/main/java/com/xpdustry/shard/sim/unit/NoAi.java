package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code NoAI}, {@code dummy}'s controller: no state, always valid, does nothing. It is not an
 * {@code AIController}, so making one draws nothing.
 */
public final class NoAi implements UnitController, Checkpointable{
    @Override
    public void updateUnit(Unit unit){
    }

    @Override
    public UnitController copy(CopyContext context){
        return new NoAi();
    }

    @Override
    public void hash(Hasher hasher){
        hasher.i(0x4e6f4149);
    }

    @Override
    public void write(CheckpointOut out){
    }

    @Override
    public void read(CheckpointIn in){
    }
}
