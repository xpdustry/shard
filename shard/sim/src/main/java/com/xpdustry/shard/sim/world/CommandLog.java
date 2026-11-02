package com.xpdustry.shard.sim.world;

import java.util.*;

/**
 * The append-only record of every input a world admitted or rejected, with its verdict, in admission order
 * (ARCHITECTURE.md §4). Together with a checkpoint it reproduces the world; the crash bundle's {@code shard replay}
 * reader is its first user. Entries are immutable, so a fork shares them: forking copies one chunk, not the log.
 *
 * <p>Every entry has a <em>position</em>, counting from the world's first admission. A checkpoint stores only the
 * position its world had reached ({@link #position()}); the entries after it are what a crash bundle carries. Once a
 * checkpoint is durable the runtime {@link #trim(long)}s the entries before it, so the log holds only what replay
 * would need. {@link #size()}, {@link #get(int)} and {@link #entries()} see the entries still held.
 */
public final class CommandLog{
    private final Chunked<Entry> entries;

    public CommandLog(){
        entries = new Chunked<>();
    }

    /** An empty log that continues at {@code position}, for a world restored from a checkpoint. */
    public CommandLog(long position){
        if(position < 0) throw new IllegalArgumentException("negative position " + position);
        entries = new Chunked<>(position);
    }

    private CommandLog(CommandLog source){
        entries = source.entries.copy();
    }

    public void append(Entry entry){
        entries.add(Objects.requireNonNull(entry, "entry"));
    }

    /** The position the next entry gets: how many entries this world has ever logged. */
    public long position(){
        return entries.position();
    }

    /** The position of the first entry still held. */
    public long start(){
        return entries.start();
    }

    /** Entries still held. */
    public int size(){
        return entries.size();
    }

    /** The {@code index}-th entry still held. */
    public Entry get(int index){
        Objects.checkIndex(index, entries.size());
        return entries.at(entries.start() + index);
    }

    /** A read-only view of the entries still held, in admission order. */
    public List<Entry> entries(){
        return entries.from(entries.start());
    }

    /** A read-only view of the entries from {@code position} on (those still held). */
    public List<Entry> since(long position){
        return entries.from(position);
    }

    /** Forgets the entries before {@code position}, once a checkpoint at that position is durable. */
    public void trim(long position){
        entries.trim(position);
    }

    public CommandLog copy(){
        return new CommandLog(this);
    }

    /**
     * One admission.
     *
     * @param tick the world tick ({@link World#tick()}) during whose {@code COMMANDS} phase it was admitted
     * @param arrival the inbox arrival number
     */
    public record Entry(long tick, long arrival, Input input, Verdict verdict){
    }
}
