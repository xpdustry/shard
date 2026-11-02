package com.xpdustry.shard.sim.world;

import java.util.*;

/**
 * Remaps references while a world is copied (ARCHITECTURE.md §3 "Fork and checkpoint"). {@link #map(Copyable)}
 * returns the copy of an object, creating an empty one and queueing it the first time; {@link #finish()} then fills
 * the queue in order. Objects are filled from a work list, never recursively, so a chain of 100k linked buildings
 * doesn't overflow the stack.
 *
 * <p>Objects that are no longer in the world but are still referenced (a dead target, a removed building) are
 * copied too, as detached objects, so the fork sees the same {@code isAdded()} answers as the source.
 */
public final class CopyContext{
    private final World target;
    private final IdentityHashMap<Copyable, Copyable> copies = new IdentityHashMap<>();
    private final ArrayDeque<Copyable> pending = new ArrayDeque<>();

    public CopyContext(World target){
        this.target = target;
    }

    /** The world being built. Copies that hold their world point here. */
    public World world(){
        return target;
    }

    /** The copy of {@code source}, or null for null. The copy is empty until {@link #finish()} runs. */
    @SuppressWarnings("unchecked")
    public <T extends Copyable> T map(T source){
        if(source == null) return null;
        Copyable copy = copies.get(source);
        if(copy == null){
            copy = source.blank();
            if(copy.getClass() != source.getClass()){
                throw new IllegalStateException(source.getClass().getName() + ".blank() returned a "
                    + copy.getClass().getName());
            }
            copies.put(source, copy);
            pending.add(source);
        }
        return (T)copy;
    }

    /** Fills every queued copy, including those queued while filling. */
    public void finish(){
        for(Copyable source; (source = pending.poll()) != null; ){
            source.copyTo(copies.get(source), this);
        }
    }

    /** How many objects were copied so far. */
    public int size(){
        return copies.size();
    }
}
