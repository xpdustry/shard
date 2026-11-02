package com.xpdustry.shard.server.sync;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.net.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.wire.Packet;
import com.xpdustry.shard.wire.PacketType;
import com.xpdustry.shard.wire.WireContext;
import com.xpdustry.shard.wire.PacketType.*;
import com.xpdustry.shard.wire.v160_7.Packets;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * The gate every frame a world sends its sessions goes through (ARCHITECTURE.md §8): Calls, snapshots, the world
 * stream of a join, and what plugins and other threads send ({@link #offer}). A frame goes to an {@link Audience};
 * the Outbox encodes a packet once and hands the same immutable frame to every viewer in it, over TCP or UDP as
 * vanilla sends that packet.
 *
 * <p>Each member session has a seat, which follows its {@link Session.Load} (ARCHITECTURE.md §7, join protocol):
 * <ol>
 *   <li><b>Waiting</b>, from its route's start (or a {@code /sync}) to its world stream's capture: it gets nothing,
 *   since everything that happens until then is in the capture.</li>
 *   <li><b>Loading</b>, from the capture ({@link #capture}) to the {@code connectConfirm} of that load
 *   ({@link #confirmed}). Its normal-priority frames wait in a bounded {@link JoinBuffer} keyed by the load's
 *   generation; high-priority ones (kicks) go out at once; low-priority ones (snapshots) are dropped, as a loading
 *   client drops them ({@code Net.java:325}). A buffer that overflows tells the {@link Listener}, which disconnects the
 *   session or starts a new load.</li>
 *   <li><b>Playing</b>, from that confirm: the buffer is flushed in order, before anything else reaches it, and
 *   frames go straight out. A confirm of an older load changes nothing.</li>
 * </ol>
 * The server holds the frames rather than trusting the client's own queue: a stock client does queue normal-priority
 * packets while it loads ({@code Net.java:150}), but only those that arrive after its {@code worldDataBegin}, and a
 * held buffer belongs to exactly one load, so a load a newer one replaced takes its frames with it.
 *
 * <p><b>Order on the wire (Q215).</b> Every frame a tick sends a session, reliable or not, goes into its
 * connection's one queue in the order of the calls here, and the connection hands them to the kernel in that order:
 * a tick's Calls leave before its snapshots. {@link #send} only queues; {@link #flush}, once at the end of the tick,
 * wakes each connection that got frames, so no tick thread waits on a socket or wakes a writer per frame.
 *
 * <p><b>Other threads</b> send through {@link #offer}, which is bounded per world: at most {@link #OFFERED_FRAMES}
 * frames and {@link #OFFERED_BYTES} bytes wait for the tick thread. Past either, the newest offer is refused (counted
 * in {@link Stats#refused()}): the frames already offered keep their order, and a flood from a plugin or the console
 * can't grow the heap while the world is stalled.
 *
 * <p>Tick thread only, except {@link #offer} and {@link #stream}.
 */
public final class Outbox{
    private static final System.Logger LOG = System.getLogger(Outbox.class.getName());

    /** What the host does about a seat whose join buffer overflowed. On the tick thread. */
    public interface Listener{
        void overflowed(Session session, int load);
    }

    /** How frames reach a session's connection. Tests record them instead. */
    public interface Transmit{
        /** Over its connection: TCP and UDP as vanilla sends the packet. */
        Transmit CONNECTIONS = new Transmit(){
            @Override
            public void send(Session session, Frame frame, boolean reliable){
                session.conn().queue(frame, reliable);
            }

            @Override
            public void flush(Session session){
                session.conn().flush();
            }

            @Override
            public boolean stream(Session session, Session.Load load, byte[] stream){
                return session.stream(load, Packets.WORLD_STREAM, stream);
            }
        };

        void send(Session session, Frame frame, boolean reliable);

        /** The end of a tick: what {@link #send} queued for {@code session} may go out now. */
        default void flush(Session session){
        }

        /** {@code load}'s world stream, unless a newer load fenced it ({@link Session#stream}). Any thread. */
        boolean stream(Session session, Session.Load load, byte[] stream);
    }

    /** A seat's stage in its load. */
    public enum Stage{
        WAITING,
        LOADING,
        PLAYING
    }

    private static final class Seat{
        final Session session;
        int team = -1;
        /**
         * The session's player at the last {@link #teams}, kept while it is added: a session's roster entry changes only
         * by removing its player ({@code HostInputs.Join} replaces a stale one, {@code Leave} removes it).
         */
        Player player;
        Stage stage = Stage.WAITING;
        /** The load generation of the last capture, or -1. */
        int load = -1;
        /** Null unless loading; null too once it overflowed. */
        JoinBuffer buffer;
        /** Frames were sent to it since the last {@link #flush}. */
        boolean unflushed;

        Seat(Session session){
            this.session = session;
        }
    }

    private record Offered(Audience audience, Frame frame){
    }

    private final SyncSettings settings;
    private final Listener listener;
    private final Transmit transmit;
    /** By session id, so every send walks the viewers in one order. */
    private final TreeMap<Long, Seat> seats = new TreeMap<>();
    /** Frames other threads offered, with their count and bytes, which {@link #offer} reserves before adding. */
    private final ConcurrentLinkedQueue<Offered> offered = new ConcurrentLinkedQueue<>();
    private final AtomicInteger offeredFrames = new AtomicInteger();
    private final AtomicLong offeredBytes = new AtomicLong();
    private final int maxOfferedFrames;
    private final long maxOfferedBytes;
    /** Seats {@link #transmit} sent to since the last {@link #flush}, in that order. */
    private final ArrayList<Seat> unflushed = new ArrayList<>();
    /** Seats in {@link Stage#PLAYING}. */
    private int playing;
    /** Where {@link #send} encodes, on the tick thread: a frame copies what it needs. */
    private final Writes encoding = new Writes(1024);
    private WireContext context = Calls.NO_WORLD;
    private final Stats stats = new Stats();

    public Outbox(SyncSettings settings, Listener listener){
        this(settings, listener, Transmit.CONNECTIONS);
    }

    public Outbox(SyncSettings settings, Listener listener, Transmit transmit){
        this(settings, listener, transmit, OFFERED_FRAMES, OFFERED_BYTES);
    }

    /** With other bounds on {@link #offer}: tests. */
    Outbox(SyncSettings settings, Listener listener, Transmit transmit, int maxOfferedFrames, long maxOfferedBytes){
        this.settings = settings;
        this.listener = Objects.requireNonNull(listener, "listener");
        this.transmit = Objects.requireNonNull(transmit, "transmit");
        if(maxOfferedFrames <= 0 || maxOfferedBytes <= 0) throw new IllegalArgumentException("offer bounds must be positive");
        this.maxOfferedFrames = maxOfferedFrames;
        this.maxOfferedBytes = maxOfferedBytes;
    }

    /**
     * Frames {@link #offer} holds for the tick thread at most. A tick drains them all, so this is what other threads
     * may send a world in one tick: 1,024 is a chat line to each of 30 players thirty times over.
     */
    public static final int OFFERED_FRAMES = 1024;
    /** Bytes {@link #offer} holds at most: four times a connection's write queue cap. */
    public static final long OFFERED_BYTES = 1 << 20;

    /** Counters since the Outbox was made, readable from any thread. */
    public static final class Stats{
        final LongAdder frames = new LongAdder(), bytes = new LongAdder(), held = new LongAdder(), flushed = new LongAdder(),
            dropped = new LongAdder(), overflows = new LongAdder(), staleStreams = new LongAdder(), refused = new LongAdder();

        /** Frames handed to connections, counting each recipient. */
        public long frames(){
            return frames.sum();
        }

        public long bytes(){
            return bytes.sum();
        }

        /** Frames held for a loading viewer. */
        public long held(){
            return held.sum();
        }

        /** Held frames sent on a confirm. */
        public long flushed(){
            return flushed.sum();
        }

        /** Low-priority frames a loading viewer would have dropped, so never sent. */
        public long dropped(){
            return dropped.sum();
        }

        public long overflows(){
            return overflows.sum();
        }

        /** Frames {@link #offer} refused because the offered frames were at their bound. */
        public long refused(){
            return refused.sum();
        }

        /** World streams not sent because a newer load of the session had begun. */
        public long staleStreams(){
            return staleStreams.sum();
        }
    }

    public Stats stats(){
        return stats;
    }

    /** The world's {@link WireContext} for this tick's encodings: set by the sync layer before it sends. */
    public void context(WireContext context){
        this.context = Objects.requireNonNull(context, "context");
    }

    // ---- seats

    /** A session's route here began: it waits for its world stream's capture. */
    public void member(Session session){
        Seat replaced = seats.put(session.id(), new Seat(session));
        if(replaced != null) stage(replaced, Stage.WAITING);
    }

    /** The session's route here ended: it gets nothing more. */
    public void left(long session){
        Seat seat = seats.remove(session);
        if(seat != null) stage(seat, Stage.WAITING);
    }

    /** Every seat is gone: the world was replaced or failed. */
    public void clear(){
        flush();
        seats.clear();
        for(Offered next; (next = offered.poll()) != null; ) release(next.frame());
        playing = 0;
    }

    /** Moves {@code seat} to {@code stage}, counting the playing seats. */
    private void stage(Seat seat, Stage stage){
        if(seat.stage == Stage.PLAYING) playing--;
        if(stage == Stage.PLAYING) playing++;
        seat.stage = stage;
    }

    /**
     * The world stream of the session's load {@code load} (its generation) is being captured at this tick boundary:
     * its frames from now on are held for that load, and an older load's are dropped.
     */
    public void capture(Session session, int load){
        Seat seat = seats.computeIfAbsent(session.id(), id -> new Seat(session));
        stage(seat, Stage.LOADING);
        seat.load = load;
        seat.buffer = new JoinBuffer(load, settings.joinBufferFrames(), settings.joinBufferBytes());
    }

    /**
     * Sends {@code load}'s world stream, unless a newer load fenced it meanwhile. Any thread: streams are deflated off
     * the tick thread.
     *
     * @return whether it was queued
     */
    public boolean stream(Session session, Session.Load load, byte[] stream){
        if(!transmit.stream(session, load, stream)){
            stats.staleStreams.increment();
            return false;
        }
        stats.frames.increment();
        stats.bytes.add(stream.length);
        return true;
    }

    /**
     * {@code /sync}: the session's client reloads the world ({@code worldDataBegin} is the session's to send, with
     * {@link Session#reload}); until its next capture it gets nothing.
     */
    public void reloading(Session session){
        Seat seat = seats.get(session.id());
        if(seat == null) return;
        stage(seat, Stage.WAITING);
        seat.buffer = null;
    }

    /**
     * The client confirmed load {@code load}: if it is the seat's current one, its held frames go out in order and it
     * plays. A confirm of an older load (or of one never captured here) changes nothing.
     *
     * @return the number of frames flushed, or -1 if the confirm was stale
     */
    public int confirmed(long session, int load){
        Seat seat = seats.get(session);
        if(seat == null) return -1;
        if(seat.stage == Stage.PLAYING) return 0;
        if(seat.stage != Stage.LOADING || seat.load != load) return -1;
        int flushed = 0;
        if(seat.buffer != null){
            for(Frame frame : seat.buffer.frames()) transmit(seat, frame, type(frame));
            flushed = seat.buffer.frames().size();
            stats.flushed.add(flushed);
            if(flushed > 0) LOG.log(System.Logger.Level.INFO, "session " + session + ", load " + load + ": " + flushed + " frames held while it loaded went out at its confirm");
        }
        seat.buffer = null;
        stage(seat, Stage.PLAYING);
        return flushed;
    }

    /** The team of the session's player, for team audiences. */
    public void team(long session, int team){
        Seat seat = seats.get(session);
        if(seat != null) seat.team = team;
    }

    /**
     * Each seat's team from its session's player in {@code roster}, as {@link #team} for every entry would set it: team
     * audiences see these until the next call. A seat whose session has no player keeps its team. The roster is read
     * again only for seats whose player isn't added (joining, or gone).
     */
    public void teams(SortedMap<Long, Player> roster){
        for(Seat seat : seats.values()){
            Player player = seat.player;
            if(player == null || !player.isAdded()){
                player = roster.get(seat.session.id());
                if(player == null) continue;
                seat.player = player;
            }
            seat.team = player.team;
        }
    }

    public Stage stage(long session){
        Seat seat = seats.get(session);
        return seat == null ? null : seat.stage;
    }

    /** The playing sessions, by id. */
    public List<Session> playing(){
        var out = new ArrayList<Session>(seats.size());
        for(Seat seat : seats.values()) if(seat.stage == Stage.PLAYING) out.add(seat.session);
        return out;
    }

    /** The teams that have a playing session, ascending: one filtered snapshot each when fog is on. */
    public SortedSet<Integer> playingTeams(){
        var out = new TreeSet<Integer>();
        for(Seat seat : seats.values()) if(seat.stage == Stage.PLAYING) out.add(seat.team);
        return out;
    }

    public boolean anyPlaying(){
        return playing > 0;
    }

    // ---- sending

    /** Encodes {@code packet} once, against this tick's world context, and sends it to {@code audience}. */
    public void send(Audience audience, Packet packet){
        if(seats.isEmpty()) return;
        PacketType type = Packets.TYPES.get(packet.packetId());
        Frame frame = null;
        for(Seat seat : seats.values()){
            if(!audience.includes(seat.session.id(), seat.team)) continue;
            if(frame == null) frame = Calls.frame(packet, context, encoding);
            route(seat, frame, type);
        }
    }

    /** Sends an encoded game packet to {@code audience}. */
    public void send(Audience audience, Frame frame){
        PacketType type = type(frame);
        for(Seat seat : seats.values()){
            if(audience.includes(seat.session.id(), seat.team)) route(seat, frame, type);
        }
    }

    /**
     * Queues a frame for {@code audience} from any thread (plugins, the console, command replies): it goes out on the
     * world's tick thread after the next tick's Calls, before its snapshots. Refused, and counted, when the frames
     * already offered are at {@link #OFFERED_FRAMES} or would go over {@link #OFFERED_BYTES}.
     *
     * @return false if it was refused
     */
    public boolean offer(Audience audience, Frame frame){
        Objects.requireNonNull(audience, "audience");
        if(offeredFrames.incrementAndGet() > maxOfferedFrames){
            offeredFrames.decrementAndGet();
            return refuse();
        }
        if(offeredBytes.addAndGet(frame.size()) > maxOfferedBytes){
            offeredBytes.addAndGet(-frame.size());
            offeredFrames.decrementAndGet();
            return refuse();
        }
        offered.add(new Offered(audience, frame));
        return true;
    }

    private boolean refuse(){
        stats.refused.increment();
        return false;
    }

    private void release(Frame frame){
        offeredFrames.decrementAndGet();
        offeredBytes.addAndGet(-frame.size());
    }

    /** Sends what other threads offered. Tick thread. */
    public void drainOffered(){
        long before = stats.refused.sum();
        for(Offered next; (next = offered.poll()) != null; ){
            release(next.frame());
            send(next.audience(), next.frame());
        }
        if(refusedSeen != before){
            LOG.log(System.Logger.Level.WARNING, (before - refusedSeen) + " offered frames refused: other threads offered more than "
                + maxOfferedFrames + " frames or " + maxOfferedBytes + " B in one tick");
            refusedSeen = before;
        }
    }

    /** {@link Stats#refused()} at the last warning. Tick thread. */
    private long refusedSeen;

    /**
     * The end of the tick: every connection {@link #send} queued frames for since the last flush may write them now.
     * Tick thread; the host calls it after the tick's last send.
     */
    public void flush(){
        for(int i = 0; i < unflushed.size(); i++){
            Seat seat = unflushed.get(i);
            seat.unflushed = false;
            transmit.flush(seat.session);
        }
        unflushed.clear();
    }

    /**
     * A connection-level frame for one session, whatever its stage: {@code worldDataBegin}, a high-priority reply. It
     * isn't held.
     */
    public void direct(Session session, Frame frame){
        transmit.send(session, frame, type(frame).reliable());
        transmit.flush(session);
        stats.frames.increment();
        stats.bytes.add(frame.size());
    }

    private void route(Seat seat, Frame frame, PacketType type){
        switch(seat.stage){
            case WAITING -> {
            }
            case PLAYING -> transmit(seat, frame, type);
            case LOADING -> {
                if(type.priority() == Priority.high){
                    transmit(seat, frame, type);
                }else if(type.priority() == Priority.low){
                    stats.dropped.increment();
                }else if(seat.buffer == null){
                    // overflowed: the listener is dealing with it
                }else if(seat.buffer.add(frame)){
                    stats.held.increment();
                }else{
                    // the buffer can't hold the load's changes: it is no use any more
                    seat.buffer = null;
                    stats.overflows.increment();
                    listener.overflowed(seat.session, seat.load);
                }
            }
        }
    }

    private void transmit(Seat seat, Frame frame, PacketType type){
        transmit.send(seat.session, frame, type.reliable());
        if(!seat.unflushed){
            seat.unflushed = true;
            unflushed.add(seat);
        }
        stats.frames.increment();
        stats.bytes.add(frame.size());
    }

    private static PacketType type(Frame frame){
        int id = frame.id();
        if(id < 0 || id >= Packets.TYPES.size()) throw new IllegalArgumentException("not a game packet: " + frame);
        return Packets.TYPES.get(id);
    }
}
