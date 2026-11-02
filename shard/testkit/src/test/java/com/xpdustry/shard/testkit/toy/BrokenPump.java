package com.xpdustry.shard.testkit.toy;

import com.xpdustry.shard.sim.building.*;

/** {@link Pump} with an injected off-by-one: its 100th stroke pumps one extra unit, so it diverges at tick 100. */
@Kind("toy-pump-broken")
public final class BrokenPump extends Pump{
    public static final int BROKEN_STROKE = 100;

    @Override
    protected float amount(){
        return strokes == BROKEN_STROKE ? RATE + 1f : RATE;
    }
}
