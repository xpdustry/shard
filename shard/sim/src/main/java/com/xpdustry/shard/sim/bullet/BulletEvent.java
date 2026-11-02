package com.xpdustry.shard.sim.bullet;

import com.xpdustry.shard.sim.world.*;

/**
 * What bullets show or send. {@link Created} is a vanilla call; the effects are not: a server draws nothing, so the
 * hit and despawn effects are events for observers (a replay viewer draws them from the type's content).
 */
public sealed interface BulletEvent extends WorldEvent{
    /**
     * {@code Call.createBullet(type, team, x, y, angle, damage, velocityScl, lifetimeScl)} from {@code createNet}: the
     * server made the bullet first, then sends the packet.
     */
    record Created(int type, int team, float x, float y, float angle, float damage, float velocityScl, float lifetimeScl)
        implements BulletEvent{
    }

    /** {@code hitEffect.at(x, y, rotation, hitColor)} in {@code BulletType.hit}: a bullet of {@code type} hit there. */
    record Hit(int type, int team, float x, float y, float rotation) implements BulletEvent{
    }

    /** {@code despawnEffect.at(x, y, rotation, hitColor)}: a bullet of {@code type} ended there. */
    record Despawned(int type, int team, float x, float y, float rotation) implements BulletEvent{
    }
}
