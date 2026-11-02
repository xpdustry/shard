package com.xpdustry.shard.net;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

/**
 * One client connection: its TCP socket, its registered UDP address and a writer queue drained by its own virtual
 * thread (note 24 §1.3). Every method is safe from any thread, and none blocks on the network: a world thread queues
 * a {@link Frame} and moves on.
 *
 * <p><b>Order (Q215).</b> Reliable and unreliable frames share one queue, and the writer hands them to the kernel in
 * the order they were queued: the TCP bytes before a datagram are written before it is sent. So a tick's Calls leave
 * before that tick's snapshots, as they do on vanilla when its write buffer is empty ({@code TcpConnection.java:200}
 * writes at once; {@code UdpConnection} always does). What happens past the kernel is the network's: two protocols on
 * two sockets, and a stock client reads whichever its selector lists first ({@code Client.java:246-295}).
 *
 * <p><b>Waking the writer.</b> {@link #sendTcp} and {@link #sendUdp} wake it at once. {@link #queue} doesn't, until
 * {@link #WAKE_BYTES} wait; a world queues a whole tick and then calls {@link #flush} once, so a usual tick's frames
 * cost one wake and, between datagrams, one gathering write. The sweeper flushes a queue that was left unflushed.
 *
 * <p>Backpressure (W4.11): reliable frames may go over {@link Transport.Config#maxQueuedBytes()}, the soft cap, for
 * {@link Transport.Config#overCapGraceMillis()}: a tick's burst over it that the writer drains in time costs nothing.
 * The connection closes as a slow client when its queue stays over the soft cap past the grace (the sweeper checks
 * {@link #overCapNanos}), at once when a frame would take it over {@link Transport.Config#hardMaxQueuedBytes()}, the
 * ceiling that bounds its memory, and when one write blocks for longer than {@link Transport.Config#writeStallMillis()}.
 * Reliable frames are never coalesced or dropped while it is open. Unreliable frames may hold at most
 * {@link Transport.Config#maxQueuedUdpBytes()}; a datagram past that is dropped, as a full socket buffer would.
 */
public final class Conn{
    private final Transport transport;
    private final int id;
    private final SocketChannel channel;
    private final InetSocketAddress remote;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile String closeReason;
    private volatile InetSocketAddress udpRemote;
    private volatile Object attachment;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition ready = lock.newCondition();
    /** Guarded by {@link #lock}: reliable and unreliable frames in send order; then paced stream frames. */
    private final FrameRing queue = new FrameRing();
    private final ArrayDeque<StreamFrame> stream = new ArrayDeque<>();
    /** Guarded by {@link #lock}: the bytes queued or being written, per kind. */
    private long queuedBytes, udpBytes, streamBytes;
    /**
     * Nonzero while the reliable bytes are over the soft cap: when they went over it. Written under {@link #lock}; the
     * sweeper reads it without.
     */
    volatile long overCapNanos;
    /** Guarded by {@link #lock}: frames were {@linkplain #queue queued} since the writer was last woken, and their bytes. */
    private boolean unflushed;
    private long unflushedBytes;
    /** Guarded by {@link #lock}: how often a send or a flush woke the writer. Tests read it ({@link #wakes}). */
    private long wakes;
    /**
     * {@link #queue} wakes the writer anyway once this many bytes wait for a flush: a tick that sends a lot (5,000 chat
     * lines in LifecycleTest) is written while it sends, as before, rather than counted whole against the queue cap.
     */
    static final int WAKE_BYTES = 32 * 1024;
    /** The writer's: frames are copied here to be written or sent, so no send allocates. Grown on demand. */
    private ByteBuffer scratch;
    /** Guarded by {@link #lock}: set by {@link #closeAfter}, the reason to close once the queue is written. */
    private String drainReason;
    volatile long drainStartNanos;

