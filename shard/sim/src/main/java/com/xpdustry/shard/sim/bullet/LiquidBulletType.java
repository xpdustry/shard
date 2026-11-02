package com.xpdustry.shard.sim.bullet;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * {@code LiquidBulletType}: a blob of liquid that boils away in hot environments, puts out fires it crosses, and on a
 * hit leaves a puddle and cools fires around it instead of the usual hit (no frags, splash or sound). Fires and
 * puddles go to {@link Hazards}. The environment's heat is the rules' attribute; weather's share (WP42) is not added.
 */
@BulletKind("liquid-bullet-type")
public class LiquidBulletType extends BulletType{
    private static final int HEAT = Attributes.id("heat");
    public LiquidDef liquid;
    public float puddleSize, boilTime;

    @Override
    protected void load(BulletDef def, ContentEpoch content){
        super.load(def, content);
        liquid = def.liquid();
        puddleSize = def.puddleSize();
        boilTime = def.boilTime();
    }

    /** {@code liquid.willBoil()}: {@code Attribute.heat.env() >= boilPoint}. */
    boolean willBoil(World world){
        return liquid != null && UnitCalls.rules(world).attributes.get(HEAT) >= liquid.boilPoint();
    }

    @Override
    public void update(Bullet b){
        super.update(b);
        if(liquid == null) return;
        World world = b.world();
        if(willBoil(world) && b.time() >= Mathf.randomSeed(b.id(), boilTime)){
            b.remove();
            return;
        }
        // canExtinguish()
        if(liquid.flammability() < 0.1f && liquid.temperature() <= 0.5f){
            int tile = tileWorld(world, b.x(), b.y());
            Hazards hazards = Hazards.of(world);
            if(tile >= 0 && hazards.hasFire(world.tiles().x(tile), world.tiles().y(tile))){
                hazards.extinguish(tile, 100f);
                b.remove();
                hit(b);
            }
        }
    }

    @Override
    public void despawned(Bullet b){
        super.despawned(b);
        if(liquid != null && !willBoil(b.world())) b.world().publish(new BulletEvent.Hit(id(), b.team(), b.x(), b.y(), b.rotation()));
    }

    @Override
    public void hit(Bullet b, float hitx, float hity, boolean createFrags){
        World world = b.world();
        world.publish(new BulletEvent.Hit(id(), b.team(), hitx, hity, b.rotation()));
        if(liquid == null) return;
        Hazards hazards = Hazards.of(world);
        hazards.puddle(tileWorld(world, hitx, hity), liquid.id(), puddleSize);
        if(liquid.temperature() <= 0.5f && liquid.flammability() < 0.3f){
            float intensity = 400f * (liquid.heatCapacity() / 0.4f) * (puddleSize / 6f);
            hazards.extinguish(tileWorld(world, hitx, hity), intensity);
            for(Point2 p : Geometry.d4){
                hazards.extinguish(tileWorld(world, hitx + p.x() * 8f, hity + p.y() * 8f), intensity);
            }
        }
    }
}
