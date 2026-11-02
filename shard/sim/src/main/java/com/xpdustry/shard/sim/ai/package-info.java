/**
 * Unit and team AI (note 22). Today: vanilla's wave AIs ({@code ai.types}), on the controller seam in
 * {@code sim.unit}, where {@link com.xpdustry.shard.sim.unit.AiController} is vanilla's {@code AIController}; and
 * {@link com.xpdustry.shard.sim.ai.CommandAi}, its {@code CommandAI}, with the commands, stances and formations of
 * {@code sim.ai.command} (WP72). The wave AIs:
 * <ul>
 *   <li>{@link com.xpdustry.shard.sim.ai.GroundAi}, {@link com.xpdustry.shard.sim.ai.SuicideAi},
 *   {@link com.xpdustry.shard.sim.ai.HugAi}: walk the team's enemy-core flowfield
 *   ({@link com.xpdustry.shard.sim.path.PathService}), stepping around lower-id units when stuck
 *   ({@link com.xpdustry.shard.sim.path.AvoidanceProcess});</li>
 *   <li>{@link com.xpdustry.shard.sim.ai.FlyingAi}, {@link com.xpdustry.shard.sim.ai.FlyingFollowAi},
 *   {@link com.xpdustry.shard.sim.ai.DefenderAi}: fly to targets, escorts or cores;</li>
 *   <li>{@link com.xpdustry.shard.sim.ai.MissileAi}: missile units, whose bullets credit their shooter;
 *   {@link com.xpdustry.shard.sim.ai.BoostAi}: RTS units that boost.</li>
 * </ul>
 * {@link com.xpdustry.shard.sim.ai.WaveAis} registers them by the content's names ({@code AiKinds}), so a type's
 * {@code aiController} and {@code controller} make them as vanilla's constructors do, timer draws included;
 * {@link com.xpdustry.shard.sim.ai.WaveFields} preloads the wave team's fields as a map load does. They are held to
 * vanilla frame by frame ({@code WaveFightOracleTest}, {@code FirstWaveOracleTest}, {@code WaveMapsOracleTest}).
 *
 * <p>The support AIs (WP70), which {@link com.xpdustry.shard.sim.ai.SupportAis} registers:
 * {@link com.xpdustry.shard.sim.ai.MinerAi} mines the item its core has least of and brings it back
 * ({@code sim.unit.Miner}, {@code OreIndex}); {@link com.xpdustry.shard.sim.ai.BuilderAi}, as the {@code rebuild} and
 * {@code assist} commands' AIs, rebuilds its team's destroyed blocks and helps a builder nearby, sharing its plan
 * (W2.15's {@code Builder}); {@link com.xpdustry.shard.sim.ai.RepairAi} heals the closest damaged building. They are
 * held to vanilla frame by frame ({@code MinerOracleTest}, {@code BuilderOracleTest}, {@code RepairOracleTest}).
 * {@link com.xpdustry.shard.sim.ai.CargoAi} is the manifold's, carrying from its cargo loader to the unload points;
 * {@link com.xpdustry.shard.sim.ai.PrebuildAi} is a builder's on a {@code prebuildAi} team, whose core units
 * {@link com.xpdustry.shard.sim.ai.PrebuildSpawns} keeps spawned ({@code CargoOracleTest}).
 * {@link com.xpdustry.shard.sim.ai.LogicAi} is the processors' (WP69). {@link com.xpdustry.shard.sim.ai.AssemblerAi}
 * is the assembly drone's, holding the spot its unit assembler gives it (WP81, {@code AssemblerOracleTest}).
 *
 * <p>Team AI ({@code BaseBuilderAI}, {@code RtsAI}: WP82) is in {@code sim.ai.team}.
 *
 * <p>Owners: WP54 (basic), WP70 (support), WP72 (RTS commands), WP82 (team AI).
 */
package com.xpdustry.shard.sim.ai;
