package com.xpdustry.shard.sim.blocks.heat;

import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code HeatConductor.HeatConductorBuild} (HeatConductor.java:54): the heat redirectors and the heat router.
 * It takes the heat facing it ({@link Heat#calculate}, tracking cycles in {@link #cameFrom}) and gives it on, the router
 * split over three sides. It updates at most once a tick ({@code state.updateId}, {@link #updateId}): in its own update,
 * or earlier when a neighbour reads its heat. Disabled, it gives none, and with {@code noUpdateDisabled} it doesn't
 * update itself.
 *
 * <p>Vanilla saves none of it: a loaded conductor starts cold and catches up on its first update.
 */
@Kind("heat-conductor")
public final class HeatConductor extends Building implements HeatBlock, HeatConsumer, Checkpointable{
    public float heat;
    public float[] sideHeat = new float[4];
    /** The ids of the heat blocks whose heat came through here, this conductor's chain included. */
    public IntSet cameFrom = new IntSet();
    /** The {@link #updateId} of the last update, -1 before the first. */
    public long lastHeatUpdate = -1;
    @Derived("the block's parameters, read on first use")
    private Params params;

    /** {@code HeatConductor}'s fields. */
    record Params(float visualMaxHeat, boolean splitHeat){
        static Params of(Building building){
            if(!building.block().baked()) return new Params(15f, false);
            var fields = building.block().fields();
            return new Params(fields.f("visualMaxHeat"), fields.b("splitHeat"));
        }
    }

    private Params own(){
        if(params == null) params = Params.of(this);
        return params;
    }

    /** The heat router's {@code splitHeat}: it gives a third of its heat to each side but its back. */
    public boolean splitHeat(){
        return own().splitHeat();
    }

    /**
     * Vanilla's {@code state.updateId}, which {@code Logic.update} raises at the start of each frame: the tick being run,
     * counted from 1, or the last one between ticks.
     */
    public static long updateId(World world){
        return world.atBoundary() ? world.ticks() : world.ticks() + 1;
    }

    @Override
    public void updateTile(){
        updateHeat();
    }

    /** {@code updateHeat()}: once per tick. */
    public void updateHeat(){
        long id = updateId(world());
        if(lastHeatUpdate == id) return;
        lastHeatUpdate = id;
        heat = enabled ? Heat.calculate(this, sideHeat, cameFrom) : 0f;
    }

    @Override
    public float[] sideHeat(){
        return sideHeat;
    }

    @Override
    public float heatRequirement(){
        return own().visualMaxHeat();
    }

    @Override
    public float heat(){
        return heat;
    }

    @Override
    public float heatFrac(){
        return (heat / own().visualMaxHeat()) / (own().splitHeat() ? 3f : 1);
    }

    /** {@code sensor @heat}. */
    @Override
    public double sense(LAccess sensor){
        if(sensor == LAccess.heat) return heat;
        return super.sense(sensor);
    }

    @Override
    public void write(CheckpointOut out){
        out.f("heat", heat).floats("sideHeat", sideHeat).ints("cameFrom", cameFrom.toSeq().toArray()).l("lastHeatUpdate", lastHeatUpdate);
    }

    @Override
    public void read(CheckpointIn in){
        heat = in.f("heat");
        in.floats("sideHeat", sideHeat);
        cameFrom.clear();
        cameFrom.addAll(in.ints("cameFrom"));
        lastHeatUpdate = in.l("lastHeatUpdate");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (HeatConductor)into;
        out.heat = heat;
        out.sideHeat = sideHeat.clone();
        out.cameFrom = cameFrom.copy();
        out.lastHeatUpdate = lastHeatUpdate;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        // a set: its table order is history, so hash its keys sorted
        int[] keys = cameFrom.toSeq().toArray();
        Arrays.sort(keys);
        hasher.f(heat).floats(sideHeat).ints(keys).l(lastHeatUpdate);
    }
}
