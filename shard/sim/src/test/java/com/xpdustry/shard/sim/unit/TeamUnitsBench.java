package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/**
 * The cost of the team unit recount ({@link TeamUnits#recount}, every tick) and of the part lookups on hot paths
 * ({@link TeamUnits#of}, {@link PlayerSpawns#of}), in W2.4's load test world: 50×50 stone, no units, and the same with 30
 * units of two teams. Writes {@code build/reports/team-units-bench.txt}. Run with
 * {@code -Pshard.tags=slow --tests '*TeamUnitsBench*'}.
 *
 * <p>On a boat VM (default type), 2026-10-29, before and after the recount cleared only the teams it counted and the
 * lookups went through {@code World.find}; each figure is the median of 7 runs of 200,000 calls, from one run of the
 * suite on each side:
 * <pre>
 *                        before     after
 * recount, no units      338 ns      2 ns
 * recount, 30 units      441 ns    209 ns
 * tick, no units       1,119 ns    707 ns
 * TeamUnits.of            12 ns      2 ns
 * PlayerSpawns.of         10 ns      6 ns
 * </pre>
 * The world hash is the same on both sides ({@code HashTest}).
 */
@Tag("slow")
class TeamUnitsBench{
    static final int WARMUP = 20_000, CALLS = 200_000, RUNS = 7;

    @Test
    void measure() throws IOException{
        World stone = stone(), crowded = stone();
        for(int i = 0; i < 30; i++){
            Unit unit = Unit.create(crowded, crowded.content().unit(i % 2 == 0 ? "dagger" : "flare"), i % 3 == 0 ? 2 : 1);
            unit.set(40f + i * 8f, 40f + i * 4f);
            unit.add();
        }
        var report = new StringBuilder();
        report.append(line("recount, no units", time(() -> TeamUnits.of(stone).recount(stone.units()))));
        report.append(line("recount, 30 units", time(() -> TeamUnits.of(crowded).recount(crowded.units()))));
        report.append(line("tick, no units", time(stone::tick)));
        report.append(line("TeamUnits.of", time(() -> TeamUnits.of(stone))));
        report.append(line("PlayerSpawns.of", time(() -> PlayerSpawns.of(stone))));
        String text = report.toString();
        System.out.print(text);
        Path out = Path.of("build", "reports", "team-units-bench.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, text, StandardCharsets.UTF_8);
    }

    static World stone(){
        Epoch vanilla = Epoch.vanilla();
        var world = new World(WorldConfig.of(vanilla, 50, 50, 1).withKinds(KindRegistry.builtIn().withPassthrough()));
        world.buildings().beginLoad();
        for(int pos = 0; pos < world.tiles().size(); pos++) world.tiles().floor(pos, vanilla.block("stone").id());
        world.buildings().endLoad(false);
        return world;
    }

    static String line(String what, double nanos){
        return String.format("%-20s %,8.0f ns%n", what, nanos);
    }

    /** The median over {@link #RUNS} runs of the mean time of one call. */
    static double time(Runnable call){
        for(int i = 0; i < WARMUP; i++) call.run();
        var runs = new double[RUNS];
        for(int run = 0; run < RUNS; run++){
            long start = System.nanoTime();
            for(int i = 0; i < CALLS; i++) call.run();
            runs[run] = (System.nanoTime() - start) / (double)CALLS;
        }
        Arrays.sort(runs);
        return runs[RUNS / 2];
    }
}
