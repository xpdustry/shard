package com.xpdustry.shard.sim.blocks.turret;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code TractorBeamTurret.TractorBeamBuild} (TractorBeamTurret.java), the parallax: every
 * {@code retargetTime} ticks it picks the closest enemy unit it may target ({@code targetAir}, {@code targetGround}),
 * turns toward it and, once within {@code shootCone}, pulls it in ({@link Unit#impulseNet}) by {@code force} plus
 * {@code scaledForce} the closer it is, damages it by {@code damage} a tick and applies its status. All of it scales
 * with efficiency (power) and, with coolant, by the coolant's heat capacity.
 *
 * <p>Vanilla keeps the target unit until the next retarget even once it is dead, and so does Shard. Saved: the aim
 * ({@link #turretRotation}). The beam's sound and drawing ({@link #lastX}, {@link #strength}) are kept as vanilla
 * keeps them, though only clients read them.
 */
@Kind("tractor-beam-turret")
public final class TractorBeamTurret extends Building implements Checkpointable{
    /** {@code rotation}: where it points, in degrees. Vanilla's field shadows the building's 0-3 {@link #rotation}. */
    public float turretRotation = 90f;
    public float activationTimer;
    public Unit target;
    public float lastX, lastY, strength;
    public boolean any;
    /** The building's {@code coolantMultiplier}, which shadows the block's. */
    public float coolantMultiplier = 1f;

    @Derived("the block's parameters, read on first use")
    private Params params;

    record Params(BaseTurretParams base, int timerTarget, float retargetTime, float shootCone, float force, float scaledForce,
                  float damage, boolean targetAir, boolean targetGround, int status, float statusDuration, float statusChance){
        static Params of(World world, Building build){
            BlockDef block = build.block();
            var base = BaseTurretParams.of(world, block, 10f, 1f, 0f);
            if(!block.baked()) return new Params(base, 0, 5f, 6f, 0.3f, 0f, 0f, true, false, -1, 300f, 1f);
            var f = block.fields();
            String status = f.s("status");
            int id = status == null || status.equals("none") ? -1 : world.content().id(ContentType.STATUS, status);
            return new Params(base, f.i("timerTarget"), f.f("retargetTime"), f.f("shootCone"), f.f("force"), f.f("scaledForce"),
                f.f("damage"), f.b("targetAir"), f.b("targetGround"), id, f.f("statusDuration"), f.f("statusChance"));
        }
    }

    Params params(){
        if(params == null) params = Params.of(world(), this);
        return params;
    }

    @Override
    public void placed(){
        super.placed();
        activationTimer = params().base.activationTime();
    }

    /** {@code Unit.checkTarget(targetAir, targetGround)}. */
    private static boolean checkTarget(Unit unit, boolean air, boolean ground){
        return (unit.isGrounded() && ground) || (unit.isFlying() && air);
    }

    @Override
    public void updateTile(){
        if(activationTimer > 0){
            activationTimer -= 1f;
            return;
        }
        Params p = params();
        World world = world();
        Rand rand = world.rand();
        float range = p.base.range();
        float eff = efficiency * coolantMultiplier, edelta = eff * delta();
        float x = x(), y = y();
        // retarget
        if(timer(p.timerTarget, p.retargetTime)){
            target = SpatialIndex.of(world).closestEnemy(team, x, y, range, u -> checkTarget(u, p.targetAir, p.targetGround));
        }
        // consume coolant
        var coolant = p.base.coolant();
        if(target != null && coolant != null){
            float maxUsed = coolant.amount;
            int liquid = liquids.current();
            float heatCapacity = world.content().liquid(liquid).heatCapacity();
            float used = Math.min(Math.min(liquids.get(liquid), maxUsed), Math.max(0, (1f / coolantMultiplier) / heatCapacity));
            liquids.remove(liquid, used);
            if(Mathf.chance(rand, 0.06 * used)){
                // coolEffect.at(x + Mathf.range(size * tilesize / 2f), y + Mathf.range(size * tilesize / 2f))
                float half = block().size() * Footprint.TILE_SIZE / 2f;
                Mathf.range(rand, half);
                Mathf.range(rand, half);
            }
            coolantMultiplier = 1f + (used * heatCapacity * coolantMultiplier);
        }
        any = false;
        // look at target
        if(target != null && target.within(x, y, range + target.hitSize / 2f) && target.team != team
            && checkTarget(target, p.targetAir, p.targetGround) && efficiency > 0.02f){
            float dest = Angles.angle(x, y, target.x, target.y);
            turretRotation = Angles.moveToward(turretRotation, dest, p.base.rotateSpeed() * edelta);
            lastX = target.x;
            lastY = target.y;
            strength = Mathf.lerpDelta(strength, 1f, 0.1f);
            // shoot when possible
            if(Angles.within(turretRotation, dest, p.shootCone)){
                if(p.damage > 0){
                    target.damageContinuousPierce(p.damage * eff * timeScale * rules().blockDamage(team));
                }
                if(p.status != -1 && Mathf.chance(rand, p.statusChance)){
                    target.statuses.apply(target, p.status, p.statusDuration);
                }
                any = true;
                float dst = target.dst(x, y);
                target.impulseNet(new Vec2().set(x, y).sub(target.x, target.y).limit((p.force + (1f - dst / range) * p.scaledForce) * edelta));
            }
        }else{
            strength = Mathf.lerpDelta(strength, 0, 0.1f);
        }
    }

    @Override
    public boolean shouldConsume(){
        return super.shouldConsume() && target != null;
    }

    @Override
    public BlockStatus status(double stateTick){
        return activationTimer <= 0 ? super.status(stateTick) : BlockStatus.inactive;
    }

    @Override
    public void write(CheckpointOut out){
        out.f("turretRotation", turretRotation).f("activationTimer", activationTimer).ref("target", target).f("lastX", lastX)
            .f("lastY", lastY).f("strength", strength).bool("any", any).f("coolantMultiplier", coolantMultiplier);
    }

    @Override
    public void read(CheckpointIn in){
        turretRotation = in.f("turretRotation");
        activationTimer = in.f("activationTimer");
        target = in.ref("target", Unit.class);
        lastX = in.f("lastX");
        lastY = in.f("lastY");
        strength = in.f("strength");
        any = in.bool("any");
        coolantMultiplier = in.f("coolantMultiplier");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (TractorBeamTurret)into;
        out.turretRotation = turretRotation;
        out.activationTimer = activationTimer;
        out.target = context.map(target);
        out.lastX = lastX;
        out.lastY = lastY;
        out.strength = strength;
        out.any = any;
        out.coolantMultiplier = coolantMultiplier;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(turretRotation).f(activationTimer).i(target == null ? -1 : target.id()).f(lastX).f(lastY).f(strength).bool(any)
            .f(coolantMultiplier);
    }
}
