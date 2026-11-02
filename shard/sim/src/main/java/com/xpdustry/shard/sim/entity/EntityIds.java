package com.xpdustry.shard.sim.entity;

import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's entity id counter ({@code EntityGroup.nextId}/{@code checkNextId}), one per world instead of one static,
 * with the pool timing that spends it. Vanilla draws an id for every entity it constructs (buildings, units, bullets,
 * fires, puddles), so unit wire ids match the client's only if Shard draws one wherever vanilla does.
 *
 * <p>Pooled entities ({@link Pooled}) draw ids at two more points, and this class replays both:
 * <ul>
 *   <li>{@link #obtain(Pooled)} is Arc's {@code Pool.obtain}: a free object keeps the id it already has; only a new
 *   object ({@code new Bullet()}) draws one.</li>
 *   <li>{@link #queueFree(Pooled)} is the end of a pooled {@code remove()} ({@code Groups.queueFree}), and
 *   {@link #flushFrees()} is {@code Groups.updatePooling} at the start of the entity update: Arc's {@code Pool.free}
 *   keeps the object if the pool has fewer than {@link #POOL_MAX} free ones, then <em>always</em> calls
 *   {@code reset()}, which draws a fresh id. The free queue is shared by every pooled kind, as vanilla's is.</li>
 * </ul>
 *
 * <p>{@link Persistence#AUTHORITATIVE}: the counter, the free ids of each pool in order, and the free queue.
 */
public final class EntityIds{
    /** Arc's {@code Pools.get} default: a pool keeps at most this many free objects. */
    public static final int POOL_MAX = 5000;

    private int lastId;
    /** Per {@link Pooled}: the ids of the free objects, in Arc's {@code Seq} order ({@code pop} takes the last). */
    private final IntSeq[] free;
    /** {@code Groups.freeQueue}: one {@link Pooled} ordinal per queued free, in remove order. */
    private final IntSeq freeQueue;

    public EntityIds(){
        free = new IntSeq[Pooled.values().length];
        for(int i = 0; i < free.length; i++) free[i] = new IntSeq();
        freeQueue = new IntSeq();
    }

    private EntityIds(EntityIds source){
        lastId = source.lastId;
        free = new IntSeq[source.free.length];
        for(int i = 0; i < free.length; i++) free[i] = source.free[i].copy();
        freeQueue = source.freeQueue.copy();
    }

    /** The next id, wrapping to 0 near {@code Integer.MAX_VALUE} as vanilla does. */
    public int next(){
        if(lastId >= Integer.MAX_VALUE - 2) lastId = 0;
        return lastId++;
    }

    /** Makes sure later ids are above {@code id}, for ids read from a save. */
    public void checkNextId(int id){
        lastId = Math.max(lastId, id + 1);
    }

    /** The id {@link #next()} would return, before wrapping. */
    public int peek(){
        return lastId;
    }

    /** The id of a pooled entity being created: a free object's (the last freed first), else a new one. */
    public int obtain(Pooled pool){
        IntSeq ids = free[pool.ordinal()];
        return ids.isEmpty() ? next() : ids.pop();
    }

    /** Records that a pooled entity was removed; {@link #flushFrees()} frees it. */
    public void queueFree(Pooled pool){
        freeQueue.add(pool.ordinal());
    }

    /** Frees every queued entity in queue order: each draws a new id, which its pool keeps if it has room. */
    public void flushFrees(){
        for(int i = 0; i < freeQueue.size; i++){
            IntSeq ids = free[freeQueue.items[i]];
            // Pool.free adds the object, then reset() gives it this id
            int id = next();
            if(ids.size < POOL_MAX) ids.add(id);
        }
        freeQueue.clear();
    }

    /** How many free objects {@code pool} holds. */
    public int free(Pooled pool){
        return free[pool.ordinal()].size;
    }

    /** How many frees wait for {@link #flushFrees()}. */
    public int queuedFrees(){
        return freeQueue.size;
    }

    public EntityIds copy(){
        return new EntityIds(this);
    }

    /** The counter, each pool's free ids as its size then its ids, and the queue; arrays' spare capacity isn't state. */
    public void write(CheckpointOut out){
        var pools = new IntSeq();
        for(IntSeq ids : free){
            pools.add(ids.size);
            pools.addAll(ids.items, 0, ids.size);
        }
        out.i("lastId", lastId).ints("free", pools.items, pools.size).ints("freeQueue", freeQueue.items, freeQueue.size);
    }

    /** Reads into this counter, which must be fresh: a restored world's. */
    public void read(CheckpointIn in){
        lastId = in.i("lastId");
        int[] pools = in.ints("free");
        int at = 0;
        for(IntSeq ids : free){
            if(pools == null || at >= pools.length || pools[at] < 0 || pools[at] > pools.length - at - 1) throw new CheckpointException("the id pools are corrupt");
            int size = pools[at++];
            ids.clear();
            ids.addAll(pools, at, size);
            at += size;
        }
        if(at != pools.length) throw new CheckpointException("the id pools have " + (pools.length - at) + " extra values");
        int[] queue = in.ints("freeQueue");
        freeQueue.clear();
        for(int pool : queue){
            if(pool < 0 || pool >= free.length) throw new CheckpointException("the free queue names pool " + pool);
            freeQueue.add(pool);
        }
    }

    public void hash(Hasher hasher){
        hasher.i(lastId);
        for(IntSeq ids : free) hasher.ints(ids.items, 0, ids.size);
        hasher.ints(freeQueue.items, 0, freeQueue.size);
    }
}
