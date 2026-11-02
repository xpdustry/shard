package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import org.junit.jupiter.api.*;

import java.io.*;
import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Forwards} against the vanilla jar's generated {@code mindustry.gen.Call}, read as bytes and never loaded:
 * each {@code x__forward} method's {@code Net.sendExcept} or {@code Net.send} is the table's side for packet
 * {@code X}.
 */
class ForwardsTest{
    /** Packet id to whether vanilla's forward skips the sender. */
    static Map<Integer, Boolean> vanilla() throws IOException{
        byte[] bytes;
        try(InputStream in = ForwardsTest.class.getClassLoader().getResourceAsStream("mindustry/gen/Call.class")){
            assertNotNull(in, "the vanilla jar is on the test class path");
            bytes = in.readAllBytes();
        }
        var out = new TreeMap<Integer, Boolean>();
        for(MethodModel method : ClassFile.of().parse(bytes).methods()){
            String name = method.methodName().stringValue();
            if(!name.endsWith("__forward")) continue;
            String packet = Character.toUpperCase(name.charAt(0)) + name.substring(1, name.length() - "__forward".length());
            int id = Packets.TYPES.stream().filter(type -> type.name().equals(packet)).findFirst().orElseThrow(() -> new AssertionError(packet)).id();
            Boolean except = null;
            for(CodeElement element : method.code().orElseThrow()){
                if(element instanceof InvokeInstruction invoke && invoke.owner().asInternalName().equals("mindustry/net/Net")){
                    String target = invoke.name().stringValue();
                    if(target.equals("sendExcept")) except = true;
                    else if(target.equals("send") && except == null) except = false;
                }
            }
            assertNotNull(except, name + " sends nothing");
            out.put(id, except);
        }
        return out;
    }

    @Test
    void theTableIsVanillas() throws IOException{
        Map<Integer, Boolean> vanilla = vanilla();
        assertEquals(13, vanilla.size(), vanilla.toString());
        var except = new TreeSet<Integer>();
        var everyone = new TreeSet<Integer>();
        vanilla.forEach((id, skips) -> (skips ? except : everyone).add(id));
        assertEquals(except, new TreeSet<>(Forwards.EXCEPT_SENDER), "called = Loc.both");
        assertEquals(everyone, new TreeSet<>(Forwards.EVERYONE), "called = Loc.server");
    }

    @Test
    void aCallTheClientRanSkipsItsSender(){
        var config = new Packets.TileConfig(-1, 42, 3);
        assertEquals(new IntentCall.AllExcept(7, config), Forwards.of(7, config));
    }

    @Test
    void aCallOnlyTheServerRanReachesItsSenderToo(){
        var rotate = new Packets.RotateBlock(-1, 42, true);
        assertEquals(new IntentCall.All(rotate), Forwards.of(7, rotate));
    }

    @Test
    void anUnforwardedCallIsABug(){
        assertThrows(IllegalArgumentException.class, () -> Forwards.of(7, new Packets.SetPosition(0, 0)));
    }

    @Test
    void everyForwardIsAPacketToClients(){
        for(int id : vanillaIds()){
            PacketType type = Packets.TYPES.get(id);
            assertEquals(PacketType.Direction.both, type.direction(), type.name() + " goes both ways");
        }
    }

    static Set<Integer> vanillaIds(){
        var out = new TreeSet<Integer>(Forwards.EXCEPT_SENDER);
        out.addAll(Forwards.EVERYONE);
        return out;
    }
}
