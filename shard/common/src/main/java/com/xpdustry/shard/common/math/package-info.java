/**
 * Arc's {@code arc.math}, bit for bit: {@link com.xpdustry.shard.common.math.Mathf} (the 14-bit sine table, Arc's
 * polynomial {@code atan2}, {@code floor}/{@code round} with Arc's offsets), {@link com.xpdustry.shard.common.math.Angles},
 * {@link com.xpdustry.shard.common.math.Interp} and {@link com.xpdustry.shard.common.math.Rand} (xorshift128+).
 * Transcendentals go through {@link java.lang.StrictMath}, never {@link java.lang.Math}'s, so results don't depend on
 * the JIT's intrinsics or the CPU ({@code checkTickMath}). Arc's
 * {@code arc.util.noise} lives here too: {@link com.xpdustry.shard.common.math.Simplex} and
 * {@link com.xpdustry.shard.common.math.Ridged}, which vanilla's map generation filters call.
 *
 * <p>Randomness is always explicit: helpers take the world's {@code Rand}, there is no global generator, and the
 * {@code *Delta} helpers assume Shard's fixed Δ = 1. Owner: WP02.
 */
package com.xpdustry.shard.common.math;
