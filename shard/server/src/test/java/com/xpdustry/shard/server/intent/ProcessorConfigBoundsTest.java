package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.logic.*;
import com.xpdustry.shard.sim.blocks.logic.*;
import com.xpdustry.shard.sim.logic.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import org.junit.jupiter.api.*;

import java.lang.management.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review 39: a processor config is admitted only if it expands within {@link LogicConfig}'s bounds and the world
 * tick's {@link LogicPart#CONFIG_BYTES_PER_TICK}, checked before any handler runs. A refused config rolls its client
 * back, is forwarded to no one and changes nothing, and refusing many in one tick costs little, with the interaction
 * rate limit off.
 */
class ProcessorConfigBoundsTest{
    static final long ALPHA = 7;
    /** {@code antiSpam} off: nothing but the bounds limits configs. */
    static final Policy NO_RATE_LIMIT = new Policy(true, false, 6_000, 25, 60);
    static final String REFUSED = "a processor config past Shard's bounds or this tick's budget";

    IntentWorld w;
    Player player;
    LogicBlock processor;
    byte[] before;

    @BeforeEach
    void world(){
        w = new IntentWorld();
        player = w.joinWith(ALPHA, "alpha", "alpha", 100f, 100f);
        processor = (LogicBlock)w.place("micro-processor", 14, 12, player.team, 0);
        w.place("switch", 16, 12, player.team, 0);
        processor.configureAny(LogicConfig.compress("set a 1", List.of(new LogicConfig.Link("switch1", 2, 0))));
        w.world.tick();
        w.drain();
        before = (byte[])processor.config();
    }

    static byte[] object(Object value){
        var out = new Writes(16);
        WireObjects.writeObjectSafe(out, value);
        return out.toByteArray();
    }

    static List<LogicConfig.Link> names(int count, String name){
        var out = new ArrayList<LogicConfig.Link>();
        for(int i = 0; i < count; i++) out.add(new LogicConfig.Link(name, 1, 0));
        return out;
    }

    /** {@code count} TileConfigs of {@code data} to the processor, arrived together and admitted in one tick: their verdicts. */
    List<Verdict> oneTick(byte[] data, int count){
        for(int i = 0; i < count; i++) w.offer(ALPHA, new BlockIntents.TileConfig(NO_RATE_LIMIT, processor.pos(), object(data)), 1_000);
        int logged = w.world.log().size();
        w.world.tick();
        assertEquals(logged + count, w.world.log().size());
        var out = new ArrayList<Verdict>();
        for(int i = logged; i < w.world.log().size(); i++) out.add(w.world.log().get(i).verdict());
        return out;
    }

    @Test
    void manyLongNamedConfigsInOneTickAreRefusedForLittle(){
        // 300 names of 60,000 bytes: 18 MB inflated each, a few kilobytes on the wire
        byte[] bomb = LogicConfig.compress("set a 2", names(300, "a".repeat(60_000)));
        int count = 64;
        var threads = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        // a warm-up tick, so the measured one loads no classes
        assertEquals(List.of(Verdict.rejected(REFUSED)), oneTick(bomb, 1));
        w.drain();
        long allocated = threads.getCurrentThreadAllocatedBytes(), started = System.nanoTime();
        List<Verdict> verdicts = oneTick(bomb, count);
        allocated = threads.getCurrentThreadAllocatedBytes() - allocated;
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertEquals(Collections.nCopies(count, Verdict.rejected(REFUSED)), verdicts);
        List<IntentEvent> events = w.drain();
        assertEquals(count, events.size(), "a rollback each, no forward");
        for(IntentEvent event : events){
            var rollback = assertInstanceOf(IntentCall.To.class, event);
            var packet = assertInstanceOf(Packets.TileConfig.class, rollback.packet());
            assertArrayEquals(before, (byte[])packet.value(), "the processor's own config back");
        }
        assertArrayEquals(before, (byte[])processor.config(), "nothing changed");
        assertEquals("set a 1", processor.processor().code());
        // unbounded, each would read 18 MB of names: 64 of them are over a gigabyte
        assertTrue(allocated < 32L << 20, "allocated " + allocated + " bytes");
        assertTrue(millis < 1_000, "the tick took " + millis + " ms");
    }

    @Test
    void aTicksBudgetAdmitsTheLargestConfigsUntilItIsSpent(){
        // the most code vanilla reads, which deflates to little
        String code = "set a 3\n#" + "x".repeat(LogicConfig.maxByteLen - 9);
        byte[] large = LogicConfig.compress(code, List.of());
        int size = LogicConfig.measure(large, LogicConfig.MAX_INFLATED);
        int fits = LogicPart.CONFIG_BYTES_PER_TICK / size;
        assertEquals(10, fits);

        List<Verdict> verdicts = oneTick(large, 16);
        var expected = new ArrayList<Verdict>();
        for(int i = 0; i < 16; i++) expected.add(i < fits ? Verdict.ADMITTED : Verdict.rejected(REFUSED));
        assertEquals(expected, verdicts);
        List<IntentEvent> events = w.drain();
        assertEquals(fits, events.stream().filter(IntentCall.AllExcept.class::isInstance).count(), "forwarded");
        assertEquals(16 - fits, events.stream().filter(IntentCall.To.class::isInstance).count(), "rolled back");
        assertEquals(code, processor.processor().code());

        // the next tick has its own budget
        assertEquals(List.of(Verdict.ADMITTED), oneTick(LogicConfig.compress("set a 4", List.of()), 1));
        assertEquals("set a 4", processor.processor().code());
    }

    @Test
    void otherConfigsOfAProcessorCostNothing(){
        assertEquals(List.of(Verdict.rejected(REFUSED)), oneTick(LogicConfig.compress("", names(4_000, "n".repeat(64))), 1));
        // a link toggle and a refused config in the same tick: only the code is bounded
        for(int i = 0; i < 4; i++) w.offer(ALPHA, new BlockIntents.TileConfig(NO_RATE_LIMIT, processor.pos(), object(com.xpdustry.shard.common.geom.Point2.pack(16, 12))), 2_000);
        int logged = w.world.log().size();
        w.world.tick();
        for(int i = logged; i < w.world.log().size(); i++) assertEquals(Verdict.ADMITTED, w.world.log().get(i).verdict());
        assertEquals(1, processor.processor().linkCount(), "four toggles: linked again");
    }
}
