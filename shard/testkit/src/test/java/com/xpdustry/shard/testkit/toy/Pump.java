package com.xpdustry.shard.testkit.toy;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.world.*;

/** Pumps {@link #RATE} water into itself every tick and counts its strokes; power status cycles with them. */
@Kind("toy-pump")
public class Pump extends Building{
    public static final float RATE = 0.25f;

    public int strokes;

    @Override
    public void updateTile(){
        strokes++;
        liquids.liquids[0] += amount();
        liquids.current = 0;
        power.status = strokes % 4 / 4f;
    }

    /** What this stroke pumps. */
    protected float amount(){
        return RATE;
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        ((Pump)into).strokes = strokes;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(strokes);
    }
}
