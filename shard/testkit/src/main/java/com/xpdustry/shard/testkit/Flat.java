package com.xpdustry.shard.testkit;

import java.util.*;

/**
 * A {@link Projection} as entities of scalar leaves, which is what the diff compares and what failure dumps print.
 * Each entity has a {@link Key} ({@code building(3,4)}, {@code unit#12}, {@code core(sharded)}, {@code rules},
 * {@code wave}, {@code world}) and its leaves in projection order, by path relative to the entity:
 * {@code health}, {@code items.copper}, {@code power.status}, {@code fields.ys[2]}. Empty lists and maps are leaves
 * themselves, so a field that is present stays visible.
 *
 * <p>Immutable once built; {@link Trace} shares unchanged entities between ticks.
 */
public final class Flat{
    /** A projection section; also the first word of a tolerance pattern. */
    public enum Scope{
        WORLD("world"), BUILDING("building"), UNIT("unit"), CORE("core"), RULES("rules"), WAVE("wave");

        final String word;

        Scope(String word){
            this.word = word;
        }

        public static Scope of(String word){
            for(Scope scope : values()){
                if(scope.word.equals(word)) return scope;
            }
            throw new IllegalArgumentException("no scope '" + word + "' (world, building, unit, core, rules, wave)");
        }

        @Override
        public String toString(){
            return word;
        }
    }

    /**
     * Identifies an entity across engines. Buildings by center tile ({@code a} = y, {@code b} = x, so keys sort in
     * row order); units by id ({@code a}), or by type, team and rank ({@code name}, {@code a}) under
     * {@link UnitMatch#TYPE_ORDER}; cores by team ({@code name}).
     */
    public record Key(Scope scope, int a, int b, String name) implements Comparable<Key>{
        static final Comparator<Key> ORDER = Comparator.comparing(Key::scope).thenComparing(Key::name)
            .thenComparingInt(Key::a).thenComparingInt(Key::b);

        public static Key building(int x, int y){
            return new Key(Scope.BUILDING, y, x, "");
        }

        public static Key unit(int id){
            return new Key(Scope.UNIT, id, 0, "");
        }

        public static Key core(String team){
            return new Key(Scope.CORE, 0, 0, team);
        }

        public static Key of(Scope scope){
            return new Key(scope, 0, 0, "");
        }

        @Override
        public int compareTo(Key other){
            return ORDER.compare(this, other);
        }

        @Override
        public String toString(){
            return switch(scope){
                case BUILDING -> "building(" + b + "," + a + ")";
                case UNIT -> name.isEmpty() ? "unit#" + a : "unit(" + name + " #" + a + ")";
                case CORE -> "core(" + name + ")";
                default -> scope.word;
            };
        }
    }

    /**
     * One entity. {@code label} is what selectors match: a building's block, a unit's type, a core's team.
     * {@code kind} is a building's kind (never compared), else null.
     */
    public record Entity(Key key, String label, String kind, Map<String, Object> leaves){
        public Entity{
            leaves = Collections.unmodifiableMap(leaves);
        }
    }

    private final String engine;
    private final long tick;
    private final TreeMap<Key, Entity> entities;
    private final EnumSet<Scope> absent;

    Flat(String engine, long tick, TreeMap<Key, Entity> entities, EnumSet<Scope> absent){
        this.engine = engine;
        this.tick = tick;
        this.entities = entities;
        this.absent = absent;
    }

