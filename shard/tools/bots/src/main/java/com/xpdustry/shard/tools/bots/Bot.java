package com.xpdustry.shard.tools.bots;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.net.*;
import com.xpdustry.shard.wire.Codecs;
import com.xpdustry.shard.wire.WireContext;
import com.xpdustry.shard.wire.WireEntity;
import com.xpdustry.shard.wire.v160_7.EntityClasses;
import com.xpdustry.shard.wire.v160_7.PlayerLayout;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;

/**
 * One headless client that speaks v160.7 as the desktop client does (note 24 §6): Arc's RegisterTCP/RegisterUDP
 * handshake, {@code ConnectPacket}, the world stream, {@code connectConfirm}, then a {@code clientSnapshot} every
 * {@code snapshotMillis} over UDP (LZ4, as the client compresses packets of 36 B or more), a {@code ping} every second
 * and optional chat. It works against Shard and against vanilla's {@code server-release.jar}.
 *
 * <p>With {@code churnSeconds}, a bot leaves after about that long in the world and joins again a few seconds later.
 * With {@code walk}, it reads the entity snapshots to find the unit it controls ({@link #track}) and drives it: it
 * walks a circle around where it spawned, shoots in bursts, and every half minute asks to build a short conveyor line,
 * then to break it again.
 *
 * <p>Three virtual threads: TCP reader, UDP reader and the driver, which sleeps between sends. It never spins: virtual
 * threads aren't time-sliced, and a few spinning bots once starved a thousand others (note 24 §1.6).
 */
final class Bot{
    /** What a bot does, shared by all bots of a run. */
    record Plan(InetSocketAddress server, String name, int snapshotMillis, int chatSeconds, int transferSeconds, int worlds, boolean trace,
                int churnSeconds, boolean walk){
    }

    /** v160.7 block ids (shard/data/vanilla-v160.7/content/block.json): what a walking bot builds. */
    static final int CONVEYOR = 257;

    /**
     * Content facts for reading other entities' syncs without the content: no dynamic statuses, every block exists and
     * rotates (unit plans carry their rotation either way). A snapshot this misreads is skipped.
     */
    static final WireContext LENIENT = new WireContext(){
        @Override
        public boolean statusDynamic(int status){
            return false;
        }

        @Override
        public boolean blockRotates(int block){
            return true;
        }

        @Override
        public boolean blockExists(int block){
            return true;
        }

        @Override
        public boolean unlockable(int type, int id){
            return true;
        }

        @Override
        public boolean tileExists(int pos){
            return true;
        }

        @Override
        public int syncedClass(int id){
            return -1;
        }
    };

    private final int index;
    private final Plan plan;
    private final BotStats stats;
    /** Each bot has its own compressor: no ThreadLocal per virtual thread (note 24 §1.6). */
    private final Lz4 lz4 = new Lz4();
    private final Object writeLock = new Object();
    private SocketChannel tcp;
    private DatagramChannel udp;
    private volatile int connectionId;
    private volatile boolean tcpRegistered, udpRegistered, loaded;
    private volatile boolean stop;
    private int streamTotal = -1, streamReceived;
    private ByteArrayOutputStream stream;
    private volatile int playerId = -1;
    /** The unit this bot controls, from its player's sync, and where the server last put it; -1 for none. */
    private volatile int unitId = -1;
    private volatile float unitX, unitY;
    /** Set while the bot leaves on purpose ({@code churnSeconds}): the close is no drop. */
    private volatile boolean leaving;
    private volatile long lastSnapshotNanos;
    private long joinStart;
    /** When the connect packet went out; 0 before. */
    private volatile long connectNanos;

    Bot(int index, Plan plan, BotStats stats){
        this.index = index;
        this.plan = plan;
        this.stats = stats;
    }

    void stop(){
        stop = true;
        close();
    }

    void run(){
        do{
            session();
            if(stop || plan.churnSeconds() <= 0) return;
            stats.left.increment();
            try{
                // away for 2-6 s, then the same player joins again
                Thread.sleep(2000 + (long)(Math.random() * 4000));
            }catch(InterruptedException e){
                return;
            }
        }while(!stop);
    }

