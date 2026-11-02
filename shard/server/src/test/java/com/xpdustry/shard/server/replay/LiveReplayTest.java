package com.xpdustry.shard.server.replay;

import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.server.chat.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.intent.*;
import com.xpdustry.shard.server.replay.view.*;
import com.xpdustry.shard.sim.path.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The live recorder and the replay service on a hosted world of a real map (Ground Zero, survival, waves every 10 s):
 * players join, confirm, walk, shoot, queue plans, chat, leave and come back. The recorder writes a bundle as the
 * server would (a match checkpoint every 600 ticks), and {@link Regenerator} then makes every segment again from the
 * bundle alone, byte for byte, with the world's hash equal to the live one at every checkpoint. The same holds across
 * a server restart in the middle of a segment, and across a recovery that cuts back a dead timeline.
 */
class LiveReplayTest{
    static final Epoch VANILLA = Epoch.vanilla();
    static final int SEED = 11;

    static WorldConfig config(int width, int height){
        WorldConfig template = HostedSystems.template(VANILLA, SEED, 4096);
        return new WorldConfig(template.content(), width, height, SEED, template.systems(), template.kinds(), template.inboxCapacity(), template.journal());
    }

    /** Ground Zero loaded and played as the host does, with waves every 10 s so some come. */
    static World groundZero() throws Exception{
        return loaded().world();
    }

    /** {@link #groundZero()} with its map state, as the host keeps it. */
    static MapLoader.Loaded loaded() throws Exception{
        // a campaign map, not one of the catalog's default ones
        return loaded("maps/serpulo/groundZero.msav", 600);
    }

    /** A map of the vanilla jar on the class path, loaded and played as the host does, with waves every {@code waveSpacing} ticks. */
    static MapLoader.Loaded loaded(String resource, float waveSpacing) throws Exception{
        byte[] bytes;
        try(InputStream in = Objects.requireNonNull(LiveReplayTest.class.getClassLoader().getResourceAsStream(resource), resource)){
            bytes = in.readAllBytes();
        }
        SaveFile save = new MsavReader(VANILLA).read(bytes);
        var loader = new MapLoader(VANILLA, BuildingCodecs.builtIn());
        Rules rules = loader.hostRules(save, Gamemode.SURVIVAL);
        // a sector's rules may hold the wave timer off
        rules.waveTimer = true;
        rules.waveSpacing = waveSpacing;
        MapLoader.Loaded loaded = loader.loadMap(save, config(save.map().width(), save.map().height()), rules);
        MapLoader.play(loaded);
        World world = loaded.world();
        world.part(WorldRules.class).pushPathRules(world, loaded.state().wave);
        world.part(PathService.class).settle(world);
        return loaded;
    }

    /** Players as a server's sessions would drive them: route 1, increasing sequences, recorded arrival times. */
    static final class Players{
        final TreeMap<Long, Long> sequences = new TreeMap<>();
        long nanos;

        void feed(World world){
            long tick = world.ticks();
            nanos = tick * 16_666_667L;
            join(world, tick, 10, 1, "[accent]alpha");
            join(world, tick, 25, 2, "beta");
            join(world, tick, 640, 3, "gamma");
            if(tick == 1450) world.inbox().offer(new Submission(new HostInputs.Leave(2), Cause.SERVER));
            join(world, tick, 1990, 4, "beta again");
            for(long session : List.copyOf(sequences.keySet())){
                if(tick % 300 == session * 37 % 300) intent(world, session, new ChatLine(ChatPolicy.VANILLA, "tick " + tick + " from " + session + ", ünïcode ✓"));
                if(tick % 4 == session % 4) move(world, session, tick);
            }
            if(tick == 1450) sequences.remove(2L);
        }

        private void join(World world, long tick, long at, long session, String name){
            if(tick == at){
                world.inbox().offer(new Submission(new HostInputs.Join(session, 1, name, 0x33aaffff, session == 1), Cause.SERVER));
                sequences.put(session, 0L);
            }
            if(tick == at + 5) intent(world, session, new HostInputs.Confirm(0));
        }

