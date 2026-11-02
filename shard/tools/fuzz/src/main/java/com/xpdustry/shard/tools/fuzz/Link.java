package com.xpdustry.shard.tools.fuzz;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.net.*;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/**
 * One client connection as Arc's client makes it: TCP, then {@code RegisterTCP} from the server, then
 * {@code RegisterUDP} over UDP until the server answers on TCP. Two virtual threads read; game packets go to the
 * {@link Listener} decompressed, framework pings are answered, and the world stream is gathered for {@link Listener#loaded}.
 *
 * <p>Sending is raw: the caller hands whole TCP frames (length prefix included) or UDP datagrams, so a fuzzer can send
 * anything. {@link #packet} frames a payload as vanilla would.
 */
final class Link implements AutoCloseable{
    /** Packet ids a client reads (Packets.java). */
    static final int STREAM_BEGIN = 0, STREAM_CHUNK = 1, CONNECT = 3, ENTITY_SNAPSHOT = 49, KICK = 65, KICK_REASON = 66, PING = 84,
        PING_RESPONSE = 86, CONNECT_CONFIRM = 34, SEND_CHAT_MESSAGE = 105, CLIENT_SNAPSHOT = 29, WORLD_DATA_BEGIN = 172;

    /** What the reader threads report. Called on them: keep it short. */
    interface Listener{
        default void packet(Link link, int id, byte[] payload){
        }

        /** The world stream arrived whole: its deflated bytes. */
        default void loaded(Link link, byte[] stream){
        }

        default void closed(Link link, String why){
        }
    }

    private final Listener listener;
    private final SocketChannel tcp;
    private final DatagramChannel udp;
    private final Object writeLock = new Object();
    final Lz4 lz4 = new Lz4();
    private final CountDownLatch tcpRegistered = new CountDownLatch(1), udpRegistered = new CountDownLatch(1);
    private volatile int connectionId;
    private volatile boolean closed;
    private volatile String closeReason;
    private int streamTotal = -1;
    private ByteArrayOutputStream stream;
    /** Kick reasons seen, for the caller's report. */
    volatile String kick;

    private Link(SocketChannel tcp, DatagramChannel udp, Listener listener){
        this.tcp = tcp;
        this.udp = udp;
        this.listener = listener;
    }

    /**
     * Connects from {@code local} (any 127.x.y.z on Linux) and registers both sides.
     *
     * @throws IOException if the server closes, or registering takes longer than {@code waitMillis}
     */
    static Link open(InetSocketAddress server, InetAddress local, Listener listener, long waitMillis) throws IOException{
        SocketChannel tcp = SocketChannel.open();
        DatagramChannel udp = null;
        try{
            tcp.bind(new InetSocketAddress(local, 0));
            tcp.setOption(StandardSocketOptions.TCP_NODELAY, true);
            tcp.connect(server);
            udp = DatagramChannel.open();
            udp.bind(new InetSocketAddress(local, 0));
            udp.connect(server);
        }catch(IOException | RuntimeException e){
            tcp.close();
            if(udp != null) udp.close();
            throw e;
        }
        var link = new Link(tcp, udp, listener);
        Thread.ofVirtual().name("fuzz-tcp").start(link::readTcp);
        Thread.ofVirtual().name("fuzz-udp").start(link::readUdp);
        try{
            if(!link.tcpRegistered.await(waitMillis, TimeUnit.MILLISECONDS)) throw new IOException("no RegisterTCP: " + link.closeReason);
            long deadline = System.nanoTime() + waitMillis * 1_000_000L;
            // Arc's client sends RegisterUDP every 100 ms until the reply (Client.java:155-176)
            while(!link.udpRegistered.await(100, TimeUnit.MILLISECONDS)){
                if(link.closed || System.nanoTime() > deadline) throw new IOException("no RegisterUDP reply: " + link.closeReason);
                udp.write(ByteBuffer.allocate(6).put(Frame.FRAMEWORK).put(Frame.REGISTER_UDP).putInt(link.connectionId).flip());
            }
        }catch(InterruptedException e){
            link.close();
            Thread.currentThread().interrupt();
            throw new InterruptedIOException();
        }catch(IOException e){
            link.close();
            throw e;
        }
        return link;
    }

    boolean isOpen(){
        return !closed;
    }

    String closeReason(){
        return closeReason;
    }

    /** A game packet framed as vanilla's client does: {@code u16 length | u8 id | u16 raw | u8 lz4 | payload}. */
    byte[] packet(int id, byte[] payload){
        return Frame.packet(id, payload, 0, payload.length, true, lz4).toByteArray();
    }

    /** Sends {@code frame}, a whole TCP frame. */
    void tcp(byte[] frame) throws IOException{
        synchronized(writeLock){
            ByteBuffer buffer = ByteBuffer.wrap(frame);
            while(buffer.hasRemaining()) tcp.write(buffer);
        }
    }

    /** Sends {@code datagram} over UDP. A refused datagram (the server is gone) is the caller's close. */
    void udp(byte[] datagram) throws IOException{
        udp.write(ByteBuffer.wrap(datagram));
    }

    /** {@link #packet} over TCP. */
    void sendTcp(int id, byte[] payload) throws IOException{
        tcp(packet(id, payload));
    }

