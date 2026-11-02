package com.xpdustry.shard.compat.v160.command;

import arc.struct.*;
import com.xpdustry.shard.common.geom.Point2;
import com.xpdustry.shard.oracle.Vanilla;
import com.xpdustry.shard.sim.ai.team.*;
import com.xpdustry.shard.sim.blocks.storage.*;
import com.xpdustry.shard.sim.build.ConstructBuild;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.BlockPlan;
import com.xpdustry.shard.sim.unit.BuildPlan;
import mindustry.ai.*;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.world.blocks.ConstructBlock;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A team-AI fixture (WP82's gates): a {@link CommandRun} with vanilla's {@code RtsAI} and {@code BaseBuilderAI} on for
 * the teams the fixture names, compared after every frame beyond what the command run compares:
 * <ul>
 *   <li>each team's AIs: whether it has them, their timers, the RTS AI's damaged buildings, the base builder's walk
 *   (its tile, count, flags, the walk in progress and the last path);</li>
 *   <li>each team's rebuild plans in order, with their configs, and its cores' items;</li>
 *   <li>every unit's build plans (position, block, progress) and every construct site's block and progress.</li>
 * </ul>
 * A pinned run ({@link CommandRun}'s pins) adds two of its own, so its exact claims hold under them: the control
 * pathfinder's recompute window from Shard's clock ({@link #pinRecomputeClock}), and vanilla's {@code RtsAI.damagedSet},
 * an {@code ObjectSet} of buildings, which iterate in identity-hash order, different in each JVM, becomes an
 * {@code OrderedSet} (first-hit order, Shard's) as soon as the AI exists. An {@link #unpinned} run keeps vanilla's
 * {@code ObjectSet} as it is (review 45).
 */
final class TeamAiRun implements AutoCloseable{
    static final int SHARDED = 1, CRUX = 2;
    private static final Field RTS_TIMER, DAMAGED, BUILD_TIMER, PATH, CALC_PATH, CALC_TILE, CALCULATING, STARTED, CALC_COUNT, TOTAL_CALCS,
        FOUND_PATH, INTERVAL_TIMES, UNIT_REQUESTS, LAST_RECOMPUTE;

    static{
        try{
            RTS_TIMER = open(RtsAI.class.getDeclaredField("timer"));
            DAMAGED = open(RtsAI.class.getDeclaredField("damagedSet"));
            BUILD_TIMER = open(BaseBuilderAI.class.getDeclaredField("timer"));
            PATH = open(BaseBuilderAI.class.getDeclaredField("path"));
            CALC_PATH = open(BaseBuilderAI.class.getDeclaredField("calcPath"));
            CALC_TILE = open(BaseBuilderAI.class.getDeclaredField("calcTile"));
            CALCULATING = open(BaseBuilderAI.class.getDeclaredField("calculating"));
            STARTED = open(BaseBuilderAI.class.getDeclaredField("startedCalculating"));
            CALC_COUNT = open(BaseBuilderAI.class.getDeclaredField("calcCount"));
            TOTAL_CALCS = open(BaseBuilderAI.class.getDeclaredField("totalCalcs"));
            FOUND_PATH = open(BaseBuilderAI.class.getDeclaredField("foundPath"));
            INTERVAL_TIMES = open(arc.util.Interval.class.getDeclaredField("times"));
            UNIT_REQUESTS = open(ControlPathfinder.class.getDeclaredField("unitRequests"));
            LAST_RECOMPUTE = open(Class.forName("mindustry.ai.ControlPathfinder$PathRequest").getDeclaredField("lastRecomputeTime"));
        }catch(ReflectiveOperationException e){
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Field open(Field field){
        field.setAccessible(true);
        return field;
    }

    final CommandRun run;
    /** The teams whose AI state is compared. */
    final int[] teams = {SHARDED, CRUX};
    /** Extra reading after each comparison: the outcome counters a fixture asserts on. */
    Consumer<String> after = at -> {};
    int maxPlans, mostSquadOrders;

    TeamAiRun(int width, int height, BiFunction<Integer, Integer, CommandRun.Cell> layout){
        this(new CommandRun(width, height, layout));
    }

    private TeamAiRun(CommandRun run){
        this.run = run;
        run.stateFirst = true;
        run.shardFirst = true;
        if(run.pinned) run.beforeFrame = this::pinRecomputeClock;
        // buildings die here: their buildDestroyed Calls are compared too
        var events = new ArrayList<Class<? extends com.xpdustry.shard.sim.world.WorldEvent>>(CommandRun.EVENTS);
        events.add(com.xpdustry.shard.sim.building.BuildingEvent.Destroyed.class);
        run.events = List.copyOf(events);
        run.also = at -> {
            if(run.pinned){
                pinDamagedSets();
                same(at);
            }
            after.accept(at);
        };
    }

    /**
     * As vanilla and Shard run unmodified ({@link CommandRun#unpinned}): nothing is compared frame by frame, and vanilla's
     * {@code damagedSet} stays an {@code ObjectSet}.
     */
    static TeamAiRun unpinned(int width, int height, BiFunction<Integer, Integer, CommandRun.Cell> layout){
        return new TeamAiRun(CommandRun.unpinned(width, height, layout));
    }

    @Override
    public void close(){
        run.close();
    }

    /** Both sides' rules for {@code team}. Call before units are made: a type's controller reads {@code rtsAi}. */
    TeamAiRun teamRules(int team, Consumer<mindustry.game.Rules.TeamRule> vanilla, Consumer<TeamRule> ours){
        vanilla.accept(state.rules.teams.get(Team.get(team)));
        ours.accept(shardRules().teams.get(team));
        return this;
    }

    /** Both sides' rules. */
    TeamAiRun rules(Consumer<mindustry.game.Rules> vanilla, Consumer<com.xpdustry.shard.sim.rules.Rules> ours){
        vanilla.accept(state.rules);
        ours.accept(shardRules());
        return this;
    }

    /**
     * A pin for W2.17's {@code WaveFields} deviation: Shard preloads the wave team's ground field under the game's rules
     * (waves on, so crux needs it), where vanilla decided at the map load, under the rules before the fixture set them.
     * Vanilla gets the field now, complete before the first frame as Shard's first flush builds it.
     */
    TeamAiRun preloadWaveField(){
        pathfinder.getField(state.rules.waveTeam, mindustry.ai.Pathfinder.costGround, mindustry.ai.Pathfinder.fieldCore);
        com.xpdustry.shard.oracle.VanillaPathfinder.settle();
        return this;
    }

    /** {@code amount} of {@code item} handed to the building at (x, y), one by one ({@code handleItem}): a turret's ammo. */
    void ammo(int x, int y, String item, int amount){
        for(int i = 0; i < amount; i++){
            world.build(x, y).handleItem(null, content.item(item));
            run.shard.buildings().at(x, y).handleItem(null, CommandRun.EPOCH.item(item).id());
        }
    }

    com.xpdustry.shard.sim.rules.Rules shardRules(){
        return run.shard.part(WorldRules.class).rules;
    }

    TeamAis ais(){
        return TeamAis.of(run.shard);
    }

    /** Each vanilla path request's last recompute, by identity: {the value last written, the frame it was set in}. */
    private final IdentityHashMap<Object, long[]> recomputes = new IdentityHashMap<>();

    /**
     * A pin for the control pathfinder's recompute window (ControlPathfinder.java:1199): vanilla reuses a request whose
     * destination moved a little while {@code Time.timeSinceMillis(lastRecomputeTime) < 1000}, by the wall clock, and
     * Shard while fewer than 60 ticks passed. Before each pinned frame every request's time is set from Shard's clock:
     * {@link Vanilla#NOT_DUE} while fewer than 60 frames passed since vanilla set it (in the window whatever the frame's wall
     * time), 0 after. A time a frame's length in the past instead raced: a vanilla frame slower than the margin left
     * moved a request out of the window that Shard kept in it.
     */
    private void pinRecomputeClock(){
        try{
            var requests = (ObjectMap<?, ?>)UNIT_REQUESTS.get(controlPath);
            for(var entry : requests){
                Object request = entry.value;
                long value = LAST_RECOMPUTE.getLong(request);
                long[] seen = recomputes.get(request);
                // a new value was set during the last frame (Time.millis(), never one of the pinned two)
                if(seen == null || seen[0] != value) recomputes.put(request, seen = new long[]{0L, run.frame - 1});
                seen[0] = run.frame - seen[1] < 60 ? Vanilla.NOT_DUE : 0L;
                LAST_RECOMPUTE.setLong(request, seen[0]);
            }
        }catch(IllegalAccessException e){
            throw new AssertionError(e);
        }
    }

    /** {@code damagedSet} as an {@code OrderedSet}, keeping what it holds. */
    @SuppressWarnings("unchecked")
    private void pinDamagedSets(){
        for(int team : teams){
            RtsAI ai = Team.get(team).data().rtsAi;
            if(ai == null) continue;
            try{
                var set = (ObjectSet<Building>)DAMAGED.get(ai);
                if(set instanceof OrderedSet<?>) continue;
                var ordered = new OrderedSet<Building>();
                ordered.addAll(set);
                DAMAGED.set(ai, ordered);
            }catch(IllegalAccessException e){
                throw new AssertionError(e);
            }
        }
    }

    // ---------------------------------------------------------------- comparing

    void same(String at){
        for(int team : teams){
            CommandRun.sameLines(vanilla(team), ours(team), "team " + team + " AI state " + at);
        }
        CommandRun.sameLines(vanillaBuilders(), shardBuilders(), "build plans and sites " + at);
    }

    @SuppressWarnings("unchecked")
    private List<String> vanilla(int team){
        var out = new ArrayList<String>();
        mindustry.game.Teams.TeamData data = Team.get(team).data();
        RtsAI rts = data.rtsAi;
        if(rts != null){
            var line = new StringBuilder("rts timers ").append(times(get(RTS_TIMER, rts), 2)).append(" damaged");
            for(Building b : (ObjectSet<Building>)get(DAMAGED, rts)) line.append(' ').append(CommandRun.name(b));
            out.add(line.toString());
        }
        BaseBuilderAI build = data.buildAi;
        if(build != null){
            mindustry.world.Tile tile = (mindustry.world.Tile)get(CALC_TILE, build);
            out.add("build timers " + times(get(BUILD_TIMER, build), 3) + " found " + get(FOUND_PATH, build) + " calculating "
                + get(CALCULATING, build) + " started " + get(STARTED, build) + " count " + get(CALC_COUNT, build) + " total "
                + get(TOTAL_CALCS, build) + " at " + (tile == null ? "-" : tile.x + "," + tile.y));
            out.add("path " + Arrays.toString(sorted((IntSet)get(PATH, build))));
            out.add("calcPath " + Arrays.toString(sorted((IntSet)get(CALC_PATH, build))));
        }
        var plans = new StringBuilder("plans");
        for(mindustry.game.Teams.BlockPlan plan : data.plans){
            plans.append(" [").append(plan.x).append(',').append(plan.y).append(" r").append(plan.rotation).append(' ')
                .append(plan.block.name).append(' ').append(config(plan.config)).append(']');
        }
        out.add(plans.toString());
        for(var core : data.cores){
            var items = new StringBuilder("core@" + core.tile.x + "," + core.tile.y);
            for(int i = 0; i < content.items().size; i++) items.append(' ').append(core.items.get(i));
            out.add(items.toString());
        }
        return out;
    }

    private List<String> ours(int team){
        var out = new ArrayList<String>();
        TeamAis ais = ais();
        RtsAi rts = ais.rts(team);
        if(rts != null){
            var line = new StringBuilder("rts timers ").append(times(rts.timers())).append(" damaged");
            for(var b : rts.damaged()) line.append(' ').append(CommandRun.name(b));
            out.add(line.toString());
        }
        BaseBuilderAi build = ais.builder(team);
        if(build != null){
            var tiles = run.shard.tiles();
            int calcTile = calcTile(build);
            out.add("build timers " + times(build.timers()) + " found " + build.foundPath() + " calculating " + flag(build, "calculating")
                + " started " + flag(build, "startedCalculating") + " count " + number(build, "calcCount") + " total "
                + number(build, "totalCalcs") + " at " + (calcTile == -1 ? "-" : tiles.x(calcTile) + "," + tiles.y(calcTile)));
            out.add("path " + Arrays.toString(build.path()));
            out.add("calcPath " + Arrays.toString(calcPath(build)));
        }
        var plans = new StringBuilder("plans");
        for(BlockPlan plan : com.xpdustry.shard.sim.team.Teams.of(run.shard).get(team).plans()){
            plans.append(" [").append(plan.x).append(',').append(plan.y).append(" r").append(plan.rotation).append(' ')
                .append(run.shard.content().block(plan.block).name()).append(' ').append(config(plan.config)).append(']');
        }
        out.add(plans.toString());
        for(Core core : com.xpdustry.shard.sim.team.Teams.of(run.shard).cores(team)){
            var items = new StringBuilder("core@" + core.tileX() + "," + core.tileY());
            for(int i = 0; i < run.shard.content().items(); i++) items.append(' ').append(core.items.get(i));
            out.add(items.toString());
        }
        return out;
    }

    private List<String> vanillaBuilders(){
        var out = new ArrayList<String>();
        for(mindustry.gen.Unit u : Groups.unit){
            if(u.plans == null || u.plans.isEmpty()) continue;
            var line = new StringBuilder("unit#" + u.id + " plans " + u.plans.size);
            var first = u.plans.first();
            line.append(" first ").append(first.x).append(',').append(first.y).append(' ').append(first.breaking ? "break" : first.block.name)
                .append(" r").append(first.rotation).append(' ').append(CommandRun.bits(first.progress)).append(' ').append(config(first.config));
            out.add(line.toString());
        }
        for(var tile : world.tiles){
            if(tile.build instanceof ConstructBlock.ConstructBuild c && c.tile == tile){
                out.add("site@" + tile.x + "," + tile.y + " " + (c.current == null ? "-" : c.current.name) + " " + CommandRun.bits(c.progress));
            }
        }
        return out;
    }

    private List<String> shardBuilders(){
        var out = new ArrayList<String>();
        for(int i = 0; i < run.shard.units().size(); i++){
            com.xpdustry.shard.sim.unit.Unit u = run.shard.units().get(i);
            if(u.plans == null || u.plans.isEmpty()) continue;
            var line = new StringBuilder("unit#" + u.id() + " plans " + u.plans.size());
            BuildPlan first = u.plans.first();
            line.append(" first ").append(first.x).append(',').append(first.y).append(' ').append(first.breaking ? "break" : run.shard.content().block(first.block).name())
                .append(" r").append(first.rotation).append(' ').append(CommandRun.bits(first.progress)).append(' ').append(config(first.config));
            out.add(line.toString());
        }
        var tiles = run.shard.tiles();
        for(int pos = 0; pos < tiles.size(); pos++){
            if(run.shard.buildings().at(pos) instanceof ConstructBuild c && c.tile() == pos){
                var current = c.currentBlock();
                out.add("site@" + c.tileX() + "," + c.tileY() + " " + (current == null ? "-" : current.name()) + " " + CommandRun.bits(c.progress));
            }
        }
        return out;
    }

    /** A config on either side, as text both print alike. */
    static String config(Object config){
        return switch(config){
            case null -> "-";
            case byte[] bytes -> "bytes" + Arrays.hashCode(bytes) + "/" + bytes.length;
            case arc.math.geom.Point2 p -> "(" + p.x + "," + p.y + ")";
            case Point2 p -> "(" + p.x() + "," + p.y() + ")";
            case arc.math.geom.Point2[] points -> {
                var out = new StringBuilder("[");
                for(var p : points) out.append('(').append(p.x).append(',').append(p.y).append(')');
                yield out.append(']').toString();
            }
            case Point2[] points -> {
                var out = new StringBuilder("[");
                for(var p : points) out.append('(').append(p.x()).append(',').append(p.y()).append(')');
                yield out.append(']').toString();
            }
            case mindustry.ctype.MappableContent c -> c.getContentType().name() + "/" + c.name;
            case com.xpdustry.shard.sim.building.ContentRef ref -> ref.type().yamlName() + "/" + contentName(ref);
            case com.xpdustry.shard.sim.building.ContentRef.Foreign ref -> com.xpdustry.shard.content.ContentType.VANILLA_NAMES.get(ref.vanillaType()) + "/"
                + (ref.vanillaType() == 16 ? CommandRun.EPOCH.unitCommands().get(ref.id()) : ref.vanillaType() == 17 ? CommandRun.EPOCH.unitStances().get(ref.id()) : String.valueOf(ref.id()));
            default -> config.toString();
        };
    }

    private static String contentName(com.xpdustry.shard.sim.building.ContentRef ref){
        return CommandRun.EPOCH.get(ref.type(), ref.id()).name();
    }

    private static String times(Object interval, int count){
        try{
            float[] times = (float[])INTERVAL_TIMES.get(interval);
            return times(Arrays.copyOf(times, count));
        }catch(IllegalAccessException e){
            throw new AssertionError(e);
        }
    }

    private static String times(float[] times){
        var out = new StringBuilder("[");
        for(float t : times) out.append(CommandRun.bits(t)).append(' ');
        return out.append(']').toString();
    }

    private static int[] sorted(IntSet set){
        int[] out = new IntSet.IntSetIterator(set).toArray().toArray();
        Arrays.sort(out);
        return out;
    }

    // Shard's walk state, read as the checkpoint stores it
    private static int calcTile(BaseBuilderAi ai){
        return (int)field(ai, "calcTile");
    }

    private static int[] calcPath(BaseBuilderAi ai){
        return sortedShard((com.xpdustry.shard.common.struct.IntSet)field(ai, "calcPath"));
    }

    private static boolean flag(BaseBuilderAi ai, String name){
        return (boolean)field(ai, name);
    }

    private static int number(BaseBuilderAi ai, String name){
        return (int)field(ai, name);
    }

    private static int[] sortedShard(com.xpdustry.shard.common.struct.IntSet set){
        var seq = set.toSeq();
        int[] out = Arrays.copyOf(seq.items, seq.size);
        Arrays.sort(out);
        return out;
    }

    private static Object field(Object owner, String name){
        try{
            return open(owner.getClass().getDeclaredField(name)).get(owner);
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
    }

    private static Object get(Field field, Object owner){
        try{
            return field.get(owner);
        }catch(IllegalAccessException e){
            throw new AssertionError(e);
        }
    }
}
