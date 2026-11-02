package com.xpdustry.shard.common.struct;

import com.xpdustry.shard.common.math.*;
import org.junit.jupiter.api.*;

import java.lang.reflect.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The collections against Arc's, op by op: the same random operations must give the same results and the same
 * internal layout (array contents and capacity, table slots), which is what makes iteration order match vanilla's.
 */
class StructTest{
    static final int RUNS = 300, OPS = 400;

    @Test
    void intSeqMatchesArc(){
        var random = new SplittableRandom(20);
        for(int run = 0; run < RUNS; run++){
            boolean ordered = random.nextBoolean();
            int capacity = random.nextInt(0, 20);
            var ours = new IntSeq(ordered, capacity);
            var theirs = new arc.struct.IntSeq(ordered, capacity);
            for(int op = 0; op < OPS; op++){
                int v = random.nextInt(-50, 50), w = random.nextInt(-50, 50), size = theirs.size;
                int at = size == 0 ? 0 : random.nextInt(size);
                String what = "run " + run + " op " + op;
                switch(random.nextInt(18)){
                    case 0, 1, 2 -> { ours.add(v); theirs.add(v); }
                    case 3 -> { ours.add(v, w); theirs.add(v, w); }
                    case 4 -> { ours.add(v, w, v + w); theirs.add(v, w, v + w); }
                    case 5 -> { int[] all = random.ints(random.nextInt(0, 30), -50, 50).toArray(); ours.addAll(all); theirs.addAll(all); }
                    case 6 -> { int i = random.nextInt(size + 1); ours.insert(i, v); theirs.insert(i, v); }
                    case 7 -> { if(size > 0) assertEquals(theirs.removeIndex(at), ours.removeIndex(at), what); }
                    case 8 -> assertEquals(theirs.removeValue(v), ours.removeValue(v), what);
                    case 9 -> { if(size > 0){ int end = at + random.nextInt(size - at); ours.removeRange(at, end); theirs.removeRange(at, end); } }
                    case 10 -> { if(size > 0) assertEquals(theirs.pop(), ours.pop(), what); }
                    case 11 -> { if(size > 1){ int j = random.nextInt(size); ours.swap(at, j); theirs.swap(at, j); } }
                    case 12 -> { if(size > 0){ ours.set(at, v); theirs.set(at, v); ours.incr(at, w); theirs.incr(at, w); } }
                    case 13 -> { ours.sort(); theirs.sort(); }
                    case 14 -> { ours.reverse(); theirs.reverse(); }
                    case 15 -> { long seed = random.nextLong(); ours.shuffle(new Rand(seed)); theirs.shuffle(new arc.math.Rand(seed)); }
                    case 16 -> { int n = random.nextInt(0, 40); ours.ensureCapacity(n); theirs.ensureCapacity(n); }
                    default -> { int n = random.nextInt(0, size + 5); ours.truncate(n); theirs.truncate(n); }
                }
                assertEquals(theirs.size, ours.size, what);
                assertEquals(theirs.items.length, ours.items.length, what + " capacity");
                assertArrayEquals(theirs.toArray(), ours.toArray(), what);
                assertEquals(theirs.contains(v), ours.contains(v));
                assertEquals(theirs.indexOf(v), ours.indexOf(v));
                assertEquals(theirs.lastIndexOf(v), ours.lastIndexOf(v));
                assertEquals(theirs.sum(), ours.sum());
                assertEquals(theirs.count(v), ours.count(v));
            }
            assertArrayEquals(ours.toArray(), ours.copy().toArray());
        }
    }

