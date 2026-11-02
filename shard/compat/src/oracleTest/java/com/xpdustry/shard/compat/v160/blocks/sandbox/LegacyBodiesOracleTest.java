package com.xpdustry.shard.compat.v160.blocks.sandbox;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.oracle.*;
import org.junit.jupiter.api.*;

import java.io.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The legacy blocks' bodies, which no bundled map holds: for each block and building revision, Shard's codec reads
 * and skips exactly the bytes vanilla's {@code read} takes, so the save sections after one stay aligned.
 */
class LegacyBodiesOracleTest{
    static final BuildingCodecs CODECS = BuildingCodecs.builtIn();
    static final String[][] BLOCKS = {
        {"legacy-mech-pad", "legacy-mech-pad"},
        {"legacy-unit-factory", "legacy-unit-factory"},
        {"legacy-unit-factory-air", "legacy-unit-factory"},
        {"legacy-unit-factory-ground", "legacy-unit-factory"},
        {"command-center", "legacy-command-center"},
    };

    @Test
    void codecsTakeTheBytesVanillaReads() throws IOException{
        Sim.empty(8, 8);
        byte[] bytes = new byte[32];
        for(int i = 0; i < bytes.length; i++) bytes[i] = (byte)(i * 37 + 5);
        for(String[] block : BLOCKS){
            var codec = CODECS.get(KindKey.of(block[1]));
            assertNotNull(codec, block[0]);
            for(int revision = 0; revision <= 1; revision++){
                var stream = new ByteArrayInputStream(bytes);
                content.block(block[0]).newBuilding().read(new arc.util.io.Reads(new DataInputStream(stream)), (byte)revision);
                int vanilla = bytes.length - stream.available();

                var in = new Reads(bytes);
                codec.read(null, in, revision, null);
                assertEquals(vanilla, in.position(), block[0] + " read at revision " + revision);
                var skipped = new Reads(bytes);
                codec.skip(new Tape(skipped), revision);
                assertEquals(vanilla, skipped.position(), block[0] + " skip at revision " + revision);
            }
        }
    }
}
