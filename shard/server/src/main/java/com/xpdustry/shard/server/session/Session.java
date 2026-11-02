package com.xpdustry.shard.server.session;

import com.xpdustry.shard.net.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * One client: its connection, its identity and its route to a world (ARCHITECTURE.md §7). What it may see is a
 * {@link Viewer}; what it is inside a world is a {@link PlayerEntity}. Those are separate types because a session
 * outlives both: it keeps its socket across transfers, while each world has its own player entity.
 *
 * <p>The route is the current world plus a <b>route generation</b> that only grows. Every intent goes into an
 * {@link IntentEnvelope} with the generation, a sequence that restarts at 0 on each route, and the recorded arrival
 * time, and waits in the route's bounded {@link Mailbox} until the world's {@link WorldMailbox} pumps it into the
 * inbox. The world admits it only if the session is a member under that same generation ({@link Members}). The
 * session drops packets that arrive while it is between routes, so packets from an old route never become intents for
 * the new one.
 *
 * <p>Joins and leaves are the mailbox's control entries: they never meet a full queue, so no step here fails for lack
 * of room. A leave goes in exactly once, behind the route's last intents, however busy the world is.
 *
 * <p>A route starts on a seat its destination gave ({@link WorldMailbox#takeSeat}), so a full or failed world turns a
 * join or a transfer away before anything changes. The seat belongs to the world's release epoch it was taken in: a
 * transfer whose destination failed and came back since doesn't start on it.
 *
 * <p><b>Loads.</b> Each world stream the client is sent belongs to a {@link Load}, and the {@link #loadGeneration}
 * only grows: a first join, a transfer's arrival and a {@code /sync} each start one. Starting one fences the older
 * ones: their streams not yet queued are refused ({@link #stream}), their frames not yet written are dropped
 * ({@link Conn#cancelStreams}), and only then does {@code worldDataBegin} go out. Vanilla's client loads every stream
 * it completes and confirms each load once, in order, so the confirms are matched in that order against the streams
 * the client may complete, and only the current load's confirm counts ({@link #confirm}).
 *
 * <p>{@code worldDataBegin} goes out only if the client, once it has handled what it was sent, is in a world. If it
 * still waits for a stream since its last {@code worldDataBegin} (or its connect), the new load's stream answers that
 * one: vanilla's client queues a {@code worldDataBegin} that comes while it loads ({@code Net.handleClientReceived}'s
 * {@code packetQueue}) and replays it after the next load, which wipes that world (a {@code /sync} still deflating
 * when a transfer loaded fortress left a stock client waiting until its 30 s data timeout).
 *
 * <p>A transfer, step by step (§7):
 * <ol>
 *   <li>{@link #beginTransfer}: the destination gives a seat, the source's {@link Destination#leave} closes the source
 *   mailbox (the source removes the player exactly once, at a tick boundary), the generation is bumped and the
 *   session stops making intents.</li>
 *   <li>Once the source has removed the player, the caller sends {@code worldDataBegin} and calls {@link #arrive}: the
 *   destination's mailbox opens with its {@link Destination#join} under the new generation.</li>
 *   <li>The destination streams its world; {@link #confirm} on the matching {@code connectConfirm} offers the first
 *   intent of the new route, which is where the destination activates the player.</li>
 * </ol>
 *
 * <p>{@link #close} is the first step without a destination. {@link Sessions} forgets a closed session once every
 * world it was leaving has taken its leave.
 *
 * <p>Thread-safe: network threads offer packets, world and server threads drive transfers. Each method runs under the
 * session's lock, including its mailbox offers, so sequence order is offer order. The world it made ready is woken
 * after, outside the lock ({@link WorldMailbox#wakeIfDue}): a tick thread's pump takes these locks too.
 */
public final class Session{
    private final Sessions owner;
    private final long id;
    private final Conn conn;
    private final Identity identity;
    private SessionState state = SessionState.CONNECTED;
    private Destination world, pending;
    /** The seat {@link #pending} gave, while transferring. */
    private WorldMailbox.Seat seat;
    /** The current route's mailbox, while a member. */
    private Mailbox mailbox;
    private int routeGeneration;
    private long nextSequence;
    /** Leaves queued that a world's inbox hasn't taken yet. */
    private int leaving;
    private int loadGeneration;
    /** The load the client is to confirm next, or null: none started, or fenced by a transfer or the close. */
    private Load load;
    /** Streams queued that the client may complete, oldest first: each earns one {@code connectConfirm}. */
    private final ArrayDeque<Sent> sent = new ArrayDeque<>();
    /**
     * The order of what the client is sent: each {@code worldDataBegin} and stream takes the next ordinal. The connect
     * counts as a {@code worldDataBegin} at 0; {@code loaded} is the newest stream it confirmed, -1 before any.
     */
    private long wire, lastBegin, loaded = -1;
    /** Run once, after the close; null once run. */
    private ArrayList<Runnable> onClose = new ArrayList<>();
    private volatile Viewer viewer;
    private volatile boolean admin;
    private final AtomicLong stale = new AtomicLong(), dropped = new AtomicLong(), coalesced = new AtomicLong();

    Session(Sessions owner, long id, Conn conn, Identity identity){
        this.owner = Objects.requireNonNull(owner, "owner");
        this.id = id;
        this.conn = Objects.requireNonNull(conn, "conn");
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    /** The result of offering a packet's intent. */
    public enum Offer{
        /** Queued in the route's mailbox; an input snapshot may have replaced an older one still waiting there. */
        OFFERED,
        /** Dropped: the session is between routes, not in a world yet, or its world failed. */
        STALE,
        /** Dropped: an input snapshot met a mailbox full of reliable intents. A later snapshot supersedes it. */
        DROPPED,
        /**
         * A reliable intent met a full mailbox: the session sends more than its world takes. Reliable input is never
         * dropped, so the caller must kick the session.
         */
        REFUSED,
        /** The session is closed. */
        CLOSED
    }

    /** A transfer in progress: the record the caller keeps until it calls {@link #arrive}. */
    public record Transfer(Session session, Destination from, Destination to, int generation){
    }

    /**
     * One world load: the stream of {@code world} the client is sent, and the {@code connectConfirm} it answers with.
     *
     * @param generation the session's {@link #loadGeneration} when it started
     */
    public record Load(int generation, Destination world, Kind kind){
        public enum Kind{
            /** A first route's: no {@code worldDataBegin}, the client is connecting. */
            JOIN,
            /** A transfer's arrival, after {@code worldDataBegin}. */
            TRANSFER,
            /** {@code /sync}: the same world again, after {@code worldDataBegin}, while the session stays in it. */
            SYNC
        }
    }

    /** A stream queued for {@code load}, as {@link Conn#sendStream} numbered it, and its place in {@link #wire}. */
    private record Sent(Load load, int streamId, long ordinal){
    }

    /** The server-wide session id, which intents carry and {@link Members} keys by. */
    public long id(){
        return id;
    }

    public Conn conn(){
        return conn;
    }

    public Identity identity(){
        return identity;
    }

    public synchronized SessionState state(){
        return state;
    }

    /** The world the session is a member of, or is loading; null when connected, transferring or closed. */
    public synchronized Destination world(){
        return state.member() ? world : null;
    }

    public synchronized int routeGeneration(){
        return routeGeneration;
    }

    /** The last load's generation: 0 before the first join, then one more for each load. */
    public synchronized int loadGeneration(){
        return loadGeneration;
    }

    /** The load the client is to confirm, or the last one it confirmed; null if none, or a transfer or close fenced it. */
    public synchronized Load load(){
        return load;
    }

    /** Whether the admin model admitted the session as an admin; its players in worlds carry the flag. */
    public boolean admin(){
        return admin;
    }

    public void admin(boolean admin){
        this.admin = admin;
    }

    /** The last projection of what this session may see, or null before its first sync. */
    public Viewer viewer(){
        return viewer;
    }

    /** Set by the sync layer from the session's player entity, at a tick boundary. */
    public void viewer(Viewer viewer){
        this.viewer = viewer;
    }

    /** Packets dropped because they arrived between routes. */
    public long staleDropped(){
        return stale.get();
    }

    /** Input snapshots dropped by a mailbox full of reliable intents. */
    public long fullDropped(){
        return dropped.get();
    }

    /** Input snapshots that replaced an older one still waiting in the mailbox. */
    public long coalesced(){
        return coalesced.get();
    }

    /** Whether a leave is queued that its world's inbox hasn't taken yet. */
    public synchronized boolean leaving(){
        return leaving > 0;
    }

    /**
     * First route: {@code CONNECTED -> LOADING}. Takes a seat in {@code to} and opens its mailbox with its join under a
     * new generation.
     *
     * @return false, and nothing queued, if the session isn't connected (it may have closed meanwhile) or {@code to}
     *     has no seat for it
     */
    public boolean join(Destination to){
        boolean joined;
        synchronized(this){
            if(state != SessionState.CONNECTED) return false;
            WorldMailbox.Seat seat = to.mailbox().takeSeat();
            if(seat == null) return false;
            routeGeneration++;
            joined = startRoute(to, seat, null);
        }
        to.mailbox().wakeIfDue();
        return joined;
    }

    /**
     * Starts a transfer: {@code IN_WORLD -> TRANSFERRING}. Queues the source's leave, then bumps the generation.
     *
     * @return the transfer record, or null with nothing changed if the session isn't in a world, {@code to} is its
     *     world, or {@code to} has no seat for it
     */
    public Transfer beginTransfer(Destination to){
        return beginTransfer(to, transfer -> {
        });
    }

    /**
     * {@link #beginTransfer(Destination)}, running {@code begun} with the transfer under the session's lock, before
     * the source is woken: a host records the transfer there, so the source's departure finds it.
     */
    public Transfer beginTransfer(Destination to, Consumer<Transfer> begun){
        Objects.requireNonNull(to, "to");
        Transfer transfer;
        synchronized(this){
            if(state != SessionState.IN_WORLD || to == world) return null;
            WorldMailbox.Seat seat = to.mailbox().takeSeat();
            if(seat == null) return null;
            Destination from = world;
            leave();
            // a /sync of the source still being sent is no use now
            fence();
            routeGeneration++;
            state = SessionState.TRANSFERRING;
            pending = to;
            this.seat = seat;
            transfer = new Transfer(this, from, to, routeGeneration);
            begun.accept(transfer);
        }
        // the leave
        transfer.from().mailbox().wakeIfDue();
        return transfer;
    }

    /**
     * The source has removed the player: {@code TRANSFERRING -> LOADING}. Sends {@code begin}
     * ({@code worldDataBegin}) once the older loads are fenced, unless the client still waits for a stream, then opens
     * the destination's mailbox with its join.
     *
     * @return false if the transfer is not this session's current one (it closed, or moved on), with nothing sent; or
     *     if the destination failed since, after {@code begin}: the session then waits in {@code CONNECTED} for
     *     another {@link #join}
     */
    public boolean arrive(Transfer transfer, Frame begin){
        boolean arrived;
        synchronized(this){
            if(state != SessionState.TRANSFERRING || transfer.generation() != routeGeneration || transfer.to() != pending) return false;
            Destination to = pending;
            WorldMailbox.Seat seat = this.seat;
            pending = null;
            this.seat = null;
            arrived = startRoute(to, seat, begin);
        }
        transfer.to().mailbox().wakeIfDue();
        return arrived;
    }

    /**
     * {@code /sync}: a new load of the session's world while it stays in it. Sends {@code begin}
     * ({@code worldDataBegin}) once the older loads are fenced, unless the client still waits for a stream.
     *
     * @return the load to {@link #stream}, or null with nothing sent if the session isn't in a world
     */
    public synchronized Load reload(Frame begin){
        if(state != SessionState.IN_WORLD) return null;
        return startLoad(world, Load.Kind.SYNC, begin);
    }

    /**
     * Queues {@code data} as the world stream of {@code load}, if it is still the current load: a stream deflated for
     * a load that a newer one fenced meanwhile is never sent.
     *
     * @param type the streamed packet's id ({@code WorldStream})
     * @return false if a newer load fenced it, the session closed or the connection refused the stream
     */
    public synchronized boolean stream(Load load, int type, byte[] data){
        if(state == SessionState.CLOSED || load != this.load) return false;
        int id = conn.sendStream(type, data);
        if(id < 0) return false;
        sent.add(new Sent(load, id, ++wire));
        return true;
    }

    /**
     * Queues {@code data} as a stream beside {@code load}'s world stream, if it is still the current load: one the
     * client loads no world from, so it earns no {@code connectConfirm} (the asset handshake's
     * {@code AssetRequirementStream} and {@code AssetStream}). A newer load fences it as it fences world streams.
     *
     * @param type the streamed packet's id
     * @return false if a newer load fenced it, the session closed or the connection refused the stream
     */
    public synchronized boolean streamAside(Load load, int type, byte[] data){
        if(state == SessionState.CLOSED || load != this.load) return false;
        return conn.sendStream(type, data) >= 0;
    }

    /**
     * Closes the connection with {@code kick} because {@code load}'s stream failed, if it is still the current load,
     * in one step with that check: a failure of a load a transfer or a {@code /sync} replaced leaves the session alone.
     *
     * @return whether it closed
     */
    public synchronized boolean failLoad(Load load, Frame kick, String reason){
        if(state == SessionState.CLOSED || load != this.load) return false;
        conn.closeAfter(kick, reason);
        return true;
    }

    /**
     * {@code connectConfirm}: the client loaded the oldest stream it was sent and hasn't confirmed. If that is the
     * current load's, its world's {@link Destination#confirm} intent is offered: for a join or a transfer it is the
     * route's first envelope ({@code LOADING -> IN_WORLD}); for a {@code /sync} the next intent of the route.
     * Anything else is stale: a load a newer one replaced, or a confirm with no stream to answer.
     */
    public Offer confirm(long arrivalNanos){
        Offer offer;
        Destination to;
        synchronized(this){
            if(state == SessionState.CLOSED) return Offer.CLOSED;
            Sent oldest = sent.poll();
            if(oldest != null) loaded = oldest.ordinal();
            boolean current = oldest != null && oldest.load() == load
                && (state == SessionState.LOADING || state == SessionState.IN_WORLD && load.kind() == Load.Kind.SYNC);
            if(!current){
                stale.incrementAndGet();
                return Offer.STALE;
            }
            to = world;
            offer = envelope(world.confirm(this, load), arrivalNanos, false);
            if(offer == Offer.OFFERED) state = SessionState.IN_WORLD;
        }
        to.mailbox().wakeIfDue();
        return offer;
    }

    /** A reliable packet's intent for the session's world. Only an {@code IN_WORLD} session makes intents. */
    public Offer offer(Intent intent, long arrivalNanos){
        return offer(intent, arrivalNanos, false);
    }

    /**
     * An input snapshot ({@code clientSnapshot}) for the session's world. The next one supersedes it, so it replaces
     * one still waiting in the mailbox, and a full mailbox drops it.
     */
    public Offer offerSnapshot(Intent intent, long arrivalNanos){
        return offer(intent, arrivalNanos, true);
    }

    private Offer offer(Intent intent, long arrivalNanos, boolean snapshot){
        Offer offer;
        Destination to;
        synchronized(this){
            if(state == SessionState.CLOSED) return Offer.CLOSED;
            if(state != SessionState.IN_WORLD){
                stale.incrementAndGet();
                return Offer.STALE;
            }
            to = world;
            offer = envelope(intent, arrivalNanos, snapshot);
        }
        to.mailbox().wakeIfDue();
        return offer;
    }

    /**
     * Closes the session's route: a member leaves its world, exactly once and however full its inbox is. Doesn't close
     * the connection; the caller does, or is reacting to its close. Then runs the {@link #whenClosed} actions, outside
     * the session's lock. Safe to call again.
     */
    public void close(){
        List<Runnable> actions;
        Destination left = null;
        synchronized(this){
            if(state == SessionState.CLOSED) return;
            if(state.member()){
                left = world;
                leave();
            }
            if(seat != null){
                seat.giveBack();
                seat = null;
            }
            pending = null;
            fence();
            state = SessionState.CLOSED;
            if(leaving == 0) owner.forget(this);
            actions = onClose;
            onClose = null;
        }
        if(left != null) left.mailbox().wakeIfDue();
        for(Runnable action : actions) action.run();
    }

    /**
     * Runs {@code action} once the session closes, on the closing thread, or now on this one if it has closed. Whoever
     * starts something that must end with the session (a join's record, a reservation) registers its end here, so it
     * ends whichever comes first.
     */
    public void whenClosed(Runnable action){
        Objects.requireNonNull(action, "action");
        synchronized(this){
            if(onClose != null){
                onClose.add(action);
                return;
            }
        }
        action.run();
    }

    /** From a {@link Mailbox}, after it released its lock: a world's inbox took one of this session's leaves. */
    synchronized void leaveDelivered(){
        leaving--;
        if(leaving == 0 && state == SessionState.CLOSED) owner.forget(this);
    }

    private void leave(){
        if(mailbox.seal(new Submission(world.leave(this), Cause.SERVER))) leaving++;
        mailbox = null;
        world = null;
    }

    /**
     * On a seat {@code to} gave: a new load, then the route. False if the seat's epoch ended since (the world failed):
     * the session is {@code CONNECTED} again, and the client, told {@code begin}, waits for another world.
     */
    private boolean startRoute(Destination to, WorldMailbox.Seat seat, Frame begin){
        startLoad(to, begin == null ? Load.Kind.JOIN : Load.Kind.TRANSFER, begin);
        mailbox = to.mailbox().open(this, routeGeneration, new Submission(to.join(this, routeGeneration), Cause.SERVER), seat);
        if(mailbox == null){
            fence();
            state = SessionState.CONNECTED;
            return false;
        }
        world = to;
        nextSequence = 0;
        state = SessionState.LOADING;
        return true;
    }

    /**
     * Fences the older loads, sends {@code begin} if any and the client is in a world by then, and makes a new load of
     * {@code of} current.
     */
    private Load startLoad(Destination of, Load.Kind kind, Frame begin){
        fence();
        if(begin != null && !waiting()){
            conn.sendTcp(begin);
            lastBegin = ++wire;
        }
        return load = new Load(++loadGeneration, of, kind);
    }

    /**
     * Whether the client, once it has handled what it was sent, waits for a world stream: its last
     * {@code worldDataBegin} came after every stream it will complete.
     */
    private boolean waiting(){
        return lastBegin > (sent.isEmpty() ? loaded : sent.peekLast().ordinal());
    }

    /**
     * No load is current: streams for older ones are refused, and their frames not yet written dropped. A stream
     * the client has whole still earns a confirm, so it stays in {@link #sent}.
     */
    private void fence(){
        load = null;
        if(sent.isEmpty()) return;
        int[] cancelled = conn.cancelStreams();
        if(cancelled.length == 0) return;
        sent.removeIf(queued -> {
            for(int id : cancelled){
                if(id == queued.streamId()) return true;
            }
            return false;
        });
    }

    private Offer envelope(Intent intent, long arrivalNanos, boolean snapshot){
        var envelope = new IntentEnvelope(id, routeGeneration, nextSequence, arrivalNanos, intent);
        return switch(mailbox.offer(envelope, snapshot)){
            case QUEUED -> {
                nextSequence++;
                yield Offer.OFFERED;
            }
            case COALESCED -> {
                nextSequence++;
                coalesced.incrementAndGet();
                yield Offer.OFFERED;
            }
            case DROPPED -> {
                dropped.incrementAndGet();
                yield Offer.DROPPED;
            }
            case FULL -> Offer.REFUSED;
            case RELEASED -> {
                stale.incrementAndGet();
                yield Offer.STALE;
            }
            // a member's mailbox is sealed only as its route ends, under this lock
            case SEALED -> throw new IllegalStateException(mailbox + " is sealed while " + this + " is " + state);
        };
    }

    @Override
    public String toString(){
        return "session#" + id + "(" + identity.name() + ")";
    }
}