    /** {@link #packet} over UDP (the frame without its length prefix). */
    void sendUdp(int id, byte[] payload) throws IOException{
        byte[] frame = packet(id, payload);
        udp(Arrays.copyOfRange(frame, 2, frame.length));
    }

    private void readTcp(){
        ByteBuffer buffer = ByteBuffer.allocate(1 << 17);
        String why = "server closed TCP";
        try{
            while(tcp.read(buffer) >= 0){
                buffer.flip();
                while(buffer.remaining() >= 2){
                    int length = buffer.getShort(buffer.position()) & 0xffff;
                    if(buffer.remaining() < 2 + length) break;
                    buffer.position(buffer.position() + 2);
                    byte[] body = new byte[length];
                    buffer.get(body);
                    handle(body);
                }
                buffer.compact();
            }
        }catch(IOException | RuntimeException e){
            why = closed ? closeReason : "tcp: " + e;
        }finally{
            closeWith(why);
        }
    }

    private void readUdp(){
        ByteBuffer buffer = ByteBuffer.allocate(1 << 16);
        try{
            while(!closed){
                buffer.clear();
                udp.read(buffer);
                byte[] body = new byte[buffer.flip().remaining()];
                buffer.get(body);
                handle(body);
            }
        }catch(IOException | RuntimeException e){
            // closed, or the server is gone: TCP reports it
        }
    }

    private void handle(byte[] body) throws IOException{
        if(body.length == 0) return;
        if(body[0] == Frame.FRAMEWORK){
            if(body.length < 2) return;
            switch(body[1]){
                case Frame.REGISTER_TCP -> {
                    connectionId = ByteBuffer.wrap(body, 2, 4).getInt();
                    tcpRegistered.countDown();
                }
                case Frame.REGISTER_UDP -> udpRegistered.countDown();
                case Frame.PING -> {
                    if(body.length == 7 && body[6] == 0) tcp(Frame.ping(ByteBuffer.wrap(body, 2, 4).getInt(), true).toByteArray());
                }
                default -> {
                }
            }
            return;
        }
        int id = body[0] & 0xff;
        byte[] payload = Frame.decodePayload(body, 1, body.length - 1);
        switch(id){
            case STREAM_BEGIN -> {
                streamTotal = ByteBuffer.wrap(payload).getInt(4);
                stream = new ByteArrayOutputStream(Math.max(0, streamTotal));
            }
            case STREAM_CHUNK -> {
                int length = ByteBuffer.wrap(payload).getShort(4);
                if(stream == null) return;
                stream.write(payload, 6, length);
                if(stream.size() >= streamTotal){
                    byte[] whole = stream.toByteArray();
                    stream = null;
                    streamTotal = -1;
                    listener.loaded(this, whole);
                }
            }
            case KICK -> kick = "kick: " + readString(payload);
            case KICK_REASON -> kick = "kick reason #" + (payload.length > 0 ? payload[0] : -1);
            default -> listener.packet(this, id, payload);
        }
    }

    private void closeWith(String why){
        if(closed) return;
        closeReason = kick != null ? why + " (" + kick + ")" : why;
        close();
        listener.closed(this, closeReason);
    }

    @Override
    public void close(){
        if(closeReason == null) closeReason = "closed by the client";
        closed = true;
        try{
            tcp.close();
        }catch(IOException ignored){
        }
        try{
            udp.close();
        }catch(IOException ignored){
        }
    }

    // ---- helpers

    /** {@code ConnectPacket} ({@code Packets.java:153-172}) for player {@code name} with a uuid from {@code seed}. */
    static byte[] connect(long seed, String name){
        byte[] uuid = new byte[8];
        new Random(seed).nextBytes(uuid);
        var crc = new CRC32();
        crc.update(uuid);
        var out = new Writes(96);
        out.i(160);
        string(out, "official");
        string(out, name);
        string(out, "en");
        byte[] usid = new byte[8];
        new Random(~seed).nextBytes(usid);
        string(out, Base64.getEncoder().encodeToString(usid));
        out.b(uuid);
        out.l(crc.getValue());
        out.bool(false);
        out.i(0xffd37fff);
        out.b(0);
        return out.toByteArray();
    }

    /** {@code TypeIO.writeString}: a presence byte, then {@code writeUTF}. */
    static void string(Writes out, String value){
        if(value == null){
            out.b(0);
            return;
        }
        out.b(1);
        out.str(value);
    }

    static String readString(byte[] payload){
        try{
            var in = new Reads(payload);
            return in.b() == 0 ? null : in.str();
        }catch(RuntimeException e){
            return HexFormat.of().formatHex(payload);
        }
    }

    /** The player id in a deflated world stream ({@code NetworkIO.loadWorld}), or -1. */
    static int playerId(byte[] deflated){
        try(var in = new InflaterInputStream(new ByteArrayInputStream(deflated))){
            var reads = new Reads(in.readAllBytes());
            reads.i(); // patch format
            int patches = reads.i();
            for(int i = 0; i < patches; i++) reads.skip(reads.i());
            reads.str(); // rules
            reads.str(); // locales
            int tags = reads.s();
            for(int i = 0; i < tags; i++){
                reads.str();
                reads.str();
            }
            reads.i(); // wave
            reads.f(); // wave countdown
            reads.d(); // tick
            reads.l(); // seeds
            reads.l();
            return reads.i();
        }catch(IOException | RuntimeException e){
            return -1;
        }
    }
}