    final long acceptedNanos;
    volatile long lastReadNanos, lastWriteNanos;
    /** Nonzero while the reader holds an incomplete TCP frame: when its first bytes arrived. */
    volatile long partialFrameNanos;
    /** Nonzero while a write is blocked in the kernel: when it started. */
    volatile long writeStartNanos;

    /** Guarded by itself. */
    private final TokenBucket all;
    private final TokenBucket[] perId = new TokenBucket[256];

    Conn(Transport transport, int id, SocketChannel channel) throws IOException{
        this.transport = transport;
        this.id = id;
        this.channel = channel;
        this.remote = (InetSocketAddress)channel.getRemoteAddress();
        long now = transport.nanoTime();
        acceptedNanos = lastReadNanos = lastWriteNanos = now;
        all = transport.config().limits().all().bucket(now);
    }

    /** The Arc connection id, sent in {@code RegisterTCP}. Arc draws it at random, so it may be negative. */
    public int id(){
        return id;
    }

    public InetSocketAddress remote(){
        return remote;
    }

    /** The registered UDP address, or null before {@code RegisterUDP}. */
    public InetSocketAddress udpRemote(){
        return udpRemote;
    }

    /** Whether both TCP and UDP are registered, so game packets may flow. */
    public boolean registered(){
        return udpRemote != null;
    }

    public boolean isOpen(){
        return !closed.get();
    }

    /** Why it closed, or null while open. */
    public String closeReason(){
        return closeReason;
    }

    /** Opaque state of the layer above (the session), or null. */
    public Object attachment(){
        return attachment;
    }

    /**
     * Attaches the layer above's state, once: the end of the handshake, which must come within
     * {@link Transport.Config#handshakeTimeoutMillis()} of accept. Atomic with {@link #close}: it fails once the
     * connection is closed, and an attachment that succeeds is always seen by {@link Transport.Handler#disconnected},
     * so whoever attaches knows the disconnect will clean it up.
     *
     * @return false, and nothing attached, if the connection is closed or already has an attachment
     */
    public boolean attach(Object attachment){
        Objects.requireNonNull(attachment, "attachment");
        lock.lock();
        try{
            if(closed.get() || this.attachment != null) return false;
            this.attachment = attachment;
            return true;
        }finally{
            lock.unlock();
        }
    }

    /** Reliable bytes queued or being written, streams excluded. */
    public long queuedBytes(){
        lock.lock();
        try{
            return queuedBytes;
        }finally{
            lock.unlock();
        }
    }

    /** Unreliable bytes queued or being sent. */
    long queuedUdpBytes(){
        lock.lock();
        try{
            return udpBytes;
        }finally{
            lock.unlock();
        }
    }

    /**
     * Queues a reliable frame and wakes the writer. Never blocks. Closes the connection as a slow client if the queue
     * would go over its hard ceiling; over the soft cap it starts the grace the sweeper times.
     *
     * @return false if the connection is closed, or closed because of this frame
     */
    public boolean sendTcp(Frame frame){
        return enqueue(frame, true, true);
    }

    /**
     * Queues an unreliable frame and wakes the writer: a datagram, or a TCP frame until {@code RegisterUDP} completes.
     * Never blocks. A datagram over the UDP cap, or whose send fails, is dropped and the connection kept.
     *
     * @return false if the connection is closed, or the datagram was dropped
     */
    public boolean sendUdp(Frame frame){
        return enqueue(frame, false, true);
    }

    /**
     * {@link #sendTcp} ({@code reliable}) or {@link #sendUdp}, without waking the writer: it writes the frame after the
     * next {@link #flush}, or after any later send that wakes it. A world queues its tick this way.
     */
    public boolean queue(Frame frame, boolean reliable){
        return enqueue(frame, reliable, false);
    }

    /** Wakes the writer for what {@link #queue} left. Cheap when nothing is left. */
    public void flush(){
        lock.lock();
        try{
            if(unflushed){
                unflushed = false;
                unflushedBytes = 0;
                wakes++;
                ready.signal();
            }
        }finally{
            lock.unlock();
        }
    }

