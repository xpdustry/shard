package com.xpdustry.shard.tools.fuzz;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The generator covers every client packet, and most of its bodies are the generated codec's, not noise. */
class GenTest{
    @Test
    void everyClientPacketHasAGenerator(){
        var gen = new Gen(new Random(1));
        var missing = new ArrayList<String>();
        for(int id : Gen.IDS) if(id != Packets.CONNECT_PACKET && gen.packet(id) == null) missing.add(Packets.TYPES.get(id).name());
        assertEquals(List.of(), missing);
        // the 38 client Calls of note 25 section 7, connect-flow ones included; ConnectPacket is Gen.connect's
        assertEquals(38, Gen.IDS.length, Arrays.toString(Gen.IDS));
    }

    @Test
    void generatedBodiesMostlyWrite(){
        var rnd = new Random(2);
        var gen = new Gen(rnd);
        for(int id : Gen.IDS){
            if(id == Packets.CONNECT_PACKET) continue;
            int written = 0;
            for(int i = 0; i < 200; i++){
                try{
                    gen.packet(id).write(new Writes(), false, gen.wire());
                    written++;
                }catch(RuntimeException refused){
                    // over a write-side limit: noise goes out instead
                }
            }
            assertTrue(written >= 100, Packets.TYPES.get(id).name() + ": only " + written + " of 200 wrote");
        }
    }
}
