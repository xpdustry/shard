package com.xpdustry.shard.testkit;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Runs one scenario on two engines and compares their projections at checkpoints: the reference (usually vanilla)
 * and the candidate (usually Shard).
 * <pre>{@code
 * Differential.of(scenario)
 *     .vanilla()                                   // the reference: OracleRunner.create()
 *     .candidate(ShardRunner.of(content))
 *     .tolerances(Tolerances.exact().abs("building[conveyor].fields.ys", 1e-5))
 *     .trace()                                     // every tick; stops at the first diverging one
 *     .run()
 *     .assertNoDiff();
 * }</pre>
 * Checkpoints: {@link #atEnd()} (the default), {@link #every(long)} ticks, or {@link #trace()}, every tick. The
 * comparison stops at the first failing checkpoint, and the report lists every leaf that differs there with its
 * value at the previous checkpoint. On failure both sides' projections at that checkpoint and the one before go to
 * {@code build/testkit/<scenario>/} ({@code shard.testkit.out}): {@code tick-<t>.<side>.txt} has one leaf per line,
 * so {@code diff -u tick-99.reference.txt tick-100.reference.txt} shows what one side did in that tick.
 *
 * <p>The two sides run in lockstep, one tick each, so nothing is stored. Two vanilla runners can't coexist (vanilla is
 * static state): then the reference runs first and its checkpoints are stored as deltas ({@link Trace}), and the
 * candidate is compared against the replay.
 */
public final class Differential{
    private final Scenario scenario;
    private Runner reference, candidate;
    private Tolerances tolerances = Tolerances.exact();
    private long every;
    private Path output = Path.of(System.getProperty("shard.testkit.out", "build/testkit"));

    private Differential(Scenario scenario){
        this.scenario = Objects.requireNonNull(scenario);
    }

    public static Differential of(Scenario scenario){
        return new Differential(scenario);
    }

    /** A scenario file's scenario, and its tolerances if it has some. */
    public static Differential of(ScenarioFile file){
        var differential = new Differential(file.scenario());
        if(file.tolerances() != null) differential.tolerances = file.tolerances();
        return differential;
    }

    public Differential reference(Runner reference){
        this.reference = Objects.requireNonNull(reference);
        return this;
    }

    public Differential candidate(Runner candidate){
        this.candidate = Objects.requireNonNull(candidate);
        return this;
    }

    /** Vanilla as the reference. */
    public Differential vanilla(){
        return reference(OracleRunner.create());
    }

    public Differential tolerances(Tolerances tolerances){
        this.tolerances = Objects.requireNonNull(tolerances);
        return this;
    }

    /** Compares every tick, from the setup on, and reports the first one where the sides diverge. */
    public Differential trace(){
        every = 1;
        return this;
    }

    /** Compares at tick 0, every {@code ticks} ticks and at the end. */
    public Differential every(long ticks){
        if(ticks <= 0) throw new IllegalArgumentException("every " + ticks + " ticks");
        every = ticks;
        return this;
    }

    /** Compares the last tick only (the default). */
    public Differential atEnd(){
        every = 0;
        return this;
    }

    /** Where failure dumps go; each scenario gets a subdirectory. */
    public Differential output(Path output){
        this.output = output;
        return this;
    }

    private boolean checkpoint(long tick){
        return tick == scenario.ticks() || every > 0 && tick % every == 0;
    }

    private String mode(){
        return every == 0 ? "at the end" : every == 1 ? "trace" : "every " + every + " ticks";
    }

    public DiffReport run(){
        if(reference == null || candidate == null) throw new IllegalStateException("a differential needs a reference and a candidate runner");
        return reference.global() && candidate.global() ? recorded() : lockstep();
    }

