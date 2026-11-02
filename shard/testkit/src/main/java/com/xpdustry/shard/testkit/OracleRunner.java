package com.xpdustry.shard.testkit;

import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.testkit.Action.*;
import com.xpdustry.shard.testkit.Projection.*;
import mindustry.ctype.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

import static mindustry.Vars.*;

/**
 * Runs scenarios on vanilla v160.7 through the oracle's {@link Sim}: one server frame per tick, {@code Time.delta == 1}.
 * Use it from {@code src/oracleTest} only (vanilla is static state: one JVM per test class).
 *
 * <p>Vanilla is global, so only one session is open at a time ({@link #global()}). Each open resets vanilla as
 * {@code Vanilla.reset()} does, seeds {@code Mathf.rand} with the scenario seed and rewinds {@code Time.time} to 0
 * (vanilla's {@code Logic.reset} keeps it, which would shift timers between two runs in one JVM).
 *
 * <p>Projection: a building's {@code fields} are the instance fields its build class declares below
 * {@code Building} (a conveyor's {@code ids}, {@code xs}, {@code ys}, {@code len}…), when {@link Values} can hold
 * them: references to buildings and tiles become {@code "@x,y"}, units {@code "#id"}, content its name, arc
 * {@code Seq}s lists, {@code Point2} and {@code Vec2} {@code [x, y]}. Other fields are left out; read them with
 * {@link #field}. Rules are every primitive, string or enum field of {@code state.rules}.
 */
public final class OracleRunner implements Runner{
    /** Applies a {@link Action.Custom} action on vanilla. */
    @FunctionalInterface
    public interface Handler{
        void apply(Sim sim, Map<String, Object> args);
    }

    /** Reads one extra field of a vanilla building. */
    @FunctionalInterface
    public interface Reader{
        Object read(Building building);
    }

    private static final ConcurrentHashMap<Class<?>, List<Field>> BUILD_FIELDS = new ConcurrentHashMap<>();
    private static List<Field> ruleFields;
    private static OracleSession current;

    private String name = "vanilla";
    private final TreeMap<String, Handler> handlers = new TreeMap<>();
    private final ArrayList<Extra> extras = new ArrayList<>();

    private record Extra(String selector, String field, Reader reader){
    }

    private OracleRunner(){
        on(WAIT_FOR_PATHS, (sim, args) -> waitForPaths(((Number)args.getOrDefault("timeout", 10_000)).longValue()));
    }

    /**
     * The {@code wait-for-paths} custom action ({@link Scenario.Builder#waitForPaths()}): blocks, in wall time, until
     * vanilla's {@code Pathfinder} thread has completed the core flowfield of every ground unit's team and path type.
     * Ground AI stands still until then, and when that happens is the documented nondeterminism (the oracle README),
     * so a scenario waits once its units exist. Arg {@code timeout}: milliseconds, 10 000 by default.
     */
    public static final String WAIT_FOR_PATHS = "wait-for-paths";

