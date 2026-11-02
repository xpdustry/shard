// Reads a load gate's JFR recording (profile.sh) over the gate's window and prints where the server's CPU and
// allocation went: CPU by thread group, then for the tick threads a top-down tree from the scheduler's turn (the
// phases, the host's work around each tick, the sync), the self frames with their callers, allocation by the same
// tree, by class and by site; then each other thread group's self frames. Writes folded stacks beside it for flame
// graphs (<out>.cpu.folded, <out>.alloc.folded). CPU comes from jdk.CPUTimeSample (each sample one period of thread
// CPU, so samples count CPU time), or jdk.ExecutionSample if the recording has none.
//   java tools/bots/load/JfrProfile.java <recording.jfr> <from-s> <to-s> [out-prefix] [world-ticks] [period-ms]
// from-s and to-s are JVM uptime, the clock of perf's t= and the GC log: the gate's perf mark and its summary. With
// the window's world ticks (perf summary's tick n) and the sampling period, the systems table is in µs per world tick.

import jdk.jfr.consumer.*;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.*;

static final double TREE_MIN = 0.4, SELF_MIN = 0.3;
static final int TREE_DEPTH = 14, SELF_TOP = 40, OTHER_TOP = 12;

/** A call tree node: samples (or bytes) through it, and its children by frame. */
static final class Node{
    final String frame;
    long total, self;
    final Map<String, Node> children = new HashMap<>();

    Node(String frame){
        this.frame = frame;
    }

    Node child(String name){
        return children.computeIfAbsent(name, Node::new);
    }
}

/** Stacks of one thread group, weighted. */
static final class Profile{
    final Node root = new Node("(all)");
    final Map<String, Long> self = new HashMap<>(), folded = new HashMap<>();
    final Map<String, Map<String, Long>> callers = new HashMap<>();
    long total;

    /** frames: leaf first, as JFR stores them. */
    void add(List<String> frames, long weight){
        total += weight;
        root.total += weight;
        Node node = root;
        for(int i = frames.size() - 1; i >= 0; i--){
            node = node.child(frames.get(i));
            node.total += weight;
        }
        node.self += weight;
        String leaf = frames.isEmpty() ? "(no stack)" : frames.getFirst();
        self.merge(leaf, weight, Long::sum);
        String caller = frames.size() > 2 ? frames.get(1) + " < " + frames.get(2) : frames.size() > 1 ? frames.get(1) : "(root)";
        callers.computeIfAbsent(leaf, k -> new HashMap<>()).merge(caller, weight, Long::sum);
        var stack = new StringBuilder();
        for(int i = frames.size() - 1; i >= 0; i--){
            if(!stack.isEmpty()) stack.append(';');
            stack.append(frames.get(i));
        }
        folded.merge(stack.toString(), weight, Long::sum);
    }
}

static String group(RecordedThread thread){
    if(thread == null) return "(none)";
    String name = thread.getJavaName();
    if(name == null || name.isEmpty()) return thread.isVirtual() ? "(virtual)" : String.valueOf(thread.getOSName());
    return name.replaceAll("[0-9]+", "N");
}

static String frame(RecordedFrame frame){
    var method = frame.getMethod();
    String type = method.getType().getName().replace("com.xpdustry.shard.", "").replaceAll("\\$\\$Lambda[./0-9a-zA-Z]*", "\\$\\$Lambda")
        .replaceAll("\\$[0-9]+$", "\\$N");
    return type + "." + method.getName();
}

static List<String> frames(RecordedStackTrace stack){
    if(stack == null) return List.of();
    var out = new ArrayList<String>(stack.getFrames().size());
    for(RecordedFrame frame : stack.getFrames()) if(frame.isJavaFrame()) out.add(frame(frame));
    if(stack.isTruncated()) out.add("(truncated)");
    return out;
}

static String type(String frame){
    int dot = frame.lastIndexOf('.');
    return dot < 0 ? frame : frame.substring(0, dot);
}

static int find(List<String> rootFirst, String suffix, int from){
    for(int i = Math.max(0, from); i < rootFirst.size(); i++) if(rootFirst.get(i).endsWith(suffix)) return i;
    return -1;
}

/**
 * Which system a tick-thread sample belongs to: the phase handler under {@code World.tick} (with the unit component under
 * {@code Unit.update}, and the AI, builder or turret class under it; the building kind under {@code Building.update};
 * the intent under admission), the host's step around the tick (with the sync's step), the hash, the boundary.
 */
