package com.xpdustry.shard.tools.replay.shard;

import com.sun.net.httpserver.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.tools.replay.view.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Option (d) of note 37: a replay service. It plays a scripted {@link ShardMatch} once,
 * keeping only what a server would store for a replay: a checkpoint at every keyframe and the command log. It then
 * serves the view stream in pieces (docs/replay/FORMAT.md "Serving it in pieces"), and makes each segment on request by
 * restoring its checkpoint and re-simulating {@code keyframeEvery} ticks with the logged inputs, then caches it.
 *
 * <p>It also recorded the stream live, so it can check each regenerated segment against the live one: equal bytes
 * mean the restore and the re-simulation were exact. {@code --check} regenerates every segment, prints the timings
 * and exits; otherwise it serves on 127.0.0.1 ({@code --viewer DIR} serves a built viewer at {@code /}).
 *
 * <pre>--map veins --minutes 5 --port 8080 --viewer <viewer's dist directory> [--check]</pre>
 */
public final class ReplayService{
    final ShardMatch match;
    final ViewWriter.Options options = new ViewWriter.Options(3, 600, 6);
    final List<byte[]> checkpoints = new ArrayList<>();
    final List<CommandLog.Entry> log;
    final byte[] header, terrain;
    final List<byte[]> live;
    final ViewWriter.Continuation continuation;
    final long end;
    final Map<Integer, Segment> cache = new ConcurrentHashMap<>();

    /** A regenerated segment, how long it took, and whether it equals the live recording. */
    record Segment(byte[] bytes, double millis, boolean identical){
    }

    ReplayService(String map, long ticks) throws IOException{
        match = new ShardMatch(map);
        World world = match.world;
        var writer = new ViewWriter(new ShardSource(world, map), options);
        long checkpointNanos = 0;
        writer.sample();
        checkpoints.add(Checkpoint.capture(world).encode(Compression.FAST));
        long start = world.ticks();
        for(long t = 0; t < ticks; t++){
            match.inputs();
            world.tick();
            writer.sample();
            if((world.ticks() - start) % options.keyframeEvery() == 0){
                long began = System.nanoTime();
                checkpoints.add(Checkpoint.capture(world).encode(Compression.FAST));
                checkpointNanos += System.nanoTime() - began;
            }
        }
        writer.finish();
        end = world.ticks();
        header = writer.header();
        terrain = writer.terrain();
        live = writer.segments();
        continuation = writer.continuation();
        log = List.copyOf(world.log().entries());
        if(live.size() != checkpoints.size()) throw new IllegalStateException(live.size() + " segments but " + checkpoints.size() + " checkpoints");

        long checkpointBytes = checkpoints.stream().mapToLong(c -> c.length).sum();
        long liveBytes = live.stream().mapToLong(s -> s.length).sum();
        // the log's size: a crash bundle with it, minus the same bundle without it
        long logBytes = new CrashBundle(checkpoints.getFirst(), 0, log, List.of(), -1, "", new TreeMap<>()).encode().length
            - new CrashBundle(checkpoints.getFirst(), 0, List.of(), List.of(), -1, "", new TreeMap<>()).encode().length;
        double hours = (end - start) / 216000.0;
        System.out.printf("recorded %s on Shard: %d ticks, %d segments, %d inputs, %d units at the end%n", map, end - start, live.size(), log.size(), world.units().size());
        System.out.printf("  stored for replay: %d checkpoints, %,d B (avg %,d B, %.2f ms each to capture and encode), command log %,d B for %d inputs%n",
            checkpoints.size(), checkpointBytes, checkpointBytes / checkpoints.size(), checkpointNanos / 1e6 / Math.max(1, checkpoints.size() - 1), logBytes, log.size());
        System.out.printf("  = %.2f MB/h of checkpoints (one per %d ticks); the live view stream is %,d B = %.2f MB/h%n",
            checkpointBytes / 1048576.0 / hours, options.keyframeEvery(), liveBytes + header.length + terrain.length, (liveBytes + header.length + terrain.length) / 1048576.0 / hours);
    }

    /** Segment {@code index}, regenerated from its checkpoint and the log, or from the cache. */
    Segment segment(int index){
        return cache.computeIfAbsent(index, this::regenerate);
    }