    /** One connection: join, play until stopped, kicked, or it is time to leave. */
    private void session(){
        tcpRegistered = udpRegistered = loaded = leaving = false;
        playerId = unitId = -1;
        streamTotal = -1;
        stream = null;
        try{
            joinStart = System.nanoTime();
            tcp = SocketChannel.open(plan.server());
            tcp.setOption(StandardSocketOptions.TCP_NODELAY, true);
            Thread.ofVirtual().name("bot-tcp-" + index).start(this::readTcp);
            udp = DatagramChannel.open().connect(plan.server());
            Thread.ofVirtual().name("bot-udp-" + index).start(this::readUdp);

            long deadline = System.nanoTime() + 10_000_000_000L;
            while(!tcpRegistered && System.nanoTime() < deadline) Thread.sleep(20);
            if(!tcpRegistered) throw new IOException("no RegisterTCP");
            // Arc's client sends RegisterUDP every 100 ms until the TCP reply (Client.java:155-176)
            while(!udpRegistered && System.nanoTime() < deadline){
                udp.write(ByteBuffer.allocate(6).put(Frame.FRAMEWORK).put(Frame.REGISTER_UDP).putInt(connectionId).flip());
                Thread.sleep(100);
            }
            if(!udpRegistered) throw new IOException("no RegisterUDP reply");
            stats.connected.increment();
            connectNanos = System.nanoTime();
            sendTcp(Frame.packet(ClientPackets.CONNECT, ClientPackets.connect(index, plan.name() + index), lz4));

            long now = System.nanoTime();
            long nextSnapshot = now, nextPing = now;
            long nextChat = plan.chatSeconds() > 0 ? now + (long)(Math.random() * plan.chatSeconds() * 1e9) : Long.MAX_VALUE;
            long nextTransfer = plan.transferSeconds() > 0 ? now + (long)((0.5 + Math.random()) * plan.transferSeconds() * 1e9) : Long.MAX_VALUE;
            int snapshotId = 0;
            long leaveAt = 0;
            var driver = plan.walk() ? new Driver(index) : null;
            while(!stop && tcp.isOpen()){
                now = System.nanoTime();
                if(!loaded){
                    // waiting for the world; sleep, never spin
                    Thread.sleep(10);
                    nextSnapshot = nextPing = System.nanoTime();
                    continue;
                }
                if(plan.churnSeconds() > 0 && leaveAt == 0) leaveAt = now + (long)((0.5 + Math.random()) * plan.churnSeconds() * 1e9);
                if(leaveAt != 0 && now >= leaveAt){
                    leaving = true;
                    break;
                }
                if(now >= nextSnapshot){
                    nextSnapshot += plan.snapshotMillis() * 1_000_000L;
                    byte[] snapshot = driver != null && unitId >= 0 ? driver.snapshot(snapshotId++, now / 1e9, unitId, unitX, unitY)
                        : ClientPackets.clientSnapshot(snapshotId++, now / 1e9, index);
                    Frame frame = Frame.packet(ClientPackets.CLIENT_SNAPSHOT, snapshot, lz4);
                    udp.write(frame.udp());
                    stats.txBytes.add(frame.bodySize());
                }
                if(now >= nextPing){
                    nextPing += 1_000_000_000L;
                    sendTcp(Frame.packet(ClientPackets.PING, ClientPackets.ping(System.nanoTime()), lz4));
                }
                if(now >= nextChat){
                    nextChat += plan.chatSeconds() * 1_000_000_000L;
                    sendTcp(Frame.packet(ClientPackets.SEND_CHAT_MESSAGE, ClientPackets.chat("hello from bot " + index + ", a typical chat line"), lz4));
                }
                if(now >= nextTransfer){
                    nextTransfer += plan.transferSeconds() * 1_000_000_000L;
                    int target = (int)(Math.random() * plan.worlds());
                    sendTcp(Frame.packet(ClientPackets.SEND_CHAT_MESSAGE, ClientPackets.chat("/world " + target), lz4));
                    stats.transfersAsked.increment();
                }
                long sleep = Math.min(nextSnapshot, nextPing) - System.nanoTime();
                if(sleep > 0) Thread.sleep(sleep / 1_000_000, (int)(sleep % 1_000_000));
            }
        }catch(Exception e){
            if(!stop && !leaving){
                if(loaded){
                    stats.reason("after join: " + e);
                }else{
                    stats.failed.increment();
                    stats.reason("connect: " + e);
                }
            }
            if(plan.trace()) e.printStackTrace();
        }finally{
            close();
        }
    }

