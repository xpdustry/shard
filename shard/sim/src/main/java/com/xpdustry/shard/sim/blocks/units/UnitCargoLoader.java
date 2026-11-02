package com.xpdustry.shard.sim.blocks.units;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code UnitCargoLoader.UnitTransportSourceBuild} (UnitCargoLoader.java): takes any item up to its
 * capacity and builds its own cargo unit ({@code unitType}, a manifold), whose {@code CargoAI} carries its items to the
 * team's unload points ({@link UnitCargoUnloadPoint}). While it has no unit and the team may make one, it builds one
 * over {@code unitBuildTime} ticks at its efficiency; then it spawns it over itself, tethered to it
 * ({@link Unit#tether}), facing up, and tells clients ({@link CargoEvent.TetherSpawned}). It consumes (power and
 * nitrogen) only while it has no unit. {@link #warmup} and {@link #readyness} ease toward the efficiency and toward
 * having a unit.
 *
 * <p>A loaded loader finds its unit by id on its first update ({@link #readUnitId}), since units are read after
 * buildings. Vanilla quirk, kept: until then its save writes no unit, as {@code TargetDummy}'s does.
 */
@Kind("unit-cargo-loader")
public final class UnitCargoLoader extends Building implements Checkpointable{
    /** The unit's id as read from a save, until the first update finds it; -1 otherwise. */
    public int readUnitId = -1;
    public float buildProgress, totalProgress;
    public float warmup, readyness;
    public Unit unit;

    @Derived("the block's unitType and unitBuildTime, read on first use")
    private Params params;

    private record Params(UnitDef unitType, float unitBuildTime){
    }

    private Params params(){
        if(params == null){
            BlockDef block = block();
            var f = block.fields();
            params = new Params(world().content().unit(block.baked() ? f.s("unitType") : "manifold"), block.baked() ? f.f("unitBuildTime") : 60f * 8f);
        }
        return params;
    }

    @Override
    public void updateTile(){
        World world = world();
        Params p = params();
        // unit was lost/destroyed
        if(unit != null && (unit.dead || !unit.isAdded())) unit = null;

        if(readUnitId != -1){
            EntityGroups groups = EntityGroups.of(world);
            unit = groups != null && groups.byId(readUnitId) instanceof Unit found ? found : null;
            // a server always forgets the id, found or not
            readUnitId = -1;
        }

        warmup = Mathf.approachDelta(warmup, efficiency, 1f / 60f);
        readyness = Mathf.approachDelta(readyness, unit != null ? 1f : 0f, 1f / 60f);

        if(unit == null && Units.canCreate(world, team, p.unitType)){
            buildProgress += edelta() / p.unitBuildTime;
            totalProgress += edelta();

            if(buildProgress >= 1f){
                unit = Unit.create(world, p.unitType, team);
                if(unit.params().tethered()) unit.tether = this;
                unit.set(x(), y());
                unit.rotation = 90f;
                unit.add();
                // Call.unitTetherBlockSpawned runs spawned(id) on the server too
                buildProgress = 0f;
                world.publish(new CargoEvent.TetherSpawned(world.ticks(), tile(), unit.id()));
            }
        }
    }

    @Override
    public boolean acceptItem(Building source, int item){
        return items.total() < info().itemCapacity;
    }

    /** {@code shouldConsume()}: only while it has no unit; vanilla's override ignores {@code enabled}. */
    @Override
    public boolean shouldConsume(){
        return unit == null;
    }

    @Override
    public void write(CheckpointOut out){
        out.i("readUnitId", readUnitId).f("buildProgress", buildProgress).f("totalProgress", totalProgress).f("warmup", warmup)
            .f("readyness", readyness).ref("unit", unit);
    }

    @Override
    public void read(CheckpointIn in){
        readUnitId = in.i("readUnitId");
        buildProgress = in.f("buildProgress");
        totalProgress = in.f("totalProgress");
        warmup = in.f("warmup");
        readyness = in.f("readyness");
        unit = in.ref("unit", Unit.class);
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (UnitCargoLoader)into;
        out.readUnitId = readUnitId;
        out.buildProgress = buildProgress;
        out.totalProgress = totalProgress;
        out.warmup = warmup;
        out.readyness = readyness;
        out.unit = context.map(unit);
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(readUnitId).f(buildProgress).f(totalProgress).f(warmup).f(readyness).i(unit == null ? -1 : unit.id());
    }
}
