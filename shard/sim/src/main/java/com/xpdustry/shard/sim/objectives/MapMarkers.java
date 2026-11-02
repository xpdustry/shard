package com.xpdustry.shard.sim.objectives;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;
import java.util.function.*;

/**
 * Vanilla's {@code MapMarkers} ({@code state.markers}): the markers world processors make, by id, plus the three lists
 * a client draws from (world, minimap, light), in which each visible marker's {@code world}, {@code minimap} or
 * {@code light} field is its index.
 *
 * <p>The map is a port of Arc's {@code IntMap} with the same table history, because its iteration order is the order
 * the save and the world stream write markers in, and the order {@link #load} rebuilds the lists in. The lists follow
 * from the markers' index fields (each visible marker sits at its index), so forks and checkpoints rebuild them.
 */
public final class MapMarkers implements Checkpointable{
    /** List kinds: {@code worldMarkers}, {@code mapMarkers}, {@code lightMarkers}. */
    public static final int WORLD = 0, MINIMAP = 1, LIGHT = 2;
    /** {@code MakeMarkerI.maxMarkers}. */
    public static final int MAX_MARKERS = 20_000;
    private static final float LOAD_FACTOR = 0.8f;
    private static final int INITIAL_CAPACITY = 51;

    private int[] keyTable;
    private ObjectiveMarker[] valueTable;
    private ObjectiveMarker zeroValue;
    private boolean hasZeroValue;
    private int size;
    @Derived("the table's length and load factor")
    private int threshold, mask, shift;
    @Derived("each visible marker at its index field (relist)")
    private List<List<ObjectiveMarker>> lists;

    public MapMarkers(){
        table(tableSize(INITIAL_CAPACITY));
        relist();
    }

    // region vanilla's API

    /** {@code clear()}: no markers; the table keeps its size, as Arc's {@code clear} does. */
    public void clear(){
        for(List<ObjectiveMarker> list : lists) list.clear();
        if(size == 0) return;
        size = 0;
        Arrays.fill(keyTable, 0);
        Arrays.fill(valueTable, null);
        zeroValue = null;
        hasZeroValue = false;
    }

    /** {@code add(id, marker)}: puts the marker, taking the place of the one it replaces in each list it showed in. */
    public void add(int id, ObjectiveMarker marker){
        if(marker == null) return;
        ObjectiveMarker prev = put(id, marker);
        for(int kind = WORLD; kind <= LIGHT; kind++) setMarker(kind, marker, prev);
    }

    /** {@code remove(id)}. */
    public void remove(int id){
        ObjectiveMarker prev = removeKey(id);
        if(prev != null){
            for(int kind = WORLD; kind <= LIGHT; kind++) remove(kind, prev.index(kind));
        }
    }

    public ObjectiveMarker get(int id){
        if(id == 0) return hasZeroValue ? zeroValue : null;
        int i = locateKey(id);
        return i >= 0 ? valueTable[i] : null;
    }

    public boolean has(int id){
        return get(id) != null;
    }

    public int size(){
        return size;
    }

    /** {@code updateMarker(markers, marker, visible, getter, setter)}: shows or hides {@code marker} in one list. */
    public void updateMarker(int kind, ObjectiveMarker marker, boolean visible){
        if((marker.index(kind) != -1) == visible) return;
        List<ObjectiveMarker> markers = lists.get(kind);
        if(!visible){
            int index = marker.index(kind);
            markers.getLast().index(kind, index);
            swapRemove(markers, index);
            marker.index(kind, -1);
        }else{
            marker.index(kind, markers.size());
            markers.add(marker);
        }
    }

    /**
     * {@code read(stream)}'s second half: the markers a save or world stream holds, in its order, into a new map; each
     * visible one goes to the end of its lists, in the new map's order.
     */
    public void load(SequencedMap<Integer, ObjectiveMarker> markers){
        table(tableSize(INITIAL_CAPACITY));
        size = 0;
        zeroValue = null;
        hasZeroValue = false;
        for(var entry : markers.entrySet()) put(entry.getKey(), entry.getValue());
        for(List<ObjectiveMarker> list : lists) list.clear();
        each((id, marker) -> {
            for(int kind = WORLD; kind <= LIGHT; kind++){
                if(marker.index(kind) != -1){
                    List<ObjectiveMarker> list = lists.get(kind);
                    list.add(marker);
                    marker.index(kind, list.size() - 1);
                }
            }
        });
    }

    /** The markers in Arc's iteration order: id 0 first, then table order, which is what saves write. */
    public void each(BiConsumer<Integer, ObjectiveMarker> action){
        if(hasZeroValue) action.accept(0, zeroValue);
        for(int i = 0; i < keyTable.length; i++){
            if(keyTable[i] != 0) action.accept(keyTable[i], valueTable[i]);
        }
    }

    /** {@code worldMarkers}, {@code mapMarkers} or {@code lightMarkers}, read-only. */
    public List<ObjectiveMarker> list(int kind){
        return Collections.unmodifiableList(lists.get(kind));
    }

    private void setMarker(int kind, ObjectiveMarker curr, ObjectiveMarker prev){
        List<ObjectiveMarker> markers = lists.get(kind);
        int currIndex = curr.index(kind);
        if(prev != null && prev.index(kind) != -1){
            int prevIndex = prev.index(kind);
            if(currIndex != -1){
                // both visible: the new one takes the old one's place
                curr.index(kind, prevIndex);
                markers.set(prevIndex, curr);
            }else{
                markers.getLast().index(kind, prevIndex);
                swapRemove(markers, prevIndex);
            }
        }else if(currIndex != -1){
            curr.index(kind, markers.size());
            markers.add(curr);
        }
    }

