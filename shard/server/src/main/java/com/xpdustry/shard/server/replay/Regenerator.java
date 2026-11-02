package com.xpdustry.shard.server.replay;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.replay.view.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.sim.world.Observer;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/**
 * The replay service on a real bundle (option (d) of note 37): it makes a view segment
 * from the bundle alone, by restoring the match checkpoint at or before the segment, replaying the logged inputs tick
 * by tick under their recorded arrival numbers, and sampling the world into a {@link ViewWriter} that continues the
 * stream's baseline. On the build that recorded the bundle, a segment comes out byte for byte as the live recorder
 * wrote it, and the world's hash at every checkpoint tick equals the one the live world reported.
 *
 * <p>{@link #verify} checks both for every segment; {@link #bake} makes the whole view stream, regenerating the
 * segments the live recorder couldn't write whole, so it can be watched after the build that recorded it is gone.
 */
public final class Regenerator{
    private final Bundle bundle;
    private final Bundle.Manifest manifest;
    private final ContentEpoch content;
    private final ViewWriter.Options options;
    private final ViewWriter.Baseline baseline;
    private final SortedSet<Long> checkpoints, logs;
    private final SortedMap<Long, String> hashes;
    private final SortedMap<Long, Bundle.SegmentEntry> segments;

    public Regenerator(Bundle bundle) throws IOException{
        this.bundle = bundle;
        manifest = bundle.manifest();
        if(!manifest.build().equals(BuildInfo.id())){
            throw new IOException(bundle.match() + " was recorded by build " + manifest.build() + "; this is " + BuildInfo.id()
                + ", and a bundle replays only on its own build");
        }
        content = ContentSource.resolve(manifest.content());
        options = new ViewWriter.Options(manifest.frameEvery(), manifest.keyframeEvery(), ViewWriter.Options.DEFAULT.level());
        baseline = baseline(bundle, manifest);
        checkpoints = bundle.checkpoints();
        logs = bundle.logs();
        hashes = bundle.hashes();
        segments = bundle.segments();
    }

    /** The view stream's baseline: its start and the terrain chunk the recorder wrote then. */
    static ViewWriter.Baseline baseline(Bundle bundle, Bundle.Manifest manifest) throws IOException{
        byte[] raw = ViewReader.inflate(Files.readAllBytes(bundle.terrain()));
        var in = new ByteReader(raw);
        int[][] layers = new int[3][];
        for(int layer = 0; layer < 3; layer++){
            var tiles = new ArrayList<int[]>();
            long runs = in.varint();
            int total = 0;
            for(long r = 0; r < runs; r++){
                int length = (int)in.varint(), block = (int)in.varint();
                tiles.add(new int[]{length, block});
                total += length;
            }
            var out = new int[total];
            int at = 0;
            for(int[] run : tiles){
                Arrays.fill(out, at, at + run[0], run[1]);
                at += run[0];
            }
            layers[layer] = out;
        }
        return new ViewWriter.Baseline(manifest.start(), layers[0], layers[1], layers[2]);
    }

    /**
     * One segment made again.
     *
     * @param live the hash the live recorder wrote, or null if it wrote none (or only part)
     * @param checked live world hashes compared on the way, at checkpoint ticks
     * @param problems hashes that differ on the way, empty when the world matched the live one at every checkpoint
     */
    public record Made(long tick, ViewWriter.Segment segment, String live, boolean partial, double millis, int checked, List<String> problems,
                       List<ViewSource.TeamView> teams){
        public boolean identical(){
            return live != null && live.equals(segment.hash());
        }
    }

    /** The keyframe ticks of the stream: one segment each, the last ending where the match does. */
    public List<Long> segmentTicks(){
        var out = new ArrayList<Long>();
        for(long tick = manifest.start(); tick <= manifest.end(); tick += options.keyframeEvery()) out.add(tick);
        return out;
    }

