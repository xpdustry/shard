package com.xpdustry.shard.sim.blocks.defense;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code ShockwaveTower.ShockwaveTowerBuild} (ShockwaveTower.java): once reloaded, every
 * {@code checkInterval} ticks it looks for hittable enemy bullets within {@code range}, and if any, releases a wave
 * that takes {@code bulletDamage} off each (less when there are more than {@code falloffCount}), removing the ones
 * it uses up. Bullets come from the bullet group's tree in vanilla's order ({@link Bullets#intersectQuad}), removed
 * ones it still holds included, as vanilla's.
 *
 * <p>{@link #reloadCounter} starts at a random draw ({@link #constructed()}), as vanilla's field initializer does.
 * Vanilla saves none of it. The wave's sound pitch draw is made.
 */
@Kind("shockwave-tower")
public final class ShockwaveTower extends Building implements Checkpointable{
    public float reloadCounter, heat;

    @Derived("the block's parameters, read on first use")
    private Params params;

    record Params(int timerCheck, float range, float reload, float bulletDamage, float falloffCount, float checkInterval,
                  float cooldownMultiplier){
        static Params of(BlockDef block){
            if(!block.baked()) return new Params(0, 110f, 60f * 1.5f, 160f, 20f, 8f, 1f);
            var f = block.fields();
            return new Params(f.i("timerCheck"), f.f("range"), f.f("reload"), f.f("bulletDamage"), f.f("falloffCount"),
                f.f("checkInterval"), f.f("cooldownMultiplier"));
        }
    }

    Params params(){
        if(params == null) params = Params.of(block());
        return params;
    }

    /** {@code reloadCounter = Mathf.random(reload)}: the field initializer's draw. */
    @Override
    protected void constructed(){
        reloadCounter = Mathf.random(world().rand(), params().reload);
    }

    @Override
    public void updateTile(){
        Params p = params();
        if(potentialEfficiency > 0 && (reloadCounter += edelta()) >= p.reload && timer(p.timerCheck, p.checkInterval)){
            World world = world();
            Bullets bullets = world.bullets();
            BulletTypes types = bullets.types(world);
            float x = x(), y = y(), range = p.range;
            var targets = new ArrayList<Bullet>();
            bullets.intersectQuad(x - range, y - range, range * 2, range * 2, (table, row) -> {
                if(table.team[row] != team && types.get(table.type[row]).hittable
                    && Mathf.within(table.x[row], table.y[row], x, y, range + 1f)){
                    targets.add(Bullet.ofObject(world, table.object[row]));
                }
            });
            if(!targets.isEmpty()){
                heat = 1f;
                reloadCounter = 0f;
                // shootSound.at(x, y, 1f + Mathf.range(0.15f), 1f)
                Mathf.range(world.rand(), 0.15f);
                float waveDamage = Math.min(p.bulletDamage, p.bulletDamage * p.falloffCount / targets.size());
                for(Bullet target : targets){
                    if(target.damage() > waveDamage){
                        target.damage(target.damage() - waveDamage);
                    }else{
                        target.remove();
                    }
                }
            }
        }
        heat = Mathf.clamp(heat - 1f / p.reload * p.cooldownMultiplier);
    }

    @Override
    public double sense(LAccess sensor){
        return switch(sensor){
            case progress -> reloadCounter / params().reload;
            case heat -> heat;
            default -> super.sense(sensor);
        };
    }

    @Override
    public boolean shouldConsume(){
        return reloadCounter < params().reload;
    }

    @Override
    public void write(CheckpointOut out){
        out.f("reloadCounter", reloadCounter).f("heat", heat);
    }

    @Override
    public void read(CheckpointIn in){
        reloadCounter = in.f("reloadCounter");
        heat = in.f("heat");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (ShockwaveTower)into;
        out.reloadCounter = reloadCounter;
        out.heat = heat;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(reloadCounter).f(heat);
    }
}
