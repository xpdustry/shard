package com.xpdustry.shard.common.struct;

import java.util.*;

/**
 * Arc's {@code arc.struct.IntIntMap}: linear probing with Fibonacci hashing, key 0 kept apart, backward-shift deletion.
 * The table layout follows the same history as Arc's, so iteration order (0 first, then table order) matches vanilla.
 * Iteration is by {@link #each(Entries)}, {@link #keys()} or {@link #values()} instead of Arc's reusable iterators.
 */
public final class IntIntMap{
    public int size;
    int[] keyTable;
    int[] valueTable;
    int zeroValue;
    boolean hasZeroValue;
    private final float loadFactor;
    private int threshold;
    private int shift;
    private int mask;

    public IntIntMap(){
        this(51, 0.8f);
    }

    public IntIntMap(int initialCapacity){
        this(initialCapacity, 0.8f);
    }

    public IntIntMap(int initialCapacity, float loadFactor){
        Tables.checkLoadFactor(loadFactor);
        this.loadFactor = loadFactor;
        int tableSize = Tables.tableSize(initialCapacity, loadFactor);
        threshold = (int)(tableSize * loadFactor);
        mask = tableSize - 1;
        shift = Long.numberOfLeadingZeros(mask);
        keyTable = new int[tableSize];
        valueTable = new int[tableSize];
    }

    /** Arc's copy constructor: same capacity, same layout. */
    public IntIntMap(IntIntMap map){
        this((int)(map.keyTable.length * map.loadFactor), map.loadFactor);
        System.arraycopy(map.keyTable, 0, keyTable, 0, map.keyTable.length);
        System.arraycopy(map.valueTable, 0, valueTable, 0, map.valueTable.length);
        size = map.size;
        zeroValue = map.zeroValue;
        hasZeroValue = map.hasZeroValue;
    }

    public static IntIntMap of(int... values){
        IntIntMap map = new IntIntMap();
        for(int i = 0; i < values.length; i += 2){
            map.put(values[i], values[i + 1]);
        }
        return map;
    }

