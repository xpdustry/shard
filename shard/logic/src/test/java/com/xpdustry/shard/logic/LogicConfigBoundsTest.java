package com.xpdustry.shard.logic;

import org.junit.jupiter.api.*;

import java.lang.management.*;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review 39: a processor config is bounded by what it inflates to, not by its compressed size. The largest config a
 * stock client writes is read as vanilla reads it; a config whose names or total go past {@link LogicConfig}'s bounds
 * is refused whole, and {@link LogicConfig#measure} refuses it after reading little.
 */
class LogicConfigBoundsTest{
    /** The bytes {@code decode} inflates for this code and these links. */
    static int inflated(String code, List<LogicConfig.Link> links){
        int bytes = 1 + 4 + code.getBytes(StandardCharsets.UTF_8).length + 4;
        for(LogicConfig.Link link : links) bytes += 2 + link.name().getBytes(StandardCharsets.UTF_8).length + 4;
        return bytes;
    }

    static List<LogicConfig.Link> links(int count, String name){
        var out = new ArrayList<LogicConfig.Link>();
        for(int i = 0; i < count; i++) out.add(new LogicConfig.Link(name.isEmpty() ? "reconstructor" + (i + 1) : name, i % 100, i / 100));
        return out;
    }

    @Test
    void theLargestConfigAStockClientWritesIsReadWhole(){
        // the most code, and every link named after vanilla's longest link name
        String code = "op add a a 1\n".repeat(LogicConfig.maxByteLen / 13) + "#".repeat(LogicConfig.maxByteLen % 13);
        List<LogicConfig.Link> links = links(Processor.maxLinks, "");
        byte[] data = LogicConfig.compress(code, links);
        assertEquals(239_302, inflated(code, links), "LogicConfig.MAX_INFLATED's reference size");
        LogicConfig.Decoded decoded = LogicConfig.decode(data);
        assertNotNull(decoded);
        assertEquals(code, decoded.code());
        assertEquals(links, decoded.links());
        assertEquals(inflated(code, links), LogicConfig.measure(data, LogicConfig.MAX_INFLATED));
    }

    @Test
    void longNamesAreRefusedWholeAfterReadingLittle(){
        // hundreds of long names: a few kilobytes on the wire, 18 MB inflated
        byte[] data = LogicConfig.compress("set a 1", links(300, "a".repeat(60_000)));
        assertTrue(data.length < 40_000, "fits a TileConfig: " + data.length);
        var threads = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();
        assertNull(LogicConfig.decode(data));
        assertEquals(-1, LogicConfig.measure(data, LogicConfig.MAX_INFLATED));
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        assertTrue(allocated < 256 * 1024, "allocated " + allocated);
    }

    @Test
    void namesAndTheTotalHaveTheirBounds(){
        String name = "n".repeat(LogicConfig.MAX_NAME_BYTES);
        byte[] atBound = LogicConfig.compress("", links(3, name));
        assertEquals(links(3, name), LogicConfig.decode(atBound).links());
        assertNull(LogicConfig.decode(LogicConfig.compress("", links(3, name + "n"))), "one byte over");
        // a non-ASCII name counts its UTF-8 bytes: 22 three-byte characters are 66
        assertNull(LogicConfig.decode(LogicConfig.compress("", links(1, "€".repeat(22)))));

        // 3,744 names at the bound fit in MAX_INFLATED; one more link goes past it
        int fit = (LogicConfig.MAX_INFLATED - 9) / (2 + LogicConfig.MAX_NAME_BYTES + 4);
        assertNotNull(LogicConfig.decode(LogicConfig.compress("", links(fit, name))));
        assertNull(LogicConfig.decode(LogicConfig.compress("", links(fit + 1, name))));
    }

    @Test
    void measureRefusesPastItsLimitAndAgreesWithDecode(){
        List<LogicConfig.Link> links = links(40, "");
        byte[] data = LogicConfig.compress("print 1", links);
        int size = inflated("print 1", links);
        assertEquals(size, LogicConfig.measure(data, size));
        assertEquals(-1, LogicConfig.measure(data, size - 1));
        assertEquals(-1, LogicConfig.measure(data, 0));
        assertEquals(0, LogicConfig.measure(null, 0));
        // malformed data is no refusal: it costs what was read, as vanilla ignores it
        assertTrue(LogicConfig.measure(new byte[]{1, 2, 3}, 100) >= 0);
        assertNull(LogicConfig.decode(new byte[]{1, 2, 3}));
    }

    @Test
    void linksCutShortStillKeepVanillasPartialRead() throws Exception{
        // vanilla has replaced the links by the time a later one fails, and doesn't update the code
        var raw = new java.io.ByteArrayOutputStream();
        var out = new java.io.DataOutputStream(new DeflaterOutputStream(raw));
        out.write(1);
        out.writeInt(3);
        out.write("end".getBytes(StandardCharsets.UTF_8));
        out.writeInt(5);
        out.writeUTF("cell1");
        out.writeShort(1);
        out.writeShort(2);
        out.writeUTF("cell2");
        out.close();
        LogicConfig.Decoded decoded = LogicConfig.decode(raw.toByteArray());
        assertNotNull(decoded);
        assertNull(decoded.code());
        assertEquals(List.of(new LogicConfig.Link("cell1", 1, 2)), decoded.links());
        assertTrue(LogicConfig.measure(raw.toByteArray(), LogicConfig.MAX_INFLATED) > 0);
    }
}
