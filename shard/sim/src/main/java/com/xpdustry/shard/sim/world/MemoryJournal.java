package com.xpdustry.shard.sim.world;

import java.util.*;

/**
 * A {@link Journal} that keeps the newest {@link #retention()} entries in memory and forgets older ones. Forks share
 * its full chunks, so forking a world with a long journal is cheap. WP32 adds persistence beyond the checkpoint and
 * building before-images.
 */
public final class MemoryJournal implements Journal{
    /** About 15 MB of entries at most per world. */
    public static final int DEFAULT_RETENTION = 1 << 18;

    private final int retention;
    private final Chunked<Entry> entries;

    public MemoryJournal(){
        this(DEFAULT_RETENTION);
    }

    public MemoryJournal(int retention){
        this(retention, 0);
    }

    /** An empty journal keeping at most {@code retention} entries, whose next entry gets {@code position}. */
    public MemoryJournal(int retention, long position){
        if(retention <= 0) throw new IllegalArgumentException("retention must be positive: " + retention);
        this.retention = retention;
        this.entries = new Chunked<>(position);
    }

    private MemoryJournal(MemoryJournal source){
        retention = source.retention;
        entries = source.entries.copy();
    }

    @Override
    public void record(Entry entry){
        entries.add(Objects.requireNonNull(entry, "entry"));
        if(entries.size() > retention) entries.trim(entries.position() - retention);
    }

    @Override
    public List<Entry> entries(){
        return entries.from(entries.start());
    }

    @Override
    public long position(){
        return entries.position();
    }

    @Override
    public List<Entry> since(long position){
        return entries.from(position);
    }

    /** How many entries this journal keeps at most. */
    public int retention(){
        return retention;
    }

    /** Entries forgotten because of the retention. */
    public long dropped(){
        return entries.start();
    }

    @Override
    public Journal copy(){
        return new MemoryJournal(this);
    }

    @Override
    public Journal emptyAt(long position){
        return new MemoryJournal(retention, position);
    }
}