    /** A deep copy with the same layout, for world forks. */
    public IntIntMap copy(){
        return new IntIntMap(this);
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

    public void put(int key, int value){
        if(key == 0){
            zeroValue = value;
            if(!hasZeroValue){
                hasZeroValue = true;
                size++;
            }
            return;
        }
        int i = locateKey(key);
        if(i >= 0){
            valueTable[i] = value;
            return;
        }
        i = -(i + 1);
        keyTable[i] = key;
        valueTable[i] = value;
        if(++size >= threshold) resize(keyTable.length << 1);
    }

    /** Returns the old value associated with the specified key, or the specified default value. */
    public int put(int key, int value, int defaultValue){
        if(key == 0){
            int oldValue = zeroValue;
            zeroValue = value;
            if(!hasZeroValue){
                hasZeroValue = true;
                size++;
                return defaultValue;
            }
            return oldValue;
        }
        int i = locateKey(key);
        if(i >= 0){
            int oldValue = valueTable[i];
            valueTable[i] = value;
            return oldValue;
        }
        i = -(i + 1);
        keyTable[i] = key;
        valueTable[i] = value;
        if(++size >= threshold) resize(keyTable.length << 1);
        return defaultValue;
    }

    /** Puts {@code value} if {@code key} is missing; returns the current value, or {@code value} if it was missing. */
    public int putMissing(int key, int value){
        return putMissing(key, value, value);
    }

    /** Puts {@code value} if {@code key} is missing; returns the current value, or {@code defaultValue} if it was missing. */
    public int putMissing(int key, int value, int defaultValue){
        if(key == 0){
            if(!hasZeroValue){
                zeroValue = value;
                hasZeroValue = true;
                size++;
                return defaultValue;
            }
            return zeroValue;
        }
        int i = locateKey(key);
        if(i >= 0) return valueTable[i];
        i = -(i + 1);
        keyTable[i] = key;
        valueTable[i] = value;
        if(++size >= threshold) resize(keyTable.length << 1);
        return defaultValue;
    }

    public void putAll(IntIntMap map){
        ensureCapacity(map.size);
        if(map.hasZeroValue) put(0, map.zeroValue);
        int[] keyTable = map.keyTable;
        int[] valueTable = map.valueTable;
        for(int i = 0, n = keyTable.length; i < n; i++){
            int key = keyTable[i];
            if(key != 0) put(key, valueTable[i]);
        }
    }

    private void putResize(int key, int value){
        int[] keyTable = this.keyTable;
        for(int i = place(key); ; i = (i + 1) & mask){
            if(keyTable[i] == 0){
                keyTable[i] = key;
                valueTable[i] = value;
                return;
            }
        }
    }

    public int get(int key){
        return get(key, 0);
    }

    public int get(int key, int defaultValue){
        if(key == 0) return hasZeroValue ? zeroValue : defaultValue;
        int i = locateKey(key);
        return i >= 0 ? valueTable[i] : defaultValue;
    }

    public int increment(int key){
        return increment(key, 0, 1);
    }

    public int increment(int key, int increment){
        return increment(key, 0, increment);
    }

    /** Returns the key's current value and adds {@code increment}; a missing key starts at {@code defaultValue}. */
    public int increment(int key, int defaultValue, int increment){
        if(key == 0){
            if(!hasZeroValue){
                hasZeroValue = true;
                zeroValue = defaultValue + increment;
                size++;
                return defaultValue;
            }
            int oldValue = zeroValue;
            zeroValue += increment;
            return oldValue;
        }
        int i = locateKey(key);
        if(i >= 0){
            int oldValue = valueTable[i];
            valueTable[i] += increment;
            return oldValue;
        }
        i = -(i + 1);
        keyTable[i] = key;
        valueTable[i] = defaultValue + increment;
        if(++size >= threshold) resize(keyTable.length << 1);
        return defaultValue;
    }

    public int remove(int key){
        return remove(key, 0);
    }

    public int remove(int key, int defaultValue){
        if(key == 0){
            if(!hasZeroValue) return defaultValue;
            hasZeroValue = false;
            size--;
            return zeroValue;
        }
        int i = locateKey(key);
        if(i < 0) return defaultValue;
        int[] keyTable = this.keyTable;
        int[] valueTable = this.valueTable;
        int oldValue = valueTable[i], mask = this.mask, next = i + 1 & mask;
        while((key = keyTable[next]) != 0){
            int placement = place(key);
            if((next - placement & mask) > (i - placement & mask)){
                keyTable[i] = key;
                valueTable[i] = valueTable[next];
                i = next;
            }
            next = next + 1 & mask;
        }
        keyTable[i] = 0;
        size--;
        return oldValue;
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
        Arrays.fill(keyTable, 0);
        size = 0;
        hasZeroValue = false;
    }

    public boolean containsValue(int value){
        if(hasZeroValue && zeroValue == value) return true;
        int[] keyTable = this.keyTable;
        int[] valueTable = this.valueTable;
        for(int i = valueTable.length - 1; i >= 0; i--)
            if(keyTable[i] != 0 && valueTable[i] == value) return true;
        return false;
    }

    public boolean containsKey(int key){
        if(key == 0) return hasZeroValue;
        return locateKey(key) >= 0;
    }

    /** The key for {@code value}, searching from the end of the table as Arc does, or {@code notFound}. */
    public int findKey(int value, int notFound){
        if(hasZeroValue && zeroValue == value) return 0;
        int[] keyTable = this.keyTable;
        int[] valueTable = this.valueTable;
        for(int i = valueTable.length - 1; i >= 0; i--){
            int key = keyTable[i];
            if(key != 0 && valueTable[i] == value) return key;
        }
        return notFound;
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
        int[] oldValueTable = valueTable;
        keyTable = new int[newSize];
        valueTable = new int[newSize];
        if(size > 0){
            for(int i = 0; i < oldCapacity; i++){
                int key = oldKeyTable[i];
                if(key != 0) putResize(key, oldValueTable[i]);
            }
        }
    }

    /** Calls {@code action} for each entry in Arc's iteration order: key 0 first, then table order. */
    public void each(Entries action){
        if(hasZeroValue) action.accept(0, zeroValue);
        int[] keyTable = this.keyTable;
        int[] valueTable = this.valueTable;
        for(int i = 0, n = keyTable.length; i < n; i++){
            if(keyTable[i] != 0) action.accept(keyTable[i], valueTable[i]);
        }
    }

    /** The keys in iteration order. */
    public IntSeq keys(){
        IntSeq out = new IntSeq(true, size);
        each((key, value) -> out.add(key));
        return out;
    }

    /** The values in iteration order. */
    public IntSeq values(){
        IntSeq out = new IntSeq(true, size);
        each((key, value) -> out.add(value));
        return out;
    }

    @Override
    public int hashCode(){
        int h = size;
        if(hasZeroValue) h += zeroValue;
        int[] keyTable = this.keyTable;
        int[] valueTable = this.valueTable;
        for(int i = 0, n = keyTable.length; i < n; i++){
            int key = keyTable[i];
            if(key != 0) h += key * 31 + valueTable[i];
        }
        return h;
    }

    @Override
    public boolean equals(Object obj){
        if(obj == this) return true;
        if(!(obj instanceof IntIntMap other)) return false;
        if(other.size != size) return false;
        if(other.hasZeroValue != hasZeroValue) return false;
        if(hasZeroValue && other.zeroValue != zeroValue) return false;
        int[] keyTable = this.keyTable;
        int[] valueTable = this.valueTable;
        for(int i = 0, n = keyTable.length; i < n; i++){
            int key = keyTable[i];
            if(key != 0){
                int otherValue = other.get(key, 0);
                if(otherValue == 0 && !other.containsKey(key)) return false;
                if(otherValue != valueTable[i]) return false;
            }
        }
        return true;
    }

    @Override
    public String toString(){
        var out = new ArrayList<String>(size);
        each((key, value) -> out.add(key + "=" + value));
        return out.toString();
    }

    @FunctionalInterface
    public interface Entries{
        void accept(int key, int value);
    }
}
