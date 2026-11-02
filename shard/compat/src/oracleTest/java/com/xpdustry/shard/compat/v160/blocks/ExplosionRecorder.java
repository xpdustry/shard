package com.xpdustry.shard.compat.v160.blocks;

import com.xpdustry.shard.sim.blocks.power.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * A recording stand-in for the generator explosion seam, which wins over {@code combat.Combat}'s because parts sort
 * by class name: it records the damage and fireball calls, and schedules what vanilla schedules, the tile damage pass
 * on the next timer update and each ignition after its delay. The pass computes its hits with {@link ExplosionDamage}
 * for the gate to compare with vanilla's but applies none; the fixtures turn fires off, so vanilla's ignitions do
 * nothing either. {@link GeneratorExplosionOracleTest} runs the real seam.
 */
public final class ExplosionRecorder implements WorldPart, GeneratorExplosions{
    final List<String> calls = new ArrayList<>();
    List<ExplosionDamage.Hit> hits;

    @Override
    public void damage(Building generator, float x, float y, float radius, float damage){
        calls.add("damage " + x + " " + y + " " + radius + " " + damage);
        generator.world().timers().run(0f, new TileDamage(x, y, radius, damage));
    }

    @Override
    public void fire(Building generator, int tile, float delay){
        generator.world().timers().run(delay, new Ignite(tile));
    }

    @Override
    public void fireball(Building generator, float x, float y, float angle, float velocityScl){
        calls.add("fireball " + x + " " + y + " " + angle + " " + velocityScl);
    }

    @Override
    public WorldPart copy(CopyContext context){
        return new ExplosionRecorder();
    }

    @Override
    public void hash(Hasher hasher){
    }

    @Override
    public Persistence persistence(){
        return Persistence.EXTERNAL;
    }

    /** {@code tileDamage}'s {@code Time.run(0f)}, computed but not applied. */
    record TileDamage(float x, float y, float radius, float damage) implements Timers.Task{
        @Override
        public void run(World world){
            world.part(ExplosionRecorder.class).hits = ExplosionDamage.tiles(world, -1, x, y, radius, damage);
        }
    }

    /** {@code Fires.create(tile)}, which does nothing with fires off. */
    record Ignite(int tile) implements Timers.Task{
        @Override
        public void run(World world){
        }
    }
}