    private void sendTcp(Frame frame) throws IOException{
        synchronized(writeLock){
            ByteBuffer buffer = frame.tcp();
            while(buffer.hasRemaining()) tcp.write(buffer);
        }
        stats.txBytes.add(frame.size());
    }

    private void readTcp(){
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        try{
            while(tcp.read(buffer) >= 0){
                buffer.flip();
                while(buffer.remaining() >= 2){
                    int length = buffer.getShort(buffer.position()) & 0xffff;
                    if(length > Frame.MAX_CLIENT_BODY) throw new IOException("frame over the client's read buffer: " + length);
                    if(buffer.remaining() < 2 + length) break;
                    buffer.position(buffer.position() + 2);
                    byte[] body = new byte[length];
                    buffer.get(body);
                    stats.rxTcpBytes.add(length + 2);
                    handle(body, length + 2);
                }
                buffer.compact();
            }
            if(!stop && !leaving) stats.reason(closedWhen());
        }catch(IOException | RuntimeException e){
            if(!stop && !leaving) stats.reason("tcp: " + e);
            if(plan.trace()) e.printStackTrace();
        }finally{
            if(loaded && !stop && !leaving) stats.dropped.increment();
            close();
        }
    }

    private void readUdp(){
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        try{
            while(!stop){
                buffer.clear();
                int length = udp.read(buffer);
                byte[] body = new byte[length];
                buffer.flip().get(body);
                stats.rxUdpBytes.add(length);
                handle(body, length);
            }
        }catch(IOException | RuntimeException e){
            // closed
        }
    }

    private void handle(byte[] body, int wireBytes) throws IOException{
        if(body.length == 0) return;
        if(plan.trace()) System.out.printf("bot%d rx %d B id=%d%n", index, wireBytes, body[0]);
        if(body[0] == Frame.FRAMEWORK){
            if(body.length < 2) return;
            switch(body[1]){
                case Frame.REGISTER_TCP -> {
                    connectionId = ByteBuffer.wrap(body, 2, 4).getInt();
                    tcpRegistered = true;
                }
                case Frame.REGISTER_UDP -> udpRegistered = true;
                case Frame.PING -> {
                    if(body.length == 7 && body[6] == 0) sendTcp(Frame.ping(ByteBuffer.wrap(body, 2, 4).getInt(), true));
                }
                default -> {
                }
            }
            return;
        }
        int id = body[0] & 0xff;
        byte[] payload = Frame.decodePayload(body, 1, body.length - 1);
        stats.received(id, wireBytes, payload.length);
        switch(id){
            case ClientPackets.STREAM_BEGIN -> {
                streamTotal = ByteBuffer.wrap(payload).getInt(4);
                streamReceived = 0;
                stream = new ByteArrayOutputStream(streamTotal);
            }
            case ClientPackets.STREAM_CHUNK -> {
                int length = ByteBuffer.wrap(payload).getShort(4);
                streamReceived += length;
                stats.worldBytes.add(length);
                if(stream != null) stream.write(payload, 6, length);
                if(streamTotal >= 0 && streamReceived >= streamTotal){
                    // a real client inflates and loads the world here; the server spawns the player on this confirm
                    playerId = ClientPackets.playerId(stream.toByteArray());
                    if(playerId < 0) stats.reason("world stream did not inflate");
                    stream = null;
                    streamTotal = -1;
                    sendTcp(Frame.packet(ClientPackets.CONNECT_CONFIRM, new byte[0], lz4));
                    if(!loaded){
                        loaded = true;
                        stats.joined.increment();
                        stats.joinTime.record((System.nanoTime() - joinStart) / 1000);
                    }else{
                        stats.transfersDone.increment();
                    }
                }
            }
            case ClientPackets.WORLD_DATA_BEGIN -> stats.worldDataBegins.increment();
            case ClientPackets.ENTITY_SNAPSHOT -> {
                stats.entities.add(ByteBuffer.wrap(payload).getShort());
                if(plan.walk() && playerId >= 0) track(payload);
                long now = System.nanoTime(), last = lastSnapshotNanos;
                lastSnapshotNanos = now;
                if(last != 0 && now - last > 20_000_000) stats.snapshotGap.record((now - last) / 1000);
            }
            case ClientPackets.PING_RESPONSE -> stats.rtt.record((System.nanoTime() - ByteBuffer.wrap(payload).getLong()) / 1000);
            case ClientPackets.KICK -> {
                stats.kicked.increment();
                stats.reason("kick: " + ClientPackets.readString(payload));
            }
            case ClientPackets.KICK_REASON -> {
                stats.kicked.increment();
                stats.reason("kick reason #" + (payload.length > 0 ? payload[0] : -1));
            }
            default -> {
            }
        }
    }

