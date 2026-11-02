package com.xpdustry.shard.server.sync;

import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.net.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.intent.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.sim.path.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * What one hosted world costs per tick in the load gate's shape (W2.21): a default map hosted as {@code Host} hosts it,
 * 10 players joined and confirmed, each sending a {@code clientSnapshot} every 4 ticks as the load gate's bots do
 * (dead, circling, no plans), and the sync of {@code HostedWorld.afterTick} to 10 playing sessions whose frames are
 * counted instead of sent. Per map: the world's tick (admission included), the sync after it, and {@code World.hash()},
 * which a hosted world takes every 300 ticks. The last column is a CRC-32C of every frame and world stream sent from the
 * joins to the end of the warmup, so two checkouts that sync the same bytes print the same digest. Writes
 * {@code build/reports/tick-cost-bench.txt}. Run with
 * {@code -Pshard.tags=slow --tests '*TickCostBench*'}.
 *
 * <p>On a boat VM (default type), 2026-10-29, main at {@code c6622a4d} against W2.21's head; the median of 7 runs of 600
 * ticks (hash: of 7 runs of 20 calls), from two runs of the suite on each side, in the order main, W2.21, W2.21, main:
 * <pre>
 *            main c6622a4d                           W2.21
 * map        tick         sync       hash             tick        sync       hash           frames/tick  bytes
 * maze       22.1 21.9 us 3.2 3.4 us  924  940 us     11.7 11.3 us 2.2 3.2 us 236 241 us     5.5         046bae5b
 * fortress   27.9 33.3 us 6.2 5.5 us  865  917 us     17.1 15.2 us 4.0 3.0 us 244 236 us     5.5         e7f4abf1
 * islands    18.3 17.7 us 1.7 2.0 us  932  915 us      9.3  8.4 us 2.1 1.5 us 176 166 us     5.5         89111aff
 * labyrinth  11.7 12.1 us 1.8 1.9 us  528  545 us      6.9  5.7 us 1.7 1.5 us 116 106 us     5.8         a141f38a
 * </pre>
 * Both sides print the same digest of the bytes they send, on every map and run.
 */
@Tag("slow")
class TickCostBench{
    static final List<String> MAPS = List.of("maze", "fortress", "islands", "labyrinth");
    static final int PLAYERS = 10, SNAPSHOT_TICKS = 4, WARMUP = 1800, TICKS = 600, RUNS = 7, HASHES = 20;
    static final Epoch EPOCH = Epoch.vanilla();

