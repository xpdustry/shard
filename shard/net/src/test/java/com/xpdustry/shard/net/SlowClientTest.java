package com.xpdustry.shard.net;

import org.junit.jupiter.api.*;

import java.net.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * When a client is too slow for its reliable queue (W4.11): a burst over the soft cap may drain within the grace, a
 * backlog that stays over it past the grace closes the connection, and a burst over the hard ceiling closes it at once.
 * The clock is the test's and moves only when it {@link #advance}s; only the test {@linkplain Transport#sweep sweeps}.
 * So whether the grace passed depends on the time the test says, never on how fast the machine drained the socket.
 */
class SlowClientTest{
    static final int SOFT = 256 * 1024, GRACE = 3_000;
    /** No timer of the transport's own fires, and its sweeper sleeps for good: the test sweeps by hand. */
    static final int NEVER = 3_600_000;

    final TransportTest.Recorder handler = new TransportTest.Recorder();
    final List<AutoCloseable> open = new ArrayList<>();
    final AtomicLong now = new AtomicLong(1_000_000_000L);

    Transport bind(Transport.Config config) throws Exception{
        Transport transport = Transport.bind(SendOrderTest.LOOPBACK, config.withTimers(NEVER, NEVER, NEVER, NEVER), handler, now::get);
        open.add(transport);
        return transport;
    }

    void advance(long millis){
        now.addAndGet(millis * 1_000_000L);
    }

    @AfterEach
    void close() throws Exception{
        for(AutoCloseable closeable : open.reversed()) closeable.close();
    }

    /** A client that reads nothing until it {@linkplain RawClient#start starts}: a 4 KiB receive buffer, the handshake by hand. */
    RawClient slowClient(Transport transport) throws Exception{
        var client = new RawClient(transport.address(), new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 4096);
        open.add(client);
        var read = ByteBuffer.allocate(8);
        while(read.hasRemaining()) client.channel.read(read);
        client.sendUdp(RawClient.registerUdp(read.getInt(4)));
        return client;
    }

    /**
     * 8 MB of Calls, more than the kernel's buffers on both sides, behind a client that reads nothing: the writer blocks
     * and the queue stays far over the soft cap until the client reads.
     *
     * @return the frames queued
     */
    static int backlog(Conn conn) throws InterruptedException{
        var random = new Random(411);
        int frames = 400;
        for(int i = 0; i < frames; i++) assertTrue(conn.queue(SendOrderTest.numbered(106, i, 20_000, random), true), "frame " + i);
        conn.flush();
        TransportTest.await("the writer to block over the soft cap", () -> conn.writeStartNanos != 0 && conn.queuedBytes() > SOFT);
        return frames;
    }

    @Test
    void aBurstOverTheSoftCapThatDrainsWithinTheGraceKeepsTheConnection() throws Exception{
        Transport transport = bind(Transport.Config.defaults().withQueueCaps(SOFT, 64 << 20, GRACE));
        RawClient client = slowClient(transport);
        Conn conn = handler.nextConnected();
        long burstAt = now.get();
        int frames = backlog(conn);
        assertTrue(conn.isOpen(), "going over the soft cap doesn't close");
        assertEquals(burstAt | 1, conn.overCapNanos, "the grace runs from when the queue went over");

        // the whole grace, still over: kept
        advance(GRACE);
        transport.sweep();
        assertTrue(conn.isOpen(), "closed within the grace: " + conn.closeReason());

        // the client reads; once the writer is back under the soft cap the grace is over, so a later sweep keeps it
        client.start();
        int seen = 0;
        while(seen < frames){
            byte[] body = client.nextTcp();
            if(!SendOrderTest.framework(body)) assertEquals(seen++, SendOrderTest.seq(body));
        }
        TransportTest.await("the queue back under the soft cap", () -> conn.queuedBytes() <= SOFT);
        assertEquals(0, conn.overCapNanos, "back under the soft cap");
        advance(10L * GRACE);
        transport.sweep();
        assertTrue(conn.isOpen(), "closed after it drained: " + conn.closeReason());
        assertEquals(0, transport.stats().slowClients.sum());
    }

    @Test
    void aBacklogHeldOverTheSoftCapPastTheGraceClosesAsSlow() throws Exception{
        Transport transport = bind(Transport.Config.defaults().withQueueCaps(SOFT, 64 << 20, GRACE));
        slowClient(transport);
        Conn conn = handler.nextConnected();
        backlog(conn);

        advance(GRACE);
        transport.sweep();
        assertTrue(conn.isOpen(), "closed within the grace: " + conn.closeReason());
        advance(1);
        transport.sweep();
        assertFalse(conn.isOpen(), "still open over the soft cap past the grace");
        assertEquals("slow client: write queue over " + SOFT + " B for over " + GRACE + " ms", handler.nextClose());
        assertEquals(1, transport.stats().slowClients.sum());
        assertEquals(0, transport.stats().deadlines.sum());
        assertFalse(conn.sendTcp(Frame.packet(106, new byte[4])), "closed connections refuse frames");
    }

    @Test
    void aBurstOverTheHardCeilingClosesAtOnce() throws Exception{
        Transport transport = bind(Transport.Config.defaults());
        int hard = transport.config().hardMaxQueuedBytes();
        assertEquals(1 << 20, hard);
        assertEquals(SOFT, transport.config().maxQueuedBytes());
        slowClient(transport);
        Conn conn = handler.nextConnected();

        byte[] noise = new byte[20_000];
        new Random(6).nextBytes(noise);
        Frame big = Frame.packet(49, noise);
        // The writer only ever takes bytes off, so a refusal means the queue (read just before) was within a frame of
        // the ceiling. No sweep runs and the clock never moves: the frame's own call closes it.
        long held = -1;
        boolean refused = false;
        for(int sent = 0; sent < 100_000 && !refused; sent++){
            held = conn.queuedBytes();
            refused = !conn.sendTcp(big);
            if(sent % 50 == 0) Thread.sleep(1); // let the writer fill the kernel buffers
        }
        assertTrue(refused, "a queue over the " + hard + " B ceiling was kept");
        assertTrue(held + big.size() > hard, "refused at " + held + " B");
        assertFalse(conn.isOpen(), "the ceiling closes in the call that crosses it");
        assertEquals("slow client: write queue over the " + hard + " B ceiling", handler.nextClose());
        assertEquals(1, transport.stats().slowClients.sum());
        assertFalse(conn.sendTcp(big), "closed connections refuse frames");
    }
}
