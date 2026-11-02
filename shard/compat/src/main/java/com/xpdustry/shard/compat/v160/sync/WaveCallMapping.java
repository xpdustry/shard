package com.xpdustry.shard.compat.v160.sync;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.wire.v160_7.*;

/**
 * A wave's Calls ({@link WaveEvent}): each spawned unit's {@code spawnEffect(x, y, rotation, type)}, unreliable, to
 * whoever sees the point (everyone without fog). A started wave sends no Call: clients see the new wave in the next
 * state snapshot ({@code NetClient.stateSnapshot} fires their {@code WaveEvent}).
 */
@Register
public final class WaveCallMapping implements CallMapping<WaveEvent>{
    @Override
    public Class<WaveEvent> event(){
        return WaveEvent.class;
    }

    @Override
    public void map(WaveEvent event, CallContext context, CallSink sink){
        switch(event){
            case WaveEvent.UnitSpawned spawned -> sink.at(spawned.x(), spawned.y(),
                new Packets.SpawnEffect(spawned.x(), spawned.y(), spawned.rotation(), (short)spawned.type()));
            case WaveEvent.Started ignored -> {
            }
        }
    }
}
