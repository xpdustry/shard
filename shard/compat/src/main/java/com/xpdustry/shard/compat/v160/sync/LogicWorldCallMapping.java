package com.xpdustry.shard.compat.v160.sync;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.logic.*;
import com.xpdustry.shard.wire.v160_7.Packets;

/**
 * The Calls processors' unit and world statements make ({@link LogicWorldEvent}), to everyone, in the order they were
 * published: {@code Explosion} is {@code logicExplosion(team, x, y, radius, damage, air, ground, pierce, effect)}, a
 * missing team as 0, as {@code TypeIO.writeTeam} writes null. Their item transfers are {@code ItemCallMapping}'s.
 */
@Register
public final class LogicWorldCallMapping implements CallMapping<LogicWorldEvent>{
    @Override
    public Class<LogicWorldEvent> event(){
        return LogicWorldEvent.class;
    }

    @Override
    public void map(LogicWorldEvent event, CallContext context, CallSink sink){
        switch(event){
            case LogicWorldEvent.Explosion e -> sink.all(new Packets.LogicExplosion(Math.max(e.team(), 0), e.x(), e.y(), e.radius(), e.damage(),
                e.air(), e.ground(), e.pierce(), e.effect()));
        }
    }
}
