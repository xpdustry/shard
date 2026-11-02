/**
 * SQLite in WAL mode with a single writer (note 34 "Storage for players and moderation").
 *
 * <ul>
 *   <li>{@link com.xpdustry.shard.server.store.Store}: one writer thread with bounded queues and group commit; reads
 *       on virtual threads over a fixed set of read-only connections; online backups.</li>
 *   <li>{@link com.xpdustry.shard.server.store.Migrations}: the numbered, checksummed SQL migrations in
 *       resources.</li>
 *   <li>The tables, as static helpers that run on a given connection, so one transaction can combine them:
 *       {@link com.xpdustry.shard.server.store.Players}, {@link com.xpdustry.shard.server.store.Sessions},
 *       {@link com.xpdustry.shard.server.store.Bans}, {@link com.xpdustry.shard.server.store.Kicks},
 *       {@link com.xpdustry.shard.server.store.NameBans}, {@link com.xpdustry.shard.server.store.Grants} (admins and the
 *       whitelist), {@link com.xpdustry.shard.server.store.Notes} and {@link com.xpdustry.shard.server.store.ChatLog}.</li>
 * </ul>
 *
 * <p>Owner: WP50 (schema), WP51 (persistence).
 */
package com.xpdustry.shard.server.store;
