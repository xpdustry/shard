/**
 * What every world object shares (ARCHITECTURE.md §3): the {@link com.xpdustry.shard.sim.entity.Entity} base with
 * {@code isAdded()} and attachments, vanilla's ordered groups as {@link com.xpdustry.shard.sim.entity.ActiveList},
 * the per-world {@link com.xpdustry.shard.sim.entity.EntityIds} counter with vanilla's pool timing
 * ({@link com.xpdustry.shard.sim.entity.Pooled}, flushed by {@code Pooling} as {@code Groups.updatePooling}), and
 * {@link com.xpdustry.shard.sim.entity.AttachmentKey}. Fires, puddles, weather and labels are plain entities here.
 *
 * <p>{@link com.xpdustry.shard.sim.entity.EntityGroups} keeps vanilla's {@code Groups.sync} (units, players, and the
 * synced kinds above; the order snapshots write, and {@code getByID}) and {@code Groups.all} (everything but units and
 * buildings), whose {@code update()} {@link com.xpdustry.shard.sim.entity.MiscUpdate} runs. Units are updated from
 * {@code Groups.unit}, buildings from {@code Groups.build}, as in vanilla.
 *
 * <p>Owner: WP12 (entity store, id allocation and iteration order).
 */
package com.xpdustry.shard.sim.entity;
