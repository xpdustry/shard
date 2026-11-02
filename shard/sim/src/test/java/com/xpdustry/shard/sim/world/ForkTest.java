package com.xpdustry.shard.sim.world;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.toy.*;
import com.xpdustry.shard.sim.unit.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** {@link World#fork()}: equal to its source, continuing identically, independent of it, references remapped. */
class ForkTest{
    @Test
    void aForkHashesLikeItsSourceAndContinuesIdentically(){
        World source = Worlds.churned(1, 300);
        World fork = source.fork();
        assertEquals(source.hash(), fork.hash());
        for(int i = 0; i < 300; i++){
            source.tick();
            fork.tick();
            WorldHash a = source.hash(), b = fork.hash();
            assertEquals(a, b, "tick " + source.ticks() + ": " + a.firstDifference(b).orElse("?"));
        }
        assertTrue(source.buildings().count() > 20, "the churn built little: " + source.buildings().count());
        assertTrue(source.bullets().size() > 0 && !source.units().isEmpty(), "the churn left no bullets or units");
    }

    @Test
    void aForkIsIndependentOfItsSource(){
        World source = Worlds.churned(2, 200);
        WorldHash before = source.hash();
        World fork = source.fork();
        for(int i = 0; i < 50; i++) fork.tick();
        Building building = fork.buildings().at(firstBuiltTile(fork));
        fork.buildings().remove(building, Cause.SERVER);
        fork.rand().nextLong();
        assertEquals(before, source.hash(), "ticking and mutating the fork changed the source");
        assertNotEquals(before, fork.hash());

        World again = source.fork();
        for(int i = 0; i < 20; i++) source.tick();
        assertEquals(before, again.hash(), "ticking the source changed an older fork");
    }

    @Test
    void everyObjectIsCopiedFieldByFieldWithReferencesRemapped(){
        World source = Worlds.churned(3, 250);
        World fork = source.fork();
        var compare = new DeepCompare(source, fork);
        int links = 0, stale = 0;
        for(int i = 0; i < source.buildings().indexLimit(); i++){
            Building original = source.buildings().get(i), copy = fork.buildings().get(i);
            compare.copyable(original, copy, "building " + i);
            if(original instanceof Link link){
                links++;
                var copied = (Link)copy;
                if(link.target != null){
                    assertSame(fork, copied.target.world());
                    assertEquals(link.target.isValid(), copied.target.isValid());
                    if(link.target.isValid()) assertSame(fork.buildings().get(link.target.index()), copied.target);
                    else stale++;
                }
            }
        }
        for(int i = 0; i < source.builds().size(); i++){
            assertSame(fork.buildings().get(source.builds().get(i).index()), fork.builds().get(i), "update order " + i);
        }
        for(int i = 0; i < source.units().size(); i++){
            compare.copyable(source.units().get(i), fork.units().get(i), "unit " + i);
            Follow follow = (Follow)fork.units().get(i).controller;
            assertTrue(follow.home == null || follow.home.world() == fork);
        }
        for(int row = 0; row < source.bullets().size(); row++){
            compare.copyable(source.bullets().owner[row], fork.bullets().owner[row], "bullet owner " + row);
        }
        compare.copyable(source.part(Chaos.class), fork.part(Chaos.class), "chaos");
        assertTrue(links > 3, "too few links to test remapping: " + links);
        assertTrue(stale > 0, "no stale references were exercised");
    }

    @Test
    void referenceCyclesAndLongChainsCopyWithoutRecursion(){
        World world = Worlds.quiet(4);
        var chain = new ArrayList<Link>();
        for(int y = 1; y < 47; y += 2){
            for(int x = 1; x < 47; x += 2){
                chain.add((Link)world.buildings().create(ToyContent.LINK, 1, x, y, 0, Cause.SERVER));
            }
        }
        for(int i = 0; i < chain.size(); i++) chain.get(i).target = chain.get((i + 1) % chain.size());
        World fork = world.fork();
        var first = (Link)fork.buildings().get(chain.getFirst().index());
        Building at = first;
        for(int i = 0; i < chain.size(); i++){
            assertSame(fork, at.world());
            at = ((Link)at).target;
        }
        assertSame(first, at, "the cycle closes on the copy");
        assertEquals(world.hash(), fork.hash());
    }

    @Test
    void theInboxAndObserversAreNotForked(){
        World world = Worlds.quiet(5);
        Observer observer = world.observe(8);
        assertTrue(world.submit(new Members.Join(1, 1), Cause.SERVER));
        World fork = world.fork();
        assertEquals(0, fork.inbox().size());
        assertEquals(1, world.inbox().size());
        fork.publish(new Noise());
        fork.tick();
        assertNull(observer.poll(), "the fork published to the source's observer");
    }

    record Noise() implements WorldEvent{
    }

    static int firstBuiltTile(World world){
        for(int pos = 0; pos < world.tiles().size(); pos++){
            if(world.tiles().build(pos) != -1) return pos;
        }
        throw new AssertionError("no buildings");
    }
}
