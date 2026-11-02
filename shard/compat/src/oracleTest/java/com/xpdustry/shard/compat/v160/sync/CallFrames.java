package com.xpdustry.shard.compat.v160.sync;

import com.xpdustry.shard.common.io.Writes;
import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.Packet;
import com.xpdustry.shard.wire.v160_7.Packets;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A byte-level Call gate for fixtures that run vanilla and Shard in step: the Calls the built-in {@link CallMappings}
 * make for a frame's events against those vanilla sent in the same frame, one line per Call: recipients, reliability,
 * packet id and body ({@code Packet.write} as a server writes it). Vanilla's sends are filtered to the packet ids the
 * fixture's events can produce, so its other traffic doesn't count.
 */
public final class CallFrames{
    private static final CallMappings MAPPINGS = CallMappings.builtIn();

    private CallFrames(){
    }

    /** Vanilla's sends of the packets {@code ids}, in order. */
    public static List<String> vanilla(List<Vanilla.Capture.Sent> sent, Set<Integer> ids){
        var out = new ArrayList<String>();
        for(Vanilla.Capture.Sent call : sent){
            if(call.frame() == null || !ids.contains(call.frame().id())) continue;
            out.add(line(call.target(), call.reliable(), call.frame().id(), call.frame().body()));
        }
        return out;
    }

    /** What the built-in mappings send for {@code events}, in order. */
    public static List<String> shard(World world, List<? extends WorldEvent> events){
        var context = WorldWireContext.capture(world);
        var out = new ArrayList<String>();
        CallSink sink = new CallSink(){
            void add(String target, Packet packet){
                var body = new Writes();
                packet.write(body, true, context);
                out.add(line(target, Packets.TYPES.get(packet.packetId()).reliable(), packet.packetId(), body.toByteArray()));
            }

            @Override
            public void all(Packet packet){
                add("all", packet);
            }

            @Override
            public void except(long session, Packet packet){
                add("except", packet);
            }

            @Override
            public void team(int team, Packet packet){
                add("team " + team, packet);
            }

            @Override
            public void at(float x, float y, Packet packet){
                // without fog, everyone
                add("all", packet);
            }

            @Override
            public void only(long session, Packet packet){
                add("connection", packet);
            }
        };
        CallContext calls = new CallContext(){
            @Override
            public World world(){
                return world;
            }

            @Override
            public int player(long session){
                return (int)session;
            }

            @Override
            public void healthChanged(int pos){
            }
        };
        for(WorldEvent event : events) MAPPINGS.map(event, calls, sink);
        return out;
    }

    /** The first line that differs, whole, then the counts. */
    public static void same(List<String> vanilla, List<String> shard, String when){
        for(int i = 0; i < Math.min(vanilla.size(), shard.size()); i++){
            if(!vanilla.get(i).equals(shard.get(i))) assertEquals(vanilla.get(i), shard.get(i), when + ", Call " + i);
        }
        assertEquals(vanilla.size(), shard.size(), when + ": " + vanilla + " vs " + shard);
    }

    private static String line(String target, boolean reliable, int id, byte[] body){
        return target + (reliable ? " tcp " : " udp ") + Packets.TYPES.get(id).name() + " " + Hex.of(body);
    }
}