static String system(List<String> leafFirst){
    var frames = leafFirst.reversed();
    int tick = find(frames, "sim.world.World.tick", 0);
    if(tick >= 0 && tick + 1 < frames.size()){
        String handler = frames.get(tick + 1);
        if(handler.endsWith("World.admit")){
            for(int i = tick + 2; i < frames.size(); i++){
                if(frames.get(i).startsWith("server.") || frames.get(i).contains(".Members.")) return "admit/" + type(frames.get(i));
            }
            return "admit";
        }
        String key = type(handler);
        int unit = find(frames, "sim.unit.Unit.update", tick);
        if(unit >= 0 && unit + 1 < frames.size()){
            key += "/" + type(frames.get(unit + 1));
            for(int i = unit + 2; i < frames.size(); i++){
                String f = frames.get(i);
                if(f.startsWith("sim.ai.") || f.startsWith("sim.build.") || f.startsWith("sim.blocks.turret.") || f.startsWith("sim.path.")){
                    return key + "/" + type(f);
                }
            }
            return key;
        }
        int building = find(frames, "sim.building.Building.update", tick);
        if(building >= 0 && building + 1 < frames.size()) return key + "/" + type(frames.get(building + 1));
        return key;
    }
    int after = find(frames, "server.host.HostedWorld.afterTick", 0);
    if(after >= 0){
        if(after + 1 >= frames.size()) return "afterTick";
        String step = frames.get(after + 1);
        if(step.endsWith("WorldSync.endTick") && after + 2 < frames.size()) return "afterTick/WorldSync.endTick/" + frames.get(after + 2).replaceAll(".*\\.", "");
        return "afterTick/" + step.replaceAll("^.*\\.(\\w+\\.\\w+)$", "$1");
    }
    if(find(frames, "server.host.HostedWorld.beforeTick", 0) >= 0) return "beforeTick";
    if(find(frames, "sim.world.World.hash", 0) >= 0) return "World.hash";
    int boundary = find(frames, "ManagedWorld.boundary", 0);
    if(boundary >= 0) return boundary + 1 < frames.size() ? "boundary/" + frames.get(boundary + 1).replaceAll(".*\\.", "") : "boundary";
    if(find(frames, "ManagedWorld.tick", 0) >= 0) return "ManagedWorld.tick";
    return "scheduler";
}

/** The part of a tick thread's stack from the scheduler's turn up, or the whole stack if it has none. */
/** Lock waits of one kind at one site. */
static final class Waits{
    long count, nanos, max;
}

/**
 * Whether a park is a wait for a lock: the frame that parked acquires one. A tick thread also parks in
 * {@code ConditionObject.await} when nothing is due, which is idle time, not a wait.
 */
static boolean lockPark(List<String> leafFirst){
    for(String frame : leafFirst){
        if(frame.startsWith("jdk.internal.misc.Unsafe.") || frame.startsWith("java.util.concurrent.locks.LockSupport.")) continue;
        return frame.endsWith("AbstractQueuedSynchronizer.acquire");
    }
    return false;
}

/** The first frame outside the JDK: the code that waited. */
static String waitSite(List<String> leafFirst){
    for(String frame : leafFirst) if(!frame.startsWith("java.") && !frame.startsWith("jdk.") && !frame.startsWith("sun.")) return frame;
    return leafFirst.isEmpty() ? "(no stack)" : leafFirst.getFirst();
}

static List<String> fromTurn(List<String> frames){
    for(int i = frames.size() - 1; i >= 0; i--){
        if(frames.get(i).endsWith("WorldScheduler.turn")) return frames.subList(0, i + 1);
    }
    return frames;
}

static String pct(long part, long whole){
    return whole == 0 ? "-" : String.format(Locale.ROOT, "%5.1f%%", 100.0 * part / whole);
}

static String amount(long value, boolean bytes){
    if(!bytes) return String.format(Locale.ROOT, "%8d", value);
    return value >= 1L << 30 ? String.format(Locale.ROOT, "%6.2f GB", value / (double)(1L << 30))
        : String.format(Locale.ROOT, "%6.1f MB", value / (double)(1L << 20));
}

