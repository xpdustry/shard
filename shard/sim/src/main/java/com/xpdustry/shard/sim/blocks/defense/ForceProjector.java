package com.xpdustry.shard.sim.blocks.defense;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.consume.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code ForceProjector.ForceBuild} (ForceProjector.java): a hexagonal shield that absorbs enemy bullets.
 * Its radius warms up with efficiency; each absorbed bullet adds its shield damage to {@link #buildup}, which cools by
 * {@code cooldownNormal} a tick (more with coolant), and when it reaches the shield's health the shield breaks: it
 * drops, cools at {@code cooldownBrokenBase} and comes back once the buildup is gone. Phase fabric adds
 * {@code phaseShieldBoost} health and {@code phaseRadiusBoost} radius while {@link #phaseHeat} is warm, one item every
 * {@code phaseUseTime} ticks.
 *
 * <p>Bullets come from the bullet group's tree in vanilla's order ({@link Bullets#intersectQuad}), so the ones it
 * still holds after their removal this tick are absorbed again, as in vanilla: each draws the hit sound's pitch and
 * adds to the buildup once more, though nothing else happens to them.
 *
 * <p>It is vanilla's only {@code ExplosionShield}: explosion waves and wreck crashes ask it first
 * ({@link ExplosionShields}, {@link #absorbExplosion}). Not ported: the break's {@code Trigger.forceProjectorBreak},
 * which only the campaign reads.
 */
@Kind("force-projector")
public final class ForceProjector extends Building implements Checkpointable{
    public boolean broken = true;
    public float buildup, radscl, hit, warmup, phaseHeat;

    @Derived("the block's parameters, read on first use")
    private Params params;

    /** The block's fields, as {@code ForceProjector} declares them, and its item and coolant consumers. */
    record Params(float phaseUseTime, float phaseRadiusBoost, float phaseShieldBoost, float radius, int sides, float shieldRotation,
                  float shieldHealth, float cooldownNormal, float cooldownLiquid, float cooldownBrokenBase, float crashDamageMultiplier,
                  int timerUse, ConsumeItems itemConsumer, ConsumeLiquidFilter coolantConsumer){
        static Params of(BlockDef block, BlockInfo info){
            if(!block.baked()) return new Params(350f, 80f, 400f, 101.7f, 6, 0f, 700f, 1.75f, 1.5f, 0.35f, 2f, 0, null, null);
            var f = block.fields();
            ConsumeItems items = f.has("itemConsumer") && f.get("itemConsumer") != null ? info.consumers.find(ConsumeItems.class) : null;
            ConsumeLiquidFilter coolant = f.b("consumeCoolant") ? info.consumers.find(ConsumeLiquidFilter.class) : null;
            return new Params(f.f("phaseUseTime"), f.f("phaseRadiusBoost"), f.f("phaseShieldBoost"), f.f("radius"), f.i("sides"),
                f.f("shieldRotation"), f.f("shieldHealth"), f.f("cooldownNormal"), f.f("cooldownLiquid"), f.f("cooldownBrokenBase"),
                f.f("crashDamageMultiplier"), f.i("timerUse"), items, coolant);
        }
    }

    Params params(){
        if(params == null) params = Params.of(block(), info());
        return params;
    }

    @Override
    public void updateTile(){
        Params p = params();
        Rand rand = world().rand();
        boolean phaseValid = p.itemConsumer != null && p.itemConsumer.efficiency(this) > 0;
        phaseHeat = Mathf.lerpDelta(phaseHeat, Mathf.num(phaseValid), 0.1f);
        if(phaseValid && !broken && timer(p.timerUse, p.phaseUseTime / timeScale) && efficiency > 0){
            consume();
        }
        radscl = Mathf.lerpDelta(radscl, broken ? 0f : warmup, 0.05f);
        if(Mathf.chanceDelta(rand, buildup / p.shieldHealth * 0.1f)){
            // Fx.reactorsmoke.at(x + Mathf.range(tilesize / 2f), y + Mathf.range(tilesize / 2f))
            Mathf.range(rand, Footprint.TILE_SIZE / 2f);
            Mathf.range(rand, Footprint.TILE_SIZE / 2f);
        }
        warmup = Mathf.lerpDelta(warmup, efficiency, 0.1f);
        if(buildup > 0){
            float scale = !broken ? p.cooldownNormal : p.cooldownBrokenBase;
            if(p.coolantConsumer != null && p.coolantConsumer.efficiency(this) > 0){
                p.coolantConsumer.update(this);
                scale *= (p.cooldownLiquid * (1f + (world().content().liquid(liquids.current()).heatCapacity() - 0.4f) * 0.9f));
            }
            buildup -= delta() * scale;
        }
        if(broken && buildup <= 0) broken = false;
        if(buildup >= p.shieldHealth + p.phaseShieldBoost * phaseHeat && !broken){
            broken = true;
            buildup = p.shieldHealth;
        }
        if(hit > 0f) hit -= 1f / 5f;
        deflectBullets();
    }

    /** {@code deflectBullets()}: absorbs the enemy bullets inside the shield's polygon. */
    public void deflectBullets(){
        float realRadius = realRadius();
        if(realRadius > 0 && !broken){
            Params p = params();
            World world = world();
            Bullets bullets = world.bullets();
            BulletTypes types = bullets.types(world);
            float x = x(), y = y();
            float[] polygon = polygon(p.sides, x, y, realRadius, p.shieldRotation);
            var found = new ArrayList<Bullet>();
            bullets.intersectQuad(x - realRadius, y - realRadius, realRadius * 2f, realRadius * 2f, (table, row) -> {
                if(table.team[row] != team && types.get(table.type[row]).absorbable && !table.absorbed[row]
                    && inPolygon(polygon, table.x[row], table.y[row])){
                    found.add(Bullet.ofObject(world, table.object[row]));
                }
            });
            // the consumer runs inside vanilla's traversal; absorbing removes nothing from the tree, so after it is the same
            for(Bullet bullet : found){
                bullet.absorb();
                // hitSound.at(bullet.x, bullet.y, 1f + Mathf.range(0.1f), hitSoundVolume)
                Mathf.range(world.rand(), 0.1f);
                hit = 1f;
                buildup += bullet.damage() * bullet.type().shieldDamageMultiplier;
            }
        }
    }

    /**
     * {@code absorbExplosion(ex, ey, damage)}: whether the shield takes an explosion at (ex, ey), adding
     * {@code crashDamageMultiplier} times its damage to the buildup.
     */
    public boolean absorbExplosion(float ex, float ey, float damage){
        Params p = params();
        boolean absorb = !broken && inPolygon(polygon(p.sides, x(), y(), realRadius(), p.shieldRotation), ex, ey);
        if(absorb){
            hit = 1f;
            buildup += damage * p.crashDamageMultiplier;
        }
        return absorb;
    }

    /** {@code realRadius()}. */
    /** {@code pickedUp()}: a payload unit lifted it; the shield starts from nothing (ForceProjector.java:196). */
    @Override
    public void pickedUp(){
        radscl = warmup = 0f;
    }

    public float realRadius(){
        Params p = params();
        return (p.radius + phaseHeat * p.phaseRadiusBoost) * radscl;
    }

    @Override
    public float range(){
        return realRadius();
    }

    /** {@code Intersector.isInRegularPolygon}'s vertices: {@code s.trns(i * 360f / sides + rotation, radius)}. */
    static float[] polygon(int sides, float cx, float cy, float radius, float rotation){
        var out = new float[sides * 2];
        var s = new Vec2();
        for(int i = 0; i < sides; i++){
            s.trns(i * 360f / sides + rotation, radius);
            out[i * 2] = cx + s.x;
            out[i * 2 + 1] = cy + s.y;
        }
        return out;
    }

    /** {@code Intersector.isInPolygon(polygon, 0, length, x, y)}. */
    static boolean inPolygon(float[] polygon, float x, float y){
        boolean oddNodes = false;
        int j = polygon.length - 2;
        for(int i = 0, n = j; i <= n; i += 2){
            float yi = polygon[i + 1];
            float yj = polygon[j + 1];
            if((yi < y && yj >= y) || (yj < y && yi >= y)){
                float xi = polygon[i];
                if(xi + (y - yi) / (yj - yi) * (polygon[j] - xi) < x) oddNodes = !oddNodes;
            }
            j = i;
        }
        return oddNodes;
    }

    @Override
    public void overwrote(List<Building> previous){
        if(!previous.isEmpty() && previous.getFirst().block() == block() && previous.getFirst() instanceof ForceProjector b){
            broken = b.broken;
            buildup = b.buildup;
        }
    }

    @Override
    public double sense(LAccess sensor){
        Params p = params();
        if(sensor == LAccess.heat) return buildup;
        if(sensor == LAccess.shield) return broken ? 0f : Math.max(p.shieldHealth + p.phaseShieldBoost * phaseHeat - buildup, 0);
        return super.sense(sensor);
    }

    @Override
    public void setProp(LAccess prop, double value){
        if(prop == LAccess.shield){
            Params p = params();
            buildup = Math.max(p.shieldHealth + p.phaseShieldBoost * phaseHeat - (float)value, 0f);
        }else{
            super.setProp(prop, value);
        }
    }

    @Override
    public void write(CheckpointOut out){
        out.bool("broken", broken).f("buildup", buildup).f("radscl", radscl).f("hit", hit).f("warmup", warmup).f("phaseHeat", phaseHeat);
    }

    @Override
    public void read(CheckpointIn in){
        broken = in.bool("broken");
        buildup = in.f("buildup");
        radscl = in.f("radscl");
        hit = in.f("hit");
        warmup = in.f("warmup");
        phaseHeat = in.f("phaseHeat");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (ForceProjector)into;
        out.broken = broken;
        out.buildup = buildup;
        out.radscl = radscl;
        out.hit = hit;
        out.warmup = warmup;
        out.phaseHeat = phaseHeat;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.bool(broken).f(buildup).f(radscl).f(hit).f(warmup).f(phaseHeat);
    }
}
