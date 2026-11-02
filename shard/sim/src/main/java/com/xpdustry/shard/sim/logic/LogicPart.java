package com.xpdustry.shard.sim.logic;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.logic.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * A world's logic: its {@link LogicWorld} (the dynamic globals, the {@code op rand} generator, the link index, the
 * throttle) on a {@link WorldHost}, and {@code state.tick}, which its globals read with the wave state
 * ({@link WaveState}). Processors live in their buildings
 * ({@code sim.blocks.logic.LogicBlock}) and run in their turn of the building update; this part runs vanilla's
 * {@code logicVars.update()} in {@link Phase#LOGIC_VARS} ({@link LogicClock}) and the throttle's tick boundary at the
 * very end of the tick ({@link LogicTickEnd}).
 *
 * <p>Links are event-driven ({@link LinkIndex}): as a {@link TileWatcher}, the part hears of every building placed,
 * removed, replaced or destroyed on a tile, and of team changes, inside the call that makes them, and tells the
 * processors that link there to follow their links again in their next turn.
 *
 * <p>{@link Persistence#AUTHORITATIVE}, in its own section ({@link SectionPart}): the logic world's state
 * ({@link LogicWorld#writeState}) and the clock. Each processor's state is its building's. The link index is rebuilt by
 * the processors as they are restored or copied.
 *
 * <p>Owner: WP67.
 */
@Register
public final class LogicPart implements SectionPart, TileWatcher{
    /** The section format. */
    static final int VERSION = 1;
    /** {@code GlobalVars.rand} is seeded once per JVM from the clock; here from the world's seed, mixed with this. */
    private static final long RAND_SALT = 0x6c6f6769636c6f67L;

    /**
     * The most bytes clients' processor configs may inflate to in one world tick ({@link #admitConfig}): four of the
     * largest a stock client writes ({@link LogicConfig#MAX_INFLATED}), whatever the interaction rate limit allows.
     */
    public static final int CONFIG_BYTES_PER_TICK = 4 * LogicConfig.MAX_INFLATED;

    private WorldHost host;
    /** Made on first use, so worlds without processors (toy content without a logic table) never build one. */
    private LogicWorld logic;
    /** Vanilla's {@code state.tick}: game ticks, which {@code @tick} and {@code @time} read. */
    private double tick;
    @Derived("the world tick configBytes counts in: checkpoints are taken between ticks, and a new tick starts at zero")
    private long configTick = -1;
    @Derived("the config bytes admitted or refused in configTick; see configTick")
    private int configBytes;
    @Derived("the configs admitted in configTick; see configTick")
    private int configsAdmitted;

    @Override
    public void init(World world){
        host = new WorldHost(world);
    }

    /** The world's logic part, or null in a world built without it. */
    public static LogicPart of(World world){
        List<WorldPart> parts = world.parts();
        if(parts == null) return null;
        for(WorldPart part : parts){
            if(part instanceof LogicPart logic) return logic;
        }
        return null;
    }

    /** The world's logic part; processors can't exist without one. */
    public static LogicPart require(World world){
        LogicPart part = of(world);
        if(part == null) throw new IllegalStateException("the world has no LogicPart, which its processors need");
        return part;
    }

    /**
     * The world's logic: its globals, generator, links and throttle. The first call makes it, with its globals at this
     * tick's values.
     */
    public LogicWorld logic(){
        if(logic == null){
            World world = host.world;
            logic = new LogicWorld(host, Globals.of(world.content()));
            logic.rand(firstRand());
            refreshGlobals(world);
        }
        return logic;
    }

    /** Whether the world runs logic yet: a processor or a script made its {@link #logic()}. */
    public boolean running(){
        return logic != null;
    }

    /**
     * {@code GlobalVars.rand} as it is now, a copy, as the state snapshot sends it ({@code NetServer.java:1149}):
     * reading it makes no logic world, and a world that has none yet has the generator it will start with.
     */
    public com.xpdustry.shard.common.math.Rand rand(){
        return logic != null ? logic.rand().copy() : firstRand();
    }

    private com.xpdustry.shard.common.math.Rand firstRand(){
        return new com.xpdustry.shard.common.math.Rand(host.world.config().seed() ^ RAND_SALT);
    }

    public WorldHost host(){
        return host;
    }

    /**
     * Admits a client's processor config before anything applies it: null, or why it is refused whole. It must stay
     * within {@link LogicConfig}'s bounds and within what this tick has left of {@link #CONFIG_BYTES_PER_TICK}.
     * Checking spends the budget either way (a refused config all it could have read), so once it is spent a config
     * costs nothing more to refuse, however many a client sends.
     */
    public String admitConfig(byte[] data){
        long now = host.world.ticks();
        if(configTick != now){
            configTick = now;
            configBytes = 0;
            configsAdmitted = 0;
        }
        int limit = Math.min(LogicConfig.MAX_INFLATED, CONFIG_BYTES_PER_TICK - configBytes);
        int bytes = LogicConfig.measure(data, limit);
        configBytes += bytes < 0 ? limit : bytes;
        if(bytes >= 0) configsAdmitted++;
        return bytes < 0 ? "a processor config past Shard's bounds or this tick's budget" : null;
    }

    /** The world tick the config budget last counted in: {@link #configBytes} and {@link #configsAdmitted} are its. */
    public long configTick(){
        return configTick;
    }

    /** The bytes {@link #admitConfig} inflated, or could have, in {@link #configTick}: at most {@link #CONFIG_BYTES_PER_TICK}. */
    public int configBytes(){
        return configBytes;
    }

    /** The configs {@link #admitConfig} admitted in {@link #configTick}. */
    public int configsAdmitted(){
        return configsAdmitted;
    }

    /** {@code state.tick}. */
    public double tick(){
        return tick;
    }

    /** The tick a loaded map or save starts from ({@code SaveVersion.readMeta}'s {@code tick}). */
    public void clock(double tick){
        this.tick = tick;
    }

    /**
     * {@code Logic.update}'s {@code state.tick += Time.delta} and {@code logicVars.update()}: the globals every processor
     * reads this tick. {@code allowLogicData} follows the rules, as vanilla's assembler reads them.
     */
    void beginTick(World world){
        tick += 1;
        if(logic != null) refreshGlobals(world);
    }

    /**
     * {@code logicVars.update()} alone, as a {@code LogicFilter} runs it before its script. A world without a
     * {@link WaveState} is at {@code new GameState()}'s wave 1 with no countdown.
     */
    public void refreshGlobals(World world){
        LogicWorld logic = logic();
        logic.allowLogicData(world.buildings().rules().allowLogicData);
        WaveState waves = WaveState.of(world);
        logic.beginTick(tick, waves == null ? 1 : waves.wave, waves == null ? 0f : waves.wavetime, world.tiles().width(),
            world.tiles().height(), 1f);
    }

    void endTick(){
        if(logic != null) logic.endTick();
    }

    /**
     * {@code LExecutor.runLogicScript(code, maxInstructions, loop)}: runs {@code code} once as a privileged processor with
     * no building, on this world's globals and generator. {@code rules}, if not null, are the rules its statements read
     * and change instead of the world's (a loading map's own).
     */
    public void runScript(String code, int maxInstructions, boolean loop, Rules rules){
        if(code == null || code.isEmpty()) return;
        Rules wasRules = host.rules;
        host.rules = rules;
        try{
            Processor.script(logic(), code).runScript(maxInstructions, loop);
        }finally{
            host.rules = wasRules;
        }
    }

    @Override
    public void buildingChanged(int pos){
        if(logic == null) return;
        var tiles = host.world.tiles();
        logic.links.tileChanged(tiles.x(pos), tiles.y(pos));
    }

    @Override
    public WorldPart copy(CopyContext context){
        var out = new LogicPart();
        out.host = new WorldHost(context.world());
        out.host.rules = host.rules;
        out.logic = logic == null ? null : logic.copy(out.host);
        out.tick = tick;
        return out;
    }

    @Override
    public void hash(Hasher hasher){
        hasher.bool(logic != null);
        if(logic != null) logic.writeState(new HashOut(hasher));
        hasher.d(tick);
    }

    @Override
    public String section(){
        return "logic";
    }

    @Override
    public void write(World world, Writes out){
        out.i(VERSION);
        out.bool(logic != null);
        if(logic != null){
            var objects = new ArrayList<Object>();
            logic.writeState(new LogicState.Writer(out, objects));
            if(!objects.isEmpty()) throw new IllegalStateException("the logic world holds host objects: " + objects);
        }
        out.d(tick);
    }

    @Override
    public void read(World world, Reads in){
        int version = in.i();
        if(version != VERSION) throw new CheckpointException("logic section version " + version + ", expected " + VERSION);
        init(world);
        if(in.bool()){
            logic = new LogicWorld(host, Globals.of(world.content()));
            logic.readState(new LogicState.Reader(in, List.of()));
        }
        tick = in.d();
    }

    /** Feeds logic state to a world hash: host objects by entity id. */
    public static final class HashOut implements LogicState.Out{
        private final Hasher hasher;

        public HashOut(Hasher hasher){
            this.hasher = hasher;
        }

        @Override
        public void bool(boolean value){
            hasher.bool(value);
        }

        @Override
        public void i(int value){
            hasher.i(value);
        }

        @Override
        public void l(long value){
            hasher.l(value);
        }

        @Override
        public void f(float value){
            hasher.f(value);
        }

        @Override
        public void d(double value){
            hasher.d(value);
        }

        @Override
        public void str(String value){
            hasher.bool(value != null);
            if(value != null) hasher.str(value);
        }

        @Override
        public void host(Object value){
            if(value instanceof Entity entity){
                hasher.i(entity.id()).bool(entity.isAdded());
            }else{
                throw new IllegalStateException("a processor holds a host object that is not an entity: " + value.getClass().getName());
            }
        }
    }

    /** Clocks and the {@code logicVars.update()} of {@link Phase#LOGIC_VARS}. */
    @Register
    public static final class LogicClock implements PhaseHandler{
        @Override
        public Phase phase(){
            return Phase.LOGIC_VARS;
        }

        @Override
        public void run(World world){
            LogicPart part = of(world);
            if(part != null) part.beginTick(world);
        }
    }

    /** The throttle's tick boundary, after everything else in the tick. */
    @Register
    public static final class LogicTickEnd implements PhaseHandler{
        @Override
        public Phase phase(){
            return Phase.SYNC;
        }

        @Override
        public int order(){
            return Integer.MAX_VALUE;
        }

        @Override
        public void run(World world){
            LogicPart part = of(world);
            if(part != null) part.endTick();
        }
    }
}
