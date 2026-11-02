package com.xpdustry.shard.sim.blocks.distribution;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code MassDriverBolt} (MassDriverBolt.java): a basic bullet carrying a {@link MassDriver}'s items
 * ({@link DriverBolts}). Each update, unless its target is dead, it checks whether it reached the target on course
 * (within 7 units, or past it at the right angle, snapping back) and hands the items over. Despawning drops a random
 * share of what it still carries (effects only, but every item draws); hitting something also explodes by what it
 * carries. A bolt without data hits every update and keeps flying, as vanilla's does.
 */
@BulletKind("mass-driver-bolt")
public final class MassDriverBolt extends BasicBulletType{
    private static final float HIT_DST = 7f;

    @Override
    public void update(Bullet b){
        super.update(b);
        // data MUST be an instance of DriverBulletData
        DriverBolts.Payload data = DriverBolts.of(b.world()).get(b);
        if(data == null){
            hit(b);
            return;
        }
        // if the target is dead, just keep flying until the bullet explodes
        if(data.to.dead) return;
        MassDriver from = data.from, to = data.to;
        float baseDst = Mathf.dst(from.x(), from.y(), to.x(), to.y());
        float dst1 = b.dst(from.x(), from.y());
        float dst2 = b.dst(to.x(), to.y());
        boolean intersect = false;
        // bullet has gone past the destination point: but did it intersect it?
        if(dst1 > baseDst){
            float angleTo = b.angleTo(to.x(), to.y());
            float baseAngle = Angles.angle(to.x(), to.y(), from.x(), from.y());
            // if angles are nearby, then yes, it did
            if(Angles.near(angleTo, baseAngle, 2f)){
                intersect = true;
                // snap bullet position back; this is used for low-FPS situations
                b.set(to.x() + Angles.trnsx(baseAngle, HIT_DST), to.y() + Angles.trnsy(baseAngle, HIT_DST));
            }
        }
        // if on course and it's in range of the target
        if(Math.abs(dst1 + dst2 - baseDst) < 4f && dst2 <= HIT_DST){
            intersect = true;
        }
        if(intersect){
            to.handlePayload(b, data.items);
        }
    }

    @Override
    public void despawned(Bullet b){
        super.despawned(b);
        DriverBolts.Payload data = DriverBolts.of(b.world()).get(b);
        if(data == null) return;
        Rand rand = b.world().rand();
        for(int i = 0; i < data.items.length; i++){
            int amountDropped = Mathf.random(rand, 0, data.items[i]);
            if(amountDropped > 0){
                // Fx.dropItem.at(b.x, b.y, b.rotation() + Mathf.range(100f), …)
                Mathf.range(rand, 100f);
            }
        }
    }

    @Override
    public void hit(Bullet b, float x, float y, boolean createFrags){
        super.hit(b, x, y, createFrags);
        despawned(b);
        DriverBolts.Payload data = DriverBolts.of(b.world()).get(b);
        if(data != null){
            World world = b.world();
            ContentEpoch content = world.content();
            float explosiveness = 0f, flammability = 0f, power = 0f;
            for(int i = 0; i < data.items.length; i++){
                ItemDef item = content.item(i);
                explosiveness += item.explosiveness() * data.items[i];
                flammability += item.flammability() * data.items[i];
                power += item.charge() * Mathf.pow(data.items[i], 1.1f) * 25f;
            }
            Damage.dynamicExplosion(world, b.x(), b.y(), flammability / 10f, explosiveness / 10f, power, 1f,
                UnitCalls.rules(world).damageExplosions,
                true, Targets.ANY_TEAM);
        }
    }

    @Override
    public void removed(Bullet b){
        super.removed(b);
        DriverBolts.of(b.world()).detach(b);
    }
}
