package com.xpdustry.shard.sim.unit.ability;

import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * The state of the abilities that keep nothing but a {@code timer} (vanilla's field of that name): the status, shield
 * regen, energy and suppression fields, and unit spawning.
 */
public final class Timed extends UnitAbility{
    public float timer;

    @Override
    public UnitAbility copy(){
        Timed out = copyInto(new Timed());
        out.timer = timer;
        return out;
    }

    @Override
    public void hash(Hasher hasher){
        super.hash(hasher);
        hasher.f(timer);
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.f("timer", timer);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        timer = in.f("timer");
    }
}
