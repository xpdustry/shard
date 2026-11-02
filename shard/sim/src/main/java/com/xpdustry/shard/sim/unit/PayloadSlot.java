package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.sim.blocks.power.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * What a unit with {@code PayloadComp} carries (vanilla {@code payloads}, an ordered {@code Seq}): the last one is the
 * next to drop. The carried units count toward their team's unit caps ({@code Teams.count}). {@link Payloads} picks up
 * and drops; {@link PayloadComponent} is the update.
 *
 * <p>{@code payloadPower} is the graph without an updater that solves the carried powered buildings each update,
 * made the first time there is one. Vanilla's is transient: a loaded unit starts without one, as one made from a save
 * here does.
 *
 * <p>{@link Persistence#AUTHORITATIVE}, in order.
 */
public final class PayloadSlot implements Checkpointable{
    private Payload[] items;
    private int size;
    /** {@code payloadPower}, or null until the unit carries a powered building. */
    PowerGraph power;

    public PayloadSlot(){
        items = new Payload[2];
    }

    private PayloadSlot(PayloadSlot source, CopyContext context){
        items = new Payload[source.items.length];
        size = source.size;
        for(int i = 0; i < size; i++) items[i] = source.items[i].copy(context);
        power = context.map(source.power);
    }

    public int size(){
        return size;
    }

    public boolean isEmpty(){
        return size == 0;
    }

    public Payload get(int index){
        Objects.checkIndex(index, size);
        return items[index];
    }

    /** {@code payloads.peek()}: the last payload, the next to drop, or null. */
    public Payload peek(){
        return size == 0 ? null : items[size - 1];
    }

    public void add(Payload payload){
        Objects.requireNonNull(payload, "payload");
        if(size == items.length) items = Arrays.copyOf(items, size * 2);
        items[size++] = payload;
    }

    /** Removes and returns the last payload. */
    public Payload pop(){
        if(size == 0) throw new NoSuchElementException("no payload");
        Payload payload = items[--size];
        items[size] = null;
        return payload;
    }

    /** {@code payloads.clear()}. */
    public void clear(){
        Arrays.fill(items, 0, size, null);
        size = 0;
    }

    /** {@code payloads.removeAll(Payload::isDead)}: the dead ones go, the others keep their order. */
    void removeDead(){
        int kept = 0;
        for(int i = 0; i < size; i++){
            if(!items[i].isDead()) items[kept++] = items[i];
        }
        Arrays.fill(items, kept, size, null);
        size = kept;
    }

    PayloadSlot copy(CopyContext context){
        return new PayloadSlot(this, context);
    }

    /** The payloads in order, each referencing its carried entity, and the payload graph; spare capacity isn't state. */
    @Override
    public void write(CheckpointOut out){
        out.list("items", Arrays.asList(items).subList(0, size)).i("size", size).ref("power", power);
    }

    @Override
    public void read(CheckpointIn in){
        List<Payload> stored = in.list("items", Payload.class);
        size = in.i("size");
        if(stored == null || stored.size() != size || stored.contains(null)) throw new CheckpointException("a payload slot is inconsistent");
        items = stored.toArray(new Payload[Math.max(2, size)]);
        power = in.ref("power", PowerGraph.class);
    }

    /** Each payload's kind and whole carried entity, then the payload graph, which only units carrying power have. */
    void hash(Hasher hasher){
        hasher.i(size);
        for(int i = 0; i < size; i++) items[i].hash(hasher);
        if(power != null) power.hashState(hasher);
    }
}
