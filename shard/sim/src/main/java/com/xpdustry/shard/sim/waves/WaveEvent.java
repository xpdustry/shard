package com.xpdustry.shard.sim.waves;

import com.xpdustry.shard.sim.world.*;

/**
 * The waves' {@link WorldEvent}s, in vanilla's order: during a wave, a shockwave's deaths (as {@code UnitEvent}s),
 * then {@link UnitSpawned} for each unit in spawn order, then {@link Started}.
 */
public sealed interface WaveEvent extends WorldEvent{
    /**
     * {@code Call.spawnEffect(x, y, rotation, type)} (unreliable): a wave unit was spawned, right after it was added
     * and given the spawn statuses, before its group's effect, items, shield and payloads.
     *
     * @param unit the unit's entity id
     * @param type its unit type id
     */
    record UnitSpawned(int unit, float x, float y, float rotation, int type) implements WaveEvent{
    }

    /**
     * {@code WaveEvent}, which sends no call: a wave was spawned and the countdown restarted. Clients learn the wave
     * from state snapshots.
     *
     * @param wave {@code state.wave} after it: the next wave's number
     */
    record Started(int wave) implements WaveEvent{
    }
}
