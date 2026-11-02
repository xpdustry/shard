/**
 * Hosted worlds (WP24): {@link com.xpdustry.shard.server.host.Host} loads maps from the
 * {@link com.xpdustry.shard.server.host.MapCatalog} into worlds, routes sessions to them and between them, and stops
 * them. Each world's {@link com.xpdustry.shard.server.host.HostedWorld} is its
 * {@link com.xpdustry.shard.server.world.WorldHost}: before a tick it pumps the session mailboxes into the inbox; after
 * it, it reacts to the tick's {@link com.xpdustry.shard.server.host.HostInputs} events (joins, confirms, leaves),
 * sends world streams ({@link com.xpdustry.shard.server.host.WorldSends}) and heartbeats, then hands the tick's intent
 * and game events to the {@link com.xpdustry.shard.server.host.HostListener}s (chat commands, the game flow, plugins).
 * {@link com.xpdustry.shard.server.host.Host#replace} reloads a world onto its next map, moving its players over their
 * connections.
 *
 * <p>Each session's in-world {@link com.xpdustry.shard.sim.unit.Player} is created by its join, added by its
 * {@code connectConfirm} and removed by its leave, and kept by session in the
 * {@link com.xpdustry.shard.server.host.Roster} part, so checkpoints and wakes keep it. Its unit comes from
 * {@link com.xpdustry.shard.sim.unit.PlayerSpawns} (W2.12's {@code Teams}): a confirming or dead player spawns at its
 * team's best core, and the host sends vanilla's {@code playerSpawn} call. A hosted map starts with vanilla's
 * {@code logic.play()} ({@code MapLoader.play}).
 *
 * <p>Owner: WP24.
 */
package com.xpdustry.shard.server.host;
