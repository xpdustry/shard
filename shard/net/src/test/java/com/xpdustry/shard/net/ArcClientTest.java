package com.xpdustry.shard.net;

import arc.net.*;
import arc.net.FrameworkMessage.*;
import net.jpountz.lz4.*;
import org.junit.jupiter.api.*;

import java.net.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The transport against Arc's own {@link Client} from the vanilla jar: the handshake, framework messages, packets
 * both ways with vanilla's compression rule, and stream chunking. The serializer is vanilla's
 * {@code ArcNetProvider.PacketSerializer} (lines 476-637) with raw packets instead of {@code Packet} classes, so the
 * test needs none of vanilla's static state.
 *
 * <p>The transport's timers run on {@link #now}, which moves only when a test advances it (as in
 * {@link TransportTest}), so a keepalive is sent when the test says and a slow machine can't time a connection out.
 * Arc's client has its own read timeout on the real clock, which would close the connection of a test that took 12 s
 * because the server, on virtual time, sends no keepalives; the test switches it off.
 */
class ArcClientTest{
    record Raw(int id, byte[] payload){
    }

    static final class VanillaSerializer implements NetSerializer{
        final LZ4Compressor compressor = LZ4Factory.fastestJavaInstance().fastCompressor();
        final LZ4SafeDecompressor decompressor = LZ4Factory.fastestJavaInstance().safeDecompressor();

        @Override
        public void write(ByteBuffer buffer, Object object){
            if(object instanceof FrameworkMessage message){
                buffer.put((byte)-2);
                switch(message){
                    case Ping ping -> buffer.put((byte)0).putInt(ping.id).put(ping.isReply ? (byte)1 : 0);
                    case DiscoverHost ignored -> buffer.put((byte)1);
                    case KeepAlive ignored -> buffer.put((byte)2);
                    case RegisterUDP register -> buffer.put((byte)3).putInt(register.connectionID);
                    case RegisterTCP register -> buffer.put((byte)4).putInt(register.connectionID);
                    default -> throw new IllegalArgumentException(message.toString());
                }
                return;
            }
            Raw raw = (Raw)object;
            int length = raw.payload().length;
            buffer.put((byte)raw.id()).putShort((short)length);
            if(length < 36 || raw.id() == Frame.STREAM_CHUNK){
                buffer.put((byte)0).put(raw.payload());
            }else{
                buffer.put((byte)1);
                int written = compressor.compress(ByteBuffer.wrap(raw.payload()), 0, length, buffer, buffer.position(), buffer.remaining());
                buffer.position(buffer.position() + written);
            }
        }

        @Override
        public Object read(ByteBuffer buffer){
            byte id = buffer.get();
            if(id == -2){
                return switch(buffer.get()){
                    case 0 -> {
                        var ping = new Ping();
                        ping.id = buffer.getInt();
                        ping.isReply = buffer.get() == 1;
                        yield ping;
                    }
                    case 1 -> FrameworkMessage.discoverHost;
                    case 2 -> FrameworkMessage.keepAlive;
                    case 3 -> {
                        var register = new RegisterUDP();
                        register.connectionID = buffer.getInt();
                        yield register;
                    }
                    case 4 -> {
                        var register = new RegisterTCP();
                        register.connectionID = buffer.getInt();
                        yield register;
                    }
                    default -> throw new IllegalStateException("Unknown framework message!");
                };
            }
            int length = buffer.getShort() & 0xffff;
            byte compression = buffer.get();
            byte[] payload = new byte[length];
            if(compression == 0){
                buffer.get(payload);
            }else{
                int compressed = buffer.remaining();
                decompressor.decompress(buffer, buffer.position(), compressed, ByteBuffer.wrap(payload), 0, length);
                buffer.position(buffer.position() + compressed);
            }
            return new Raw(id & 0xff, payload);
        }
    }

    final TransportTest.Recorder handler = new TransportTest.Recorder();
    /** Virtual time, away from 0, which the transport's timestamps use for "none". */
    final AtomicLong now = new AtomicLong(1_000_000_000L);
    final LinkedBlockingQueue<Object> received = new LinkedBlockingQueue<>();
    Transport transport;
    Client client;

    @BeforeEach
    void connect() throws Exception{
        transport = Transport.bind(TransportTest.LOOPBACK, Transport.Config.defaults().withTimers(12_000, 500, 10_000, 50), handler, now::get);
        // vanilla's client buffers (ArcNetProvider.java:64)
        client = new Client(16384, Frame.MAX_CLIENT_BODY, new VanillaSerializer());
        client.setTimeout(0); // Arc's own read timeout is on the real clock; see the class comment
        client.addListener(new NetListener(){
            @Override
            public void received(Connection connection, Object object){
                received.add(object);
            }
        });
        client.start();
        int port = transport.address().getPort();
        client.connect((int)Hang.MILLIS, InetAddress.getLoopbackAddress(), port, port); // returns once RegisterUDP is answered
    }

