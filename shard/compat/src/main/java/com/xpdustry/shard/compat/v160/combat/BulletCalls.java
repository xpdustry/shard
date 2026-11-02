package com.xpdustry.shard.compat.v160.combat;

import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.wire.v160_7.*;

/**
 * The packets of {@link BulletEvent}s. Only {@link BulletEvent.Created} is a call ({@code BulletType.createNet}); the
 * effect events have none, so {@link #packet} gives null for them.
 */
public final class BulletCalls{
    private BulletCalls(){
    }

    /** {@code Call.createBullet(type, team, x, y, angle, damage, velocityScl, lifetimeScl)}. */
    public static Packets.CreateBullet createBullet(BulletEvent.Created event){
        return new Packets.CreateBullet((short)event.type(), event.team(), event.x(), event.y(), event.angle(), event.damage(),
            event.velocityScl(), event.lifetimeScl());
    }

    /** The packet {@code event} sends to every client, or null when it sends none. */
    public static Packets.CreateBullet packet(BulletEvent event){
        return event instanceof BulletEvent.Created created ? createBullet(created) : null;
    }
}
