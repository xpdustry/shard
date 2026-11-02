package com.xpdustry.shard.testkit;

import com.xpdustry.shard.testkit.Action.*;
import org.snakeyaml.engine.v2.api.*;
import org.snakeyaml.engine.v2.common.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/**
 * The YAML form of a {@link Scenario}, for fixtures that are data rather than Java. One action per line, defaults left
 * out ({@code rotation: 0}, {@code team: sharded}, no config):
 * <pre>
 * schema: 1
 * name: sorter-split
 * world: {width: 12, height: 5}          # or: world: {map: fortress}
 * seed: 0
 * ticks: 600
 * actions:
 * - {at: 0, do: place, block: item-source, x: 1, y: 2, config: {item: copper}}
 * - {at: 0, do: place, block: conveyor, x: 2, y: 2}
 * - {at: 0, do: spawn, unit: flare, team: crux, x: 64.0, y: 64.0}
 * - {at: 0, do: rules, set: {waveTimer: false}}
 * - {at: 300, do: remove, x: 4, y: 2}
 * - {at: 300, do: open-valve, x: 4, y: 2}      # any other name is a custom action; its keys are the args
 * tolerances:                                   # optional: what Differential.of(ScenarioFile) compares with
 *   units: id                                   # or type-order
 *   missing-fields: note                        # or fail
 *   rules:
 *   - {abs: 'building[conveyor].fields.ys', by: 1.0E-5}
 *   - {ignore: building.fields.blendbits}
 * </pre>
 * Configs: {@code {item: copper}}, {@code {liquid: water}}, {@code {block: …}}, {@code {unit: …}}, {@code {int: 3}},
 * {@code {bool: true}}, {@code {string: …}}, {@code {point: [1, 0]}}, {@code {points: [[1, 0], [2, 0]]}}, or null.
 * {@link #write} is canonical: {@code write(read(write(s)))} is {@code write(s)}, and {@code read(write(s))} equals
 * {@code s}. Unknown keys are errors, so a typo never goes unnoticed.
 */
public final class ScenarioYaml{
    public static final int SCHEMA = 1;
    private static final Set<String> RESERVED = Set.of("at", "do");

    private ScenarioYaml(){}

    public static ScenarioFile load(Path file){
        try{
            return read(Files.readString(file));
        }catch(IOException e){
            throw new UncheckedIOException("cannot read " + file, e);
        }catch(IllegalArgumentException e){
            throw new IllegalArgumentException(file + ": " + e.getMessage(), e);
        }
    }

