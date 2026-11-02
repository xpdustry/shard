package com.xpdustry.shard.sim.bullet;

/**
 * {@code BasicBulletType}: the sprite bullet most turrets and units fire. Its own code only draws, so it behaves as
 * {@link BulletType}; it is the base of the artillery, missile, flak, bomb and laser bolt kinds.
 */
@BulletKind("basic-bullet-type")
public class BasicBulletType extends BulletType{
}
