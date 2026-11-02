package com.xpdustry.shard.testkit;

import com.xpdustry.shard.testkit.Action.*;

import java.util.*;

/**
 * A world layout and a timeline: the map, the gameplay seed, how many ticks to run, and actions at given ticks.
 * Tick 0 is the setup, before the first tick runs; an action at tick {@code t} runs after {@code t} ticks. Actions of
 * one tick run in list order. Immutable; {@link ScenarioYaml} reads and writes the YAML form.
 * <pre>{@code
 * Scenario s = Scenario.named("sorter-split").world(12, 5)
 *     .place("item-source", 1, 2, 0, Config.item("copper"))
 *     .row("conveyor", 2, 5, 2, 0)
 *     .place("sorter", 6, 2, 0, Config.item("copper"))
 *     .step(300)
 *     .remove(4, 2)
 *     .step(300)
 *     .build();
 * }</pre>
 *
 * @param seed the gameplay random seed: Shard's world {@code Rand}, vanilla's {@code Mathf.rand}
 */
public record Scenario(String name, MapSpec map, long seed, long ticks, List<Timed> actions){
    public Scenario{
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(map, "map");
        if(ticks < 0) throw new IllegalArgumentException("negative tick count");
        var sorted = new ArrayList<>(actions);
        sorted.sort(Comparator.comparingLong(Timed::at)); // stable: same-tick actions keep their order
        for(Timed timed : sorted){
            if(timed.at() > ticks) throw new IllegalArgumentException("action at tick " + timed.at() + " is past the end (" + ticks + "): " + timed.action());
        }
        actions = List.copyOf(sorted);
    }

    public static Builder named(String name){
        return new Builder(name);
    }

    /** The same scenario run for {@code ticks} ticks instead (at least as long as its last action). */
    public Scenario withTicks(long ticks){
        return new Scenario(name, map, seed, ticks, actions);
    }

    public Builder toBuilder(){
        var builder = new Builder(name);
        builder.map = map;
        builder.seed = seed;
        builder.actions.addAll(actions);
        builder.cursor = ticks;
        return builder;
    }

    /**
     * Builds a scenario as a timeline: actions go at the cursor, {@link #step(long)} moves it, and the scenario ends
     * where the cursor ends. Placements and spawns use the current {@link #team(String)}, sharded at first.
     */
    public static final class Builder{
        private final String name;
        private MapSpec map = new MapSpec.Empty(16, 16);
        private long seed;
        private long cursor;
        private String team = "sharded";
        private final ArrayList<Timed> actions = new ArrayList<>();

        private Builder(String name){
            this.name = name;
        }

        /** An empty stone world (the default is 16×16). */
        public Builder world(int width, int height){
            map = new MapSpec.Empty(width, height);
            return this;
        }

        /** A bundled map, hosted in survival. */
        public Builder map(String map){
            this.map = new MapSpec.Bundled(map);
            return this;
        }

        public Builder seed(long seed){
            this.seed = seed;
            return this;
        }

        /** The team of the placements and spawns that follow. */
        public Builder team(String team){
            Teams.id(team);
            this.team = team;
            return this;
        }

        /** Runs {@code ticks} ticks before the next actions. */
        public Builder step(long ticks){
            if(ticks < 0) throw new IllegalArgumentException("negative step");
            cursor += ticks;
            return this;
        }

        /** Moves the cursor to tick {@code tick}, which must not be before it. */
        public Builder at(long tick){
            if(tick < cursor) throw new IllegalArgumentException("tick " + tick + " is before the cursor (" + cursor + ")");
            cursor = tick;
            return this;
        }

        public long cursor(){
            return cursor;
        }

        public Builder action(Action action){
            actions.add(new Timed(cursor, action));
            return this;
        }

        public Builder place(String block, int x, int y){
            return place(block, x, y, 0, Config.NONE);
        }

        public Builder place(String block, int x, int y, int rotation){
            return place(block, x, y, rotation, Config.NONE);
        }

        public Builder place(String block, int x, int y, int rotation, Config config){
            return action(new Place(block, x, y, rotation, team, config));
        }

        /** {@code block} on every tile from (x0, y) to (x1, y), inclusive, either direction. */
        public Builder row(String block, int x0, int x1, int y, int rotation){
            for(int x = x0; ; x += Integer.signum(x1 - x0)){
                place(block, x, y, rotation);
                if(x == x1) break;
            }
            return this;
        }

        /** {@code block} on every tile from (x, y0) to (x, y1), inclusive, either direction. */
        public Builder column(String block, int x, int y0, int y1, int rotation){
            for(int y = y0; ; y += Integer.signum(y1 - y0)){
                place(block, x, y, rotation);
                if(y == y1) break;
            }
            return this;
        }

        public Builder remove(int x, int y){
            return action(new Remove(x, y));
        }

        public Builder configure(int x, int y, Config config){
            return action(new Configure(x, y, config));
        }

        public Builder items(int x, int y, String item, int amount){
            return action(new Items(x, y, item, amount));
        }

        public Builder liquid(int x, int y, String liquid, float amount){
            return action(new Liquid(x, y, liquid, amount));
        }

        /** Core items for the current team. */
        public Builder coreItems(String item, int amount){
            return action(new CoreItems(team, item, amount));
        }

        /** A unit at world position (x, y): tile (tx, ty) is (tx × 8, ty × 8). */
        public Builder spawn(String unit, float x, float y){
            return spawn(unit, x, y, 0f);
        }

        public Builder spawn(String unit, float x, float y, float rotation){
            return action(new Spawn(unit, team, x, y, rotation));
        }

        public Builder rule(String name, Object value){
            return action(new Action.Rules(Map.of(name, value)));
        }

        public Builder rules(Map<String, Object> set){
            return action(new Action.Rules(set));
        }

        /**
         * Vanilla's ground units stand still until its pathfinder thread has built their flowfield, in wall time; this
         * waits for it (a no-op on Shard). Put it after the spawns. See {@link OracleRunner#WAIT_FOR_PATHS}.
         */
        public Builder waitForPaths(){
            return custom(OracleRunner.WAIT_FOR_PATHS, Map.of());
        }

        public Builder custom(String name, Map<String, Object> args){
            return action(new Custom(name, args));
        }

        /** The scenario, ending at the cursor. */
        public Scenario build(){
            return new Scenario(name, map, seed, cursor, actions);
        }
    }
}
