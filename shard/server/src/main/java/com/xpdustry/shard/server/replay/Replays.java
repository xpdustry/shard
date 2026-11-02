package com.xpdustry.shard.server.replay;

import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.config.*;
import com.xpdustry.shard.server.config.ConfigKeys.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.replay.view.*;
import com.xpdustry.shard.server.sched.*;
import com.xpdustry.shard.server.world.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.world.*;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/**
 * The server's replays (note 37): for each hosted world with {@code replay.record}, a
 * {@link MatchRecorder} on its {@code WorldSync} records every match into a {@link Bundle}, and the match's view stream
 * is published under {@code views/} as files: {@code <match>.sview} whole, and {@code <match>/} split for lazy
 * loading. Serving them is the web host's job; Shard runs no HTTP server.
 *
 * <p>The policy, until note 37 §8 is settled (each a config key):
 * <ul>
 *   <li>bundles live with their build ({@code replay.keep-bundles: build}): one recorded by an earlier build is deleted
 *   at startup once its view stream is baked, since only its own build replays it;</li>
 *   <li>view streams stay until someone deletes them ({@code replay.keep-views: 0});</li>
 *   <li>a PvP match's view stream is published only once the match ends ({@code replay.pvp-views: after-match}); other
 *   matches publish each segment as it closes;</li>
 *   <li>no in-game replays ({@code replay.in-game: none}).</li>
 * </ul>
 *
 * <p>A match ends with a game over, when its world is stopped or replaced, or when the server stops without
 * {@code persistence.resume}. Its bundle is then baked: the segments the live recorder couldn't write whole are made
 * again from the bundle ({@link Regenerator}), and the whole stream is published.
 */
public final class Replays implements MatchListener, MatchRecorder.Listener{
    private static final System.Logger LOG = System.getLogger(Replays.class.getName());

    private final ReplayStore store;
    private final ConfigService config;
    private final Host host;
    private final ServerThreads threads;
    private final boolean resume;
    private final ViewWriter.Options options = ViewWriter.Options.DEFAULT;
    private final ConcurrentHashMap<String, MatchRecorder> recorders = new ConcurrentHashMap<>();
    /** Recorders a reload turned off, ended once; kept, as turning recording off is a rare admin action. */
    private final Set<MatchRecorder> stopping = ConcurrentHashMap.newKeySet();
    /** The recorder each hosted world records with now. */
    private final ConcurrentHashMap<HostedWorld, MatchRecorder> recording = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<CompletableFuture<?>> work = new ConcurrentLinkedQueue<>();
    private volatile boolean closing;

    private Replays(Path root, ConfigService config, Host host, ServerThreads threads, LifecyclePolicy policy){
        this.store = new ReplayStore(root);
        this.config = config;
        this.host = host;
        this.threads = threads;
        this.resume = policy.resume();
    }

    /**
     * The replays of a server: listens to {@code host}'s world manager, and applies the retention policy to what is on
     * disk. Call it before the first world is hosted.
     */
    public static Replays start(ConfigService config, Host host, ServerThreads threads) throws IOException{
        Config current = config.current();
        Path root = Path.of(current.get(ConfigKeys.REPLAY_DIRECTORY)).toAbsolutePath();
        Files.createDirectories(root.resolve("bundles"));
        Files.createDirectories(root.resolve("views"));
        var replays = new Replays(root, config, host, threads, host.manager().policy());
        host.manager().matches(replays);
        // a reload that turns replay.record off stops the worlds recording now, even when they write no checkpoints to hear it at
        config.subscribe(reloaded -> threads.server().execute(replays::sweep));
        replays.retain(current.get(ConfigKeys.REPLAY_KEEP_BUNDLES), current.get(ConfigKeys.REPLAY_KEEP_VIEWS));
        return replays;
    }

    public ReplayStore store(){
        return store;
    }

    private Path views(){
        return store.views();
    }

    Bundle bundle(String match){
        return store.bundle(match);
    }

    /** The world's own {@code replay.record}, and match checkpoints to record from: its effective config, live. */
    private boolean records(WorldId id){
        return config.current().world(id.name()).get(ConfigKeys.REPLAY_RECORD) && checkpointEvery(id) > 0;
    }

    private int checkpointEvery(WorldId id){
        return Server.matchTicks(config.current().world(id.name()));
    }