    @AfterEach
    void close() throws Exception{
        client.stop();
        client.dispose();
        transport.close();
    }

    /** The next object that isn't a keepalive. */
    Object next() throws InterruptedException{
        while(true){
            Object object = Objects.requireNonNull(received.poll(Hang.SECONDS, TimeUnit.SECONDS), "nothing received");
            if(!(object instanceof KeepAlive)) return object;
        }
    }

    @Test
    void arcsHandshakeCompletes() throws Exception{
        Conn conn = handler.nextConnected();
        assertTrue(client.isConnected());
        assertEquals(conn.id(), client.getID(), "the id from RegisterTCP");
        assertNotNull(conn.udpRemote());
    }

    @Test
    void packetsBothWaysWithVanillasCompression() throws Exception{
        Conn conn = handler.nextConnected();
        byte[] small = {1, 2, 3}, big = new byte[500];
        new Random(8).nextBytes(big);
        Arrays.fill(big, 100, 400, (byte)7);

        client.sendTCP(new Raw(105, small));
        client.sendUDP(new Raw(29, big));
        var got = new HashMap<Integer, Packet>();
        for(int i = 0; i < 2; i++){
            Packet packet = handler.nextPacket();
            got.put(packet.id(), packet);
        }
        assertArrayEquals(small, got.get(105).payload());
        assertTrue(got.get(105).reliable());
        assertArrayEquals(big, got.get(29).payload(), "LZ4 from lz4-java decodes");
        assertFalse(got.get(29).reliable());

        conn.sendTcp(Frame.packet(106, big));
        conn.sendUdp(Frame.packet(49, small));
        var back = new HashMap<Integer, Raw>();
        for(int i = 0; i < 2; i++){
            Raw raw = (Raw)next();
            back.put(raw.id(), raw);
        }
        assertArrayEquals(big, back.get(106).payload(), "our LZ4 decodes with lz4-java");
        assertArrayEquals(small, back.get(49).payload());
    }

    @Test
    void pingsAndKeepalives() throws Exception{
        Conn conn = handler.nextConnected();
        client.updateReturnTripTime();
        Ping reply = (Ping)next();
        assertTrue(reply.isReply);
        assertEquals(0, reply.id);
        received.clear();
        // the writer stamps a write after the socket takes it, which the client's read can beat, and the keepalive interval
        // runs from the stamp. It takes the frame off the queue's count after stamping, so the clock waits for that
        TransportTest.await("the reply written and stamped", () -> conn.queuedBytes() == 0);
        now.addAndGet(501 * 1_000_000L); // past the 500 ms keepalive interval, with nothing written meanwhile
        transport.sweep();
        Object keepAlive = received.poll(Hang.SECONDS, TimeUnit.SECONDS);
        assertInstanceOf(KeepAlive.class, keepAlive, "the server keeps a quiet connection alive");
    }

    @Test
    void streamsReassemble() throws Exception{
        Conn conn = handler.nextConnected();
        byte[] data = new byte[30_000];
        new Random(9).nextBytes(data);
        int id = conn.sendStream(2, data);
        Raw begin = (Raw)next();
        assertEquals(Frame.STREAM_BEGIN, begin.id());
        ByteBuffer header = ByteBuffer.wrap(begin.payload());
        assertEquals(id, header.getInt());
        assertEquals(data.length, header.getInt());
        assertEquals(2, header.get());
        var out = new java.io.ByteArrayOutputStream();
        while(out.size() < data.length){
            Raw chunk = (Raw)next();
            assertEquals(Frame.STREAM_CHUNK, chunk.id());
            ByteBuffer body = ByteBuffer.wrap(chunk.payload());
            assertEquals(id, body.getInt());
            int length = body.getShort();
            out.write(chunk.payload(), 6, length);
        }
        assertArrayEquals(data, out.toByteArray());
    }

    @Test
    void closingTheConnectionDisconnectsArc() throws Exception{
        Conn conn = handler.nextConnected();
        conn.close("kicked");
        assertEquals("kicked", handler.nextClose());
        // Arc's client notices on its own thread: a failure detector, not a bound
        long deadline = System.nanoTime() + Hang.NANOS;
        while(client.isConnected() && System.nanoTime() < deadline) Thread.sleep(10);
        assertFalse(client.isConnected());
    }
}
