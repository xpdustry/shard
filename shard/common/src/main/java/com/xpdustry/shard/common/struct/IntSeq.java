package com.xpdustry.shard.common.struct;

import com.xpdustry.shard.common.math.*;

import java.util.*;
import java.util.function.*;

/**
 * Arc's {@code arc.struct.IntSeq}: a growable int array with the same growth and the same removal semantics. An
 * unordered seq swaps the last element into a removed slot, as Arc's does; vanilla code that iterates one depends on
 * that order.
 */
public final class IntSeq{
    public int[] items;
    public int size;
    public boolean ordered;

    public IntSeq(){
        this(true, 16);
    }

    public IntSeq(int capacity){
        this(true, capacity);
    }

    public IntSeq(boolean ordered, int capacity){
        this.ordered = ordered;
        items = new int[capacity];
    }

    /** A copy holding only {@code size} elements of capacity, like Arc's copy constructor. */
    public IntSeq(IntSeq array){
        this.ordered = array.ordered;
        size = array.size;
        items = new int[size];
        System.arraycopy(array.items, 0, items, 0, size);
    }

    public static IntSeq with(int... array){
        IntSeq out = new IntSeq(true, array.length);
        out.size = array.length;
        System.arraycopy(array, 0, out.items, 0, array.length);
        return out;
    }

    public static IntSeq range(int min, int max){
        IntSeq out = new IntSeq();
        for(int i = min; i < max; i++){
            out.add(i);
        }
        return out;
    }

    /** A deep copy with the same capacity, for world forks. */
    public IntSeq copy(){
        IntSeq out = new IntSeq(ordered, 0);
        out.items = items.clone();
        out.size = size;
        return out;
    }

    public void each(IntConsumer action){
        int size = this.size;
        int[] items = this.items;
        for(int i = 0; i < size; i++){
            action.accept(items[i]);
        }
    }

    public int count(int value){
        int out = 0;
        for(int i = 0; i < size; i++){
            if(items[i] == value) out++;
        }
        return out;
    }

    public int sum(){
        int sum = 0;
        for(int i = 0; i < size; i++){
            sum += items[i];
        }
        return sum;
    }

    public boolean addUnique(int value){
        if(!contains(value)){
            add(value);
            return true;
        }
        return false;
    }

    public void add(int value){
        int[] items = this.items;
        if(size == items.length) items = resize(Math.max(8, (int)(size * 1.75f)));
        items[size++] = value;
    }

    public void add(int value1, int value2){
        int[] items = this.items;
        if(size + 1 >= items.length) items = resize(Math.max(8, (int)(size * 1.75f)));
        items[size] = value1;
        items[size + 1] = value2;
        size += 2;
    }

    public void add(int value1, int value2, int value3){
        int[] items = this.items;
        if(size + 2 >= items.length) items = resize(Math.max(8, (int)(size * 1.75f)));
        items[size] = value1;
        items[size + 1] = value2;
        items[size + 2] = value3;
        size += 3;
    }

    public void addAll(IntSeq array){
        addAll(array.items, 0, array.size);
    }

    public void addAll(int... array){
        addAll(array, 0, array.length);
    }

    public void addAll(int[] array, int offset, int length){
        int[] items = this.items;
        int sizeNeeded = size + length;
        if(sizeNeeded > items.length) items = resize(Math.max(8, (int)(sizeNeeded * 1.75f)));
        System.arraycopy(array, offset, items, size, length);
        size += length;
    }

    public int get(int index){
        if(index >= size) throw new IndexOutOfBoundsException("index can't be >= size: " + index + " >= " + size);
        return items[index];
    }

    public void set(int index, int value){
        if(index >= size) throw new IndexOutOfBoundsException("index can't be >= size: " + index + " >= " + size);
        items[index] = value;
    }

    public void incr(int index, int value){
        if(index >= size) throw new IndexOutOfBoundsException("index can't be >= size: " + index + " >= " + size);
        items[index] += value;
    }

    /** Inserts at {@code index}; an unordered seq moves the old element at {@code index} to the end. */
    public void insert(int index, int value){
        if(index > size) throw new IndexOutOfBoundsException("index can't be > size: " + index + " > " + size);
        int[] items = this.items;
        if(size == items.length) items = resize(Math.max(8, (int)(size * 1.75f)));
        if(ordered)
            System.arraycopy(items, index, items, index + 1, size - index);
        else
            items[size] = items[index];
        size++;
        items[index] = value;
    }