    /** How often a send or a flush has woken the writer. */
    long wakes(){
        lock.lock();
        try{
            return wakes;
        }finally{
            lock.unlock();
        }
    }

    /** Whether frames wait for a {@link #flush}. */
    boolean unflushed(){
        lock.lock();
        try{
            return unflushed;
        }finally{
            lock.unlock();
        }
    }

    private boolean enqueue(Frame frame, boolean reliable, boolean wake){
        // before RegisterUDP an unreliable frame goes over TCP, as Arc's sendUDP can't (Connection.java:108-111)
        boolean udp = !reliable && udpRemote != null;
        Transport.Config config = transport.config();
        // a reliable frame is refused only at the ceiling: the soft cap is the sweeper's, after the grace
        long max = udp ? config.maxQueuedUdpBytes() : config.hardMaxQueuedBytes();
        boolean overflow = false;
        lock.lock();
        try{
            if(closed.get() || drainReason != null) return false;
            long held = udp ? udpBytes : queuedBytes;
            if(held + frame.size() > max){
                overflow = true;
            }else{
                queue.add(frame, udp);
                if(udp){
                    udpBytes += frame.size();
                }else{
                    queuedBytes += frame.size();
                    // over the soft cap: the grace runs from here until the writer takes it back under
                    if(queuedBytes > config.maxQueuedBytes() && overCapNanos == 0) overCapNanos = transport.nanoTime() | 1;
                }
                unflushedBytes += frame.size();
                if(wake || unflushedBytes >= WAKE_BYTES){
                    unflushed = false;
                    unflushedBytes = 0;
                    wakes++;
                    ready.signal();
                }else{
                    unflushed = true;
                }
            }
        }finally{
            lock.unlock();
        }
        if(overflow){
            if(udp){
                transport.stats().udpShed.increment();
            }else{
                tryClose("slow client: write queue over the " + max + " B ceiling", transport.stats().slowClients);
            }
            return false;
        }
        if(!udp){
            transport.stats().tcpOut.increment();
            transport.stats().tcpBytesOut.add(frame.size());
        }
        return true;
    }

    /**
     * Sends {@code data} as a stream: {@code StreamBegin}, then {@code StreamChunk}s of
     * {@link Transport.Config#streamChunk()} bytes. Stream frames go out only while no reliable frame waits, so a
     * world stream never holds snapshots back or trips the queue cap (Arc's {@code InputStreamSender} sends while
     * idle the same way). Frames queued before this call are written before {@code StreamBegin}; frames queued after
     * it may overtake the chunks, as in vanilla, so a caller that sends something the stream must not follow
     * ({@code worldDataBegin}) {@linkplain #cancelStreams cancels} it first.
     *
     * @param type the packet id of the streamed packet ({@code WorldStream} is 2)
     * @return the stream id, or -1 if the connection is closed or closing, or the stream would go over its cap
     */
    public int sendStream(int type, byte[] data){
        int chunk = transport.config().streamChunk();
        long bytes = streamSize(data.length, chunk), max = transport.config().maxStreamBytes();
        // reserve before copying, so a refused or oversized stream allocates nothing
        boolean overflow = false;
        lock.lock();
        try{
            if(closed.get() || drainReason != null) return -1;
            if(bytes > max - streamBytes){
                overflow = true;
            }else{
                streamBytes += bytes;
            }
        }finally{
            lock.unlock();
        }
        if(overflow){
            tryClose("slow client: streams over " + max + " B", transport.stats().slowClients);
            return -1;
        }
        boolean queued = false;
        int streamId = transport.nextStreamId();
        int count = Math.toIntExact(Math.ceilDiv((long)data.length, chunk));
        try{
            var frames = new ArrayList<StreamFrame>(1 + count);
            frames.add(new StreamFrame(streamId, Frame.streamBegin(streamId, data.length, type)));
            for(int off = 0; off < data.length; off += chunk){
                frames.add(new StreamFrame(streamId, Frame.streamChunk(streamId, data, off, Math.min(chunk, data.length - off))));
            }
            lock.lock();
            try{
                // closeAfter or close may have come while we copied
                if(closed.get() || drainReason != null) return -1;
                stream.addAll(frames);
                queued = true;
                ready.signal();
            }finally{
                lock.unlock();
            }
            transport.stats().tcpOut.add(frames.size());
            transport.stats().tcpBytesOut.add(bytes);
            return streamId;
        }finally{
            if(!queued){
                lock.lock();
                try{
                    streamBytes -= bytes;
                }finally{
                    lock.unlock();
                }
            }
        }
    }

