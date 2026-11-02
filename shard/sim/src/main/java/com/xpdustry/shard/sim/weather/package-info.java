/**
 * Weather (WP42): {@link com.xpdustry.shard.sim.weather.WeatherState}, vanilla's pooled weather entity, and
 * {@link com.xpdustry.shard.sim.weather.Weathers}, the world's {@code Groups.weather}, the schedule the rules' weather
 * entries run, its reset at game start and the environment attributes.
 *
 * <p>Phase order, as {@code Logic.update}: the schedule in {@code WEATHER} ({@code updateWeather}, before objectives
 * and the wave countdown), {@code envAttrs} in {@code ENV}, then each event's update in {@code MISC}
 * ({@code Groups.all.update()}), where it fades, pushes units, applies its status and expires. A removed event's
 * object goes back to its pool at the next {@code Groups.updatePooling}, which draws its next id.
 *
 * <p>Owner: WP42.
 */
package com.xpdustry.shard.sim.weather;
