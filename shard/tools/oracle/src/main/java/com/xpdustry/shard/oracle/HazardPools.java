package com.xpdustry.shard.oracle;

import arc.math.*;
import arc.util.pooling.*;
import mindustry.entities.*;
import mindustry.gen.*;

import java.lang.reflect.*;

/**
 * Vanilla's {@code Fire}, {@code Puddle} and {@code WeatherState} pools are one per JVM: a pooled fire keeps the timers
 * its last {@code reset()} drew, and only a new weather state draws its wind. A fixture that compares fires, puddles or
 * weather with a fresh Shard world empties them first: {@link #empty()} frees what {@code Groups.clear} queued (a load's
 * clear removes the old world's entities), makes each pool with its real factory, clears it, and puts the random and the
 * entity id counter back as they were before all of that.
 */
public final class HazardPools{
    private HazardPools(){
    }

    public static void empty(){
        Vanilla.boot();
        long seed0 = Mathf.rand.seed0, seed1 = Mathf.rand.seed1;
        int lastId = lastId();
        Groups.updatePooling();
        Fire.create();
        Puddle.create();
        WeatherState.create();
        Pools.get(Fire.class, () -> null).clear();
        Pools.get(Puddle.class, () -> null).clear();
        Pools.get(WeatherState.class, () -> null).clear();
        Mathf.rand.setState(seed0, seed1);
        lastId(lastId);
    }

    private static int lastId(){
        try{
            Field field = EntityGroup.class.getDeclaredField("lastId");
            field.setAccessible(true);
            return field.getInt(null);
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
    }

    private static void lastId(int value){
        try{
            Field field = EntityGroup.class.getDeclaredField("lastId");
            field.setAccessible(true);
            field.setInt(null, value);
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
    }
}
