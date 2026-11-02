package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.path.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.weather.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/**
 * The cost of the part lookups a tick makes ({@code SpatialIndex.of(world)}, {@code UnitCalls.rules(world)},
 * {@code world.part(PathService.class)}…): each tick's handlers make a few dozen. The lookups go round 100 worlds, as
 * 100 hosted worlds share three tick threads, so the parts aren't all in cache. Writes
 * {@code build/reports/part-lookup-bench.txt}. Run with {@code -Pshard.tags=slow --tests '*PartLookupBench*'}.
 *
 * <p>On a boat VM (large type), 2026-10-29, main at {@code 98ea5276} against W2.21's head; the median of 7 runs of
 * 200,000 calls, from two runs of the suite on each side, in the order main, W2.21, W2.21, main:
 * <pre>
 *                       main 98ea5276    W2.21
 * SpatialIndex.of        40.9  48.0 ns    8.2   6.1 ns
 * UnitCalls.rules        59.5  45.3 ns    7.1   7.6 ns
 * WaveState.of           51.7  61.0 ns    6.8   6.7 ns
 * Teams.of               36.1  49.4 ns   10.8   6.6 ns
 * Weathers.of            52.7  57.7 ns    7.6   5.3 ns
 * EntityGroups.of        22.5  26.8 ns    5.5   5.3 ns
 * UnitPhysics.of         33.7  40.3 ns   15.3  16.9 ns
 * DropZones.of           54.9  65.9 ns    7.3   7.3 ns
 * Players.of             38.2  47.9 ns    6.5   6.5 ns
 * PlayerSpawns.of         4.1   4.2 ns    9.3   9.6 ns
 * part(WorldRules)       29.5  36.7 ns    7.8   7.0 ns
 * part(PathService)      25.8  31.6 ns    8.2   7.9 ns
 * all of the above      449.6 514.8 ns  100.2  92.9 ns
 * </pre>
 * {@code PlayerSpawns.of} already went through {@code World.find} on main, near the front of a list of a few types;
 * it now pays the hash like the rest.
 */
@Tag("slow")
class PartLookupBench{
    static final int WORLDS = 100, WARMUP = 50_000, CALLS = 200_000, RUNS = 7;

    @Test
    void measure() throws IOException{
        var worlds = new World[WORLDS];
        for(int i = 0; i < WORLDS; i++) worlds[i] = TeamUnitsBench.stone();
        var lookups = new LinkedHashMap<String, Function<World, Object>>();
        lookups.put("SpatialIndex.of", SpatialIndex::of);
        lookups.put("UnitCalls.rules", UnitCalls::rules);
        lookups.put("WaveState.of", WaveState::of);
        lookups.put("Teams.of", Teams::of);
        lookups.put("Weathers.of", Weathers::of);
        lookups.put("EntityGroups.of", EntityGroups::of);
        lookups.put("UnitPhysics.of", UnitPhysics::of);
        lookups.put("DropZones.of", DropZones::of);
        lookups.put("Players.of", Players::of);
        lookups.put("PlayerSpawns.of", PlayerSpawns::of);
        lookups.put("part(WorldRules)", world -> world.part(WorldRules.class));
        lookups.put("part(PathService)", world -> world.part(PathService.class));
        var report = new StringBuilder();
        double total = 0;
        for(var entry : lookups.entrySet()){
            double nanos = time(worlds, entry.getValue());
            total += nanos;
            report.append(String.format("%-20s %6.1f ns%n", entry.getKey(), nanos));
        }
        report.append(String.format("%-20s %6.1f ns%n", "all of the above", total));
        String text = report.toString();
        System.out.print(text);
        Path out = Path.of("build", "reports", "part-lookup-bench.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, text, StandardCharsets.UTF_8);
    }

    /** The median over {@link #RUNS} runs of the mean time of one lookup, each in the next world. */
    static double time(World[] worlds, Function<World, Object> lookup){
        Object[] sink = new Object[1];
        for(int i = 0; i < WARMUP; i++) sink[0] = lookup.apply(worlds[i % WORLDS]);
        var runs = new double[RUNS];
        for(int run = 0; run < RUNS; run++){
            long start = System.nanoTime();
            for(int i = 0; i < CALLS; i++) sink[0] = lookup.apply(worlds[i % WORLDS]);
            runs[run] = (System.nanoTime() - start) / (double)CALLS;
        }
        Arrays.sort(runs);
        return runs[RUNS / 2];
    }
}
