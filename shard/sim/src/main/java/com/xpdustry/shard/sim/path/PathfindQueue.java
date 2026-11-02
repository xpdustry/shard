package com.xpdustry.shard.sim.path;

import java.util.*;

/**
 * Vanilla's {@code PathfindQueue} ({@code PathfindQueue.java}): a binary min-heap of ints by float weight, which
 * {@link ControlPathfinder}'s two A* searches pop. Equal weights come out in the heap's order, not insertion order, so
 * the sift steps are vanilla's to the comparison: a search that breaks ties another way finds another of the
 * equally short paths.
 */
final class PathfindQueue{
    int[] queue;
    float[] weights;
    int size;

    PathfindQueue(){
        this(12);
    }

    PathfindQueue(int capacity){
        queue = new int[capacity];
        weights = new float[capacity];
    }

    boolean empty(){
        return size == 0;
    }

    void add(int e, float weight){
        int i = size;
        if(i >= queue.length) grow(i + 1);
        size = i + 1;
        if(i == 0){
            queue[0] = e;
            weights[0] = weight;
        }else{
            siftUp(i, e, weight);
        }
    }

    void clear(){
        size = 0;
    }

    /** The lightest element, removed; 0 when empty, as vanilla's. */
    int poll(){
        if(size == 0) return 0;
        int s = --size;
        int result = queue[0];
        int x = queue[s];
        if(s != 0) siftDown(0, x, weights[s]);
        return result;
    }

    private void siftUp(int k, int x, float weight){
        while(k > 0){
            int parent = (k - 1) >>> 1;
            int e = queue[parent];
            if(weight >= weights[parent]) break;
            queue[k] = e;
            weights[k] = weights[parent];
            k = parent;
        }
        queue[k] = x;
        weights[k] = weight;
    }

    private void siftDown(int k, int x, float weight){
        int half = size >>> 1;
        while(k < half){
            int child = (k << 1) + 1;
            int c = queue[child];
            int right = child + 1;
            if(right < size && weights[child] > weights[right]){
                c = queue[child = right];
            }
            if(weight <= weights[child]) break;
            queue[k] = c;
            weights[k] = weights[child];
            k = child;
        }
        queue[k] = x;
        weights[k] = weight;
    }

    /** {@code growToSize}: doubles a small heap, else grows it by half. */
    private void grow(int minCapacity){
        int oldCapacity = queue.length;
        int newCapacity = (int)(oldCapacity < 64 ? (oldCapacity + 1) * 2.0 : oldCapacity * 1.5);
        if(newCapacity < 0) newCapacity = Integer.MAX_VALUE;
        if(newCapacity < minCapacity) newCapacity = minCapacity;
        queue = Arrays.copyOf(queue, newCapacity);
        weights = Arrays.copyOf(weights, newCapacity);
    }
}
