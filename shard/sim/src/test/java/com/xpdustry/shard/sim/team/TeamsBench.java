package com.xpdustry.shard.sim.team;

import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/**
 * The cost of the team updates every tick makes: the cores' part of {@code updateTeamStats} ({@link Teams#updateStats},
 * {@code TeamCoreStats}) and {@code getActive} ({@code CoreFill}). They go round 100 worlds, as 100 hosted worlds share
 * three tick threads. Writes {@code build/reports/teams-bench.txt}. Run with
 * {@code -Pshard.tags=slow --tests '*TeamsBench*'}.
 *
 * <p>On a boat VM (large type), 2026-10-29, main at {@code 98ea5276} against W2.21's head; the median of 7 runs of
 * 200,000 calls, from two runs of the suite on each side, in the order main, W2.21, W2.21, main:
 * <pre>
 *                       main 98ea5276    W2.21
 * Teams.updateStats     349.3 386.2 ns   15.5  27.6 ns
 * Teams.getActive        73.7 105.0 ns   55.8  51.1 ns
 * </pre>
 * {@code getActive} still copies; {@code CoreFill}, its caller each tick, reads {@code activeView} instead.
 */
@Tag("slow")
class TeamsBench{
    static final int WORLDS = 100, WARMUP = 20_000, CALLS = 200_000, RUNS = 7;

    @Test
    void measure() throws IOException{
        var worlds = new World[WORLDS];
        for(int i = 0; i < WORLDS; i++){
            Epoch vanilla = Epoch.vanilla();
            var world = new World(WorldConfig.of(vanilla, 50, 50, i).withKinds(KindRegistry.builtIn().withPassthrough()));
            world.buildings().beginLoad();
            for(int pos = 0; pos < world.tiles().size(); pos++) world.tiles().floor(pos, vanilla.block("stone").id());
            world.buildings().endLoad(false);
            worlds[i] = world;
        }
        var report = new StringBuilder();
        report.append(line("Teams.updateStats", time(worlds, world -> Teams.of(world).updateStats())));
        report.append(line("Teams.getActive", time(worlds, world -> Teams.of(world).getActive(world))));
        String text = report.toString();
        System.out.print(text);
        Path out = Path.of("build", "reports", "teams-bench.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, text, StandardCharsets.UTF_8);
    }

    static String line(String what, double nanos){
        return String.format("%-20s %,8.1f ns%n", what, nanos);
    }

    /** The median over {@link #RUNS} runs of the mean time of one call, each in the next world. */
    static double time(World[] worlds, Consumer<World> call){
        for(int i = 0; i < WARMUP; i++) call.accept(worlds[i % WORLDS]);
        var runs = new double[RUNS];
        for(int run = 0; run < RUNS; run++){
            long start = System.nanoTime();
            for(int i = 0; i < CALLS; i++) call.accept(worlds[i % WORLDS]);
            runs[run] = (System.nanoTime() - start) / (double)CALLS;
        }
        Arrays.sort(runs);
        return runs[RUNS / 2];
    }
}