    @Test
    void intQueueMatchesArc() throws ReflectiveOperationException{
        Field head = arc.struct.IntQueue.class.getDeclaredField("head"), tail = arc.struct.IntQueue.class.getDeclaredField("tail");
        Field ourHead = IntQueue.class.getDeclaredField("head"), ourTail = IntQueue.class.getDeclaredField("tail");
        for(Field field : List.of(head, tail, ourHead, ourTail)) field.setAccessible(true);
        var random = new SplittableRandom(21);
        for(int run = 0; run < RUNS; run++){
            int capacity = random.nextInt(1, 20);
            var ours = new IntQueue(capacity);
            var theirs = new arc.struct.IntQueue(capacity);
            for(int op = 0; op < OPS; op++){
                int v = random.nextInt(-50, 50), size = theirs.size;
                String what = "run " + run + " op " + op;
                switch(random.nextInt(10)){
                    case 0, 1, 2 -> { ours.addLast(v); theirs.addLast(v); }
                    case 3, 4 -> { ours.addFirst(v); theirs.addFirst(v); }
                    case 5 -> { if(size > 0) assertEquals(theirs.removeFirst(), ours.removeFirst(), what); }
                    case 6 -> { if(size > 0) assertEquals(theirs.removeLast(), ours.removeLast(), what); }
                    case 7 -> { if(size > 0){ int i = random.nextInt(size); assertEquals(theirs.removeIndex(i), ours.removeIndex(i), what); } }
                    case 8 -> assertEquals(theirs.removeValue(v), ours.removeValue(v), what);
                    default -> { if(random.nextInt(10) == 0){ ours.clear(); theirs.clear(); }else{ int n = random.nextInt(0, 40); ours.ensureCapacity(n); theirs.ensureCapacity(n); } }
                }
                assertEquals(theirs.size, ours.size, what);
                assertArrayEquals(theirs.values, ours.values, what + " buffer");
                assertEquals(head.getInt(theirs), ourHead.getInt(ours), what + " head");
                assertEquals(tail.getInt(theirs), ourTail.getInt(ours), what + " tail");
                assertEquals(theirs.indexOf(v), ours.indexOf(v), what);
                for(int i = 0; i < theirs.size; i++) assertEquals(theirs.get(i), ours.get(i), what);
                if(theirs.size > 0){
                    assertEquals(theirs.first(), ours.first());
                    assertEquals(theirs.last(), ours.last());
                }
            }
            var copy = ours.copy();
            for(int i = 0; i < ours.size; i++) assertEquals(ours.get(i), copy.get(i));
        }
    }

    @Test
    void intSetMatchesArcIncludingTableLayout() throws ReflectiveOperationException{
        Field keyTable = arc.struct.IntSet.class.getDeclaredField("keyTable");
        keyTable.setAccessible(true);
        var random = new SplittableRandom(22);
        for(int run = 0; run < RUNS; run++){
            int capacity = random.nextInt(0, 60);
            var ours = new IntSet(capacity);
            var theirs = new arc.struct.IntSet(capacity);
            int range = random.nextBoolean() ? 100 : 100_000;
            for(int op = 0; op < OPS; op++){
                int v = random.nextInt(-range, range);
                String what = "run " + run + " op " + op;
                switch(random.nextInt(10)){
                    case 0, 1, 2, 3, 4 -> assertEquals(theirs.add(v), ours.add(v), what);
                    case 5, 6 -> assertEquals(theirs.remove(v), ours.remove(v), what);
                    case 7 -> { int n = random.nextInt(0, 100); ours.ensureCapacity(n); theirs.ensureCapacity(n); }
                    case 8 -> { int n = random.nextInt(0, 100); ours.shrink(n); theirs.shrink(n); }
                    default -> { if(random.nextInt(8) == 0){ int n = random.nextInt(0, 100); ours.clear(n); theirs.clear(n); } }
                }
                assertEquals(theirs.size, ours.size, what);
                assertArrayEquals((int[])keyTable.get(theirs), ours.keyTable, what + " table");
                assertEquals(theirs.contains(v), ours.contains(v));
                assertEquals(order(theirs), box(ours.toSeq()), what + " order");
            }
            assertArrayEquals(ours.toSeq().toArray(), ours.copy().toSeq().toArray());
        }
    }