    /**
     * On the server thread after a reload: a world that records but no longer should stops, and its bundle is closed
     * as a match end closes it ({@link #stop}).
     */
    private void sweep(){
        for(var entry : recording.entrySet()){
            HostedWorld hosted = entry.getKey();
            if(!records(hosted.id())) stop(entry.getValue(), hosted.id());
        }
    }

    /**
     * {@code replay.record} went off for a match still being played: its bundle ends where it is, as at a match end
     * (no winner), not {@code suspended}: a suspended bundle is waited for at the same tick, and nothing recorded
     * covers the ticks played unrecorded. The view stream so far is baked and published. The match records no more:
     * turning it on again starts with the next match.
     */
    private void stop(MatchRecorder recorder, WorldId id){
        // the sweep (server thread) and keyframe (tick thread) may both see the same recorder: only one ends it
        if(!stopping.add(recorder)) return;
        try{
            LOG.log(System.Logger.Level.INFO, id + ": replay.record is off; ending the replay of " + recorder.bundle().match());
            end(recorder, -1, true);
        }catch(IOException | RuntimeException e){
            LOG.log(System.Logger.Level.WARNING, id + ": ending the replay of " + recorder.bundle().match() + " failed: " + e, e);
        }
    }

    // ---- MatchListener

    @Override
    public void resident(WorldId id, String match, long tick, boolean fresh, SortedMap<String, String> labels, World world){
        if(!records(id)) return;
        try{
            MatchRecorder recorder = recorder(id, match, tick, labels, world);
            if(recorder == null) return;
            recorder.resident(world, tick);
            HostedWorld hosted = host.world(id.name()).orElse(null);
            if(hosted != null){
                MatchRecorder before = recording.put(hosted, recorder);
                if(before != recorder){
                    if(before != null) hosted.sync().stopRecording(before);
                    hosted.sync().record(recorder);
                }
            }
        }catch(IOException | RuntimeException e){
            LOG.log(System.Logger.Level.WARNING, id + ": can't record " + match + ": " + e, e);
        }
    }

    /** The recorder of {@code match}, made if it has none; null when its bundle is over. */
    private MatchRecorder recorder(WorldId id, String match, long tick, SortedMap<String, String> labels, World world) throws IOException{
        MatchRecorder recorder = recorders.get(match);
        if(recorder == null){
            Bundle bundle = bundle(match);
            Bundle.Manifest manifest = bundle.exists() ? bundle.manifest() : manifest(id, match, tick, labels, world);
            if(manifest.over()) return null;
            recorder = new MatchRecorder(this, bundle, manifest, options, threads.io());
            recorders.put(match, recorder);
        }
        return recorder;
    }

    /**
     * A match checkpoint is due in a match under way. If the world records now and didn't, it starts here, at a
     * keyframe, with no restart; if it records and shouldn't, it stops ({@link #sweep} may have done so already).
     */
    @Override
    public void keyframe(WorldId id, String match, long tick, SortedMap<String, String> labels, World world){
        MatchRecorder recorder = recorders.get(match);
        try{
            if(!records(id)){
                if(recorder != null && recording.containsValue(recorder)) stop(recorder, id);
                return;
            }
            if(recorder != null && recording.containsValue(recorder)) return;
            resident(id, match, tick, false, labels, world);
        }catch(RuntimeException e){
            LOG.log(System.Logger.Level.WARNING, id + ": can't record " + match + " from tick " + tick + ": " + e, e);
        }
    }

    private Bundle.Manifest manifest(WorldId id, String match, long tick, SortedMap<String, String> labels, World world){
        HostedWorld hosted = host.world(id.name()).orElse(null);
        String mapName = hosted != null ? hosted.map().name() : labels.getOrDefault("map", id.name());
        String mode = labels.getOrDefault("mode", hosted != null ? hosted.mode().name() : "survival").toLowerCase(Locale.ROOT);
        Rules rules = world.part(WorldRules.class).rules;
        // the stream starts at a keyframe, where the world has a match checkpoint
        long start = Math.ceilDiv(tick, options.keyframeEvery()) * options.keyframeEvery();
        return new Bundle.Manifest(Bundle.Manifest.FORMAT, BuildInfo.id(), world.content().hash(), Checkpoint.systemsId(world.config()), "v160.7",
            id.name(), match, labels.getOrDefault("map", ""), mapName, mode, rules.pvp, Instant.now().toString(), null, start, tick, checkpointEvery(id),
            options.frameEvery(), options.keyframeEvery(), "recording", -1, false);
    }

