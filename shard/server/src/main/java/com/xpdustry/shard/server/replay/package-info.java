/**
 * Replays from the live server (note 37, phase 2): a recorder per hosted world on
 * {@code WorldSync.record} writes each match's replay bundle (match checkpoints, the inputs, the world's hashes) and its
 * view stream ({@code docs/replay/FORMAT.md}); a replay service makes segments again from a bundle, byte for byte, and bakes
 * whole view streams before the build that recorded them retires; {@code shard replay list|verify|export|bake}.
 * {@code server.replay.view} is the view stream format and the world's view.
 *
 * <p>Owner: the replay side quest (RP2).
 */
package com.xpdustry.shard.server.replay;
