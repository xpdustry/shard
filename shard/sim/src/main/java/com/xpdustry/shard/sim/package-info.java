/**
 * The simulation: one {@link com.xpdustry.shard.sim.world.World} per world, its objects (buildings, units) and
 * tables (tiles, bullets), the fixed tick phases and all gameplay (ARCHITECTURE.md §3, §4). Deterministic: no
 * threads, sockets, clocks, wire bytes, transcendental {@code java.lang.Math}, {@code HashMap}/{@code HashSet} or
 * unordered {@code Set.of}/{@code Map.of}; {@code DeterminismTest} enforces it.
 *
 * <p>Each subpackage names its owner. A block family spans {@code sim.blocks.<family>} (behaviour and state) and
 * {@code compat.v160.blocks.<family>} (its v160.7 codecs), with one owner for both.
 */
package com.xpdustry.shard.sim;
