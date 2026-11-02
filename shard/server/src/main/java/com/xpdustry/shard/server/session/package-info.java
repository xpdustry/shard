/**
 * {@link com.xpdustry.shard.server.session.Session} (connection, identity, route generation),
 * {@link com.xpdustry.shard.server.session.Viewer} and {@link com.xpdustry.shard.server.session.PlayerEntity} (a
 * session's in-world {@link com.xpdustry.shard.sim.unit.Player}), and the
 * transfer state machine {@code IN_WORLD -> TRANSFERRING -> LOADING -> IN_WORLD}. A session wraps each intent in an
 * {@link com.xpdustry.shard.sim.world.IntentEnvelope} and queues it in its route's bounded
 * {@link com.xpdustry.shard.server.session.Mailbox}, which the world's
 * {@link com.xpdustry.shard.server.session.WorldMailbox} pumps into its inbox at each tick boundary; packets that
 * arrive between routes are dropped. A world's seats ({@code host.world-player-limit}) are taken before a join or a
 * transfer starts, so a full world turns them away before anything moves.
 *
 * <p>Owners: WP15 (sessions), WP24 (join, transfer).
 */
package com.xpdustry.shard.server.session;