        /** A clientSnapshot: walk in a circle, shoot every other second, keep a plan queue. */
        private void move(World world, long session, long tick){
            Player player = Roster.of(world).player(session);
            if(player == null || player.unit() == null) return;
            Unit unit = player.unit();
            float angle = tick * 0.02f + session;
            float x = unit.x + (float)Math.cos(angle) * 2f, y = unit.y + (float)Math.sin(angle) * 2f;
            boolean shooting = tick / 120 % 2 == 0;
            var plans = new ArrayList<BuildPlan>();
            int block = world.content().block("conveyor").id();
            for(int i = 0; i < 3; i++) plans.add(BuildPlan.place((int)(unit.x / 8) + 3 + i, (int)(unit.y / 8) + (int)session, 0, block, null));
            intent(world, session, new Snapshot(Policy.VANILLA, (int)tick, unit.id(), false, x, y, x + 60, y + 20, angle * 57f, angle * 57f, 0, 0, -1,
                false, shooting, false, !shooting, block, 0, plans, x, y, 400, 300));
        }

        private void intent(World world, long session, Intent intent){
            long sequence = sequences.merge(session, 1L, Long::sum);
            world.inbox().offer(new IntentEnvelope(session, 1, sequence, nanos, intent));
        }
    }

    /** One recorded match: the world, its recorder, its bundle. */
    static final class Match{
        final Bundle bundle;
        final Players players = new Players();
        final ExecutorService io = Executors.newSingleThreadExecutor();
        final List<Long> segments = new ArrayList<>();
        World world;
        MatchRecorder recorder;

        Match(Path dir, World world) throws IOException{
            this.world = world;
            bundle = new Bundle(dir.resolve("groundzero-test"));
            checkpoint();
            var manifest = new Bundle.Manifest(Bundle.Manifest.FORMAT, BuildInfo.id(), world.content().hash(), Checkpoint.systemsId(world.config()), "v160.7",
                "groundzero", bundle.match(), "groundZero.msav", "Ground Zero", "survival", false, "now", null, 0, 0, 600, 3, 600, "recording", -1, false);
            recorder = recorder(manifest);
            recorder.resident(world, 0);
        }

        MatchRecorder recorder(Bundle.Manifest manifest) throws IOException{
            return new MatchRecorder(new MatchRecorder.Listener(){
                @Override
                public void gameOver(MatchRecorder recorder, int winner){
                }

                @Override
                public void segmentWritten(MatchRecorder recorder, long tick){
                    // as Replays does, from io, while a lifecycle call may hold the recorder and wait for io
                    recorder.manifest();
                    synchronized(segments){
                        segments.add(tick);
                    }
                }
            }, bundle, manifest, ViewWriter.Options.DEFAULT, io);
        }

        /** Runs until {@code tick}, as a server does: inputs, the tick, the recorder, then a match checkpoint when due. */
        void runTo(long tick) throws IOException{
            while(world.ticks() < tick){
                players.feed(world);
                world.tick();
                recorder.frame(world, List.of());
                if(world.ticks() % 600 == 0) checkpoint();
            }
        }

        void checkpoint() throws IOException{
            Bundle.write(bundle.checkpoint(world.ticks()), Checkpoint.capture(world).encode(Compression.FAST));
        }

        /** The world as a checkpoint of it now restores. */
        World restored(){
            return Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(config(world.tiles().width(), world.tiles().height()));
        }

        void close(){
            io.shutdown();
        }
    }

