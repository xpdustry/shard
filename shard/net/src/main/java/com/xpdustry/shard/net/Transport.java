package com.xpdustry.shard.net;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * An Arc-net compatible server on one port, TCP and UDP, on virtual threads and blocking IO (ARCHITECTURE.md §7,
 * note 24 §1.2). No Netty.
 *
 * <ul>
 *   <li>{@code tcp-accept} (virtual) accepts; each {@link Conn} gets a reader and a writer virtual thread.</li>
 *   <li>{@code udp-recv} (platform) blocks in {@link DatagramChannel#receive}. Datagrams are sent by each
 *   connection's writer, in order with its TCP frames ({@link Conn}); no world thread touches a socket.</li>
 *   <li>{@code net-sweeper} (virtual) enforces Arc's 12 s read timeout, the 8 s keepalive, the write-stall limit,
 *   the grace over the reliable soft cap, and two deadlines the idle timeout can't: the handshake (a connection must be attached within 10 s of accept) and
 *   the frame (a TCP frame must complete within 5 s of its first bytes), so trickled bytes can't hold a slot.</li>
 * </ul>
 *
 * <p>The handshake is Arc's ({@code Server.java:227-230,286-300,384-404}): the server sends {@code RegisterTCP} with a
 * random id, the client sends {@code RegisterUDP(id)} over UDP until the server answers {@code RegisterUDP} over TCP,
 * and only then may game packets flow. Unlike Arc, the UDP address binds only if its IP is the TCP peer's.
 *
 * <p>The {@link Handler} runs on network threads. It decodes and routes, and never touches a world (§4).
 *
 * <p>Timers, deadlines and rate limits read one clock, {@link System#nanoTime} outside tests. A test in this package
 * binds with its own clock and runs {@link #sweep} itself, so whether a deadline passed doesn't depend on how busy the
 * machine is.
 */
public final class Transport implements AutoCloseable{
    private static final System.Logger LOG = System.getLogger("com.xpdustry.shard.net");
    /** The close reason when the client ended the connection. */
    public static final String CLIENT_CLOSED = "closed by client";

    /** Callbacks from network threads. They must be quick and must not block. */
    public interface Handler{
        /** Both TCP and UDP are registered: the connection may now send and receive game packets. */
        default void connected(Conn conn){
        }

        /** A game packet. Framework messages never reach the handler. */
        void received(Conn conn, Packet packet);

        /**
         * Called once per registered or pending connection, from whichever thread closed it, after the close. It sees
         * every attachment {@link Conn#attach} accepted, and none can be made after it.
         */
        default void disconnected(Conn conn, String reason){
        }

        /**
         * The connection broke a {@link RateLimits.Action#BLACKLIST} limit and is about to close: vanilla refuses its
         * address from now on ({@code Administration.blacklistDos}). From the thread that read the message.
         */
        default void blacklisted(Conn conn){
        }

        /**
         * A server-list ping ({@code DiscoverHost} over UDP). Returns the raw reply datagram (vanilla's
         * {@code NetworkIO.writeServerData}), or null to stay silent.
         */
        default byte[] discover(InetSocketAddress from){
            return null;
        }
    }

    /**
     * Transport settings. {@link #defaults()} is note 24's with W4.11's grace: a writer queue that may sit over 256 KiB
     * for 3 s and never over 1 MiB, Arc's timeouts, vanilla's spam limit.
     *
     * @param allowPublic whether {@link #bind} may use a non-loopback address. Only on a disposable VM.
     * @param maxConnections connections over this are closed on accept
     * @param maxQueuedBytes the reliable soft cap: a connection whose reliable bytes queued or in flight stay over it for
     *     longer than {@code overCapGraceMillis} closes as a slow client. A tick's burst may go over it and drain.
     * @param hardMaxQueuedBytes the reliable ceiling, for memory: a frame that would take the queue over it closes the
     *     connection at once. At least {@code maxQueuedBytes}.
     * @param overCapGraceMillis how long the reliable queue may stay over {@code maxQueuedBytes}; the sweeper checks it
     * @param maxQueuedUdpBytes unreliable bytes a connection may have queued or in flight; a datagram over it is dropped
     * @param maxStreamBytes stream bytes a connection may have queued before it closes
     * @param streamChunk bytes per {@code StreamChunk}; vanilla sends 1,024 and accepts up to the 25,000 B body limit
     * @param readTimeoutMillis close a connection that sent nothing over TCP for this long (Arc: 12 s)
     * @param handshakeTimeoutMillis close a connection that isn't {@linkplain Conn#attach attached} this long after
     *     accept: {@code RegisterTCP}, {@code RegisterUDP} and the layer above's own handshake (the connect packet) must
     *     all fit in it, however much else the client sends
     * @param frameTimeoutMillis close a connection whose TCP frame is still incomplete this long after its first bytes
     *     arrived, so a client can't hold a slot by trickling one long frame under the read timeout
     * @param keepAliveMillis send a keepalive after this long without a TCP write (Arc: 8 s)
     * @param writeStallMillis close a connection whose write blocks for this long
     * @param sweepMillis how often the sweeper checks the timers and deadlines
     * @param limits what each connection may send
     * @param discoveryPerSecond discovery replies per second over all clients, a cap on UDP amplification
     */
    public record Config(boolean allowPublic, int maxConnections, int maxQueuedBytes, int hardMaxQueuedBytes, int overCapGraceMillis,
                         int maxQueuedUdpBytes, int maxStreamBytes, int streamChunk,
                         int readTimeoutMillis, int handshakeTimeoutMillis, int frameTimeoutMillis, int keepAliveMillis,
                         int writeStallMillis, int sweepMillis, RateLimits limits, int discoveryPerSecond){
        public Config{
            Objects.requireNonNull(limits, "limits");
            if(handshakeTimeoutMillis <= 0 || frameTimeoutMillis <= 0){
                throw new IllegalArgumentException("deadlines must be positive: " + handshakeTimeoutMillis + ", " + frameTimeoutMillis);
            }
            if(maxQueuedBytes <= 0 || hardMaxQueuedBytes < maxQueuedBytes || overCapGraceMillis < 0){
                throw new IllegalArgumentException("reliable caps need 0 < soft <= hard and a grace >= 0: "
                    + maxQueuedBytes + ", " + hardMaxQueuedBytes + ", " + overCapGraceMillis);
            }
            if(streamChunk <= 0 || streamChunk > Frame.MAX_CLIENT_BODY - Frame.HEADER - 6){
                throw new IllegalArgumentException("stream chunk must fit the client's read buffer: " + streamChunk);
            }
        }

        /**
         * The 10 s handshake is twice the vanilla client's own connect timeout ({@code ArcNetProvider.java:231}). The
         * 256 KiB soft cap holds a join's catch-up on a 200 ms link at about 64% (W4.5, {@code BurstBench}); a tick
         * that sends more (5,000 chat lines are about 275 KB) goes over it and drains well within the 3 s grace, and a
         * client that can't drain it is closed then. Vanilla's whole write buffer is 41,000 B (Q241).
         */
        public static Config defaults(){
            return new Config(false, 4096, 256 * 1024, 1 << 20, 3_000, 256 * 1024, 16 << 20, 4096, 12_000, 10_000, 5_000, 8_000, 10_000, 1_000,
                RateLimits.vanilla(), 100);
        }

        public Config withAllowPublic(boolean allowPublic){
            return new Config(allowPublic, maxConnections, maxQueuedBytes, hardMaxQueuedBytes, overCapGraceMillis, maxQueuedUdpBytes, maxStreamBytes, streamChunk, readTimeoutMillis,
                handshakeTimeoutMillis, frameTimeoutMillis, keepAliveMillis, writeStallMillis, sweepMillis, limits, discoveryPerSecond);
        }

        public Config withMaxQueuedUdpBytes(int maxQueuedUdpBytes){
            return new Config(allowPublic, maxConnections, maxQueuedBytes, hardMaxQueuedBytes, overCapGraceMillis, maxQueuedUdpBytes, maxStreamBytes, streamChunk, readTimeoutMillis,
                handshakeTimeoutMillis, frameTimeoutMillis, keepAliveMillis, writeStallMillis, sweepMillis, limits, discoveryPerSecond);
        }

        public Config withLimits(RateLimits limits){
            return new Config(allowPublic, maxConnections, maxQueuedBytes, hardMaxQueuedBytes, overCapGraceMillis, maxQueuedUdpBytes, maxStreamBytes, streamChunk, readTimeoutMillis,
                handshakeTimeoutMillis, frameTimeoutMillis, keepAliveMillis, writeStallMillis, sweepMillis, limits, discoveryPerSecond);
        }

        /** One reliable cap, soft and hard, that closes at once: a test that wants room, or none, past the defaults. */
        public Config withMaxQueuedBytes(int maxQueuedBytes){
            return withQueueCaps(maxQueuedBytes, maxQueuedBytes, overCapGraceMillis);
        }

        /** The reliable soft cap, the hard ceiling and the grace over the soft cap. */
        public Config withQueueCaps(int maxQueuedBytes, int hardMaxQueuedBytes, int overCapGraceMillis){
            return new Config(allowPublic, maxConnections, maxQueuedBytes, hardMaxQueuedBytes, overCapGraceMillis, maxQueuedUdpBytes, maxStreamBytes, streamChunk, readTimeoutMillis,
                handshakeTimeoutMillis, frameTimeoutMillis, keepAliveMillis, writeStallMillis, sweepMillis, limits, discoveryPerSecond);
        }

        public Config withDeadlines(int handshakeTimeoutMillis, int frameTimeoutMillis){
            return new Config(allowPublic, maxConnections, maxQueuedBytes, hardMaxQueuedBytes, overCapGraceMillis, maxQueuedUdpBytes, maxStreamBytes, streamChunk, readTimeoutMillis,
                handshakeTimeoutMillis, frameTimeoutMillis, keepAliveMillis, writeStallMillis, sweepMillis, limits, discoveryPerSecond);
        }

        public Config withTimers(int readTimeoutMillis, int keepAliveMillis, int writeStallMillis, int sweepMillis){
            return new Config(allowPublic, maxConnections, maxQueuedBytes, hardMaxQueuedBytes, overCapGraceMillis, maxQueuedUdpBytes, maxStreamBytes, streamChunk, readTimeoutMillis,
                handshakeTimeoutMillis, frameTimeoutMillis, keepAliveMillis, writeStallMillis, sweepMillis, limits, discoveryPerSecond);
        }
    }

    /** A test's view of the writers: each frame as the kernel took it, from the connection's writer, in that order. */
    interface Tap{
        void sent(Conn conn, Frame frame, boolean udp);
    }

    /** Null outside tests. */
    volatile Tap tap;

    /** Ports a bind to port 0 tries before it gives up, each lost because another socket held its UDP side. */
    static final int BIND_ATTEMPTS = 8;

    private final Config config;
    private final Handler handler;
    private final LongSupplier clock;
    private final ServerSocketChannel tcp;
    private final DatagramChannel udp;
    private final InetSocketAddress address;
    private final ConcurrentHashMap<Integer, Conn> conns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SocketAddress, Conn> byUdp = new ConcurrentHashMap<>();
    private final NetStats stats = new NetStats();
    private final SecureRandom ids = new SecureRandom();
    /** Vanilla numbers streams from one static counter ({@code Packets.java:92-94}); one per transport here. */
    private final AtomicInteger streamIds = new AtomicInteger();
    private final TokenBucket discovery;
    private final List<Thread> threads = new ArrayList<>();
    private volatile boolean closed;

    private Transport(Sockets sockets, Config config, Handler handler, LongSupplier clock){
        this.config = config;
        this.handler = handler;
        this.clock = clock;
        this.discovery = new TokenBucket(Math.max(1, config.discoveryPerSecond()), Math.max(1, config.discoveryPerSecond()), clock.getAsLong());
        tcp = sockets.tcp();
        udp = sockets.udp();
        address = sockets.address();
    }

    record Sockets(ServerSocketChannel tcp, DatagramChannel udp, InetSocketAddress address){
    }

    /**
     * TCP and UDP on one port. Port 0 lets the kernel pick a free TCP port, whose UDP side another socket may hold;
     * then it picks again. {@code tcpBound} runs between the two binds.
     */
    static Sockets open(InetSocketAddress requested, Consumer<InetSocketAddress> tcpBound) throws IOException{
        for(int attempt = 1; ; attempt++){
            var tcp = ServerSocketChannel.open();
            DatagramChannel udp = null;
            try{
                tcp.bind(requested, 512);
                var bound = (InetSocketAddress)tcp.getLocalAddress();
                tcpBound.accept(bound);
                udp = DatagramChannel.open(bound.getAddress() instanceof Inet6Address ? StandardProtocolFamily.INET6 : StandardProtocolFamily.INET);
                udp.setOption(StandardSocketOptions.SO_RCVBUF, 4 << 20);
                udp.setOption(StandardSocketOptions.SO_SNDBUF, 4 << 20);
                udp.bind(bound); // same port as TCP, even when the request was port 0
                return new Sockets(tcp, udp, bound);
            }catch(IOException | RuntimeException e){
                tcp.close();
                if(udp != null) udp.close();
                if(!(e instanceof BindException) || udp == null || requested.getPort() != 0 || attempt == BIND_ATTEMPTS) throw e;
            }
        }
    }

    /**
     * Binds TCP and UDP on the same port of {@code address} (port 0 picks one) and starts the network threads.
     *
     * @throws IllegalArgumentException if {@code address} is not loopback and the config doesn't allow public binds
     */
    public static Transport bind(InetSocketAddress address, Config config, Handler handler) throws IOException{
        return bind(address, config, handler, System::nanoTime);
    }

    /**
     * {@link #bind}, with timers, deadlines and arrival stamps on {@code clock} (nanoseconds) instead of
     * {@link System#nanoTime}: a test's, which moves it, or {@link #sweep}s by hand in this package.
     */
    public static Transport bind(InetSocketAddress address, Config config, Handler handler, LongSupplier clock) throws IOException{
        Objects.requireNonNull(handler, "handler");
        if(address.isUnresolved()) throw new IllegalArgumentException("unresolved address: " + address);
        if(!config.allowPublic() && !address.getAddress().isLoopbackAddress()){
            throw new IllegalArgumentException("refusing a non-loopback bind without allowPublic: " + address);
        }
        var transport = new Transport(open(address, bound -> {
        }), config, handler, clock);
        transport.threads.add(Thread.ofVirtual().name("tcp-accept").start(transport::acceptLoop));
        transport.threads.add(Thread.ofPlatform().daemon().name("udp-recv").start(transport::udpLoop));
        transport.threads.add(Thread.ofVirtual().name("net-sweeper").start(transport::sweepLoop));
        return transport;
    }

    /** The bound address, with the real port. */
    public InetSocketAddress address(){
        return address;
    }

    public Config config(){
        return config;
    }

    public NetStats stats(){
        return stats;
    }

    /** Open connections, registered or not. */
    public int connections(){
        return conns.size();
    }

    /** Closes the sockets and every connection. */
    @Override
    public void close(){
        if(closed) return;
        closed = true;
        try{
            tcp.close();
        }catch(IOException ignored){
        }
        try{
            udp.close();
        }catch(IOException ignored){
        }
        for(Conn conn : conns.values()) conn.close("server closed");
        for(Thread thread : threads) thread.interrupt();
    }

    /** Now, on the clock of the timers and the packets' arrival. */
    long nanoTime(){
        return clock.getAsLong();
    }

    int nextStreamId(){
        return streamIds.getAndIncrement();
    }

    /** Sends {@code datagram}, {@code bytes} long, from a connection's writer. A failed send drops it. */
    boolean sendDatagram(ByteBuffer datagram, InetSocketAddress to, int bytes){
        try{
            udp.send(datagram, to);
            stats.udpOut.increment();
            stats.udpBytesOut.add(bytes);
            return true;
        }catch(IOException e){
            stats.udpDropped.increment();
            return false;
        }
    }

    /** From {@link Conn#admit}: tell the handler. */
    void blacklisted(Conn conn){
        try{
            handler.blacklisted(conn);
        }catch(RuntimeException e){
            LOG.log(System.Logger.Level.WARNING, "handler failed on blacklisting " + conn, e);
        }
    }

    /** From {@link Conn#close}: forget the connection and tell the handler. */
    void closed(Conn conn, String reason){
        conns.remove(conn.id(), conn);
        InetSocketAddress udpRemote = conn.udpRemote();
        if(udpRemote != null) byUdp.remove(udpRemote, conn);
        try{
            handler.disconnected(conn, reason);
        }catch(RuntimeException e){
            LOG.log(System.Logger.Level.WARNING, "handler failed on disconnect of " + conn, e);
        }
    }

    private void acceptLoop(){
        while(!closed){
            SocketChannel channel;
            try{
                channel = tcp.accept();
            }catch(IOException e){
                if(!closed) LOG.log(System.Logger.Level.WARNING, "accept failed", e);
                continue;
            }
            try{
                if(conns.size() >= config.maxConnections()){
                    channel.close();
                    continue;
                }
                channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
                Conn conn;
                int id;
                do{
                    id = ids.nextInt();
                    conn = new Conn(this, id, channel);
                }while(conns.putIfAbsent(id, conn) != null);
                Conn started = conn;
                conn.sendTcp(Frame.register(Frame.REGISTER_TCP, id));
                Thread.ofVirtual().name("tcp-w-" + id).start(started::writeLoop);
                Thread.ofVirtual().name("tcp-r-" + id).start(() -> readLoop(started));
            }catch(IOException e){
                try{
                    channel.close();
                }catch(IOException ignored){
                }
            }
        }
    }

    private void readLoop(Conn conn){
        ByteBuffer buf = ByteBuffer.allocate(2 * (2 + Frame.MAX_SERVER_BODY));
        String reason = CLIENT_CLOSED;
        try{
            while(conn.isOpen()){
                if(conn.channelRead(buf) < 0) break;
                long now = clock.getAsLong();
                conn.lastReadNanos = now;
                buf.flip();
                boolean completed = false;
                while(conn.isOpen() && buf.remaining() >= 2){
                    int length = buf.getShort(buf.position()) & 0xffff;
                    if(length == 0 || length > Frame.MAX_SERVER_BODY) throw new ProtocolException("invalid frame length " + length);
                    if(buf.remaining() < 2 + length) break;
                    // Arc closes a connection that talks over TCP before its UDP side registered (Server.java:227-230)
                    if(!conn.registered()) throw new ProtocolException("TCP data before UDP registration");
                    int start = buf.position() + 2;
                    stats.tcpIn.increment();
                    body(conn, buf.array(), start, length, true, now);
                    buf.position(start + length);
                    completed = true;
                }
                // the frame deadline runs from the read that brought the first bytes of the frame still incomplete
                if(!buf.hasRemaining()) conn.partialFrameNanos = 0;
                else if(completed || conn.partialFrameNanos == 0) conn.partialFrameNanos = now;
                buf.compact();
            }
        }catch(ProtocolException | IllegalArgumentException e){
            reason = "protocol: " + e.getMessage();
        }catch(IOException e){
            reason = conn.isOpen() ? "read: " + describe(e) : reason;
        }catch(RuntimeException e){
            LOG.log(System.Logger.Level.WARNING, "reader of " + conn + " failed", e);
            reason = "error: " + e;
        }finally{
            conn.close(reason);
        }
    }

    private void udpLoop(){
        ByteBuffer buf = ByteBuffer.allocate(65536);
        while(!closed){
            SocketAddress from;
            buf.clear();
            try{
                from = udp.receive(buf);
            }catch(ClosedChannelException e){
                return;
            }catch(IOException e){
                if(!closed) LOG.log(System.Logger.Level.WARNING, "UDP receive failed", e);
                continue;
            }
            long now = clock.getAsLong();
            int length = buf.position();
            byte[] bytes = buf.array();
            stats.udpIn.increment();
            try{
                Conn conn = byUdp.get(from);
                if(conn == null){
                    unknownDatagram((InetSocketAddress)from, bytes, length);
                }else if(length == 0 || length > Frame.MAX_SERVER_BODY){
                    stats.udpDropped.increment();
                }else{
                    body(conn, bytes, 0, length, false, now);
                }
            }catch(ProtocolException | IllegalArgumentException e){
                // vanilla logs UDP deserialization errors and keeps the connection (Server.java:286-290)
                stats.udpDropped.increment();
            }catch(RuntimeException e){
                LOG.log(System.Logger.Level.WARNING, "UDP datagram from " + from + " failed", e);
            }
        }
    }

    /** A datagram from an address with no connection: only {@code RegisterUDP} and {@code DiscoverHost} mean anything. */
    private void unknownDatagram(InetSocketAddress from, byte[] bytes, int length){
        if(length == 6 && bytes[0] == Frame.FRAMEWORK && bytes[1] == Frame.REGISTER_UDP){
            int id = ByteBuffer.wrap(bytes, 2, 4).getInt();
            Conn conn = conns.get(id);
            if(conn == null || conn.registered() || !conn.isOpen()) return;
            if(!from.getAddress().equals(conn.remote().getAddress())){
                stats.udpHijacks.increment();
                return;
            }
            if(byUdp.putIfAbsent(from, conn) != null) return;
            conn.registerUdp(from);
            // Arc answers with a fresh RegisterUDP, whose id is 0 (Server.java:296)
            conn.sendTcp(Frame.register(Frame.REGISTER_UDP, 0));
            if(!conn.isOpen()){
                byUdp.remove(from, conn);
                return;
            }
            try{
                handler.connected(conn);
            }catch(RuntimeException e){
                LOG.log(System.Logger.Level.WARNING, "handler failed on connect of " + conn, e);
                conn.close("error: " + e);
            }
        }else if(length == 2 && bytes[0] == Frame.FRAMEWORK && bytes[1] == Frame.DISCOVER_HOST){
            boolean allowed;
            synchronized(discovery){
                allowed = discovery.tryTake(clock.getAsLong());
            }
            if(!allowed){
                stats.discoveriesRefused.increment();
                return;
            }
            byte[] reply = handler.discover(from);
            if(reply == null) return;
            try{
                udp.send(ByteBuffer.wrap(reply), from);
                stats.discoveries.increment();
            }catch(IOException e){
                stats.udpDropped.increment();
            }
        }else{
            stats.udpDropped.increment();
        }
    }

    /** One frame body, from TCP or UDP: a framework message or a game packet. */
    private void body(Conn conn, byte[] bytes, int off, int length, boolean tcp, long now) throws ProtocolException{
        byte id = bytes[off];
        if(!conn.admit(id, now)) return;
        if(id == Frame.FRAMEWORK){
            framework(conn, bytes, off + 1, length - 1);
            return;
        }
        int packetId = id & 0xff;
        // vanilla's Packet.allow(server) refuses these from clients, which closes the connection (ArcNetProvider.java:483)
        if(packetId == Frame.STREAM_BEGIN || packetId == Frame.STREAM_CHUNK){
            throw new ProtocolException("packet " + packetId + " is server to client only");
        }
        byte[] payload = Frame.decodePayload(bytes, off + 1, length - 1);
        try{
            handler.received(conn, new Packet(packetId, payload, tcp, now));
        }catch(RuntimeException e){
            // the server's bug, not the client's: the loops log it, where an IllegalArgumentException would pass for
            // a protocol error and close the connection without a word (a racy sort in the join did, in W2.4)
            throw new HandlerFailure(packetId, e);
        }
    }

    /** The handler threw on a packet. */
    static final class HandlerFailure extends RuntimeException{
        HandlerFailure(int packetId, RuntimeException cause){
            super("the handler failed on packet " + packetId + ": " + cause, cause);
        }
    }

    /** Arc's framework messages ({@code ArcNetProvider.java:612-637}); each must fill its body exactly. */
    private static void framework(Conn conn, byte[] bytes, int off, int length) throws ProtocolException{
        if(length < 1) throw new ProtocolException("empty framework message");
        byte type = bytes[off];
        int expected = switch(type){
            case Frame.PING -> 6;
            case Frame.DISCOVER_HOST, Frame.KEEP_ALIVE -> 1;
            case Frame.REGISTER_UDP, Frame.REGISTER_TCP -> 5;
            default -> throw new ProtocolException("unknown framework message " + type);
        };
        if(length != expected) throw new ProtocolException("framework message " + type + " of " + length + " B");
        // answer pings as Arc's Connection.notifyReceived does; keepalives only refresh the read timer
        if(type == Frame.PING && bytes[off + 5] != 1){
            conn.sendTcp(Frame.ping(ByteBuffer.wrap(bytes, off + 1, 4).getInt(), true));
        }
    }

    private void sweepLoop(){
        while(!closed){
            try{
                Thread.sleep(Math.max(1, config.sweepMillis()));
            }catch(InterruptedException e){
                return;
            }
            sweep();
        }
    }

    /** One pass of the timers and deadlines over every connection, at the clock's now. */
    void sweep(){
        long readTimeout = config.readTimeoutMillis() * 1_000_000L, keepAlive = config.keepAliveMillis() * 1_000_000L;
        long stall = config.writeStallMillis() * 1_000_000L, grace = config.overCapGraceMillis() * 1_000_000L;
        long handshake = config.handshakeTimeoutMillis() * 1_000_000L, frame = config.frameTimeoutMillis() * 1_000_000L;
        long now = clock.getAsLong();
        for(Conn conn : conns.values()){
            long writeStart = conn.writeStartNanos, partial = conn.partialFrameNanos, overCap = conn.overCapNanos;
            if(now - conn.lastReadNanos > readTimeout){
                conn.close("timed out");
            }else if(conn.attachment() == null && now - conn.acceptedNanos > handshake){
                conn.tryClose("handshake not done within " + config.handshakeTimeoutMillis() + " ms", stats.deadlines);
            }else if(partial != 0 && now - partial > frame){
                conn.tryClose("protocol: a frame took over " + config.frameTimeoutMillis() + " ms", stats.deadlines);
            }else if(writeStart != 0 && now - writeStart > stall){
                conn.tryClose("slow client: a write stalled for over " + config.writeStallMillis() + " ms", stats.slowClients);
            }else if(overCap != 0 && now - overCap > grace){
                conn.tryClose("slow client: write queue over " + config.maxQueuedBytes() + " B for over " + config.overCapGraceMillis() + " ms",
                    stats.slowClients);
            }else if(conn.drainReason() != null && now - conn.drainStartNanos > stall){
                conn.close(conn.drainReason());
            }else if(now - conn.lastWriteNanos > keepAlive && writeStart == 0){
                conn.sendTcp(Frame.KEEP_ALIVE_FRAME);
            }
            // a sender that queued without flushing: late, but not lost
            if(conn.unflushed()) conn.flush();
        }
    }

    static String describe(IOException e){
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
