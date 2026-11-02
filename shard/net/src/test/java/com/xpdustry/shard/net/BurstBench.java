package com.xpdustry.shard.net;

import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * W4.5: how many bytes a burst leaves in a connection's user-space queue (what {@code maxQueuedBytes} counts) on a slow
 * but healthy link: one tick's frames, cold, warm, and warm after an idle second. Not a test; a measurement, run by hand
 * on a VM with 100 ms of netem delay each way on lo and lo's MTU at 1500 (so slow start counts real-sized segments):
 * <pre>
 *   sudo ip link set lo mtu 1500 && sudo tc qdisc add dev lo root netem delay 100ms
 *   java -cp net/build/classes/java/test:net/build/classes/java/main com.xpdustry.shard.net.BurstBench [per-frame]
 *   sudo tc qdisc del dev lo root && sudo ip link set lo mtu 65536
 * </pre>
 */
public class BurstBench{
    /** With "per-frame": wake the writer per frame (sendTcp), as main's Outbox did; otherwise queue a tick, then flush. */
    static boolean perFrame;

    public static void main(String[] args) throws Exception{
        perFrame = args.length > 0 && args[0].equals("per-frame");
        System.out.println(perFrame ? "mode: a wake per frame (main's Outbox)" : "mode: queue the tick, then flush (this branch)");
        var conns = new LinkedBlockingQueue<Conn>();
        var config = Transport.Config.defaults().withMaxQueuedBytes(64 << 20).withTimers(600_000, 600_000, 600_000, 1_000);
        try(var transport = Transport.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), config, new Transport.Handler(){
            @Override
            public void connected(Conn conn){
                conn.attach(conn);
                conns.add(conn);
            }

            @Override
            public void received(Conn conn, Packet packet){
            }
        })){
            int[] bursts = {160 * 1024, 256 * 1024, 1024 * 1024};
            int[] sizes = {50, 20_000};
            for(String state : List.of("cold", "warm", "warm+1s idle")){
                for(int burst : bursts){
                    for(int size : sizes){
                        try(var client = new Client(transport.address())){
                            Conn conn = conns.poll(10, TimeUnit.SECONDS);
                            if(state.startsWith("warm")){
                                // 4 MB first, drained, so slow start is over
                                send(conn, 4 << 20, 20_000);
                                waitDrained(client, conn, 60_000);
                                if(state.endsWith("idle")) Thread.sleep(1_000);
                            }
                            long base = client.bytes.get() + conn.queuedBytes();
                            long start = System.nanoTime();
                            send(conn, burst, size);
                            long peak = 0, peakAt = 0;
                            while(client.bytes.get() < base + burst * 0.999){
                                long q = conn.queuedBytes();
                                if(q > peak){
                                    peak = q;
                                    peakAt = System.nanoTime() - start;
                                }
                                Thread.sleep(1);
                                if(System.nanoTime() - start > 120_000_000_000L) throw new IllegalStateException("not drained");
                            }
                            long done = System.nanoTime() - start;
                            System.out.printf("%-13s burst=%5d KiB frames=%6d B  peak queued=%7d B (%5.1f%% of 256 KiB, %s vanilla's 41,000 B) at %4d ms; delivered in %5d ms%n",
                                state, burst / 1024, size, peak, 100.0 * peak / (256 * 1024), peak > 41_000 ? "over" : "under", peakAt / 1_000_000, done / 1_000_000);
                        }
                    }
                }
            }
        }
    }

    /** Queues {@code bytes} in frames of about {@code size} on the wire, as one tick, then flushes. */
    static void send(Conn conn, int bytes, int size){
        var random = new Random(1);
        byte[] payload = new byte[size - 6];
        int sent = 0;
        while(sent < bytes){
            random.nextBytes(payload); // incompressible, so the frame is its size
            Frame frame = Frame.packet(106, payload, 0, payload.length, false, null);
            if(!(perFrame ? conn.sendTcp(frame) : conn.queue(frame, true))) throw new IllegalStateException("refused");
            sent += frame.size();
        }
        conn.flush();
    }

    static void waitDrained(Client client, Conn conn, long millis) throws Exception{
        long end = System.nanoTime() + millis * 1_000_000;
        long last = -1;
        while(System.nanoTime() < end){
            long now = client.bytes.get();
            if(conn.queuedBytes() == 0 && now == last) return;
            last = now;
            Thread.sleep(400);
        }
        throw new IllegalStateException("never drained");
    }

    /** Registers TCP and UDP, then reads TCP as fast as it comes, counting bytes. */
    static final class Client implements AutoCloseable{
        final SocketChannel tcp;
        final DatagramChannel udp;
        final AtomicLong bytes = new AtomicLong();

        Client(InetSocketAddress server) throws Exception{
            tcp = SocketChannel.open(server);
            udp = DatagramChannel.open().connect(server);
            var register = ByteBuffer.allocate(8);
            while(register.hasRemaining()) tcp.read(register);
            udp.write(ByteBuffer.allocate(6).put((byte)-2).put((byte)3).putInt(register.getInt(4)).flip());
            Thread.ofPlatform().daemon().start(() -> {
                var buffer = ByteBuffer.allocateDirect(1 << 20);
                try{
                    int n;
                    while((n = tcp.read(buffer)) >= 0){
                        bytes.addAndGet(n);
                        buffer.clear();
                    }
                }catch(Exception ignored){
                }
            });
        }

        @Override
        public void close() throws Exception{
            tcp.close();
            udp.close();
        }
    }
}
