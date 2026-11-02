package com.xpdustry.shard.sim.blocks.units;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.consume.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code PayloadSeq} (PayloadSeq.java): how many of each block or unit type a building holds, in an
 * {@code ObjectIntMap} with a running {@link #total()}. As vanilla's map, a content's entry stays at 0 once removed
 * (only {@link #clear()} drops entries), and the save writes the entries with their zeros.
 *
 * <p>Vanilla's entries come out in its hash map's order, which follows the content objects' identity hashes and so
 * differs from one JVM to the next (a tier exception, as proximity order). Shard keeps them in the order they were
 * first added, which is an order vanilla's map can give; the oracles pin vanilla's map to the same order.
 */
public final class PayloadSeq implements PayloadStock{
    private ContentType[] types = new ContentType[4];
    private int[] ids = new int[4], amounts = new int[4];
    private int size, total;

    public PayloadSeq(){
    }

    /** A copy of {@code source}. */
    public PayloadSeq(PayloadSeq source){
        types = source.types.clone();
        ids = source.ids.clone();
        amounts = source.amounts.clone();
        size = source.size;
        total = source.total;
    }

    /** {@code total()}: the sum of the counts. */
    public int total(){
        return total;
    }

    /** {@code isEmpty()}. */
    public boolean isEmpty(){
        return total == 0;
    }

    /** How many entries the map holds, zeros included ({@code payloads.size}). */
    public int entries(){
        return size;
    }

    public ContentType type(int entry){
        return types[entry];
    }

    public int id(int entry){
        return ids[entry];
    }

    public int amount(int entry){
        return amounts[entry];
    }

    /** {@code add(content, amount)}: {@code payloads.increment(content, amount)}, a new entry at the end. */
    public void add(ContentType type, int id, int amount){
        total += amount;
        int at = find(type, id);
        if(at >= 0){
            amounts[at] += amount;
            return;
        }
        if(size == ids.length){
            types = Arrays.copyOf(types, size * 2);
            ids = Arrays.copyOf(ids, size * 2);
            amounts = Arrays.copyOf(amounts, size * 2);
        }
        types[size] = type;
        ids[size] = id;
        amounts[size++] = amount;
    }

    @Override
    public int get(ContentType type, int id){
        int at = find(type, id);
        return at < 0 ? 0 : amounts[at];
    }

    /** {@code remove(content, amount)}: {@code add(content, -amount)}, which keeps the entry. */
    @Override
    public void remove(ContentType type, int id, int amount){
        add(type, id, -amount);
    }

    /** {@code clear()}. */
    public void clear(){
        size = 0;
        total = 0;
    }

    private int find(ContentType type, int id){
        for(int i = 0; i < size; i++) if(ids[i] == id && types[i] == type) return i;
        return -1;
    }

    public void hash(Hasher hasher){
        hasher.i(size).i(total);
        for(int i = 0; i < size; i++) hasher.i(types[i].ordinal()).i(ids[i]).i(amounts[i]);
    }

    /** The entries as {@code type ordinal, id, amount} triples, for a checkpoint. */
    public int[] toArray(){
        int[] out = new int[size * 3];
        for(int i = 0; i < size; i++){
            out[i * 3] = types[i].ordinal();
            out[i * 3 + 1] = ids[i];
            out[i * 3 + 2] = amounts[i];
        }
        return out;
    }

    /** Refills from {@link #toArray()}'s triples. */
    public void set(int[] triples){
        clear();
        ContentType[] all = ContentType.values();
        for(int i = 0; i < triples.length; i += 3) add(all[triples[i]], triples[i + 1], triples[i + 2]);
    }
}
