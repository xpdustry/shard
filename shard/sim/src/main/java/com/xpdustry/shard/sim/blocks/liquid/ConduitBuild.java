package com.xpdustry.shard.sim.blocks.liquid;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code Conduit.ConduitBuild} (Conduit.java:174): once a tick, while it holds any of its current liquid, a
 * conduit moves it into the building in front ({@code moveLiquidForward}, with the block's {@code liquidPressure}); with
 * nothing in front, a conduit that {@code leaks} spills two thirds of it as a puddle onto open ground. It takes a
 * liquid from anywhere but its front, while it holds that liquid or less than 0.2 of another. An empty conduit
 * sleeps, and anything offered to it wakes it.
 *
 * <p>The two kinds are the conduit (conduit, pulse conduit) and the armored conduit (plated, reinforced), which
 * doesn't leak and takes from its sides only from conduits, junctions and direction bridges. Vanilla's
 * {@code instanceof ConduitBuild} checks are this class.
 *
 * <p>Not ported: drawing state ({@code smoothLiquid}, blending and caps, which {@code onProximityUpdate} computes only
 * to draw), the placement-line replacements, and {@code next()} ({@code ChainedBuilding}, which only drawing and
 * bridges' placement read).
 */
public abstract sealed class ConduitBuild extends Building permits Conduit, ArmoredConduit{
    @Derived("the block's flow timer index, read on first use")
    private int timerFlow = -1;
    @Derived("the block's leaks flag, read on first use")
    private int leaks = -1;

    private int timerFlow(){
        if(timerFlow == -1) timerFlow = block().baked() ? block().fields().i("timerFlow") : 0;
        return timerFlow;
    }

    private boolean leaks(){
        if(leaks == -1) leaks = !block().baked() || block().fields().b("leaks") ? 1 : 0;
        return leaks == 1;
    }

    @Override
    public boolean acceptLiquid(Building source, int liquid){
        noSleep();
        return (liquids.current() == liquid || liquids.currentAmount() < 0.2f)
            && (source == this || (source.relativeTo(tileX(), tileY()) + 2) % 4 != rotation);
    }

    @Override
    public void updateTile(){
        if(liquids.currentAmount() > 0.0001f && timer(timerFlow(), 1)){
            moveLiquidForward(leaks(), liquids.current());
            noSleep();
        }else{
            sleep();
        }
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (ConduitBuild)into;
        out.timerFlow = timerFlow;
        out.leaks = leaks;
    }
}
