package com.xpdustry.shard.sim.toy;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/** A controller that drifts toward a home building, which may be gone. */
public final class Follow implements UnitController{
    public Building home;
    public int steps;

    public Follow(Building home){
        this.home = home;
    }

    @Override
    public void updateUnit(Unit unit){
        steps++;
        unit.x += unit.world().rand().range(1f);
        unit.y += home != null && home.isValid() ? 0.25f : -0.25f;
        unit.rotation = com.xpdustry.shard.common.math.Mathf.angle(unit.x, unit.y);
    }

    @Override
    public UnitController copy(CopyContext context){
        var out = new Follow(context.map(home));
        out.steps = steps;
        return out;
    }

    @Override
    public void hash(Hasher hasher){
        hasher.i(home == null ? -1 : home.id()).i(steps);
    }
}
