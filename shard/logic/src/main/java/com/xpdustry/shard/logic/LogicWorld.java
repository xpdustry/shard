package com.xpdustry.shard.logic;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;

import java.util.*;

/**
 * One world's logic state: its host, its copy of the dynamic globals, its {@code op rand} generator, the link index
 * and the throttle. Vanilla keeps the globals and {@code GlobalVars.rand} once per JVM; a Shard server runs many
 * worlds, so each has its own. Not thread-safe: it belongs to its world's thread.
 *
 * <p>Per tick, the world calls {@link #beginTick} where vanilla calls {@code logicVars.update()}, then each processor's
 * {@link Processor#update} in its building's turn, then {@link #endTick()}.
 */
public final class LogicWorld{
    public final LogicHost host;
    public final Globals table;
    /** The dynamic globals, by {@link Globals} id. */
    final double[] globals = new double[Globals.DYNAMIC];
    /** {@code GlobalVars.rand}, which vanilla seeds once per JVM with the time; set it for a reproducible world. */
    private Rand rand = new Rand(0);
    /** {@code Time.delta} for this tick, which {@code wait} adds to its timer. */
    float delta = 1f;
    long tick;
    /** {@code rules.allowLogicData}. */
    boolean allowLogicData;

    public final LinkIndex links = new LinkIndex();
    public final LogicBudget budget = new LogicBudget();

    // statistics
    long executed, weighted, parkedCalls;

    public LogicWorld(LogicHost host, Globals table){
        this.host = Objects.requireNonNull(host);
        this.table = Objects.requireNonNull(table);
        globals[Globals.SERVER] = 1;
    }

    /**
     * {@code GlobalVars.update}, at the start of a tick: {@code tick} is {@code state.tick} (a double that counts
     * {@code Time.delta}), {@code waveTime} is {@code state.wavetime} in ticks, and {@code delta} is {@code Time.delta}.
     */
    public void beginTick(double tick, int wave, float waveTime, int width, int height, float delta){
        this.tick = (long)tick;
        this.delta = delta;
        globals[Globals.TIME] = tick / 60.0 * 1000.0;
        globals[Globals.TICK] = tick;
        globals[Globals.SECOND] = tick / 60f;
        globals[Globals.MINUTE] = tick / 60f / 60f;
        globals[Globals.WAVE] = wave;
        globals[Globals.WAVE_TIME] = waveTime / 60f;
        globals[Globals.MAP_W] = width;
        globals[Globals.MAP_H] = height;
        // a dedicated server: @server is 1, @client 0, and there is no local player
        globals[Globals.SERVER] = 1;
        globals[Globals.CLIENT] = 0;
    }

    /** The tick boundary: the throttle updates its factors from this tick's use. */
    public void endTick(){
        budget.endTick(tick);
    }

    /** The current value of dynamic global {@code id}. */
    public double global(int id){
        return globals[id];
    }

    /** Sets a dynamic global, for a host that tracks {@code @clientTeam} and friends. */
    public void global(int id, double value){
        globals[id] = value;
    }

    public Rand rand(){
        return rand;
    }

    public void rand(Rand rand){
        this.rand = Objects.requireNonNull(rand);
    }

    public long tick(){
        return tick;
    }

    public float delta(){
        return delta;
    }

    /** {@code rules.allowLogicData}; it applies to code assembled after the change, as in vanilla. */
    public void allowLogicData(boolean allow){
        allowLogicData = allow;
    }

    /** Instructions run on this world, weighted instructions, and calls a parked processor skipped. */
    public long executed(){
        return executed;
    }

    public long weighted(){
        return weighted;
    }

    public long parkedCalls(){
        return parkedCalls;
    }

    /**
     * Writes this world's live logic state ({@link LogicState}): the {@code op rand} generator, the dynamic globals,
     * the tick, delta and {@code allowLogicData} as last set, the throttle, and the statistics. The link index is not
     * state: each processor's {@link Processor#readState} or {@link Processor#copy} watches its links again.
     */
    public void writeState(LogicState.Out out){
        out.l(rand.seed0);
        out.l(rand.seed1);
        for(double value : globals) out.d(value);
        out.f(delta);
        out.l(tick);
        out.bool(allowLogicData);
        budget.writeState(out);
        out.l(executed);
        out.l(weighted);
        out.l(parkedCalls);
    }

    /** Reads what {@link #writeState} wrote into this world, which has no processors yet. */
    public void readState(LogicState.In in){
        rand = new Rand(in.l(), in.l());
        for(int i = 0; i < globals.length; i++) globals[i] = in.d();
        delta = in.f();
        tick = in.l();
        allowLogicData = in.bool();
        budget.readState(in);
        executed = in.l();
        weighted = in.l();
        parkedCalls = in.l();
    }

    /** A copy of this world's live logic state on {@code host}, with no processors yet: copy each with {@link Processor#copy}. */
    public LogicWorld copy(LogicHost host){
        var out = new LogicWorld(host, table);
        out.rand = rand.copy();
        System.arraycopy(globals, 0, out.globals, 0, globals.length);
        out.delta = delta;
        out.tick = tick;
        out.allowLogicData = allowLogicData;
        out.budget.copyFrom(budget);
        out.executed = executed;
        out.weighted = weighted;
        out.parkedCalls = parkedCalls;
        return out;
    }

    Program assemble(String code, Assembler.Options options){
        return table.cache().get(code, options);
    }

    void executed(Processor p, int instructions, long weight){
        executed += instructions;
        weighted += weight;
        budget.record(p, instructions, weight);
    }

    /** {@code PrintI.toString}: what {@code print} and {@code format} write for an object. */
    public String toString(Object value){
        if(value == null) return "null";
        if(value instanceof String s) return s;
        // MappableContent prints its name, other content (bullets) "[content]"
        if(value instanceof ContentDef def) return def.type() == ContentType.BULLET ? "[content]" : def.name();
        if(value instanceof LogicTeam team) return team.name();
        String hosted = host.toString(value);
        if(hosted != null) return hosted;
        if(value instanceof Enum<?> e) return e.name();
        return "[object]";
    }
}
