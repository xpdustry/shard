package com.xpdustry.shard.compat.v160.blocks.logic;

import com.xpdustry.shard.logic.*;
import com.xpdustry.shard.testkit.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review 39: {@link LogicConfig}'s bounds keep every processor config in vanilla's bundled maps. Each map is inflated
 * and searched for the deflate streams that hold a processor's code and links, read here as vanilla's
 * {@code readCompressed} reads them, with no bound; {@link LogicConfig#decode} must read each the same, and
 * {@link LogicConfig#measure} must admit it.
 */
class BundledLogicConfigsTest{
    /** A config as vanilla reads it: its code and links, and how many bytes it inflated to. */
    record Config(String code, List<LogicConfig.Link> links, int size){
    }

    @Test
    void everyProcessorConfigInTheBundledMapsIsReadWhole() throws Exception{
        int configs = 0, largest = 0, longestName = 0, mostLinks = 0;
        for(String map : BundledMaps.all()){
            byte[] file = inflate(BundledMaps.read(map), 0, Integer.MAX_VALUE).bytes();
            for(int at = 0; at + 1 < file.length; at++){
                if(file[at] != 0x78 || file[at + 1] != (byte)0x9c) continue;
                Inflated stream = inflate(file, at, 1 << 20);
                Config vanilla = stream == null ? null : vanilla(stream.bytes());
                if(vanilla == null) continue;
                byte[] data = Arrays.copyOfRange(file, at, at + stream.read());
                configs++;
                LogicConfig.Decoded decoded = LogicConfig.decode(data);
                assertNotNull(decoded, map + " at " + at);
                assertEquals(vanilla.code(), decoded.code(), map + " at " + at);
                assertEquals(vanilla.links(), decoded.links(), map + " at " + at);
                assertEquals(vanilla.size(), LogicConfig.measure(data, LogicConfig.MAX_INFLATED), map + " at " + at);
                largest = Math.max(largest, vanilla.size());
                mostLinks = Math.max(mostLinks, vanilla.links().size());
                for(LogicConfig.Link link : vanilla.links()) longestName = Math.max(longestName, link.name().getBytes(StandardCharsets.UTF_8).length);
            }
        }
        // what LogicConfig's bounds were chosen against
        assertEquals(718, configs);
        assertEquals(15_669, largest);
        assertEquals(56, mostLinks);
        assertEquals(15, longestName);
        assertTrue(largest * 16 < LogicConfig.MAX_INFLATED && longestName * 4 < LogicConfig.MAX_NAME_BYTES);
    }

    /** A zlib stream, inflated, and how many bytes of it were read. */
    record Inflated(byte[] bytes, int read){
    }

    /** The zlib stream at {@code from}, or null if it is none or inflates past {@code cap}. */
    static Inflated inflate(byte[] data, int from, int cap){
        var inflater = new Inflater();
        try{
            inflater.setInput(data, from, data.length - from);
            var out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            while(!inflater.finished()){
                int n = inflater.inflate(buffer);
                if(n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null;
                out.write(buffer, 0, n);
                if(out.size() > cap) return null;
            }
            return new Inflated(out.toByteArray(), (int)inflater.getBytesRead());
        }catch(DataFormatException e){
            return null;
        }finally{
            inflater.end();
        }
    }

    /** {@code readCompressed} of inflated bytes with no bounds, or null unless they are exactly a version 1 config. */
    static Config vanilla(byte[] raw){
        if(raw.length < 9 || raw[0] != 1) return null;
        try(var in = new DataInputStream(new ByteArrayInputStream(raw))){
            in.read();
            int length = in.readInt();
            if(length < 0 || length > raw.length) return null;
            byte[] code = new byte[length];
            in.readFully(code);
            int count = in.readInt();
            if(count < 0 || count > Processor.maxLinks) return null;
            var links = new ArrayList<LogicConfig.Link>();
            for(int i = 0; i < count; i++) links.add(new LogicConfig.Link(in.readUTF(), in.readShort(), in.readShort()));
            if(in.available() != 0) return null;
            return new Config(new String(code, StandardCharsets.UTF_8), links, raw.length);
        }catch(IOException e){
            return null;
        }
    }
}
