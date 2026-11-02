/**
 * The world scheduler: earliest deadline first on {@code tick-N} platform threads, at most 3 catch-up ticks per turn,
 * staggered snapshot phases, tick histograms, the watchdog, per-system CPU accounting.
 *
 * <p>Owner: WP13 (scheduler), WP92 (density).
 */
package com.xpdustry.shard.server.sched;