    private static void waitForPaths(long timeoutMillis){
        var fields = new ArrayList<mindustry.ai.Pathfinder.Flowfield>();
        Groups.unit.each(u -> !u.type.flying, u -> {
            var field = pathfinder.getField(u.team, u.type.flowfieldPathType, mindustry.ai.Pathfinder.fieldCore);
            if(!fields.contains(field)) fields.add(field);
        });
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        for(var field : fields){
            while(!field.hasCompleteWeights()){
                if(System.nanoTime() > deadline) throw new IllegalStateException("vanilla's pathfinder didn't complete a flowfield in " + timeoutMillis + " ms");
                try{
                    Thread.sleep(2);
                }catch(InterruptedException e){
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    public static OracleRunner create(){
        return new OracleRunner();
    }

    public OracleRunner name(String name){
        this.name = Objects.requireNonNull(name);
        return this;
    }

    /** Handles {@link Action.Custom} actions named {@code action}. */
    public OracleRunner on(String action, Handler handler){
        handlers.put(action, Objects.requireNonNull(handler));
        return this;
    }

    /**
     * Adds (or replaces) field {@code field} of buildings matching {@code selector}: a block name, {@code kind=<kind>}
     * or {@code *}. The value goes through {@link Values} like the reflected ones.
     */
    public OracleRunner field(String selector, String field, Reader reader){
        extras.add(new Extra(selector, field, reader));
        return this;
    }

    @Override
    public String name(){
        return name;
    }

    @Override
    public boolean global(){
        return true;
    }

    @Override
    public Session open(Scenario scenario){
        synchronized(OracleRunner.class){
            if(current != null) throw new IllegalStateException("vanilla is global: close the open " + current.label + " session first");
            // before Sim.empty: Vanilla.emptyWorld(w, h) reads Blocks.stone before it boots, so it would be null
            Vanilla.boot();
            Sim sim = switch(scenario.map()){
                case MapSpec.Empty(int width, int height) -> Sim.empty(width, height);
                case MapSpec.Bundled(String map) -> Sim.host(map);
            };
            Mathf.rand.setSeed(scenario.seed());
            Time.setInternalTime(0);
            current = new OracleSession(sim, scenario.name());
            return current;
        }
    }

    /** {@code GenericCrafter} → {@code generic-crafter}: the kind key vanilla's block class would have. */
    static String kind(Block block){
        Class<?> type = block.getClass();
        while(type.getSimpleName().isEmpty()) type = type.getSuperclass();
        String simple = type.getSimpleName();
        var out = new StringBuilder();
        for(int i = 0; i < simple.length(); i++){
            char c = simple.charAt(i);
            if(Character.isUpperCase(c) && i > 0 && (Character.isLowerCase(simple.charAt(i - 1)) || Character.isDigit(simple.charAt(i - 1))
                || (i + 1 < simple.length() && Character.isLowerCase(simple.charAt(i + 1)) && Character.isUpperCase(simple.charAt(i - 1))))){
                out.append('-');
            }
            out.append(Character.toLowerCase(c));
        }
        return out.toString();
    }

    private static Object ref(Object value){
        return switch(value){
            case Building b -> Values.building(b.tileX(), b.tileY());
            case Tile t -> Values.building(t.x, t.y);
            case Entityc e -> Values.unit(e.id());
            case MappableContent c -> c.name;
            case Team t -> t.name;
            case IntSeq s -> s.toArray();
            case FloatSeq s -> s.toArray();
            case Seq<?> s -> s.list();
            case Point2 p -> List.of(p.x, p.y);
            case Vec2 v -> List.of(v.x, v.y);
            default -> Values.UNSUPPORTED;
        };
    }

    private static List<Field> buildFields(Class<?> type){
        return BUILD_FIELDS.computeIfAbsent(type, t -> {
            var chain = new ArrayList<Class<?>>();
            for(Class<?> c = t; c != null && c != Building.class; c = c.getSuperclass()) chain.addFirst(c);
            var out = new ArrayList<Field>();
            for(Class<?> c : chain){
                for(Field field : c.getDeclaredFields()){
                    if(Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                    field.setAccessible(true);
                    out.add(field);
                }
            }
            return List.copyOf(out);
        });
    }

    private static synchronized List<Field> ruleFields(){
        if(ruleFields == null){
            var out = new ArrayList<Field>();
            for(Field field : mindustry.game.Rules.class.getFields()){
                Class<?> type = field.getType();
                if(Modifier.isStatic(field.getModifiers())) continue;
                if(type.isPrimitive() || type == String.class || type.isEnum()) out.add(field);
            }
            out.sort(Comparator.comparing(Field::getName));
            ruleFields = List.copyOf(out);
        }
        return ruleFields;
    }

    private final class OracleSession implements Session{
        private final Sim sim;
        private final String label;
        private boolean closed;

        OracleSession(Sim sim, String scenario){
            this.sim = sim;
            this.label = name + " (" + scenario + ")";
        }

        @Override
        public long tick(){
            return sim.frames();
        }

        @Override
        public void step(){
            sim.step(1);
        }

        @Override
        public void apply(Action action){
            switch(action){
                case Place p -> {
                    Building build = sim.place(block(p.block()), p.x(), p.y(), p.rotation(), team(p.team()));
                    if(build == null) throw new IllegalStateException(p.block() + " placed no building");
                    if(!(p.config() instanceof Config.None)) configure(build, p.config());
                }
                case Remove r -> {
                    building(r.x(), r.y()).tile.setAir();
                    Vanilla.runPosted();
                }
                case Configure c -> configure(building(c.x(), c.y()), c.config());
                case Action.Items i -> {
                    Building build = building(i.x(), i.y());
                    if(build.items == null) throw new IllegalStateException(build.block.name + " has no item module");
                    build.items.add(item(i.item()), i.amount());
                }
                case Action.Liquid l -> {
                    Building build = building(l.x(), l.y());
                    if(build.liquids == null) throw new IllegalStateException(build.block.name + " has no liquid module");
                    build.liquids.add(liquid(l.liquid()), l.amount());
                }
                case CoreItems c -> {
                    var core = state.teams.get(team(c.team())).core();
                    if(core == null) throw new IllegalStateException("team " + c.team() + " has no core");
                    core.items.add(item(c.item()), c.amount());
                }
                case Spawn s -> {
                    UnitType type = content.unit(s.unit());
                    if(type == null) throw new IllegalArgumentException("no unit type '" + s.unit() + "'");
                    type.spawn(team(s.team()), s.x(), s.y(), s.rotation());
                    Vanilla.runPosted();
                }
                case Action.Rules r -> r.set().forEach(this::rule);
                case Custom c -> {
                    Handler handler = handlers.get(c.name());
                    if(handler == null) throw new UnsupportedOperationException("no handler for custom action '" + c.name() + "': OracleRunner.on(\"" + c.name() + "\", ...)");
                    handler.apply(sim, c.args());
                    Vanilla.runPosted();
                }
            }
        }

        private void configure(Building build, Config config){
            build.configureAny(switch(config){
                case Config.None n -> null;
                case Config.Int i -> i.value();
                case Config.Bool b -> b.value();
                case Config.Text t -> t.value();
                case Config.Point p -> new Point2(p.x(), p.y());
                case Config.Points p -> p.points().stream().map(q -> new Point2(q.x(), q.y())).toArray(Point2[]::new);
                case Config.Content c -> switch(c.type()){
                    case "item" -> item(c.name());
                    case "liquid" -> liquid(c.name());
                    case "block" -> block(c.name());
                    default -> {
                        UnitType unit = content.unit(c.name());
                        if(unit == null) throw new IllegalArgumentException("no unit type '" + c.name() + "'");
                        yield unit;
                    }
                };
            });
            Vanilla.runPosted();
        }

        private void rule(String rule, Object value){
            Field field = ruleFields().stream().filter(f -> f.getName().equals(rule)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no simple rule named '" + rule + "' in vanilla's Rules"));
            Class<?> type = field.getType();
            Object converted;
            if(type == boolean.class && value instanceof Boolean) converted = value;
            else if(type == int.class && value instanceof Number n) converted = n.intValue();
            else if(type == long.class && value instanceof Number n) converted = n.longValue();
            else if(type == float.class && value instanceof Number n) converted = n.floatValue();
            else if(type == double.class && value instanceof Number n) converted = n.doubleValue();
            else if(type == String.class && value instanceof String) converted = value;
            else if(type.isEnum() && value instanceof String s) converted = enumConstant(type, s);
            else throw new IllegalArgumentException("rule " + rule + " is a " + type.getSimpleName() + ", not " + Values.show(value));
            try{
                field.set(state.rules, converted);
            }catch(IllegalAccessException e){
                throw new IllegalStateException(e);
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static Object enumConstant(Class<?> type, String name){
            return Enum.valueOf((Class<? extends Enum>)type, name);
        }

        private Building building(int x, int y){
            Tile tile = world.tile(x, y);
            if(tile == null) throw new IllegalArgumentException("(" + x + ", " + y + ") is outside the world");
            if(tile.build == null) throw new IllegalArgumentException("no building at (" + x + ", " + y + ")");
            return tile.build;
        }

        private static Block block(String name){
            Block block = content.block(name);
            if(block == null) throw new IllegalArgumentException("no block '" + name + "'");
            return block;
        }

        private static Item item(String name){
            Item item = content.item(name);
            if(item == null) throw new IllegalArgumentException("no item '" + name + "'");
            return item;
        }

        private static mindustry.type.Liquid liquid(String name){
            mindustry.type.Liquid liquid = content.liquid(name);
            if(liquid == null) throw new IllegalArgumentException("no liquid '" + name + "'");
            return liquid;
        }

        private static Team team(String name){
            return Team.get(Teams.id(name));
        }

        @Override
        public Projection project(){
            var out = Projection.builder(name, sim.frames(), world.width(), world.height());
            for(Tile tile : world.tiles){
                if(tile.build != null && tile.isCenter()) out.building(building(tile.build));
            }
            Groups.unit.each(u -> out.unit(new UnitState(u.id, u.type.name, u.team.name, u.x, u.y, u.rotation, u.health)));
            out.cores();
            for(var data : state.teams.getActive()){
                if(data.core() == null) continue;
                data.core().items.each((item, amount) -> out.core(data.team.name, item.name, amount));
            }
            out.rules();
            for(Field field : ruleFields()){
                try{
                    out.rule(field.getName(), Values.of(field.get(state.rules), OracleRunner::ref));
                }catch(IllegalAccessException e){
                    throw new IllegalStateException(e);
                }
            }
            out.waves(new WaveState(state.wave, state.wavetime, state.enemies));
            return out.build();
        }

        private BuildingState building(Building b){
            Map<String, Integer> items = null;
            if(b.items != null){
                var map = new LinkedHashMap<String, Integer>();
                b.items.each((item, amount) -> map.put(item.name, amount));
                items = map;
            }
            Map<String, Float> liquids = null;
            if(b.liquids != null){
                var map = new LinkedHashMap<String, Float>();
                b.liquids.each((liquid, amount) -> {
                    if(amount != 0f) map.put(liquid.name, amount);
                });
                liquids = map;
            }
            var fields = new LinkedHashMap<String, Object>();
            for(Field field : buildFields(b.getClass())){
                Object value;
                try{
                    value = Values.of(field.get(b), OracleRunner::ref);
                }catch(IllegalAccessException e){
                    throw new IllegalStateException(e);
                }
                if(value != Values.UNSUPPORTED) fields.put(field.getName(), value);
            }
            String kind = kind(b.block);
            for(Extra extra : extras){
                if(extra.selector.equals("*") || extra.selector.equals(b.block.name) || extra.selector.equals("kind=" + kind)){
                    Object value = Values.of(extra.reader.read(b), OracleRunner::ref);
                    if(value == Values.UNSUPPORTED) throw new IllegalArgumentException("field " + extra.field + " of " + b.block.name + ": cannot project its value");
                    fields.put(extra.field, value);
                }
            }
            return new BuildingState(b.tileX(), b.tileY(), b.block.name, kind, b.team.name, b.rotation, b.health, b.enabled,
                items, liquids, b.power == null ? null : new PowerState(b.power.status), fields);
        }

        @Override
        public void close(){
            synchronized(OracleRunner.class){
                if(closed) return;
                closed = true;
                if(current == this) current = null;
            }
        }
    }
}
