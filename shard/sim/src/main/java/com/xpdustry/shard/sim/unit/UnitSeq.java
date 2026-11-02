package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * An ordered list of units, as vanilla's {@code Seq<Unit>} in team data ({@code TeamData.units},
 * {@code unitsByType}). It may hold units removed since it was filled; readers check {@code isValid()}.
 */
public final class UnitSeq implements Checkpointable{
    private Unit[] items;
    private int size;

    public UnitSeq(){
        items = new Unit[8];
    }

    private UnitSeq(UnitSeq source, CopyContext context){
        items = new Unit[source.items.length];
        size = source.size;
        for(int i = 0; i < size; i++) items[i] = context.map(source.items[i]);
    }

    public int size(){
        return size;
    }

    public boolean isEmpty(){
        return size == 0;
    }

    public Unit get(int index){
        Objects.checkIndex(index, size);
        return items[index];
    }

    public void add(Unit unit){
        Objects.requireNonNull(unit, "unit");
        if(size == items.length) items = Arrays.copyOf(items, size * 7 / 4 + 1);
        items[size++] = unit;
    }

    public void clear(){
        Arrays.fill(items, 0, size, null);
        size = 0;
    }

    public UnitSeq copy(CopyContext context){
        return new UnitSeq(this, context);
    }

    /** The units in order, by reference; the array's spare capacity isn't state. */
    @Override
    public void write(CheckpointOut out){
        out.refs("items", items, size).i("size", size);
    }

    @Override
    public void read(CheckpointIn in){
        Unit[] stored = in.refs("items", Unit.class);
        size = in.i("size");
        if(stored == null || stored.length != size) throw new CheckpointException("a unit list is inconsistent");
        for(Unit unit : stored) if(unit == null) throw new CheckpointException("a unit list holds a null");
        items = Arrays.copyOf(stored, Math.max(8, size));
    }

    /** Feeds the units' ids, in order. */
    public void hash(Hasher hasher){
        hasher.i(size);
        for(int i = 0; i < size; i++) hasher.i(items[i].id());
    }
}