    private DiffReport lockstep(){
        var compare = new Compare(tolerances);
        try(Run a = Run.start(reference, scenario); Run b = Run.start(candidate, scenario)){
            Flat previousA = null, previousB = null;
            long lastAgreed = -1;
            int checkpoints = 0;
            for(long tick = 0; ; tick++){
                if(tick > 0){
                    a.step();
                    b.step();
                }
                if(checkpoint(tick)){
                    checkpoints++;
                    Projection pa = a.project(), pb = b.project();
                    Flat fa = pa.flat(tolerances.units()), fb = pb.flat(tolerances.units());
                    List<Difference> differences = compare.compare(fa, fb, previousA);
                    if(!differences.isEmpty()){
                        Path dumps = dump(tick, lastAgreed, fa, fb, previousA, previousB, pa, pb);
                        return report(compare, checkpoints, tick, lastAgreed, differences, dumps, a.nanos(), b.nanos());
                    }
                    previousA = fa;
                    previousB = fb;
                    lastAgreed = tick;
                }
                if(tick >= scenario.ticks()) break;
            }
            return report(compare, checkpoints, -1, lastAgreed, List.of(), null, a.nanos(), b.nanos());
        }
    }

    private DiffReport recorded(){
        var compare = new Compare(tolerances);
        var trace = new Trace(reference.name());
        long referenceNanos;
        try(Run a = Run.start(reference, scenario)){
            for(long tick = 0; ; tick++){
                if(tick > 0) a.step();
                if(checkpoint(tick)) trace.record(a.project().flat(tolerances.units()));
                if(tick >= scenario.ticks()) break;
            }
            referenceNanos = a.nanos();
        }
        Iterator<Flat> replay = trace.replay();
        try(Run b = Run.start(candidate, scenario)){
            Flat previousA = null, previousB = null;
            long lastAgreed = -1;
            int checkpoints = 0;
            for(long tick = 0; ; tick++){
                if(tick > 0) b.step();
                if(checkpoint(tick)){
                    checkpoints++;
                    Flat fa = replay.next();
                    Projection pb = b.project();
                    Flat fb = pb.flat(tolerances.units());
                    List<Difference> differences = compare.compare(fa, fb, previousA);
                    if(!differences.isEmpty()){
                        Path dumps = dump(tick, lastAgreed, fa, fb, previousA, previousB, null, pb);
                        return report(compare, checkpoints, tick, lastAgreed, differences, dumps, referenceNanos, b.nanos());
                    }
                    previousA = fa;
                    previousB = fb;
                    lastAgreed = tick;
                }
                if(tick >= scenario.ticks()) break;
            }
            return report(compare, checkpoints, -1, lastAgreed, List.of(), null, referenceNanos, b.nanos());
        }
    }

    private String candidateLabel(){
        return candidate.name().equals(reference.name()) ? candidate.name() + " (2nd run)" : candidate.name();
    }

    private DiffReport report(Compare compare, int checkpoints, long divergedAt, long lastAgreed, List<Difference> differences,
                              Path dumps, long referenceNanos, long candidateNanos){
        String candidateLabel = candidateLabel();
        var report = new DiffReport(scenario.name(), reference.name(), candidateLabel, mode(), scenario.ticks(), checkpoints,
            divergedAt, lastAgreed, differences, compare.notes.lines(reference.name(), candidateLabel), dumps,
            referenceNanos / 1e9, candidateNanos / 1e9);
        if(dumps != null) write(dumps.resolve("report.txt"), report.text());
        return report;
    }

    /** Writes both sides at the failing checkpoint and the one before; returns the directory, or null if it can't. */
    private Path dump(long tick, long lastAgreed, Flat a, Flat b, Flat previousA, Flat previousB, Projection pa, Projection pb){
        Path dir = output.resolve(scenario.name().replaceAll("[^A-Za-z0-9._-]", "_"));
        try{
            Files.createDirectories(dir);
            try(var old = Files.newDirectoryStream(dir, "{tick-*,report.txt}")){
                for(Path file : old) Files.delete(file);
            }
        }catch(IOException e){
            return null;
        }
        write(dir.resolve("tick-" + tick + ".reference.txt"), a.text());
        write(dir.resolve("tick-" + tick + ".candidate.txt"), b.text());
        if(previousA != null){
            write(dir.resolve("tick-" + lastAgreed + ".reference.txt"), previousA.text());
            write(dir.resolve("tick-" + lastAgreed + ".candidate.txt"), previousB.text());
        }
        if(pa != null) write(dir.resolve("tick-" + tick + ".reference.json"), pa.json() + "\n");
        if(pb != null) write(dir.resolve("tick-" + tick + ".candidate.json"), pb.json() + "\n");
        return dir.toAbsolutePath();
    }

    private static void write(Path file, String text){
        try{
            Files.writeString(file, text);
        }catch(IOException e){
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }
}
