package com.xpdustry.shard.oracle.dump;

import mindustry.net.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;
import com.xpdustry.shard.oracle.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

class DumpTest{
    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    /** The bytecode reader agrees with itself: a generated packet's handled() reads what its write() writes. */
    @Test
    void generatedPacketsReadWhatTheyWrite() throws Exception{
        @SuppressWarnings("unchecked")
        var dump = (Map<String, Object>)PacketDump.dump();
        int count = (int)dump.get("count");
        assertEquals(173, count);
        int generated = 0;
        for(int id = 0; id < count; id++){
            Class<?> type = Net.newPacket((byte)id).getClass();
            if(!type.getName().startsWith("mindustry.gen.")) continue;
            generated++;
            Layouts.Layout write = Layouts.declared(type, "write", PacketDump.writeDesc), read = Layouts.declared(type, "handled", "()V");
            assertNotNull(write, type.getName());
            assertNotNull(read, type.getName());
            assertEquals(write.fields(), read.fields(), type.getName());
        }
        assertEquals(166, generated);
    }

    /** shard/data/vanilla-v160.7 is what the dumper writes now: regenerate it with ./gradlew :tools:oracle:dump. */
    @Test
    void committedDumpsAreCurrent(@TempDir Path out) throws Exception{
        String data = System.getProperty("com.xpdustry.shard.oracle.data");
        Assumptions.assumeTrue(data != null && Files.exists(Path.of(data, "README.md")), "no committed dumps");
        Path committed = Path.of(data);

        Dump.main(new String[]{out.toString()});

        assertEquals(files(committed), files(out), "file list; run ./gradlew :tools:oracle:dump");
        for(String name : files(out)){
            assertEquals(-1L, Files.mismatch(committed.resolve(name), out.resolve(name)), name + " is stale; run ./gradlew :tools:oracle:dump");
        }
    }

    static List<String> files(Path root) throws Exception{
        try(Stream<Path> walk = Files.walk(root)){
            return walk.filter(Files::isRegularFile).map(p -> root.relativize(p).toString()).sorted().toList();
        }
    }
}
