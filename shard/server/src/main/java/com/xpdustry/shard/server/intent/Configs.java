package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.sim.building.*;

/**
 * Plan configs as intents carry them. A plan is world state once queued, and checkpoints store a fixed list of config
 * types ({@code BuildPlan.writeConfig}): the values vanilla's client puts in plans (numbers, strings, points, vectors,
 * byte and int arrays, content) are on it, the rest of {@code readObjectSafe}'s union (buildings, units, teams, logic
 * enums, tech nodes) are not, so a plan's config of those types is dropped at decode. A queue holds a copy of each
 * plan, so nothing in the logged intent is ever world state.
 */
final class Configs{
    private Configs(){
    }

    /** {@code config} if a queued plan may hold it, else null. */
    static Object storable(Object config){
        return storableValue(config) ? config : null;
    }

    private static boolean storableValue(Object config){
        return switch(config){
            case null -> true;
            case Integer i -> true;
            case Long l -> true;
            case Float f -> true;
            case Double d -> true;
            case Boolean b -> true;
            case String s -> true;
            case Point2 p -> true;
            case Vec2 v -> true;
            case byte[] bytes -> true;
            case int[] ints -> true;
            case boolean[] bools -> true;
            case ContentRef ref -> true;
            case ContentRef.Foreign ref -> true;
            case Point2[] points -> true;
            case Vec2[] vecs -> true;
            case Object[] values -> {
                for(Object value : values) if(!storableValue(value)) yield false;
                yield true;
            }
            default -> false;
        };
    }

    /** A deep copy of a storable config: arrays and vectors are new, immutable values shared. */
    static Object copy(Object config){
        return switch(config){
            case Vec2 vec -> new Vec2(vec);
            case byte[] bytes -> bytes.clone();
            case int[] ints -> ints.clone();
            case boolean[] bools -> bools.clone();
            case Point2[] points -> points.clone();
            case Vec2[] vecs -> {
                var out = new Vec2[vecs.length];
                for(int i = 0; i < vecs.length; i++) out[i] = vecs[i] == null ? null : new Vec2(vecs[i]);
                yield out;
            }
            case Object[] values -> {
                var out = new Object[values.length];
                for(int i = 0; i < values.length; i++) out[i] = copy(values[i]);
                yield out;
            }
            case null, default -> config;
        };
    }
}
