package com.xpdustry.shard.logic.oracle;

import com.xpdustry.shard.logic.*;
import mindustry.logic.*;
import mindustry.world.blocks.logic.LogicBlock.*;

import java.util.*;

/**
 * The prototype's benchmark (note 28 §3) on the real VM: {@code bench <engine> <scenario> <processors> <ticks>}, with
 * engine {@code vanilla} ({@code LogicBuild.updateTile}), {@code vanilla-exec} (its accumulator loop without the link
 * polling) or {@code interp} (Shard: {@link Processor#update}, with event-driven links and parking). Run one JVM per
 * line: {@code ./gradlew :logic:bench -Pbench.args="interp sorter 1000 600"}. Prints the median µs per tick of 7
 * rounds after a warm-up.
 */
public final class Bench{
    private Bench(){
    }

    public static void main(String[] args){
        String engine = args[0], name = args[1];
        int count = Integer.parseInt(args[2]), ticks = Integer.parseInt(args[3]);
        Scenarios.Scenario scenario = Scenarios.create(name, count);
        LogicBuild[] builds = scenario.processors().toArray(LogicBuild[]::new);
        VanillaHost host = new VanillaHost();
        LogicWorld world = VanillaLogic.logicWorld(host);
        Processor[] processors = new Processor[0];
        if(engine.equals("interp")){
            long start = System.nanoTime();
            processors = new Processor[builds.length];
            for(int i = 0; i < builds.length; i++) processors[i] = VanillaLogic.processor(world, host, builds[i], builds[i]);
            System.out.printf("assemble: %.1f us/processor, %d programs cached%n", (System.nanoTime() - start) / 1000.0 / count,
            world.table.cache().size());
        }

        int warmup = Math.max(2000, ticks / 2), rounds = 7;
        double[] perTick = new double[rounds];
        long instructions = 0, parked = 0;
        for(int r = -1; r < rounds; r++){
            int n = r < 0 ? warmup : ticks;
            long executed = world.executed(), parkedBefore = world.parkedCalls();
            long t0 = System.nanoTime();
            for(int t = 0; t < n; t++){
                VanillaLogic.tick();
                switch(engine){
                    case "vanilla" -> {
                        for(LogicBuild b : builds) b.updateTile();
                    }
                    case "vanilla-exec" -> {
                        for(LogicBuild b : builds) vanillaExecOnly(b);
                    }
                    case "interp" -> {
                        VanillaLogic.tick(world);
                        for(Processor p : processors) p.update(true, 1f);
                        world.endTick();
                    }
                    default -> throw new IllegalArgumentException(engine);
                }
            }
            long dt = System.nanoTime() - t0;
            if(r >= 0){
                perTick[r] = dt / (double)n;
                instructions = world.executed() - executed;
                parked = world.parkedCalls() - parkedBefore;
            }
        }
        Arrays.sort(perTick);
        double median = perTick[rounds / 2];
        String detail = processors.length == 0 ? "" : String.format(Locale.ROOT, " %.1f ns/instr, %d instr/tick, %d parked/tick",
        median / Math.max(1.0, (double)instructions / ticks), instructions / ticks, parked / ticks);
        System.out.printf(Locale.ROOT, "RESULT engine=%s scenario=%s procs=%d tick_us_median=%.1f tick_us_min=%.1f%s%n", engine, name,
        count, median / 1000, perTick[0] / 1000, detail);
        System.exit(0);
    }

    /** {@code LogicBuild.updateTile}'s accumulator loop without the per-tick link polling. */
    static void vanillaExecOnly(LogicBuild b){
        LExecutor ex = b.executor;
        ex.team = b.team;
        if(b.accumulator > 5 * b.ipt) b.accumulator = 5 * b.ipt;
        while(b.accumulator >= 1f){
            ex.runOnce();
            if(ex.yield){
                ex.yield = false;
                break;
            }
            b.accumulator--;
        }
        b.accumulator += b.edelta() * b.ipt;
    }
}
