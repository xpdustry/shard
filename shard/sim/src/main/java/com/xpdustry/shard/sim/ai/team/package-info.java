/**
 * Team AIs (WP82): vanilla's {@code RtsAI} ({@link com.xpdustry.shard.sim.ai.team.RtsAi}), which sends a team's idle
 * commanded units out in squads, and {@code BaseBuilderAI} ({@link com.xpdustry.shard.sim.ai.team.BaseBuilderAi}),
 * which plans random base parts from {@code BaseRegistry} ({@link com.xpdustry.shard.sim.ai.team.BaseRegistry}) around
 * a team's cores for its builders. The rules {@code rules.teams[team].rtsAi} and {@code buildAi} turn them on; each
 * team's pair lives in {@link com.xpdustry.shard.sim.ai.team.TeamAis}, which runs them in vanilla's team loop.
 *
 * <p>Owner: WP82.
 */
package com.xpdustry.shard.sim.ai.team;