    /**
     * The bytes {@link #sendStream} queues for {@code length} bytes in chunks of {@code chunk}: {@code StreamBegin}
     * (a 9-byte payload), then one {@code StreamChunk} (6 bytes plus the data) per chunk, each with the 2-byte length
     * and the {@link Frame#HEADER}. In longs, so no length overflows it.
     */
    static long streamSize(int length, int chunk){
        if(length < 0 || chunk <= 0) throw new IllegalArgumentException("length " + length + ", chunk " + chunk);
        long chunks = Math.ceilDiv((long)length, chunk);
        long frame = 2 + Frame.HEADER;
        return Math.addExact(Math.addExact(frame + 9, Math.multiplyExact(chunks, frame + 6)), length);
    }

    /**
     * Drops the stream frames not yet written. A stream the client has whole stays sent; one it has only a part of
     * never completes (Arc's client keeps the part until it reconnects, and nothing else). The frame being written
     * goes out whole.
     *
     * @return the ids of the streams that lost frames, oldest first
     */
    public int[] cancelStreams(){
        lock.lock();
        try{
            return dropStreams();
        }finally{
            lock.unlock();
        }
    }

    /** Holds {@link #lock}. */
    private int[] dropStreams(){
        if(stream.isEmpty()) return new int[0];
        int[] ids = new int[4];
        int n = 0;
        // frames the writer holds, and streams still being copied, release their own bytes
        for(StreamFrame frame : stream){
            streamBytes -= frame.frame().size();
            if(n == 0 || ids[n - 1] != frame.streamId()){
                if(n == ids.length) ids = Arrays.copyOf(ids, n * 2);
                ids[n++] = frame.streamId();
            }
        }
        stream.clear();
        return Arrays.copyOf(ids, n);
    }

    /** A stream's frame and the stream it belongs to. */
    private record StreamFrame(int streamId, Frame frame){
    }

    /**
     * Queues {@code last} (a kick), then closes once it and everything queued before it is written; pending streams
     * are dropped and later frames refused. A client too slow to take it is closed after the write-stall limit.
     */
    public void closeAfter(Frame last, String reason){
        lock.lock();
        try{
            if(closed.get() || drainReason != null) return;
            queue.add(last, false);
            queuedBytes += last.size();
            dropStreams();
            drainReason = reason;
            drainStartNanos = transport.nanoTime();
            ready.signal();
        }finally{
            lock.unlock();
        }
    }

    /** The reason given to {@link #closeAfter}, or null. */
    String drainReason(){
        lock.lock();
        try{
            return drainReason;
        }finally{
            lock.unlock();
        }
    }

    /** Closes the connection once; the handler hears {@code reason}. Safe to call again or from any thread. */
    public void close(String reason){
        tryClose(reason);
    }

    /** {@link #close}, true if this call is the one that closed it. */
    boolean tryClose(String reason){
        return tryClose(reason, null);
    }

    /**
     * {@link #tryClose(String)}, counting the close in {@code counter} (a {@link NetStats} field) when this call is the
     * one that closed it. The count comes before the handler hears of the close, so whoever reacts to
     * {@link Transport.Handler#disconnected} sees it.
     */
    boolean tryClose(String reason, LongAdder counter){
        lock.lock();
        try{
            // under the lock, so attach() lands either before this (and disconnected sees it) or not at all
            if(!closed.compareAndSet(false, true)) return false;
            closeReason = reason;
            queue.clear();
            stream.clear();
            unflushed = false;
            unflushedBytes = 0;
            ready.signalAll();
        }finally{
            lock.unlock();
        }
        if(counter != null) counter.increment();
        try{
            channel.close();
        }catch(IOException ignored){
        }
        transport.closed(this, reason);
        return true;
    }

