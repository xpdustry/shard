package com.xpdustry.shard.server.sched;

import com.xpdustry.shard.sim.world.*;

import java.lang.management.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * One world's tick accounting (ARCHITECTURE.md §7 "Scheduler"): a tick-time histogram, a lateness histogram (tick
 * start minus deadline), a histogram of the boundary work after each turn, counters, and wall and thread-CPU time per
 * tick phase, which feeds {@code /shard profile}.
 * It is the {@link TickProbe} the scheduler passes to the world's tick. Written by the world's current tick thread,
 * readable from any thread.
 *
 * <p>Thread CPU time is a system call on Linux: read at every phase of every tick, it took 6% of the tick threads'
 * samples at 100 worlds, and more in the kernel (W2.4's load gate). So one tick at random in each run of
 * {@value #CPU_SAMPLE} reads it and counts {@value #CPU_SAMPLE} times what it measured. The per-phase CPU stays an
 * unbiased estimate, even of work that runs every Nth tick, which a fixed stride would alias. Wall time is measured
 * on every tick.
 */
public final class TickStats implements TickProbe{
    /** Accounting buckets beyond the tick phases. */
    public enum Extra{
        /** Work at tick boundaries: captures, hashes, lifecycle steps. */
        BOUNDARY,
        /** The 1 Hz turns of an empty world. */
        MAINTENANCE
    }

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final boolean CPU = THREADS.isCurrentThreadCpuTimeSupported();
    private static final int PHASES = Phase.values().length, SLOTS = PHASES + Extra.values().length;
    /** One tick in this many measures thread CPU time. */
    static final int CPU_SAMPLE = 16;

    private final Histogram tickTime = new Histogram(), lateness = new Histogram(), boundaryTime = new Histogram();
    private final AtomicLongArray wall = new AtomicLongArray(SLOTS), cpu = new AtomicLongArray(SLOTS);
    private final AtomicLong ticks = new AtomicLong(), dropped = new AtomicLong(), turns = new AtomicLong(),
        maintenance = new AtomicLong(), stalls = new AtomicLong();
    private final AtomicInteger maxTurnTicks = new AtomicInteger();

    // The open phase and the sampling state, touched only by the thread running the tick.
    private int open = -1;
    private long openWall, openCpu;
    /** {@link #openCpu}'s weight: 0 when it wasn't read. */
    private int openWeight;
    /** Whether the coming tick measures CPU, its position in the current run and the run's sampled position. */
    private boolean sampling = true;
    private int runPosition, runSample;

    @Override
    public void enter(Phase phase){
        open(phase.ordinal(), sampling ? CPU_SAMPLE : 0);
    }

    @Override
    public void exit(){
        close();
    }

    /** Opens an {@link Extra} bucket; {@link #exit()} closes it. */
    public void enter(Extra extra){
        // a maintenance turn comes once a second: always measured
        open(PHASES + extra.ordinal(), extra == Extra.MAINTENANCE ? 1 : sampling ? CPU_SAMPLE : 0);
    }

    private void open(int slot, int weight){
        close();
        open = slot;
        openWeight = CPU ? weight : 0;
        openWall = System.nanoTime();
        if(openWeight > 0) openCpu = cpuNow();
    }

    private void close(){
        if(open < 0) return;
        wall.addAndGet(open, System.nanoTime() - openWall);
        if(openWeight > 0) cpu.addAndGet(open, (cpuNow() - openCpu) * openWeight);
        open = -1;
    }

    private static long cpuNow(){
        return CPU ? THREADS.getCurrentThreadCpuTime() : 0;
    }

    void tick(long latenessNanos, long tookNanos){
        ticks.incrementAndGet();
        lateness.recordNanos(latenessNanos);
        tickTime.recordNanos(tookNanos);
        // the next tick's turn: a new run picks its sampled tick at random
        if(++runPosition == CPU_SAMPLE){
            runPosition = 0;
            runSample = ThreadLocalRandom.current().nextInt(CPU_SAMPLE);
        }
        sampling = runPosition == runSample;
    }

    /** One turn's {@link ScheduledWorld#boundary()}: outside the ticks, so outside {@link #tickTime()}. */
    void boundary(long tookNanos){
        boundaryTime.recordNanos(tookNanos);
    }

    void turn(int ticksRun){
        turns.incrementAndGet();
        maxTurnTicks.accumulateAndGet(ticksRun, Math::max);
    }

    void dropped(long count){
        dropped.addAndGet(count);
    }

    void maintained(){
        maintenance.incrementAndGet();
    }

    void stalled(){
        stalls.incrementAndGet();
    }

    /** Ticks run. */
    public long ticks(){
        return ticks.get();
    }

    /** Ticks skipped because the world fell more than the catch-up bound behind: its lag. */
    public long dropped(){
        return dropped.get();
    }

    public long turns(){
        return turns.get();
    }

    /** The most ticks one turn ran: 1 plus the catch-up ticks. */
    public int maxTurnTicks(){
        return maxTurnTicks.get();
    }

    /** Maintenance turns of an empty world, at 1 Hz with simulation time frozen. */
    public long maintenance(){
        return maintenance.get();
    }

    /** Ticks and boundaries the watchdog reported for running over 5× their budget. */
    public long stalls(){
        return stalls.get();
    }

    public Histogram tickTime(){
        return tickTime;
    }

    public Histogram lateness(){
        return lateness;
    }

    /** Each turn's {@link ScheduledWorld#boundary()}: captures, hashes, autosave forks. */
    public Histogram boundaryTime(){
        return boundaryTime;
    }

    /** Wall nanoseconds spent in {@code phase}. */
    public long wallNanos(Phase phase){
        return wall.get(phase.ordinal());
    }

    /** Thread-CPU nanoseconds spent in {@code phase}, estimated (see above); 0 where the JVM can't measure it. */
    public long cpuNanos(Phase phase){
        return cpu.get(phase.ordinal());
    }

    public long wallNanos(Extra extra){
        return wall.get(PHASES + extra.ordinal());
    }

    public long cpuNanos(Extra extra){
        return cpu.get(PHASES + extra.ordinal());
    }

    /** CPU nanoseconds by phase and extra bucket, largest first, for {@code /shard profile}. */
    public SortedMap<String, Long> profile(){
        var out = new TreeMap<String, Long>();
        for(Phase phase : Phase.values()){
            if(cpuNanos(phase) > 0 || wallNanos(phase) > 0) out.put(phase.name(), CPU ? cpuNanos(phase) : wallNanos(phase));
        }
        for(Extra extra : Extra.values()){
            if(cpuNanos(extra) > 0 || wallNanos(extra) > 0) out.put(extra.name(), CPU ? cpuNanos(extra) : wallNanos(extra));
        }
        return out;
    }

    public String summary(){
        return String.format("ticks=%d dropped=%d turns=%d maxTurn=%d maintenance=%d stalls=%d tick[%s] late[%s] boundary[%s]", ticks(),
            dropped(), turns(), maxTurnTicks(), maintenance(), stalls(), tickTime.summary(), lateness.summary(), boundaryTime.summary());
    }
}
