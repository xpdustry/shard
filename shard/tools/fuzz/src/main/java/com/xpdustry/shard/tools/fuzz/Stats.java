package com.xpdustry.shard.tools.fuzz;

import com.xpdustry.shard.wire.v160_7.Packets;

import java.util.*;
import java.util.concurrent.atomic.*;

/** What the fuzzers did, for the report: messages by packet id and frame kind, connections and how they ended. */
final class Stats{
    final LongAdder sent = new LongAdder(), connections = new LongAdder(), joins = new LongAdder(), closes = new LongAdder(),
        closedByServer = new LongAdder(), slowJoins = new LongAdder();
    private final AtomicLongArray byId = new AtomicLongArray(257);
    private final AtomicLongArray byKind = new AtomicLongArray(Mutate.FrameKind.values().length);

    void sent(int id, Mutate.FrameKind kind){
        byId.incrementAndGet(id >= 0 && id < 256 ? id : 256);
        byKind.incrementAndGet(kind.ordinal());
    }

    /** Game packets sent of {@code id}. */
    long of(int id){
        return byId.get(id);
    }

    String summary(){
        var out = new StringBuilder();
        out.append(String.format("messages %,d; connections %,d, joins %,d, closed by the server %,d, connect failures %,d, slow joins %,d%n", sent.sum(),
            connections.sum(), joins.sum(), closedByServer.sum(), closes.sum(), slowJoins.sum()));
        out.append("frames:");
        for(Mutate.FrameKind kind : Mutate.FrameKind.values()) out.append(String.format(" %s=%,d", kind, byKind.get(kind.ordinal())));
        out.append("\npackets by id:");
        for(int id = 0; id < 257; id++){
            long n = byId.get(id);
            if(n == 0) continue;
            String name = id < Packets.TYPES.size() ? Packets.TYPES.get(id).name() : id == 256 ? "other" : "#" + id;
            out.append(String.format(" %s=%,d", name, n));
        }
        return out.append('\n').toString();
    }
}
