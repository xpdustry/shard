package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * One status effect on a unit (vanilla {@code StatusEntry}). The multipliers are used only by dynamic effects, and
 * written only when they differ from their defaults ({@code TypeIO.writeStatus}). WP42 ports the effects.
 *
 * <p>Every field is {@link Persistence#AUTHORITATIVE}.
 */
public final class StatusEntry implements Checkpointable{
    /** The status effect's content id. */
    public int effect;
    /** Frames left. */
    public float time;
    public float damageTime;
    public float damageMultiplier = 1f, healthMultiplier = 1f, speedMultiplier = 1f, reloadMultiplier = 1f;
    public float buildSpeedMultiplier = 1f, dragMultiplier = 1f, armorOverride = -1f;

    /** A blank entry for a checkpoint to fill. */
    public StatusEntry(){
    }

    public StatusEntry(int effect, float time){
        this.effect = effect;
        this.time = time;
    }

    StatusEntry copy(){
        var out = new StatusEntry(effect, time);
        out.damageTime = damageTime;
        out.damageMultiplier = damageMultiplier;
        out.healthMultiplier = healthMultiplier;
        out.speedMultiplier = speedMultiplier;
        out.reloadMultiplier = reloadMultiplier;
        out.buildSpeedMultiplier = buildSpeedMultiplier;
        out.dragMultiplier = dragMultiplier;
        out.armorOverride = armorOverride;
        return out;
    }

    @Override
    public void write(CheckpointOut out){
        out.i("effect", effect).f("time", time).f("damageTime", damageTime).f("damageMultiplier", damageMultiplier)
            .f("healthMultiplier", healthMultiplier).f("speedMultiplier", speedMultiplier).f("reloadMultiplier", reloadMultiplier)
            .f("buildSpeedMultiplier", buildSpeedMultiplier).f("dragMultiplier", dragMultiplier).f("armorOverride", armorOverride);
    }

    @Override
    public void read(CheckpointIn in){
        effect = in.i("effect");
        time = in.f("time");
        damageTime = in.f("damageTime");
        damageMultiplier = in.f("damageMultiplier");
        healthMultiplier = in.f("healthMultiplier");
        speedMultiplier = in.f("speedMultiplier");
        reloadMultiplier = in.f("reloadMultiplier");
        buildSpeedMultiplier = in.f("buildSpeedMultiplier");
        dragMultiplier = in.f("dragMultiplier");
        armorOverride = in.f("armorOverride");
    }

    void hash(Hasher hasher){
        hasher.i(effect).f(time).f(damageTime).f(damageMultiplier).f(healthMultiplier).f(speedMultiplier);
        hasher.f(reloadMultiplier).f(buildSpeedMultiplier).f(dragMultiplier).f(armorOverride);
    }
}
