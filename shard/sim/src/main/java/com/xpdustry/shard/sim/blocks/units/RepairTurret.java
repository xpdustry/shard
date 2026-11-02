package com.xpdustry.shard.sim.blocks.units;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code RepairTurret.RepairPointBuild} (RepairTurret.java): the repair point and repair turret. Every 20
 * ticks it picks the closest damaged unit of its team in {@code repairRadius}, turns toward it ({@code slerpDelta})
 * and, while it points within 30 degrees, heals it by {@code repairSpeed} a tick, scaled by its beam's
 * {@link #strength}, efficiency and, with {@code acceptCoolant}, the coolant's heat capacity. It draws power only
 * while it has a target ({@code consumePowerCond}).
 *
 * <p>The beam's {@code offset} stays zero on a server, which never draws it. Saved: the aim ({@link #turretRotation}).
 */
@Kind("repair-turret")
public final class RepairTurret extends Building implements Checkpointable{
    public Unit target;
    public float strength;
    /** {@code rotation}: where it points, in degrees. Vanilla's field shadows the building's 0-3 {@link #rotation}. */
    public float turretRotation = 90f;

    @Derived("the block's parameters, read on first use")
    private Params params;

    record Params(int timerTarget, float repairRadius, float repairSpeed, boolean acceptCoolant, float coolantMultiplier){
        static Params of(BlockDef block){
            if(!block.baked()) return new Params(0, 50f, 0.3f, false, 1f);
            var f = block.fields();
            return new Params(f.i("timerTarget"), f.f("repairRadius"), f.f("repairSpeed"), f.b("acceptCoolant"), f.f("coolantMultiplier"));
        }
    }

    Params params(){
        if(params == null) params = Params.of(block());
        return params;
    }

    @Override
    public void updateTile(){
        Params p = params();
        float multiplier = 1f;
        if(p.acceptCoolant){
            multiplier = 1f + world().content().liquid(liquids.current()).heatCapacity() * p.coolantMultiplier * optionalEfficiency;
        }
        float x = x(), y = y();
        if(target != null && (target.dead || target.dst(x, y) - target.hitSize / 2f > p.repairRadius || target.health >= target.maxHealth)){
            target = null;
        }
        boolean healed = false;
        if(target != null && efficiency > 0){
            float angle = Angles.angle(x, y, target.x, target.y);
            if(Angles.angleDist(angle, turretRotation) < 30f){
                healed = true;
                target.heal(p.repairSpeed * strength * edelta() * multiplier);
            }
            turretRotation = Mathf.slerpDelta(turretRotation, angle, 0.5f * efficiency * timeScale);
        }
        strength = Mathf.lerpDelta(strength, healed ? 1f : 0f, 0.08f);
        if(timer(p.timerTarget, 20)){
            target = SpatialIndex.of(world()).closest(team, x, y, p.repairRadius, Unit::damaged);
        }
    }

    @Override
    public boolean consumesPowerNow(){
        return target != null;
    }

    @Override
    public boolean shouldConsume(){
        return target != null && enabled;
    }

    @Override
    public BlockStatus status(double stateTick){
        return Mathf.equal(potentialEfficiency, 0f, 0.01f) ? BlockStatus.noInput : super.status(stateTick);
    }

    @Override
    public float range(){
        return params().repairRadius;
    }

    @Override
    public void write(CheckpointOut out){
        out.ref("target", target).f("strength", strength).f("turretRotation", turretRotation);
    }

    @Override
    public void read(CheckpointIn in){
        target = in.ref("target", Unit.class);
        strength = in.f("strength");
        turretRotation = in.f("turretRotation");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (RepairTurret)into;
        out.target = context.map(target);
        out.strength = strength;
        out.turretRotation = turretRotation;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(target == null ? -1 : target.id()).f(strength).f(turretRotation);
    }
}