/** Prints the tree, folding single-child chains that keep almost all of their parent. */
static void tree(PrintStream out, Node node, long whole, int depth, String indent, boolean bytes){
    var kids = new ArrayList<>(node.children.values());
    kids.sort(Comparator.comparingLong((Node n) -> n.total).reversed());
    for(Node kid : kids){
        if(100.0 * kid.total / whole < TREE_MIN) break;
        var label = new StringBuilder(kid.frame);
        Node at = kid;
        while(at.children.size() == 1 && at.self == 0){
            Node only = at.children.values().iterator().next();
            label.append(" > ").append(only.frame);
            at = only;
        }
        // long chains: keep the ends
        String text = label.toString();
        String[] parts = text.split(" > ");
        if(parts.length > 4) text = parts[0] + " > … > " + parts[parts.length - 2] + " > " + parts[parts.length - 1];
        out.printf(Locale.ROOT, "%s %s%s %s%n", amount(at.total, bytes), pct(at.total, whole), indent, text);
        if(depth < TREE_DEPTH) tree(out, at, whole, depth + 1, indent + "  ", bytes);
    }
}

static void selfTable(PrintStream out, Profile profile, int top, boolean bytes, boolean withCallers){
    var entries = new ArrayList<>(profile.self.entrySet());
    entries.sort(Map.Entry.<String, Long>comparingByValue().reversed());
    int shown = 0;
    for(var entry : entries){
        if(shown++ >= top || 100.0 * entry.getValue() / profile.total < SELF_MIN) break;
        out.printf(Locale.ROOT, "%s %s  %s%n", amount(entry.getValue(), bytes), pct(entry.getValue(), profile.total), entry.getKey());
        if(!withCallers) continue;
        var callers = new ArrayList<>(profile.callers.get(entry.getKey()).entrySet());
        callers.sort(Map.Entry.<String, Long>comparingByValue().reversed());
        for(int i = 0; i < Math.min(3, callers.size()); i++){
            var caller = callers.get(i);
            if(caller.getValue() * 10 < entry.getValue()) break;
            out.printf(Locale.ROOT, "%18s    < %s (%s)%n", "", caller.getKey(), pct(caller.getValue(), entry.getValue()).trim());
        }
    }
}

static void writeFolded(Path file, Map<String, Long> folded) throws IOException{
    try(var writer = Files.newBufferedWriter(file)){
        for(var entry : folded.entrySet()) writer.write(entry.getKey() + " " + entry.getValue() + "\n");
    }
}

