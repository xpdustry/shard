package com.xpdustry.shard.tools.fuzz;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.EntityClasses;
import com.xpdustry.shard.wire.v160_7.Packets;
import com.xpdustry.shard.wire.v160_7.PlayerLayout;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * A well-behaved player in one world, from 127.0.0.1: it joins, moves itself to its world with {@code /world}, then
 * plays as a stock client would while the fuzzers run: a {@code clientSnapshot} every 66 ms walking a circle, a ping
 * a second, a conveyor line built and broken every 20 s, a chat line every 15 s. The campaign checks that it stays
 * connected, keeps receiving entity snapshots and ping replies, and that the server keeps moving its unit.
 */
final class ControlClient implements Runnable{
    final String world;
    final String name;
    private final Campaign campaign;
    private final int index;
    private volatile Link link;
    private volatile boolean stop;
    /** The world streams received: the first join, then the transfer to {@link #world}. */
    private final AtomicInteger loads = new AtomicInteger();
    private volatile int playerId = -1, unitId = -1;
    private volatile float unitX, unitY;
    /** Health, read by the campaign's monitor. */
    volatile long lastSnapshotNanos, lastPongNanos, playingSinceNanos;
    final AtomicLong snapshots = new AtomicLong(), pongs = new AtomicLong(), unitSeen = new AtomicLong();
    volatile String closed;

    ControlClient(int index, String world, Campaign campaign){
        this.index = index;
        this.world = world;
        this.campaign = campaign;
        name = "control" + index;
    }

    void stop(){
        stop = true;
        Link link = this.link;
        if(link != null) link.close();
    }

    boolean playing(){
        return playingSinceNanos != 0 && closed == null;
    }

    @Override
    public void run(){
        var firstLoad = new CountDownLatch(1);
        var secondLoad = new CountDownLatch(1);
        try{
            link = Link.open(campaign.address(), InetAddress.getLoopbackAddress(), new Link.Listener(){
                @Override
                public void loaded(Link link, byte[] stream){
                    playerId = Link.playerId(stream);
                    int n = loads.incrementAndGet();
                    try{
                        link.sendTcp(Link.CONNECT_CONFIRM, new byte[0]);
                    }catch(IOException ignored){
                    }
                    (n == 1 ? firstLoad : secondLoad).countDown();
                }

                @Override
                public void packet(Link link, int id, byte[] payload){
                    switch(id){
                        case Link.ENTITY_SNAPSHOT -> {
                            lastSnapshotNanos = System.nanoTime();
                            snapshots.incrementAndGet();
                            track(payload);
                        }
                        case Link.PING_RESPONSE -> {
                            lastPongNanos = System.nanoTime();
                            pongs.incrementAndGet();
                        }
                        default -> {
                        }
                    }
                }

                @Override
                public void closed(Link link, String why){
                    if(!stop) closed = why;
                }
            }, campaign.config().joinWaitSeconds() * 1000L);
            link.sendTcp(Link.CONNECT, Link.connect(0xC0FFEEL + index, name));
            if(!firstLoad.await(campaign.config().joinWaitSeconds(), TimeUnit.SECONDS)) throw new IOException("no world stream");
            if(!world.equals(campaign.worldOf(name))){
                link.sendTcp(Link.SEND_CHAT_MESSAGE, chat("/world " + world));
                if(!secondLoad.await(campaign.config().joinWaitSeconds(), TimeUnit.SECONDS)) throw new IOException("no transfer to " + world);
            }
            long now = System.nanoTime();
            lastSnapshotNanos = lastPongNanos = playingSinceNanos = now;
            drive();
        }catch(IOException e){
            if(!stop) closed = "control: " + e;
        }catch(InterruptedException e){
            // stopped
        }finally{
            Link last = link;
            if(last != null) last.close();
        }
    }

