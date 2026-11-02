package com.xpdustry.shard.server.sched;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;
import java.util.function.*;

/**
 * Runs world ticks earliest-deadline-first on a fixed pool of {@code tick-N} platform threads (ARCHITECTURE.md §7,
 * note 24 §2.2, §2.4). A world's tick never runs on two threads at once, and Δ is always 1: a world that can't keep
 * up runs fewer ticks, never longer ones.
 *
 * <ul>
 *   <li><b>Leader hand-off.</b> The worker that takes the head of the queue signals another waiter if the queue is
 *   not empty, so the next deadline is always watched. Without it, note 24's prototype started every world one
 *   tick-duration late.</li>
 *   <li><b>Spread phases.</b> A world's first deadline is offset by {@link Stagger#tickPhase}, a mixed hash of its
 *   id, so worlds added together don't run as one burst every period.</li>
 *   <li><b>Bounded catch-up.</b> A turn runs the due tick and then at most {@value #MAX_CATCH_UP} more while they are
 *   due, then yields. A world more than {@value #MAX_CATCH_UP} ticks behind drops its backlog, counts it as lag and
 *   takes "now" as its next deadline: it never keeps a past deadline, so it can't jump ahead of worlds that are on
 *   time (in note 24, catch-up for an always-late world made the others 65 ms late).</li>
 *   <li><b>Modes.</b> {@link Mode#ACTIVE} worlds tick at the period; {@link Mode#MAINTENANCE} worlds get a turn
 *   every maintenance period (1 s) without ticking; {@link Mode#PARKED} worlds aren't queued and cost nothing.</li>
 *   <li><b>Watchdog.</b> A thread checks running ticks and boundaries ({@link ScheduledWorld#boundary()}: captures,
 *   autosave forks) and dumps the stack of any that has run for more than 5× the period.</li>
 * </ul>
 *
 * <p>All of its time comes from a {@link NanoClock}: the system's, or a {@link ManualClock} that tests move, so what
 * they check (deadlines, lateness, drops, catch-up, maintenance turns, stalls) doesn't depend on how busy the machine
 * is.
 */
public final class WorldScheduler implements AutoCloseable{
    /** The ticks a turn may run beyond the due one. */
    public static final int MAX_CATCH_UP = 3;
    /** 60 Hz. */
    public static final long PERIOD = 1_000_000_000L / 60;
    public static final long MAINTENANCE_PERIOD = 1_000_000_000L;

    public enum Mode{
        /** Ticking at the period. */
        ACTIVE,
        /** Empty: one {@link ScheduledWorld#maintain()} turn per maintenance period, no ticks. */
        MAINTENANCE,
        /** Not scheduled at all (hibernating, failed, being restored). */
        PARKED
    }

    /**
     * A tick, or the {@link ScheduledWorld#boundary()} after a turn, that ran over 5× its budget, with the stack of its
     * thread when the watchdog looked.
     */
    public record Stall(String world, long elapsedNanos, StackTraceElement[] stack, boolean boundary){
        /** What ran: {@code tick} or {@code boundary}. */
        public String step(){
            return boundary ? "boundary" : "tick";
        }

        @Override
        public String toString(){
            var out = new StringBuilder(String.format("world %s: %s running for %.1f ms%n", world, step(), elapsedNanos / 1e6));
            for(StackTraceElement element : stack) out.append("\tat ").append(element).append('\n');
            return out.toString();
        }
    }

    /** Scheduler settings; {@link #of} gives 60 Hz with a 1 s maintenance period. */
    public record Settings(int threads, long period, long maintenancePeriod, long watchdogBudget, String threadPrefix){
        public static Settings of(int threads){
            return new Settings(threads, PERIOD, MAINTENANCE_PERIOD, 5 * PERIOD, "tick-");
        }

        public Settings{
            if(threads < 1 || period <= 0 || maintenancePeriod <= 0 || watchdogBudget <= 0) throw new IllegalArgumentException(toString());
        }

        public Settings withPeriods(long period, long maintenancePeriod){
            return new Settings(threads, period, maintenancePeriod, 5 * period, threadPrefix);
        }
    }

