package com.xpdustry.shard.sim.blocks.units;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code RepairTower.RepairTowerBuild} (RepairTower.java), the unit repair tower: every 6 ticks while it
 * could run, it lists the damaged units of its team in {@code range}, and every tick it heals each one still damaged
 * by {@code healAmount}, scaled by efficiency. Heal suppression stops it.
 *
 * <p>{@link #refresh} starts at a random draw ({@link #constructed()}), as vanilla's field initializer does. Vanilla
 * saves nothing of it.
 */
@Kind("repair-tower")
public final class RepairTower extends Building implements Checkpointable{
    /** {@code RepairTower.refreshInterval}. */
    static final float REFRESH_INTERVAL = 6f;

    public float refresh, warmup, totalProgress;
    /** {@code targets}, in the order the team's unit tree gave them. */
    public List<Unit> targets = new ArrayList<>();

    @Derived("the block's parameters, read on first use")
    private Params params;

    record Params(float range, float healAmount, float circleSpeed){
        static Params of(BlockDef block){
            if(!block.baked()) return new Params(80f, 1f, 120f);
            var f = block.fields();
            return new Params(f.f("range"), f.f("healAmount"), f.f("circleSpeed"));
        }
    }

    Params params(){
        if(params == null) params = Params.of(block());
        return params;
    }

    /** {@code refresh = Mathf.random(refreshInterval)}: the field initializer's draw. */
    @Override
    protected void constructed(){
        refresh = Mathf.random(world().rand(), REFRESH_INTERVAL);
    }

    @Override
    public void updateTile(){
        Params p = params();
        if(potentialEfficiency > 0 && (refresh += 1f) >= REFRESH_INTERVAL){
            targets.clear();
            refresh = 0f;
            SpatialIndex.of(world()).nearby(team, x(), y(), p.range, u -> {
                if(u.damaged()) targets.add(u);
            });
        }
        if(checkSuppression()){
            warmup = 0f;
            return;
        }
        boolean any = false;
        if(efficiency > 0){
            for(Unit target : targets){
                if(target.damaged()){
                    target.heal(p.healAmount * edelta());
                    any = true;
                }
            }
        }
        warmup = Mathf.lerpDelta(warmup, any ? efficiency : 0f, 0.08f);
        totalProgress += 1f / p.circleSpeed;
    }

    @Override
    public boolean shouldConsume(){
        return !targets.isEmpty();
    }

    @Override
    public float range(){
        return params().range;
    }

    @Override
    public void write(CheckpointOut out){
        out.f("refresh", refresh).f("warmup", warmup).f("totalProgress", totalProgress).refs("targets", targets.toArray(new Unit[0]), targets.size());
    }

    @Override
    public void read(CheckpointIn in){
        refresh = in.f("refresh");
        warmup = in.f("warmup");
        totalProgress = in.f("totalProgress");
        targets = new ArrayList<>(Arrays.asList(in.refs("targets", Unit.class)));
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (RepairTower)into;
        out.refresh = refresh;
        out.warmup = warmup;
        out.totalProgress = totalProgress;
        out.targets = new ArrayList<>(targets.size());
        for(Unit unit : targets) out.targets.add(context.map(unit));
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(refresh).f(warmup).f(totalProgress).i(targets.size());
        for(Unit unit : targets) hasher.i(unit.id());
    }
}
