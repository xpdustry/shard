/**
 * Fog of war, as vanilla has it (WP73): {@link com.xpdustry.shard.sim.fog.FogControl}, each team's discovered and
 * visible tiles, which hide units from targeting and from other teams' entity snapshots, and refuse placement on
 * undiscovered tiles. Buildings stay visible to every client (cosmetic fog); real fog, which withholds them
 * ({@code docs/architecture/fog.md}, note 23), is a separate mode and stays off. Checkpointed.
 *
 * <p>Owner: WP73.
 */
package com.xpdustry.shard.sim.fog;
