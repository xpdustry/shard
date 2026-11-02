package com.xpdustry.shard.wire;

import com.xpdustry.shard.oracle.*;
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
 * L4 for the other direction ({@link Fuzz}): every decoder a server's packets reach, the 128 server-to-client packets
 * and the 21 both-way packets in their server form, against vanilla's client. Each case mutates a body vanilla's server
 * wrote. Shard reads these in bots and test rigs, and a client's view of the server must not depend on which reader
 * runs it.
 */
@Tag("slow")
class ServerPacketFuzzTest{
    static Randoms random;
    static final VanillaContext context = new VanillaContext();

    @BeforeAll
    static void world(){
        random = Randoms.world(5);
    }

    static Stream<Arguments> decoders(){
        return Packets.TYPES.stream().filter(t -> !t.framework() && t.direction().toClientAllowed()).map(t -> Arguments.of(t.id(), t.name()));
    }

    @Test
    void thereAre149(){
        assertEquals(149, decoders().count());
    }

    @ParameterizedTest(name = "{1} (#{0})")
    @MethodSource("decoders")
    void callPacket(int id, String name) throws Exception{
        List<byte[]> seeds = new ArrayList<>();
        for(int i = 0; i < 12; i++){
            Packet packet = Net.newPacket((byte)id);
            random.fill(packet);
            seeds.add(Golden.packet(packet, Oracle.Direction.toClient).body());
        }
        Fuzz.fuzz(name, id, seeds, body -> ClientDecode.vanilla(id, body), body -> ClientDecode.shard(id, body, context), Fuzz.budget(decoders().count()));
    }
}
