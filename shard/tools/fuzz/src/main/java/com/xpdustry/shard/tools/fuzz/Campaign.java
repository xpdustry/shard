package com.xpdustry.shard.tools.fuzz;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.config.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.replay.*;
import com.xpdustry.shard.server.sched.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.server.world.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.io.*;
import java.lang.management.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * One fuzzing run: an in-process {@link Server} hosting one world per map, a control client in each, and
 * {@link FuzzClient}s sending until {@link FuzzConfig#packets} messages are out. A monitor checks the invariants every
 * second while they run; at the end every recorded match is replayed from its bundle and its hashes compared.
 *
 * <p>The invariants (WP93):
 * <ol>
 *   <li>no server exception escapes: no log line at ERROR, none with a throwable, no uncaught exception, no world
 *       {@code FAILED};</li>
 *   <li>no world stops ticking: over every {@code tickWindowSeconds}, each world ran at least {@code minTickShare} of
 *       its ticks, and no tick took over a second (watchdog stalls are counted for the report);</li>
 *   <li>world state stays valid: units and players finite and on the map, item counts not negative, a player's unit on
 *       its team, no unit controlled by two players or moved to another team while a player holds it; and the replay
 *       audit makes every match again from its inputs with the live hashes;</li>
 *   <li>no session affects another world: the audit replays each world from its own inputs only, so a write from
 *       another world's session would show as a hash difference; and each world's control client keeps playing;</li>
 *   <li>memory stays bounded: the live heap after a full GC grows less than {@code heapGrowthMiB} over the run;</li>
 *   <li>a well-behaved control client in each world stays connected, gets entity snapshots and ping replies, and the
 *       server keeps its unit.</li>
 * </ol>
 */
final class Campaign implements AutoCloseable{
    /** A broken invariant. */
    record Violation(long atMessages, String invariant, String world, String detail){
        @Override
        public String toString(){
            return String.format("[%s] %s at %,d messages: %s", invariant, world, atMessages, detail);
        }
    }

    /** What a run found. */
    record Result(long messages, List<Violation> violations, String report){
        boolean ok(){
            return violations.isEmpty();
        }
    }

    static final int MAX_VIOLATIONS = 200, DUMPS = 20;

    private final FuzzConfig config;
    final Stats stats = new Stats();
    private final AtomicLong counter = new AtomicLong();
    private volatile boolean done;
    private Server server;
    private LogTap tap;
    private InetSocketAddress address;
    private final List<FuzzClient> fuzzers = new ArrayList<>();
    private final List<Thread> fuzzThreads = new ArrayList<>();
    private final List<ControlClient> controls = new ArrayList<>();
    private final List<Thread> controlThreads = new ArrayList<>();
    private volatile Map<String, Targets> targets = Map.of();
    private volatile List<Targets> targetList = List.of();
    private volatile Map<String, String> sessionWorlds = Map.of();
    private final List<Violation> violations = Collections.synchronizedList(new ArrayList<>());
    private final List<String> harness = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger dumps = new AtomicInteger();
    private volatile int conveyor = 257;
    private final Map<String, WorldWatch> watches = new TreeMap<>();
    /** Watchdog stalls per world over the run, for the report. */
    private final Map<String, Long> totalStalls = new TreeMap<>();
    /** Worlds already reported failed: once each. */
    private final Set<ManagedWorld> failed = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<String> heap = new ArrayList<>();
    private long heapBaseline = -1, heapMax;

    Campaign(FuzzConfig config){
        this.config = config;
    }

    FuzzConfig config(){
        return config;
    }

    long seed(){
        return config.seed();
    }

    InetSocketAddress address(){
        return address;
    }

    int conveyor(){
        return conveyor;
    }

    boolean done(){
        return done;
    }

    /** Counts {@code n} messages sent; false once the run's count is reached. */
    boolean count(int n){
        stats.sent.add(n);
        if(counter.addAndGet(n) >= config.packets()) done = true;
        return !done;
    }

    Targets targetsOf(String session){
        String world = sessionWorlds.get(session);
        Targets own = world == null ? null : targets.get(world);
        if(own != null) return own;
        List<Targets> all = targetList;
        return all.isEmpty() ? Targets.NONE : all.get(Math.floorMod(session.hashCode(), all.size()));
    }

    List<Targets> targets(){
        return targetList;
    }

    String worldOf(String session){
        refreshSessions();
        return sessionWorlds.get(session);
    }

    void clientFailure(FuzzClient client, RuntimeException e){
        var out = new StringWriter();
        e.printStackTrace(new PrintWriter(out));
        harness.add(client + " failed: " + out);
    }

    private void violation(String invariant, String world, String detail){
        if(violations.size() >= MAX_VIOLATIONS){
            done = true;
            return;
        }
        var v = new Violation(counter.get(), invariant, world, detail);
        violations.add(v);
        System.out.println("VIOLATION " + v);
        if(dumps.getAndIncrement() < DUMPS) dump(v);
    }

    /** The violation and every fuzzer's last packets, for a reproduction. */
    private void dump(Violation v){
        try{
            Path dir = Files.createDirectories(config.out().resolve("violations"));
            var out = new StringBuilder(v + "\n\nlast packets per fuzz client (session, transport:frame kind, packet, size, first 96 bytes hex):\n");
            synchronized(fuzzers){
                for(FuzzClient client : fuzzers){
                    out.append("\n# ").append(client).append('\n');
                    for(FuzzClient.Sent sent : client.recent()) out.append(sent).append('\n');
                }
            }
            Files.writeString(dir.resolve(String.format("%03d-%s.txt", dumps.get(), v.invariant())), out);
        }catch(IOException e){
            harness.add("dump failed: " + e);
        }
    }

    // ---- the run

    Result run() throws Exception{
        Files.createDirectories(config.out());
        Path root = config.out().resolve("server");
        Path replays = config.out().resolve("replays");
        start(root, replays);
        long began = System.nanoTime();
        try{
            refresh();
            for(int i = 0; i < config.maps().size(); i++){
                var control = new ControlClient(i, config.maps().get(i), this);
                controls.add(control);
                controlThreads.add(Thread.ofVirtual().name("control-" + i).start(control));
            }
            awaitControls();
            for(int i = 0; i < config.clients(); i++){
                var client = new FuzzClient(i, this, config.seed() + i);
                synchronized(fuzzers){
                    fuzzers.add(client);
                }
                fuzzThreads.add(Thread.ofVirtual().name("fuzz-" + i).start(client));
            }
            monitor(began);
            for(FuzzClient client : fuzzers) client.stop();
            for(Thread thread : fuzzThreads) thread.join(TimeUnit.MINUTES.toMillis(3));
            // the control clients go on alone for a while: a world the fuzzers left broken shows here
            long settle = System.nanoTime() + 5_000_000_000L;
            while(System.nanoTime() < settle){
                Thread.sleep(1000);
                check(false);
            }
            check(true);
        }finally{
            for(ControlClient control : controls) control.stop();
            for(Thread thread : controlThreads) thread.join(10_000);
        }
        String worlds = worldSummary();
        server.close();
        server = null;
        checkLog();
        String audit = config.audit() ? audit(replays) : "audit: skipped\n";
        tap.uninstall();
        long seconds = (System.nanoTime() - began) / 1_000_000_000L;
        var report = new StringBuilder();
        report.append(String.format("shard-fuzz: %,d messages in %d s, %d fuzz clients, worlds %s, seed %d%n", counter.get(), seconds, config.clients(),
            config.maps(), config.seed()));
        report.append(stats.summary());
        report.append(worlds);
        report.append("heap after GC (MiB): ").append(String.join(" ", heap)).append('\n');
        report.append(audit);
        report.append("warnings:\n");
        tap.warnings.entrySet().stream().sorted(Map.Entry.<String, AtomicLong>comparingByValue(Comparator.comparingLong(AtomicLong::get)).reversed())
            .limit(30).forEach(e -> report.append(String.format("  %,8d %s%n", e.getValue().get(), e.getKey())));
        if(!harness.isEmpty()) report.append("harness failures (the fuzzer's own bugs):\n  ").append(String.join("\n  ", harness)).append('\n');
        report.append(violations.isEmpty() ? "no invariant broken\n" : violations.size() + " violations:\n");
        synchronized(violations){
            for(Violation v : violations) report.append("  ").append(v).append('\n');
        }
        Files.writeString(config.out().resolve("report.txt"), report);
        return new Result(counter.get(), List.copyOf(violations), report.toString());
    }

    private void start(Path root, Path replays) throws Exception{
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> violation("exception", "-", "uncaught on " + thread.getName() + ": " + e));
        server = startServer(root, config.maps(), config.out().resolve("server.log"), replays);
        tap = LogTap.install();
        address = server.transport().address();
    }

    /**
     * A server on loopback hosting one world per map, set up for fuzzing: no player limit, a raised packet spam
     * limit, no kick cooldown, empty worlds simulated and never hibernated, matches recorded into {@code replays}.
     */
    static Server startServer(Path root, List<String> maps, Path log, Path replays) throws Exception{
        Path data = root.resolve("data");
        Files.createDirectories(data);
        String jar = vanillaJar();
        Files.writeString(root.resolve("shard.yml"), """
            net:
              port: 0
              player-limit: 0
              # vanilla's 300 per 3 s would blacklist every fuzz client within a second; the transport's own tests cover it
              packet-spam-limit: 100000000
            ops:
              enabled: false
            log:
              format: text
              level: warn
              file: "%s"
            admin:
              uuid-change-limit: 0
              kick-cooldown: 0s
            host:
              worlds: [%s]
              join: round-robin
            game:
              simulate-when-empty: true
            storage:
              database: "%s"
              backup-dir: "%s"
            persistence:
              directory: "%s"
              hibernate-after: 0s
            replay:
              record: true
              directory: "%s"
            maps:
              vanilla-jar: "%s"
              directory: "%s"
            """.formatted(log.toAbsolutePath(), String.join(", ", maps), data.resolve("shard.db").toAbsolutePath(),
            data.resolve("backups").toAbsolutePath(), data.resolve("worlds").toAbsolutePath(), replays.toAbsolutePath(), jar,
            root.resolve("maps").toAbsolutePath()));
        return Server.start(new Server.Options(root, Map.of(), true));
    }

    /** The vanilla jar the server hosts the default maps from: the system property, else the one on the class path. */
    static String vanillaJar() throws Exception{
        String property = System.getProperty("com.xpdustry.shard.fuzz.vanillaJar");
        if(property != null) return property;
        var url = Campaign.class.getClassLoader().getResource("maps/default/maze.msav");
        if(url == null) throw new IllegalStateException("no vanilla jar: set -Dcom.xpdustry.shard.fuzz.vanillaJar");
        String text = url.toString();
        return Path.of(URI.create(text.substring("jar:".length(), text.indexOf("!/")))).toString();
    }

    private void awaitControls() throws InterruptedException{
        long deadline = System.nanoTime() + config.joinWaitSeconds() * 1_000_000_000L;
        while(System.nanoTime() < deadline){
            if(controls.stream().allMatch(ControlClient::playing)) return;
            if(controls.stream().anyMatch(c -> c.closed != null)) break;
            Thread.sleep(50);
        }
        for(ControlClient control : controls){
            if(!control.playing()) violation("control", control.world, control + " never started playing: " + control.closed);
        }
    }

    private void monitor(long began) throws InterruptedException{
        long nextReport = System.nanoTime();
        int second = 0;
        while(!done){
            Thread.sleep(1000);
            second++;
            check(second % 5 == 0);
            if(second % 15 == 0) heap();
            if(System.nanoTime() >= nextReport){
                nextReport += 10_000_000_000L;
                long elapsed = Math.max(1, (System.nanoTime() - began) / 1_000_000_000L);
                System.out.printf("%,d messages (%,d/s), %,d joins, %d violations%n", counter.get(), counter.get() / elapsed, stats.joins.sum(), violations.size());
            }
        }
    }

    /** One round of checks: worlds, control clients, the log; {@code validity} also walks every world's state. */
    private void check(boolean validity){
        refresh();
        checkTicks();
        checkControls();
        checkLog();
        if(validity) checkValidity();
    }

    private void refresh(){
        var out = new TreeMap<String, Targets>();
        for(HostedWorld world : server.host().worlds()){
            String name = world.id().name();
            try{
                out.put(name, world.inspect(w -> Targets.of(name, w)).get(Hang.SECONDS, TimeUnit.SECONDS));
            }catch(TimeoutException e){
                violation("ticking", name, "the world didn't answer an inspection within " + Hang.SECONDS + " s");
            }catch(Exception e){
                // a world being replaced (game over) refuses inspections for a moment
            }
        }
        if(conveyor == 257 && !server.host().worlds().isEmpty()){
            try{
                conveyor = server.host().worlds().getFirst().inspect(w -> w.content().find(ContentType.BLOCK, "conveyor").id()).get(Hang.SECONDS, TimeUnit.SECONDS);
            }catch(Exception ignored){
            }
        }
        targets = out;
        targetList = List.copyOf(out.values());
        refreshSessions();
    }

    private void refreshSessions(){
        var out = new HashMap<String, String>();
        for(Session session : server.sessions().all()){
            if(session.world() instanceof HostedWorld world) out.put(session.identity().plainName(), world.id().name());
        }
        sessionWorlds = out;
    }

    /** A world's tick counters over a sliding window. */
    private static final class WorldWatch{
        HostedWorld world;
        final ArrayDeque<long[]> samples = new ArrayDeque<>();
        long stalls = -1;
        long longest;
        boolean late;
    }

    private void checkTicks(){
        long now = System.nanoTime();
        for(HostedWorld world : server.host().worlds()){
            String name = world.id().name();
            ManagedWorld managed = world.managed();
            if(managed == null) continue;
            if(managed.lifecycle() == Lifecycle.FAILED){
                if(failed.add(managed)) violation("exception", name, "the world failed: " + managed.status().error());
                continue;
            }
            WorldScheduler.Slot slot = managed.slot();
            if(slot == null) continue;
            WorldWatch watch = watches.computeIfAbsent(name, k -> new WorldWatch());
            if(watch.world != world){
                // a new match on the same id (a game over's reload): a new window
                watch.world = world;
                watch.samples.clear();
                watch.stalls = slot.stats().stalls();
                watch.longest = 0;
            }
            // a watchdog stall (one tick over 5x its budget) is counted, not a violation: under a JVM shared with 128
            // clients every world stalls in the same second (a GC or safepoint pause), which says nothing about a world.
            // "Over budget for long" is the tick share below; one tick of over a second is a violation on its own.
            long stalls = slot.stats().stalls();
            totalStalls.merge(name, Math.max(0, stalls - watch.stalls), Long::sum);
            watch.stalls = stalls;
            long longest = slot.stats().tickTime().max();
            if(longest > config.longTickMillis() * 1000L && longest > watch.longest){
                violation("ticking", name, "a tick took " + longest / 1000 + " ms");
            }
            watch.longest = Math.max(watch.longest, longest);
            watch.samples.addLast(new long[]{now, slot.stats().ticks()});
            long[] first = watch.samples.peekFirst();
            double seconds = (now - first[0]) / 1e9;
            if(seconds >= config.tickWindowSeconds()){
                long ran = slot.stats().ticks() - first[1];
                double expected = seconds * 60;
                boolean late = ran < expected * config.minTickShare();
                if(late && !watch.late){
                    violation("ticking", name, String.format("ran %d ticks in %.1f s, under %.0f%% of %.0f (%s)", ran, seconds, config.minTickShare() * 100, expected,
                        slot.stats().summary()));
                }
                watch.late = late;
                while(watch.samples.size() > 1 && (now - watch.samples.peekFirst()[0]) / 1e9 > config.tickWindowSeconds()) watch.samples.removeFirst();
            }
        }
    }

    private void checkControls(){
        long now = System.nanoTime();
        for(ControlClient control : controls){
            if(control.playingSinceNanos == 0) continue;
            if(control.closed != null){
                violation("control", control.world, control + " was disconnected: " + control.closed);
                control.closed = null;
                control.playingSinceNanos = 0;
                continue;
            }
            long quiet = (now - control.lastSnapshotNanos) / 1_000_000_000L, deaf = (now - control.lastPongNanos) / 1_000_000_000L;
            if(quiet > config.tickWindowSeconds()){
                violation("control", control.world, control + " got no entity snapshot for " + quiet + " s");
                control.lastSnapshotNanos = now;
            }
            if(deaf > config.tickWindowSeconds()){
                violation("control", control.world, control + " got no ping reply for " + deaf + " s");
                control.lastPongNanos = now;
            }
            String in = sessionWorlds.get(control.name);
            if(in != null && !in.equals(control.world)) violation("isolation", control.world, control + " is in " + in);
        }
    }

    private void checkLog(){
        String line;
        while((line = tap.escaped.poll()) != null) violation("exception", "-", line);
    }

    /** Units, players and buildings of every world, read on its thread. */
    private final Map<String, Map<Integer, Integer>> teamsByWorld = new HashMap<>();

    private void checkValidity(){
        for(HostedWorld world : server.host().worlds()){
            String name = world.id().name();
            Map<Integer, Integer> teams = teamsByWorld.computeIfAbsent(name, k -> new HashMap<>());
            List<String> problems;
            try{
                problems = world.inspect(w -> validity(w, teams)).get(Hang.SECONDS, TimeUnit.SECONDS);
            }catch(Exception e){
                continue;
            }
            for(String problem : problems) violation("validity", name, problem);
        }
    }

    static List<String> validity(World world, Map<Integer, Integer> teams){
        var problems = new ArrayList<String>();
        float margin = 250 + 64, maxX = world.tiles().width() * 8f + margin, maxY = world.tiles().height() * 8f + margin;
        var controlled = new HashMap<Integer, Integer>();
        for(Player player : Roster.of(world).players().values()){
            if(!Float.isFinite(player.x) || !Float.isFinite(player.y)) problems.add("player " + player.id() + " at " + player.x + "," + player.y);
            Unit unit = player.unit();
            if(unit == null || !unit.isValid()) continue;
            if(unit.team != player.team) problems.add("player " + player.id() + " of team " + player.team + " controls unit " + unit.id() + " of team " + unit.team);
            Integer other = controlled.put(unit.id(), player.id());
            if(other != null) problems.add("players " + other + " and " + player.id() + " both control unit " + unit.id());
        }
        var seen = new HashMap<Integer, Integer>();
        for(int i = 0, n = world.units().size(); i < n; i++){
            Unit unit = world.units().get(i);
            if(!Float.isFinite(unit.x) || !Float.isFinite(unit.y) || unit.x < -margin || unit.y < -margin || unit.x > maxX || unit.y > maxY){
                problems.add("unit " + unit.id() + " at " + unit.x + "," + unit.y);
            }
            if(Float.isNaN(unit.health) || Float.isNaN(unit.rotation)) problems.add("unit " + unit.id() + " health " + unit.health + " rotation " + unit.rotation);
            if(unit.stackAmount < 0) problems.add("unit " + unit.id() + " carries " + unit.stackAmount);
            Integer before = teams.get(unit.id());
            if(before != null && before != unit.team && controlled.containsKey(unit.id())){
                problems.add("unit " + unit.id() + " went from team " + before + " to " + unit.team + " under player " + controlled.get(unit.id()));
            }
            seen.put(unit.id(), unit.team);
        }
        teams.clear();
        teams.putAll(seen);
        int items = world.content().items();
        for(int i = 0, n = world.builds().size(); i < n; i++){
            Building build = world.builds().get(i);
            if(build.items == null) continue;
            for(int item = 0; item < items; item++){
                if(build.items.get(item) < 0){
                    problems.add("building " + build.id() + " at " + build.tileX() + "," + build.tileY() + " holds " + build.items.get(item) + " of item " + item);
                    break;
                }
            }
        }
        return problems;
    }

    private void heap(){
        System.gc();
        long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() >> 20;
        heap.add(Long.toString(used));
        heapMax = Math.max(heapMax, used);
        // the first sample after the warm-up is the baseline: the joins and the first replay segments are in it
        if(heapBaseline < 0){
            if(heap.size() >= 2) heapBaseline = used;
            return;
        }
        if(used > heapBaseline + config.heapGrowthMiB()) violation("memory", "-", "live heap " + used + " MiB, " + (used - heapBaseline) + " over the baseline");
    }

    private String worldSummary(){
        var out = new StringBuilder("worlds:\n");
        for(HostedWorld world : server.host().worlds()){
            ManagedWorld managed = world.managed();
            WorldScheduler.Slot slot = managed == null ? null : managed.slot();
            out.append(String.format("  %s (%s): %s%n", world.id().name(), world.map().name(), slot == null ? "no slot" : slot.stats().summary()));
        }
        out.append("  watchdog stalls (ticks over 5x budget, counted): ").append(totalStalls).append('\n');
        out.append(String.format("  intents: malformed %,d, ignored %,d%n", server.host().intents().malformed.sum(), server.host().intents().ignored.sum()));
        return out.toString();
    }

    /** Replays every recorded match from its bundle and compares hashes with the live ones. */
    private String audit(Path dir) throws IOException{
        var out = new StringBuilder("audit:\n");
        var store = new ReplayStore(dir);
        int matches = 0, hashes = 0;
        for(var entry : store.all().entrySet()){
            for(Bundle bundle : entry.getValue()){
                matches++;
                try{
                    Regenerator.Report report = new Regenerator(bundle).verify(line -> {});
                    hashes += report.hashesChecked();
                    out.append(String.format("  %s: %d segments, %d hashes, %s%n", bundle.match(), report.segments().size(), report.hashesChecked(),
                        report.ok() ? "identical" : "DIFFERS"));
                    if(!report.ok()) violation("replay", bundle.match(), String.join("; ", report.problems()));
                    if(report.hashesChecked() == 0) violation("replay", bundle.match(), "no hash was compared");
                }catch(IOException | RuntimeException e){
                    violation("replay", bundle.match(), "the bundle didn't replay: " + e);
                }
            }
        }
        if(matches == 0) violation("replay", "-", "no match was recorded");
        out.append(String.format("  %d matches, %d hashes compared%n", matches, hashes));
        return out.toString();
    }

    @Override
    public void close(){
        done = true;
        for(FuzzClient client : fuzzers) client.stop();
        for(ControlClient control : controls) control.stop();
        if(server != null) server.close();
    }

    /** {@code Hang}'s bound: how long a world may take to answer before it counts as stopped. */
    static final class Hang{
        static final long SECONDS = 180;
    }
}
