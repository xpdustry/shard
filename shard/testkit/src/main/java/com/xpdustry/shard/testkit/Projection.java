package com.xpdustry.shard.testkit;

import com.xpdustry.shard.oracle.*;

import java.util.*;

/**
 * A normalized view of one world at one tick, the same schema for every engine ({@link #SCHEMA}). Both runners build
 * it, the diff compares it, and {@link #json()} prints it.
 *
 * <ul>
 *   <li>{@code buildings}: one per building, at its center tile, sorted by (y, x). {@code kind} is the behaviour kind
 *   (Shard's kind key; vanilla's block class in kebab case) and is never compared. {@code fields} holds the kind's
 *   own state under vanilla's field names.</li>
 *   <li>{@code units}: sorted by entity id. Positions are world units (8 per tile).</li>
 *   <li>{@code cores} (team → item → amount), {@code rules} (simple rule fields by name) and {@code waves} are null
 *   when the engine doesn't project them yet; the diff then notes them as not compared.</li>
 * </ul>
 * Item, liquid and core maps leave out zero amounts.
 */
public record Projection(int schema, String engine, long tick, int width, int height, List<BuildingState> buildings,
                         List<UnitState> units, Map<String, Map<String, Integer>> cores, Map<String, Object> rules,
                         WaveState waves){
    /** Bump when a field changes meaning or moves; readers refuse other versions. */
    public static final int SCHEMA = 1;

    public Projection{
        buildings = List.copyOf(buildings);
        units = List.copyOf(units);
    }

    /**
     * @param items item → amount, or null without an item module
     * @param liquids liquid → amount, or null without a liquid module
     * @param power the power module, or null without one
     */
    public record BuildingState(int x, int y, String block, String kind, String team, int rotation, float health,
                                boolean enabled, Map<String, Integer> items, Map<String, Float> liquids, PowerState power,
                                Map<String, Object> fields){
    }

    public record PowerState(float status){
    }

    public record UnitState(int id, String type, String team, float x, float y, float rotation, float health){
    }

    /** Vanilla's {@code state.wave}, {@code state.wavetime} (the countdown, in ticks) and {@code state.enemies}. */
    public record WaveState(int wave, float wavetime, int enemies){
    }

    public static Builder builder(String engine, long tick, int width, int height){
        return new Builder(engine, tick, width, height);
    }

    /** The building whose center is (x, y), or null. */
    public BuildingState building(int x, int y){
        for(BuildingState building : buildings){
            if(building.x() == x && building.y() == y) return building;
        }
        return null;
    }

    /** The unit with entity id {@code id}, or null. */
    public UnitState unit(int id){
        for(UnitState unit : units){
            if(unit.id() == id) return unit;
        }
        return null;
    }

    /** The flattened form the diff compares. */
    public Flat flat(UnitMatch match){
        return Flat.of(this, match);
    }

    public String json(){
        return Json.write(this);
    }

    /** Reads {@link #json()} back, for committed golden projections. */
    @SuppressWarnings("unchecked")
    public static Projection fromJson(String json){
        var root = (Map<String, Object>)Json.read(json);
        int schema = ((Number)root.get("schema")).intValue();
        if(schema != SCHEMA) throw new IllegalArgumentException("projection schema " + schema + ", this testkit reads " + SCHEMA);
        var buildings = new ArrayList<BuildingState>();
        for(var b : (List<Map<String, Object>>)root.get("buildings")){
            var power = (Map<String, Object>)b.get("power");
            buildings.add(new BuildingState(i(b.get("x")), i(b.get("y")), (String)b.get("block"), (String)b.get("kind"),
                (String)b.get("team"), i(b.get("rotation")), f(b.get("health")), (Boolean)b.get("enabled"),
                ints((Map<String, Object>)b.get("items")), floats((Map<String, Object>)b.get("liquids")),
                power == null ? null : new PowerState(f(power.get("status"))), (Map<String, Object>)b.get("fields")));
        }
        var units = new ArrayList<UnitState>();
        for(var u : (List<Map<String, Object>>)root.get("units")){
            units.add(new UnitState(i(u.get("id")), (String)u.get("type"), (String)u.get("team"), f(u.get("x")), f(u.get("y")),
                f(u.get("rotation")), f(u.get("health"))));
        }
        Map<String, Map<String, Integer>> cores = null;
        if(root.get("cores") instanceof Map<?, ?> map){
            cores = new TreeMap<>();
            for(var entry : ((Map<String, Object>)map).entrySet()) cores.put(entry.getKey(), ints((Map<String, Object>)entry.getValue()));
        }
        var waves = (Map<String, Object>)root.get("waves");
        return new Projection(schema, (String)root.get("engine"), ((Number)root.get("tick")).longValue(), i(root.get("width")),
            i(root.get("height")), buildings, units, cores, (Map<String, Object>)root.get("rules"),
            waves == null ? null : new WaveState(i(waves.get("wave")), f(waves.get("wavetime")), i(waves.get("enemies"))));
    }

    private static int i(Object value){
        return ((Number)value).intValue();
    }

    private static float f(Object value){
        return value instanceof String s ? Float.parseFloat(s) : ((Number)value).floatValue();
    }

    private static Map<String, Integer> ints(Map<String, Object> map){
        if(map == null) return null;
        var out = new LinkedHashMap<String, Integer>();
        map.forEach((k, v) -> out.put(k, i(v)));
        return out;
    }

    private static Map<String, Float> floats(Map<String, Object> map){
        if(map == null) return null;
        var out = new LinkedHashMap<String, Float>();
        map.forEach((k, v) -> out.put(k, f(v)));
        return out;
    }

    /** What a runner fills in; {@link #build()} sorts buildings by (y, x) and units by id. */
    public static final class Builder{
        private final String engine;
        private final long tick;
        private final int width, height;
        private final ArrayList<BuildingState> buildings = new ArrayList<>();
        private final ArrayList<UnitState> units = new ArrayList<>();
        private Map<String, Map<String, Integer>> cores;
        private Map<String, Object> rules;
        private WaveState waves;

        private Builder(String engine, long tick, int width, int height){
            this.engine = engine;
            this.tick = tick;
            this.width = width;
            this.height = height;
        }

        public Builder building(BuildingState building){
            buildings.add(building);
            return this;
        }

        public Builder unit(UnitState unit){
            units.add(unit);
            return this;
        }

        /** Marks cores as projected, even if no team has one. */
        public Builder cores(){
            if(cores == null) cores = new TreeMap<>();
            return this;
        }

        /** Adds {@code amount} of {@code item} to {@code team}'s core items; zero amounts are left out. */
        public Builder core(String team, String item, int amount){
            cores();
            var items = cores.computeIfAbsent(team, t -> new LinkedHashMap<>());
            int total = items.getOrDefault(item, 0) + amount;
            if(total == 0) items.remove(item);
            else items.put(item, total);
            return this;
        }

        /** Marks rules as projected. */
        public Builder rules(){
            if(rules == null) rules = new TreeMap<>();
            return this;
        }

        public Builder rule(String name, Object value){
            rules();
            rules.put(name, value);
            return this;
        }

        public Builder waves(WaveState waves){
            this.waves = waves;
            return this;
        }

        public Projection build(){
            buildings.sort(Comparator.comparingInt(BuildingState::y).thenComparingInt(BuildingState::x));
            units.sort(Comparator.comparingInt(UnitState::id));
            return new Projection(SCHEMA, engine, tick, width, height, buildings, units, cores, rules, waves);
        }
    }
}
