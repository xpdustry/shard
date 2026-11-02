package com.xpdustry.shard.server.ui;

import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * A client's answer to a menu or a text input, on its world's tick thread after the tick that admitted it: read the
 * world, never change it (offer a command to its inbox instead).
 *
 * @param option the menu option chosen, or -1 when the menu was closed or for a text input
 * @param text the text input's text, or null (closed, or a menu)
 */
public record Answer(HostedWorld world, World state, Session session, Player player, int id, int option, String text){
}