void main(String[] args) throws IOException{
    if(args.length < 3){
        System.err.println("usage: JfrProfile <recording.jfr> <from-s> <to-s> [out-prefix]");
        System.exit(2);
    }
    Path file = Path.of(args[0]);
    double from = Double.parseDouble(args[1]), to = Double.parseDouble(args[2]);
    String prefix = args.length > 3 ? args[3] : file.toString().replaceAll("\\.jfr$", "");
    long worldTicks = args.length > 4 ? Long.parseLong(args[4]) : 0;
    double periodMs = args.length > 5 ? Double.parseDouble(args[5]) : 0;

    // the JVM's start, so the window can be given in uptime
    Instant start = null;
    boolean cpuTime = false;
    try(var recording = new RecordingFile(file)){
        while(recording.hasMoreEvents()){
            RecordedEvent event = recording.readEvent();
            String name = event.getEventType().getName();
            if(name.equals("jdk.JVMInformation") && start == null) start = event.getInstant("jvmStartTime");
            if(name.equals("jdk.CPUTimeSample")) cpuTime = true;
        }
    }
    if(start == null) throw new IllegalStateException("no jdk.JVMInformation in " + file);
    Instant begin = start.plusMillis((long)(from * 1000)), end = start.plusMillis((long)(to * 1000));
    String cpuEvent = cpuTime ? "jdk.CPUTimeSample" : "jdk.ExecutionSample";

    var cpu = new TreeMap<String, Profile>();
    var alloc = new TreeMap<String, Profile>();
    var allocClass = new HashMap<String, Long>();
    var systems = new HashMap<String, Long>();
    var allocSystems = new HashMap<String, Long>();
    long cpuFailed = 0;
    // jdk.ThreadAllocationStatistics: each thread's allocated bytes, first and last in the window
    var allocFirst = new HashMap<Long, Long>();
    var allocLast = new HashMap<Long, Long>();
    var allocGroup = new HashMap<Long, String>();
    // jdk.CPUTimeSamplesLost by group; jdk.ThreadCPULoad (user + system, a share of the JVM's processors) by thread
    var lost = new TreeMap<String, Long>();
    var loadSum = new HashMap<Long, Double>();
    var loadCount = new HashMap<Long, Integer>();
    var loadGroup = new HashMap<Long, String>();
    double jvmLoad = 0, machineLoad = 0;
    int cpuLoads = 0;
    // tick threads' waits for monitors and locks, by lock class and site
    var waits = new HashMap<String, Waits>();
    try(var recording = new RecordingFile(file)){
        while(recording.hasMoreEvents()){
            RecordedEvent event = recording.readEvent();
            Instant at = event.getStartTime();
            if(at.isBefore(begin) || at.isAfter(end)) continue;
            String name = event.getEventType().getName();
            if(name.equals(cpuEvent)){
                if(cpuTime && event.getBoolean("failed")){
                    cpuFailed++;
                    continue;
                }
                RecordedThread thread = cpuTime ? event.getThread("eventThread") : event.getThread("sampledThread");
                String group = group(thread);
                List<String> frames = frames(event.getStackTrace());
                if(group.startsWith("tick-")){
                    frames = fromTurn(frames);
                    systems.merge(system(frames), 1L, Long::sum);
                }
                cpu.computeIfAbsent(group, g -> new Profile()).add(frames, 1);
            }else if(name.equals("jdk.ObjectAllocationSample")){
                String group = group(event.getThread("eventThread"));
                List<String> frames = frames(event.getStackTrace());
                if(group.startsWith("tick-")){
                    frames = fromTurn(frames);
                    allocClass.merge(event.getClass("objectClass").getName(), event.getLong("weight"), Long::sum);
                    allocSystems.merge(system(frames), event.getLong("weight"), Long::sum);
                }
                alloc.computeIfAbsent(group, g -> new Profile()).add(frames, event.getLong("weight"));
            }else if(name.equals("jdk.JavaMonitorEnter") || name.equals("jdk.ThreadPark")){
                if(!group(event.getThread("eventThread")).startsWith("tick-")) continue;
                List<String> frames = frames(event.getStackTrace());
                boolean monitor = name.equals("jdk.JavaMonitorEnter");
                if(!monitor && !lockPark(frames)) continue;
                RecordedClass lock = event.getClass(monitor ? "monitorClass" : "parkedClass");
                String key = (lock == null ? "?" : lock.getName().replace("com.xpdustry.shard.", "")) + " at " + waitSite(frames);
                Waits site = waits.computeIfAbsent(key, k -> new Waits());
                long nanos = event.getDuration().toNanos();
                site.count++;
                site.nanos += nanos;
                site.max = Math.max(site.max, nanos);
            }else if(name.equals("jdk.CPUTimeSamplesLost")){
                lost.merge(group(event.getThread("eventThread")), (long)event.getInt("lostSamples"), Long::sum);
            }else if(name.equals("jdk.ThreadCPULoad")){
                RecordedThread thread = event.getThread("eventThread");
                if(thread == null) continue;
                loadSum.merge(thread.getId(), (double)event.getFloat("user") + event.getFloat("system"), Double::sum);
                loadCount.merge(thread.getId(), 1, Integer::sum);
                loadGroup.put(thread.getId(), group(thread));
            }else if(name.equals("jdk.CPULoad")){
                jvmLoad += event.getFloat("jvmUser") + event.getFloat("jvmSystem");
                machineLoad += event.getFloat("machineTotal");
                cpuLoads++;
            }else if(name.equals("jdk.ThreadAllocationStatistics")){
                RecordedThread thread = event.getThread("thread");
                if(thread == null) continue;
                long id = thread.getId();
                allocFirst.putIfAbsent(id, event.getLong("allocated"));
                allocLast.put(id, event.getLong("allocated"));
                allocGroup.put(id, group(thread));
            }
        }
    }

    PrintStream out = System.out;
    double seconds = to - from;
    out.printf(Locale.ROOT, "JFR %s, uptime %.0f-%.0f s (%.0f s); CPU from %s%n", file.getFileName(), from, to, seconds, cpuEvent);

    out.println();
    out.println("== CPU by thread group" + (cpuTime ? " (samples are CPU periods; % of all samples; cores = share x the process CPU)" : ""));
    long allCpu = cpu.values().stream().mapToLong(p -> p.total).sum();
    var groups = new ArrayList<>(cpu.entrySet());
    groups.sort(Comparator.comparingLong((Map.Entry<String, Profile> e) -> e.getValue().total).reversed());
    for(var entry : groups){
        if(100.0 * entry.getValue().total / allCpu < 0.2) continue;
        out.printf(Locale.ROOT, "%8d %s  %s%n", entry.getValue().total, pct(entry.getValue().total, allCpu), entry.getKey());
    }
    if(cpuFailed > 0) out.printf(Locale.ROOT, "%8d failed samples (no stack), left out%n", cpuFailed);
    long lostAll = lost.values().stream().mapToLong(Long::longValue).sum();
    if(lostAll > 0){
        out.printf(Locale.ROOT, "%8d samples lost (jdk.CPUTimeSamplesLost), by the event's thread:", lostAll);
        lost.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(6)
            .forEach(e -> out.printf(Locale.ROOT, " %s %d", e.getKey(), e.getValue()));
        out.println();
    }
    if(cpuLoads > 0){
        out.printf(Locale.ROOT, "jdk.CPULoad: JVM %.1f%%, machine %.1f%% of all CPUs (mean of %d)%n", 100 * jvmLoad / cpuLoads, 100 * machineLoad / cpuLoads, cpuLoads);
    }
    var loads = new TreeMap<String, Double>();
    for(var entry : loadSum.entrySet()) loads.merge(loadGroup.get(entry.getKey()), entry.getValue() / loadCount.get(entry.getKey()), Double::sum);
    if(!loads.isEmpty()){
        out.print("jdk.ThreadCPULoad, mean user + system summed per group (a share of the JVM's processors):");
        loads.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed()).filter(e -> e.getValue() >= 0.002)
            .forEach(e -> out.printf(Locale.ROOT, " %s %.1f%%", e.getKey(), 100 * e.getValue()));
        out.println();
    }

    var tickCpu = new Profile();
    var tickAlloc = new Profile();
    BiConsumer<Map<String, Profile>, Profile> merge = (from_, into) -> {
        for(var entry : from_.entrySet()){
            if(!entry.getKey().startsWith("tick-")) continue;
            Profile p = entry.getValue();
            into.total += p.total;
            mergeNode(into.root, p.root);
            p.self.forEach((k, v) -> into.self.merge(k, v, Long::sum));
            p.folded.forEach((k, v) -> into.folded.merge(k, v, Long::sum));
            p.callers.forEach((k, v) -> v.forEach((c, w) -> into.callers.computeIfAbsent(k, x -> new HashMap<>()).merge(c, w, Long::sum)));
        }
    };
    merge.accept(cpu, tickCpu);
    merge.accept(alloc, tickAlloc);

    out.println();
    out.printf(Locale.ROOT, "== Tick threads, CPU, top-down from WorldScheduler.turn (%d samples, %s of all)%n", tickCpu.total, pct(tickCpu.total, allCpu).trim());
    tree(out, tickCpu.root, tickCpu.total, 0, "", false);
    out.println();
    long tickLost = lost.entrySet().stream().filter(e -> e.getKey().startsWith("tick-")).mapToLong(Map.Entry::getValue).sum();
    double scale = worldTicks > 0 && periodMs > 0 ? periodMs * 1000.0 * (tickCpu.total + tickLost) / Math.max(1, tickCpu.total) / worldTicks : 0;
    out.printf(Locale.ROOT, "== Tick threads by system%s%n", scale > 0
        ? String.format(Locale.ROOT, " (us per world tick: samples x %.1f ms, scaled up for %d lost samples, over %d world ticks)", periodMs, tickLost, worldTicks) : "");
    systems.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
        .filter(e -> e.getValue() * 1000 >= tickCpu.total)
        .forEach(e -> out.printf(Locale.ROOT, "%8d %s %s  %s%n", e.getValue(), pct(e.getValue(), tickCpu.total),
            scale > 0 ? String.format(Locale.ROOT, "%7.2f us", e.getValue() * scale) : "", e.getKey()));
    out.println();
    out.println("== Tick threads, CPU, self");
    selfTable(out, tickCpu, SELF_TOP, false, true);

    out.println();
    out.println("== Allocation by thread group (jdk.ThreadAllocationStatistics over the window)");
    var byGroup = new TreeMap<String, Long>();
    for(var entry : allocLast.entrySet()){
        long delta = entry.getValue() - allocFirst.getOrDefault(entry.getKey(), entry.getValue());
        byGroup.merge(allocGroup.get(entry.getKey()), delta, Long::sum);
    }
    long allAlloc = byGroup.values().stream().mapToLong(Long::longValue).sum();
    byGroup.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
        .filter(e -> e.getValue() * 500 >= allAlloc)
        .forEach(e -> out.printf(Locale.ROOT, "%s %s  %s  (%.1f MB/s)%n", amount(e.getValue(), true), pct(e.getValue(), allAlloc), e.getKey(),
            e.getValue() / (double)(1 << 20) / seconds));

    out.println();
    out.printf(Locale.ROOT, "== Tick threads, allocation (sampled weight %s), top-down%n", amount(tickAlloc.total, true).trim());
    tree(out, tickAlloc.root, tickAlloc.total, 0, "", true);
    out.println();
    out.println("== Tick threads, allocation by system" + (worldTicks > 0 ? " (bytes per world tick)" : ""));
    allocSystems.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
        .filter(e -> e.getValue() * 200 >= tickAlloc.total)
        .forEach(e -> out.printf(Locale.ROOT, "%s %s %s  %s%n", amount(e.getValue(), true), pct(e.getValue(), tickAlloc.total),
            worldTicks > 0 ? String.format(Locale.ROOT, "%8.0f B", e.getValue() / (double)worldTicks) : "", e.getKey()));
    out.println();
    out.println("== Tick threads, allocation by class");
    allocClass.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(25)
        .forEach(e -> out.printf(Locale.ROOT, "%s %s  %s%n", amount(e.getValue(), true), pct(e.getValue(), tickAlloc.total), e.getKey()));
    out.println();
    out.println("== Tick threads, allocation by site");
    selfTable(out, tickAlloc, 30, true, true);

    long waitCount = waits.values().stream().mapToLong(w -> w.count).sum(), waitNanos = waits.values().stream().mapToLong(w -> w.nanos).sum();
    long waitMax = waits.values().stream().mapToLong(w -> w.max).max().orElse(0);
    out.println();
    out.printf(Locale.ROOT, "== Tick threads, lock waits (monitor enters, and parks acquiring a lock, as long as the recording's thresholds): "
        + "%d waits, %.1f ms in all%s, the longest %.1f ms%n", waitCount, waitNanos / 1e6,
        worldTicks > 0 ? String.format(Locale.ROOT, ", %.3f us per world tick", waitNanos / 1e3 / worldTicks) : "", waitMax / 1e6);
    waits.entrySet().stream().sorted(Map.Entry.<String, Waits>comparingByValue(Comparator.comparingLong(w -> w.nanos)).reversed()).limit(OTHER_TOP)
        .forEach(e -> out.printf(Locale.ROOT, "  %7d %10.1f ms  max %8.1f ms  %s%n", e.getValue().count, e.getValue().nanos / 1e6, e.getValue().max / 1e6, e.getKey()));

    for(var entry : groups){
        if(entry.getKey().startsWith("tick-") || 100.0 * entry.getValue().total / allCpu < 1) continue;
        out.println();
        out.printf(Locale.ROOT, "== %s, CPU self (%d samples, %s of all)%n", entry.getKey(), entry.getValue().total, pct(entry.getValue().total, allCpu).trim());
        selfTable(out, entry.getValue(), OTHER_TOP, false, true);
    }
    for(var entry : alloc.entrySet()){
        if(entry.getKey().startsWith("tick-") || entry.getValue().total * 50 < tickAlloc.total) continue;
        out.println();
        out.printf(Locale.ROOT, "== %s, allocation by site (sampled %s)%n", entry.getKey(), amount(entry.getValue().total, true).trim());
        selfTable(out, entry.getValue(), OTHER_TOP, true, true);
    }

    writeFolded(Path.of(prefix + ".cpu.folded"), tickCpu.folded);
    writeFolded(Path.of(prefix + ".alloc.folded"), tickAlloc.folded);
    var all = new HashMap<String, Long>();
    for(var entry : cpu.entrySet()) entry.getValue().folded.forEach((k, v) -> all.merge(entry.getKey() + ";" + k, v, Long::sum));
    writeFolded(Path.of(prefix + ".all.folded"), all);
}

static void mergeNode(Node into, Node from){
    into.total += from.total;
    into.self += from.self;
    for(var entry : from.children.entrySet()) mergeNode(into.child(entry.getKey()), entry.getValue());
}
