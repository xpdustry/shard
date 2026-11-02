package com.xpdustry.shard.server.session;

import com.xpdustry.shard.server.Hang;
import com.xpdustry.shard.net.*;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Real registered {@link Conn}s over loopback, for tests that need one; the clients do nothing after the handshake.
 * {@code disconnected} runs as the handler's, on whichever thread closed the connection.
 */
final class LoopbackConns implements AutoCloseable{
    private final LinkedBlockingQueue<Conn> connected = new LinkedBlockingQueue<>();
    private final Transport transport;
    private final List<Closeable> clients = Collections.synchronizedList(new ArrayList<>());

    LoopbackConns() throws IOException{
        this(conn -> {
        });
    }

    LoopbackConns(java.util.function.Consumer<Conn> disconnected) throws IOException{
        transport = Transport.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), Transport.Config.defaults(),
            new Transport.Handler(){
                @Override
                public void connected(Conn conn){
                    connected.add(conn);
                }

                @Override
                public void received(Conn conn, Packet packet){
                }

                @Override
                public void disconnected(Conn conn, String reason){
                    disconnected.accept(conn);
                }
            });
    }

    Conn open() throws Exception{
        var tcp = SocketChannel.open(transport.address());
        var udp = DatagramChannel.open().connect(transport.address());
        clients.add(tcp);
        clients.add(udp);
        var register = ByteBuffer.allocate(8);
        while(register.hasRemaining()) tcp.read(register);
        udp.write(ByteBuffer.allocate(6).put(Frame.FRAMEWORK).put(Frame.REGISTER_UDP).putInt(register.getInt(4)).flip());
        return Objects.requireNonNull(connected.poll(Hang.SECONDS, TimeUnit.SECONDS), "no connection");
    }

    @Override
    public void close() throws IOException{
        for(Closeable client : clients) client.close();
        transport.close();
    }
}