    /** A scenario from the class path, e.g. {@code "/scenarios/sorter-split.yaml"} in test resources. */
    public static ScenarioFile resource(String path){
        try(InputStream in = ScenarioYaml.class.getResourceAsStream(path)){
            if(in == null) throw new IllegalArgumentException("no resource " + path);
            return read(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }catch(IllegalArgumentException e){
            throw new IllegalArgumentException(path + ": " + e.getMessage(), e);
        }
    }

    //region reading

    public static ScenarioFile read(String yaml){
        Object root = new Load(LoadSettings.builder().setLabel("scenario").build()).loadFromString(yaml);
        var top = map(root, "the document");
        keys(top, "the document", "schema", "name", "world", "seed", "ticks", "actions", "tolerances");
        int schema = integer(top, "schema", SCHEMA);
        if(schema != SCHEMA) throw new IllegalArgumentException("scenario schema " + schema + ", this testkit reads " + SCHEMA);

        var world = map(require(top, "world"), "world");
        MapSpec map;
        if(world.containsKey("map")){
            keys(world, "world", "map");
            map = new MapSpec.Bundled(string(world, "map"));
        }else{
            keys(world, "world", "width", "height");
            map = new MapSpec.Empty(integer(world, "width", null), integer(world, "height", null));
        }

        var actions = new ArrayList<Timed>();
        Object list = top.get("actions");
        if(list != null){
            if(!(list instanceof List<?> items)) throw new IllegalArgumentException("actions must be a list");
            for(int i = 0; i < items.size(); i++){
                var action = map(items.get(i), "action " + (i + 1));
                try{
                    actions.add(new Timed(number(action, "at", 0L).longValue(), action(action)));
                }catch(RuntimeException e){
                    throw new IllegalArgumentException("action " + (i + 1) + " " + action + ": " + e.getMessage(), e);
                }
            }
        }
        var scenario = new Scenario(string(top, "name"), map, number(top, "seed", 0L).longValue(),
            number(top, "ticks", null).longValue(), actions);
        Tolerances tolerances = top.get("tolerances") == null ? null : tolerances(map(top.get("tolerances"), "tolerances"));
        return new ScenarioFile(scenario, tolerances);
    }

    private static Action action(Map<String, Object> m){
        String type = string(m, "do");
        return switch(type){
            case "place" -> {
                keys(m, type, "at", "do", "block", "x", "y", "rotation", "team", "config");
                yield new Place(string(m, "block"), integer(m, "x", null), integer(m, "y", null), integer(m, "rotation", 0),
                    m.containsKey("team") ? string(m, "team") : "sharded", config(m.get("config")));
            }
            case "remove" -> {
                keys(m, type, "at", "do", "x", "y");
                yield new Remove(integer(m, "x", null), integer(m, "y", null));
            }
            case "configure" -> {
                keys(m, type, "at", "do", "x", "y", "config");
                yield new Configure(integer(m, "x", null), integer(m, "y", null), config(m.get("config")));
            }
            case "items" -> {
                keys(m, type, "at", "do", "x", "y", "item", "amount");
                yield new Action.Items(integer(m, "x", null), integer(m, "y", null), string(m, "item"), integer(m, "amount", null));
            }
            case "liquid" -> {
                keys(m, type, "at", "do", "x", "y", "liquid", "amount");
                yield new Liquid(integer(m, "x", null), integer(m, "y", null), string(m, "liquid"), number(m, "amount", null).floatValue());
            }
            case "core-items" -> {
                keys(m, type, "at", "do", "team", "item", "amount");
                yield new CoreItems(m.containsKey("team") ? string(m, "team") : "sharded", string(m, "item"), integer(m, "amount", null));
            }
            case "spawn" -> {
                keys(m, type, "at", "do", "unit", "team", "x", "y", "rotation");
                yield new Spawn(string(m, "unit"), m.containsKey("team") ? string(m, "team") : "sharded",
                    number(m, "x", null).floatValue(), number(m, "y", null).floatValue(), number(m, "rotation", 0).floatValue());
            }
            case "rules" -> {
                keys(m, type, "at", "do", "set");
                yield new Action.Rules(map(require(m, "set"), "set"));
            }
            default -> {
                var args = new LinkedHashMap<String, Object>(m);
                RESERVED.forEach(args::remove);
                yield new Custom(type, args);
            }
        };
    }

    private static Config config(Object value){
        if(value == null) return Config.NONE;
        var m = map(value, "config");
        if(m.size() != 1) throw new IllegalArgumentException("a config has exactly one key (item, liquid, block, unit, int, bool, string, point, points): " + m);
        var entry = m.entrySet().iterator().next();
        Object v = entry.getValue();
        return switch(entry.getKey()){
            case "item", "liquid", "block", "unit" -> {
                if(!(v instanceof String name)) throw new IllegalArgumentException("config " + entry.getKey() + " must be a name");
                yield new Config.Content(entry.getKey(), name);
            }
            case "int" -> new Config.Int(integer(m, "int", null));
            case "bool" -> {
                if(!(v instanceof Boolean b)) throw new IllegalArgumentException("config bool must be true or false");
                yield new Config.Bool(b);
            }
            case "string" -> new Config.Text(string(m, "string"));
            case "point" -> point(v);
            case "points" -> {
                if(!(v instanceof List<?> list)) throw new IllegalArgumentException("config points must be a list of [x, y]");
                yield new Config.Points(list.stream().map(ScenarioYaml::point).toList());
            }
            default -> throw new IllegalArgumentException("unknown config type '" + entry.getKey() + "'");
        };
    }

    private static Config.Point point(Object value){
        if(value instanceof List<?> list && list.size() == 2 && list.get(0) instanceof Integer x && list.get(1) instanceof Integer y){
            return new Config.Point(x, y);
        }
        throw new IllegalArgumentException("a point is [x, y], not " + value);
    }

    private static Tolerances tolerances(Map<String, Object> m){
        keys(m, "tolerances", "units", "missing-fields", "rules");
        Tolerances out = Tolerances.exact();
        if(m.containsKey("units")){
            out = out.units(switch(string(m, "units")){
                case "id" -> UnitMatch.ID;
                case "type-order" -> UnitMatch.TYPE_ORDER;
                default -> throw new IllegalArgumentException("units must be id or type-order");
            });
        }
        if(m.containsKey("missing-fields")){
            out = out.missingFields(switch(string(m, "missing-fields")){
                case "note" -> Tolerances.MissingFields.NOTE;
                case "fail" -> Tolerances.MissingFields.FAIL;
                default -> throw new IllegalArgumentException("missing-fields must be note or fail");
            });
        }
        if(m.get("rules") instanceof List<?> rules){
            for(Object item : rules){
                var rule = map(item, "tolerance rule");
                Tolerances.Op op = null;
                for(Tolerances.Op candidate : Tolerances.Op.values()){
                    if(rule.containsKey(candidate.name().toLowerCase(Locale.ROOT))){
                        if(op != null) throw new IllegalArgumentException("a tolerance rule has one of ignore, exact, abs, rel: " + rule);
                        op = candidate;
                    }
                }
                if(op == null) throw new IllegalArgumentException("a tolerance rule needs ignore, exact, abs or rel: " + rule);
                String key = op.name().toLowerCase(Locale.ROOT);
                boolean amount = op == Tolerances.Op.ABS || op == Tolerances.Op.REL;
                if(amount) keys(rule, "tolerance rule", key, "by");
                else keys(rule, "tolerance rule", key);
                out = out.with(new Tolerances.Rule(op, string(rule, key), amount ? number(rule, "by", null).doubleValue() : 0));
            }
        }else if(m.containsKey("rules")){
            throw new IllegalArgumentException("tolerances.rules must be a list");
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value, String what){
        if(!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException(what + " must be a map, not " + value);
        for(Object key : map.keySet()){
            if(!(key instanceof String)) throw new IllegalArgumentException(what + ": key " + key + " is not a string");
        }
        return (Map<String, Object>)map;
    }

    private static void keys(Map<String, Object> m, String what, String... allowed){
        var known = List.of(allowed);
        for(String key : m.keySet()){
            if(!known.contains(key)) throw new IllegalArgumentException(what + ": unknown key '" + key + "' (known: " + String.join(", ", known) + ")");
        }
    }

    private static Object require(Map<String, Object> m, String key){
        Object value = m.get(key);
        if(value == null) throw new IllegalArgumentException("missing '" + key + "'");
        return value;
    }

    private static String string(Map<String, Object> m, String key){
        Object value = require(m, key);
        if(!(value instanceof String s)) throw new IllegalArgumentException("'" + key + "' must be a string, not " + value);
        return s;
    }

    private static Number number(Map<String, Object> m, String key, Number fallback){
        Object value = m.get(key);
        if(value == null){
            if(fallback == null) throw new IllegalArgumentException("missing '" + key + "'");
            return fallback;
        }
        if(!(value instanceof Number n)) throw new IllegalArgumentException("'" + key + "' must be a number, not " + value);
        return n;
    }

    private static int integer(Map<String, Object> m, String key, Integer fallback){
        Number n = number(m, key, fallback);
        if(!(n instanceof Integer i)) throw new IllegalArgumentException("'" + key + "' must be an integer, not " + n);
        return i;
    }

    //endregion
    //region writing

    public static String write(Scenario scenario){
        return write(ScenarioFile.of(scenario));
    }

    public static String write(ScenarioFile file){
        Scenario s = file.scenario();
        var out = new StringBuilder();
        out.append("schema: ").append(SCHEMA).append('\n');
        out.append("name: ").append(flow(s.name())).append('\n');
        var world = new LinkedHashMap<String, Object>();
        switch(s.map()){
            case MapSpec.Empty(int width, int height) -> {
                world.put("width", width);
                world.put("height", height);
            }
            case MapSpec.Bundled(String map) -> world.put("map", map);
        }
        out.append("world: ").append(flow(world)).append('\n');
        out.append("seed: ").append(s.seed()).append('\n');
        out.append("ticks: ").append(s.ticks()).append('\n');
        if(!s.actions().isEmpty()){
            out.append("actions:\n");
            for(Timed timed : s.actions()) out.append("- ").append(describe(timed)).append('\n');
        }
        Tolerances t = file.tolerances();
        if(t != null){
            out.append("tolerances:\n");
            out.append("  units: ").append(t.units() == UnitMatch.ID ? "id" : "type-order").append('\n');
            out.append("  missing-fields: ").append(t.missingFields().name().toLowerCase(Locale.ROOT)).append('\n');
            if(!t.rules().isEmpty()){
                out.append("  rules:\n");
                for(Tolerances.Rule rule : t.rules()){
                    var m = new LinkedHashMap<String, Object>();
                    m.put(rule.op().name().toLowerCase(Locale.ROOT), rule.pattern());
                    if(rule.op() == Tolerances.Op.ABS || rule.op() == Tolerances.Op.REL) m.put("by", rule.amount());
                    out.append("  - ").append(flow(m)).append('\n');
                }
            }
        }
        return out.toString();
    }

    /** One action as its YAML line: {@code {at: 0, do: place, block: conveyor, x: 2, y: 2}}. */
    public static String describe(Timed timed){
        var m = new LinkedHashMap<String, Object>();
        m.put("at", timed.at());
        switch(timed.action()){
            case Place p -> {
                m.put("do", "place");
                m.put("block", p.block());
                m.put("x", p.x());
                m.put("y", p.y());
                if(p.rotation() != 0) m.put("rotation", p.rotation());
                if(!p.team().equals("sharded")) m.put("team", p.team());
                if(!(p.config() instanceof Config.None)) m.put("config", config(p.config()));
            }
            case Remove r -> {
                m.put("do", "remove");
                m.put("x", r.x());
                m.put("y", r.y());
            }
            case Configure c -> {
                m.put("do", "configure");
                m.put("x", c.x());
                m.put("y", c.y());
                m.put("config", config(c.config()));
            }
            case Action.Items i -> {
                m.put("do", "items");
                m.put("x", i.x());
                m.put("y", i.y());
                m.put("item", i.item());
                m.put("amount", i.amount());
            }
            case Liquid l -> {
                m.put("do", "liquid");
                m.put("x", l.x());
                m.put("y", l.y());
                m.put("liquid", l.liquid());
                m.put("amount", decimal(l.amount()));
            }
            case CoreItems c -> {
                m.put("do", "core-items");
                if(!c.team().equals("sharded")) m.put("team", c.team());
                m.put("item", c.item());
                m.put("amount", c.amount());
            }
            case Spawn s -> {
                m.put("do", "spawn");
                m.put("unit", s.unit());
                if(!s.team().equals("sharded")) m.put("team", s.team());
                m.put("x", decimal(s.x()));
                m.put("y", decimal(s.y()));
                if(s.rotation() != 0f) m.put("rotation", decimal(s.rotation()));
            }
            case Action.Rules r -> {
                m.put("do", "rules");
                m.put("set", r.set());
            }
            case Custom c -> {
                m.put("do", c.name());
                c.args().forEach((key, value) -> {
                    if(RESERVED.contains(key)) throw new IllegalArgumentException("custom action " + c.name() + ": '" + key + "' is reserved");
                    m.put(key, value);
                });
            }
        }
        return flow(m);
    }

    private static Object config(Config config){
        return switch(config){
            case Config.None n -> null;
            case Config.Int i -> Map.of("int", i.value());
            case Config.Bool b -> Map.of("bool", b.value());
            case Config.Text t -> Map.of("string", t.value());
            case Config.Point p -> Map.of("point", List.of(p.x(), p.y()));
            case Config.Points p -> Map.of("points", p.points().stream().map(q -> List.of(q.x(), q.y())).toList());
            case Config.Content c -> Map.of(c.type(), c.name());
        };
    }

    /** A float as its shortest decimal, which reads back as the same float. */
    private static double decimal(float value){
        return Double.parseDouble(Float.toString(value));
    }

    private static String flow(Object value){
        var settings = DumpSettings.builder().setDefaultFlowStyle(FlowStyle.FLOW).setWidth(Integer.MAX_VALUE).setSplitLines(false).build();
        return new Dump(settings).dumpToString(value).strip();
    }

    //endregion
}
