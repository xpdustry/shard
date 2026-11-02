package com.xpdustry.shard.net;

import org.junit.jupiter.api.*;

import java.net.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The transport over real loopback sockets, driven by {@link RawClient}. Its clock is {@link #now}, which moves only
 * when a test {@link #advance}s it, so timers, deadlines and rate limits see the time a test says and not how long a
 * busy machine took; such a test {@linkplain Transport#sweep sweeps} itself once its client's bytes were read.
 */
class TransportTest{
    static final InetSocketAddress LOOPBACK = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);

    /** Records what the transport tells its handler. */
    static class Recorder implements Transport.Handler{
        final LinkedBlockingQueue<Conn> connected = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<Packet> packets = new LinkedBlockingQueue<>();
        final LinkedBlockingQueue<String> closed = new LinkedBlockingQueue<>();
        volatile Function<InetSocketAddress, byte[]> discovery = from -> null;
        /** Whether to attach on connect, as a session layer does once the connect packet is in. */
        volatile boolean attach = true;
        /**
         * How long {@link #disconnected} lingers after recording the close, like slow handler code. A test that reads
         * a counter right after {@link #nextClose} sets it, so a counter bumped after the handler runs shows up.
         */
        volatile long lingerMillis;
        /** Thrown from {@link #received}, like a bug in the handler. */
        volatile RuntimeException failure;

        @Override
        public void connected(Conn conn){
            if(attach) conn.attach(conn);
            connected.add(conn);
        }

        @Override
        public void received(Conn conn, Packet packet){
            if(failure != null) throw failure;
            packets.add(packet);
        }

        @Override
        public void disconnected(Conn conn, String reason){
            closed.add(reason);
            if(lingerMillis > 0){
                try{
                    Thread.sleep(lingerMillis);
                }catch(InterruptedException e){
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public byte[] discover(InetSocketAddress from){
            return discovery.apply(from);
        }

        Conn nextConnected() throws InterruptedException{
            return Objects.requireNonNull(connected.poll(Hang.SECONDS, TimeUnit.SECONDS), "no connection");
        }

        Packet nextPacket() throws InterruptedException{
            return Objects.requireNonNull(packets.poll(Hang.SECONDS, TimeUnit.SECONDS), "no packet");
        }

        String nextClose() throws InterruptedException{
            return Objects.requireNonNull(closed.poll(Hang.SECONDS, TimeUnit.SECONDS), "not closed");
        }
    }

    final Recorder handler = new Recorder();
    final List<AutoCloseable> open = new ArrayList<>();
    /** Virtual time, away from 0, which the transport's timestamps use for "none". */
    final AtomicLong now = new AtomicLong(1_000_000_000L);

    Transport bind(Transport.Config config) throws Exception{
        Transport transport = Transport.bind(LOOPBACK, config, handler, now::get);
        open.add(transport);
        return transport;
    }

    /** The body of the reply to ping {@code id}. */
    static byte[] pingReply(int id){
        return Arrays.copyOfRange(Frame.ping(id, true).toByteArray(), 2, 9);
    }

    void advance(long millis){
        now.addAndGet(millis * 1_000_000L);
    }

    static void await(String what, BooleanSupplier condition) throws InterruptedException{
        long deadline = System.nanoTime() + Hang.NANOS;
        while(!condition.getAsBoolean()){
            if(System.nanoTime() > deadline) fail("timed out waiting for " + what);
            Thread.sleep(1);
        }
    }

    /** Sends {@code frame}, then waits until the transport has read it whole: its {@code n}th TCP frame. */
    void sendAndAwait(Transport transport, RawClient client, Frame frame, long n) throws Exception{
        client.sendTcp(frame);
        await("TCP frame " + n, () -> transport.stats().tcpIn.sum() >= n);
    }

    RawClient client(Transport transport) throws Exception{
        RawClient client = new RawClient(transport.address());
        open.add(client);
        return client.start();
    }

    @AfterEach
    void close() throws Exception{
        for(AutoCloseable closeable : open.reversed()) closeable.close();
    }

    @Test
    void aHandlerFailureIsTheServersErrorNotAProtocolError() throws Exception{
        Transport transport = bind(Transport.Config.defaults());
        RawClient client = client(transport).handshake();
        handler.nextConnected();
        // TimSort's complaint about a comparator that changes under it, as a racy sort in the join threw
        handler.failure = new IllegalArgumentException("Comparison method violates its general contract!");
        client.sendTcp(Frame.packet(3, new byte[]{1}));
        String reason = handler.nextClose();
        assertTrue(reason.startsWith("error: ") && reason.contains("the handler failed on packet 3") && reason.contains("general contract"),
            reason);
    }

    @Test
    void handshakeThenPacketsBothWays() throws Exception{
        Transport transport = bind(Transport.Config.defaults());
        RawClient client = client(transport).handshake();
        Conn conn = handler.nextConnected();
        assertEquals(client.connectionId, conn.id());
        assertTrue(conn.registered());
        assertEquals(1, transport.connections());

        // client to server: uncompressed over TCP, LZ4 over UDP, as the vanilla client sends clientSnapshot
        byte[] small = {1, 2, 3}, big = new byte[300];
        Arrays.fill(big, (byte)9);
        client.sendTcp(Frame.packet(84, small));
        client.sendUdp(Frame.packet(29, big));
        Packet first = handler.nextPacket(), second = handler.nextPacket();
        Packet tcp = first.reliable() ? first : second, udp = first.reliable() ? second : first;
        assertEquals(84, tcp.id());
        assertArrayEquals(small, tcp.payload());
        assertEquals(29, udp.id());
        assertFalse(udp.reliable());
        assertArrayEquals(big, udp.payload());
        assertEquals(now.get(), tcp.arrivalNanos(), "arrival is on the transport's clock");
        assertEquals(now.get(), udp.arrivalNanos());

        // server to client
        assertTrue(conn.sendTcp(Frame.packet(106, new byte[]{0})));
        byte[] body = client.nextTcpPacket();
        assertEquals(106, body[0]);
        assertTrue(conn.sendUdp(Frame.packet(49, big)));
        byte[] datagram = client.udp.poll(Hang.SECONDS, TimeUnit.SECONDS);
        assertNotNull(datagram);
        assertEquals(49, datagram[0]);
        assertArrayEquals(big, RawClient.payload(datagram));
    }

    @Test
    void tcpBeforeUdpRegistrationCloses() throws Exception{
        Transport transport = bind(Transport.Config.defaults());
        RawClient client = client(transport);
        client.nextTcp(); // RegisterTCP
        client.sendTcp(Frame.KEEP_ALIVE_FRAME);
        assertEquals("protocol: TCP data before UDP registration", handler.nextClose());
        assertTrue(client.awaitEof(Hang.MILLIS));
        assertTrue(handler.connected.isEmpty(), "never connected");
    }

    @Test
    void pingsAreAnsweredAndKeepalivesSent() throws Exception{
        Transport transport = bind(Transport.Config.defaults().withTimers(12_000, 300, 10_000, 50));
        RawClient client = client(transport).handshake();
        Conn conn = handler.nextConnected();
        client.sendTcp(Frame.ping(42, false));
        byte[] reply = client.nextTcpPacket();
        assertArrayEquals(pingReply(42), reply);
        // the writer stamps a write after the socket takes it, which the client's read can beat, and the interval runs from
        // the stamp. It takes the frame off the queue's count after stamping, so the clock waits for that
        await("the answer written and stamped", () -> conn.queuedBytes() == 0);
        // a reply from the client is not answered
        sendAndAwait(transport, client, Frame.ping(43, true), 2);
        advance(300);
        transport.sweep();
        // the write queue is in order, so the answer to a ping sent after the sweep comes after anything the sweep queued
        client.sendTcp(Frame.ping(44, false));
        assertArrayEquals(pingReply(44), client.nextTcp(), "no keepalive before 300 ms without a write");
        // that answer was a write too, so the interval runs from it: 300 ms and 1 is over it
        await("the second answer written and stamped", () -> conn.queuedBytes() == 0);
        advance(301);
        transport.sweep();
        byte[] keepAlive = client.nextTcp();
        assertArrayEquals(new byte[]{Frame.FRAMEWORK, Frame.KEEP_ALIVE}, keepAlive, "only keepalives follow");
    }

    @Test
    void silentClientsTimeOut() throws Exception{
        Transport transport = bind(Transport.Config.defaults().withTimers(300, 100, 10_000, 50));
        RawClient client = client(transport).handshake();
        handler.nextConnected();
        advance(300);
        transport.sweep();
        assertTrue(handler.closed.isEmpty(), "300 ms of silence is the limit, not over it");
        advance(1);
        transport.sweep();
        assertEquals("timed out", handler.nextClose());
        assertTrue(client.awaitEof(Hang.MILLIS));
        assertEquals(0, transport.connections());
    }

    @Test
    void keepalivesFromTheClientKeepItOpen() throws Exception{
        Transport transport = bind(Transport.Config.defaults().withTimers(400, 100, 10_000, 50));
        RawClient client = client(transport).handshake();
        handler.nextConnected();
        for(int i = 1; i <= 10; i++){
            sendAndAwait(transport, client, Frame.KEEP_ALIVE_FRAME, i);
            advance(100);
            transport.sweep();
        }
        assertTrue(handler.closed.isEmpty(), "still open after 1 s of keepalives");
        // the read timeout runs from the last of them
        advance(301);
        transport.sweep();
        assertEquals("timed out", handler.nextClose());
    }

    @Test
    void aTrickledFrameHitsTheFrameDeadline() throws Exception{
        // each byte refreshes the 1 s read timeout, but the frame never completes
        handler.lingerMillis = 100;
        Transport transport = bind(Transport.Config.defaults().withTimers(1_000, 30_000, 10_000, 50).withDeadlines(30_000, 400));
        RawClient client = client(transport).handshake();
        Conn conn = handler.nextConnected();
        client.channel.write(ByteBuffer.allocate(2).putShort(0, (short)1_000));
        await("the frame's first bytes", () -> conn.partialFrameNanos == now.get());
        for(int i = 0; i < 8; i++){
            advance(50);
            client.channel.write(ByteBuffer.wrap(new byte[]{84}));
            await("byte " + i, () -> conn.lastReadNanos == now.get());
            transport.sweep();
        }
        assertTrue(handler.closed.isEmpty(), "400 ms since the first bytes is the deadline, not over it");
        advance(1);
        transport.sweep();
        assertEquals("protocol: a frame took over 400 ms", handler.nextClose());
        assertTrue(client.awaitEof(Hang.MILLIS));
        assertEquals(1, transport.stats().deadlines.sum());
    }

    @Test
    void slowFramesThatCompleteKeepTheConnection() throws Exception{
        // a frame completes every eighth of the deadline, for twice the deadline in all: one is always pending
        int deadline = 400, every = deadline / 8, writes = 16;
        Transport transport = bind(Transport.Config.defaults().withTimers(10_000, 30_000, 10_000, 50).withDeadlines(30_000, deadline));
        RawClient client = client(transport).handshake();
        Conn conn = handler.nextConnected();
        // keepalives split across writes, so a partial frame is always pending, but each one completes
        byte[] two = new byte[8];
        ByteBuffer.wrap(two).put(Frame.KEEP_ALIVE_FRAME.toByteArray()).put(Frame.KEEP_ALIVE_FRAME.toByteArray());
        client.channel.write(ByteBuffer.wrap(two, 0, 2));
        await("the first partial frame", () -> conn.partialFrameNanos == now.get());
        for(int i = 1; i <= writes; i++){
            advance(every);
            client.channel.write(ByteBuffer.wrap(two, 2, 4)); // the rest of one, half of the next
            await("frame " + i, () -> conn.partialFrameNanos == now.get());
            transport.sweep();
        }
        assertTrue(handler.closed.isEmpty(), "frames that complete are progress");
        // the last partial frame still has its deadline
        advance(deadline + 1);
        transport.sweep();
        assertEquals("protocol: a frame took over " + deadline + " ms", handler.nextClose());
    }

    @Test
    void anUnattachedConnectionHitsTheHandshakeDeadline() throws Exception{
        handler.attach = false;
        handler.lingerMillis = 100;
        Transport transport = bind(Transport.Config.defaults().withTimers(1_000, 30_000, 10_000, 50).withDeadlines(500, 5_000));
        RawClient client = client(transport).handshake();
        handler.nextConnected();
        // registered and talking, but it never finishes the handshake above the transport
        for(int i = 1; i <= 10; i++){
            sendAndAwait(transport, client, Frame.KEEP_ALIVE_FRAME, i);
            advance(50);
            transport.sweep();
        }
        assertTrue(handler.closed.isEmpty(), "500 ms is the deadline, not over it");
        advance(1);
        transport.sweep();
        assertEquals("handshake not done within 500 ms", handler.nextClose());
        assertEquals(1, transport.stats().deadlines.sum());
    }

    @Test
    void theHandshakeDeadlineAlsoCoversRegistration() throws Exception{
        Transport transport = bind(Transport.Config.defaults().withTimers(30_000, 30_000, 10_000, 50).withDeadlines(500, 5_000));
        RawClient client = client(transport);
        client.nextTcp(); // RegisterTCP, and no RegisterUDP ever
        advance(501);
        transport.sweep();
        assertEquals("handshake not done within 500 ms", handler.nextClose());
        assertTrue(client.awaitEof(Hang.MILLIS));
    }

    @Test
    void attachedConnectionsOutliveTheHandshakeDeadline() throws Exception{
        Transport transport = bind(Transport.Config.defaults().withTimers(1_000, 30_000, 10_000, 50).withDeadlines(300, 5_000));
        RawClient client = client(transport).handshake();
        handler.nextConnected();
        for(int i = 1; i <= 10; i++){
            sendAndAwait(transport, client, Frame.KEEP_ALIVE_FRAME, i);
            advance(100);
            transport.sweep();
        }
        assertTrue(handler.closed.isEmpty());
    }

    @Test
    void discoveryRepliesWithTheHandlersRawBytes() throws Exception{
        Transport transport = bind(Transport.Config.defaults());
        byte[] info = "server info".getBytes();
        handler.discovery = from -> info;
        try(var socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))){
            socket.setSoTimeout((int)Hang.MILLIS);
            byte[] request = {Frame.FRAMEWORK, Frame.DISCOVER_HOST};
            socket.send(new DatagramPacket(request, 2, transport.address()));
            var reply = new DatagramPacket(new byte[512], 512);
            socket.receive(reply);
            assertArrayEquals(info, Arrays.copyOf(reply.getData(), reply.getLength()));
        }
        // the transport counts a reply after it sends it, so the client can read the reply first
        await("the reply counted", () -> transport.stats().discoveries.sum() >= 1);
        assertEquals(1, transport.stats().discoveries.sum());
    }

    /**
     * Sends {@code count} discovery requests, {@code already} having been counted before, in bursts that wait to be
     * counted: back to back, a busy machine's reader falls behind and the socket's buffer drops datagrams, which then
     * never get counted.
     */
    static void sendDiscoveries(Transport transport, DatagramSocket socket, byte[] request, int count, int already) throws Exception{
        for(int sent = 0; sent < count; sent += 50){
            for(int i = 0; i < 50; i++) socket.send(new DatagramPacket(request, 2, transport.address()));
            long total = already + sent + 50;
            await(total + " discoveries counted", () -> transport.stats().discoveries.sum() + transport.stats().discoveriesRefused.sum() == total);
        }
    }

    @Test
    void discoveryIsRateLimited() throws Exception{
        Transport transport = bind(Transport.Config.defaults());
        handler.discovery = from -> new byte[100];
        try(var socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))){
            byte[] request = {Frame.FRAMEWORK, Frame.DISCOVER_HOST};
            sendDiscoveries(transport, socket, request, 400, 0);
            assertEquals(100, transport.stats().discoveries.sum(), "the burst of a frozen clock");
            // a second refills 100
            advance(1_000);
            sendDiscoveries(transport, socket, request, 400, 400);
        }
        assertEquals(200, transport.stats().discoveries.sum());
        assertEquals(600, transport.stats().discoveriesRefused.sum());
    }

    @Test
    void udpFromAnotherIpCannotTakeOverTheConnection() throws Exception{
        Transport transport = bind(Transport.Config.defaults());
        RawClient client = client(transport);
        byte[] register = client.nextTcp();
        int id = ByteBuffer.wrap(register, 2, 4).getInt();
        // 127.0.0.2 is loopback too on Linux, but not the TCP peer's address
        try(var other = java.nio.channels.DatagramChannel.open().bind(new InetSocketAddress("127.0.0.2", 0))){
            other.send(ByteBuffer.wrap(RawClient.registerUdp(id)), transport.address());
        }
        await("the impostor's RegisterUDP", () -> transport.stats().udpHijacks.sum() > 0);
        assertEquals(1, transport.stats().udpHijacks.sum());
        // the real client still registers
        client.sendUdp(RawClient.registerUdp(id));
        RawClient.assertBody(client.nextTcp(), Frame.FRAMEWORK, Frame.REGISTER_UDP);
        assertEquals(id, handler.nextConnected().id());
        // a reply to the impostor would have been queued before this one, so a ping's answer must come next
        client.sendTcp(Frame.ping(1, false));
        assertArrayEquals(pingReply(1), client.nextTcp(), "no RegisterUDP reply to the impostor");
    }

    @Test
    void streamsAreChunkedAndPacedBehindQueuedFrames() throws Exception{
        Transport transport = bind(Transport.Config.defaults());
        RawClient client = client(transport).handshake();
        Conn conn = handler.nextConnected();
        byte[] data = new byte[50_000];
        new Random(5).nextBytes(data);
        conn.sendTcp(Frame.packet(172, new byte[0])); // worldDataBegin, queued before the stream
        int streamId = conn.sendStream(2, data);
        assertTrue(streamId >= 0);

        assertEquals((byte)172, client.nextTcpPacket()[0], "frames queued first go first");
        byte[] begin = client.nextTcpPacket();
        assertEquals(Frame.STREAM_BEGIN, begin[0]);
        ByteBuffer header = ByteBuffer.wrap(RawClient.payload(begin));
        assertEquals(streamId, header.getInt());
        assertEquals(data.length, header.getInt());
        assertEquals(2, header.get());

        var received = new java.io.ByteArrayOutputStream();
        int chunks = 0;
        while(received.size() < data.length){
            byte[] chunk = client.nextTcpPacket();
            assertEquals(Frame.STREAM_CHUNK, chunk[0]);
            assertEquals(0, chunk[3], "never compressed");
            ByteBuffer payload = ByteBuffer.wrap(RawClient.payload(chunk));
            assertEquals(streamId, payload.getInt());
            int length = payload.getShort();
            assertTrue(length <= transport.config().streamChunk());
            received.write(payload.array(), payload.position(), length);
            chunks++;
        }
        assertArrayEquals(data, received.toByteArray());
        assertEquals((data.length + 4095) / 4096, chunks);
        assertEquals(0, conn.queuedBytes());
    }

    @Test
    void anOversizedStreamIsRefusedBeforeItIsCopied() throws Exception{
        Transport transport = bind(Transport.Config.defaults());
        client(transport).handshake();
        Conn conn = handler.nextConnected();
        byte[] data = new byte[20 << 20]; // over the 16 MiB stream cap
        var threads = (com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();
        assertEquals(-1, conn.sendStream(2, data));
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        assertTrue(allocated < 1 << 20, "allocated " + allocated + " B for a refused stream");
        assertTrue(handler.nextClose().startsWith("slow client: streams over"));

        before = threads.getCurrentThreadAllocatedBytes();
        assertEquals(-1, conn.sendStream(2, new byte[0]), "a closed connection refuses streams");
        assertEquals(-1, conn.sendStream(2, data));
        assertTrue(threads.getCurrentThreadAllocatedBytes() - before < 1 << 20, "nothing copied for a closed connection");
    }

    @Test
    void streamSizesAreExactAndDontOverflow(){
        long frames = Frame.streamBegin(0, 50_000, 2).size();
        byte[] data = new byte[50_000];
        for(int off = 0; off < data.length; off += 4096) frames += Frame.streamChunk(0, data, off, Math.min(4096, data.length - off)).size();
        assertEquals(frames, Conn.streamSize(50_000, 4096));
        assertEquals(15, Conn.streamSize(0, 4096), "an empty stream is only its begin");
        long chunks = (Integer.MAX_VALUE + 4095L) / 4096;
        assertEquals(15 + chunks * 12 + Integer.MAX_VALUE, Conn.streamSize(Integer.MAX_VALUE, 4096));
        assertTrue(Conn.streamSize(Integer.MAX_VALUE, 1) > Integer.MAX_VALUE);
    }

    @Test
    void aWriteThatStallsIsDisconnected() throws Exception{
        Transport transport = bind(Transport.Config.defaults().withMaxQueuedBytes(64 << 20).withTimers(3_600_000, 3_600_000, 500, 50));
        var client = new RawClient(transport.address(), new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 4096);
        open.add(client);
        var read = ByteBuffer.allocate(8);
        while(read.hasRemaining()) client.channel.read(read);
        client.sendUdp(RawClient.registerUdp(read.getInt(4)));
        Conn conn = handler.nextConnected();
        byte[] noise = new byte[20_000];
        new Random(7).nextBytes(noise);
        Frame big = Frame.packet(49, noise);
        for(int i = 0; i < 1000 && conn.isOpen(); i++) conn.sendTcp(big); // 20 MB, more than any kernel buffer
        // the writer soon blocks for good; until then a sweep half a second on may still find it moving
        await("the stalled write to close", () -> {
            advance(501);
            transport.sweep();
            return !handler.closed.isEmpty();
        });
        assertTrue(handler.nextClose().startsWith("slow client: a write stalled"));
        assertEquals(1, transport.stats().slowClients.sum());
    }

    @RepeatedTest(3)
    void packetSpamClosesTheConnection() throws Exception{
        // the reader that closes for spam lingers in the handler; the counter must already count it
        handler.lingerMillis = 100;
        // vanilla's limit on a frozen clock: its burst, and nothing refills (RateLimitsTest has the refill)
        Transport transport = bind(Transport.Config.defaults());
        RawClient client = client(transport).handshake();
        Frame frame = Frame.packet(84, new byte[8]);
        try{
            for(int i = 0; i < 400; i++) client.sendTcp(frame);
        }catch(java.io.IOException reset){
            // the server may close while we still write
        }
        assertEquals("packet spam", handler.nextClose());
        assertEquals(RateLimits.VANILLA_SPAM.burst(), handler.packets.size(), "the burst got through");
        assertEquals(1, transport.stats().rateClosed.sum());
    }

    @Test
    void perIdLimitsDropWithoutClosing() throws Exception{
        var limits = RateLimits.vanilla().with(105, new RateLimits.Limit(5, 1, RateLimits.Action.DROP));
        Transport transport = bind(Transport.Config.defaults().withLimits(limits));
        RawClient client = client(transport).handshake();
        for(int i = 0; i < 20; i++) client.sendTcp(Frame.packet(105, new byte[]{(byte)i}));
        client.sendTcp(Frame.packet(84, new byte[]{1}));
        var ids = new ArrayList<Integer>();
        while(true){
            Packet packet = handler.nextPacket();
            if(packet.id() == 84) break;
            ids.add(packet.id());
        }
        assertEquals(5, ids.size(), "the burst of 5 chat packets, then drops");
        assertEquals(15, transport.stats().rateDropped.sum());
        assertTrue(handler.closed.isEmpty());
    }

    @Test
    void malformedTcpCloses() throws Exception{
        Transport transport = bind(Transport.Config.defaults());
        record Case(String reason, byte[] body){
        }
        for(Case c : List.of(
            new Case("protocol: packet 1 is server to client only", Arrays.copyOfRange(Frame.streamChunk(0, new byte[4], 0, 4).toByteArray(), 2, 16)),
            new Case("protocol: LZ4: corrupt block: literal length past the end", new byte[]{29, 0, 40, 1, (byte)0xff}),
            new Case("protocol: unknown framework message 9", new byte[]{Frame.FRAMEWORK, 9}),
            new Case("protocol: framework message 2 of 2 B", new byte[]{Frame.FRAMEWORK, Frame.KEEP_ALIVE, 0}),
            new Case("protocol: payload is 1 B, header says 2", new byte[]{84, 0, 2, 0, 7})
        )){
            RawClient client = client(transport).handshake();
            client.sendTcpBody(c.body());
            assertEquals(c.reason(), handler.nextClose());
            assertTrue(client.awaitEof(Hang.MILLIS));
        }
        RawClient client = client(transport).handshake();
        client.sendTcpBody(new byte[0]);
        assertEquals("protocol: invalid frame length 0", handler.nextClose());
    }

    @Test
    void malformedUdpIsDroppedAndTheConnectionStays() throws Exception{
        Transport transport = bind(Transport.Config.defaults());
        RawClient client = client(transport).handshake();
        client.sendUdp(new byte[]{29, 0, 40, 1, (byte)0xff});
        client.sendUdp(new byte[]{Frame.FRAMEWORK, 9});
        client.sendUdp(Frame.packet(29, new byte[]{5}));
        assertArrayEquals(new byte[]{5}, handler.nextPacket().payload());
        assertEquals(2, transport.stats().udpDropped.sum());
        assertTrue(handler.closed.isEmpty());
    }

    @RepeatedTest(3)
    void closeAfterWritesTheKickFirst() throws Exception{
        Transport transport = bind(Transport.Config.defaults());
        // the client reads nothing until the kick is queued, through a 4 KiB window: the writer is stuck mid-stream
        var client = new RawClient(transport.address(), new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 4096);
        open.add(client);
        var read = ByteBuffer.allocate(8);
        while(read.hasRemaining()) client.channel.read(read); // RegisterTCP, by hand
        client.sendUdp(RawClient.registerUdp(read.getInt(4)));
        Conn conn = handler.nextConnected();
        conn.sendTcp(Frame.packet(106, new byte[]{1}));
        // 12 MiB: more than any loopback socket buffers, so the stream can't be written before the kick is queued
        int chunks = (12 << 20) / transport.config().streamChunk();
        assertTrue(conn.sendStream(2, new byte[12 << 20]) >= 0);
        conn.closeAfter(Frame.packet(65, new byte[]{2}), "kicked");
        assertFalse(conn.sendTcp(Frame.packet(106, new byte[]{3})), "nothing after the kick");

        client.start();
        byte[] first;
        while((first = client.nextTcpPacket())[0] == Frame.FRAMEWORK){
            // the RegisterUDP reply, still unread
        }
        assertEquals((byte)106, first[0], "frames queued before the stream go first");
        // what the kernel buffered before the kick, never the whole stream
        int streamFrames = 0;
        byte[] next;
        while((next = client.nextTcpPacket())[0] != 65){
            assertTrue(next[0] == Frame.STREAM_BEGIN || next[0] == Frame.STREAM_CHUNK);
            streamFrames++;
        }
        assertTrue(streamFrames < 1 + chunks, "the rest of the stream was dropped: " + streamFrames + " of " + (1 + chunks));
        assertEquals("kicked", handler.nextClose());
        assertTrue(client.awaitEof(Hang.MILLIS));
    }

    @Test
    void cancelledStreamsLoseTheirUnwrittenFrames() throws Exception{
        Transport transport = bind(Transport.Config.defaults());
        // as above: a 4 KiB window the client doesn't read yet, so the stream waits in the queue
        var client = new RawClient(transport.address(), new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 4096);
        open.add(client);
        var read = ByteBuffer.allocate(8);
        while(read.hasRemaining()) client.channel.read(read);
        client.sendUdp(RawClient.registerUdp(read.getInt(4)));
        Conn conn = handler.nextConnected();
        int chunks = (12 << 20) / transport.config().streamChunk();
        int old = conn.sendStream(2, new byte[12 << 20]);
        assertTrue(old >= 0);
        assertArrayEquals(new int[]{old}, conn.cancelStreams());
        assertArrayEquals(new int[0], conn.cancelStreams(), "once");
        // worldDataBegin, then the new load's stream
        conn.sendTcp(Frame.packet(172, new byte[0]));
        byte[] data = new byte[10_000];
        new Random(7).nextBytes(data);
        int current = conn.sendStream(2, data);

        client.start();
        int oldFrames = 0;
        byte[] next;
        while((next = client.nextTcpPacket())[0] != (byte)172){
            if(next[0] == Frame.FRAMEWORK) continue;
            assertEquals(old, ByteBuffer.wrap(RawClient.payload(next)).getInt(), "only what the kernel took before the cancel");
            oldFrames++;
        }
        assertTrue(oldFrames < 1 + chunks, "the old stream never completes: " + oldFrames + " of " + (1 + chunks));
        byte[] begin = client.nextTcpPacket();
        assertEquals(Frame.STREAM_BEGIN, begin[0]);
        assertEquals(current, ByteBuffer.wrap(RawClient.payload(begin)).getInt(), "nothing of the old stream after worldDataBegin");
        var received = new java.io.ByteArrayOutputStream();
        while(received.size() < data.length){
            ByteBuffer payload = ByteBuffer.wrap(RawClient.payload(client.nextTcpPacket()));
            assertEquals(current, payload.getInt());
            int length = payload.getShort();
            received.write(payload.array(), payload.position(), length);
        }
        assertArrayEquals(data, received.toByteArray());
        assertTrue(conn.sendStream(2, new byte[12 << 20]) >= 0, "the cancelled stream's bytes went back to the cap");
    }

    @Test
    void closingTheTransportClosesEveryConnection() throws Exception{
        Transport transport = Transport.bind(LOOPBACK, Transport.Config.defaults(), handler);
        try(RawClient a = new RawClient(transport.address()).start(); RawClient b = new RawClient(transport.address()).start()){
            a.handshake();
            b.handshake();
            transport.close();
            assertEquals("server closed", handler.nextClose());
            assertEquals("server closed", handler.nextClose());
            assertTrue(a.awaitEof(Hang.MILLIS) && b.awaitEof(Hang.MILLIS));
        }
    }

    @Test
    void portZeroPicksAgainWhenItsUdpSideIsTaken() throws Exception{
        // another socket takes the UDP side of the first TCP port the kernel picks, as a parallel test fork can
        var first = new AtomicInteger();
        var taken = new ArrayList<DatagramSocket>();
        try{
            var sockets = Transport.open(LOOPBACK, bound -> {
                if(first.get() != 0) return;
                first.set(bound.getPort());
                try{
                    taken.add(new DatagramSocket(bound));
                }catch(SocketException alreadyTaken){
                    // by someone else: the same case
                }
            });
            try(var tcp = sockets.tcp(); var udp = sockets.udp()){
                assertNotEquals(first.get(), sockets.address().getPort());
                assertEquals(sockets.address(), tcp.getLocalAddress());
                assertEquals(sockets.address(), udp.getLocalAddress());
            }
        }finally{
            for(DatagramSocket socket : taken) socket.close();
        }
        // a port asked for by number is not picked again
        try(var udp = new DatagramSocket(LOOPBACK)){
            var port = new InetSocketAddress(InetAddress.getLoopbackAddress(), udp.getLocalPort());
            assertThrows(BindException.class, () -> Transport.open(port, bound -> {
            }));
        }
    }

    @Test
    void publicAddressesNeedAllowPublic(){
        var any = new InetSocketAddress(0);
        var thrown = assertThrows(IllegalArgumentException.class, () -> Transport.bind(any, Transport.Config.defaults(), handler));
        assertTrue(thrown.getMessage().startsWith("refusing a non-loopback bind"));
    }
}