    private static final System.Logger LOG = System.getLogger(WorldScheduler.class.getName());

    private final Settings settings;
    private final NanoClock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final PriorityQueue<Slot> due = new PriorityQueue<>(Comparator.<Slot>comparingLong(slot -> slot.deadline).thenComparingLong(slot -> slot.order));
    private final CopyOnWriteArrayList<Slot> slots = new CopyOnWriteArrayList<>();
    private final List<Thread> workers = new ArrayList<>();
    private final Thread watchdog;
    private final Consumer<Stall> stalls;
    private long added;
    private volatile boolean closed;

    /**
     * {@code stalls} receives watchdog reports, on the watchdog thread; it must not block for long. Time comes from
     * {@code clock}.
     */
    public WorldScheduler(Settings settings, Consumer<Stall> stalls, NanoClock clock){
        this.settings = settings;
        this.stalls = stalls;
        this.clock = Objects.requireNonNull(clock, "clock");
        for(int i = 0; i < settings.threads(); i++){
            workers.add(Thread.ofPlatform().name(settings.threadPrefix() + i).daemon().start(this::work));
        }
        watchdog = Thread.ofPlatform().name(settings.threadPrefix() + "watchdog").daemon().unstarted(this::watch);
        // before it runs: a busy machine may start it late, and a manual clock must wait for its first look
        clock.addWatcher(watchdog);
        watchdog.start();
    }

    /** On the system clock. */
    public WorldScheduler(Settings settings, Consumer<Stall> stalls){
        this(settings, stalls, NanoClock.SYSTEM);
    }

    /** With stalls logged as warnings, on the system clock. */
    public WorldScheduler(Settings settings){
        this(settings, stall -> LOG.log(System.Logger.Level.WARNING, stall.toString()));
    }

    public Settings settings(){
        return settings;
    }

    /** The clock of the deadlines: the worlds' timers read it too. */
    public NanoClock clock(){
        return clock;
    }

    /**
     * Schedules {@code world} in {@code mode}. {@code id} spreads its tick phase ({@link Stagger}); {@code name}
     * labels stats and stalls.
     */
    public Slot add(String name, long id, ScheduledWorld world, Mode mode){
        var slot = new Slot(name, id, world);
        lock.lock();
        try{
            if(closed) throw new IllegalStateException("the scheduler is closed");
            slot.order = added++;
            slot.mode = slot.requested = mode;
            long now = clock.nanoTime();
            slot.deadline = mode == Mode.MAINTENANCE ? now + settings.maintenancePeriod() : now + Stagger.tickPhase(id, settings.period());
            if(mode != Mode.PARKED) insert(slot);
            slots.add(slot);
        }finally{
            lock.unlock();
        }
        return slot;
    }

    /** Every slot not yet removed. */
    public List<Slot> slots(){
        return List.copyOf(slots);
    }

    private void work(){
        while(true){
            Slot slot;
            lock.lock();
            try{
                while(true){
                    if(closed) return;
                    Slot head = due.peek();
                    if(head == null){
                        changed.await();
                        continue;
                    }
                    long wait = head.deadline - clock.nanoTime();
                    if(wait > 0){
                        clock.awaitNanos(changed, wait);
                        continue;
                    }
                    slot = due.poll();
                    slot.queued = false;
                    slot.running = true;
                    // The leader hand-off: someone else must now wait for the next deadline.
                    if(!due.isEmpty()) changed.signal();
                    break;
                }
            }catch(InterruptedException e){
                return;
            }finally{
                lock.unlock();
            }
            turn(slot);
            lock.lock();
            try{
                slot.running = false;
                reschedule(slot);
            }finally{
                lock.unlock();
            }
        }
    }

