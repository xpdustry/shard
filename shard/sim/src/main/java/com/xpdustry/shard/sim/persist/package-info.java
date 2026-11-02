/**
 * Shard's native checkpoints (note 34, ARCHITECTURE.md §4): a world's authoritative state at a tick boundary, in a
 * versioned, checksummed file that records the content hash and the build and schema ids, restored with its derived
 * state rebuilt. {@link com.xpdustry.shard.sim.persist.Checkpoint} is the entry point.
 * {@link com.xpdustry.shard.sim.persist.Checkpointable} and {@link com.xpdustry.shard.sim.persist.ClassCodec} are how
 * a class stores its fields, {@link com.xpdustry.shard.sim.world.Derived} and
 * {@link com.xpdustry.shard.sim.persist.RebuildHook} how derived state is marked and rebuilt, and
 * {@link com.xpdustry.shard.sim.persist.Coverage} checks that no field is missed.
 * {@link com.xpdustry.shard.sim.persist.CrashBundle} and {@link com.xpdustry.shard.sim.persist.Replay} are the crash
 * bundle and its same-build reader. Never v160.7 bytes: msav import and export live in {@code compat.v160.msav}.
 *
 * <p>Owner: the persistence work package (note 34).
 */
package com.xpdustry.shard.sim.persist;