    private Segment regenerate(int index){
        long began = System.nanoTime();
        World world = Checkpoint.decode(checkpoints.get(index)).restore(match.world.config());
        long from = world.ticks(), until = Math.min(from + options.keyframeEvery(), end);
        var writer = new ViewWriter(new ShardSource(world, match.map), options, continuation);
        writer.sample();
        int next = 0;
        while(next < log.size() && log.get(next).tick() < from) next++;
        while(world.ticks() < until){
            while(next < log.size() && log.get(next).tick() == world.ticks()){
                if(!world.inbox().offer(log.get(next).input())) throw new IllegalStateException("the inbox refused input " + next);
                next++;
            }
            world.tick();
            writer.sample();
        }
        byte[] bytes = writer.finishSegments().getFirst();
        double millis = (System.nanoTime() - began) / 1e6;
        return new Segment(bytes, millis, Arrays.equals(bytes, live.get(index)));
    }

    public static void main(String[] args) throws IOException{
        var options = new HashMap<String, String>();
        for(int i = 0; i < args.length; i++){
            String key = args[i].substring(2);
            options.put(key, i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "true");
        }
        var service = new ReplayService(options.getOrDefault("map", "veins"), Math.round(Double.parseDouble(options.getOrDefault("minutes", "5")) * 3600));
        if(options.containsKey("check")){
            int identical = 0;
            double total = 0, worst = 0;
            for(int i = 0; i < service.live.size(); i++){
                Segment segment = service.segment(i);
                identical += segment.identical() ? 1 : 0;
                total += segment.millis();
                worst = Math.max(worst, segment.millis());
                if(!segment.identical()) System.out.printf("  segment %d differs: %,d B regenerated vs %,d B live%n", i, segment.bytes().length, service.live.get(i).length);
            }
            System.out.printf("regenerated %d segments: %d identical to the live recording; %.1f ms on average, %.1f ms worst (restore + %d ticks + encode)%n",
                service.live.size(), identical, total / service.live.size(), worst, service.options.keyframeEvery());
            System.exit(identical == service.live.size() ? 0 : 1);
        }
        service.serve(Integer.parseInt(options.getOrDefault("port", "8080")), options.get("viewer"));
    }

    void serve(int port, String viewer) throws IOException{
        // loopback only: put a reverse proxy in front to share it
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        String id = match.map + "-shard";
        server.createContext("/api/replays", exchange -> {
            try(exchange){
                String path = exchange.getRequestURI().getPath().substring("/api/replays".length());
                if(path.isEmpty() || path.equals("/")){
                    send(exchange, 200, "application/json", ("[{\"id\":\"" + id + "\",\"map\":\"" + match.map + "\",\"base\":\"/api/replays/" + id + "\",\"source\":\"replay service\"}]").getBytes(StandardCharsets.UTF_8));
                    return;
                }
                String prefix = "/" + id + "/";
                if(!path.startsWith(prefix)){
                    send(exchange, 404, "text/plain", "no such replay".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                String rest = path.substring(prefix.length());
                if(rest.equals("header.json")) send(exchange, 200, "application/json", header);
                else if(rest.equals("terrain")) send(exchange, 200, "application/octet-stream", terrain);
                else if(rest.startsWith("segments/")){
                    int index = Integer.parseInt(rest.substring("segments/".length()));
                    if(index < 0 || index >= live.size()){
                        send(exchange, 404, "text/plain", "no such segment".getBytes(StandardCharsets.UTF_8));
                        return;
                    }
                    boolean cached = cache.containsKey(index);
                    Segment segment = segment(index);
                    exchange.getResponseHeaders().add("X-Regenerated-Ms", cached ? "0" : String.format("%.1f", segment.millis()));
                    exchange.getResponseHeaders().add("X-Identical", Boolean.toString(segment.identical()));
                    send(exchange, 200, "application/octet-stream", segment.bytes());
                }else send(exchange, 404, "text/plain", "unknown request".getBytes(StandardCharsets.UTF_8));
            }catch(RuntimeException e){
                e.printStackTrace();
            }
        });
        if(viewer != null){
            Path root = Path.of(viewer).toAbsolutePath().normalize();
            server.createContext("/", exchange -> {
                try(exchange){
                    String path = exchange.getRequestURI().getPath();
                    Path file = root.resolve(path.equals("/") ? "index.html" : path.substring(1)).normalize();
                    if(!file.startsWith(root) || !Files.isRegularFile(file)){
                        send(exchange, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
                        return;
                    }
                    send(exchange, 200, type(file.toString()), Files.readAllBytes(file));
                }
            });
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("serving " + id + " on http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/");
    }

    static String type(String name){
        if(name.endsWith(".html")) return "text/html; charset=utf-8";
        if(name.endsWith(".js")) return "text/javascript";
        if(name.endsWith(".css")) return "text/css";
        if(name.endsWith(".json")) return "application/json";
        if(name.endsWith(".png")) return "image/png";
        if(name.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }

    static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException{
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Expose-Headers", "X-Regenerated-Ms, X-Identical");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
