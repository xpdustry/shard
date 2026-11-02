package com.xpdustry.shard.server.session;

import com.xpdustry.shard.sim.unit.*;

import java.util.*;

/**
 * A session's player inside one world: the server's handle on the world's {@link Player} entity, which has its own
 * entity id per world. The player is world state: it lives in the world, copies with a fork and hashes, and it never
 * points at the {@link Session}; this handle pairs the two for the tick thread of that world, which alone may read
 * the player.
 *
 * @param session the session's id
 */
public record PlayerEntity(long session, Player player){
    public PlayerEntity{
        Objects.requireNonNull(player, "player");
    }

    /** The entity id in this world ({@code EntityIds}), which the client knows the player by. */
    public int id(){
        return player.id();
    }

    /**
     * What this player's session may see now: its team, and the view rectangle its client last reported (vanilla keeps
     * it on the connection, {@code NetConnection.viewX}), centre and size in world units.
     */
    public Viewer viewer(float viewX, float viewY, float viewWidth, float viewHeight){
        return new Viewer(session, player.team, viewX, viewY, viewWidth, viewHeight, player.admin);
    }
}
