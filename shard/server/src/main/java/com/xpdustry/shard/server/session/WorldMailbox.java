package com.xpdustry.shard.server.session;

import com.xpdustry.shard.sim.world.*;

import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * A world's end of the per-session {@link Mailbox}es (ARCHITECTURE.md §4, step 2): the mailboxes that have something
 * for it, in the order they became ready, and the {@link #pump} that moves their entries into the world's
 * {@link Inbox}. The world-side API stays the inbox and {@link IntentEnvelope}; this is the runtime in front of it. It
 * outlives the {@code World}: a hibernated world wakes as a new {@code World} with a new inbox, and the pump takes
 * whichever is current.
 *
 * <p>The world's host calls {@link #pump} on the world's tick thread right before each tick, so whatever it moves is
 * admitted by that tick, and a leave the inbox took is as good as applied. The pump goes round-robin: each mailbox
 * gets a quantum per turn and goes to the back; one whose turn the budget cut short, or didn't reach, is first next
 * tick.
 *
 * <p><b>Seats.</b> A session takes a {@link Seat} ({@link #takeSeat}) before its route starts here, so a transfer never
 * leaves its source for a world that can't take it. The route keeps the seat until the world's inbox takes its leave.
 * A seat belongs to the release epoch it was taken in and goes back exactly once: a route of a failed epoch neither
 * starts on it after {@link #reopen} nor frees a seat of the new epoch when it ends.
 *
 * <p><b>Release.</b> When the world fails, {@link #release} gives up every route at once: leaves still waiting count
 * as delivered, later leaves are delivered on the spot, intents are dropped and no seat is given, until
 * {@link #reopen}.
 *
 * <p><b>Wakes.</b> A mailbox that becomes ready while none was makes a wake due; the offering side runs it with
 * {@link #wakeIfDue} once it holds no lock. A tick thread's pump takes the same mailbox and session locks, so a wake
 * run under them (an executor's hand-off, a call site linking) held the world's tick: 85-274 ms in RP2's bot gate.
 *
 * <p>Thread-safe; {@link #pump} runs on one thread at a time.
 */
public final class WorldMailbox{
    /** Intents one session may have waiting: about a second of a vanilla client's reliable packets in one tick. */
    public static final int DEFAULT_BOUND = 64;
    /** Entries per mailbox per turn of the pump. */
    public static final int DEFAULT_QUANTUM = 16;

    private final int capacity, bound, quantum, reserve, seatLimit;
    /** Guarded by itself, as are {@link #released}, {@link #epoch} and {@link #seats}. */
    private final ArrayDeque<Mailbox> ready = new ArrayDeque<>();
    private boolean released;
    /** Grows with each {@link #release}: a mailbox opened before one is given up for good, even after {@link #reopen}. */
    private int epoch;
    private int seats;
    private volatile Runnable signal;
    /** A mailbox became ready while none was, and nobody has run the signal for it yet. */
    private final AtomicBoolean wakeDue = new AtomicBoolean();
    private final Budget budget = new Budget();

    /** {@link #DEFAULT_BOUND}, {@link #DEFAULT_QUANTUM}, an eighth of the inbox reserved for control, and no seat limit. */
    public WorldMailbox(Inbox inbox){
        this(inbox.capacity(), DEFAULT_BOUND, DEFAULT_QUANTUM, Math.max(1, inbox.capacity() / 8), 0);
    }

    /**
     * @param capacity the capacity of the world's inbox
     * @param bound intents each session may have waiting
     * @param quantum entries a mailbox moves per turn of the pump
     * @param reserve inbox slots intents never take, kept for joins, leaves and other sources
     * @param seatLimit sessions the world takes at once, or 0 for no limit
     */
    public WorldMailbox(int capacity, int bound, int quantum, int reserve, int seatLimit){
        if(bound <= 0 || quantum <= 0) throw new IllegalArgumentException("bound and quantum must be positive: " + bound + ", " + quantum);
        if(reserve < 0 || reserve >= capacity){
            throw new IllegalArgumentException("reserve must leave room for intents: " + reserve + " of " + capacity);
        }
        if(seatLimit < 0) throw new IllegalArgumentException("negative seat limit " + seatLimit);
        this.capacity = capacity;
        this.bound = bound;
        this.quantum = quantum;
        this.reserve = reserve;
        this.seatLimit = seatLimit;
    }

    public int bound(){
        return bound;
    }

    public int reserve(){
        return reserve;
    }

    /**
     * Runs {@code signal} on the offering thread whenever a mailbox becomes ready while none was, so a scheduler can
     * wake a world that ticks slowly or not at all: from {@link #wakeIfDue}, outside every lock. It should be quick.
     * Null removes it.
     */
    public void signal(Runnable signal){
        this.signal = signal;
    }

    /** Mailboxes with something waiting, about now. */
    public int waiting(){
        synchronized(ready){
            return ready.size();
        }
    }

    /** Seats taken: routes here whose leave the world hasn't taken yet, and reservations for routes about to start. */
    public int seats(){
        synchronized(ready){
            return seats;
        }
    }

    /**
     * Takes a seat for a route about to start here ({@link Session#join}, {@link Session#beginTransfer}).
     *
     * @return null if the world is full or released
     */
    Seat takeSeat(){
        synchronized(ready){
            if(released || (seatLimit > 0 && seats >= seatLimit)) return null;
            seats++;
            return new Seat(this, epoch);
        }
    }

    /** From {@link Seat#giveBack}, once per seat: a seat of an older epoch was freed by {@link #release} already. */
    private void returnSeat(Seat seat){
        synchronized(ready){
            if(seat.epoch == epoch && seats > 0) seats--;
        }
    }

    /**
     * The world failed: drops every waiting entry, counts every queued leave as delivered and frees every seat. Until
     * {@link #reopen}, leaves are delivered as they are sealed and no seat is given. Not while {@link #pump} runs.
     */
    public void release(){
        List<Mailbox> pending;
        synchronized(ready){
            released = true;
            epoch++;
            seats = 0;
            pending = List.copyOf(ready);
            ready.clear();
        }
        for(Mailbox mailbox : pending) mailbox.release();
    }

    /** The world is back ({@code recover}): routes may start here again. */
    public void reopen(){
        synchronized(ready){
            released = false;
        }
    }

    public boolean released(){
        synchronized(ready){
            return released;
        }
    }

    /**
     * A new route's mailbox, on a seat {@link #takeSeat} took, with {@code join} as its first entry.
     *
     * @return null, with the seat given back, if the world released the seat's epoch since
     */
    Mailbox open(Session session, int generation, Submission join, Seat seat){
        if(seat.world != this) throw new IllegalArgumentException(seat + " is not a seat here");
        var mailbox = new Mailbox(this, seat, session, generation, join);
        if(ready(mailbox)) return mailbox;
        seat.giveBack();
        return null;
    }

    /** Whether a mailbox opened in {@code epoch} still delivers here. */
    boolean live(int epoch){
        synchronized(ready){
            return !released && this.epoch == epoch;
        }
    }

    /**
     * Queues a mailbox that became ready, and makes a wake due if none was; false, with nothing queued, if its world
     * released it. Under the mailbox's and the session's locks, so it never runs the signal itself.
     */
    boolean ready(Mailbox mailbox){
        synchronized(ready){
            if(released || epoch != mailbox.epoch()) return false;
            if(ready.isEmpty()) wakeDue.set(true);
            ready.add(mailbox);
        }
        return true;
    }

    /**
     * Runs the signal if a wake is due, once per wake: the offering side calls it after it released its locks. Of two
     * threads racing here, one runs it; a later offer that made no wake due may run one an earlier offer still owes.
     */
    public void wakeIfDue(){
        if(!wakeDue.get() || !wakeDue.compareAndSet(true, false)) return;
        Runnable signal = this.signal;
        if(signal != null) signal.run();
    }

    /**
     * Moves waiting entries into {@code inbox}, round-robin, until it is full or every mailbox is empty. Intents stop
     * {@link #reserve()} slots short of full. On the world's tick thread, right before its tick.
     *
     * @return how many entries moved
     */
    public int pump(Inbox inbox){
        if(inbox.capacity() != capacity) throw new IllegalArgumentException("an inbox of " + inbox.capacity() + ", not " + capacity);
        int size = inbox.size();
        budget.room = capacity - size;
        budget.intentRoom = capacity - reserve - size;
        budget.quantum = quantum;
        budget.moved = 0;
        ArrayList<Mailbox> blocked = null;
        while(budget.room > 0){
            Mailbox next;
            synchronized(ready){
                next = ready.poll();
            }
            if(next == null) break;
            budget.blocked = false;
            if(!next.drain(inbox, budget)) continue;
            if(budget.blocked){
                // the budget cut its turn short: it goes first next tick
                if(blocked == null) blocked = new ArrayList<>();
                blocked.add(next);
            }else{
                synchronized(ready){
                    ready.add(next);
                }
            }
        }
        if(blocked != null){
            synchronized(ready){
                for(Mailbox mailbox : blocked.reversed()) ready.addFirst(mailbox);
            }
        }
        return budget.moved;
    }

    /**
     * A seat {@link #takeSeat} gave in one release epoch. {@link #giveBack} returns it exactly once, and only to the
     * epoch it came from.
     */
    static final class Seat{
        private final WorldMailbox world;
        private final int epoch;
        private final AtomicBoolean back = new AtomicBoolean();

        private Seat(WorldMailbox world, int epoch){
            this.world = world;
            this.epoch = epoch;
        }

        int epoch(){
            return epoch;
        }

        /** Safe to call again, and from any thread. */
        void giveBack(){
            if(back.compareAndSet(false, true)) world.returnSeat(this);
        }

        @Override
        public String toString(){
            return "seat(epoch " + epoch + (back.get() ? ", back" : "") + ")";
        }
    }

    /** The pump's running totals, reused across ticks. */
    static final class Budget{
        int room, intentRoom, quantum, moved;
        boolean blocked;
    }
}
