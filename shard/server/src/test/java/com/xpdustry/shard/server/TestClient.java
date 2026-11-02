package com.xpdustry.shard.server;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.net.*;
import com.xpdustry.shard.wire.Bootstrap;
import com.xpdustry.shard.wire.v160_7.Packets;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A byte-level client over loopback that sorts what it receives by packet id, as Arc's client frames it, and also
 * keeps the TCP packets in arrival order for {@link #loads}. Public for the tests of other packages.
 */
public final class TestClient implements AutoCloseable{
    final SocketChannel tcp;
    final DatagramChannel udp;
    final ConcurrentHashMap<Integer, LinkedBlockingQueue<byte[]>> received = new ConcurrentHashMap<>();
    final CountDownLatch closed = new CountDownLatch(1);
    private final Lz4 lz4 = new Lz4();
    /** When a TCP frame last went out, for {@link #keepAlive}. */
    private volatile long lastWrite = System.nanoTime();
    /** TCP bodies in arrival order, until {@link #loads} plays them. */
    private final LinkedBlockingQueue<byte[]> ordered = new LinkedBlockingQueue<>();
    /** {@link #loads}' state: its streams being built, by id, and what it saw. */
    private final HashMap<Integer, ByteArrayOutputStream> building = new HashMap<>();
    private final HashMap<Integer, Integer> totals = new HashMap<>();
    private final ArrayList<String> history = new ArrayList<>();
    /** Vanilla's {@code clientLoaded}, and the {@code worldDataBegin}s its {@code packetQueue} holds meanwhile. */
    private boolean clientLoaded;
    private int queuedBegins;

    public TestClient(InetSocketAddress server) throws Exception{
        this(server, null);
    }

    /** A client whose sockets bind {@code local} (another loopback address, 127.0.0.2...), or any if null. */
    public TestClient(InetSocketAddress server, InetAddress local) throws Exception{
        tcp = SocketChannel.open();
        if(local != null) tcp.bind(new InetSocketAddress(local, 0));
        tcp.connect(server);
        udp = DatagramChannel.open();
        if(local != null) udp.bind(new InetSocketAddress(local, 0));
        udp.connect(server);
        Thread.ofVirtual().start(() -> {
            var buffer = ByteBuffer.allocate(1 << 17);
            try{
                while(tcp.read(buffer) >= 0){
                    buffer.flip();
                    while(buffer.remaining() >= 2 && buffer.remaining() >= 2 + (buffer.getShort(buffer.position()) & 0xffff)){
                        byte[] body = new byte[buffer.getShort() & 0xffff];
                        buffer.get(body);
                        if(body.length > 0 && body[0] != Frame.FRAMEWORK) ordered.add(body);
                        add(body);
                    }
                    buffer.compact();
                }
            }catch(IOException ignored){
            }
            closed.countDown();
        });
        Thread.ofVirtual().start(() -> {
            var buffer = ByteBuffer.allocate(1 << 16);
            try{
                while(true){
                    buffer.clear();
                    udp.read(buffer);
                    byte[] body = new byte[buffer.flip().remaining()];
                    buffer.get(body);
                    add(body);
                }
            }catch(IOException ignored){
            }
        });
        byte[] register = next(-2 & 0xff);
        assertEquals(Frame.REGISTER_TCP, register[1]);
        int id = ByteBuffer.wrap(register, 2, 4).getInt();
        // Arc's client sends RegisterUDP until the reply, a datagram can be lost, so the retries run to the hang timeout
        long deadline = System.nanoTime() + Hang.NANOS;
        while(System.nanoTime() < deadline){
            udp.write(ByteBuffer.allocate(6).put(Frame.FRAMEWORK).put(Frame.REGISTER_UDP).putInt(id).flip());
            byte[] reply = queue(-2 & 0xff).poll(100, TimeUnit.MILLISECONDS);
            if(reply != null && reply[1] == Frame.REGISTER_UDP){
                // TCP before the UDP side registered would break the protocol
                Thread.ofVirtual().start(this::keepAlive);
                return;
            }
        }
        fail("no RegisterUDP reply within " + Hang.SECONDS + " s");
    }

    private void add(byte[] body){
        if(body.length >= 2 && body[0] == Frame.FRAMEWORK && body[1] == Frame.KEEP_ALIVE) return;
        queue(body[0] & 0xff).add(body);
    }

    /** How long a test waits for what it expects before it fails: {@link Hang#TIMEOUT}, a failure detector. */
    public static final Duration WAIT = Hang.TIMEOUT;
    /**
     * How long the TCP side may stay quiet before the client keeps it alive. Arc's {@code keepAliveTCP} is 8 s against the
     * server's 12 s read timeout, which leaves a starved client thread 4 s; a quarter of that leaves it 10.
     */
    static final Duration KEEP_ALIVE = Duration.ofSeconds(2);

    public LinkedBlockingQueue<byte[]> queue(int id){
        return received.computeIfAbsent(id, key -> new LinkedBlockingQueue<>());
    }

    /** The next body of packet {@code id}. */
    public byte[] next(int id) throws InterruptedException{
        byte[] body = queue(id).poll(WAIT.toNanos(), TimeUnit.NANOSECONDS);
        if(body == null) fail("no packet " + id + " within " + Hang.SECONDS + " s");
        return body;
    }

    /** The next payload of packet {@code id}, decompressed. */
    public byte[] payload(int id) throws InterruptedException{
        byte[] body = next(id);
        return Frame.decodePayload(body, 1, body.length - 1);
    }

    /** Waits for the chat message {@code text}, skipping others. */
    public void message(String text) throws InterruptedException{
        var seen = new ArrayList<String>();
        long deadline = System.nanoTime() + WAIT.toNanos();
        while(System.nanoTime() < deadline){
            byte[] body = queue(Packets.SEND_MESSAGE).poll(100, TimeUnit.MILLISECONDS);
            if(body == null) continue;
            String message = com.xpdustry.shard.wire.Codecs.readString(new Reads(Frame.decodePayload(body, 1, body.length - 1)));
            if(message.equals(text)) return;
            seen.add(message);
        }
        fail("no message '" + text + "' within " + WAIT.toSeconds() + " s; got " + seen);
    }

    /**
     * The TCP packets that arrive until packet {@code id} does, in order and {@code id} last: a chat message as
     * "message text", a kick as "kick reason", others by id. It reads the log {@link #loads} reads, so call it after
     * the loads.
     */
    public List<String> until(int id) throws InterruptedException{
        var seen = new ArrayList<String>();
        long deadline = System.nanoTime() + WAIT.toNanos();
        while(System.nanoTime() < deadline){
            byte[] body = ordered.poll(100, TimeUnit.MILLISECONDS);
            if(body == null) continue;
            int type = body[0] & 0xff;
            seen.add(switch(type){
                case Packets.SendMessage.ID -> "message " + com.xpdustry.shard.wire.Codecs.readString(new Reads(Frame.decodePayload(body, 1, body.length - 1)));
                case Packets.Kick.ID -> "kick " + com.xpdustry.shard.wire.Codecs.readString(new Reads(Frame.decodePayload(body, 1, body.length - 1)));
                default -> String.valueOf(type);
            });
            if(type == id) return seen;
        }
        return fail("no packet " + id + " within " + WAIT.toSeconds() + " s; got " + seen);
    }

    /**
     * Waits until the server answers a ping sent now. The server writes to a client in order, so whatever it queued for
     * this client before the answer has arrived by then: a barrier for "nothing else is coming", in place of a sleep
     * that hopes the writer had time.
     */
    public void barrier() throws Exception{
        byte[] mark = ByteBuffer.allocate(8).putLong(System.nanoTime()).array();
        tcp(Packets.PING, mark);
        while(!Arrays.equals(payload(Packets.PING_RESPONSE), mark)){
            // an earlier barrier's or test's answer
        }
    }

    /** Whether packet {@code id} arrives within {@code millis}: an absence window for a packet that shouldn't come. */
    public boolean receives(int id, long millis) throws InterruptedException{
        return queue(id).poll(millis, TimeUnit.MILLISECONDS) != null;
    }

    public void tcp(int id, byte[] payload) throws IOException{
        write(Frame.packet(id, payload, lz4).tcp());
    }

    /** One TCP frame, whole: the keepalive thread writes too. */
    private void write(ByteBuffer buffer) throws IOException{
        synchronized(tcp){
            while(buffer.hasRemaining()) tcp.write(buffer);
            lastWrite = System.nanoTime();
        }
    }

    /**
     * Arc's client keepalive ({@code Client.keepAliveTCP}, {@code TcpConnection.needsKeepAlive}), at {@link #KEEP_ALIVE}
     * instead of 8 s. Without it, the server's 12 s read timeout closes a test's quiet client, with nothing logged on a
     * quiet server: a client that confirmed early and waits while a loaded VM runs the rest of the test (LifecycleTest's
     * chatter, which once timed out under the full check).
     */
    private void keepAlive(){
        try{
            while(tcp.isOpen()){
                Thread.sleep(500);
                if(keepingAlive && System.nanoTime() - lastWrite > KEEP_ALIVE.toNanos()) write(Frame.KEEP_ALIVE_FRAME.tcp());
            }
        }catch(IOException | InterruptedException ignored){
        }
    }

    /** Whether {@link #keepAlive} runs on the client's own clock; a test on the server's virtual time sends them itself. */
    private volatile boolean keepingAlive = true;

    /** Stops the client's own keepalives: from now on it sends only what the test sends, {@link #keepAliveNow} included. */
    public void stopKeepingAlive(){
        keepingAlive = false;
    }

    /** One keepalive now, as Arc's client sends after {@code keepAliveTCP} of quiet. */
    public void keepAliveNow() throws IOException{
        write(Frame.KEEP_ALIVE_FRAME.tcp());
    }

    /** Sends a connect packet as vanilla's client would, named {@code name} with an id made from it. */
    public void connect(String name) throws IOException{
        connect(Connect.named(name).packet());
    }

    public void connect(Bootstrap.Connect connect) throws IOException{
        var out = new Writes();
        Bootstrap.write(out, connect);
        tcp(Packets.CONNECT_PACKET, out.toByteArray());
    }

    /** A connect packet to craft: a stock client named {@code name}, with one field changed at a time. */
    public record Connect(int build, String type, String name, String usid, byte[] id, List<String> mods){
        public static Connect named(String name){
            return new Connect(160, "official", name, "usid-" + name, TestClient.id(name), List.of());
        }

        Connect build(int build){
            return new Connect(build, type, name, usid, id, mods);
        }

        Connect type(String type){
            return new Connect(build, type, name, usid, id, mods);
        }

        Connect usid(String usid){
            return new Connect(build, type, name, usid, id, mods);
        }

        Connect id(byte[] id){
            return new Connect(build, type, name, usid, id, mods);
        }

        Connect mods(List<String> mods){
            return new Connect(build, type, name, usid, id, mods);
        }

        public Bootstrap.Connect packet(){
            return Bootstrap.Connect.of(build, type, name, "en", usid, id, false, 0xd37fffff, mods);
        }
    }

    /** An 8-byte client id derived from {@code seed}. */
    public static byte[] id(String seed){
        return ByteBuffer.allocate(8).putLong(seed.hashCode() * 0x9E3779B97F4A7C15L).array();
    }

    public void chat(String text) throws IOException{
        var out = new Writes();
        com.xpdustry.shard.wire.Codecs.writeString(out, text);
        tcp(Packets.SEND_CHAT_MESSAGE, out.toByteArray());
    }

    public void confirm() throws IOException{
        tcp(Packets.CONNECT_CONFIRM, new byte[0]);
    }

    /** The kick reason id of the next {@code kick(reason)}. */
    public int kickReason() throws InterruptedException{
        return payload(Packets.Kick2.ID)[0];
    }

    /** Reads one stream and returns it inflated: the world stream {@code NetworkIO.loadWorld} reads. */
    public byte[] loadWorld() throws Exception{
        Streamed stream = stream();
        assertEquals(Packets.WORLD_STREAM, stream.type(), "a world stream");
        return new InflaterInputStream(new ByteArrayInputStream(stream.data())).readAllBytes();
    }

    /** A whole stream as it arrived: its {@code Streamable} packet id and its bytes, not inflated. */
    public record Streamed(int type, byte[] data){
    }

    /** Reads the next stream whole, of any type (the world's, the asset handshake's). */
    public Streamed stream() throws Exception{
        var begin = ByteBuffer.wrap(payload(Packets.STREAM_BEGIN));
        int streamId = begin.getInt(), total = begin.getInt();
        int type = begin.get();
        var data = new ByteArrayOutputStream();
        while(data.size() < total){
            var chunk = ByteBuffer.wrap(payload(Packets.STREAM_CHUNK));
            assertEquals(streamId, chunk.getInt());
            data.write(chunk.array(), 6, chunk.getShort());
        }
        return new Streamed(type, data.toByteArray());
    }

    /** {@code Call.requestAssets(ids)}, as vanilla's client answers an {@code AssetRequirementStream}. */
    public void requestAssets(short... ids) throws IOException{
        var out = new Writes();
        com.xpdustry.shard.wire.Codecs.writeShorts(out, ids);
        tcp(Packets.REQUEST_ASSETS, out.toByteArray());
    }

    /** {@code Call.requestWorld()}, once the client has loaded its assets. */
    public void requestWorld() throws IOException{
        tcp(Packets.REQUEST_WORLD, new byte[0]);
    }

    /** The head of a world stream: what {@code NetworkIO.loadWorld} reads before the map. */
    public record WorldData(String rules, Map<String, String> tags, int wave, int player, int size){
    }

    /** Reads one world stream and parses its head. */
    public WorldData world() throws Exception{
        return parse(loadWorld());
    }

    static WorldData parse(byte[] raw){
        var in = new Reads(raw);
        in.skip(PATCHES);
        String rules = in.str();
        in.str(); // locales
        var tags = new LinkedHashMap<String, String>();
        for(int i = in.s(); i > 0; i--) tags.put(in.str(), in.str());
        int wave = in.i();
        in.f();
        in.d();
        in.l();
        in.l();
        return new WorldData(rules, tags, wave, in.i(), raw.length);
    }

    private static final int PATCHES = com.xpdustry.shard.compat.v160.world.WorldStreamWriter.patches(com.xpdustry.shard.content.epoch.Epoch.vanilla()).length;

    /**
     * Plays the TCP packets received since the last call through vanilla's client ({@code Net.handleClientReceived}):
     * every world stream it completes is a load ({@code NetClient.java:149}), which vanilla confirms, and
     * {@code worldDataBegin} starts over. A {@code worldDataBegin} that comes while the client loads waits in its
     * {@code packetQueue} and is replayed after the next load, wiping that world: {@code "begin (replayed)"}. The
     * packets stay in the per-id queues too.
     *
     * @return everything seen so far, in order: {@code "begin"}, {@code "begin (replayed)"}, and
     *     {@code "load <map name>"} per completed stream
     */
    public synchronized List<String> loads() throws IOException{
        for(byte[] body; (body = ordered.poll()) != null; ){
            int id = body[0] & 0xff;
            if(id == Packets.WorldDataBegin.ID){
                if(clientLoaded){
                    history.add("begin");
                    clientLoaded = false;
                }else{
                    queuedBegins++;
                }
            }else if(id == Packets.STREAM_BEGIN){
                var begin = ByteBuffer.wrap(Frame.decodePayload(body, 1, body.length - 1));
                int stream = begin.getInt();
                totals.put(stream, begin.getInt());
                building.put(stream, new ByteArrayOutputStream());
            }else if(id == Packets.STREAM_CHUNK){
                var chunk = ByteBuffer.wrap(Frame.decodePayload(body, 1, body.length - 1));
                int stream = chunk.getInt();
                ByteArrayOutputStream data = building.get(stream);
                data.write(chunk.array(), 6, chunk.getShort());
                if(data.size() >= totals.get(stream)){
                    building.remove(stream);
                    byte[] raw = new InflaterInputStream(new ByteArrayInputStream(data.toByteArray())).readAllBytes();
                    history.add("load " + parse(raw).tags().get("name"));
                    clientLoaded = true;
                    for(; queuedBegins > 0; queuedBegins--){
                        history.add("begin (replayed)");
                        clientLoaded = false;
                    }
                }
            }
        }
        return List.copyOf(history);
    }

    /** Waits until {@link #loads} has {@code count} entries, and returns them. */
    public List<String> loads(int count) throws Exception{
        long deadline = System.nanoTime() + WAIT.toNanos();
        List<String> seen;
        while((seen = loads()).size() < count){
            if(System.nanoTime() > deadline) fail("saw only " + seen + " within " + Hang.SECONDS + " s");
            Thread.sleep(10);
        }
        return seen;
    }

    /** Whether the server closed the connection within {@code millis}; {@link Hang#MILLIS} to wait for it, a short one for "it stays". */
    public boolean closedWithin(long millis) throws InterruptedException{
        return closed.await(millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() throws IOException{
        tcp.close();
        udp.close();
    }
}