    @Override
    public void checkpoint(WorldId id, String match, long tick, Path file){
        if(!records(id)) return;
        try{
            Bundle.link(file, bundle(match).checkpoint(tick));
        }catch(IOException e){
            LOG.log(System.Logger.Level.WARNING, id + ": the replay of " + match + " lost its checkpoint at tick " + tick + ": " + e);
        }
    }

    @Override
    public void hibernated(WorldId id, String match, long tick){
        MatchRecorder recorder = recorders.get(match);
        if(recorder == null) return;
        try{
            recorder.hibernated(tick);
            // a server stopping without resume ends its matches here: nothing will go on with them
            if(closing && !resume) end(recorder, -1, false);
        }catch(IOException | RuntimeException e){
            LOG.log(System.Logger.Level.WARNING, id + ": saving the replay of " + match + " failed: " + e, e);
        }
    }

    @Override
    public void ended(WorldId id, String match){
        MatchRecorder recorder = recorders.get(match);
        try{
            if(recorder == null){
                Bundle bundle = bundle(match);
                if(!bundle.exists() || bundle.manifest().over()) return;
                recorder = new MatchRecorder(this, bundle, bundle.manifest(), options, threads.io());
            }
            end(recorder, -1, true);
        }catch(IOException | RuntimeException e){
            LOG.log(System.Logger.Level.WARNING, id + ": ending the replay of " + match + " failed: " + e, e);
        }
    }

    /** From the tick thread: the match had its game over. It ends on the server thread. */
    @Override
    public void gameOver(MatchRecorder recorder, int winner){
        track(CompletableFuture.runAsync(() -> {
            try{
                end(recorder, winner, true);
            }catch(IOException e){
                throw new UncheckedIOException(e);
            }
        }, threads.server()));
    }

    /** Ends a match: the recorder closes its bundle, stops recording, and the view stream is baked and published. */
    private void end(MatchRecorder recorder, int winner, boolean async) throws IOException{
        recorder.end(winner);
        recorders.remove(recorder.bundle().match(), recorder);
        recording.forEach((hosted, current) -> {
            if(current == recorder && recording.remove(hosted, recorder)) hosted.sync().stopRecording(recorder);
        });
        LOG.log(System.Logger.Level.INFO, recorder.bundle().match() + ": the match ended at tick " + recorder.manifest().end() + "; baking its view stream");
        if(async) track(CompletableFuture.runAsync(() -> publish(recorder.bundle()), threads.ioCpu()));
        else publish(recorder.bundle());
    }

    // ---- publishing

    /** A live segment is on disk: a match that may be watched live gets it in its split view. */
    @Override
    public void segmentWritten(MatchRecorder recorder, long tick){
        Bundle.Manifest manifest = recorder.manifest();
        if(manifest.pvp() && config.current().world(manifest.world()).get(ConfigKeys.REPLAY_PVP_VIEWS) == PvpViews.AFTER_MATCH) return;
        ViewHeader header = recorder.header();
        if(header == null) return;
        try{
            Path dir = views().resolve(manifest.match());
            // the split view lists segments from the start up to the first one not recorded yet
            var index = new ArrayList<ViewHeader.Index>();
            long end = manifest.start();
            for(Bundle.SegmentEntry entry : recorder.segmentIndex().values()){
                if(entry.tick() != manifest.start() + (long)index.size() * options.keyframeEvery()) break;
                Path piece = dir.resolve("segments").resolve(Integer.toString(index.size()));
                byte[] bytes = Files.readAllBytes(recorder.bundle().segment(entry.tick()));
                // a piece is written once, or again when a partial segment got longer
                if(!Files.isRegularFile(piece) || Files.size(piece) != bytes.length) Bundle.write(piece, bytes);
                index.add(new ViewHeader.Index(-1, bytes.length, entry.tick() - manifest.start(), entry.frames(), entry.hash()));
                end = entry.tick() + (long)(entry.frames() - 1) * options.frameEvery();
            }
            if(index.isEmpty()) return;
            Files.createDirectories(dir);
            Bundle.write(dir.resolve("terrain"), Files.readAllBytes(recorder.bundle().terrain()));
            ViewHeader live = header.withSegments(index, (int)Files.size(dir.resolve("terrain"))).withEnd(end, false, -1);
            Bundle.write(dir.resolve("header.json"), live.json());
            store.index(manifest, live, null);
        }catch(IOException | RuntimeException e){
            LOG.log(System.Logger.Level.WARNING, manifest.match() + ": publishing a live segment failed: " + e);
        }
    }

