package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.registry.*;

/**
 * {@code VelComp.update} (priority -1, first): moves by the velocity through tile collisions, stops an axis that didn't
 * move, then applies drag. A server always runs it ({@code !net.client()}).
 */
@Register
public final class VelComponent implements UnitComponent{
    @Override
    public String name(){
        return "VelComp";
    }

    @Override
    public float priority(){
        return -1f;
    }

    @Override
    public boolean applies(UnitParams params){
        return true;
    }

    @Override
    public void update(Unit unit){
        float px = unit.x, py = unit.y;
        unit.move(unit.vel.x, unit.vel.y);
        if(Mathf.equal(px, unit.x)) unit.vel.x = 0;
        if(Mathf.equal(py, unit.y)) unit.vel.y = 0;
        unit.vel.scl(Math.max(1f - unit.drag, 0));
    }
}
