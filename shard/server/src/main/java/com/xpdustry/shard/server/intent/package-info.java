/**
 * Client packets as intents (WP26, ARCHITECTURE.md §4): {@link com.xpdustry.shard.server.intent.Intents} decodes each
 * gameplay packet on its connection's thread into an immutable intent, within the codecs' limits, and offers it to the
 * session's mailbox; only {@code clientSnapshot}s coalesce. The world admits each in {@code COMMANDS} as vanilla's
 * handler does: {@link com.xpdustry.shard.server.intent.Snapshot} validates movement and replaces the builder's plan
 * queue, the other intents validate their call, ask the action filters
 * ({@link com.xpdustry.shard.server.intent.action}) and apply it. What admission sends back (corrections, rollbacks,
 * forwards) is published as {@link com.xpdustry.shard.server.intent.IntentCall}s with their recipients, for the sync
 * layer's {@code Outbox}.
 *
 * <p>What admission remembers per player (the last snapshot, rate keepers, the view) is the player's
 * {@link com.xpdustry.shard.server.intent.PlayerInput} attachment, so forks and replays admit the same way. Each intent
 * carries the {@link com.xpdustry.shard.server.intent.Policy} it was decoded under, and values the world must resolve
 * (configs, entity references) as the client's bytes, decoded again at admission.
 *
 * <p>Owner: WP26.
 */
package com.xpdustry.shard.server.intent;
