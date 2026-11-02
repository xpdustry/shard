package com.xpdustry.shard.common.struct;

import java.util.*;

/** Arc's {@code arc.struct.IntQueue}: a ring-buffer deque of ints with the same growth and removal semantics. */
public final class IntQueue{
    public int size = 0;
    public int[] values;
    private int head = 0;
    private int tail = 0;

    public IntQueue(){
        this(16);
    }

    public IntQueue(int initialSize){
        this.values = new int[initialSize];
    }

    /** A deep copy with the same layout, for world forks. */
    public IntQueue copy(){
        IntQueue out = new IntQueue(0);
        out.values = values.clone();
        out.size = size;
        out.head = head;
        out.tail = tail;
        return out;
    }

    public void addLast(int object){
        int[] values = this.values;
        if(size == values.length){
            resize(values.length << 1);
            values = this.values;
        }
        values[tail++] = object;
        if(tail == values.length){
            tail = 0;
        }
        size++;
    }

    public void addFirst(int object){
        int[] values = this.values;
        if(size == values.length){
            resize(values.length << 1);
            values = this.values;
        }
        int head = this.head;
        head--;
        if(head == -1){
            head = values.length - 1;
        }
        values[head] = object;
        this.head = head;
        this.size++;
    }

    public void ensureCapacity(int additional){
        final int needed = size + additional;
        if(values.length < needed){
            resize(needed);
        }
    }

    private void resize(int newSize){
        final int[] values = this.values;
        final int head = this.head;
        final int tail = this.tail;

        final int[] newArray = new int[newSize];
        if(head < tail){
            System.arraycopy(values, head, newArray, 0, tail - head);
        }else if(size > 0){
            final int rest = values.length - head;
            System.arraycopy(values, head, newArray, 0, rest);
            System.arraycopy(values, 0, newArray, rest, tail);
        }
        this.values = newArray;
        this.head = 0;
        this.tail = size;
    }

    public int removeFirst(){
        if(size == 0){
            throw new NoSuchElementException("Queue is empty.");
        }
        final int[] values = this.values;
        final int result = values[head];
        values[head] = 0;
        head++;
        if(head == values.length){
            head = 0;
        }
        size--;
        return result;
    }

    public int removeLast(){
        if(size == 0){
            throw new NoSuchElementException("Queue is empty.");
        }
        final int[] values = this.values;
        int tail = this.tail;
        tail--;
        if(tail == -1){
            tail = values.length - 1;
        }
        final int result = values[tail];
        values[tail] = 0;
        this.tail = tail;
        size--;
        return result;
    }

    public int indexOf(int value){
        if(size == 0) return -1;
        int[] values = this.values;
        final int head = this.head, tail = this.tail;
        if(head < tail){
            for(int i = head; i < tail; i++)
                if(values[i] == value) return i - head;
        }else{
            for(int i = head, n = values.length; i < n; i++)
                if(values[i] == value) return i - head;
            for(int i = 0; i < tail; i++)
                if(values[i] == value) return i + values.length - head;
        }
        return -1;
    }

    public boolean removeValue(int value){
        int index = indexOf(value);
        if(index == -1) return false;
        removeIndex(index);
        return true;
    }

    public int removeIndex(int index){
        if(index < 0) throw new IndexOutOfBoundsException("index can't be < 0: " + index);
        if(index >= size) throw new IndexOutOfBoundsException("index can't be >= size: " + index + " >= " + size);

        int[] values = this.values;
        int head = this.head, tail = this.tail;
        index += head;
        int value;
        if(head < tail){ // index is between head and tail.
            value = values[index];
            System.arraycopy(values, index + 1, values, index, tail - index);
            this.tail--;
        }else if(index >= values.length){ // index is between 0 and tail.
            index -= values.length;
            value = values[index];
            System.arraycopy(values, index + 1, values, index, tail - index);
            this.tail--;
        }else{ // index is between head and values.length.
            value = values[index];
            System.arraycopy(values, head, values, head + 1, index - head);
            this.head++;
            if(this.head == values.length){
                this.head = 0;
            }
        }
        size--;
        return value;
    }

    public boolean isEmpty(){
        return size == 0;
    }

    public int first(){
        if(size == 0){
            throw new NoSuchElementException("Queue is empty.");
        }
        return values[head];
    }

    public int last(){
        if(size == 0){
            throw new NoSuchElementException("Queue is empty.");
        }
        final int[] values = this.values;
        int tail = this.tail;
        tail--;
        if(tail == -1){
            tail = values.length - 1;
        }
        return values[tail];
    }

    public int get(int index){
        if(index < 0) throw new IndexOutOfBoundsException("index can't be < 0: " + index);
        if(index >= size) throw new IndexOutOfBoundsException("index can't be >= size: " + index + " >= " + size);
        final int[] values = this.values;

        int i = head + index;
        if(i >= values.length){
            i -= values.length;
        }
        return values[i];
    }

    public void set(int index, int value){
        if(index < 0) throw new IndexOutOfBoundsException("index can't be < 0: " + index);
        if(index >= size) throw new IndexOutOfBoundsException("index can't be >= size: " + index + " >= " + size);
        final int[] values = this.values;

        int i = head + index;
        if(i >= values.length){
            i -= values.length;
        }
        values[i] = value;
    }

    /** Empties the queue. Quirk kept: old values stay in the buffer, as in Arc. */
    public void clear(){
        if(size == 0) return;
        this.head = 0;
        this.tail = 0;
        this.size = 0;
    }

    @Override
    public String toString(){
        var out = new ArrayList<Integer>(size);
        for(int i = 0; i < size; i++) out.add(get(i));
        return out.toString();
    }
}
