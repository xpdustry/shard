package com.xpdustry.shard.common.struct;

import java.util.*;
import java.util.function.*;

/**
 * Arc's {@code arc.struct.IntSet}: linear probing with Fibonacci hashing, key 0 kept apart, backward-shift deletion.
 * The table layout follows the same history as Arc's, so iteration order (0 first, then table order) matches vanilla.
 * Iteration is by {@link #each(IntConsumer)} or {@link #toSeq()} instead of Arc's reusable iterators.
 */
public final class IntSet{
    public int size;
    int[] keyTable;
    boolean hasZeroValue;
    private final float loadFactor;
    private int threshold;
    private int shift;
    private int mask;

    public IntSet(){
        this(51, 0.8f);
    }

    public IntSet(int initialCapacity){
        this(initialCapacity, 0.8f);
    }

    public IntSet(int initialCapacity, float loadFactor){
        Tables.checkLoadFactor(loadFactor);
        this.loadFactor = loadFactor;
        int tableSize = Tables.tableSize(initialCapacity, loadFactor);
        threshold = (int)(tableSize * loadFactor);
        mask = tableSize - 1;
        shift = Long.numberOfLeadingZeros(mask);
        keyTable = new int[tableSize];
    }

    /** Arc's copy constructor: same capacity, same layout. */
    public IntSet(IntSet set){
        this((int)(set.keyTable.length * set.loadFactor), set.loadFactor);
        System.arraycopy(set.keyTable, 0, keyTable, 0, set.keyTable.length);
        size = set.size;
        hasZeroValue = set.hasZeroValue;
    }

    public static IntSet with(int... array){
        IntSet set = new IntSet(array.length);
        set.addAll(array);
        return set;
    }

    /** A deep copy with the same layout, for world forks. */
    public IntSet copy(){
        return new IntSet(this);
    }

    private int place(int item){
        return (int)(item * 0x9E3779B97F4A7C15L >>> shift);
    }

    private int locateKey(int key){
        int[] keyTable = this.keyTable;
        for(int i = place(key); ; i = i + 1 & mask){
            int other = keyTable[i];
            if(other == 0) return -(i + 1);
            if(other == key) return i;
        }
    }

    public boolean add(int key){
        if(key == 0){
            if(hasZeroValue) return false;
            hasZeroValue = true;
            size++;
            return true;
        }
        int i = locateKey(key);
        if(i >= 0) return false;
        i = -(i + 1);
        keyTable[i] = key;
        if(++size >= threshold) resize(keyTable.length << 1);
        return true;
    }

    public void addAll(IntSeq array){
        addAll(array.items, 0, array.size);
    }

    public void addAll(int... array){
        addAll(array, 0, array.length);
    }

    public void addAll(int[] array, int offset, int length){
        ensureCapacity(length);
        for(int i = offset, n = i + length; i < n; i++)
            add(array[i]);
    }

    public void addAll(IntSet set){
        ensureCapacity(set.size);
        if(set.hasZeroValue) add(0);
        int[] keyTable = set.keyTable;
        for(int i = 0, n = keyTable.length; i < n; i++){
            int key = keyTable[i];
            if(key != 0) add(key);
        }
    }

    private void addResize(int key){
        int[] keyTable = this.keyTable;
        for(int i = place(key); ; i = (i + 1) & mask){
            if(keyTable[i] == 0){
                keyTable[i] = key;
                return;
            }
        }
    }

    public boolean remove(int key){
        if(key == 0){
            if(!hasZeroValue) return false;
            hasZeroValue = false;
            size--;
            return true;
        }
        int i = locateKey(key);
        if(i < 0) return false;
        int[] keyTable = this.keyTable;
        int mask = this.mask, next = i + 1 & mask;
        while((key = keyTable[next]) != 0){
            int placement = place(key);
            if((next - placement & mask) > (i - placement & mask)){
                keyTable[i] = key;
                i = next;
            }
            next = next + 1 & mask;
        }
        keyTable[i] = 0;
        size--;
        return true;
    }

    public boolean notEmpty(){
        return size > 0;
    }

    public boolean isEmpty(){
        return size == 0;
    }

    public void shrink(int maximumCapacity){
        if(maximumCapacity < 0) throw new IllegalArgumentException("maximumCapacity must be >= 0: " + maximumCapacity);
        if(size > maximumCapacity) maximumCapacity = size;
        int tableSize = Tables.tableSize(maximumCapacity, loadFactor);
        if(keyTable.length > tableSize) resize(tableSize);
    }

    public void clear(int maximumCapacity){
        int tableSize = Tables.tableSize(maximumCapacity, loadFactor);
        if(keyTable.length <= tableSize){
            clear();
            return;
        }
        size = 0;
        hasZeroValue = false;
        resize(tableSize);
    }

    public void clear(){
        if(size == 0) return;
        size = 0;
        Arrays.fill(keyTable, 0);
        hasZeroValue = false;
    }

    public boolean contains(int key){
        if(key == 0) return hasZeroValue;
        return locateKey(key) >= 0;
    }

    public int first(){
        if(hasZeroValue) return 0;
        int[] keyTable = this.keyTable;
        for(int i = 0, n = keyTable.length; i < n; i++)
            if(keyTable[i] != 0) return keyTable[i];
        throw new IllegalStateException("IntSet is empty.");
    }

    public void ensureCapacity(int additionalCapacity){
        int tableSize = Tables.tableSize(size + additionalCapacity, loadFactor);
        if(keyTable.length < tableSize) resize(tableSize);
    }

    private void resize(int newSize){
        int oldCapacity = keyTable.length;
        threshold = (int)(newSize * loadFactor);
        mask = newSize - 1;
        shift = Long.numberOfLeadingZeros(mask);
        int[] oldKeyTable = keyTable;
        keyTable = new int[newSize];
        if(size > 0){
            for(int i = 0; i < oldCapacity; i++){
                int key = oldKeyTable[i];
                if(key != 0) addResize(key);
            }
        }
    }

    /** Calls {@code action} for each key in Arc's iteration order: 0 first, then table order. */
    public void each(IntConsumer action){
        if(hasZeroValue) action.accept(0);
        int[] keyTable = this.keyTable;
        for(int i = 0, n = keyTable.length; i < n; i++){
            if(keyTable[i] != 0) action.accept(keyTable[i]);
        }
    }

    /** The keys in iteration order. */
    public IntSeq toSeq(){
        IntSeq out = new IntSeq(true, size);
        each(out::add);
        return out;
    }

    @Override
    public int hashCode(){
        int h = size;
        int[] keyTable = this.keyTable;
        for(int i = 0, n = keyTable.length; i < n; i++){
            int key = keyTable[i];
            if(key != 0) h += key;
        }
        return h;
    }

    @Override
    public boolean equals(Object obj){
        if(!(obj instanceof IntSet other)) return false;
        if(other.size != size) return false;
        if(other.hasZeroValue != hasZeroValue) return false;
        int[] keyTable = this.keyTable;
        for(int i = 0, n = keyTable.length; i < n; i++)
            if(keyTable[i] != 0 && !other.contains(keyTable[i])) return false;
        return true;
    }

    @Override
    public String toString(){
        return toSeq().toString();
    }
}