    /** One turn: a maintenance call, or the due tick plus bounded catch-up; then the boundary hook. */
    private void turn(Slot slot){
        try{
            if(slot.mode == Mode.ACTIVE){
                int ran = 0;
                while(true){
                    long start = clock.nanoTime();
                    slot.thread = Thread.currentThread();
                    slot.ticking.start = start;
                    try{
                        slot.world.tick(slot.stats);
                    }finally{
                        slot.ticking.start = 0;
                    }
                    long end = clock.nanoTime();
                    slot.stats.tick(start - slot.deadline, end - start);
                    ran++;
                    slot.deadline += settings.period();
                    if(slot.deadline > end){
                        slot.behind = false;
                        break;
                    }
                    long behind = (end - slot.deadline) / settings.period() + 1;
                    if(slot.behind || behind > MAX_CATCH_UP){
                        // Too far behind to catch up: drop the backlog and never keep a past deadline.
                        if(!slot.behind){
                            LOG.log(System.Logger.Level.WARNING, "world " + slot.name + " is " + behind + " ticks behind; dropping them until it keeps up");
                        }
                        slot.stats.dropped(behind);
                        slot.deadline = end;
                        slot.behind = true;
                        break;
                    }
                    if(ran > MAX_CATCH_UP) break;
                }
                slot.stats.turn(ran);
            }else if(slot.mode == Mode.MAINTENANCE){
                slot.stats.enter(TickStats.Extra.MAINTENANCE);
                try{
                    slot.world.maintain();
                }finally{
                    slot.stats.exit();
                }
                slot.stats.maintained();
                slot.deadline += settings.maintenancePeriod();
            }
            long boundaryStart = clock.nanoTime();
            slot.thread = Thread.currentThread();
            slot.bounding.start = boundaryStart;
            slot.stats.enter(TickStats.Extra.BOUNDARY);
            try{
                slot.world.boundary();
            }finally{
                slot.stats.exit();
                slot.bounding.start = 0;
                slot.stats.boundary(clock.nanoTime() - boundaryStart);
            }
        }catch(Throwable error){
            lock.lock();
            try{
                slot.requested = Mode.PARKED;
            }finally{
                lock.unlock();
            }
            try{
                slot.world.failed(error);
            }catch(Throwable nested){
                error.addSuppressed(nested);
                LOG.log(System.Logger.Level.ERROR, "world " + slot.name + " failed, and so did its failure handler", error);
            }
        }
    }

    /** Puts a slot back after its turn or a mode change, in its requested mode. Holds the lock. */
    private void reschedule(Slot slot){
        if(slot.removed){
            slots.remove(slot);
            slot.gone.complete(null);
            return;
        }
        Mode mode = slot.requested;
        long now = clock.nanoTime();
        if(mode != slot.mode){
            if(mode == Mode.ACTIVE){
                slot.deadline = now;
                slot.behind = false;
            }else if(mode == Mode.MAINTENANCE){
                slot.deadline = now + settings.maintenancePeriod();
            }
            slot.mode = mode;
        }else if(mode == Mode.MAINTENANCE && slot.deadline < now){
            slot.deadline = now;
        }
        if(mode != Mode.PARKED) insert(slot);
    }

    private void insert(Slot slot){
        slot.queued = true;
        due.add(slot);
        // Only a new head changes what the waiting leader waits for.
        if(due.peek() == slot) changed.signal();
    }

    private void watch(){
        long budget = settings.watchdogBudget();
        long interval = Math.max(1_000_000L, Math.min(budget / 4, 50_000_000L));
        while(!closed){
            clock.parkNanos(this, interval);
            long now = clock.nanoTime();
            for(Slot slot : slots){
                check(slot, slot.ticking, false, now, budget);
                check(slot, slot.bounding, true, now, budget);
            }
        }
    }

    /** Reports {@code step} of {@code slot} once if it has run for more than {@code budget}. */
    private void check(Slot slot, Watched step, boolean boundary, long now, long budget){
        long start = step.start;
        Thread thread = slot.thread;
        if(start == 0 || thread == null || now - start <= budget || step.reported == start) return;
        step.reported = start;
        StackTraceElement[] stack = thread.getStackTrace();
        if(step.start != start) return;
        slot.stats.stalled();
        try{
            stalls.accept(new Stall(slot.name, now - start, stack, boundary));
        }catch(RuntimeException e){
            LOG.log(System.Logger.Level.ERROR, "the stall handler failed", e);
        }
    }

