package com.xpdustry.shard.sim.blocks.sandbox;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code LiquidSource.LiquidSourceBuild} (LiquidSource.java:61): every tick it fills itself to capacity with
 * its configured liquid and dumps it to its neighbours ({@code dumpLiquid}, scaling 2); with none, it empties itself.
 * Configured with a liquid, cleared with null; {@link #config()} gives the liquid back. Logic can't disable it
 * ({@code noUpdateDisabled}).
 */
@Kind("liquid-source")
public final class LiquidSource extends Building implements Checkpointable{
    static final Configs<LiquidSource> CONFIGS = Configs.of(LiquidSource.class)
        .on(ContentType.LIQUID, (source, liquid) -> source.source = liquid)
        .clear(source -> source.source = -1);

    /** The liquid it makes, or -1. */
    public int source = -1;

    @Override
    public void updateTile(){
        if(source == -1){
            liquids.clear();
        }else{
            liquids.set(source, info().liquidCapacity);
            dumpLiquid(source);
        }
    }

    @Override
    protected Configs<?> configs(){
        return CONFIGS;
    }

    @Override
    public Object config(){
        return source == -1 ? null : ContentRef.liquid(source);
    }

    @Override
    public void write(CheckpointOut out){
        out.i("source", source);
    }

    @Override
    public void read(CheckpointIn in){
        source = in.i("source");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        ((LiquidSource)into).source = source;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(source);
    }
}