    /** Bakes a finished match's view stream and publishes it whole and split. */
    void publish(Bundle bundle){
        try{
            long bytes = store.publish(bundle, line -> LOG.log(System.Logger.Level.INFO, bundle.match() + ": " + line));
            LOG.log(System.Logger.Level.INFO, bundle.match() + ": published views/" + bundle.match() + ".sview (" + bytes + " B)");
        }catch(IOException | RuntimeException e){
            LOG.log(System.Logger.Level.WARNING, bundle.match() + ": baking its view stream failed: " + e, e);
        }
    }

    // ---- retention

    /**
     * Bundles of other builds go once their view stream is baked ({@code keep-bundles: build}); view streams older
     * than {@code keepViews} go too, unless it is 0.
     */
    private void retain(BundleRetention keepBundles, Duration keepViews) throws IOException{
        if(keepBundles == BundleRetention.BUILD){
            try(Stream<Path> builds = Files.list(store.root().resolve("bundles"))){
                for(Path build : builds.toList()){
                    if(!Files.isDirectory(build) || build.getFileName().toString().equals(BuildInfo.id())) continue;
                    try(Stream<Path> matches = Files.list(build)){
                        for(Path dir : matches.toList()){
                            var bundle = new Bundle(dir);
                            if(Files.isRegularFile(views().resolve(bundle.match() + ".sview"))){
                                bundle.delete();
                                LOG.log(System.Logger.Level.INFO, "deleted the bundle of " + bundle.match() + " (build " + build.getFileName() + "): its view stream is baked");
                            }else{
                                LOG.log(System.Logger.Level.WARNING, "the bundle of " + bundle.match() + " (build " + build.getFileName()
                                    + ") has no baked view stream, and this build can't make one; kept");
                            }
                        }
                    }
                    try(Stream<Path> left = Files.list(build)){
                        if(left.findAny().isEmpty()) Files.deleteIfExists(build);
                    }
                }
            }
        }
        if(!keepViews.isZero()){
            Instant before = Instant.now().minus(keepViews);
            try(Stream<Path> views = Files.list(views())){
                for(Path view : views.toList()){
                    String name = view.getFileName().toString();
                    if(!name.endsWith(".sview") || Files.getLastModifiedTime(view).toInstant().isAfter(before)) continue;
                    Files.deleteIfExists(view);
                    new Bundle(views().resolve(name.substring(0, name.length() - ".sview".length()))).delete();
                }
            }
        }
    }

    // ---- shutdown

    /**
     * The server is stopping: unless its worlds will resume, matches already suspended (their worlds hibernated while
     * empty) end now, and worlds that hibernate from now on end theirs.
     */
    public void closing(){
        closing = true;
        if(resume) return;
        // a world that hibernated before the stop (an empty one) doesn't hibernate again, so hibernated() never ends its
        // match: end every suspended one here, as nothing will go on with it (found on the live test server)
        for(MatchRecorder recorder : List.copyOf(recorders.values())){
            if(!"suspended".equals(recorder.manifest().state())) continue;
            try{
                end(recorder, -1, false);
            }catch(IOException | RuntimeException e){
                LOG.log(System.Logger.Level.WARNING, "ending the replay of " + recorder.bundle().match() + " failed: " + e, e);
            }
        }
    }

    /** Waits for the bakes in flight, at most {@code timeout}. */
    public void await(Duration timeout){
        long deadline = System.nanoTime() + timeout.toNanos();
        for(CompletableFuture<?> task; (task = work.poll()) != null; ){
            try{
                task.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            }catch(InterruptedException e){
                Thread.currentThread().interrupt();
                return;
            }catch(ExecutionException | TimeoutException e){
                LOG.log(System.Logger.Level.WARNING, "a replay task didn't finish: " + e);
            }
        }
    }

    private void track(CompletableFuture<?> task){
        work.removeIf(CompletableFuture::isDone);
        work.add(task);
    }
}
