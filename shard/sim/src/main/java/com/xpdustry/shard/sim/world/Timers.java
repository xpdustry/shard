package com.xpdustry.shard.sim.world;

import com.xpdustry.shard.common.registry.*;

import java.util.*;

/**
 * A world's delayed tasks and game time: vanilla's {@code Time.run} and {@code Time.update}, one per world instead of
 * one static, at Δ = 1 (Arc {@code Time.java:39-98}). The world advances it at the start of {@link Phase#TIME}, once
 * per tick; a world that doesn't tick (paused, frozen while empty) doesn't advance it. Timers are
 * {@link Persistence#AUTHORITATIVE}: they are forked, hashed and stored in checkpoints.
 *
 * <p>Vanilla's order, kept exactly:
 * <ul>
 *   <li>{@link #update(World)} first adds 1 to the time, then walks the pending runs in the order they were added,
 *   subtracts 1 from each delay and runs every task whose delay reached {@code <= 0}, then removes the runs that
 *   fired, keeping the order of the rest.</li>
 *   <li>Vanilla quirk: a task added while {@code update} walks the list is appended and visited in the same pass, so
 *   its delay already drops by 1 then. {@code run(60, task)} from inside a task fires 59 updates later; from anywhere
 *   else it fires on the 60th update. A delay {@code <= 1} added from a task fires in the same pass.</li>
 *   <li>Vanilla pools its {@code DelayRun}s; reusing a freed run inside the same pass can't change which tasks fire or
 *   when, so Shard doesn't model the pool.</li>
 * </ul>
 *
 * <p>Tasks are world state, so they must be storable: records, copied and hashed component by component (mapping
 * world objects through the fork's {@link CopyContext}) through the shapes the registration index holds for them
 * ({@link Shaped}). Lambdas, anonymous classes and other classes are refused.
 */
public final class Timers{
    private double time;
    private final ArrayList<Run> runs;

    public Timers(){
        runs = new ArrayList<>();
    }

    private Timers(Timers source, CopyContext context){
        time = source.time;
        runs = new ArrayList<>(source.runs.size());
        for(Run run : source.runs) runs.add(new Run(run.delay, run.task.copy(context)));
    }

    Timers copy(CopyContext context){
        return new Timers(this, context);
    }

    /** Vanilla's {@code Time.run}: runs {@code task} after {@code delay} ticks. */
    public void run(float delay, Task task){
        Objects.requireNonNull(task, "task");
        if(!(task instanceof Record)){
            throw new IllegalArgumentException("a timer task is world state and must be a record: " + task.getClass().getName());
        }
        runs.add(new Run(delay, task));
    }

    /** Vanilla's {@code Time.update} at Δ = 1. */
    void update(World world){
        time += 1;
        // Vanilla resets an infinite or NaN time to 0; at Δ = 1 that would take longer than the universe has.
        boolean fired = false;
        for(int i = 0; i < runs.size(); i++){
            Run run = runs.get(i);
            run.delay -= 1f;
            if(run.delay <= 0f){
                run.fired = true;
                fired = true;
                run.task.run(world);
            }
        }
        if(fired) runs.removeIf(run -> run.fired);
    }

    /** Vanilla's {@code Time.time}: the game time in ticks, as the float vanilla reads (precise to 2^24 ticks). */
    public float time(){
        return (float)time;
    }

    /** Vanilla's {@code Time.timeRaw}, which {@link #time()} rounds. */
    public double rawTime(){
        return time;
    }

    /**
     * Starts a loaded world's clock at {@code time}, the game time its save was made at. Vanilla's {@code Time.time} is
     * global and a load doesn't reset it, so a loaded building's {@code Interval}, all zeros, counts against the time
     * already played, and timers longer ago than their period fire on the first frame. Shard models a server that has
     * played only this game: its clock is the save's {@code state.tick}. Only for a world that hasn't started its clock.
     */
    public void continueFrom(double time){
        if(this.time != 0 || !runs.isEmpty()) throw new IllegalStateException("the clock already started at " + this.time);
        this.time = time;
    }

    /** The pending runs, in vanilla's order. */
    public List<Pending> pending(){
        var out = new ArrayList<Pending>(runs.size());
        for(Run run : runs) out.add(new Pending(run.delay, run.task));
        return out;
    }

    /** For checkpoints: the state {@link #pending()} and {@link #rawTime()} described. */
    void restore(double time, List<Pending> pending){
        this.time = time;
        runs.clear();
        for(Pending run : pending) runs.add(new Run(run.delay(), run.task()));
    }

    void hash(Hasher hasher){
        hasher.d(time).i(runs.size());
        for(Run run : runs){
            hasher.f(run.delay).str(run.task.getClass().getName());
            run.task.hash(hasher);
        }
    }

    /** Something to do later, on the world thread: a record, whose shape the registration index holds. */
    @Shaped
    public interface Task{
        void run(World world);

        /** This task for a fork: copied component by component, unless the record overrides this. */
        default Task copy(CopyContext context){
            return this instanceof Record record ? (Task)Records.copy(record, context) : this;
        }

        /** Feeds this task's state to the world hash: its components, unless the record overrides this. */
        default void hash(Hasher hasher){
            Records.hash(this, hasher);
        }
    }

    /** A pending run: the task and the delay it has left. */
    public record Pending(float delay, Task task){
        public Pending{
            Objects.requireNonNull(task, "task");
        }
    }

    private static final class Run{
        float delay;
        final Task task;
        boolean fired;

        Run(float delay, Task task){
            this.delay = delay;
            this.task = task;
        }
    }
}
