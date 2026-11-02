package com.xpdustry.shard.net;

import org.junit.jupiter.api.*;

import java.net.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Q215: every frame a tick queues for one connection, reliable or not, reaches the kernel in the order it was queued,
 * and no tick thread waits for a slow client meanwhile. Also the per-connection send path's other rules: the UDP cap,
 * {@link Conn#queue} and {@link Conn#flush}, and the blacklist hook.
 */
class SendOrderTest{
    static final InetSocketAddress LOOPBACK = TransportTest.LOOPBACK;

    final TransportTest.Recorder handler = new TransportTest.Recorder();
    final List<AutoCloseable> open = new ArrayList<>();
    final AtomicLong now = new AtomicLong(1_000_000_000L);

    Transport bind(Transport.Config config) throws Exception{
        Transport transport = Transport.bind(LOOPBACK, config, handler, now::get);
        open.add(transport);
        return transport;
    }

    @AfterEach
    void close() throws Exception{
        for(AutoCloseable closeable : open.reversed()) closeable.close();
    }

    /** A client that reads nothing yet: a 4 KiB receive buffer, the handshake done by hand. */
    RawClient slowClient(Transport transport) throws Exception{
        var client = new RawClient(transport.address(), new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 4096);
        open.add(client);
        var read = ByteBuffer.allocate(8);
        while(read.hasRemaining()) client.channel.read(read);
        client.sendUdp(RawClient.registerUdp(read.getInt(4)));
        // RegisterUDP's reply (8 B) is read with everything else once the client starts
        return client;
    }

    /** A game packet whose payload starts with its sequence number, padded to {@code size} bytes. */
    static Frame numbered(int id, int seq, int size, Random random){
        byte[] payload = new byte[Math.max(4, size)];
        random.nextBytes(payload);
        ByteBuffer.wrap(payload).putInt(0, seq);
        return Frame.packet(id, payload);
    }

    static int seq(byte[] body){
        return ByteBuffer.wrap(RawClient.payload(body)).getInt(0);
    }

    static boolean framework(byte[] body){
        return body[0] == Frame.FRAMEWORK;
    }

    /**
     * A world stream sent right after a lone datagram goes out on TCP, every frame of it. The writer's per-slot UDP flags
     * outlive a batch, and the stream branch didn't reset its slot, so a batch that began with a datagram sent the next
     * stream's begin and chunks as datagrams (review 43, blocker).
     */
    @Test
    void aStreamAfterADatagramLeavesOnTcp() throws Exception{
        Transport transport = bind(Transport.Config.defaults().withTimers(3_600_000, 3_600_000, 3_600_000, 50));
        RawClient client = slowClient(transport).start();
        Conn conn = handler.nextConnected();
        var handedOff = new ConcurrentLinkedQueue<String>();
        transport.tap = (c, frame, udp) -> {
            if(c == conn && frame.id() != Frame.FRAMEWORK) handedOff.add(udp ? "U" : "T");
        };
        var random = new Random(43);

        // a batch whose first frame is a datagram, finished before the stream is queued
        assertTrue(conn.queue(numbered(49, 0, 100, random), false));
        conn.flush();
        TransportTest.await("the datagram handed off", () -> handedOff.size() == 1);
        assertEquals("U", handedOff.peek());

        // then a world stream of several chunks, with no ordinary frame in between
        byte[] world = new byte[transport.config().streamChunk() * 4 + 17];
        random.nextBytes(world);
        int frames = 1 + Math.toIntExact(Math.ceilDiv((long)world.length, transport.config().streamChunk()));
        assertTrue(conn.sendStream(2, world) >= 0);
        TransportTest.await("every stream frame handed off", () -> handedOff.size() == 1 + frames);
        assertEquals(List.of("U"), List.copyOf(handedOff).subList(0, 1));
        assertFalse(List.copyOf(handedOff).subList(1, 1 + frames).contains("U"), "a stream frame left as a datagram: " + handedOff);
        assertTrue(conn.isOpen());
    }

    /**
     * The tick's interleaved Calls (TCP) and snapshots (UDP) behind a client that has stopped reading: the queueing
     * thread never blocks, no datagram leaves before the TCP frames queued ahead of it, and once the client reads
     * again every frame arrives, each protocol in order.
     */
    @RepeatedTest(3)
    void aTicksCallsAndSnapshotsLeaveInTickOrderBehindASlowReader() throws Exception{
        Transport transport = bind(Transport.Config.defaults().withMaxQueuedBytes(64 << 20).withMaxQueuedUdpBytes(64 << 20)
            .withTimers(3_600_000, 3_600_000, 3_600_000, 50));
        var handedOff = new ConcurrentLinkedQueue<String>();
        RawClient client = slowClient(transport);
        Conn conn = handler.nextConnected();
        transport.tap = (c, frame, udp) -> {
            if(c == conn && frame.id() != Frame.FRAMEWORK) handedOff.add((udp ? "U" : "T") + frame.id());
        };
        var random = new Random(215);

        // 8 MB of Calls first, more than the kernel's buffers on both sides: the writer blocks in the middle of them
        int bulk = 400;
        for(int i = 0; i < bulk; i++) assertTrue(conn.queue(numbered(106, i, 20_000, random), true));
        conn.flush();
        TransportTest.await("the writer to block", () -> conn.writeStartNanos != 0 && conn.queuedBytes() > 0);

        // then ticks: each one's Calls, then its snapshots, as WorldSync sends them; queued while the writer is stuck
        var expected = new ArrayList<String>();
        for(int i = 0; i < bulk; i++) expected.add("T106");
        int ticks = 50;
        // Queueing that waited for the blocked writer would wait for good (the client reads nothing until below), so a
        // failure detector tells the two apart; a bound of seconds only measured the machine
        assertTimeoutPreemptively(Hang.TIMEOUT, () -> {
            for(int tick = 0; tick < ticks; tick++){
                int seq = bulk + tick * 3;
                assertTrue(conn.queue(numbered(106, seq, 40, random), true));
                assertTrue(conn.queue(numbered(141, seq + 1, 600, random), false));
                assertTrue(conn.queue(numbered(49, seq + 2, 900, random), false));
                conn.flush();
                expected.addAll(List.of("T106", "U141", "U49"));
            }
        });
        assertTrue(conn.writeStartNanos != 0, "the writer is still blocked: the queueing above never waited for it");

        // no datagram has left: each is behind Calls the client hasn't read. Its UDP socket holds nothing yet (main sent
        // each one from the queueing thread at once, so they were all there)
        Thread.sleep(200);
        assertFalse(handedOff.stream().anyMatch(entry -> entry.startsWith("U")), "a datagram left before the Calls queued ahead of it");
        client.datagrams.configureBlocking(false);
        assertNull(client.datagrams.receive(ByteBuffer.allocate(65536)), "a datagram reached the client before the Calls queued ahead of it");
        client.datagrams.configureBlocking(true);
        client.start();

        // all of it arrives, each protocol in queue order
        var tcp = new ArrayList<Integer>();
        while(tcp.size() < bulk + ticks){
            byte[] body = client.nextTcp();
            if(!framework(body)) tcp.add(seq(body));
        }
        var udp = new ArrayList<Integer>();
        while(udp.size() < 2 * ticks){
            byte[] body = client.udp.poll(Hang.SECONDS, TimeUnit.SECONDS);
            assertNotNull(body, "datagram " + udp.size() + " of " + 2 * ticks + " never came");
            udp.add(seq(body));
        }
        for(int i = 0; i < tcp.size(); i++) assertEquals(i < bulk ? i : bulk + (i - bulk) * 3, tcp.get(i), "TCP frame " + i);
        for(int i = 0; i < udp.size(); i++) assertEquals(bulk + (i / 2) * 3 + 1 + i % 2, udp.get(i), "datagram " + i);

        // and the kernel took them in exactly the order the ticks queued them
        TransportTest.await("every frame handed off", () -> handedOff.size() == expected.size());
        assertEquals(expected, List.copyOf(handedOff));
        assertTrue(conn.isOpen());
        assertEquals(0, transport.stats().udpShed.sum());
    }

    @Test
    void queuedFramesWaitForAFlush() throws Exception{
        Transport transport = bind(Transport.Config.defaults().withTimers(3_600_000, 3_600_000, 3_600_000, 3_600_000));
        RawClient client = new RawClient(transport.address());
        open.add(client);
        client.start().handshake();
        Conn conn = handler.nextConnected();
        var random = new Random(1);
        // The writer is done with the handshake's reply once it is off the count (it stamps, then takes it off). A writer
        // still finishing that would find the frames below on its way round and write them, flush or not, so what the
        // test counts is the wakes, not whether anything was seen on the wire in the first milliseconds.
        TransportTest.await("the handshake's reply written", () -> conn.queuedBytes() == 0);
        long wakes = conn.wakes();
        assertTrue(conn.queue(numbered(106, 1, 10, random), true));
        assertTrue(conn.queue(numbered(49, 2, 10, random), false));
        assertTrue(conn.unflushed());
        assertEquals(wakes, conn.wakes(), "queueing wakes nobody");
        conn.flush();
        assertFalse(conn.unflushed());
        assertEquals(wakes + 1, conn.wakes(), "a flush wakes the writer once");
        assertEquals(1, seq(client.nextTcpPacket()));
        assertEquals(2, seq(Objects.requireNonNull(client.udp.poll(Hang.SECONDS, TimeUnit.SECONDS))));
    }

    /**
     * A tick that queues a lot wakes the writer once {@link Conn#WAKE_BYTES} wait, without its flush, so the tick's
     * burst is written while it is queued instead of counting whole against the write cap (LifecycleTest's 5,000 chat
     * lines). State, not speed: {@link Conn#wakes} counts the wakes the senders issue, whatever the writer is doing.
     */
    @Test
    void aWriterWakeIsIssuedOnceWakeBytesWait() throws Exception{
        Transport transport = bind(Transport.Config.defaults().withMaxQueuedBytes(64 << 20)
            .withTimers(3_600_000, 3_600_000, 3_600_000, 3_600_000));
        // the writer stuck in a write behind a client that doesn't read, so it takes nothing the test queues next
        RawClient client = slowClient(transport);
        Conn conn = handler.nextConnected();
        var random = new Random(5);
        int bulk = 400;
        for(int i = 0; i < bulk; i++) assertTrue(conn.queue(numbered(106, i, 20_000, random), true));
        conn.flush();
        TransportTest.await("the writer to block", () -> conn.writeStartNanos != 0 && conn.queuedBytes() > 0);

        long wakes = conn.wakes();
        int queued = 0, frames = bulk;
        // just under the threshold: queued, no flush, no wake
        while(true){
            Frame frame = numbered(106, frames, 100, random);
            if(queued + frame.size() >= Conn.WAKE_BYTES) break;
            assertTrue(conn.queue(frame, true));
            queued += frame.size();
            frames++;
        }
        assertEquals(wakes, conn.wakes(), "under " + Conn.WAKE_BYTES + " B and no flush: no wake");
        assertTrue(conn.unflushed());
        // the frame that reaches it wakes the writer, in the same call
        assertTrue(conn.queue(numbered(106, frames++, 100, random), true));
        assertEquals(wakes + 1, conn.wakes(), "reaching " + Conn.WAKE_BYTES + " B wakes the writer without a flush");
        assertFalse(conn.unflushed());

        // once the client reads, everything arrives in order, though nothing flushed the last frames (a long wait: it
        // only catches a writer that never woke)
        client.start();
        int seen = 0;
        while(seen < frames){
            byte[] body = client.nextTcp();
            if(!framework(body)) assertEquals(seen++, seq(body));
        }
        assertTrue(conn.isOpen());
    }

    @Test
    void theSweeperFlushesWhatASenderLeft() throws Exception{
        Transport transport = bind(Transport.Config.defaults().withTimers(3_600_000, 3_600_000, 3_600_000, 3_600_000));
        RawClient client = new RawClient(transport.address());
        open.add(client);
        client.start().handshake();
        Conn conn = handler.nextConnected();
        TransportTest.await("the handshake's reply written", () -> conn.queuedBytes() == 0);
        long wakes = conn.wakes();
        assertTrue(conn.queue(numbered(106, 7, 10, new Random(2)), true));
        assertTrue(conn.unflushed());
        assertEquals(wakes, conn.wakes(), "queueing wakes nobody");
        transport.sweep();
        assertFalse(conn.unflushed(), "the sweep flushed it");
        assertEquals(wakes + 1, conn.wakes());
        assertEquals(7, seq(client.nextTcpPacket()));
    }

    @Test
    void datagramsOverTheUdpCapAreDroppedAndTheConnectionKept() throws Exception{
        Transport transport = bind(Transport.Config.defaults().withMaxQueuedBytes(64 << 20).withMaxQueuedUdpBytes(10_000)
            .withTimers(3_600_000, 3_600_000, 3_600_000, 50));
        RawClient client = slowClient(transport);
        Conn conn = handler.nextConnected();
        var random = new Random(3);
        for(int i = 0; i < 400; i++) conn.queue(numbered(106, i, 20_000, random), true);
        conn.flush();
        TransportTest.await("the writer to block", () -> conn.writeStartNanos != 0);
        Frame snapshot = numbered(49, 0, 900, random);
        int sent = 0;
        for(int i = 0; i < 30; i++) if(conn.sendUdp(snapshot)) sent++;
        int fits = 10_000 / snapshot.size();
        assertEquals(fits, sent, "datagrams up to the cap are queued");
        assertEquals(30 - fits, transport.stats().udpShed.sum());
        assertTrue(conn.queuedUdpBytes() <= 10_000);
        assertTrue(conn.isOpen(), "a full UDP queue drops, it never closes");
        assertTrue(conn.sendTcp(numbered(106, 400, 10, random)), "reliable frames still queue");
    }

    @Test
    void packetSpamBlacklistsBeforeTheClose() throws Exception{
        var events = new LinkedBlockingQueue<String>();
        var recorder = new TransportTest.Recorder(){
            @Override
            public void blacklisted(Conn conn){
                events.add("blacklisted " + conn.remote().getAddress().getHostAddress());
            }

            @Override
            public void disconnected(Conn conn, String reason){
                events.add("closed " + reason);
            }
        };
        Transport transport = Transport.bind(LOOPBACK, Transport.Config.defaults(), recorder, now::get);
        open.add(transport);
        RawClient client = new RawClient(transport.address());
        open.add(client);
        client.start().handshake();
        Frame frame = Frame.packet(84, new byte[8]);
        try{
            for(int i = 0; i < 400; i++) client.sendTcp(frame);
        }catch(java.io.IOException reset){
            // closed while we wrote
        }
        assertEquals("blacklisted 127.0.0.1", events.poll(Hang.SECONDS, TimeUnit.SECONDS));
        assertEquals("closed packet spam", events.poll(Hang.SECONDS, TimeUnit.SECONDS));
        assertNull(events.poll(200, TimeUnit.MILLISECONDS), "once");
        assertEquals(1, transport.stats().rateClosed.sum());
    }

    @Test
    void aCloseLimitDoesNotBlacklist() throws Exception{
        var blacklisted = new AtomicInteger();
        var recorder = new TransportTest.Recorder(){
            @Override
            public void blacklisted(Conn conn){
                blacklisted.incrementAndGet();
            }
        };
        var limits = RateLimits.vanilla().with(3, new RateLimits.Limit(2, 0.001, RateLimits.Action.CLOSE));
        Transport transport = Transport.bind(LOOPBACK, Transport.Config.defaults().withLimits(limits), recorder, now::get);
        open.add(transport);
        RawClient client = new RawClient(transport.address());
        open.add(client);
        client.start().handshake();
        for(int i = 0; i < 3; i++) client.sendTcp(Frame.packet(3, new byte[]{1}));
        assertEquals("packet spam: id 3", recorder.nextClose());
        assertEquals(0, blacklisted.get());
    }

    @Test
    void theRingKeepsOrderAndKindsAcrossGrowth(){
        var ring = new Conn.FrameRing();
        var frames = new ArrayList<Frame>();
        for(int i = 0; i < 100; i++) frames.add(Frame.packet(i % 200 + 2, new byte[]{(byte)i}));
        int in = 0, out = 0;
        // wrap the head around before it grows, several times
        for(int round = 0; round < 10; round++){
            for(int i = 0; i < 7 + round * 3 && in < frames.size(); i++, in++) ring.add(frames.get(in), in % 3 == 0);
            for(int i = 0; i < 5 && !ring.isEmpty(); i++, out++){
                assertEquals(out % 3 == 0, ring.headUdp(), "kind of " + out);
                assertSame(frames.get(out), ring.poll());
            }
        }
        while(!ring.isEmpty()){
            assertEquals(out % 3 == 0, ring.headUdp());
            assertSame(frames.get(out++), ring.poll());
        }
        assertEquals(in, out);
        assertNull(ring.poll());
    }
}
