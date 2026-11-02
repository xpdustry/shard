package com.xpdustry.shard.server.session;

import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * One session's bounded queue for one route (ARCHITECTURE.md §4, step 2): its join, its intents in offer order, and
 * finally its leave. The {@link WorldMailbox} of the route's world pumps it into the world's inbox at each tick
 * boundary, a few entries per turn, so one session can't take a whole world's inbox.
 *
 * <ul>
 *   <li>At most {@link WorldMailbox#bound()} intents wait. A reliable intent over that is refused, never dropped:
 *   the caller kicks the session.</li>
 *   <li>Only an input snapshot is ever coalesced: a newer one replaces the one still waiting, at the back of the
 *   queue, so it stays behind every reliable intent that came before it.</li>
 *   <li>The join and the leave are control entries outside the bound, and pump with priority: intents never take the
 *   world's last {@link WorldMailbox#reserve()} inbox slots, and a leaving mailbox's remaining intents may use them,
 *   so another session's traffic can't hold a leave back.</li>
 *   <li>The leave stays here until the world's inbox takes it, on the world's tick thread right before the tick that
 *   admits it. A full or sealed inbox only delays it; it goes in exactly once.</li>
 * </ul>
 *
 * <p>Thread-safe. Lock order: the session, then its mailbox, then the world's ready queue; the pump takes a mailbox
 * alone and tells the session about a delivered leave after releasing it.
 */
public final class Mailbox{
    /** What became of an offered intent. */
    enum Result{
        QUEUED,
        /** An input snapshot that replaced the one still waiting. */
        COALESCED,
        /** An input snapshot that met a full mailbox: a later one supersedes it. */
        DROPPED,
        /** A reliable intent that met a full mailbox. */
        FULL,
        /** The mailbox has its leave: its route is over. */
        SEALED,
        /** The world failed and gave up its routes ({@link WorldMailbox#release}). */
        RELEASED
    }

    private final WorldMailbox world;
    /** The route's seat: given back when the inbox takes the leave, or when the world releases the route. */
    private final WorldMailbox.Seat seat;
    private final Session session;
    private final int generation;
    /** Guarded by this: joins and leaves are {@link Submission}s, intents {@link IntentEnvelope}s. */
    private final ArrayDeque<Input> entries = new ArrayDeque<>();
    private IntentEnvelope snapshot;
    private Submission leave;
    private int intents;
    /** In the world's ready queue, or being pumped. */
    private boolean ready;
    private boolean delivered;

    Mailbox(WorldMailbox world, WorldMailbox.Seat seat, Session session, int generation, Submission join){
        this.world = world;
        this.seat = seat;
        this.session = session;
        this.generation = generation;
        entries.add(join);
        ready = true;
    }

    /** The {@link WorldMailbox} release epoch it was opened in: its seat's. */
    int epoch(){
        return seat.epoch();
    }

    /** The route generation this mailbox carries. */
    public int generation(){
        return generation;
    }

    /** Intents waiting, control entries excluded. */
    public synchronized int intents(){
        return intents;
    }

    /** Whether the leave is queued but the world's inbox hasn't taken it yet. */
    public synchronized boolean leaving(){
        return leave != null && !delivered;
    }

    synchronized Result offer(IntentEnvelope envelope, boolean supersedes){
        if(leave != null) return Result.SEALED;
        if(delivered || !world.live(seat.epoch())) return Result.RELEASED;
        if(supersedes && snapshot != null){
            if(entries.peekLast() == snapshot) entries.pollLast();
            else entries.removeLastOccurrence(snapshot);
            entries.add(envelope);
            snapshot = envelope;
            return Result.COALESCED;
        }
        if(intents >= world.bound()) return supersedes ? Result.DROPPED : Result.FULL;
        entries.add(envelope);
        intents++;
        if(supersedes) snapshot = envelope;
        if(!markReady()){
            giveUp();
            return Result.RELEASED;
        }
        return Result.QUEUED;
    }

    /**
     * Queues the leave as the last entry.
     *
     * @return false if it already has one, or if its world released it: then the leave counts as delivered now
     */
    synchronized boolean seal(Submission leave){
        if(this.leave != null) return false;
        this.leave = leave;
        if(delivered) return false;
        entries.add(leave);
        if(!markReady()){
            giveUp();
            return false;
        }
        return true;
    }

    /** False if the world released this mailbox. */
    private boolean markReady(){
        if(ready) return true;
        ready = true;
        return world.ready(this);
    }

    /** Released: nothing here will reach the world. Holds this. */
    private void giveUp(){
        entries.clear();
        intents = 0;
        snapshot = null;
        ready = false;
        delivered = true;
        seat.giveBack();
    }

    /**
     * From {@link WorldMailbox#release}, which already freed every seat of the epoch: drops what waits and, if the
     * leave was queued, tells the session it is delivered.
     */
    void release(){
        boolean left;
        synchronized(this){
            left = leave != null && !delivered;
            giveUp();
        }
        if(left) session.leaveDelivered();
    }

    /**
     * On the pump: moves up to a quantum of entries into {@code inbox} within the pump's budget.
     *
     * @return whether entries are left, in which case the pump keeps it in the ready queue
     */
    boolean drain(Inbox inbox, WorldMailbox.Budget budget){
        boolean left = false, more;
        synchronized(this){
            for(int moved = 0; moved < budget.quantum && !entries.isEmpty(); moved++){
                Input head = entries.peek();
                boolean control = leave != null || head instanceof Submission;
                if(budget.room <= 0 || (!control && budget.intentRoom <= 0)){
                    budget.blocked = true;
                    break;
                }
                if(!inbox.offer(head)){
                    budget.room = 0; // filled by another source, or sealed
                    budget.blocked = true;
                    break;
                }
                entries.poll();
                budget.room--;
                budget.moved++;
                if(head instanceof IntentEnvelope){
                    budget.intentRoom--;
                    intents--;
                    if(head == snapshot) snapshot = null;
                }else if(head == leave){
                    delivered = left = true;
                }
            }
            more = !entries.isEmpty();
            if(!more) ready = false;
        }
        if(left){
            seat.giveBack();
            session.leaveDelivered();
        }
        return more;
    }

    @Override
    public String toString(){
        return "mailbox(" + session + ", route " + generation + ")";
    }
}
