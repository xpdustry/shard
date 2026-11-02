package com.xpdustry.shard.sim.world;

import java.util.*;

/**
 * Records every tile and building mutation with its {@link Cause} and a before-image (ARCHITECTURE.md §4), for
 * rollback and block logs (note 32). The world owns one; a fork copies it and a checkpoint stores the entries it
 * still holds.
 *
 * <p>Entries have positions, counting from the world's first mutation, like the {@link CommandLog}'s. A journal may
 * keep only the newest entries ({@link MemoryJournal}'s retention); {@link #position()} still counts all of them.
 */
public interface Journal{
    void record(Entry entry);

    /** The entries still held, in record order. */
    List<Entry> entries();

    /** How many entries were ever recorded: the position the next one gets. */
    default long position(){
        return entries().size();
    }

    /** The entries still held from {@code position} on, in record order. */
    default List<Entry> since(long position){
        List<Entry> held = entries();
        long first = position() - held.size();
        return held.subList(Math.clamp(position - first, 0, held.size()), held.size());
    }

    /** The entries still held for the tile at {@code pos}, oldest first: a block log. */
    default List<Entry> history(int pos){
        var out = new ArrayList<Entry>();
        for(Entry entry : entries()){
            if(entry.before().pos() == pos) out.add(entry);
        }
        return out;
    }

    Journal copy();

    /**
     * A new, empty journal of the same kind and retention whose next entry gets {@code position}. Each world takes
     * one from its config's journal, so worlds never share one; a restored world continues the numbering.
     */
    Journal emptyAt(long position);

    /** A journal that keeps nothing, for worlds that need neither rollback nor block logs. */
    static Journal discarding(){
        return Discarding.INSTANCE;
    }

    /** @param tick the world tick ({@link World#tick()}) of the mutation */
    record Entry(long tick, Cause cause, TileImage before){
        public Entry{
            Objects.requireNonNull(cause, "cause");
            Objects.requireNonNull(before, "before");
        }
    }

    final class Discarding implements Journal{
        static final Discarding INSTANCE = new Discarding();

        @Override
        public void record(Entry entry){
        }

        @Override
        public List<Entry> entries(){
            return List.of();
        }

        @Override
        public Journal copy(){
            return this;
        }

        @Override
        public Journal emptyAt(long position){
            return this;
        }
    }
}