    @Test
    void intIntMapMatchesArcIncludingTableLayout() throws ReflectiveOperationException{
        Field keyTable = arc.struct.IntIntMap.class.getDeclaredField("keyTable");
        Field valueTable = arc.struct.IntIntMap.class.getDeclaredField("valueTable");
        keyTable.setAccessible(true);
        valueTable.setAccessible(true);
        var random = new SplittableRandom(23);
        for(int run = 0; run < RUNS; run++){
            int capacity = random.nextInt(0, 60);
            var ours = new IntIntMap(capacity);
            var theirs = new arc.struct.IntIntMap(capacity);
            int range = random.nextBoolean() ? 100 : 100_000;
            for(int op = 0; op < OPS; op++){
                int k = random.nextInt(-range, range), v = random.nextInt(-9, 9), d = random.nextInt(-3, 3);
                String what = "run " + run + " op " + op;
                switch(random.nextInt(11)){
                    case 0, 1, 2 -> { ours.put(k, v); theirs.put(k, v); }
                    case 3 -> assertEquals(theirs.put(k, v, d), ours.put(k, v, d), what);
                    case 4 -> assertEquals(theirs.increment(k, d, v), ours.increment(k, d, v), what);
                    case 5 -> assertEquals(theirs.putMissing(k, v, d), ours.putMissing(k, v, d), what);
                    case 6, 7 -> assertEquals(theirs.remove(k, d), ours.remove(k, d), what);
                    case 8 -> { int n = random.nextInt(0, 100); ours.ensureCapacity(n); theirs.ensureCapacity(n); }
                    case 9 -> { int n = random.nextInt(0, 100); ours.shrink(n); theirs.shrink(n); }
                    default -> { if(random.nextInt(8) == 0){ int n = random.nextInt(0, 100); ours.clear(n); theirs.clear(n); } }
                }
                assertEquals(theirs.size, ours.size, what);
                assertArrayEquals((int[])keyTable.get(theirs), ours.keyTable, what + " keys");
                assertArrayEquals((int[])valueTable.get(theirs), ours.valueTable, what + " values");
                assertEquals(theirs.get(k, d), ours.get(k, d));
                assertEquals(theirs.containsKey(k), ours.containsKey(k));
                assertEquals(theirs.containsValue(v), ours.containsValue(v));
                assertEquals(theirs.findKey(v, d), ours.findKey(v, d));
                var arcOrder = new ArrayList<Integer>();
                for(var entry : theirs) arcOrder.add(entry.key);
                assertEquals(arcOrder, box(ours.keys()), what + " order");
            }
        }
    }

    @Test
    void bitsMatchArcIncludingWords() throws ReflectiveOperationException{
        Field words = arc.struct.Bits.class.getDeclaredField("bits");
        words.setAccessible(true);
        var random = new SplittableRandom(24);
        for(int run = 0; run < RUNS; run++){
            int capacity = random.nextInt(0, 200);
            var ours = new Bits(capacity);
            var theirs = new arc.struct.Bits(capacity);
            var otherOurs = new Bits();
            var otherTheirs = new arc.struct.Bits();
            for(int i = 0; i < 30; i++){
                int bit = random.nextInt(0, 400);
                otherOurs.set(bit);
                otherTheirs.set(bit);
            }
            for(int op = 0; op < OPS; op++){
                int i = random.nextInt(0, 400), j = i + random.nextInt(0, 200);
                String what = "run " + run + " op " + op;
                switch(random.nextInt(14)){
                    case 0, 1, 2 -> { ours.set(i); theirs.set(i); }
                    case 3 -> { ours.clear(i); theirs.clear(i); }
                    case 4 -> { ours.flip(i); theirs.flip(i); }
                    case 5 -> assertEquals(theirs.getAndSet(i), ours.getAndSet(i), what);
                    case 6 -> assertEquals(theirs.getAndClear(i), ours.getAndClear(i), what);
                    case 7 -> { ours.set(i, j); theirs.set(i, j); }
                    case 8 -> { ours.and(otherOurs); theirs.and(otherTheirs); }
                    case 9 -> { ours.or(otherOurs); theirs.or(otherTheirs); }
                    case 10 -> { ours.xor(otherOurs); theirs.xor(otherTheirs); }
                    case 11 -> { ours.andNot(otherOurs); theirs.andNot(otherTheirs); }
                    case 12 -> { ours.set(i, random.nextBoolean()); theirs.set(i, ours.get(i)); }
                    default -> { if(random.nextInt(10) == 0){ ours.clear(); theirs.clear(); } }
                }
                assertArrayEquals((long[])words.get(theirs), ours.bits, what + " words");
                assertEquals(theirs.get(i), ours.get(i), what);
                assertEquals(theirs.length(), ours.length(), what);
                assertEquals(theirs.numBits(), ours.numBits(), what);
                assertEquals(theirs.isEmpty(), ours.isEmpty(), what);
                assertEquals(theirs.nextSetBit(i), ours.nextSetBit(i), what);
                assertEquals(theirs.nextClearBit(i), ours.nextClearBit(i), what);
                assertEquals(theirs.intersects(otherTheirs), ours.intersects(otherOurs), what);
                assertEquals(theirs.containsAll(otherTheirs), ours.containsAll(otherOurs), what);
                assertEquals(theirs.equals(otherTheirs), ours.equals(otherOurs), what);
            }
            assertEquals(ours, ours.copy());
        }
    }

    static List<Integer> order(arc.struct.IntSet set){
        var out = new ArrayList<Integer>();
        var iterator = set.iterator();
        while(iterator.hasNext) out.add(iterator.next());
        return out;
    }

    static List<Integer> box(IntSeq seq){
        var out = new ArrayList<Integer>();
        for(int i = 0; i < seq.size; i++) out.add(seq.get(i));
        return out;
    }
}
