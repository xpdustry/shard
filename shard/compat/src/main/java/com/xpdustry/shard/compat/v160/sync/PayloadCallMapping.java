package com.xpdustry.shard.compat.v160.sync;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.*;

/**
 * The payload Calls ({@link PayloadEvent}), to everyone (InputHandler.java:590-659): {@code pickedUnitPayload(unit,
 * target)}, {@code pickedBuildPayload(unit, build, onGround)} with the building as {@code TypeIO.writeBuilding} wrote
 * it, its position as it stood, {@code payloadDropped(unit, x, y)}, and {@code unitEnteredPayload(unit, build)} (W4.2), and {@code unitBuildingControlSelect(unit, build)} (W5.7), whose unit
 * may be a turret's block unit (kind 1). Other units are standard units (kind 2) by id.
 */
@Register
public final class PayloadCallMapping implements CallMapping<PayloadEvent>{
    @Override
    public Class<PayloadEvent> event(){
        return PayloadEvent.class;
    }

    @Override
    public void map(PayloadEvent event, CallContext context, CallSink sink){
        Codecs.UnitRef unit = Codecs.UnitRef.unit(event.unit());
        sink.all(switch(event){
            case PayloadEvent.PickedUnit picked -> new Packets.PickedUnitPayload(unit, Codecs.UnitRef.unit(picked.target()));
            case PayloadEvent.PickedBuild picked -> new Packets.PickedBuildPayload(unit, picked.pos(), picked.onGround());
            case PayloadEvent.Dropped dropped -> new Packets.PayloadDropped(unit, dropped.x(), dropped.y());
            case PayloadEvent.EnteredPayload entered -> new Packets.UnitEnteredPayload(unit, entered.pos());
            case PayloadEvent.ControlSelect select -> new Packets.UnitBuildingControlSelect(
                select.blockUnit() == -1 ? unit : Codecs.UnitRef.block(select.blockUnit()), select.pos());
        });
    }
}
