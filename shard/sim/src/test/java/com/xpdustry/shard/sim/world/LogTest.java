package com.xpdustry.shard.sim.world;

import com.xpdustry.shard.sim.toy.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** {@link CommandLog} and {@link MemoryJournal}: positions, trimming, retention, and cheap independent copies. */
class LogTest{
    record Nop(int n) implements Command.Script{
        @Override
        public Verdict apply(World world, Cause cause){
            return Verdict.ADMITTED;
        }
    }

    static CommandLog.Entry entry(long n){
        return new CommandLog.Entry(n / 10, n, new Submission(new Nop((int)n), Cause.SERVER), Verdict.ADMITTED);
    }

    static Journal.Entry change(long n){
        return new Journal.Entry(n, Cause.SERVER, new TileImage((int)n, (short)0, (short)0, (short)1, (byte)0, -1, (byte)0, (byte)0, (byte)0, 0));
    }

    @Test
    void theLogCountsPositionsAcrossTrims(){
        var log = new CommandLog();
        for(int i = 0; i < 5000; i++) log.append(entry(i));
        assertEquals(5000, log.position());
        assertEquals(0, log.start());
        log.trim(2500);
        assertEquals(2500, log.start(), "the start is exact even though whole chunks are dropped");
        assertEquals(2500, log.size());
        assertEquals(entry(2500), log.entries().getFirst());
        assertEquals(entry(4999), log.since(4999).getFirst());
        assertEquals(List.of(), log.since(5000));
        log.trim(9999);
        assertEquals(5000, log.start(), "trimming past the end empties the log");
        log.append(entry(5000));
        assertEquals(List.of(entry(5000)), log.entries());
    }

    @Test
    void aLogCanStartMidStream(){
        var log = new CommandLog(123_456);
        assertEquals(123_456, log.position());
        assertEquals(123_456, log.start());
        log.append(entry(1));
        assertEquals(123_457, log.position());
        assertEquals(List.of(entry(1)), log.since(123_456));
    }

    @Test
    void copiesShareHistoryButNotTheFuture(){
        var log = new CommandLog();
        for(int i = 0; i < 2100; i++) log.append(entry(i));
        CommandLog copy = log.copy();
        log.append(entry(-1));
        copy.append(entry(-2));
        assertEquals(entry(-1), log.get(2100));
        assertEquals(entry(-2), copy.get(2100));
        assertEquals(log.entries().subList(0, 2100), copy.entries().subList(0, 2100));
        copy.trim(2000);
        assertEquals(0, log.start(), "trimming a copy leaves the original");
    }

    @Test
    void theJournalKeepsItsRetentionAndCountsTheRest(){
        var journal = new MemoryJournal(1500);
        for(int i = 0; i < 4000; i++) journal.record(change(i));
        assertEquals(4000, journal.position());
        assertEquals(1500, journal.entries().size());
        assertEquals(2500, journal.dropped());
        assertEquals(change(2500), journal.entries().getFirst());
        assertEquals(List.of(change(3999)), journal.since(3999));
        assertEquals(1500, journal.since(0).size(), "a position before the retention clamps to what is held");

        Journal copy = journal.copy();
        copy.record(change(-1));
        assertEquals(4000, journal.position());
        assertEquals(4001, copy.position());

        Journal fresh = journal.emptyAt(4000);
        assertEquals(4000, fresh.position());
        assertEquals(List.of(), fresh.entries());
        assertSame(Journal.discarding(), Journal.discarding().emptyAt(9));
    }

    @Test
    void mutationsAreJournaledWithTheirCause(){
        World world = Worlds.quiet(4);
        long before = world.journal().position();
        var building = world.buildings().create(ToyContent.COUNTER, 1, 3, 3, 0, new Cause.Player(7));
        world.buildings().remove(building, new Cause.Admin("mod"));
        List<Journal.Entry> entries = world.journal().since(before);
        assertFalse(entries.isEmpty());
        assertEquals(new Cause.Player(7), entries.getFirst().cause());
        assertEquals(new Cause.Admin("mod"), entries.getLast().cause());
        assertFalse(world.journal().history(world.tiles().pos(3, 3)).isEmpty());
    }
}
