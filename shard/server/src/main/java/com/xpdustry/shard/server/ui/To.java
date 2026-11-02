package com.xpdustry.shard.server.ui;

import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.session.*;

/**
 * Who a UI Call goes to: vanilla's {@code Call.x(con, ...)} (one player) or {@code Call.x(...)} (everyone), in a host of
 * many worlds. A world's players get it through the world's {@code Outbox}, after its next tick's Calls, so a
 * loading player gets it once its load is confirmed.
 */
public sealed interface To{
    /** One player, in whichever world it is; straight to its connection if it is in none. */
    static To player(Session session){
        return new Player(session);
    }

    /** Everyone in {@code world}. */
    static To world(HostedWorld world){
        return new World(world);
    }

    /** The players of {@code team} in {@code world}. */
    static To team(HostedWorld world, int team){
        return new Team(world, team);
    }

    /** Everyone in every world: vanilla's {@code Call.x(...)} on a server of one world. */
    static To everyone(){
        return new Everyone();
    }

    record Player(Session session) implements To{
    }

    record World(HostedWorld world) implements To{
    }

    record Team(HostedWorld world, int team) implements To{
    }

    record Everyone() implements To{
    }
}
