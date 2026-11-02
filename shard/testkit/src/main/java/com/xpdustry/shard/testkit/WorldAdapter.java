package com.xpdustry.shard.testkit;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.world.*;

/**
 * How {@link ShardRunner} reaches world-level state that Shard doesn't model in the foundation: cores, rules and
 * waves. Their owners (cores WP23, rules W1.7, waves WP47) register one from test sources with {@code @Register};
 * every registered adapter is asked in class-name order. Without one, scenarios that set rules or core items fail
 * with a clear message, and the projection leaves those sections out (the diff notes them as not compared).
 */
@Registry
public interface WorldAdapter{
    /** Adds {@code amount} of item {@code item} to {@code team}'s core; false when this adapter has no cores. */
    default boolean coreItems(World world, int team, int item, int amount){
        return false;
    }

    /** Sets the rule named {@code name} (vanilla's field name); false when this adapter doesn't model it. */
    default boolean rule(World world, String name, Object value){
        return false;
    }

    /** Adds this adapter's sections to the projection: {@link Projection.Builder#core}, {@code rule}, {@code waves}. */
    default void project(World world, Projection.Builder out, ShardNames names){
    }
}
