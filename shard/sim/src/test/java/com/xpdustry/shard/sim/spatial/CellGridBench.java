package com.xpdustry.shard.sim.spatial;

import com.xpdustry.shard.common.math.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/**
 * The cost of placing a unit grid's members ({@link CellGrid#build}), which the team grids and the all-units grid do
 * every tick ({@code TeamStats}, {@code EntityPhysics}), and of a 3×3-cell query's row ranges, on a 300×300-tile map
 * (91×91 cells, the size of the default maps), for a few units around a core and for a crowd. Writes
 * {@code build/reports/cell-grid-bench.txt}. Run with {@code -Pshard.tags=slow --tests '*CellGridBench*'}.
 *
 * <p>On a boat VM (default type), 2026-10-29, main at {@code c6622a4d} against W2.21's head; the median of 7 runs of
 * 20,000 builds or 1,000,000 queries, after a pass over every case to warm up, from two runs of the suite on each side,
 * in the order main, W2.21, W2.21, main:
 * <pre>
 *               main c6622a4d                         W2.21
 *    0 units: build 1,813 1,764 ns  query 2.5 2.4 ns    build     2     2 ns  query 2.7 2.9 ns
 *   10 units: build 1,906 1,776 ns  query 2.5 2.4 ns    build    59    63 ns  query 3.9 4.0 ns
 *   30 units: build 2,048 1,803 ns  query 2.6 2.4 ns    build   132   142 ns  query 3.8 4.0 ns
 *  200 units: build 2,184 2,032 ns  query 2.4 2.4 ns    build   603   607 ns  query 3.8 3.8 ns
 * 2000 units: build 5,151 4,659 ns  query 2.5 2.5 ns    build 4,661 4,474 ns  query 3.8 3.8 ns
 * </pre>
 */
@Tag("slow")
class CellGridBench{
    static final int TILES = 300, CALLS = 20_000, QUERIES = 1_000_000, RUNS = 7;
    static int sink;

    @Test
    void measure() throws IOException{
        var report = new StringBuilder();
        // the first pass only warms up, so every case is measured compiled
        for(int pass = 0; pass < 2; pass++){
            for(int units : new int[]{0, 10, 30, 200, 2000}){
                var grid = new CellGrid(TILES, TILES);
                var rand = new Rand(units);
                float[] xs = new float[units], ys = new float[units];
                for(int i = 0; i < units; i++){
                    // around a core at the map's centre, as the players' units are
                    xs[i] = TILES * 4f + rand.range(30f * 8f);
                    ys[i] = TILES * 4f + rand.range(30f * 8f);
                }
                double build = build(grid, xs, ys, units);
                grid.build(xs, ys, units);
                double query = query(grid, grid.cellX(TILES * 4f), grid.cellY(TILES * 4f));
                if(pass == 1) report.append(String.format("%5d units: build %,9.0f ns   3x3 query %,6.1f ns%n", units, build, query));
            }
        }
        String text = report.toString();
        System.out.print(text);
        Path out = Path.of("build", "reports", "cell-grid-bench.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, text, StandardCharsets.UTF_8);
    }

    /** The median over {@link #RUNS} runs of the mean time of one build. */
    static double build(CellGrid grid, float[] xs, float[] ys, int units){
        var runs = new double[RUNS];
        for(int run = 0; run < RUNS; run++){
            long start = System.nanoTime();
            for(int i = 0; i < CALLS; i++) grid.build(xs, ys, units);
            runs[run] = (System.nanoTime() - start) / (double)CALLS;
        }
        return median(runs);
    }

    /** The median over {@link #RUNS} runs of the mean time of the row ranges of the 3×3 cells around ({@code cx}, {@code cy}). */
    static double query(CellGrid grid, int cx, int cy){
        var runs = new double[RUNS];
        for(int run = 0; run < RUNS; run++){
            long start = System.nanoTime();
            for(int i = 0; i < QUERIES; i++){
                for(int row = cy - 1; row <= cy + 1; row++) sink += grid.to(row, cx + 1) - grid.from(row, cx - 1);
            }
            runs[run] = (System.nanoTime() - start) / (double)QUERIES;
        }
        return median(runs);
    }

    static double median(double[] runs){
        Arrays.sort(runs);
        return runs[RUNS / 2];
    }
}