    public void swap(int first, int second){
        if(first >= size) throw new IndexOutOfBoundsException("first can't be >= size: " + first + " >= " + size);
        if(second >= size) throw new IndexOutOfBoundsException("second can't be >= size: " + second + " >= " + size);
        int[] items = this.items;
        int firstValue = items[first];
        items[first] = items[second];
        items[second] = firstValue;
    }

    public boolean contains(int value){
        int i = size - 1;
        int[] items = this.items;
        while(i >= 0)
            if(items[i--] == value) return true;
        return false;
    }

    public int indexOf(int value){
        int[] items = this.items;
        for(int i = 0, n = size; i < n; i++)
            if(items[i] == value) return i;
        return -1;
    }

    public int lastIndexOf(int value){
        int[] items = this.items;
        for(int i = size - 1; i >= 0; i--)
            if(items[i] == value) return i;
        return -1;
    }

    public boolean removeValue(int value){
        int[] items = this.items;
        for(int i = 0, n = size; i < n; i++){
            if(items[i] == value){
                removeIndex(i);
                return true;
            }
        }
        return false;
    }

    /** Removes and returns {@code items[index]}; an unordered seq moves the last element into its place. */
    public int removeIndex(int index){
        if(index >= size) throw new IndexOutOfBoundsException("index can't be >= size: " + index + " >= " + size);
        int[] items = this.items;
        int value = items[index];
        size--;
        if(ordered)
            System.arraycopy(items, index + 1, items, index, size - index);
        else
            items[index] = items[size];
        return value;
    }

    /** Removes [start, end], both inclusive, as Arc does. */
    public void removeRange(int start, int end){
        if(end >= size) throw new IndexOutOfBoundsException("end can't be >= size: " + end + " >= " + size);
        if(start > end) throw new IndexOutOfBoundsException("start can't be > end: " + start + " > " + end);
        int[] items = this.items;
        int count = end - start + 1;
        if(ordered)
            System.arraycopy(items, start + count, items, start, size - (start + count));
        else{
            int lastIndex = this.size - 1;
            for(int i = 0; i < count; i++)
                items[start + i] = items[lastIndex - i];
        }
        size -= count;
    }

    public int pop(){
        return items[--size];
    }

    public int peek(){
        return items[size - 1];
    }

    public int first(){
        if(size == 0) throw new IllegalStateException("Array is empty.");
        return items[0];
    }

    public boolean isEmpty(){
        return size == 0;
    }

    public boolean any(){
        return size > 0;
    }

    public void clear(){
        size = 0;
    }

    public int[] ensureCapacity(int additionalCapacity){
        if(additionalCapacity < 0)
            throw new IllegalArgumentException("additionalCapacity must be >= 0: " + additionalCapacity);
        int sizeNeeded = size + additionalCapacity;
        if(sizeNeeded > items.length) resize(Math.max(8, sizeNeeded));
        return items;
    }

    public int[] setSize(int newSize){
        if(newSize < 0) throw new IllegalArgumentException("newSize must be >= 0: " + newSize);
        if(newSize > items.length) resize(Math.max(8, newSize));
        size = newSize;
        return items;
    }

    private int[] resize(int newSize){
        int[] newItems = new int[newSize];
        int[] items = this.items;
        System.arraycopy(items, 0, newItems, 0, Math.min(size, newItems.length));
        this.items = newItems;
        return newItems;
    }

    public void sort(){
        Arrays.sort(items, 0, size);
    }

    public void reverse(){
        int[] items = this.items;
        for(int i = 0, lastIndex = size - 1, n = size / 2; i < n; i++){
            int ii = lastIndex - i;
            int temp = items[i];
            items[i] = items[ii];
            items[ii] = temp;
        }
    }

    /** Arc's Fisher-Yates shuffle, drawing from {@code rand}. */
    public void shuffle(Rand rand){
        int[] items = this.items;
        for(int i = size - 1; i >= 0; i--){
            int ii = rand.nextInt(i + 1);
            int temp = items[i];
            items[i] = items[ii];
            items[ii] = temp;
        }
    }

    public void truncate(int newSize){
        if(size > newSize) size = newSize;
    }

    public int[] toArray(){
        int[] array = new int[size];
        System.arraycopy(items, 0, array, 0, size);
        return array;
    }

    @Override
    public boolean equals(Object object){
        if(object == this) return true;
        if(!(object instanceof IntSeq other) || !ordered || !other.ordered || other.size != size) return false;
        return Arrays.equals(items, 0, size, other.items, 0, size);
    }

    @Override
    public int hashCode(){
        int h = 1;
        for(int i = 0, n = size; i < n; i++)
            h = h * 31 + items[i];
        return h;
    }

    @Override
    public String toString(){
        return Arrays.toString(toArray());
    }
}
