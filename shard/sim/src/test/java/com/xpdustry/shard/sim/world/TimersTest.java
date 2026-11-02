package com.xpdustry.shard.sim.world;

import arc.util.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.toy.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** {@link Timers} against Arc's {@code Time} at Δ = 1, and its place in the tick. */
class TimersTest{
    /** Logs a firing; some firings schedule more, decided by the id so both sides decide alike. */
    record Fire(int id, Script script) implements Timers.Task{
        @Override
        public void run(World world){
            script.fired(id, (delay, child) -> world.timers().run(delay, new Fire(child, script)));
        }
    }

    interface Scheduler{
        void run(float delay, int id);
    }

    /** One side's log and the shared rule for children. */
    static final class Script{
        final ArrayList<String> log = new ArrayList<>();
        long tick;

        void fired(int id, Scheduler scheduler){
            log.add(tick + ":" + id);
            // Children with no delay run in the same pass; negative ones too (vanilla fires anything <= 0).
            int kind = Math.floorMod(id * 0x9E3779B1, 7);
            if(id < 1_000_000 && kind < 3) scheduler.run(kind == 0 ? 0f : kind == 1 ? -2f : 1.5f, id * 7 + 1_000_000 + kind);
        }
    }

    @Test
    void firesLikeArcTime(){
        Time.clear();
        Time.setInternalTime(0);
        Time.delta = 1f;
        World world = Worlds.quiet(1);
        var ours = new Script();
        var theirs = new Script();
        var random = new Random(77);
        int next = 0;
        for(int tick = 0; tick < 3000; tick++){
            ours.tick = theirs.tick = tick;
            int count = random.nextInt(4);
            for(int i = 0; i < count; i++){
                float delay = switch(random.nextInt(6)){
                    case 0 -> 0f;
                    case 1 -> 1f;
                    case 2 -> 0.5f;
                    case 3 -> random.nextInt(60);
                    case 4 -> random.nextFloat() * 90f;
                    default -> -random.nextFloat();
                };
                int id = next++;
                world.timers().run(delay, new Fire(id, ours));
                arcRun(delay, id, theirs);
            }
            world.timers().update(world);
            Time.update();
            assertEquals(theirs.log, ours.log, "tick " + tick);
            assertEquals(Time.time, world.timers().time(), "time at tick " + tick);
            assertEquals(Time.getRuns().size, world.timers().pending().size(), "pending at tick " + tick);
        }
        assertTrue(ours.log.size() > 3000, "too few firings: " + ours.log.size());
        Time.clear();
    }

    private static void arcRun(float delay, int id, Script script){
        Time.run(delay, () -> script.fired(id, (childDelay, child) -> arcRun(childDelay, child, script)));
    }

    @Test
    void aTimerSetBeforeATickFiresInThatTicksTimePhase(){
        World world = Worlds.quiet(2);
        var script = new Script();
        world.timers().run(1f, new Fire(1_000_001, script));
        world.timers().run(2f, new Fire(1_000_002, script));
        script.tick = 0;
        world.tick();
        assertEquals(List.of("0:1000001"), script.log, "delay 1 fires on the next tick");
        script.tick = 1;
        world.tick();
        assertEquals(List.of("0:1000001", "1:1000002"), script.log);
        assertEquals(2f, world.timers().time());
    }

    @Test
    void aLoadedWorldsClockContinuesFromItsSave(){
        World world = Worlds.quiet(2);
        world.timers().continueFrom(300.0);
        world.tick();
        assertEquals(301f, world.timers().time());
        assertThrows(IllegalStateException.class, () -> world.timers().continueFrom(5.0), "only before the clock starts");
    }

    record Poke(Building target) implements Timers.Task{
        @Override
        public void run(World world){
            target.health -= 1f;
        }
    }

    @Test
    void pendingTimersForkWithTheirReferencesAndHash(){
        World world = Worlds.quiet(3);
        Building counter = world.buildings().create(ToyContent.COUNTER, 1, 5, 5, 0, Cause.SERVER);
        WorldHash idle = world.hash();
        world.timers().run(3f, new Poke(counter));
        assertNotEquals(idle, world.hash(), "a pending timer is state");
        World fork = world.fork();
        assertEquals(world.hash(), fork.hash());
        var task = (Poke)fork.timers().pending().getFirst().task();
        assertSame(fork.buildings().get(counter.index()), task.target(), "the fork's timer points at the fork's building");
        for(int i = 0; i < 3; i++){
            world.tick();
            fork.tick();
        }
        assertEquals(counter.maxHealth - 1f, counter.health);
        assertEquals(world.hash(), fork.hash());
        assertTrue(world.timers().pending().isEmpty());
    }

    @Test
    void lambdasAreRefusedBecauseTheyCannotBeStored(){
        var timers = new Timers();
        assertThrows(IllegalArgumentException.class, () -> timers.run(1f, w -> {}));
        assertThrows(IllegalArgumentException.class, () -> timers.run(1f, new Timers.Task(){
            @Override
            public void run(World world){
            }
        }));
        // a named class has no checkpoint codec: refused when queued, not at the next checkpoint
        assertThrows(IllegalArgumentException.class, () -> timers.run(1f, new Named()));
    }

    static final class Named implements Timers.Task{
        @Override
        public void run(World world){
        }
    }
}
