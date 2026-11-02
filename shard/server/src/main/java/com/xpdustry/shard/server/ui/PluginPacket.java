package com.xpdustry.shard.server.ui;

import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.sim.world.*;

/**
 * A plugin packet a client sent: {@code serverPacketReliable(type, contents)} and its kin. {@code contents} is the
 * text's UTF-8, the bytes, or a logic value's {@code readObjectSafe} bytes; on the world's tick thread, as {@link Answer}.
 */
public record PluginPacket(HostedWorld world, World state, Session session, Kind kind, String type, byte[] contents, boolean reliable){
    public enum Kind{ TEXT, BINARY, LOGIC }
}
