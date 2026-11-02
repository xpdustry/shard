package com.xpdustry.shard.net;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * A byte-level Arc client for the tests: it does exactly what it is told, so tests can also break the protocol. A
 * virtual thread reads TCP frames into {@link #tcp}, another reads datagrams into {@link #udp}; each entry is a body.
 */
final class RawClient implements AutoCloseable{
    final SocketChannel channel;
    final DatagramChannel datagrams;
    final LinkedBlockingQueue<byte[]> tcp = new LinkedBlockingQueue<>(), udp = new LinkedBlockingQueue<>();
    volatile boolean eof;
    int connectionId;

    RawClient(InetSocketAddress server) throws IOException{
        this(server, new InetSocketAddress(server.getAddress(), 0), -1);
    }

    /** With {@code receiveBuffer} > 0 the TCP socket gets that SO_RCVBUF, to make a slow client. */
    RawClient(InetSocketAddress server, InetSocketAddress udpLocal, int receiveBuffer) throws IOException{
        channel = SocketChannel.open();
        if(receiveBuffer > 0) channel.setOption(StandardSocketOptions.SO_RCVBUF, receiveBuffer);
        channel.connect(server);
        datagrams = DatagramChannel.open().bind(udpLocal).connect(server);
    }

    /** Starts both readers. A slow-client test doesn't, so nothing drains the socket. */
    RawClient start(){
        Thread.ofVirtual().start(this::readTcp);
        Thread.ofVirtual().start(this::readUdp);
        return this;
    }

    /** Reads RegisterTCP, then sends RegisterUDP until the TCP reply. */
    RawClient handshake() throws Exception{
        byte[] register = nextTcp();
        assertBody(register, Frame.FRAMEWORK, Frame.REGISTER_TCP);
        connectionId = ByteBuffer.wrap(register, 2, 4).getInt();
        // a datagram can be lost, so RegisterUDP is resent until the reply; only a hang runs the loop out
        long deadline = System.nanoTime() + Hang.NANOS;
        while(System.nanoTime() < deadline){
            sendUdp(registerUdp(connectionId));
            byte[] reply = tcp.poll(100, TimeUnit.MILLISECONDS);
            if(reply != null){
                assertBody(reply, Frame.FRAMEWORK, Frame.REGISTER_UDP);
                return this;
            }
        }
        throw new AssertionError("no RegisterUDP reply within " + Hang.SECONDS + " s");
    }

    static byte[] registerUdp(int id){
        return ByteBuffer.allocate(6).put(Frame.FRAMEWORK).put(Frame.REGISTER_UDP).putInt(id).array();
    }

    static void assertBody(byte[] body, byte first, byte second){
        if(body.length < 2 || body[0] != first || body[1] != second){
            throw new AssertionError("expected " + first + "," + second + " but got " + Arrays.toString(Arrays.copyOf(body, Math.min(8, body.length))));
        }
    }

    void sendTcp(Frame frame) throws IOException{
        ByteBuffer buffer = frame.tcp();
        while(buffer.hasRemaining()) channel.write(buffer);
    }

    /** A raw TCP frame: the length prefix, then {@code body}. */
    void sendTcpBody(byte[] body) throws IOException{
        ByteBuffer buffer = ByteBuffer.allocate(2 + body.length).putShort((short)body.length).put(body).flip();
        while(buffer.hasRemaining()) channel.write(buffer);
    }

    void sendUdp(byte[] body) throws IOException{
        datagrams.write(ByteBuffer.wrap(body));
    }

    void sendUdp(Frame frame) throws IOException{
        datagrams.write(frame.udp());
    }

    byte[] nextTcp() throws InterruptedException{
        byte[] body = tcp.poll(Hang.SECONDS, TimeUnit.SECONDS);
        if(body == null) throw new AssertionError("no TCP frame within " + Hang.SECONDS + " s (eof=" + eof + ")");
        return body;
    }

    /** The next TCP body that isn't a keepalive. */
    byte[] nextTcpPacket() throws InterruptedException{
        while(true){
            byte[] body = nextTcp();
            if(!(body.length == 2 && body[0] == Frame.FRAMEWORK && body[1] == Frame.KEEP_ALIVE)) return body;
        }
    }

    /** Waits until the server closes TCP. */
    boolean awaitEof(long millis) throws InterruptedException{
        long end = System.nanoTime() + millis * 1_000_000L;
        while(!eof && System.nanoTime() < end) Thread.sleep(10);
        return eof;
    }

    private void readTcp(){
        ByteBuffer buffer = ByteBuffer.allocate(1 << 17);
        try{
            while(channel.read(buffer) >= 0){
                buffer.flip();
                while(buffer.remaining() >= 2){
                    int length = buffer.getShort(buffer.position()) & 0xffff;
                    if(buffer.remaining() < 2 + length) break;
                    buffer.position(buffer.position() + 2);
                    byte[] body = new byte[length];
                    buffer.get(body);
                    tcp.add(body);
                }
                buffer.compact();
            }
        }catch(IOException ignored){
        }
        eof = true;
    }

    private void readUdp(){
        ByteBuffer buffer = ByteBuffer.allocate(65536);
        try{
            while(true){
                buffer.clear();
                datagrams.read(buffer);
                buffer.flip();
                byte[] body = new byte[buffer.remaining()];
                buffer.get(body);
                udp.add(body);
            }
        }catch(IOException ignored){
        }
    }

    /** The payload of a game packet body. */
    static byte[] payload(byte[] body){
        return Frame.decodePayload(body, 1, body.length - 1);
    }

    @Override
    public void close() throws IOException{
        channel.close();
        datagrams.close();
    }
}
