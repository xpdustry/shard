package com.xpdustry.shard.sim.blocks.distribution;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * The {@code data} of the live {@link MassDriverBolt}s: vanilla's {@code DriverBulletData} (the driver that fired, its
 * target and the items carried), which vanilla keeps on the bullet object. Shard's bullet table has no object column,
 * so a world keeps them here by bullet id, in firing order; a bolt's entry leaves with it ({@code removed}). A bolt
 * without one is vanilla's bolt without data, which hits at once.
 *
 * <p>Vanilla reads {@code b.data()} directly, so a lookup costs nothing there; here every bolt looks its payload up
 * each update. Lookups go through an index by bullet id, and a removed payload leaves a hole that is compacted once
 * holes are half the list, so lookup and removal are constant time (amortized) and N bolts in flight cost O(N) a
 * frame. Holes and the index are layout, not state: the hash, checkpoints and forks see only the live payloads, in
 * firing order.
 *
 * <p>Vanilla obtains its {@code DriverBulletData} from a pool it never frees to, so every shot has a fresh one; there
 * is no pool to model.
 */
@Register
public final class DriverBolts implements WorldPart, Checkpointable{
    /** The payloads in firing order, with null holes where bolts left. */
    private ArrayList<Payload> payloads = new ArrayList<>();
    @Derived("how many holes payloads has, rebuilt with it")
    private int holes;
    @Derived("each live payload's position in payloads by bullet id, rebuilt on read and copy")
    private IntIntMap slots = new IntIntMap();
    @Derived("a work counter for cost tests: payload entries visited")
    private long visits;

    /** One bolt's {@code DriverBulletData}. */
    public static final class Payload implements Checkpointable{
        public int bullet;
        public MassDriver from, to;
        /** Per item id, how many the bolt still carries. */
        public int[] items;

        Payload copy(CopyContext context){
            var out = new Payload();
            out.bullet = bullet;
            out.from = context.map(from);
            out.to = context.map(to);
            out.items = items.clone();
            return out;
        }

        void hash(Hasher hasher){
            hasher.i(bullet).i(from.id()).i(to.id()).ints(items);
        }

        @Override
        public void write(CheckpointOut out){
            out.i("bullet", bullet).ref("from", from).ref("to", to).ints("items", items);
        }

        @Override
        public void read(CheckpointIn in){
            bullet = in.i("bullet");
            from = in.ref("from", MassDriver.class);
            to = in.ref("to", MassDriver.class);
            items = in.ints("items");
        }
    }

    /** The world's bolts. */
    public static DriverBolts of(World world){
        DriverBolts bolts = world.find(DriverBolts.class);
        if(bolts == null) throw new IllegalStateException("this world has no DriverBolts part");
        return bolts;
    }

    /** {@code bullet.data = data}: what {@code bullet} carries from {@code from} to {@code to}. */
    public void attach(Bullet bullet, MassDriver from, MassDriver to, int[] items){
        var payload = new Payload();
        payload.bullet = bullet.id();
        payload.from = from;
        payload.to = to;
        payload.items = items;
        slots.put(payload.bullet, payloads.size());
        payloads.add(payload);
    }

    /** The data of {@code bullet}, or null. */
    public Payload get(Bullet bullet){
        visits++;
        int slot = slots.get(bullet.id(), -1);
        return slot == -1 ? null : payloads.get(slot);
    }

    /** Forgets {@code bullet}'s data once it is removed. */
    public void detach(Bullet bullet){
        visits++;
        int slot = slots.remove(bullet.id(), -1);
        if(slot == -1) return;
        payloads.set(slot, null);
        if(++holes * 2 > payloads.size()) compact();
    }

    /** Drops the holes, keeping firing order, and re-indexes: amortized over the removals that made them. */
    private void compact(){
        int at = 0;
        for(int i = 0; i < payloads.size(); i++){
            Payload payload = payloads.get(i);
            visits++;
            if(payload == null) continue;
            payloads.set(at, payload);
            slots.put(payload.bullet, at++);
        }
        payloads.subList(at, payloads.size()).clear();
        holes = 0;
    }

    /** The live payloads in firing order, as a new list: observing never compacts (ARCHITECTURE §4). */
    private List<Payload> live(){
        var out = new ArrayList<Payload>(size());
        for(Payload payload : payloads) if(payload != null) out.add(payload);
        return out;
    }

    /** How many bolts carry data, for tests. */
    public int size(){
        return payloads.size() - holes;
    }

    /** Payload entries visited by lookups, removals and compaction so far, for cost tests. */
    public long visits(){
        return visits;
    }

    @Override
    public WorldPart copy(CopyContext context){
        var out = new DriverBolts();
        for(Payload payload : payloads) if(payload != null) out.add(payload.copy(context));
        return out;
    }

    private void add(Payload payload){
        slots.put(payload.bullet, payloads.size());
        payloads.add(payload);
    }

    @Override
    public void hash(Hasher hasher){
        hasher.i(size());
        for(Payload payload : payloads) if(payload != null) payload.hash(hasher);
    }

    @Override
    public void write(CheckpointOut out){
        out.list("payloads", live());
    }

    @Override
    public void read(CheckpointIn in){
        payloads = new ArrayList<>();
        holes = 0;
        slots = new IntIntMap();
        for(Payload payload : in.list("payloads", Payload.class)) add(payload);
    }
}
