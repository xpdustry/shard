/**
 * The view model: what a replay viewer sees of a world at one tick, as {@link com.xpdustry.shard.tools.replay.view.ViewSource}
 * produces it, and its encoding, the view stream ({@code docs/replay/FORMAT.md}). Both vanilla, through the oracle, and
 * Shard implement the source, so a renderer never waits for the sim. Content is named, never numbered: a stream plays
 * on any build whose asset pack knows the names.
 */
package com.xpdustry.shard.tools.replay.view;