    /**
     * The segment whose keyframe is at {@code tick}, made from the bundle. A partial live segment (the world
     * hibernated in its middle) is made as far as the live one went, so the two compare; {@code whole} makes it to its
     * end instead.
     */
    public Made segment(long tick, boolean whole) throws IOException{
        long began = System.nanoTime();
        SortedSet<Long> before = checkpoints.headSet(tick + 1);
        if(before.isEmpty()) throw new IOException(bundle.match() + " has no checkpoint at or before tick " + tick);
        long from = before.last();
        var problems = new ArrayList<String>();
        int checkedBefore = checked;
        World world = restore(from, problems);
        var inputs = new Inputs(from);
        while(world.ticks() < tick) step(world, inputs, problems);

        Observer observer = world.observe(1 << 16, MatchRecorder.EVENTS, null);
        var view = new ShardView(world, manifest.mapName(), manifest.match(), manifest.mode());
        var writer = new ViewWriter(view, options, baseline);
        writer.sample();
        Bundle.SegmentEntry live = segments.get(tick);
        long until = Math.min(tick + options.keyframeEvery(), manifest.end());
        if(live != null && live.partial() && !whole) until = tick + (long)(live.frames() - 1) * options.frameEvery();
        while(world.ticks() < until){
            step(world, inputs, problems);
            view.accept(observer.drain(), world.ticks());
            writer.sample();
        }
        if(observer.dropped() > 0) problems.add("the observer dropped " + observer.dropped() + " events");
        observer.close();
        ViewWriter.Segment made = writer.finish().getFirst();
        return new Made(tick, made, live == null ? null : live.hash(), live != null && live.partial(), (System.nanoTime() - began) / 1e6,
            checked - checkedBefore, problems, view.teams());
    }

    /** The world at checkpoint {@code tick}, checked against the live hash there. */
    private World restore(long tick, List<String> problems) throws IOException{
        byte[] bytes = Files.readAllBytes(bundle.checkpoint(tick));
        Checkpoint checkpoint = Checkpoint.decode(bytes);
        WorldConfig stored = checkpoint.config(content);
        WorldConfig config = HostedSystems.matching(stored, checkpoint.header().systems());
        if(config == null) throw new IOException("checkpoint " + tick + " ran systems this build doesn't have");
        World world = checkpoint.restore(config);
        // the inputs that waited at the checkpoint are in the log, under their arrival numbers
        world.inbox().forget();
        check(world, problems);
        return world;
    }

    private void step(World world, Inputs inputs, List<String> problems) throws IOException{
        for(CommandLog.Entry entry : inputs.take(world.ticks(), world)){
            if(!world.inbox().replay(new Inbox.Arrival(entry.arrival(), entry.input()))){
                throw new IOException("the inbox refused input " + entry.arrival() + " at tick " + world.ticks());
            }
        }
        world.tick();
        check(world, problems);
    }

    /** Live hashes compared so far. */
    private int checked;

    /** At a checkpoint tick, the world's hash against the live one's. */
    private void check(World world, List<String> problems){
        String live = hashes.get(world.ticks());
        if(live == null) return;
        checked++;
        String now = MatchRecorder.hash(world.hash());
        if(!now.equals(live)) problems.add("tick " + world.ticks() + ": world hash " + now + ", live " + live);
    }

    /** The bundle's input logs, read in order across files. */
    private final class Inputs{
        private final Iterator<Long> files;
        private long nextFile;
        private InputLog.Reader reader;

        Inputs(long from) throws IOException{
            var starts = new ArrayList<>(logs);
            // the file holding tick `from`, and every one after it
            int first = 0;
            for(int i = 0; i < starts.size(); i++) if(starts.get(i) <= from) first = i;
            files = starts.subList(first, starts.size()).iterator();
            nextFile = files.hasNext() ? files.next() : Long.MAX_VALUE;
            advance(from);
            if(reader != null) reader.skip(from);
        }

        private void advance(long tick) throws IOException{
            while(nextFile <= tick){
                reader = InputLog.read(Files.readAllBytes(bundle.log(nextFile)), content);
                nextFile = files.hasNext() ? files.next() : Long.MAX_VALUE;
            }
        }

        List<CommandLog.Entry> take(long tick, World world) throws IOException{
            advance(tick);
            if(reader == null) return List.of();
            return reader.take(tick, world);
        }
    }

    // ---- verify and bake

    /** What {@link #verify} found. */
    public record Report(List<Made> segments, int hashesChecked, List<String> problems){
        public boolean ok(){
            return problems.isEmpty();
        }
    }

    /**
     * Makes every recorded segment again and compares it with the live one, and the world's hash with the live one at
     * every checkpoint tick on the way.
     */
    public Report verify(Consumer<String> out) throws IOException{
        return verify(out, 0, Integer.MAX_VALUE);
    }

