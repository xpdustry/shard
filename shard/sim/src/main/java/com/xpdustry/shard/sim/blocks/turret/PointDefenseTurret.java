package com.xpdustry.shard.sim.blocks.turret;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.consume.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code PointDefenseTurret.PointDefenseBuild} (PointDefenseTurret.java), the segment: every
 * {@code retargetTime} ticks it picks the nearest hittable enemy bullet in range from the bullet group's tree
 * ({@link Bullets#intersectQuad}, vanilla's order, so ties break as vanilla's do), turns toward it, and once within
 * {@code shootCone} and reloaded takes {@code bulletDamage} (scaled by the team's block damage) off the bullet's
 * damage, removing it when that runs out. Reload and turning follow efficiency; coolant speeds the reload.
 *
 * <p>The target is the bullet's pooled object ({@link #target}), as vanilla keeps the {@code Bullet}: once the bullet
 * is removed it is dropped, and an object the pool hands out again before the next retarget is the new bullet.
 * Saved: the aim ({@link #turretRotation}).
 */
@Kind("point-defense-turret")
public final class PointDefenseTurret extends Building implements Checkpointable{
    /** {@code rotation}: where it points, in degrees. Vanilla's field shadows the building's 0-3 {@link #rotation}. */
    public float turretRotation = 90f;
    public float activationTimer, reloadCounter;
    /** The target bullet's pooled object ({@link Bullets#object}), or -1. */
    public int target = -1;

    @Derived("the block's parameters, read on first use")
    private Params params;

    record Params(BaseTurretParams base, int timerTarget, float retargetTime, float shootCone, float bulletDamage){
        static Params of(World world, Building build){
            var block = build.block();
            var base = BaseTurretParams.of(world, block, 20f, 2f, 30f);
            if(!block.baked()) return new Params(base, 0, 5f, 5f, 10f);
            var f = block.fields();
            return new Params(base, f.i("timerTarget"), f.f("retargetTime"), f.f("shootCone"), f.f("bulletDamage"));
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

    @Override
    public void updateTile(){
        Params p = params();
        World world = world();
        float range = p.base.range();
        if(timer(p.timerTarget, p.retargetTime)) target = findTarget(range);
        // pooled bullets
        Bullet bullet = Bullet.ofObject(world, target);
        if(bullet != null && !bullet.isAdded()){
            target = -1;
            bullet = null;
        }
        if(bullet == null) target = -1;
        if(p.base.coolant() != null && reloadCounter < p.base.reload()) updateCooling(p.base);
        float x = x(), y = y();
        if(bullet != null && Mathf.within(bullet.x(), bullet.y(), x, y, range) && bullet.team() != team && bullet.type() != null
            && bullet.type().hittable){
            float dest = Angles.angle(x, y, bullet.x(), bullet.y());
            turretRotation = Angles.moveToward(turretRotation, dest, p.base.rotateSpeed() * edelta());
            reloadCounter += edelta();
            // shoot when possible
            if(Angles.within(turretRotation, dest, p.shootCone) && reloadCounter >= p.base.reload()){
                float realDamage = p.bulletDamage * rules().blockDamage(team);
                if(bullet.damage() > realDamage){
                    bullet.damage(bullet.damage() - realDamage);
                }else{
                    bullet.remove();
                }
                // shootSound.at(x + Tmp.v1.x, y + Tmp.v1.y, Mathf.random(0.9f, 1.1f))
                Mathf.random(world.rand(), 0.9f, 1.1f);
                reloadCounter = 0;
            }
        }
    }

    /**
     * {@code Groups.bullet.intersect(x - range, y - range, range * 2, range * 2).min(b -> b.team != team &&
     * b.type().hittable, b -> b.dst2(this))}: the last of the nearest in tree order, as its pooled object, or -1.
     */
    private int findTarget(float range){
        World world = world();
        Bullets bullets = world.bullets();
        BulletTypes types = bullets.types(world);
        float x = x(), y = y();
        int[] best = {-1};
        float[] min = {Float.MAX_VALUE};
        bullets.intersectQuad(x - range, y - range, range * 2, range * 2, (table, row) -> {
            if(table.team[row] == team || !types.get(table.type[row]).hittable) return;
            float dst = Mathf.dst2(table.x[row], table.y[row], x, y);
            if(dst <= min[0]){
                best[0] = table.object[row];
                min[0] = dst;
            }
        });
        return best[0];
    }

    /** {@code ReloadTurretBuild.updateCooling}: coolant speeds the reload by its heat capacity. */
    private void updateCooling(BaseTurretParams base){
        ConsumeLiquidBase coolant = base.coolant();
        if(coolant.efficiency(this) > 0 && efficiency > 0){
            float capacity;
            if(coolant instanceof ConsumeLiquidFilter filter){
                capacity = world().content().liquid(filter.consumed(this)).heatCapacity();
            }else{
                capacity = coolant.consumes(liquids.current()) ? world().content().liquid(liquids.current()).heatCapacity() : 0.4f;
            }
            float amount = coolant.amount * coolant.efficiency(this);
            coolant.update(this);
            reloadCounter += amount * edelta() * capacity * base.coolantMultiplier();
            Rand rand = world().rand();
            if(Mathf.chance(rand, 0.06 * amount)){
                // coolEffect.at(x + Mathf.range(size * tilesize / 2f), y + Mathf.range(size * tilesize / 2f))
                float half = block().size() * Footprint.TILE_SIZE / 2f;
                Mathf.range(rand, half);
                Mathf.range(rand, half);
            }
        }
    }

    @Override
    public boolean shouldConsume(){
        return super.shouldConsume() && target != -1;
    }

    @Override
    public BlockStatus status(double stateTick){
        return activationTimer <= 0 ? super.status(stateTick) : BlockStatus.inactive;
    }

    @Override
    public void write(CheckpointOut out){
        out.f("turretRotation", turretRotation).f("activationTimer", activationTimer).f("reloadCounter", reloadCounter).i("target", target);
    }

    @Override
    public void read(CheckpointIn in){
        turretRotation = in.f("turretRotation");
        activationTimer = in.f("activationTimer");
        reloadCounter = in.f("reloadCounter");
        target = in.i("target");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (PointDefenseTurret)into;
        out.turretRotation = turretRotation;
        out.activationTimer = activationTimer;
        out.reloadCounter = reloadCounter;
        out.target = target;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(turretRotation).f(activationTimer).f(reloadCounter).i(target);
    }
}
