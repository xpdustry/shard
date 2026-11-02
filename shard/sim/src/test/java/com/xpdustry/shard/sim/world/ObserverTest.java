package com.xpdustry.shard.sim.world;

import com.xpdustry.shard.sim.toy.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ObserverTest{
    record Built(int pos) implements WorldEvent{
    }

    record Destroyed(int pos) implements WorldEvent{
    }

    record Publish(int pos) implements Command.Script{
        @Override
        public Verdict apply(World world, Cause cause){
            world.publish(new Built(pos));
            return Verdict.ADMITTED;
        }
    }

    @Test
    void eventsArriveAfterTheirTickInOrder(){
        World world = Worlds.quiet(1);
        Observer observer = world.observe(16);
        for(int i = 0; i < 3; i++) world.submit(new Publish(i), Cause.SERVER);
        assertNull(observer.poll());
        world.tick();
        assertEquals(List.of(new Built(0), new Built(1), new Built(2)), observer.drain());
    }

    @Test
    void aFullQueueDropsAndCounts(){
        World world = Worlds.quiet(2);
        Observer slow = world.observe(2), fast = world.observe(10);
        for(int i = 0; i < 5; i++) world.submit(new Publish(i), Cause.SERVER);
        world.tick();
        assertEquals(List.of(new Built(0), new Built(1)), slow.drain());
        assertEquals(3, slow.dropped());
        assertEquals(5, fast.drain().size());
        assertEquals(0, fast.dropped());
    }

    record Flood(int built, int destroyed) implements Command.Script{
        @Override
        public Verdict apply(World world, Cause cause){
            for(int i = 0; i < destroyed; i++) world.publish(new Destroyed(i));
            for(int i = 0; i < built; i++) world.publish(new Built(i));
            return Verdict.ADMITTED;
        }
    }

    /** Throws on tick 1, after the tick's commands published. */
    public static final class Boom implements PhaseHandler{
        @Override
        public Phase phase(){
            return Phase.BUILDINGS;
        }

        @Override
        public void run(World world){
            if(world.ticks() == 1) throw new IllegalStateException("boom");
        }
    }

    @Test
    void observersReceiveOnlyTheTypesTheyAskFor(){
        World world = Worlds.quiet(3);
        Observer built = world.observe(8, List.of(Built.class), null), all = world.observe(8);
        world.submit(new Flood(2, 3), Cause.SERVER);
        world.tick();
        assertEquals(List.of(new Built(0), new Built(1)), built.drain());
        assertEquals(5, all.drain().size());
    }

    @Test
    void aFloodOfOneTypeDoesNotPushOutAnother(){
        World world = Worlds.quiet(4);
        Observer built = world.observe(4, List.of(Built.class), null), destroyed = world.observe(4, List.of(Destroyed.class), null);
        world.submit(new Flood(3, 1000), Cause.SERVER);
        world.tick();
        assertEquals(List.of(new Built(0), new Built(1), new Built(2)), built.drain());
        assertEquals(0, built.dropped());
        assertEquals(4, destroyed.drain().size());
        assertEquals(996, destroyed.dropped());
    }

    @Test
    void theWakeUpRunsOnlyWhenSomethingArrived(){
        World world = Worlds.quiet(5);
        var wakes = new int[1];
        Observer observer = world.observe(4, List.of(Built.class), () -> wakes[0]++);
        world.tick();
        world.submit(new Flood(0, 2), Cause.SERVER);
        world.tick();
        assertEquals(0, wakes[0], "nothing it wants arrived");
        world.submit(new Publish(1), Cause.SERVER);
        world.tick();
        assertEquals(1, wakes[0]);
        assertEquals(List.of(new Built(1)), observer.drain());
    }

    @Test
    void aClosedObserverIsForgotten(){
        World world = Worlds.quiet(6);
        Observer observer = world.observe(4);
        world.submit(new Publish(1), Cause.SERVER);
        world.tick();
        observer.close();
        world.submit(new Publish(2), Cause.SERVER);
        world.tick();
        assertEquals(List.of(new Built(1)), observer.drain(), "queued events stay drainable after close; new ones don't arrive");
        assertTrue(observer.closed());
    }

    @Test
    void aTickThatThrowsDeliversNothing(){
        var config = WorldConfig.of(ToyContent.INSTANCE, 16, 16, 1).withSystems(Systems.of(List.of(new Boom()), List.of()));
        var world = new World(config);
        Observer observer = world.observe(8);
        world.submit(new Publish(0), Cause.SERVER);
        world.tick();
        assertEquals(List.of(new Built(0)), observer.drain());
        world.submit(new Publish(1), Cause.SERVER);
        assertThrows(IllegalStateException.class, world::tick);
        assertTrue(world.atBoundary());
        assertNull(observer.poll(), "a failed tick's events never reach observers");
        assertEquals(0, observer.dropped());
    }
}
