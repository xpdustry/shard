package com.xpdustry.shard.wire;

import arc.struct.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import mindustry.net.Net;
import mindustry.net.Packet;
import mindustry.net.Packets.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.util.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L4: differential fuzzing ({@link Fuzz}) of every decoder a client reaches, the 17 client-to-server packets, the 21
 * both-way packets in their client form and {@code ConnectPacket}, against vanilla's server. Each case mutates a body
 * vanilla's client wrote. {@link ServerPacketFuzzTest} does the other direction.
 */
@Tag("slow")
class ClientDecoderFuzzTest{
    static Randoms random;
    static final VanillaContext context = new VanillaContext();

    @BeforeAll
    static void world(){
        random = Randoms.world(4);
    }

    static Stream<Arguments> decoders(){
        return Packets.TYPES.stream().filter(t -> !t.framework() && t.direction().toServerAllowed()).map(t -> Arguments.of(t.id(), t.name()));
    }

    @Test
    void thereAre38(){
        assertEquals(38, decoders().count());
    }

    @ParameterizedTest(name = "{1} (#{0})")
    @MethodSource("decoders")
    void callPacket(int id, String name) throws Exception{
        List<byte[]> seeds = new ArrayList<>();
        for(int i = 0; i < 12; i++){
            Packet packet = Net.newPacket((byte)id);
            random.fill(packet);
            seeds.add(Golden.packet(packet, Oracle.Direction.toServer).body());
        }
        Fuzz.fuzz(name, id, seeds, body -> ServerDecode.vanilla(id, body), body -> ServerDecode.shard(id, body, context), Fuzz.budget(decoders().count() + 1));
    }

    @Test
    void connectPacket(){
        List<byte[]> seeds = new ArrayList<>();
        for(int i = 0; i < 12; i++){
            var packet = new ConnectPacket();
            packet.versionType = random.nullableString();
            packet.name = random.nullableString();
            packet.locale = random.nullableString();
            packet.usid = random.nullableString();
            byte[] id = new byte[8];
            random.rnd.nextBytes(id);
            packet.uuid = Base64.getEncoder().encodeToString(id);
            packet.mobile = random.rnd.nextBoolean();
            packet.color = random.rnd.nextInt();
            packet.mods = new Seq<>();
            int mods = random.rnd.nextInt(4);
            for(int m = 0; m < mods; m++) packet.mods.add(random.nullableString());
            seeds.add(Golden.bytes(packet::write));
        }
        Fuzz.fuzz("ConnectPacket", Bootstrap.CONNECT_ID, seeds, ServerDecode::vanillaConnect, ServerDecode::shardConnect, Fuzz.budget(decoders().count() + 1));
    }
}