    private void remove(int kind, int index){
        if(index != -1){
            List<ObjectiveMarker> markers = lists.get(kind);
            markers.getLast().index(kind, index);
            swapRemove(markers, index);
        }
    }

    /** An unordered {@code Seq.remove(index)}: the last element takes the removed one's place. */
    private static void swapRemove(List<ObjectiveMarker> list, int index){
        ObjectiveMarker last = list.removeLast();
        if(index < list.size()) list.set(index, last);
    }

    // endregion
    // region Arc's IntMap

    private static int tableSize(int capacity){
        return Mathf.nextPowerOfTwo(Math.max(2, (int)Math.ceil(capacity / LOAD_FACTOR)));
    }

    private void table(int tableSize){
        threshold = (int)(tableSize * LOAD_FACTOR);
        mask = tableSize - 1;
        shift = Long.numberOfLeadingZeros(mask);
        keyTable = new int[tableSize];
        valueTable = new ObjectiveMarker[tableSize];
    }

    private int place(int key){
        return (int)(key * 0x9E3779B97F4A7C15L >>> shift);
    }

    private int locateKey(int key){
        for(int i = place(key); ; i = i + 1 & mask){
            int other = keyTable[i];
            if(other == 0) return -(i + 1);
            if(other == key) return i;
        }
    }

    private ObjectiveMarker put(int key, ObjectiveMarker value){
        if(key == 0){
            ObjectiveMarker old = zeroValue;
            zeroValue = value;
            if(!hasZeroValue){
                hasZeroValue = true;
                size++;
            }
            return old;
        }
        int i = locateKey(key);
        if(i >= 0){
            ObjectiveMarker old = valueTable[i];
            valueTable[i] = value;
            return old;
        }
        i = -(i + 1);
        keyTable[i] = key;
        valueTable[i] = value;
        if(++size >= threshold) resize(keyTable.length << 1);
        return null;
    }

    private void resize(int newSize){
        int[] oldKeys = keyTable;
        ObjectiveMarker[] oldValues = valueTable;
        table(newSize);
        if(size > 0){
            for(int i = 0; i < oldKeys.length; i++){
                int key = oldKeys[i];
                if(key == 0) continue;
                for(int j = place(key); ; j = j + 1 & mask){
                    if(keyTable[j] == 0){
                        keyTable[j] = key;
                        valueTable[j] = oldValues[i];
                        break;
                    }
                }
            }
        }
    }

    private ObjectiveMarker removeKey(int key){
        if(key == 0){
            if(!hasZeroValue) return null;
            hasZeroValue = false;
            ObjectiveMarker old = zeroValue;
            zeroValue = null;
            size--;
            return old;
        }
        int i = locateKey(key);
        if(i < 0) return null;
        ObjectiveMarker old = valueTable[i];
        int next = i + 1 & mask;
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
        valueTable[i] = null;
        size--;
        return old;
    }

    // endregion
    // region world state

    /** Rebuilds the lists from the markers' index fields. */
    private void relist(){
        lists = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        each((id, marker) -> {
            for(int kind = WORLD; kind <= LIGHT; kind++){
                int index = marker.index(kind);
                if(index == -1) continue;
                List<ObjectiveMarker> list = lists.get(kind);
                while(list.size() <= index) list.add(null);
                list.set(index, marker);
            }
        });
    }

    public MapMarkers copy(){
        var out = new MapMarkers();
        out.table(keyTable.length);
        System.arraycopy(keyTable, 0, out.keyTable, 0, keyTable.length);
        for(int i = 0; i < valueTable.length; i++) out.valueTable[i] = valueTable[i] == null ? null : valueTable[i].copy();
        out.zeroValue = zeroValue == null ? null : zeroValue.copy();
        out.hasZeroValue = hasZeroValue;
        out.size = size;
        out.relist();
        return out;
    }

    public void hash(Hasher hasher){
        hasher.i(size).i(keyTable.length).bool(hasZeroValue);
        if(hasZeroValue) zeroValue.hash(hasher);
        for(int i = 0; i < keyTable.length; i++){
            if(keyTable[i] == 0) continue;
            hasher.i(i).i(keyTable[i]);
            valueTable[i].hash(hasher);
        }
    }

    @Override
    public void write(CheckpointOut out){
        out.ints("keyTable", keyTable).list("valueTable", Arrays.asList(valueTable)).obj("zeroValue", zeroValue)
            .bool("hasZeroValue", hasZeroValue).i("size", size);
    }

    @Override
    public void read(CheckpointIn in){
        int[] keys = in.ints("keyTable");
        List<ObjectiveMarker> values = in.list("valueTable", ObjectiveMarker.class);
        table(keys.length);
        System.arraycopy(keys, 0, keyTable, 0, keys.length);
        for(int i = 0; i < values.size(); i++) valueTable[i] = values.get(i);
        zeroValue = in.obj("zeroValue", ObjectiveMarker.class);
        hasZeroValue = in.bool("hasZeroValue");
        size = in.i("size");
        relist();
    }

    // endregion
}
