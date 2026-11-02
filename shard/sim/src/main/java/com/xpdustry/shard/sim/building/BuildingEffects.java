package com.xpdustry.shard.sim.building;

import com.xpdustry.shard.sim.world.*;

/**
 * The hook where a building's damage and death reach the systems wave 3 ports: explosions and fires
 * ({@code Damage.dynamicExplosion}, {@code Fires.create}), puddles ({@code Puddles.deposit}) and the block's
 * {@code destroyBullet}. The owner of those systems implements it on a {@link WorldPart}; a world finds it as the first
 * part that implements it, and {@link #NONE} until one exists.
 *
 * <p>{@link #NONE} does nothing, which is exact only where vanilla's own call would do nothing: an explosion with no
 * flammability, explosiveness or power, and no puddle or bullet. Elsewhere the world drifts from vanilla until the
 * owner lands.
 */
public interface BuildingEffects{
    /** Does nothing. */
    BuildingEffects NONE = new BuildingEffects(){
    };

    /**
     * {@code Damage.dynamicExplosion(x, y, flammability, explosiveness, power, radius, damage, …)} at the building's
     * centre (BuildingComp.java:1525), while its tile is still intact.
     */
    default void explode(Building building, float flammability, float explosiveness, float power, float radius, boolean damage){
    }

    /** {@code splashLiquid(liquid, amount)} (BuildingComp.java:1467): puddles around a destroyed building. */
    default void splashLiquid(Building building, int liquid, float amount){
    }

    /** {@code Puddles.deposit(tile, source, liquid, amount, initial, cap)} in a world; tiles are {@code y * width + x}. */
    default void puddle(World world, int tile, int source, int liquid, float amount, boolean initial, boolean cap){
    }

    /** {@code afterDestroyed()} (BuildingComp.java:1453): the block's {@code destroyBullet}, once the tile is gone. */
    default void afterDestroyed(Building building){
    }

    /** The world's effects, or {@link #NONE}. */
    static BuildingEffects of(World world){
        BuildingEffects effects = world.find(BuildingEffects.class);
        return effects == null ? NONE : effects;
    }
}
