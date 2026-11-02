package com.xpdustry.shard.compat.v160.sync;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.*;

/**
 * A unit's death or removal: each {@link UnitEvent} is the {@code Units} Call of the same name, to everyone. The
 * four that take the unit itself write it as {@code TypeIO.writeUnit}, a standard unit (kind 2) and its id.
 */
@Register
public final class UnitCallMapping implements CallMapping<UnitEvent>{
    @Override
    public Class<UnitEvent> event(){
        return UnitEvent.class;
    }

    @Override
    public void map(UnitEvent event, CallContext context, CallSink sink){
        Codecs.UnitRef unit = Codecs.UnitRef.unit(event.unit());
        sink.all(switch(event){
            case UnitEvent.Death death -> new Packets.UnitDeath(death.unit());
            case UnitEvent.Destroy destroy -> new Packets.UnitDestroy(destroy.unit());
            case UnitEvent.Despawn ignored -> new Packets.UnitDespawn(unit);
            case UnitEvent.EnvDeath ignored -> new Packets.UnitEnvDeath(unit);
            case UnitEvent.CapDeath ignored -> new Packets.UnitCapDeath(unit);
            case UnitEvent.SafeDeath ignored -> new Packets.UnitSafeDeath(unit);
        });
    }
}
