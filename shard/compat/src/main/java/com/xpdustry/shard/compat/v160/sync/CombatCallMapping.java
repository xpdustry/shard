package com.xpdustry.shard.compat.v160.sync;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.wire.Codecs;
import com.xpdustry.shard.wire.v160_7.Packets;

/**
 * Combat's Calls ({@link CombatEvent}): {@code UnitSpawned} is {@code unitSpawn(new UnitSyncContainer(unit))}
 * (Units.java:39), to everyone, so a unit a bullet made appears before the next snapshot. It carries the unit as the
 * entity snapshots write it, as it is after the tick; a unit already gone by then sends nothing, as its removal
 * reaches clients through the snapshots.
 */
@Register
public final class CombatCallMapping implements CallMapping<CombatEvent>{
    @Override
    public Class<CombatEvent> event(){
        return CombatEvent.class;
    }

    @Override
    public void map(CombatEvent event, CallContext context, CallSink sink){
        switch(event){
            case CombatEvent.UnitSpawned spawned -> {
                EntityGroups groups = EntityGroups.of(context.world());
                if(groups == null || !(groups.byId(spawned.unit()) instanceof Unit unit) || !unit.isAdded()) return;
                var container = new Codecs.SyncedUnit(unit.id(), EntityProjector.classId(unit), context.projector().project(unit));
                sink.all(new Packets.UnitSpawn(container));
            }
        }
    }
}
