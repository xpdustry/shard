package com.xpdustry.shard.sim.entity;

import arc.util.pooling.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link EntityIds}' pool timing against Arc's {@code Pool} from the jar, which vanilla's {@code Groups.queueFree} and
 * {@code updatePooling} drive: a new object draws an id, a free one keeps its id, and every free draws one in
 * {@code reset()}, kept or not. {@code EntityIdOracleTest} checks the same against vanilla's own bullets.
 */
class EntityIdsTest{
    @Test
    void obtainAndFreeDrawIdsLikeArcsPool(){
        for(long seed = 0; seed < 200; seed++){
            var random = new SplittableRandom(seed);
            var arc = new ArcSide();
            var ids = new EntityIds();
            var live = new ArrayList<Integer>();
            var arcLive = new ArrayList<Probe>();
            for(int step = 0; step < 300; step++){
                int roll = random.nextInt(10);
                if(roll < 5){
                    live.add(ids.obtain(Pooled.BULLET));
                    arcLive.add(arc.pool.obtain());
                }else if(roll < 8 && !live.isEmpty()){
                    int victim = random.nextInt(live.size());
                    live.remove(victim);
                    ids.queueFree(Pooled.BULLET);
                    arc.queue.add(arcLive.remove(victim));
                }else if(roll < 9){
                    ids.flushFrees();
                    arc.flush();
                }else{
                    assertEquals(arc.nextId(), ids.next(), "a plain entity, seed " + seed);
                }
                assertEquals(arc.lastId, ids.peek(), "seed " + seed + " step " + step);
            }
            for(int i = 0; i < live.size(); i++) assertEquals(arcLive.get(i).id, live.get(i), "seed " + seed);
        }
    }

    @Test
    void aFullPoolStillDrawsAnIdPerFree(){
        var ids = new EntityIds();
        for(int i = 0; i < EntityIds.POOL_MAX + 3; i++) ids.obtain(Pooled.FIRE);
        for(int i = 0; i < EntityIds.POOL_MAX + 3; i++) ids.queueFree(Pooled.FIRE);
        int before = ids.peek();
        ids.flushFrees();
        assertEquals(before + EntityIds.POOL_MAX + 3, ids.peek());
        assertEquals(EntityIds.POOL_MAX, ids.free(Pooled.FIRE));
        assertEquals(0, ids.queuedFrees());
    }

    @Test
    void poolsAreSeparateButShareOneQueue(){
        var ids = new EntityIds();
        ids.obtain(Pooled.BULLET);
        ids.obtain(Pooled.PUDDLE);
        ids.queueFree(Pooled.PUDDLE);
        ids.queueFree(Pooled.BULLET);
        ids.flushFrees();
        // the puddle was queued first, so it drew id 2 and the bullet id 3
        assertEquals(3, ids.obtain(Pooled.BULLET));
        assertEquals(2, ids.obtain(Pooled.PUDDLE));
        assertEquals(4, ids.obtain(Pooled.PUDDLE), "the puddle pool is empty again");
    }

    @Test
    void copiesAreIndependentAndHashEveryPart(){
        var ids = new EntityIds();
        ids.obtain(Pooled.BULLET);
        ids.obtain(Pooled.BULLET);
        ids.queueFree(Pooled.BULLET);
        ids.flushFrees();
        ids.queueFree(Pooled.FIRE);
        var copy = ids.copy();
        assertEquals(hash(ids), hash(copy));
        copy.flushFrees();
        assertEquals(1, ids.queuedFrees(), "flushing the copy flushed the source");

        var base = hash(ids);
        var counter = ids.copy();
        counter.next();
        var pool = ids.copy();
        pool.obtain(Pooled.BULLET);
        var queue = ids.copy();
        queue.queueFree(Pooled.BULLET);
        assertNotEquals(base, hash(counter), "the counter");
        assertNotEquals(base, hash(pool), "the free ids");
        assertNotEquals(base, hash(queue), "the free queue");
    }

    static long hash(EntityIds ids){
        var hasher = new Hasher();
        ids.hash(hasher);
        return hasher.value();
    }

    /** Arc's pool with vanilla's id rules: the constructor and {@code reset()} each draw the next id. */
    static final class ArcSide{
        int lastId;
        final ArrayList<Probe> queue = new ArrayList<>();
        final Pool<Probe> pool = new Pool<>(16, EntityIds.POOL_MAX){
            @Override
            protected Probe newObject(){
                return new Probe(ArcSide.this);
            }
        };

        int nextId(){
            return lastId++;
        }

        /** {@code Groups.updatePooling}. */
        void flush(){
            for(Probe probe : queue) pool.free(probe);
            queue.clear();
        }
    }

    static final class Probe implements Pool.Poolable{
        final ArcSide side;
        int id;

        Probe(ArcSide side){
            this.side = side;
            id = side.nextId();
        }

        @Override
        public void reset(){
            id = side.nextId();
        }
    }
}
