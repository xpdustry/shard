package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * A builder's plan queue (vanilla {@code BuilderComp.plans}, an Arc {@code Queue}): the first plan is the one being
 * built. Synced as a whole ({@code TypeIO.writePlansQueueNet}), so order is state.
 *
 * <p>{@link Persistence#AUTHORITATIVE}, in order.
 */
public final class PlanQueue implements Checkpointable{
    private BuildPlan[] items;
    private int size;

    public PlanQueue(){
        items = new BuildPlan[1];
    }

    private PlanQueue(PlanQueue source){
        items = new BuildPlan[source.items.length];
        size = source.size;
        for(int i = 0; i < size; i++) items[i] = source.items[i].copy();
    }

    public int size(){
        return size;
    }

    public boolean isEmpty(){
        return size == 0;
    }

    public BuildPlan get(int index){
        Objects.checkIndex(index, size);
        return items[index];
    }

    /** The plan being built. */
    public BuildPlan first(){
        return get(0);
    }

    public void addLast(BuildPlan plan){
        insert(size, plan);
    }

    public void addFirst(BuildPlan plan){
        insert(0, plan);
    }

    public BuildPlan removeFirst(){
        return remove(0);
    }

    public BuildPlan remove(int index){
        Objects.checkIndex(index, size);
        BuildPlan plan = items[index];
        System.arraycopy(items, index + 1, items, index, size - index - 1);
        items[--size] = null;
        return plan;
    }

    public void clear(){
        Arrays.fill(items, 0, size, null);
        size = 0;
    }

    private void insert(int index, BuildPlan plan){
        Objects.requireNonNull(plan, "plan");
        if(size == items.length) items = Arrays.copyOf(items, Math.max(8, size * 7 / 4));
        System.arraycopy(items, index, items, index + 1, size - index);
        items[index] = plan;
        size++;
    }

    PlanQueue copy(){
        return new PlanQueue(this);
    }

    /** The plans in order; the array's spare capacity isn't state. */
    @Override
    public void write(CheckpointOut out){
        out.list("items", Arrays.asList(items).subList(0, size)).i("size", size);
    }

    @Override
    public void read(CheckpointIn in){
        List<BuildPlan> stored = in.list("items", BuildPlan.class);
        size = in.i("size");
        if(stored == null || stored.size() != size || stored.contains(null)) throw new CheckpointException("a plan queue is inconsistent");
        items = stored.toArray(new BuildPlan[Math.max(1, size)]);
    }

    void hash(Hasher hasher){
        hasher.i(size);
        for(int i = 0; i < size; i++) items[i].hash(hasher);
    }
}
