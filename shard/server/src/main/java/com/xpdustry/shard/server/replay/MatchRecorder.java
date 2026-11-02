package com.xpdustry.shard.server.replay;

import com.xpdustry.shard.server.chat.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.replay.view.*;
import com.xpdustry.shard.server.sync.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.game.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.sim.world.Observer;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Records one match of one hosted world into its {@link Bundle}, live, from {@link WorldSync#record}: after every
 * tick it takes the inputs the world admitted, and every {@link ViewWriter.Options#frameEvery} ticks a view frame; at
 * every keyframe tick (a multiple of {@code keyframeEvery}, where the world also writes a match checkpoint) it closes
 * a log file and a segment and records the world's hash. Compressing and writing happen on {@code io}, in order.
 *
 * <p>Restores: the world becomes resident again at a tick ({@link #resident}). One earlier than the last recorded
 * tick starts a new timeline, so everything recorded after it goes. The view stream goes on at the next keyframe; the
 * segment the restore cut is regenerated from the bundle when the match is baked. It observes the world itself from
 * {@code resident} on, so it sees every event of the first tick too.
 *
 * <p>Threads: {@link #frame} on the world's tick thread; the lifecycle calls on the server thread while the world
 * doesn't tick. Both hold this. The writes on {@code io} never do: the lifecycle calls wait for them. The segment index
 * has its own lock.
 */
final class MatchRecorder implements Recorder{
    private static final System.Logger LOG = System.getLogger(MatchRecorder.class.getName());
    /** The events a view shows. */
    static final List<Class<? extends WorldEvent>> EVENTS = List.of(UnitEvent.Death.class, BuildingEvent.Destroyed.class, WaveEvent.Started.class,
        GameEvent.GameOver.class, ChatEvent.Said.class);

    private final Listener replays;
    private final Bundle bundle;
    private final ViewWriter.Options options;
    private final Executor io;
    /** The queued writes' tail; replaced under this. */
    private volatile CompletableFuture<Void> writes = CompletableFuture.completedFuture(null);

    /**
     * Replaced under this; read without it. Writes on {@code io} read it ({@link Listener#segmentWritten}) while a
     * lifecycle call holds this and waits for them, so a lock here would deadlock the shutdown.
     */
    private volatile Bundle.Manifest manifest;
    private World world;
    private Observer observer;
    private ShardView view;
    private ViewWriter writer;
    private ViewWriter.Baseline baseline;
    private volatile ViewHeader header;
    private InputLog.Writer log;
    private long consumed;
    private final TreeMap<Long, String> hashes;
    private final TreeMap<Long, Bundle.SegmentEntry> segments;
    private boolean stopped;
    private long dropped;

    /** Who hears what the recorder does: {@link Replays}. */
    interface Listener{
        /** From the tick thread: the match had its game over; end it elsewhere. */
        void gameOver(MatchRecorder recorder, int winner);

        /** From {@code io}: a segment is on disk. */
        void segmentWritten(MatchRecorder recorder, long tick);
    }

    MatchRecorder(Listener replays, Bundle bundle, Bundle.Manifest manifest, ViewWriter.Options options, Executor io) throws IOException{
        this.replays = replays;
        this.bundle = bundle;
        this.manifest = manifest;
        this.options = options;
        this.io = io;
        hashes = new TreeMap<>(bundle.hashes());
        segments = new TreeMap<>(bundle.segments());
        if(java.nio.file.Files.isRegularFile(bundle.terrain())) baseline = Regenerator.baseline(bundle, manifest);
        java.nio.file.Path headerFile = bundle.dir().resolve("view").resolve("header.json");
        if(java.nio.file.Files.isRegularFile(headerFile)) header = ViewHeader.parse(java.nio.file.Files.readAllBytes(headerFile));
    }

    Bundle bundle(){
        return bundle;
    }

    Bundle.Manifest manifest(){
        return manifest;
    }

    @Override
    public int frameEvery(){
        return 1;
    }

    // ---- lifecycle, on the server thread

    /**
     * The world is resident at {@code tick}, before its first tick: a fresh world, or one restored from a checkpoint.
     * Anything recorded after {@code tick} belongs to a timeline the world no longer runs.
     */
    synchronized void resident(World world, long tick) throws IOException{
        if(manifest.over()) return;
        if(tick < manifest.end()) cut(tick);
        if(observer != null) observer.close();
        this.world = world;
        observer = world.observe(1 << 16, EVENTS, null);
        dropped = 0;
        consumed = world.log().position();
        if(log == null || log.from() > tick) log = new InputLog.Writer(tick);
        view = new ShardView(world, manifest.mapName(), manifest.match(), manifest.mode());
        writer = null;
        if(keyframe(tick)) startView(tick);
        if(checkpointTick(tick) && !hashes.containsKey(tick)) hashes.put(tick, hash(world.hash()));
        manifest = manifest.withEnd(tick, "recording");
        stopped = false;
        flushIndex();
    }

    /** Forgets what was recorded after {@code tick}: the world was restored there, a timeline the rest isn't part of. */
    private void cut(long tick) throws IOException{
        LOG.log(System.Logger.Level.INFO, bundle.match() + ": restored at tick " + tick + ", forgetting ticks " + tick + "-" + manifest.end());
        long segmentTick = segmentOf(tick);
        await();
        if(log != null) log.truncate(tick);
        for(long from : bundle.logs()){
            if(from >= tick) java.nio.file.Files.deleteIfExists(bundle.log(from));
            else if(log == null || from < log.from()){
                byte[] cut = InputLog.truncate(java.nio.file.Files.readAllBytes(bundle.log(from)), tick);
                if(cut == null) java.nio.file.Files.deleteIfExists(bundle.log(from));
                else Bundle.write(bundle.log(from), cut);
            }
        }
        if(log != null && log.from() >= tick) log = null;
        hashes.tailMap(tick, false).clear();
        synchronized(segments){
            segments.tailMap(segmentTick, true).clear();
        }
        for(long at : bundle.segmentFiles()) if(at >= segmentTick) java.nio.file.Files.deleteIfExists(bundle.segment(at));
        for(long at : bundle.checkpoints()) if(at > tick) java.nio.file.Files.deleteIfExists(bundle.checkpoint(at));
        writer = null;
    }

    /** The world hibernated at {@code tick}: what is in memory goes to disk, a partial segment included. */
    synchronized void hibernated(long tick) throws IOException{
        if(manifest.over() || world == null) return;
        closeLog(tick);
        if(writer != null){
            for(ViewWriter.Segment segment : writer.finish()) writeSegment(segment, segment.tick() + options.keyframeEvery() > tick);
            writer = null;
        }
        if(observer != null) observer.close();
        observer = null;
        world = null;
        manifest = manifest.withEnd(tick, "suspended");
        flushIndex();
        await();
    }

    /**
     * The match ended at the last recorded tick ({@code winner} won, or -1): the log and the view stream are closed, and
     * nothing more is recorded. Then {@link Replays} bakes and publishes it.
     */
    synchronized void end(int winner) throws IOException{
        if(manifest.over()) return;
        long end = manifest.end();
        if(world != null) closeLog(end + 1);
        else if(log != null && log.size() > 0) closeLog(end + 1);
        if(writer != null){
            for(ViewWriter.Segment segment : writer.finish()) writeSegment(segment, false);
            writer = null;
        }
        if(observer != null) observer.close();
        observer = null;
        world = null;
        stopped = true;
        // a segment cut by a hibernation the match didn't come back from is whole: the match ends where it does
        long lastFrame = manifest.start() + Math.floorDiv(end - manifest.start(), options.frameEvery()) * options.frameEvery();
        await();
        synchronized(segments){
            if(!segments.isEmpty()){
                Bundle.SegmentEntry last = segments.lastEntry().getValue();
                if(last.partial() && last.tick() + (long)(last.frames() - 1) * options.frameEvery() == lastFrame){
                    segments.put(last.tick(), new Bundle.SegmentEntry(last.tick(), last.frames(), last.hash(), false));
                }
            }
        }
        manifest = manifest.ended(Instant.now().toString(), end, winner);
        flushIndex();
        await();
    }

    // ---- the tick thread

    @Override
    public synchronized void frame(World world, List<WorldEvent> ignored){
        if(stopped || world != this.world || observer == null) return;
        long tick = world.ticks();
        List<WorldEvent> events = observer.drain();
        if(observer.dropped() > dropped){
            dropped = observer.dropped();
            LOG.log(System.Logger.Level.WARNING, bundle.match() + ": the recorder's observer dropped events; the live view differs from a regenerated one");
        }
        view.accept(events, tick);
        CommandLog commands = world.log();
        if(commands.position() > consumed){
            for(CommandLog.Entry entry : commands.since(consumed)) log.add(entry);
            consumed = commands.position();
        }
        manifest = manifest.withEnd(tick, "recording");
        if(checkpointTick(tick)){
            hashes.put(tick, hash(world.hash()));
            closeLog(tick);
            log = new InputLog.Writer(tick);
        }
        if(writer != null){
            writer.sample();
            for(ViewWriter.Segment segment : writer.take()) writeSegment(segment, false);
        }else if(keyframe(tick)){
            startView(tick);
        }
        if(checkpointTick(tick)) flushIndex();
        for(WorldEvent event : events){
            if(event instanceof GameEvent.GameOver over){
                // nothing after the game over is part of the match; Replays ends it off this thread
                stopped = true;
                replays.gameOver(this, over.winner());
                break;
            }
        }
    }

    @Override
    public void detached(){
        // the world stopped or was restored: the lifecycle calls say which
    }

    // ---- pieces

    private void startView(long tick){
        if(baseline == null){
            writer = new ViewWriter(view, options);
            writer.sample();
            baseline = writer.baseline();
            header = ViewHeader.of(view, options, baseline.start());
            byte[] terrain = writer.terrain();
            ViewHeader template = header;
            write(() -> {
                Bundle.write(bundle.terrain(), terrain);
                Bundle.write(bundle.dir().resolve("view").resolve("header.json"), template.json());
            });
        }else{
            writer = new ViewWriter(view, options, baseline);
            writer.sample();
        }
    }

    /** Writes the inputs since the open file's start, up to {@code tick} (exclusive), as {@code log/<from>.slog}. */
    private void closeLog(long tick){
        InputLog.Writer closing = log;
        log = null;
        if(closing == null) return;
        String content = world != null ? world.content().hash() : manifest.content();
        long systems = manifest.systems();
        var labels = new TreeMap<String, String>();
        labels.put("match", manifest.match());
        labels.put("until", Long.toString(tick));
        write(() -> Bundle.write(bundle.log(closing.from()), closing.encode(content, systems, labels, Compression.FAST)));
    }

    private void writeSegment(ViewWriter.Segment segment, boolean partial){
        widenTeams();
        int level = options.level();
        write(() -> {
            String hash = segment.hash();
            Bundle.write(bundle.segment(segment.tick()), segment.deflated(level));
            synchronized(segments){
                segments.put(segment.tick(), new Bundle.SegmentEntry(segment.tick(), segment.frames(), hash, partial));
            }
            replays.segmentWritten(this, segment.tick());
        });
    }

    /**
     * Adds the teams the view met since the header was written (a wave's team, a new PvP team): readers check every
     * team a segment names against the header's. Segments don't depend on it, so a regenerated stream reads the same.
     */
    private void widenTeams(){
        if(header == null || view == null) return;
        ViewHeader widened = Regenerator.withTeams(header, view.teams());
        if(widened == header) return;
        header = widened;
        write(() -> Bundle.write(bundle.dir().resolve("view").resolve("header.json"), widened.json()));
    }

    /** The manifest, the hashes and the segment index, as they are now. */
    private void flushIndex(){
        Bundle.Manifest now = manifest;
        var hashesNow = new TreeMap<>(hashes);
        write(() -> {
            bundle.manifest(now);
            bundle.hashes(hashesNow);
            bundle.segments(segmentIndex());
        });
    }

    private interface Write{
        void run() throws IOException;
    }

    /** Queues a disk write after the earlier ones. */
    private void write(Write task){
        writes = writes.thenRunAsync(() -> {
            try{
                task.run();
            }catch(IOException e){
                throw new UncheckedIOException(e);
            }
        }, io).exceptionally(failure -> {
            LOG.log(System.Logger.Level.WARNING, bundle.match() + ": a replay write failed", failure);
            return null;
        });
    }

    /** Waits for the queued writes. */
    void await(){
        writes.join();
    }

    /** The view header without its segment index, once the view stream started. */
    ViewHeader header(){
        return header;
    }

    SortedMap<Long, Bundle.SegmentEntry> segmentIndex(){
        synchronized(segments){
            return new TreeMap<>(segments);
        }
    }

    private boolean keyframe(long tick){
        long start = baseline != null ? baseline.start() : manifest.start();
        return tick >= start && (tick - start) % options.keyframeEvery() == 0;
    }

    private boolean checkpointTick(long tick){
        return tick % manifest.checkpointEvery() == 0;
    }

    private long segmentOf(long tick){
        long start = baseline != null ? baseline.start() : manifest.start();
        return start + Math.floorDiv(tick - start, options.keyframeEvery()) * options.keyframeEvery();
    }

    static String hash(WorldHash hash){
        var out = new StringBuilder(String.format("%016x", hash.value()));
        for(WorldHash.Section section : hash.sections()) out.append(' ').append(section.name()).append('=').append(String.format("%016x", section.hash()));
        return out.toString();
    }

    @Override
    public String toString(){
        return "MatchRecorder[" + bundle.match() + "]";
    }
}
