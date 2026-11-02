package com.xpdustry.shard.compat.v160.sync;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.wire.v160_7.Packets;

/**
 * A dead team's clean-up Calls ({@link TeamEvent}, {@code TeamData.destroyToDerelict}), to everyone:
 * <ul>
 *   <li>{@code SetTeams}: {@code setTeams(positions, team)} (Tile.java:861), positions as {@code Point2.pack};</li>
 *   <li>{@code DestroyPayload}: {@code destroyPayload(build)} (Teams.java:506), the building by its position.</li>
 * </ul>
 */
@Register
public final class TeamCallMapping implements CallMapping<TeamEvent>{
    @Override
    public Class<TeamEvent> event(){
        return TeamEvent.class;
    }

    @Override
    public void map(TeamEvent event, CallContext context, CallSink sink){
        switch(event){
            case TeamEvent.SetTeams e -> sink.all(new Packets.SetTeams(e.positions(), e.team()));
            case TeamEvent.DestroyPayload e -> sink.all(new Packets.DestroyPayload(e.pos()));
        }
    }
}
