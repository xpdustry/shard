package com.xpdustry.shard.server.sync;

import com.xpdustry.shard.server.Hang;
import com.xpdustry.shard.net.*;
import com.xpdustry.shard.server.session.*;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Real {@link Session}s over a loopback transport whose clients do nothing, and a {@link Outbox.Transmit} that records
 * what each session would be sent, in order, instead of sending it.
 */
final class Loopback implements Outbox.Transmit, AutoCloseable{
    /** One frame handed to a session, or a world stream ({@code frame} null). */
    record Sent(long session, Frame frame, boolean reliable, byte[] stream){
        int id(){
            return frame == null ? -1 : frame.id();
        }
    }

    private final LinkedBlockingQueue<Conn> connected = new LinkedBlockingQueue<>();
    private final Transport transport;
    private final Sessions sessions = new Sessions();
    private final List<Closeable> clients = Collections.synchronizedList(new ArrayList<>());
    final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());

    Loopback() throws IOException{
        transport = Transport.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), Transport.Config.defaults(), new Transport.Handler(){
            @Override
            public void connected(Conn conn){
                connected.add(conn);
            }

            @Override
            public void received(Conn conn, Packet packet){
            }

            @Override
            public void disconnected(Conn conn, String reason){
            }
        });
    }

    Session session(String name) throws Exception{
        var tcp = SocketChannel.open(transport.address());
        var udp = DatagramChannel.open().connect(transport.address());
        clients.add(tcp);
        clients.add(udp);
        var register = ByteBuffer.allocate(8);
        while(register.hasRemaining()) tcp.read(register);
        udp.write(ByteBuffer.allocate(6).put(Frame.FRAMEWORK).put(Frame.REGISTER_UDP).putInt(register.getInt(4)).flip());
        Conn conn = Objects.requireNonNull(connected.poll(Hang.SECONDS, TimeUnit.SECONDS), "no connection");
        return sessions.open(conn, new Identity(160, "official", name, "en", "usid", "uuid-" + name, false, -1, List.of()));
    }

    /** What {@code session} was sent, in order. */
    List<Sent> to(Session session){
        synchronized(sent){
            return sent.stream().filter(s -> s.session() == session.id()).toList();
        }
    }

    /** The packet ids {@code session} was sent, in order; a stream is -1. */
    List<Integer> ids(Session session){
        return to(session).stream().map(Sent::id).toList();
    }

    @Override
    public void send(Session session, Frame frame, boolean reliable){
        sent.add(new Sent(session.id(), frame, reliable, null));
    }

    /** The sessions {@link #flush} was called for, in order. */
    final List<Long> flushes = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void flush(Session session){
        flushes.add(session.id());
    }

    /** Streams of these load generations are fenced: {@link Session#stream} would refuse them. */
    final Set<Integer> fenced = Collections.synchronizedSet(new HashSet<>());

    @Override
    public boolean stream(Session session, Session.Load load, byte[] stream){
        if(fenced.contains(load.generation())) return false;
        sent.add(new Sent(session.id(), null, true, stream));
        return true;
    }

    @Override
    public void close() throws IOException{
        for(Closeable client : clients) client.close();
        transport.close();
    }
}