    @Test
    void measure() throws Exception{
        var report = new StringBuilder();
        report.append(String.format("%-10s %12s %12s %12s %10s %10s%n", "map", "tick", "sync", "hash", "frames/tick", "bytes"));
        try(var loopback = new Loopback()){
            var sessions = new ArrayList<Session>();
            for(int i = 0; i < PLAYERS; i++) sessions.add(loopback.session("bot" + i));
            MapCatalog maps = MapCatalog.load(jar(), null, new MsavReader(EPOCH));
            for(String map : MAPS) report.append(measure(maps, map, sessions));
        }
        String text = report.toString();
        System.out.print(text);
        Path out = Path.of("build", "reports", "tick-cost-bench.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, text, StandardCharsets.UTF_8);
    }

    /** One map's line: the median per-tick costs. */
    static String measure(MapCatalog maps, String name, List<Session> sessions) throws IOException{
        var loader = new MapLoader(EPOCH, BuildingCodecs.builtIn());
        SaveFile save = new MsavReader(EPOCH).read(maps.find(name).orElseThrow().read());
        Rules rules = loader.hostRules(save, Gamemode.SURVIVAL);
        WorldConfig template = HostedSystems.template(EPOCH, 7, 256);
        var config = new WorldConfig(template.content(), save.map().width(), save.map().height(), 7, template.systems(), template.kinds(),
            template.inboxCapacity(), template.journal());
        // Host.load
        MapLoader.Loaded loaded = loader.loadMap(save, config, rules);
        World world = loaded.world();
        world.part(WorldRules.class).pushPathRules(world, loaded.state().wave);
        world.part(PathService.class).settle(world);

        long[] frames = {0};
        var digest = new CRC32C();
        boolean[] digesting = {true};
        var outbox = new Outbox(SyncSettings.DEFAULT, (session, load) -> {
        }, new Outbox.Transmit(){
            @Override
            public void send(Session session, Frame frame, boolean reliable){
                frames[0]++;
                if(digesting[0]) digest(session, frame.toByteArray());
            }

            @Override
            public boolean stream(Session session, Session.Load load, byte[] stream){
                if(digesting[0]) digest(session, stream);
                return true;
            }

            void digest(Session session, byte[] bytes){
                digest.update(sessions.indexOf(session));
                digest.update(bytes);
            }
        });
        var sync = new WorldSync(name.hashCode(), SyncSettings.DEFAULT, outbox, EPOCH, BuildingCodecs.builtIn());
        sync.attached(world);
        var run = new Run(world, sync, outbox, loaded.state(), sessions);
        run.join();
        for(int i = 0; i < WARMUP; i++) run.tick();
        digesting[0] = false;

        var tick = new double[RUNS];
        var synced = new double[RUNS];
        var hash = new double[RUNS];
        long framesBefore = frames[0];
        for(int r = 0; r < RUNS; r++){
            run.tickNanos = run.syncNanos = 0;
            for(int i = 0; i < TICKS; i++) run.tick();
            tick[r] = run.tickNanos / 1e3 / TICKS;
            synced[r] = run.syncNanos / 1e3 / TICKS;
            long start = System.nanoTime();
            for(int i = 0; i < HASHES; i++) world.hash();
            hash[r] = (System.nanoTime() - start) / 1e3 / HASHES;
        }
        double perTick = (frames[0] - framesBefore) / (double)(RUNS * TICKS);
        return String.format("%-10s %9.1f us %9.1f us %9.1f us %10.1f   %08x%n", name, median(tick), median(synced), median(hash), perTick, digest.getValue());
    }

    /** A hosted world with its players, ticked as {@code ManagedWorld.tick} and {@code HostedWorld.afterTick} tick it. */
    static final class Run{
        final World world;
        final WorldSync sync;
        final Outbox outbox;
        final MapState state;
        final List<Session> sessions;
        final long[] sequence;
        int snapshotId;
        long tickNanos, syncNanos;

        Run(World world, WorldSync sync, Outbox outbox, MapState state, List<Session> sessions){
            this.world = world;
            this.sync = sync;
            this.outbox = outbox;
            this.state = state;
            this.sessions = sessions;
            sequence = new long[sessions.size()];
        }

        /** Each session joins, its stream is captured and it confirms, as the host's lifecycle does. */
        void join(){
            for(Session session : sessions){
                world.submit(new HostInputs.Join(session.id(), 1, session.identity().name(), 0xffcc00, false), Cause.SERVER);
                outbox.member(session);
            }
            tick();
            for(int i = 0; i < sessions.size(); i++){
                Session session = sessions.get(i);
                outbox.capture(session, 1);
                world.inbox().offer(new IntentEnvelope(session.id(), 1, sequence[i]++, System.nanoTime(), new HostInputs.Confirm(1)));
            }
            tick();
            for(Session session : sessions) outbox.confirmed(session.id(), 1);
        }

        void tick(){
            long ticks = world.ticks();
            for(int i = 0; i < sessions.size(); i++){
                if((ticks + i) % SNAPSHOT_TICKS != 0) continue;
                // the bots' clientSnapshot (ClientPackets.clientSnapshot): dead, circling the map, no plans
                double t = ticks / 60.0 * 0.5 + i;
                float x = (float)(200 + 100 * Math.cos(t)), y = (float)(200 + 100 * Math.sin(t));
                var snapshot = new Snapshot(Policy.VANILLA, snapshotId++, -1, true, x, y, x + 40, y + 40, (float)(t * 20 % 360), 0, 0.4f, 0.2f, -1,
                    false, false, false, false, -1, 0, List.of(), x, y, 1920 / 4f, 1080 / 4f);
                world.inbox().offer(new IntentEnvelope(sessions.get(i).id(), 1, sequence[i]++, System.nanoTime(), snapshot));
            }
            long start = System.nanoTime();
            world.tick();
            long ticked = System.nanoTime();
            Roster roster = Roster.of(world);
            sync.beginTick(world, roster.players());
            sync.drain();
            sync.endTick(world, state);
            long end = System.nanoTime();
            tickNanos += ticked - start;
            syncNanos += end - ticked;
        }
    }

    static double median(double[] runs){
        double[] sorted = runs.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    static Path jar() throws Exception{
        String url = Objects.requireNonNull(TickCostBench.class.getClassLoader().getResource("maps/default/fortress.msav")).toString();
        return Path.of(java.net.URI.create(url.substring("jar:".length(), url.indexOf("!/"))));
    }
}
