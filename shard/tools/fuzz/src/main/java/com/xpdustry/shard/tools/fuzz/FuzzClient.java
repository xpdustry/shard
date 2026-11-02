package com.xpdustry.shard.tools.fuzz;

import com.xpdustry.shard.net.*;
import com.xpdustry.shard.wire.v160_7.Packets;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * One fuzzing client: it connects again and again, each time from a fresh loopback address (so a kick's cooldown or
 * the DOS list never stops it, and never touches the control clients on 127.0.0.1), and sends packets from {@link Gen}
 * through {@link Mutate} until the campaign has sent its count.
 *
 * <p>Each connection is one of: garbage before or during the handshake; a mutated connect packet; or a player that
 * joins (sometimes fuzzing before it confirms), then sends a few thousand packets and leaves, or is closed for what it
 * sent. Every packet goes into {@link #recent}, which a violation report prints.
 */
final class FuzzClient implements Runnable{
    /** A sent packet, for reports: when, how, and its bytes. */
    record Sent(long nanos, String session, String how, int id, byte[] bytes){
        @Override
        public String toString(){
            String name = id >= 0 && id < Packets.TYPES.size() ? Packets.TYPES.get(id).name() : id == -2 ? "framework" : "id " + id;
            return String.format("%s %s %s %d B %s", session, how, name, bytes.length, HexFormat.of().formatHex(bytes, 0, Math.min(bytes.length, 96)));
        }
    }

    static final int RECENT = 64;

    private final int index;
    private final Campaign campaign;
    private final Random rnd;
    private final Gen gen;
    private final Mutate mutate;
    private final ArrayDeque<Sent> recent = new ArrayDeque<>(RECENT);
    private volatile boolean stop;
    private int generation;
    private String session = "-";

    FuzzClient(int index, Campaign campaign, long seed){
        this.index = index;
        this.campaign = campaign;
        rnd = new Random(seed);
        gen = new Gen(rnd);
        mutate = new Mutate(rnd);
    }

    void stop(){
        stop = true;
    }

    synchronized List<Sent> recent(){
        return List.copyOf(recent);
    }

    private synchronized void remember(String how, int id, byte[] bytes){
        if(recent.size() == RECENT) recent.removeFirst();
        recent.addLast(new Sent(System.nanoTime(), session, how, id, bytes));
    }

    @Override
    public void run(){
        while(!stop && !campaign.done()){
            generation++;
            session = "fz" + index + "x" + generation;
            InetAddress local = address();
            int roll = rnd.nextInt(100);
            try{
                if(roll < 4) garbage(local);
                else if(roll < 8) connectFuzz(local);
                else play(local);
            }catch(IOException e){
                campaign.stats.closes.increment();
            }catch(InterruptedException e){
                return;
            }catch(RuntimeException e){
                campaign.clientFailure(this, e);
                return;
            }
        }
    }

    /** A loopback address other than 127.0.0.1, which the control clients use. */
    private InetAddress address(){
        try{
            return InetAddress.getByAddress(new byte[]{127, (byte)(1 + rnd.nextInt(250)), (byte)rnd.nextInt(256), (byte)(1 + rnd.nextInt(254))});
        }catch(UnknownHostException e){
            throw new IllegalStateException(e);
        }
    }

    /** Bytes before or instead of a handshake: frames on a raw socket, then gone. */
    private void garbage(InetAddress local) throws IOException, InterruptedException{
        campaign.stats.connections.increment();
        try(SocketChannel tcp = SocketChannel.open()){
            tcp.bind(new InetSocketAddress(local, 0));
            tcp.connect(campaign.address());
            for(int i = 0, n = 1 + rnd.nextInt(8); i < n; i++){
                byte[] frame = switch(rnd.nextInt(3)){
                    case 0 -> mutate.tcp(mutate.framework(), Mutate.FrameKind.VALID);
                    case 1 -> {
                        int id = gen.id();
                        yield mutate.tcp(mutate.body(id, gen.body(id), mutate.frameKind(200), Lz4.forThread()), mutate.frameKind(200));
                    }
                    default -> gen.bytes(64);
                };
                remember("raw", -1, frame);
                tcp.write(ByteBuffer.wrap(frame));
                if(!campaign.count(1)) break;
            }
            // trickle: half a frame, as a slow attacker would
            if(rnd.nextBoolean()){
                byte[] half = {0, 40, Frame.FRAMEWORK};
                remember("raw", -1, half);
                tcp.write(ByteBuffer.wrap(half));
                Thread.sleep(rnd.nextInt(50));
            }
        }catch(IOException e){
            // the server closed it, as it should
        }
        // a stray datagram from an unknown address
        try(DatagramChannel udp = DatagramChannel.open()){
            udp.bind(new InetSocketAddress(local, 0));
            byte[] datagram = rnd.nextBoolean() ? mutate.framework() : gen.bytes(64);
            remember("raw-udp", -2, datagram);
            udp.send(ByteBuffer.wrap(datagram), campaign.address());
            campaign.count(1);
        }
    }

    /** A handshake, then a mutated connect packet and whatever follows. */
    private void connectFuzz(InetAddress local) throws IOException, InterruptedException{
        campaign.stats.connections.increment();
        try(Link link = Link.open(campaign.address(), local, new Link.Listener(){}, 10_000)){
            byte[] connect = rnd.nextInt(5) == 0 ? mutate.payload(gen.connect()) : gen.connect();
            send(link, Link.CONNECT, connect, true, Mutate.FrameKind.VALID);
            // packets before the server has answered: they must be dropped or close the connection
            for(int i = 0, n = rnd.nextInt(20); i < n && link.isOpen(); i++) fuzzOne(link);
            Thread.sleep(rnd.nextInt(30));
        }
    }

    /** A player: joins, maybe fuzzes before confirming, then fuzzes until it leaves or is closed. */
    private void play(InetAddress local) throws IOException, InterruptedException{
        var loaded = new CountDownLatch(1);
        campaign.stats.connections.increment();
        try(Link link = Link.open(campaign.address(), local, new Link.Listener(){
            @Override
            public void loaded(Link link, byte[] stream){
                loaded.countDown();
            }
        }, 10_000)){
            link.sendTcp(Link.CONNECT, Link.connect(campaign.seed() * 1_000_003L + index * 7919L + generation, session));
            boolean early = rnd.nextInt(10) == 0;
            if(early){
                for(int i = 0, n = rnd.nextInt(50); i < n && link.isOpen(); i++) fuzzOne(link);
            }
            // the world stream of a default map takes well under a second; past the hang bound, the join is the server's
            // fault. A close (a refused connect, or the early packets) ends the wait at once, and so does the run's end:
            // a latch that only the stream opens held clients, and the run, for the whole bound (W5.5's smoke took 200 s)
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(campaign.config().joinWaitSeconds());
            while(!loaded.await(100, TimeUnit.MILLISECONDS)){
                if(!link.isOpen() || stop || campaign.done()) return;
                if(System.nanoTime() > deadline){
                    if(!early) campaign.stats.slowJoins.increment();
                    return;
                }
            }
            send(link, Link.CONNECT_CONFIRM, new byte[0], true, Mutate.FrameKind.VALID);
            campaign.stats.joins.increment();
            int budget = rnd.nextInt(10) == 0 ? 20_000 + rnd.nextInt(30_000) : 500 + rnd.nextInt(4000);
            int perSecond = campaign.config().packetsPerSecond();
            long start = System.nanoTime();
            for(int sent = 0; sent < budget && link.isOpen() && !stop && !campaign.done(); sent++){
                if((sent & 63) == 0) gen.aim(campaign.targetsOf(session), campaign.targets());
                fuzzOne(link);
                long due = start + (long)(sent + 1) * 1_000_000_000L / perSecond;
                long wait = due - System.nanoTime();
                if(wait > 1_000_000) Thread.sleep(wait / 1_000_000);
            }
            if(!link.isOpen()) campaign.stats.closedByServer.increment();
        }
    }

    /** One fuzzed message on {@code link}. */
    private void fuzzOne(Link link) throws IOException{
        if(rnd.nextInt(100) < 3){
            byte[] message = mutate.framework();
            boolean tcp = rnd.nextBoolean();
            remember(tcp ? "tcp" : "udp", -2, message);
            if(tcp) link.tcp(mutate.tcp(message, Mutate.FrameKind.VALID));
            else link.udp(message);
            campaign.count(1);
            return;
        }
        int id = gen.id();
        byte[] payload = gen.body(id);
        if(rnd.nextInt(100) < campaign.config().mutatePercent()) payload = mutate.payload(payload);
        boolean reliable = id < Packets.TYPES.size() ? Packets.TYPES.get(id).reliable() : rnd.nextBoolean();
        send(link, id, payload, rnd.nextInt(5) == 0 ? !reliable : reliable, mutate.frameKind(campaign.config().framePerMille()));
    }

    private void send(Link link, int id, byte[] payload, boolean tcp, Mutate.FrameKind kind) throws IOException{
        if(payload.length > 0xffff) payload = Arrays.copyOf(payload, 0xffff);
        byte[] body = mutate.body(id, payload, kind, link.lz4);
        campaign.stats.sent(id, kind);
        try{
            if(tcp){
                byte[] frame = mutate.tcp(body, kind);
                remember("tcp:" + kind, id, frame);
                link.tcp(frame);
            }else{
                remember("udp:" + kind, id, body);
                // a datagram can't carry more than this, and the kernel refuses it
                if(body.length < 65_000) link.udp(body);
            }
        }finally{
            campaign.count(1);
        }
    }

    @Override
    public String toString(){
        return "fuzz client " + index + " (" + session + ")";
    }
}
