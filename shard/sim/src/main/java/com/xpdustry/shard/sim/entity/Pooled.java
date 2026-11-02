package com.xpdustry.shard.sim.entity;

/**
 * The entity kinds vanilla pools ({@code @EntityDef(pooled = true)}) and a server creates. Each has its own Arc pool,
 * so its own free ids in {@link EntityIds}. {@code Decal} and {@code EffectState} are pooled too, but only clients
 * create them ({@code Effect.create} returns early on a headless server).
 */
public enum Pooled{
    BULLET,
    FIRE,
    PUDDLE,
    WEATHER_STATE
}
