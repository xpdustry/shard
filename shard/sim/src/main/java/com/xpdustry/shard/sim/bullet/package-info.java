/**
 * Bullets: the {@link com.xpdustry.shard.sim.bullet.Bullets} struct-of-arrays table in vanilla {@code Groups.bullet}
 * order, with vanilla's pooled id timing, pooled objects and the group's lazy collision grid; the
 * {@link com.xpdustry.shard.sim.bullet.Bullet} handle the type hooks see; and the bullet types (note 31):
 * {@link com.xpdustry.shard.sim.bullet.BulletType}'s core and the common kinds (basic, artillery, missile, flak, bomb,
 * laser bolt, explosion, empty, liquid, fire), each registered for its kind with
 * {@link com.xpdustry.shard.sim.bullet.BulletKind}.
 *
 * <p>{@link com.xpdustry.shard.sim.bullet.BulletUpdate} is the {@code BULLETS} phase: {@code BulletComp.update} for
 * every bullet, then collisions with units (per enemy team) and buildings (the swept tile raycast), in vanilla's order.
 * Buildings that take bullets their own way implement {@link com.xpdustry.shard.sim.bullet.BulletCollider}. Hit and
 * despawn effects are {@link com.xpdustry.shard.sim.bullet.BulletEvent}s; {@code createNet} bullets publish the
 * {@code Call.createBullet} event {@code compat} sends.
 *
 * <p>The special kinds of WP61: beams that damage along a line when made or while kept alive (laser, continuous laser and
 * flame, sap, rail, shrapnel) through {@link com.xpdustry.shard.sim.bullet.Lasers}, vanilla's line and point damage;
 * the point laser, EMP, lightning and space liquid kinds. Every kind in v160.7's pack is ported; a kind without a
 * class of its own (a mod's) runs its nearest ported ancestor's behaviour and is listed by
 * {@link com.xpdustry.shard.sim.bullet.BulletTypes#unsupported()}. {@code InterceptorBulletType}, {@code MultiBulletType}
 * and {@code PointBulletType} have no content in the pack, so no schema to load them from.
 *
 * <p>Owners: WP12 (the table, ids, the grid), WP41 (the rest), WP61 (special bullet kinds).
 */
package com.xpdustry.shard.sim.bullet;