    private void drive() throws IOException, InterruptedException{
        int snapshotId = 0;
        long start = System.nanoTime(), nextPing = start, nextChat = start + 5_000_000_000L;
        float homeX = Float.NaN, homeY = 0, x = 0, y = 0;
        int lastUnit = -1;
        double angle = index;
        while(!stop && link.isOpen()){
            long now = System.nanoTime();
            int unit = unitId;
            if(unit >= 0 && (unit != lastUnit || Float.isNaN(homeX) || Math.hypot(unitX - x, unitY - y) > 48)){
                if(unit != lastUnit || Float.isNaN(homeX)){
                    homeX = unitX;
                    homeY = unitY;
                }
                lastUnit = unit;
                x = unitX;
                y = unitY;
            }
            List<Codecs.Plan> plans = null;
            boolean shooting = false, building = false;
            float vx = 0, vy = 0, rotation = 0;
            if(unit >= 0){
                angle += 0.05;
                float tx = homeX + 40f * (float)Math.cos(angle), ty = homeY + 40f * (float)Math.sin(angle);
                float dx = tx - x, dy = ty - y, distance = (float)Math.hypot(dx, dy), step = Math.min(distance, 6f);
                vx = distance > 0 ? dx / distance * step : 0;
                vy = distance > 0 ? dy / distance * step : 0;
                x += vx;
                y += vy;
                rotation = (float)Math.toDegrees(Math.atan2(vy, vx));
                double seconds = (now - start) / 1e9;
                shooting = seconds % 7 < 2;
                double cycle = seconds % 20;
                building = cycle < 10;
                if(building){
                    plans = new ArrayList<>();
                    int tileX = (int)(homeX / 8) + 3, tileY = (int)(homeY / 8) + 3;
                    for(int i = 0; i < 4; i++){
                        int pos = Point2.pack(tileX + i, tileY);
                        plans.add(cycle < 5 ? new Codecs.Plan(false, pos, campaign.conveyor(), 0, null) : Codecs.Plan.breaking(pos));
                    }
                }
            }
            var snapshot = new Packets.ClientSnapshot(snapshotId++, unit, unit < 0, x, y, x + 40, y + 40, rotation, rotation, vx / 4f, vy / 4f, -1, false,
                shooting, false, building, (short)-1, 0, plans, x, y, 1920 / 4f, 1080 / 4f);
            var out = new Writes(160);
            snapshot.write(out, false, Gen.Lenient.INSTANCE);
            link.sendUdp(Link.CLIENT_SNAPSHOT, out.toByteArray());
            if(now >= nextPing){
                nextPing += 1_000_000_000L;
                var ping = new Writes(8);
                ping.l(now);
                link.sendTcp(Link.PING, ping.toByteArray());
            }
            if(now >= nextChat){
                nextChat += 15_000_000_000L;
                link.sendTcp(Link.SEND_CHAT_MESSAGE, chat("control " + index + " still here"));
            }
            Thread.sleep(66);
        }
    }

    /** Finds this player in an entity snapshot and keeps its unit's id and position; a snapshot that misreads is skipped. */
    private void track(byte[] payload){
        int player = playerId;
        if(player < 0) return;
        try{
            var read = new Reads(payload);
            int amount = read.s();
            var entities = new Reads(Codecs.readBytes(read));
            for(int i = 0; i < amount; i++){
                int id = entities.i(), classId = entities.b() & 0xff;
                WireEntity entity = EntityClasses.readSync(entities, classId, false, Gen.Lenient.INSTANCE);
                if(id != player || !(entity instanceof PlayerLayout.State state)) continue;
                Codecs.UnitRef unit = state.unit();
                unitId = unit.kind() == 2 ? unit.ref() : -1;
                unitX = state.x;
                unitY = state.y;
                if(unitId >= 0) unitSeen.incrementAndGet();
                return;
            }
        }catch(RuntimeException e){
            // another entity misread under the lenient context: this snapshot tells nothing
        }
    }

    private static byte[] chat(String text){
        var out = new Writes(text.length() + 3);
        Link.string(out, text);
        return out.toByteArray();
    }

    @Override
    public String toString(){
        return name + " in " + world;
    }
}
