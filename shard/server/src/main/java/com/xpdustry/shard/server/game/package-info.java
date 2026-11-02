/**
 * The game flow (WP48): what a hosted world does when its game ends. {@link com.xpdustry.shard.server.game.GameFlow}
 * is vanilla's {@code ServerControl} game-over listener per world: the next map from the world's
 * {@link com.xpdustry.shard.server.game.Rotation} (its {@code game.maps} registry and {@code game.shuffle} mode, or a
 * console {@code nextmap}), the game-over message, and after {@code game.round-extra-time} the world reloaded onto that
 * map with every player moved over their connections; with no next map, the world's players are kicked with
 * {@code gameover} and the world stops. {@link com.xpdustry.shard.server.game.ForceGameOver} is the console's
 * {@code gameover}, a logged admin command. The game-over check itself is {@code :sim}'s {@code CheckGameState}.
 *
 * <p>Owner: WP48.
 */
package com.xpdustry.shard.server.game;
