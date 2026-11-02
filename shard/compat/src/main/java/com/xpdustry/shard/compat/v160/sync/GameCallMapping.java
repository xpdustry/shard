package com.xpdustry.shard.compat.v160.sync;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.game.*;
import com.xpdustry.shard.wire.v160_7.Packets;

/**
 * The game's Calls ({@link GameEvent}): {@code GameOver} is {@code updateGameOver(winner)}, to everyone, as a vanilla
 * server sends it (ServerControl.java:123). The client's {@code Call.gameOver} comes from {@code Control}, which a
 * dedicated server doesn't run. The {@code infoMessage} naming the next map, which vanilla sends just before, and the
 * kick when there is none, are the map rotation's.
 */
@Register
public final class GameCallMapping implements CallMapping<GameEvent>{
    @Override
    public Class<GameEvent> event(){
        return GameEvent.class;
    }

    @Override
    public void map(GameEvent event, CallContext context, CallSink sink){
        switch(event){
            case GameEvent.GameOver e -> sink.all(new Packets.UpdateGameOver(e.winner()));
        }
    }
}
