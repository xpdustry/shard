package com.xpdustry.shard.logic;

import java.util.*;

/**
 * Assembled programs by code and {@link Assembler.Options}, shared by every processor and world on one
 * {@link Globals} table: the 400 copies of a sorter on a map are one program. Least recently used programs are
 * dropped past the capacity. Thread-safe; programs are immutable.
 */
public final class ProgramCache{
    private final Globals globals;
    private final int capacity;
    private final LinkedHashMap<Key, Program> programs;
    private long hits, misses;

    private record Key(String code, Assembler.Options options){
    }

    ProgramCache(Globals globals, int capacity){
        this.globals = globals;
        this.capacity = capacity;
        this.programs = new LinkedHashMap<>(64, 0.75f, true){
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, Program> eldest){
                return size() > ProgramCache.this.capacity;
            }
        };
    }

    /** The program for {@code code}, assembled on a miss. */
    public Program get(String code, Assembler.Options options){
        Key key = new Key(code, options);
        synchronized(this){
            Program program = programs.get(key);
            if(program != null){
                hits++;
                return program;
            }
        }
        Program program = Assembler.assemble(code, options, globals, globals.statusEffects());
        synchronized(this){
            misses++;
            Program existing = programs.putIfAbsent(key, program);
            return existing != null ? existing : program;
        }
    }

    public synchronized int size(){
        return programs.size();
    }

    public synchronized long hits(){
        return hits;
    }

    public synchronized long misses(){
        return misses;
    }
}
