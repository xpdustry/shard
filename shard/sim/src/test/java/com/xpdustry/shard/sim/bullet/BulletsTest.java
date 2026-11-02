package com.xpdustry.shard.sim.bullet;

import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.toy.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The bullet table keeps {@code EntityGroup}'s order rules: the same script of removals and frag spawns during a pass
 * visits rows exactly as an {@link ActiveList} (which {@code ActiveListTest} checks against vanilla) visits entities.
 */
class BulletsTest{
    @Test
    void rowsVisitLikeAnActiveList(){
        for(long seed = 0; seed < 300; seed++){
            assertEquals(list(seed), table(seed), "seed " + seed);
        }
    }

    @Test
    void copiesAreDeepAndOwnersRemapped(){
        World world = Worlds.quiet(1);
        var bullets = world.bullets();
        for(int i = 0; i < 100; i++) bullets.add(world.ids(), i % 3, 1, i, -i, 1, 1, 30, 5, null);
        World fork = world.fork();
        var copy = fork.bullets();
        copy.x[0] = 99;
        copy.remove(5);
        assertEquals(0f, bullets.x[0]);
        assertEquals(100, bullets.size());
        assertEquals(99, copy.size());
        assertEquals(99, copy.id[5], "the last row moved into the hole");
        assertEquals(0, world.ids().queuedFrees(), "the fork freed into its own ids");
        assertEquals(1, fork.ids().queuedFrees());
    }

    @Test
    void idsFollowVanillasBulletPool(){
        var ids = new EntityIds();
        var bullets = new Bullets(ids);
        int a = bullets.add(ids, 0, 1, 0, 0, 0, 0, 10, 1, null), b = bullets.add(ids, 0, 1, 0, 0, 0, 0, 10, 1, null);
        assertEquals(0, bullets.id[a]);
        assertEquals(1, bullets.id[b]);
        bullets.remove(a);
        bullets.remove(0);
        assertEquals(2, ids.queuedFrees());
        assertEquals(2, ids.peek(), "removing draws nothing yet");
        ids.flushFrees();
        assertEquals(4, ids.peek(), "each free reset draws an id");
        int c = bullets.add(ids, 0, 1, 0, 0, 0, 0, 10, 1, null);
        assertEquals(3, bullets.id[c], "the last freed bullet comes back with its reset id");
        assertEquals(4, ids.peek());
    }

    static List<Integer> table(long seed){
        var random = new SplittableRandom(seed);
        var ids = new EntityIds();
        var bullets = new Bullets(ids);
        var trace = new ArrayList<Integer>();
        for(int round = 0; round < 30; round++){
            for(int i = random.nextInt(0, 10); i > 0; i--) bullets.add(ids, 0, 0, 0, 0, 0, 0, 10, 1, null);
            bullets.each(row -> {
                trace.add(bullets.id[row]);
                int roll = random.nextInt(10);
                if(roll < 3) bullets.remove(row);
                else if(roll < 4) bullets.add(ids, 0, 0, 0, 0, 0, 0, 10, 1, null);
            });
            trace.add(-1);
        }
        return trace;
    }

    static List<Integer> list(long seed){
        var random = new SplittableRandom(seed);
        var list = new ActiveList<Probe>(new ActiveList.Slot<>(){
            @Override
            public int get(Probe probe){
                return probe.position;
            }

            @Override
            public void set(Probe probe, int position){
                probe.position = position;
            }
        });
        int[] next = {0};
        var trace = new ArrayList<Integer>();
        for(int round = 0; round < 30; round++){
            for(int i = random.nextInt(0, 10); i > 0; i--) list.add(new Probe(next[0]++));
            list.each(probe -> {
                trace.add(probe.label);
                int roll = random.nextInt(10);
                if(roll < 3) list.remove(probe);
                else if(roll < 4) list.add(new Probe(next[0]++));
            });
            trace.add(-1);
        }
        return trace;
    }

    static final class Probe extends Entity{
        final int label;
        int position = -1;

        Probe(int label){
            this.label = label;
        }

        @Override
        public void add(){
        }

        @Override
        public void remove(){
        }

        @Override
        public Copyable blank(){
            return new Probe(label);
        }
    }
}
