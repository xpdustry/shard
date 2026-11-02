/**
 * Waves (WP47): {@link com.xpdustry.shard.sim.waves.WaveState} (the wave number, countdown, enemy count and game over
 * of vanilla's {@code GameState}), {@link com.xpdustry.shard.sim.waves.WaveSpawner} (spawn points, the wave's units,
 * the drop zones' shockwave and push), {@link com.xpdustry.shard.sim.waves.Waves} ({@code runWave}, the default and
 * generated spawn group lists) and {@link com.xpdustry.shard.sim.waves.WaveCores}, the team data seam the spawner
 * reads cores through.
 *
 * <p>Phase order, as {@code Logic.update}: the enemy count in {@code TEAMS} (-250), the countdown and
 * {@code runWave} in {@code WAVES}, the drop zone push in each unit's {@code UnitComp} update. A wave publishes
 * {@link com.xpdustry.shard.sim.waves.WaveEvent}s in vanilla's call order.
 *
 * <p>Hosting a map: after {@code endLoad}, call {@code WaveSpawner.reset(world)} (vanilla's {@code WorldLoadEvent}) and
 * set {@code WaveState}'s wave and countdown from the save; at game start, {@code WaveState.play(rules)} then
 * {@code Weathers.play(world)}. Maps with no spawn groups get {@link com.xpdustry.shard.sim.waves.Waves#defaults}.
 *
 * <p>Owner: WP47.
 */
package com.xpdustry.shard.sim.waves;
