package com.xpdustry.shard.wire;

import com.xpdustry.shard.common.io.*;

/** A generated {@code Call} packet: one record per packet in {@code com.xpdustry.shard.wire.v160_7.Packets}. */
public interface Packet{
    /** The id, which is the packet's position in vanilla's registration order. */
    int packetId();

    /**
     * Writes the body, as vanilla's {@code CallPacket.write}.
     * @param fromServer the server's form: a packet that goes both ways then carries its sender first
     */
    void write(Writes write, boolean fromServer, WireContext context);
}
