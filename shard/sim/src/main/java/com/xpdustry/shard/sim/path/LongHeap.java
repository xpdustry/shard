package com.xpdustry.shard.sim.path;

import java.util.*;

/**
 * A binary min-heap of {@code distance << 32 | tile} keys. Each key is unique while it is queued (a tile is pushed
 * again only at a strictly smaller distance), so the pop order depends only on the keys, never on push order.
 */
final class LongHeap{
    private long[] items;
    private int size;

    LongHeap(){
        items = new long[256];
    }

    private LongHeap(LongHeap source){
        items = Arrays.copyOf(source.items, Math.max(16, source.size));
        size = source.size;
    }

    LongHeap copy(){
        return new LongHeap(this);
    }

    static long key(int distance, int tile){
        return (long)distance << 32 | tile;
    }

    static int distance(long key){
        return (int)(key >>> 32);
    }

    static int tile(long key){
        return (int)key;
    }

    int size(){
        return size;
    }

    boolean isEmpty(){
        return size == 0;
    }

    void clear(){
        size = 0;
    }

    void add(long value){
        if(size == items.length) items = Arrays.copyOf(items, size * 2);
        int i = size++;
        while(i > 0){
            int parent = (i - 1) >> 1;
            if(items[parent] <= value) break;
            items[i] = items[parent];
            i = parent;
        }
        items[i] = value;
    }

    long poll(){
        long top = items[0], last = items[--size];
        int i = 0;
        while(true){
            int left = 2 * i + 1;
            if(left >= size) break;
            int right = left + 1, min = right < size && items[right] < items[left] ? right : left;
            if(items[min] >= last) break;
            items[i] = items[min];
            i = min;
        }
        if(size > 0) items[i] = last;
        return top;
    }
}