    static Flat of(Projection p, UnitMatch match){
        var entities = new TreeMap<Key, Entity>();
        var absent = EnumSet.noneOf(Scope.class);

        var world = new LinkedHashMap<String, Object>();
        world.put("width", p.width());
        world.put("height", p.height());
        put(entities, new Entity(Key.of(Scope.WORLD), null, null, world));

        for(var b : p.buildings()){
            var leaves = new LinkedHashMap<String, Object>();
            leaves.put("block", b.block());
            leaves.put("team", b.team());
            leaves.put("rotation", b.rotation());
            leaves.put("health", b.health());
            leaves.put("enabled", b.enabled());
            if(b.items() != null) b.items().forEach((item, amount) -> leaves.put("items." + item, amount));
            if(b.liquids() != null) b.liquids().forEach((liquid, amount) -> leaves.put("liquids." + liquid, amount));
            if(b.power() != null) leaves.put("power.status", b.power().status());
            if(b.fields() != null) b.fields().forEach((name, value) -> flatten("fields." + name, value, leaves));
            put(entities, new Entity(Key.building(b.x(), b.y()), b.block(), b.kind(), leaves));
        }

        var ranks = new HashMap<String, Integer>();
        for(var u : p.units()){
            var leaves = new LinkedHashMap<String, Object>();
            Key key;
            if(match == UnitMatch.ID){
                key = Key.unit(u.id());
            }else{
                String group = u.type() + "/" + u.team();
                int rank = ranks.merge(group, 1, Integer::sum) - 1;
                key = new Key(Scope.UNIT, rank, 0, group);
            }
            leaves.put("type", u.type());
            leaves.put("team", u.team());
            leaves.put("x", u.x());
            leaves.put("y", u.y());
            leaves.put("rotation", u.rotation());
            leaves.put("health", u.health());
            put(entities, new Entity(key, u.type(), null, leaves));
        }

        if(p.cores() == null){
            absent.add(Scope.CORE);
        }else{
            p.cores().forEach((team, items) -> put(entities, new Entity(Key.core(team), team, null, new LinkedHashMap<>(items))));
        }
        if(p.rules() == null){
            absent.add(Scope.RULES);
        }else{
            var leaves = new LinkedHashMap<String, Object>();
            p.rules().forEach((name, value) -> flatten(name, value, leaves));
            put(entities, new Entity(Key.of(Scope.RULES), null, null, leaves));
        }
        if(p.waves() == null){
            absent.add(Scope.WAVE);
        }else{
            var leaves = new LinkedHashMap<String, Object>();
            leaves.put("wave", p.waves().wave());
            leaves.put("wavetime", p.waves().wavetime());
            leaves.put("enemies", p.waves().enemies());
            put(entities, new Entity(Key.of(Scope.WAVE), null, null, leaves));
        }
        return new Flat(p.engine(), p.tick(), entities, absent);
    }

    private static void put(TreeMap<Key, Entity> entities, Entity entity){
        if(entities.putIfAbsent(entity.key(), entity) != null){
            throw new IllegalStateException("two entities project to " + entity.key() + " (two buildings centered on one tile?)");
        }
    }

    /** Lists become {@code path[i]}, maps {@code path.key}; empty ones stay as one leaf. */
    static void flatten(String path, Object value, Map<String, Object> out){
        if(value instanceof List<?> list){
            if(list.isEmpty()) out.put(path, List.of());
            for(int i = 0; i < list.size(); i++) flatten(path + "[" + i + "]", list.get(i), out);
        }else if(value instanceof Map<?, ?> map){
            if(map.isEmpty()) out.put(path, Map.of());
            for(var entry : map.entrySet()) flatten(path + "." + entry.getKey(), entry.getValue(), out);
        }else{
            out.put(path, value);
        }
    }

    public String engine(){
        return engine;
    }

    public long tick(){
        return tick;
    }

    /** Every entity, in key order. */
    public Collection<Entity> entities(){
        return Collections.unmodifiableCollection(entities.values());
    }

    public Entity entity(Key key){
        return entities.get(key);
    }

    /** Sections this engine doesn't project (cores, rules, waves). */
    public Set<Scope> absent(){
        return Collections.unmodifiableSet(absent);
    }

    TreeMap<Key, Entity> map(){
        return entities;
    }

    /** One {@code entity.path = value} line per leaf, for failure dumps; two dumps diff well with {@code diff -u}. */
    public String text(){
        var out = new StringBuilder();
        out.append("# ").append(engine).append(", tick ").append(tick);
        if(!absent.isEmpty()) out.append(", not projected: ").append(absent);
        out.append('\n');
        for(Entity entity : entities.values()){
            String prefix = entity.key().toString();
            if(entity.kind() != null) out.append(prefix).append(" $kind = ").append(entity.kind()).append('\n');
            entity.leaves().forEach((path, value) -> out.append(prefix).append('.').append(path).append(" = ").append(Values.show(value)).append('\n'));
        }
        return out.toString();
    }
}
