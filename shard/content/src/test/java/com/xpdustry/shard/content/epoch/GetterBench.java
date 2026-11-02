package com.xpdustry.shard.content.epoch;

import com.xpdustry.shard.content.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/**
 * The cost of the typed getters hot paths call per unit per tick or per client snapshot: {@code buildSpeed}
 * ({@code UnitInput.canBuild}), {@code isEnemy} ({@code EnemyCount}), {@code entityClass} (entity snapshots),
 * {@code aimDst} and a weapon's {@code controllable} ({@code UnitInput.aim}, {@code controlWeapons}). They go round
 * every unit type. Writes {@code build/reports/getter-bench.txt}. Run with
 * {@code -Pshard.tags=slow --tests '*GetterBench*'}.
 *
 * <p>On a boat VM (large type), 2026-10-29, main at {@code 98ea5276} against W2.21's head; the median of 7 runs of
 * 1,000,000 calls, from two runs of the suite on each side, in the order main, W2.21, W2.21, main:
 * <pre>
 *                          main 98ea5276    W2.21
 * UnitDef.buildSpeed          9.4   8.1 ns    0.7  1.0 ns
 * UnitDef.isEnemy            10.0   8.2 ns    0.7  0.8 ns
 * UnitDef.entityClass        29.8  26.4 ns    0.7  0.8 ns
 * UnitDef.aimDst              7.7   7.0 ns    0.5  0.7 ns
 * WeaponDef.controllable     10.3   7.8 ns    0.5  0.5 ns
 * </pre>
 */
@Tag("slow")
class GetterBench{
    static final int WARMUP = 200_000, CALLS = 1_000_000, RUNS = 7;

    @Test
    void measure() throws IOException{
        Epoch vanilla = Epoch.vanilla();
        var units = new UnitDef[vanilla.units()];
        for(int i = 0; i < units.length; i++) units[i] = vanilla.unit(i);
        var list = new ArrayList<WeaponDef>();
        for(UnitDef unit : units) list.addAll(unit.weapons());
        WeaponDef[] weapons = list.toArray(WeaponDef[]::new);
        // each loop calls its getter at its own call site, as the hot paths do, and keeps primitives unboxed
        var report = new StringBuilder();
        report.append(line("UnitDef.buildSpeed", time(units.length, () -> {
            double sum = 0;
            for(UnitDef unit : units) sum += unit.buildSpeed();
            return sum;
        })));
        report.append(line("UnitDef.isEnemy", time(units.length, () -> {
            double sum = 0;
            for(UnitDef unit : units) if(unit.isEnemy()) sum++;
            return sum;
        })));
        report.append(line("UnitDef.entityClass", time(units.length, () -> {
            double sum = 0;
            for(UnitDef unit : units) if(unit.entityClass() != null) sum++;
            return sum;
        })));
        report.append(line("UnitDef.aimDst", time(units.length, () -> {
            double sum = 0;
            for(UnitDef unit : units) sum += unit.aimDst();
            return sum;
        })));
        report.append(line("WeaponDef.controllable", time(weapons.length, () -> {
            double sum = 0;
            for(WeaponDef weapon : weapons) if(weapon.controllable()) sum++;
            return sum;
        })));
        String text = report.toString();
        System.out.print(text);
        Path out = Path.of("build", "reports", "getter-bench.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, text, StandardCharsets.UTF_8);
    }

    static String line(String what, double nanos){
        return String.format("%-24s %6.1f ns%n", what, nanos);
    }

    static double sink;

    /** The median over {@link #RUNS} runs of the mean time of one call, from rounds over every def. */
    static double time(int defs, DoubleSupplier round){
        int rounds = CALLS / defs;
        for(int i = 0; i < WARMUP / defs; i++) sink += round.getAsDouble();
        var runs = new double[RUNS];
        for(int run = 0; run < RUNS; run++){
            long start = System.nanoTime();
            for(int i = 0; i < rounds; i++) sink += round.getAsDouble();
            runs[run] = (System.nanoTime() - start) / (double)(rounds * defs);
        }
        Arrays.sort(runs);
        return runs[RUNS / 2];
    }
}