    /** A step of a turn the watchdog watches. */
    private static final class Watched{
        /** When the running step started, or 0 between steps. */
        volatile long start;
        /** The start the watchdog last reported; only the watchdog touches it. */
        long reported;
    }

    /**
     * Whether nothing more happens until {@code manual} moves on: every running turn sleeps on it, and nothing is due
     * that a free worker could take. For tests that drive the scheduler through virtual time.
     */
    public boolean idle(ManualClock manual){
        lock.lock();
        try{
            int running = 0;
            for(Slot slot : slots){
                if(slot.running) running++;
            }
            if(manual.sleepers() != running) return false;
            Slot head = due.peek();
            return head == null || head.deadline > clock.nanoTime() || running == settings.threads();
        }finally{
            lock.unlock();
        }
    }

    /** The next deadline after now, or {@link Long#MAX_VALUE}. For tests. */
    long nextDeadline(){
        lock.lock();
        try{
            Slot head = due.peek();
            return head == null || head.deadline <= clock.nanoTime() ? Long.MAX_VALUE : head.deadline;
        }finally{
            lock.unlock();
        }
    }

    /** Stops the workers after their current turn; slots stay as they are. */
    @Override
    public void close(){
        lock.lock();
        try{
            closed = true;
            changed.signalAll();
        }finally{
            lock.unlock();
        }
        LockSupport.unpark(watchdog);
        for(Thread worker : workers){
            try{
                worker.join(TimeUnit.SECONDS.toMillis(10));
            }catch(InterruptedException e){
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** One world's place in the scheduler. */
    public final class Slot{
        private final String name;
        private final long id;
        private final ScheduledWorld world;
        private final TickStats stats = new TickStats();
        private final CompletableFuture<Void> gone = new CompletableFuture<>();
        // Guarded by the scheduler's lock, or owned by the worker running the turn.
        private long deadline, order;
        private Mode mode;
        private boolean queued, running, removed, behind;
        /** Written under the scheduler's lock; volatile so {@link #mode()} reads it without the lock. */
        private volatile Mode requested;
        // Read by the watchdog.
        private final Watched ticking = new Watched(), bounding = new Watched();
        private volatile Thread thread;

        private Slot(String name, long id, ScheduledWorld world){
            this.name = name;
            this.id = id;
            this.world = world;
        }

        public String name(){
            return name;
        }

        public long id(){
            return id;
        }

        public TickStats stats(){
            return stats;
        }

        /**
         * The mode the slot runs in now, or will after the running turn. Without the scheduler's lock: input arriving
         * for a world asks it ({@code ManagedWorld.inputArrived}, {@code inputWaiting}) from tick and server threads,
         * and with the lock a tick thread's unlock had to wake the server's virtual threads queued on it.
         */
        public Mode mode(){
            return requested;
        }

        /**
         * Changes the mode, from any thread, including the world's own {@link ScheduledWorld#boundary()}. It applies
         * after a running turn; a queued or parked slot moves at once. Switching to {@link Mode#ACTIVE} makes the next
         * tick due now.
         */
        public void mode(Mode mode){
            Objects.requireNonNull(mode, "mode");
            lock.lock();
            try{
                if(removed) return;
                requested = mode;
                if(running || mode == this.mode) return;
                if(queued){
                    due.remove(this);
                    queued = false;
                }
                reschedule(this);
            }finally{
                lock.unlock();
            }
        }

        /** Whether a turn is running on a tick thread right now. */
        public boolean running(){
            lock.lock();
            try{
                return running;
            }finally{
                lock.unlock();
            }
        }

        /**
         * Removes the slot. The future completes once no turn is running and none will: after that, the world's
         * state may be touched from any thread.
         */
        public CompletableFuture<Void> remove(){
            lock.lock();
            try{
                if(!removed){
                    removed = true;
                    if(queued){
                        due.remove(this);
                        queued = false;
                    }
                    if(!running){
                        slots.remove(this);
                        gone.complete(null);
                    }
                }
            }finally{
                lock.unlock();
            }
            return gone;
        }

        @Override
        public String toString(){
            return "Slot[" + name + ", " + mode() + ", " + stats.summary() + "]";
        }
    }
}
