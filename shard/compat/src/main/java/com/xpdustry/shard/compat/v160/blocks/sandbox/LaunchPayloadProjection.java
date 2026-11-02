package com.xpdustry.shard.compat.v160.blocks.sandbox;

import com.xpdustry.shard.sim.blocks.sandbox.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.*;

import java.util.*;

/** One launch payload, frozen for {@link LaunchPayloadLayout}: its id, then its saved fields, items in the epoch's ids. */
public final class LaunchPayloadProjection implements LaunchPayloadLayout.Source{
    private final int id, team;
    private final float lifetime, time, x, y;
    private final List<Codecs.ItemStack> stacks;

    public LaunchPayloadProjection(LaunchPayload payload){
        id = payload.id();
        team = payload.team;
        lifetime = payload.lifetime;
        time = payload.time;
        x = payload.x;
        y = payload.y;
        var out = new ArrayList<Codecs.ItemStack>();
        for(int i = 0; i + 1 < payload.stacks.length; i += 2) out.add(new Codecs.ItemStack(payload.stacks[i], payload.stacks[i + 1]));
        stacks = List.copyOf(out);
    }

    /** The payload's entity id. */
    public int id(){
        return id;
    }

    @Override
    public float lifetime(){
        return lifetime;
    }

    @Override
    public List<Codecs.ItemStack> stacks(){
        return stacks;
    }

    @Override
    public int team(){
        return team;
    }

    @Override
    public float time(){
        return time;
    }

    @Override
    public float x(){
        return x;
    }

    @Override
    public float y(){
        return y;
    }
}