    /** {@link #verify(Consumer)} of the segments numbered {@code first} to {@code last}, from 0. */
    public Report verify(Consumer<String> out, int first, int last) throws IOException{
        var made = new ArrayList<Made>();
        var problems = new ArrayList<String>();
        int compared = 0;
        List<Long> ticks = segmentTicks();
        for(long tick : ticks.subList(Math.min(first, ticks.size()), (int)Math.min(last + 1L, ticks.size()))){
            Made segment = segment(tick, false);
            made.add(segment);
            compared += segment.checked();
            String verdict = segment.live() == null ? "no live segment" : segment.identical() ? "identical" : "DIFFERS";
            out.accept(String.format("segment %d (tick %d%s): %s, %d frames, %d hashes, %.0f ms", (tick - manifest.start()) / options.keyframeEvery(), tick,
                segment.partial() ? ", partial" : "", verdict, segment.segment().frames(), segment.checked(), segment.millis()));
            // a segment a restore cut and the live recorder never wrote has nothing to compare: bake makes it
            if(segment.live() != null && !segment.identical()) problems.add("segment at tick " + tick + " differs from the live one");
            for(String problem : segment.problems()) problems.add("segment at tick " + tick + ": " + problem);
        }
        return new Report(made, compared, problems);
    }

    /**
     * The whole view stream: the live segments, and the ones the live recorder couldn't write whole made again. Each
     * live segment is checked against its hash first.
     */
    public ViewFile bake(Consumer<String> out) throws IOException{
        Path headerFile = bundle.dir().resolve("view").resolve("header.json");
        ViewHeader header = ViewHeader.parse(Files.readAllBytes(headerFile));
        var index = new ArrayList<ViewHeader.Index>();
        var deflated = new ArrayList<byte[]>();
        for(long tick : segmentTicks()){
            Bundle.SegmentEntry live = segments.get(tick);
            byte[] bytes = null;
            String hash = null;
            int frames = 0;
            if(live != null && !live.partial() && Files.isRegularFile(bundle.segment(tick))){
                bytes = Files.readAllBytes(bundle.segment(tick));
                hash = ViewWriter.hash(ViewReader.inflate(bytes));
                frames = live.frames();
                if(!hash.equals(live.hash())){
                    out.accept("segment at tick " + tick + " doesn't match its hash; making it again");
                    bytes = null;
                }
            }
            if(bytes == null){
                Made made = segment(tick, true);
                bytes = made.segment().deflated(options.level());
                hash = made.segment().hash();
                frames = made.segment().frames();
                out.accept(String.format("made segment at tick %d again (%.0f ms)", tick, made.millis()));
                header = withTeams(header, made.teams());
            }
            index.add(new ViewHeader.Index(-1, bytes.length, tick - manifest.start(), frames, hash));
            deflated.add(bytes);
        }
        byte[] terrain = Files.readAllBytes(bundle.terrain());
        ViewHeader whole = header.withSegments(index, terrain.length).withEnd(manifest.end(), manifest.over(), manifest.winner());
        return ViewFile.of(whole, terrain, deflated);
    }

    /** {@code header} with {@code teams} it doesn't list added, by id; itself when it lists them all. */
    static ViewHeader withTeams(ViewHeader header, List<ViewSource.TeamView> teams){
        var byId = new TreeMap<Integer, ViewSource.TeamView>();
        for(ViewSource.TeamView team : header.teams()) byId.put(team.id(), team);
        for(ViewSource.TeamView team : teams) byId.putIfAbsent(team.id(), team);
        return byId.size() == header.teams().size() ? header : header.withTeams(List.copyOf(byId.values()));
    }

    /** A varint reader over the terrain chunk. */
    private static final class ByteReader{
        private final byte[] data;
        private int at;

        ByteReader(byte[] data){
            this.data = data;
        }

        long varint(){
            long value = 0;
            for(int shift = 0; shift < 64; shift += 7){
                if(at >= data.length) throw new IllegalArgumentException("the terrain chunk ends early");
                int b = data[at++] & 0xFF;
                value |= (long)(b & 0x7F) << shift;
                if((b & 0x80) == 0) return value;
            }
            throw new IllegalArgumentException("a varint longer than 10 bytes in the terrain chunk");
        }
    }
}