    void registerUdp(InetSocketAddress address){
        udpRemote = address;
    }

    int channelRead(ByteBuffer buffer) throws IOException{
        return channel.read(buffer);
    }

    /**
     * Takes a token for a message with this id byte; false drops the message. A limit whose action
     * {@linkplain RateLimits.Action#closes closes} closes the connection; a {@link RateLimits.Action#BLACKLIST} one tells
     * the handler first, so the address is refused before the disconnect is heard.
     */
    boolean admit(byte idByte, long now){
        RateLimits limits = transport.config().limits();
        RateLimits.Limit broken = null;
        synchronized(perId){
            if(!all.tryTake(now)){
                broken = limits.all();
            }else if(idByte != Frame.FRAMEWORK){
                int id = idByte & 0xff;
                RateLimits.Limit limit = limits.of(id);
                if(limit != null){
                    if(perId[id] == null) perId[id] = limit.bucket(now);
                    if(!perId[id].tryTake(now)) broken = limit;
                }
            }
        }
        if(broken == null) return true;
        if(broken.action().closes()){
            if(broken.action() == RateLimits.Action.BLACKLIST && isOpen()) transport.blacklisted(this);
            tryClose(idByte == Frame.FRAMEWORK || broken == limits.all() ? "packet spam" : "packet spam: id " + (idByte & 0xff),
                transport.stats().rateClosed);
        }else{
            transport.stats().rateDropped.increment();
        }
        return false;
    }

    /**
     * The writer virtual thread. Takes queued frames in order and copies runs of TCP frames into {@link #scratch}, one
     * write each; before a datagram it writes what it has gathered, so the kernel gets every frame in queue order.
     * Stream frames go out only when the queue is empty.
     */
    void writeLoop(){
        // a wake takes up to 256 frames: a burst of small Calls is a few writes, not one per 64
        Frame[] batch = new Frame[256];
        boolean[] udp = new boolean[256];
        try{
            while(true){
                int n = 0;
                long tcpBytes = 0, udpTaken = 0;
                boolean streaming;
                String drained = null;
                lock.lock();
                try{
                    while(queue.isEmpty() && stream.isEmpty() && drainReason == null && !closed.get()) ready.await();
                    if(closed.get()) return;
                    if(queue.isEmpty() && drainReason != null) drained = drainReason;
                    streaming = queue.isEmpty();
                    if(drained != null){
                        // nothing more to write
                    }else if(streaming){
                        Frame frame = stream.poll().frame();
                        // streams are TCP only: the flag slot may still hold an earlier batch's datagram (review 43)
                        udp[n] = false;
                        batch[n++] = frame;
                        tcpBytes += frame.size();
                    }else{
                        // what is taken now is written now: a later flush needn't wake us for it
                        unflushed = false;
                        unflushedBytes = 0;
                        while(n < batch.length && !queue.isEmpty()){
                            udp[n] = queue.headUdp();
                            Frame frame = queue.poll();
                            batch[n++] = frame;
                            if(udp[n - 1]) udpTaken += frame.size();
                            else tcpBytes += frame.size();
                        }
                    }
                }finally{
                    lock.unlock();
                }
                if(drained != null){
                    close(drained);
                    return;
                }
                writeStartNanos = transport.nanoTime() | 1;
                InetSocketAddress to = udpRemote;
                ByteBuffer out = scratch(0);
                int run = 0; // the first frame gathered in out
                for(int i = 0; i < n; i++){
                    Frame frame = batch[i];
                    if(udp[i]){
                        writeOut(batch, run, i);
                        run = i + 1;
                        out = scratch(frame.bodySize());
                        frame.putUdp(out);
                        out.flip();
                        transport.sendDatagram(out, to, frame.bodySize());
                        out.clear();
                        Transport.Tap tap = transport.tap;
                        if(tap != null) tap.sent(this, frame, true);
                    }else{
                        if(out.remaining() < frame.size()){
                            writeOut(batch, run, i);
                            run = i;
                            out = scratch(frame.size());
                        }
                        frame.putTcp(out);
                    }
                }
                writeOut(batch, run, n);
                long now = transport.nanoTime();
                writeStartNanos = 0;
                if(tcpBytes > 0){
                    lastWriteNanos = now;
                    transport.stats().tcpWrites.increment();
                }
                lock.lock();
                try{
                    if(streaming) streamBytes -= tcpBytes;
                    else queuedBytes -= tcpBytes;
                    if(overCapNanos != 0 && queuedBytes <= transport.config().maxQueuedBytes()) overCapNanos = 0;
                    udpBytes -= udpTaken;
                }finally{
                    lock.unlock();
                }
                Arrays.fill(batch, 0, n, null);
            }
        }catch(InterruptedException e){
            close("interrupted");
        }catch(IOException e){
            close("write: " + Transport.describe(e));
        }
    }

