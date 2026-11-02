package com.xpdustry.shard.compat.v160.sync;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.combat.*;
import com.xpdustry.shard.sim.bullet.*;

/**
 * A bullet's Call ({@link BulletEvent}): {@code Created} is {@code createBullet} ({@code BulletType.createNet}), to
 * everyone; hits and despawns are effects, which a server doesn't send.
 */
@Register
public final class BulletCallMapping implements CallMapping<BulletEvent>{
    @Override
    public Class<BulletEvent> event(){
        return BulletEvent.class;
    }

    @Override
    public void map(BulletEvent event, CallContext context, CallSink sink){
        if(event instanceof BulletEvent.Created created) sink.all(BulletCalls.createBullet(created));
    }
}