    @Test
    void everySegmentComesBackFromTheBundle(@TempDir Path dir) throws Exception{
        var match = new Match(dir, groundZero());
        try{
            match.runTo(2400);
            match.recorder.end(-1);
            Regenerator.Report report = verify(match.bundle);
            assertEquals(List.of(0L, 600L, 1200L, 1800L, 2400L), new Regenerator(match.bundle).segmentTicks());
            assertTrue(report.segments().stream().allMatch(Regenerator.Made::identical), "every segment is the live one");
            assertTrue(report.hashesChecked() >= 5, "the hash at every checkpoint tick: " + report.hashesChecked());

            ViewFile file = publish(dir, match.bundle);
            fixture(file, "groundzero-live-40s");
            var seen = seen(file);
            System.out.println("seen: " + seen);
            assertTrue(seen.players.containsAll(List.of("[accent]alpha", "beta", "gamma", "beta again")), "players: " + seen.players);
            assertTrue(seen.chat >= 10, "chat lines: " + seen.chat);
            assertTrue(seen.plans > 0, "build plans");
            assertTrue(seen.bullets > 0, "bullets");
            assertTrue(seen.waves > 0, "waves");
            assertTrue(seen.leftAndCameBack, "beta left and a new player joined");
        }finally{
            match.close();
        }
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aServerRestartInTheMiddleOfASegmentStillReplays(@TempDir Path dir) throws Exception{
        var match = new Match(dir, groundZero());
        try{
            match.runTo(1500);
            WorldHash then = match.world.hash();
            // the server stops: the world hibernates, its last generation is at tick 1500
            match.recorder.hibernated(1500);
            World restored = match.restored();
            assertEquals(then, restored.hash());
            // a new process: a new recorder from the bundle on disk, the world resumed from its generation
            match.world = restored;
            match.recorder = match.recorder(match.bundle.manifest());
            match.recorder.resident(restored, 1500);
            match.runTo(2400);
            match.recorder.end(-1);

            assertTrue(match.bundle.segments().get(1200L).partial(), "the live recorder wrote the cut segment only up to the stop");
            assertTrue(match.bundle.logs().contains(1500L), "a log file starts where the world resumed");
            Regenerator.Report report = verify(match.bundle);
            assertTrue(report.segments().stream().allMatch(Regenerator.Made::identical), "every segment, the cut one as far as it went");
            ViewFile file = publish(dir, match.bundle);
            assertEquals(5, file.segments().size());
            assertEquals(new Regenerator(match.bundle).segment(1200, true).segment().hash(), file.header().segments().get(2).hash(),
                "the baked stream has the cut segment whole, made from the bundle");
        }finally{
            match.close();
        }
    }

    @Test
    void aRecoveryCutsTheDeadTimeline(@TempDir Path dir) throws Exception{
        var match = new Match(dir, groundZero());
        try{
            match.runTo(1650);
            World generation = match.restored();
            match.runTo(2100);
            // the world failed at 2100 and is recovered from its generation at 1650: 1650-2100 never happened
            match.world = generation;
            match.recorder.resident(generation, 1650);
            assertFalse(match.bundle.checkpoints().contains(1800L), "the dead timeline's checkpoint went");
            match.runTo(2400);
            match.recorder.end(-1);
            Regenerator.Report report = verify(match.bundle);
            assertTrue(report.ok(), report.problems().toString());
        }finally{
            match.close();
        }
    }

    /** Bakes and publishes {@code bundle}'s view stream, and reads the file back. */
    static ViewFile publish(Path dir, Bundle bundle) throws IOException{
        var store = new ReplayStore(dir);
        store.publish(bundle, line -> {});
        return ViewFile.decode(Files.readAllBytes(store.view(bundle.match())));
    }

    static Regenerator.Report verify(Bundle bundle) throws IOException{
        var lines = new ArrayList<String>();
        Regenerator.Report report = new Regenerator(bundle).verify(lines::add);
        assertTrue(report.ok(), String.join("\n", lines) + "\n" + report.problems());
        return report;
    }

    /**
     * Writes {@code file} and a dump of every 7th state {@link ViewReader} decodes from it to
     * {@code build/reports/replay-fixture/}, for a browser decoder's tests: a JSON
     * line per state with the tick, the status, a checksum of the buildings and every other list as stored.
     */
    static void fixture(ViewFile file, String name) throws IOException{
        Path dir = Path.of("build", "reports", "replay-fixture");
        Files.createDirectories(dir);
        Files.write(dir.resolve(name + ".sview"), file.encode());
        var reader = new ViewReader(file.header());
        var lines = new StringBuilder();
        int index = 0;
        for(byte[] segment : file.segments()){
            for(ViewReader.State state : reader.segment(ViewReader.inflate(segment))){
                if(index++ % 7 != 0) continue;
                var line = new LinkedHashMap<String, Object>();
                line.put("tick", state.tick);
                line.put("status", state.status);
                long sum = 0;
                for(var entry : state.buildings.entrySet()){
                    sum = sum * 31 + entry.getKey();
                    for(int value : entry.getValue()) sum = sum * 31 + value;
                }
                line.put("builds", state.buildings.size());
                line.put("buildSum", Long.toString(sum));
                line.put("events", state.events);
                line.put("units", rows(state.units, u -> Arrays.copyOf(u, 10)));
                line.put("bullets", rows(state.bullets, b -> new long[]{b[0], b[1], b[2], b[3], b[4], b[5], b[9]}));
                var players = new ArrayList<Object>();
                state.players.forEach((id, p) -> players.add(List.of(id, p.name(), p.color(), p.team(), p.flags(), p.unit())));
                line.put("players", players);
                line.put("chat", state.chat.stream().map(c -> List.of(c.player(), c.text(), c.age())).toList());
                line.put("plans", rows(state.plans, p -> p));
                line.put("fires", rows(state.fires, life -> new int[]{life}));
                line.put("puddles", rows(state.puddles, p -> p));
                line.put("rebuild", rows(state.rebuild, p -> p));
                lines.append(Bundle.JSON.writeValueAsString(line)).append('\n');
            }
        }
        Files.writeString(dir.resolve(name + ".dump.jsonl"), lines);
    }

    /** {@code map}'s entries as {@code [key, values...]} rows, ascending. */
    private static <T> List<List<Object>> rows(Map<Integer, T> map, java.util.function.Function<T, Object> values){
        var out = new ArrayList<List<Object>>();
        map.forEach((key, value) -> {
            var row = new ArrayList<Object>();
            row.add(key);
            Object array = values.apply(value);
            if(array instanceof int[] ints) for(int v : ints) row.add(v);
            else if(array instanceof long[] longs) for(long v : longs) row.add(v);
            out.add(row);
        });
        return out;
    }

    /** What a decoder finds in a stream. */
    static final class Seen{
        final TreeSet<String> players = new TreeSet<>();
        int chat, plans, bullets, waves, sites, aiming, fires, puddles, rebuild;
        boolean leftAndCameBack;

        @Override
        public String toString(){
            return "players " + players + ", chat " + chat + ", plans " + plans + ", bullets " + bullets + ", waves " + waves + ", sites " + sites
                + ", aiming turrets " + aiming + ", fires " + fires + ", puddles " + puddles + ", rebuild plans " + rebuild;
        }
    }

    static Seen seen(ViewFile file){
        var reader = new ViewReader(file.header());
        var seen = new Seen();
        int wave = ViewWriter.EFFECTS.indexOf("wave");
        Set<Integer> before = Set.of();
        for(byte[] segment : file.segments()){
            for(ViewReader.State state : reader.segment(ViewReader.inflate(segment))){
                state.players.values().forEach(player -> seen.players.add(player.name()));
                seen.chat += state.chat.size();
                seen.plans += state.plans.size();
                seen.bullets += state.bullets.size();
                seen.fires += state.fires.size();
                seen.puddles += state.puddles.size();
                seen.rebuild += state.rebuild.size();
                for(int[] b : state.buildings.values()){
                    if((b[4] & (ViewSource.BuildView.CONSTRUCT | ViewSource.BuildView.DECONSTRUCT)) != 0) seen.sites++;
                    if((b[4] & ViewSource.BuildView.AIM) != 0) seen.aiming++;
                }
                for(int[] event : state.events) if(event[0] == wave) seen.waves++;
                if(!state.keyframe && !before.isEmpty() && !state.players.keySet().containsAll(before)) seen.leftAndCameBack = true;
                before = state.players.keySet();
            }
        }
        return seen;
    }
}
