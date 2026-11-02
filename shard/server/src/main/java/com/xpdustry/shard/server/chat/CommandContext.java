package com.xpdustry.shard.server.chat;

import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.server.sync.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * What a chat command runs with: vanilla's {@code Player} parameter, as the session that sent the line, its world and
 * its player there. It lives for one run, on the world's tick thread after the tick: read the world, never change it
 * (offer a command to its inbox instead), and send through the methods here.
 */
public final class CommandContext{
    private final HostedWorld world;
    private final World state;
    private final Session session;
    private final Player player;

    CommandContext(HostedWorld world, World state, Session session, Player player){
        this.world = world;
        this.state = state;
        this.session = session;
        this.player = player;
    }

    public HostedWorld world(){
        return world;
    }

    /** The world after the tick that admitted the line. */
    public World state(){
        return state;
    }

    public Session session(){
        return session;
    }

    /** The sender's player in this world. */
    public Player player(){
        return player;
    }

    /** {@code player.sendMessage(text)}: a server line in the sender's chat. */
    public void reply(String text){
        world.outbox().send(Audience.only(session.id()), Calls.message(text));
    }

    /** {@code Call.sendMessage(text)}: a server line to everyone in the world. */
    public void broadcast(String text){
        world.outbox().send(Audience.ALL, Calls.message(text));
    }
}
