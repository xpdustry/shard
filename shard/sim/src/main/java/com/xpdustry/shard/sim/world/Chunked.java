package com.xpdustry.shard.sim.world;

import java.util.*;

/**
 * An append-only list of immutable values in fixed-size chunks, behind {@link CommandLog} and {@link MemoryJournal}.
 * A copy shares every full chunk with its source and copies only the last one, so forking a world with a long
 * history costs one chunk, not the history. The front can be trimmed, which drops whole chunks once nothing in them
 * is visible.
 *
 * <p>Positions count every value ever appended: {@link #start()} is the position of the first value still held,
 * {@link #position()} the position the next append gets.
 */
final class Chunked<T>{
    static final int CHUNK = 1024;

    /** Every chunk but the last is full and never written again, so copies share them. */
    private ArrayList<Object[]> chunks;
    /** The position of {@code chunks.get(0)[0]}. */
    private long first;
    private long start;
    private long end;

    Chunked(){
        this(0);
    }

    /** An empty list whose first append gets {@code position}, as a restored log continues its numbering. */
    Chunked(long position){
        chunks = new ArrayList<>();
        first = start = end = position;
    }

    private Chunked(Chunked<T> source){
        chunks = new ArrayList<>(source.chunks);
        first = source.first;
        start = source.start;
        end = source.end;
        if(!chunks.isEmpty()) chunks.set(chunks.size() - 1, chunks.getLast().clone());
    }

    Chunked<T> copy(){
        return new Chunked<>(this);
    }

    void add(T value){
        Objects.requireNonNull(value, "value");
        if(end - first == (long)chunks.size() * CHUNK) chunks.add(new Object[CHUNK]);
        chunks.getLast()[(int)((end - first) % CHUNK)] = value;
        end++;
    }

    /** The value at {@code position}, which must be held. */
    @SuppressWarnings("unchecked")
    T at(long position){
        if(position < start || position >= end) throw new IndexOutOfBoundsException("position " + position + " outside " + start + ".." + end);
        long relative = position - first;
        return (T)chunks.get((int)(relative / CHUNK))[(int)(relative % CHUNK)];
    }

    long start(){
        return start;
    }

    long position(){
        return end;
    }

    int size(){
        return (int)(end - start);
    }

    /** Forgets every value before {@code position}; positions stay as they were. */
    void trim(long position){
        if(position <= start) return;
        start = Math.min(position, end);
        while(!chunks.isEmpty() && first + CHUNK <= start){
            chunks.removeFirst();
            first += CHUNK;
        }
    }

    /** A read-only view of the held values from {@code position} on, which follows later appends. */
    List<T> from(long position){
        long from = Math.max(position, start);
        return new AbstractList<>(){
            @Override
            public T get(int index){
                Objects.checkIndex(index, size());
                return at(from + index);
            }

            @Override
            public int size(){
                return (int)Math.max(0, end - from);
            }
        };
    }
}
