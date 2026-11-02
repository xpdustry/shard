package com.xpdustry.shard.logic;

import com.xpdustry.shard.logic.HostOp.*;

import java.util.*;

/**
 * The logic throttle (ARCHITECTURE.md §4, note 28 §4.2). Each team's processors are charged weighted instructions:
 * one per instruction, except that a statement calling the world counts as its {@link Cost} weight. Off by default, which is
 * vanilla: every factor is exactly 1. When configured, {@link #endTick()} compares each team's smoothed use with its
 * budget and sets the team's efficiency factor {@code f}, which the next tick's processors apply as
 * {@code accumulator += edelta * f * ipt}: a slower processor, never a skipped instruction. Factors are computed only
 * from instruction counts, at the tick boundary, so they are deterministic and part of the replayable state; every
 * change is reported to the listener.
 */
public final class LogicBudget{
    /** Team ids are bytes. */
    static final int teams = 256;
    /** Factors are multiples of 1/256, so a factor is a small exact float and changes are discrete. */
    static final int steps = 256;

    /** What a statement that calls the world counts as, in instructions, by family. */
    public enum Cost{
        SENSOR(2), CONTROL(3), READ(2), WRITE(2), PRINTFLUSH(2), DRAWFLUSH(2), RADAR(25), UNIT_BIND(4),
        UNIT_CONTROL(8), UNIT_LOCATE(25), QUERY(25), WORLD(4), CLIENT(1);

        /** The default weight, from the prototype's per-call costs (note 28 §3) relative to {@code op} (≈ 3 ns). */
        public final int defaultWeight;

        Cost(int defaultWeight){
            this.defaultWeight = defaultWeight;
        }

        static Cost of(HostOp op){
            return switch(op){
                case Radar r -> RADAR;
                case UnitBind u -> UNIT_BIND;
                case UnitControl u -> UNIT_CONTROL;
                case UnitLocate u -> UNIT_LOCATE;
                case Query q -> QUERY;
                case Cutscene c -> CLIENT;
                case PlaySound s -> CLIENT;
                case PlayMusic m -> CLIENT;
                case Effect e -> CLIENT;
                case ClientData c -> CLIENT;
                case Sync s -> CLIENT;
                default -> WORLD;
            };
        }
    }

    /**
     * A throttle setting. {@code perTeam} is the weighted instructions each team may use per tick (0 turns the throttle
     * off). {@code smoothing} is the weight of the newest tick in the moving average; the factor only moves when the
     * target is more than {@code hysteresis} away, and never below {@code minFactor}. World processors are exempt
     * unless {@code throttleWorldProcessors}.
     */
    public record Config(long perTeam, double smoothing, double hysteresis, double minFactor, boolean throttleWorldProcessors,
                         Map<Cost, Integer> weights){
        public static final Config OFF = new Config(0, 0.2, 0.05, 0.05, false, Map.of());

        public Config{
            weights = Map.copyOf(weights);
        }

        public int weight(Cost cost){
            return weights.getOrDefault(cost, cost.defaultWeight);
        }
    }

    /** Hears every factor change, at the tick boundary that makes it. */
    public interface Listener{
        void changed(long tick, int team, float before, float after, double used);
    }

    private Config config = Config.OFF;
    private final int[] weights = new int[Cost.values().length];
    private Listener listener = (tick, team, before, after, used) -> {};

    private final long[] used = new long[teams];
    private final long[] executed = new long[teams];
    private final double[] average = new double[teams];
    private final float[] factor = new float[teams];

    LogicBudget(){
        Arrays.fill(factor, 1f);
        configure(Config.OFF);
    }

    public void configure(Config config){
        this.config = config;
        for(Cost cost : Cost.values()) weights[cost.ordinal()] = config.weight(cost);
        if(config.perTeam() <= 0){
            Arrays.fill(factor, 1f);
            Arrays.fill(average, 0);
        }
    }

    public Config config(){
        return config;
    }

    public void listener(Listener listener){
        this.listener = Objects.requireNonNull(listener);
    }

    /** The factor {@code p} runs at this tick. */
    float factor(Processor p){
        return p.privileged && !config.throttleWorldProcessors() ? 1f : factor[p.team & 0xff];
    }

    /** The team's current factor. */
    public float factor(int team){
        return factor[team & 0xff];
    }

    int weight(Cost cost){
        return weights[cost.ordinal()];
    }

    void record(Processor p, int instructions, long weighted){
        int team = p.team & 0xff;
        executed[team] += instructions;
        if(!p.privileged || config.throttleWorldProcessors()) used[team] += weighted;
    }

    /** Weighted instructions charged to {@code team} this tick so far. */
    public long used(int team){
        return used[team & 0xff];
    }

    /** Instructions {@code team}'s processors ran, in total. */
    public long executed(int team){
        return executed[team & 0xff];
    }

    /** The throttle's setting and its per-team state, for {@link LogicWorld#writeState}. The listener is not state. */
    void writeState(LogicState.Out out){
        out.l(config.perTeam());
        out.d(config.smoothing());
        out.d(config.hysteresis());
        out.d(config.minFactor());
        out.bool(config.throttleWorldProcessors());
        var costs = new TreeMap<String, Integer>();
        for(var entry : config.weights().entrySet()) costs.put(entry.getKey().name(), entry.getValue());
        out.i(costs.size());
        for(var entry : costs.entrySet()){
            out.str(entry.getKey());
            out.i(entry.getValue());
        }
        for(int team = 0; team < teams; team++){
            out.l(used[team]);
            out.l(executed[team]);
            out.d(average[team]);
            out.f(factor[team]);
        }
    }

    void readState(LogicState.In in){
        long perTeam = in.l();
        double smoothing = in.d(), hysteresis = in.d(), minFactor = in.d();
        boolean throttleWorld = in.bool();
        var costs = new EnumMap<Cost, Integer>(Cost.class);
        for(int i = in.i(); i > 0; i--) costs.put(Cost.valueOf(in.str()), in.i());
        configure(new Config(perTeam, smoothing, hysteresis, minFactor, throttleWorld, costs));
        for(int team = 0; team < teams; team++){
            used[team] = in.l();
            executed[team] = in.l();
            average[team] = in.d();
            factor[team] = in.f();
        }
    }

    void copyFrom(LogicBudget source){
        configure(source.config);
        System.arraycopy(source.used, 0, used, 0, teams);
        System.arraycopy(source.executed, 0, executed, 0, teams);
        System.arraycopy(source.average, 0, average, 0, teams);
        System.arraycopy(source.factor, 0, factor, 0, teams);
    }

    /** The tick boundary: update each team's average and factor from this tick's use, then start counting again. */
    void endTick(long tick){
        long budget = config.perTeam();
        if(budget <= 0){
            Arrays.fill(used, 0);
            return;
        }
        double alpha = config.smoothing();
        for(int team = 0; team < teams; team++){
            float f = factor[team];
            // what the team would use at full speed: use scales with f
            double demand = used[team] / (double)f;
            average[team] += alpha * (demand - average[team]);
            used[team] = 0;
            double target = average[team] <= budget ? 1 : Math.max(config.minFactor(), budget / average[team]);
            float next = (float)(Math.floor(target * steps) / steps);
            if(next <= 0) next = 1f / steps;
            if(next != f && (next == 1f || Math.abs(next - f) > config.hysteresis())){
                factor[team] = next;
                listener.changed(tick, team, f, next, average[team]);
            }
        }
    }
}
