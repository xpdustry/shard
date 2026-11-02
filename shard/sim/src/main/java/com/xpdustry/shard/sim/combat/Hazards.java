package com.xpdustry.shard.sim.combat;

import com.xpdustry.shard.sim.world.*;

/**
 * Fires and puddles, which bullets, weapons and explosions start and liquids put out; {@code sim.fire.Fires} implements
 * it on a {@link WorldPart} ({@code sim.fire} and {@code sim.puddle} hold the fires and puddles), and a world finds it
 * as the first part that does. Every fire and puddle combat creates comes here, at the point and in the order vanilla
 * calls {@code Fires.create} and {@code Puddles.deposit}, with the arguments it passes. A world without the part burns
 * and spills nothing ({@link #NONE}).
 */
public interface Hazards{
    Hazards NONE = new Hazards(){
    };

    /** {@code Fires.create(Tile)}; {@code tile} is {@code y * width + x}, or -1 for vanilla's {@code null}. */
    default void fire(int tile){
    }

    /** {@code Fires.has(x, y)}. */
    default boolean hasFire(int x, int y){
        return false;
    }

    /** {@code Fires.extinguish(tile, intensity)}. */
    default void extinguish(int tile, float intensity){
    }

    /** {@code Puddles.deposit(tile, liquid, amount)}: {@code deposit(tile, tile, liquid, amount, true, false)}. */
    default void puddle(int tile, int liquid, float amount){
        puddle(tile, tile, liquid, amount, true, false);
    }

    /**
     * {@code Puddles.deposit(tile, source, liquid, amount, initial, cap)}; tiles are {@code y * width + x}, -1 for
     * {@code null}, and {@code liquid} is a content id.
     */
    default void puddle(int tile, int source, int liquid, float amount, boolean initial, boolean cap){
    }

    /**
     * This seam acting on {@code world}: a part that needs its world (parts don't keep one) returns a view bound to
     * it; a recorder returns itself.
     */
    default Hazards in(World world){
        return this;
    }

    static Hazards of(World world){
        Hazards hazards = world.find(Hazards.class);
        return hazards == null ? NONE : hazards.in(world);
    }
}
