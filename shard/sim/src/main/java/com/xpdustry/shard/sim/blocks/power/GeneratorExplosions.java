package com.xpdustry.shard.sim.blocks.power;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Where a generator's explosion ({@code GeneratorBuild.onExplosion}, PowerGenerator.java:118) reaches systems other
 * work packages own: area damage (WP41), fires and bullets. {@link Generator} makes vanilla's random draws for them in
 * vanilla's order, breaks props and leaves puddles itself, and hands the rest here. A world finds it as the first
 * {@link WorldPart} implementing it; {@link #NONE} until one exists, which applies nothing.
 *
 * <p>{@link #NONE} is exact only for the generators whose explosion is all props: no damage, no ignition, no
 * fireballs. A fireball's own creation draws from the random ({@code BulletType.create}), so its implementation
 * makes those draws too.
 */
public interface GeneratorExplosions{
    GeneratorExplosions NONE = new GeneratorExplosions(){
    };

    /**
     * {@code Damage.damage(x, y, radius, damage)}: every unit and building, of every team, around (x, y) in world
     * units ({@link ExplosionDamage} says what it hits). Vanilla applies the building pass on the next timer update.
     */
    default void damage(Building generator, float x, float y, float radius, float damage){
    }

    /** {@code Time.run(delay, () -> Fires.create(tile))}; {@code tile} is {@code y * width + x}, in the map. */
    default void fire(Building generator, int tile, float delay){
    }

    /** {@code Bullets.fireball.createNet(Team.derelict, x, y, angle, -1f, velocityScl, 1f)}. */
    default void fireball(Building generator, float x, float y, float angle, float velocityScl){
    }

    static GeneratorExplosions of(World world){
        GeneratorExplosions explosions = world.find(GeneratorExplosions.class);
        return explosions == null ? NONE : explosions;
    }
}
