/**
 * Block families, one subpackage each. Each kind is one {@link com.xpdustry.shard.sim.building.Building} subclass,
 * registered with {@link com.xpdustry.shard.sim.building.Kind}; its v160.7 codecs sit in the matching
 * {@code compat.v160.blocks.<family>}. Kinds compose helpers instead of inheriting from each other.
 *
 * <h2>Porting a block family</h2>
 * The router ({@code distribution.Router}, {@code compat.v160.blocks.distribution.RouterCodec},
 * {@code RouterOracleTest}) is the reference; the item source and void are the simplest kinds with and without state.
 * <ol>
 * <li><b>Kind.</b> One class per vanilla building class, keyed by the build kind (the class that declares the
 * building): {@code @Kind("router") public final class Router extends Building}, which also serves the distributor.
 * Public, final, with a public no-argument constructor; the indexer checks. Once registered, the kind is no longer a
 * {@code PassthroughBuilding} anywhere, so every gate below covers it on every bundled map.</li>
 * <li><b>Parameters.</b> Read the block's definition ({@code block().fields().f("speed")}, {@link
 * com.xpdustry.shard.sim.building.BlockInfo} for what the base derives) and cache it in a field marked
 * {@link com.xpdustry.shard.sim.world.Derived}, which checkpoints skip. Guard on {@code block().baked()} with
 * vanilla's default for toy content.</li>
 * <li><b>State.</b> Vanilla's fields, by vanilla's names, as plain values: content as ids ({@code -1} for null), tiles
 * as {@code y * width + x}, buildings and units as references. Allocate per-content arrays in {@code created()}.
 * Implement {@link com.xpdustry.shard.sim.persist.Checkpointable} (every field, every time; the coverage test fails on
 * one neither written nor {@code @Derived}), and override {@code copyState} and {@code hashState}, calling
 * {@code super} first.</li>
 * <li><b>Behaviour.</b> Override vanilla's hooks with vanilla's bodies: {@code updateTile}, {@code acceptItem},
 * {@code handleItem}, {@code removeStack}, {@code onProximityUpdate}, {@code onDestroyed} and so on; the base's
 * {@code dump}, {@code offload}, {@code moveForward}, {@code dumpLiquid}, {@code damage}, {@code control} and status
 * are vanilla's already. Configs are one static {@link com.xpdustry.shard.sim.building.Configs} returned from
 * {@code configs()} (vanilla's {@code config(Class, …)} and {@code configClear}), with {@code config()} giving the
 * value back. Read units through {@code EntityGroups.of(world)}; a kind that acts on units over it implements
 * {@code unitOn}/{@code unitOnAny}. Name what isn't ported in the class comment, with why vanilla never reaches it.</li>
 * <li><b>Construction draws.</b> If vanilla's field initializers or constructor draw from {@code Mathf.rand} or take
 * an entity id, add the kind to {@code BlockInfo}'s table, which {@code Building.drawFields} reads: map loading and
 * payload reads count those draws for buildings Shard never makes. A kind that keeps the values overrides
 * {@code constructed()} and draws the same, in vanilla's field order.</li>
 * <li><b>Events.</b> Only what vanilla sends as a Call becomes a domain event: publish a
 * {@link com.xpdustry.shard.sim.building.BuildingEvent} where vanilla sends it, after its local effects.
 * {@code Buildings.configure}, {@code kill} and {@code healthChanged} publish theirs already.</li>
 * <li><b>Codec.</b> {@code @Register public final class RouterCodec implements BuildingCodec} in
 * {@code compat.v160.blocks.<family>}: {@code revision()} is vanilla's {@code version()}; {@code write}, {@code read}
 * and {@code skip} mirror its {@code write} and {@code read(Reads, byte revision)}, field by field, older revisions
 * included, with content ids through the {@code IdMapping}. A class without {@code write} has an empty body at
 * revision 0.</li>
 * <li><b>Tests.</b> A unit test of the behaviour and a checkpoint round trip in {@code :sim} (see
 * {@code BuildingBaseTest}); an oracle in {@code compat/src/oracleTest/…/blocks} that builds fixtures in vanilla's
 * {@code Sim} and a Shard world, edits both on the same frames, and compares state every frame and the save chunk at
 * the end ({@code RouterOracleTest}; {@code KindRegistry.withKind} swaps in an instrumented class). The bundled-map
 * gates ({@code BuildingBaseOracleTest}, the world stream) then check the kind's bytes on every map with no extra
 * work.</li>
 * </ol>
 */
package com.xpdustry.shard.sim.blocks;
