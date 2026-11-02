package com.xpdustry.shard.server.admin;

import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * The console's {@code admin add|remove} on an online player: their player's {@code admin} flag, which the player
 * list shows and {@code adminRequest} checks, set through the world's inbox ({@code playert.admin = add},
 * {@code ServerControl.java:1112}).
 */
public record SetAdmin(long session, boolean admin) implements Command.Admin{
    @Override
    public Verdict apply(World world, Cause cause){
        Roster roster = Roster.of(world);
        Player player = roster == null ? null : roster.player(session);
        if(player == null) return Verdict.rejected("no such player");
        player.admin = admin;
        return Verdict.ADMITTED;
    }
}
