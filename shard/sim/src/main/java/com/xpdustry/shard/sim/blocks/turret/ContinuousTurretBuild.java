package com.xpdustry.shard.sim.blocks.turret;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code ContinuousTurret.ContinuousTurretBuild}: fires one long-lived bullet and holds it while it shoots,
 * every frame moving it to the barrel, stretching its aim toward the target at {@code aimChangeSpeed} and keeping it
 * alive. There is no reload. The two kinds are {@link ContinuousTurret} and {@link ContinuousLiquidTurret}.
 *
 * <p>Their bullets are continuous and point lasers (WP61, W2.18): until those kinds are ported, the bullet held is
 * the base bullet's.
 */
public abstract class ContinuousTurretBuild extends TurretBuild{
    public BulletEntries bullets = new BulletEntries();
    public float lastLength = Float.NaN;

    @Override
    public void created(){
        super.created();
        lastLength = block().size() * 4f;
    }

    /** How coolant works here, if at all, vanilla doesn't say: nothing. */
    @Override
    protected void updateCooling(){
    }

    @Override
    public Ammo useAmmo(){
        // nothing used directly
        return peekAmmo();
    }

    @Override
    public boolean hasAmmo(){
        return canConsume();
    }

    @Override
    public boolean shouldConsume(){
        return isShooting;
    }

    /** {@code estimateDps()} (ContinuousTurret.java:43): from the shoot type's damage and interval. */
    @Override
    public float estimateDps(){
        if(!hasAmmo()) return 0f;
        var shootType = type().shootType.type();
        float interval = shootType.def().isA(com.xpdustry.shard.content.KindKey.of("continuous-bullet-type"))
            ? shootType.def().fields().f("damageInterval") : 5f;
        return shootType.damage * 60f / interval;
    }

    @Override
    public void updateTile(){
        super.updateTile();
        World world = world();
        bullets.removeDead(world, this, false);
        if(bullets.any()){
            for(int i = 0; i < bullets.size; i++) updateBullet(world, i);
            wasShooting = true;
            heat = 1f;
            curRecoil = recoilValue();
        }
    }

    /** {@code curRecoil = recoil}: the block's visual recoil, which the shoot pattern doesn't read. */
    private float recoilValue(){
        return block().fields().f("recoil");
    }

    protected void updateBullet(World world, int i){
        TurretType t = type();
        Bullet bullet = bullets.bullet(world, i);
        float bulletX = x() + Angles.trnsx(turretRotation - 90, t.shootX + bullets.x[i], t.shootY + bullets.y[i]);
        float bulletY = y() + Angles.trnsy(turretRotation - 90, t.shootX + bullets.x[i], t.shootY + bullets.y[i]);
        float angle = turretRotation + bullets.rotation[i];
        bullet.rotation(angle);
        bullet.set(bulletX, bulletY);
        // target length of laser
        float shootLength = Math.min(dst(targetX, targetY), t.range);
        // current length of laser
        float curLength = dst(bullet.aimX(), bullet.aimY());
        // resulting length of the bullet (smoothed)
        float resultLength = Mathf.approachDelta(curLength, shootLength, t.aimChangeSpeed);
        // actual aim end point based on length
        var end = new Vec2().trns(turretRotation, lastLength = resultLength).add(x(), y());
        bullet.aim(end.x, end.y);
        if(t.scaleDamageEfficiency){
            bullet.damage(bullet.type().damage * Math.min(efficiency, 1f) * timeScale * bullet.damageMultiplier());
        }
        if(isShooting && hasAmmo()){
            bullet.time(bullet.lifetime() * bullet.type().optimalLifeFract * Math.min(shootWarmup, efficiency));
            bullet.keepAlive(true);
        }
    }

    /** Continuous turrets don't have a concept of reload: they are always firing when possible. */
    @Override
    protected void updateReload(){
    }

    @Override
    protected void updateShooting(){
        if(bullets.any()) return;
        if(canConsume() && !charging() && shootWarmup >= type().minWarmup) shoot(peekAmmo());
    }

    @Override
    protected void turnToTarget(float targetRot){
        turretRotation = Angles.moveToward(turretRotation, targetRot, efficiency * type().rotateSpeed * delta());
    }

    @Override
    protected void handleBullet(Bullet bullet, float offsetX, float offsetY, float angleOffset){
        if(bullet != null){
            bullets.add(bullet, offsetX, offsetY, angleOffset, 0f);
            // make sure the length updates to the last set value
            var end = new Vec2().trns(turretRotation, type().shootY + lastLength).add(x(), y());
            bullet.aim(end.x, end.y);
        }
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.obj("bullets", bullets);
        out.f("lastLength", lastLength);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        bullets = in.obj("bullets", BulletEntries.class);
        if(bullets == null) throw new CheckpointException("a turret's bullet entries are missing");
        lastLength = in.f("lastLength");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (ContinuousTurretBuild)into;
        out.bullets = bullets.copy();
        out.lastLength = lastLength;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        bullets.hash(hasher);
        hasher.f(lastLength);
    }
}
