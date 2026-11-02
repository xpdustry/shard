package com.xpdustry.shard.sim.blocks.defense;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code ShockMine.ShockMineBuild} (ShockMine.java): when an enemy ground unit stands on it, at most every
 * {@code cooldown} ticks, it arcs {@code tendrils} lightning bolts of ground-only lightning in random directions, fires
 * its {@code bullet} (if any) in {@code shots} even directions, and takes {@code tileDamage} itself. It never updates;
 * the unit's own update calls {@link #unitOn}. Its timer is all its state.
 */
@Kind("shock-mine")
public final class ShockMine extends Building{
    @Derived("the block's mine parameters, read on first use")
    private Params params;

    /** {@code timerDamage}, {@code cooldown}, {@code tileDamage}, {@code damage}, {@code length}, {@code tendrils}, {@code shots}, {@code inaccuracy}, {@code bullet}. */
    private record Params(int timerDamage, float cooldown, float tileDamage, float damage, int length, int tendrils, int shots,
                          float inaccuracy, BulletDef bullet){
    }

    @Override
    public void unitOn(Unit unit){
        Params p = params();
        if(enabled && unit.team != team && timer(p.timerDamage, p.cooldown)){
            triggered();
            damage(p.tileDamage);
        }
    }

    /** {@code triggered()}: the lightning, then the bullets. */
    public void triggered(){
        Params p = params();
        World world = world();
        BulletType ground = world.bullets().types(world).get(world.content().bullet("damage-lightning-ground"));
        for(int i = 0; i < p.tendrils; i++){
            // Mathf.random(360f) is an argument: drawn before the lightning's own draws
            float angle = Mathf.random(world.rand(), 360f);
            Lightning.create(world, team, ground, p.damage, x(), y(), angle, p.length);
        }
        if(p.bullet != null){
            BulletType type = world.bullets().types(world).get(p.bullet);
            for(int i = 0; i < p.shots; i++){
                float angle = (360f / p.shots) * i + Mathf.random(world.rand(), p.inaccuracy);
                type.create(world, this, team, x(), y(), angle);
            }
        }
    }

    private Params params(){
        if(params == null){
            BlockDef block = block();
            if(block.baked()){
                var f = block.fields();
                String bullet = f.has("bullet") ? f.s("bullet") : null;
                params = new Params(f.i("timerDamage"), f.f("cooldown"), f.f("tileDamage"), f.f("damage"), f.i("length"), f.i("tendrils"),
                    f.i("shots"), f.f("inaccuracy"), bullet == null ? null : world().content().bullet(bullet));
            }else{
                params = new Params(1, 80f, 5f, 13f, 10, 6, 6, 0f, null);
            }
        }
        return params;
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        ((ShockMine)into).params = params;
    }
}
