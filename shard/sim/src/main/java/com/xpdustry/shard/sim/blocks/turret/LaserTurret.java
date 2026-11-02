package com.xpdustry.shard.sim.blocks.turret;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.consume.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code LaserTurret.LaserTurretBuild} (the meltdown): a {@link PowerTurret} that holds its laser in the
 * barrel for {@code shootDuration} frames (scaled by efficiency), turning at {@code firingMoveFract} of its speed
 * meanwhile, then cools down: {@code reloadCounter} counts down from {@code reload}, faster with coolant.
 *
 * <p>The laser itself is a continuous laser bullet (WP61, W2.18): until that kind is ported, the bullet the turret
 * holds is the base bullet's.
 */
@Kind("laser-turret")
public final class LaserTurret extends TurretBuild{
    public BulletEntries bullets = new BulletEntries();

    @Override
    public Ammo useAmmo(){
        return type().shootType;
    }

    @Override
    public boolean hasAmmo(){
        return true;
    }

    @Override
    public Ammo peekAmmo(){
        return type().shootType;
    }

    @Override
    public double sense(LAccess sensor){
        return switch(sensor){
            case ammo -> power == null ? 0f : power.status;
            case ammoCapacity -> 1;
            case heat -> type().heatRequirement > 0 ? heatReq : Float.NaN;
            default -> super.sense(sensor);
        };
    }

    /** Cooling is irrelevant here. */
    @Override
    protected void updateCooling(){
    }

    /** Still consumes power while a bullet is around. */
    @Override
    public boolean shouldConsume(){
        if(bullets.any() || isActive()) return true;
        return isShooting;
    }

    @Override
    public void placed(){
        super.placed();
        reloadCounter = type().reload;
    }

    @Override
    public void updateTile(){
        super.updateTile();
        TurretType t = type();
        World world = world();
        bullets.removeDead(world, this, true);
        if(bullets.any()){
            for(int i = 0; i < bullets.size; i++){
                Bullet bullet = bullets.bullet(world, i);
                float bulletX = x() + Angles.trnsx(turretRotation - 90, t.shootX + bullets.x[i], t.shootY + bullets.y[i]);
                float bulletY = y() + Angles.trnsy(turretRotation - 90, t.shootX + bullets.x[i], t.shootY + bullets.y[i]);
                float angle = turretRotation + bullets.rotation[i];
                bullet.rotation(angle);
                bullet.set(bulletX, bulletY);
                bullet.time(bullet.type().lifetime * bullet.type().optimalLifeFract);
                bullet.keepAlive(true);
                bullets.life[i] -= timeScale / Math.max(efficiency, 0.00001f);
            }
            wasShooting = true;
            heat = 1f;
            curRecoil = 1f;
        }else if(reloadCounter > 0){
            ConsumeLiquidBase coolant = t.coolant;
            if(coolant != null){
                int liquid = liquids.current();
                float maxUsed = coolant.amount;
                float used = (cheating() ? maxUsed : Math.min(liquids.get(liquid), maxUsed)) * delta();
                reloadCounter -= used * world.content().liquid(liquid).heatCapacity() * t.coolantMultiplier;
                liquids.remove(liquid, used);
                Rand rand = world.rand();
                if(Mathf.chance(rand, 0.06 * used)){
                    // coolEffect.at(x + Mathf.range(size * tilesize / 2f), y + Mathf.range(size * tilesize / 2f))
                    float half = block().size() * Footprint.TILE_SIZE / 2f;
                    Mathf.range(rand, half);
                    Mathf.range(rand, half);
                }
            }else{
                reloadCounter -= edelta();
            }
        }
    }

    @Override
    public float progress(){
        return 1f - Mathf.clamp(reloadCounter / type().reload);
    }

    /** Updated in {@link #updateTile()} depending on coolant. */
    @Override
    protected void updateReload(){
    }

    @Override
    protected void updateShooting(){
        if(bullets.any()) return;
        TurretType t = type();
        if(reloadCounter <= 0 && efficiency > 0 && !charging() && shootWarmup >= t.minWarmup){
            shoot(peekAmmo());
            reloadCounter = t.reload;
        }
    }

    @Override
    protected void turnToTarget(float targetRot){
        TurretType t = type();
        turretRotation = Angles.moveToward(turretRotation, targetRot, efficiency * t.rotateSpeed * delta() * (bullets.any() ? t.firingMoveFract : 1f));
    }

    @Override
    protected void handleBullet(Bullet bullet, float offsetX, float offsetY, float angleOffset){
        if(bullet != null) bullets.add(bullet, offsetX, offsetY, angleOffset, type().shootDuration);
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.obj("bullets", bullets);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        bullets = in.obj("bullets", BulletEntries.class);
        if(bullets == null) throw new CheckpointException("a turret's bullet entries are missing");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        ((LaserTurret)into).bullets = bullets.copy();
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        bullets.hash(hasher);
    }
}
