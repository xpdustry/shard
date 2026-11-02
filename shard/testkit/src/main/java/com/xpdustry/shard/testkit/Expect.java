package com.xpdustry.shard.testkit;

import com.xpdustry.shard.testkit.Flat.*;

import java.util.*;
import java.util.function.*;

/**
 * Scripted expectations: values one engine must show at given ticks, when there is no second engine to compare
 * with (a kind not in vanilla, a toy kind, a known vanilla number).
 * <pre>{@code
 * Expect.of(scenario)
 *     .at(60, w -> w.building(3, 2).field("count", 60).items("copper", 15))
 *     .at(120, w -> w.unit(0).value("x", 64f).absent(w.building(9, 9)))
 *     .check(ShardRunner.of(content))
 *     .assertNoDiff();
 * }</pre>
 * Only the leaves you name are compared, under the {@link #tolerances}; a named leaf the engine lacks fails. The
 * check stops at the first tick with a failing expectation.
 */
public final class Expect{
    private final Scenario scenario;
    private final TreeMap<Long, World> ticks = new TreeMap<>();
    private Tolerances tolerances = Tolerances.exact();

    private Expect(Scenario scenario){
        this.scenario = scenario;
    }

    public static Expect of(Scenario scenario){
        return new Expect(scenario);
    }

    /** Adds expectations for tick {@code tick} (0 is right after the setup). */
    public Expect at(long tick, Consumer<World> expectations){
        if(tick < 0 || tick > scenario.ticks()) throw new IllegalArgumentException("tick " + tick + " is outside the scenario (0.." + scenario.ticks() + ")");
        expectations.accept(ticks.computeIfAbsent(tick, t -> new World()));
        return this;
    }

    public Expect tolerances(Tolerances tolerances){
        this.tolerances = Objects.requireNonNull(tolerances);
        return this;
    }

    /** Expectations for one tick. */
    public static final class World{
        private final LinkedHashMap<Key, Target> targets = new LinkedHashMap<>();

        public Target building(int x, int y){
            return target(Key.building(x, y));
        }

        /** The unit with entity id {@code id} (expectations always match units by id). */
        public Target unit(int id){
            return target(Key.unit(id));
        }

        public Target core(String team){
            return target(Key.core(team));
        }

        public Target rules(){
            return target(Key.of(Scope.RULES));
        }

        public Target waves(){
            return target(Key.of(Scope.WAVE));
        }

        /** Expects {@code target}'s entity not to exist. */
        public World absent(Target target){
            target.absent = true;
            return this;
        }

        private Target target(Key key){
            return targets.computeIfAbsent(key, k -> new Target(k, this));
        }
    }

    /** Expected leaves of one entity, by path relative to it ({@link Flat}). */
    public static final class Target{
        private final Key key;
        private final World world;
        private final LinkedHashMap<String, Object> leaves = new LinkedHashMap<>();
        private boolean absent;

        private Target(Key key, World world){
            this.key = key;
            this.world = world;
        }

        /** Any leaf: {@code health}, {@code items.copper}, {@code fields.ys[2]}, {@code x}. Lists and maps expand. */
        public Target value(String path, Object value){
            Object normalized = Values.of(value, v -> Values.UNSUPPORTED);
            if(normalized == Values.UNSUPPORTED) throw new IllegalArgumentException("cannot expect a " + value.getClass().getName());
            Flat.flatten(path, normalized, leaves);
            return this;
        }

        /** A kind field, {@code fields.<name>}. */
        public Target field(String name, Object value){
            return value("fields." + name, value);
        }

        public Target items(String item, int amount){
            return value(key.scope() == Scope.CORE ? item : "items." + item, amount);
        }

        public Target liquid(String liquid, float amount){
            return value("liquids." + liquid, amount);
        }

        public Target block(String block){
            return value("block", block);
        }

        public Target health(float health){
            return value("health", health);
        }

        /** The other entities of this tick. */
        public World and(){
            return world;
        }
    }

    /** Runs the scenario on {@code runner} and checks every expectation at its tick. */
    public DiffReport check(Runner runner){
        try(Run run = Run.start(runner, scenario)){
            long lastAgreed = -1;
            int checkpoints = 0;
            for(var entry : ticks.entrySet()){
                long tick = entry.getKey();
                run.stepTo(tick);
                checkpoints++;
                Flat flat = run.project().flat(UnitMatch.ID);
                var differences = new ArrayList<Difference>();
                for(Target target : entry.getValue().targets.values()) check(flat, target, differences);
                if(!differences.isEmpty()){
                    return new DiffReport(scenario.name(), "expected", runner.name(), "expectations at " + ticks.size() + " ticks",
                        scenario.ticks(), checkpoints, tick, lastAgreed, differences, List.of(), null, 0, run.nanos() / 1e9);
                }
                lastAgreed = tick;
            }
            return new DiffReport(scenario.name(), "expected", runner.name(), "expectations at " + ticks.size() + " ticks",
                scenario.ticks(), checkpoints, -1, lastAgreed, List.of(), List.of(), null, 0, run.nanos() / 1e9);
        }
    }

    private void check(Flat flat, Target target, List<Difference> out){
        Entity actual = flat.entity(target.key);
        String where = target.key.toString();
        if(target.absent){
            if(actual != null) out.add(new Difference(flat.tick(), where, actual.label(), "", Difference.ABSENT, "present", "exists", Difference.ABSENT));
            return;
        }
        if(actual == null){
            if(flat.absent().contains(target.key.scope())){
                out.add(new Difference(flat.tick(), where, null, "", "present", Difference.ABSENT, "not projected by " + flat.engine(), Difference.ABSENT));
            }else{
                out.add(new Difference(flat.tick(), where, null, "", "present", Difference.ABSENT, "exists", Difference.ABSENT));
            }
            return;
        }
        target.leaves.forEach((path, expected) -> {
            Object value = actual.leaves().containsKey(path) ? actual.leaves().get(path)
                : Compare.zeroByDefault(target.key.scope(), path) ? (Object)0 : Difference.ABSENT;
            Tolerances.Rule rule = tolerances.resolve(actual, path);
            if(value == Difference.ABSENT || !rule.accepts(expected, value)){
                out.add(new Difference(flat.tick(), where, actual.label(), path, expected, value, rule.describe(), Difference.ABSENT));
            }
        });
    }
}
