package com.xpdustry.shard.sim.bullet;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.world.*;

/**
 * {@code FlakBulletType}: every {@code flakInterval} ticks it looks for an enemy unit within {@code explodeRange}, and
 * when it finds one it ends {@code explodeDelay} ticks later, splashing where it is then.
 */
@BulletKind("flak-bullet-type")
public final class FlakBulletType extends BasicBulletType{
    public float explodeRange, explodeDelay, flakDelay, flakInterval;

    @Override
    protected void load(BulletDef def, ContentEpoch content){
        super.load(def, content);
        explodeRange = def.explodeRange();
        explodeDelay = def.explodeDelay();
        flakDelay = def.flakDelay();
        flakInterval = def.flakInterval();
    }

    @Override
    public void update(Bullet b){
        super.update(b);
        if(b.time() >= flakDelay && b.fdata() >= 0 && b.timer(2, flakInterval)){
            SpatialIndex index = SpatialIndex.of(b.world());
            if(index == null) return;
            float x = b.x(), y = b.y(), size = explodeRange * 2f;
            index.nearbyEnemies(b.team(), x - size / 2f, y - size / 2f, size, size, unit -> {
                if(b.fdata() < 0f || !Targets.checkTarget(unit, collidesAir, collidesGround) || !unit.targetable(b.team())) return;
                if(unit.within(b.x(), b.y(), explodeRange + unit.hitSize / 2f)){
                    b.fdata(-1f);
                    b.world().timers().run(explodeDelay, new Trigger(b.object()));
                }
            });
        }
    }

    /**
     * {@code Time.run(explodeDelay, () -> { if(b.fdata < 0) b.time = b.lifetime; })}. The closure holds the pooled
     * bullet object, so this holds its {@link Bullets#object} number: whichever live bullet is that object then.
     */
    public record Trigger(int object) implements Timers.Task{
        @Override
        public void run(World world){
            Bullets bullets = world.bullets();
            int row = bullets.rowOfObject(object);
            if(row >= 0 && bullets.fdata[row] < 0) bullets.time[row] = bullets.lifetime[row];
        }
    }
}