    /**
     * The writer's scratch buffer, cleared, with room for at least {@code bytes}: direct, so the channel writes it
     * without the copy it makes of a heap buffer. It grows to the largest frame (at most a 25,000 B body) and stays.
     */
    private ByteBuffer scratch(int bytes){
        ByteBuffer out = scratch;
        if(out == null || out.capacity() < bytes){
            int capacity = Math.max(SCRATCH, Integer.highestOneBit(Math.max(1, bytes - 1)) << 1);
            scratch = out = ByteBuffer.allocateDirect(capacity);
        }
        return out;
    }

    /** The first scratch buffer: a 4 KiB stream chunk and its headers fit. */
    static final int SCRATCH = 8192;

    /** Writes the TCP bytes gathered in {@link #scratch}, all of them, and clears it: {@code batch[from, to)}'s. */
    private void writeOut(Frame[] batch, int from, int to) throws IOException{
        ByteBuffer out = scratch;
        if(out == null || out.position() == 0) return;
        out.flip();
        while(out.hasRemaining()) channel.write(out);
        out.clear();
        Transport.Tap tap = transport.tap;
        if(tap != null) for(int i = from; i < to; i++) tap.sent(this, batch[i], false);
    }

    /**
     * A growable ring of frames, each marked TCP or UDP, in queue order: an {@code ArrayDeque} of records would
     * allocate one per frame. Guarded by its {@link Conn}'s lock.
     */
    static final class FrameRing{
        private Frame[] frames = new Frame[16];
        private boolean[] udp = new boolean[16];
        private int head, size;

        void add(Frame frame, boolean datagram){
            if(size == frames.length) grow();
            int at = (head + size) & (frames.length - 1);
            frames[at] = frame;
            udp[at] = datagram;
            size++;
        }

        boolean isEmpty(){
            return size == 0;
        }

        int size(){
            return size;
        }

        /** Whether the head is a datagram. */
        boolean headUdp(){
            return udp[head];
        }

        Frame poll(){
            if(size == 0) return null;
            Frame frame = frames[head];
            frames[head] = null;
            head = (head + 1) & (frames.length - 1);
            size--;
            return frame;
        }

        void clear(){
            Arrays.fill(frames, null);
            head = size = 0;
        }

        private void grow(){
            int length = frames.length;
            var nextFrames = new Frame[length * 2];
            var nextUdp = new boolean[length * 2];
            for(int i = 0; i < size; i++){
                nextFrames[i] = frames[(head + i) & (length - 1)];
                nextUdp[i] = udp[(head + i) & (length - 1)];
            }
            frames = nextFrames;
            udp = nextUdp;
            head = 0;
        }
    }

    @Override
    public String toString(){
        return "conn#" + id + "@" + remote;
    }
}
