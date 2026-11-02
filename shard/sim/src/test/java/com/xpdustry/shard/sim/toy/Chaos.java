package com.xpdustry.shard.sim.toy;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.world.*;

/**
 * A registered part that, once enabled, makes {@link ChaosHandler} churn the world: it places and removes buildings,
 * spawns and kills units, and fires bullets, all drawn from the world's {@code Rand}. Off by default, so other tests
 * see a quiet world.
 */
@Register
public final class Chaos implements WorldPart{
    public boolean enabled;
    public int placed, removed, spawned, killed, fired;

    @Override
    public WorldPart copy(CopyContext context){
        var out = new Chaos();
        out.enabled = enabled;
        out.placed = placed;
        out.removed = removed;
        out.spawned = spawned;
        out.killed = killed;
        out.fired = fired;
        return out;
    }

    @Override
    public void hash(Hasher hasher){
        hasher.bool(enabled).i(placed).i(removed).i(spawned).i(killed).i(fired);
    }
}
