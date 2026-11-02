package com.xpdustry.shard.wire;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.oracle.Oracle.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import mindustry.net.Net;
import mindustry.net.Packet;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.util.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L1 for packets (proto 25's {@code PacketRoundTrip}): every {@code Call} packet, in each form it is sent in. Vanilla
 * writes a packet with random fields (A); Shard reads A, consuming all of it, and writes it back (B); A == B. A
 * both-way packet is checked in the client's form (no sender) and the server's (sender first). Building and unit
 * references point at real entities, and the JSON, UI and plan codecs carry real values.
 */
class PacketRoundTripTest{
    static final int SEEDS = 25;
    static Randoms random;
    static final VanillaContext context = new VanillaContext();

    @BeforeAll
    static void world(){
        random = Randoms.world(25);
    }

    /** Each Call packet and form: "server" for what the server sends, "client" for what a client sends. */
    static Stream<Arguments> forms(){
        List<Arguments> out = new ArrayList<>();
        for(PacketType type : Packets.TYPES){
            if(type.framework()) continue;
            if(type.direction().toClientAllowed()) out.add(Arguments.of(type.id(), type.name(), true));
            if(type.direction().toServerAllowed()) out.add(Arguments.of(type.id(), type.name(), false));
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{1} (#{0}) from server: {2}")
    @MethodSource("forms")
    void roundTrip(int id, String name, boolean fromServer) throws Exception{
        Oracle.Direction direction = fromServer ? Oracle.Direction.toClient : Oracle.Direction.toServer;
        for(int seed = 0; seed < SEEDS; seed++){
            Packet packet = Net.newPacket((byte)id);
            assertEquals(name, vanillaName(packet));
            random.fill(packet);
            byte[] a = Golden.packet(packet, direction).body();

            Reads read = new Reads(a);
            com.xpdustry.shard.wire.Packet decoded = Packets.read(id, read, fromServer, context.source(a));
            assertEquals(a.length, read.position(), () -> name + ": bytes left after Shard's read of " + Hex.of(a));
            Writes write = new Writes();
            decoded.write(write, fromServer, context);
            int s = seed;
            assertArrayEquals(a, write.toByteArray(), () -> name + " seed " + s + ": " + decoded + "\nA=" + Hex.of(a) + "\nB=" + Hex.of(write.toByteArray()));
            assertEquals(id, decoded.packetId());
        }
    }

    @Test
    void everyCallPacketIsCovered(){
        //17 client-to-server, 128 server-to-client and 21 both-way packets: 166 Call packets, 187 forms
        assertEquals(166, Packets.TYPES.stream().filter(t -> !t.framework()).count());
        assertEquals(187, forms().count());
    }

    /** Vanilla's class name without {@code CallPacket}, which an overload's number follows ({@code KickCallPacket2}). */
    static String vanillaName(Packet packet){
        return packet.getClass().getSimpleName().replace("CallPacket", "");
    }
}