    /**
     * Finds this bot's player in an entity snapshot and keeps its unit's id and position. Entities have no length
     * prefix, so every one before it is read with {@link #LENIENT}; a snapshot that doesn't read is skipped.
     */
    private void track(byte[] payload){
        try{
            var read = new Reads(payload);
            int amount = read.s();
            var entities = new Reads(Codecs.readBytes(read));
            for(int i = 0; i < amount; i++){
                int id = entities.i(), classId = entities.b() & 0xff;
                WireEntity entity = EntityClasses.readSync(entities, classId, false, LENIENT);
                if(id != playerId || !(entity instanceof PlayerLayout.State player)) continue;
                Codecs.UnitRef unit = player.unit();
                unitId = unit.kind() == 2 ? unit.ref() : -1;
                unitX = player.x;
                unitY = player.y;
                return;
            }
        }catch(RuntimeException e){
            stats.unreadSnapshots.increment();
        }
    }

    /** What a walking bot does with its unit, one {@code clientSnapshot} at a time. */
    private static final class Driver{
        private final int index;
        private float homeX = Float.NaN, homeY, x, y;
        private int lastUnit = -1;
        private double angle;

        Driver(int index){
            this.index = index;
            angle = index;
        }

        byte[] snapshot(int snapshotId, double seconds, int unit, float serverX, float serverY){
            // a new unit (a respawn): walk around where it appeared
            if(unit != lastUnit || Float.isNaN(homeX) || Math.hypot(serverX - x, serverY - y) > 48){
                if(unit != lastUnit || Float.isNaN(homeX)){
                    homeX = serverX;
                    homeY = serverY;
                }
                lastUnit = unit;
                x = serverX;
                y = serverY;
            }
            angle += 0.05;
            float targetX = homeX + 40f * (float)Math.cos(angle), targetY = homeY + 40f * (float)Math.sin(angle);
            float dx = targetX - x, dy = targetY - y, distance = (float)Math.hypot(dx, dy);
            // at most 6 world units a snapshot, under an alpha's speed over four ticks
            float step = Math.min(distance, 6f);
            float vx = distance > 0 ? dx / distance * step : 0, vy = distance > 0 ? dy / distance * step : 0;
            x += vx;
            y += vy;
            float rotation = (float)Math.toDegrees(Math.atan2(vy, vx));
            double phase = seconds + index * 1.7;
            boolean shooting = phase % 7 < 2;
            // a build cycle every 30 s: 8 s placing a conveyor line beside home, then 8 s breaking it
            double cycle = (seconds + index * 3.1) % 30;
            boolean building = cycle < 16;
            List<Codecs.Plan> plans = null;
            if(building){
                plans = new ArrayList<>();
                int tx = (int)(homeX / 8) + 3, ty = (int)(homeY / 8) + 3 + index % 6;
                for(int i = 0; i < 4; i++){
                    int pos = ((tx + i) << 16) | ((ty) & 0xffff);
                    plans.add(cycle < 8 ? new Codecs.Plan(false, pos, CONVEYOR, 0, null) : Codecs.Plan.breaking(pos));
                }
            }
            float aimX = x + 60f * (float)Math.cos(Math.toRadians(rotation + 25)), aimY = y + 60f * (float)Math.sin(Math.toRadians(rotation + 25));
            return ClientPackets.driveSnapshot(snapshotId, unit, x, y, aimX, aimY, rotation, vx / 4f, vy / 4f, shooting, building, plans);
        }
    }

    /** A server close, with the phase it cut short: a close before the world says when, to the second. */
    private String closedWhen(){
        if(loaded) return "server closed TCP";
        long sent = connectNanos;
        if(sent == 0) return "server closed TCP before the connect packet";
        return "server closed TCP before the world, " + (System.nanoTime() - sent) / 1_000_000_000L + " s after the connect packet";
    }

    private void close(){
        try{
            if(tcp != null) tcp.close();
        }catch(IOException ignored){
        }
        try{
            if(udp != null) udp.close();
        }catch(IOException ignored){
        }
    }
}
