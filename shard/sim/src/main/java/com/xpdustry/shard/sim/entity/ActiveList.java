package com.xpdustry.shard.sim.entity;

import com.xpdustry.shard.sim.world.*;

import java.util.*;
import java.util.function.*;

/**
 * One of vanilla's ordered groups ({@code Groups.build}, {@code Groups.unit}, {@code Groups.all}…): a port of
 * {@code EntityGroup}'s list semantics, which decide iteration order and so gameplay (ARCHITECTURE.md §3).
 * <ul>
 *   <li>An unordered array: removal moves the last entity into the hole.</li>
 *   <li>Each entity remembers its position here through a {@link Slot}, as vanilla's generated {@code index__build}
 *   fields do; a wrong position falls back to a search.</li>
 *   <li>Iteration uses a cursor field. Removing at or before the cursor steps it back, so the entity swapped into
 *   the hole is visited; entities added during iteration are visited in the same loop.</li>
 * </ul>
 * The order is {@link Persistence#AUTHORITATIVE} and part of every checkpoint.
 *
 * @param <T> the entity type
 */
public final class ActiveList<T extends Entity>{
    private final Slot<T> slot;
    private Entity[] items;
    private int size;
    private int index;
    private boolean clearing;

    public ActiveList(Slot<T> slot){
        this.slot = slot;
        this.items = new Entity[32];
    }

    private ActiveList(ActiveList<T> source, CopyContext context){
        this.slot = source.slot;
        this.items = new Entity[source.items.length];
        this.size = source.size;
        this.index = source.index;
        for(int i = 0; i < size; i++) items[i] = context.map(source.items[i]);
    }

    /** A copy holding the copies of the same entities, in the same order. */
    public ActiveList<T> copy(CopyContext context){
        return new ActiveList<>(this, context);
    }

    public int size(){
        return size;
    }

    public boolean isEmpty(){
        return size == 0;
    }

    /** The entity at position {@code i}. */
    @SuppressWarnings("unchecked")
    public T get(int i){
        Objects.checkIndex(i, size);
        return (T)items[i];
    }

    /** Vanilla's {@code update()}: calls {@code action} on each entity, in order, with the cursor semantics above. */
    @SuppressWarnings("unchecked")
    public void each(Consumer<? super T> action){
        for(index = 0; index < size; index++){
            action.accept((T)items[index]);
        }
    }

    /** Vanilla's {@code update(filter)}. */
    @SuppressWarnings("unchecked")
    public void each(Predicate<? super T> filter, Consumer<? super T> action){
        for(index = 0; index < size; index++){
            T item = (T)items[index];
            if(filter.test(item)) action.accept((T)items[index]);
        }
    }

    /** Vanilla's {@code addIndex}: appends and records the position in the entity's slot. */
    public void add(T entity){
        Objects.requireNonNull(entity, "entity");
        if(size == items.length) items = Arrays.copyOf(items, Math.max(8, (int)(size * 1.75f)));
        slot.set(entity, size);
        items[size++] = entity;
    }

    /**
     * Vanilla's {@code removeIndex}: removes the entity at its recorded position, moving the last entity into it, and
     * clears the slot. Removing an entity that isn't here does nothing.
     */
    @SuppressWarnings("unchecked")
    public void remove(T entity){
        if(clearing) return;
        Objects.requireNonNull(entity, "entity");
        int position = slot.get(entity);
        if(position != -1 && position < size){
            if(items[position] != entity){
                removeBySearch(entity);
                slot.set(entity, -1);
                return;
            }
            if(size > 1){
                T head = (T)items[size - 1];
                slot.set(head, position);
                items[position] = head;
            }
            size--;
            items[size] = null;
            if(index >= position) index--;
        }
        slot.set(entity, -1);
    }

    /** Vanilla's {@code remove(T)}: the slow path, a search by identity. */
    @SuppressWarnings("unchecked")
    private void removeBySearch(T entity){
        int idx = -1;
        for(int i = 0; i < size; i++){
            if(items[i] == entity){
                idx = i;
                break;
            }
        }
        if(idx == -1) return;
        size--;
        items[idx] = items[size];
        items[size] = null;
        if(size > 0 && idx != size) slot.set((T)items[idx], idx);
        if(index >= idx) index--;
    }

    /** Vanilla's {@code clear()}: calls {@code remove} on each entity (removals from this list are ignored), then empties it. */
    @SuppressWarnings("unchecked")
    public void clear(Consumer<? super T> remove){
        clearing = true;
        try{
            for(int i = 0; i < size; i++) remove.accept((T)items[i]);
            for(int i = 0; i < size; i++) slot.set((T)items[i], -1);
            Arrays.fill(items, 0, size, null);
            size = 0;
        }finally{
            clearing = false;
        }
    }

    /**
     * Replaces the contents with {@code entities}, in order, setting their slots: a restore. The cursor is only read
     * during {@link #each}, which resets it, so it restarts at the end.
     */
    public void restore(List<? extends T> entities){
        for(int i = 0; i < size; i++) items[i] = null;
        if(items.length < entities.size()) items = new Entity[Math.max(32, entities.size())];
        size = 0;
        for(T entity : entities){
            slot.set(entity, size);
            items[size++] = Objects.requireNonNull(entity, "entity");
        }
        index = size;
    }

    /** The entities, in order. */
    @SuppressWarnings("unchecked")
    public List<T> toList(){
        var out = new ArrayList<T>(size);
        for(int i = 0; i < size; i++) out.add((T)items[i]);
        return out;
    }

    /**
     * Where an entity keeps its position in one list. An entity in several lists (a unit is in {@code unit} and
     * {@code sync}) has one field per list.
     */
    public interface Slot<T>{
        int get(T entity);

        void set(T entity, int position);
    }
}
