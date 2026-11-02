package com.xpdustry.shard.oracle;

import arc.files.*;
import arc.graphics.*;
import arc.math.geom.*;
import arc.struct.*;
import mindustry.content.TechTree.*;
import mindustry.ctype.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.world.*;

import java.lang.reflect.*;
import java.util.*;

/**
 * Turns vanilla objects into JSON-able trees ({@link LinkedHashMap}, {@link ArrayList}, strings, numbers, booleans,
 * null) that {@link Json} can write. Things with an identity become references instead of copies:
 * <pre>
 * content        {"$ref": "block:conveyor"}        unnamed content (bullets): {"$ref": "bullet:12"}
 * team           {"$team": "sharded"}
 * entity         {"$entity": "UnitEntityLegacyGamma", "id": 7}
 * building       {"$building": "conveyor", "x": 3, "y": 4}
 * tile           {"$tile": [3, 4]}
 * singleton      {"$static": "Fx.shootSmall"}      static fields of Fx, Sounds, Interp, Loadouts, ...
 * lambda         {"$lambda": "mindustry.content.Blocks", "$type": "arc.func.Prov", "arg$1": ...captured values}
 * tech node      {"$techNode": {"$ref": ...}}
 * </pre>
 * Any other object becomes {@code {"$class": ..., <field>: <value>, ...}} with every non-static field of its class chain,
 * superclass fields first, in declaration order (a shadowed field is keyed {@code Declaring.field}). An object seen
 * before under the same root becomes {@code {"$same": "<path of the first copy>"}}, which also breaks cycles.
 * Colors are {@code "#rrggbbaa"}, byte arrays {@code {"$hex": ...}}, NaN and infinities strings. Unordered
 * collections are sorted, so the output is stable across runs.
 */
public final class Views{
    /** Default depth for views of decoded state. */
    public static final int defaultDepth = 3;

    private static IdentityHashMap<Object, String> statics;
    private static final Map<Class<?>, List<Field>> fieldCache = new HashMap<>();
    private static final String[] staticHolders = {
        "mindustry.content.Fx", "mindustry.gen.Sounds", "mindustry.gen.Musics", "mindustry.content.Loadouts", "arc.math.Interp",
        "mindustry.entities.part.DrawPart$PartProgress", "mindustry.graphics.CacheLayer", "mindustry.world.meta.Attribute",
        "mindustry.world.meta.BuildVisibility", "arc.graphics.Blending", "mindustry.entities.Effect"
    };
    private static final String[] skippedPrefixes = {
        "arc.graphics.g2d.", "arc.graphics.g3d.", "arc.graphics.gl.", "mindustry.graphics.g3d.", "arc.scene.", "arc.audio.",
        "arc.graphics.Texture", "arc.graphics.Pixmap", "arc.graphics.Mesh", "arc.graphics.Camera", "arc.Settings",
        //new Rand() seeds itself from the clock: its state differs every run
        "arc.math.Rand"
    };

    /** A view of a value, {@link #defaultDepth} objects deep. */
    public static Object of(Object value){
        return of(value, defaultDepth);
    }

    public static Object of(Object value, int depth){
        return new Viewer(depth, null).view(value, 0, "$");
    }

    /**
     * The fields an object's class chain declares below {@code stopAt} (exclusive), e.g. the block-specific state of a
     * building with {@code stopAt = Building.class}.
     */
    public static Map<String, Object> fields(Object object, Class<?> stopAt, int depth){
        Viewer viewer = new Viewer(depth, object);
        viewer.seen.put(object, "$");
        Map<String, Object> out = new LinkedHashMap<>();
        viewer.putFields(out, object, stopAt, 0, "$");
        return out;
    }

    /** A full view of one root object (e.g. a content entry): the root is expanded even if it is content. */
    public static Object root(Object object, int depth){
        return new Viewer(depth, object).view(object, 0, "$");
    }

    /** The reference string of content: {@code "block:conveyor"}, or {@code "bullet:12"} for unnamed content. */
    public static String ref(Content content){
        return content.getContentType().name() + ":" + (content instanceof MappableContent m ? m.name : String.valueOf(content.id));
    }

    /** The stable name of a class: lambdas lose their {@code /0x...} address suffix. */
    public static String className(Class<?> type){
        String name = type.getName();
        int slash = name.indexOf('/');
        return slash < 0 ? name : name.substring(0, slash);
    }

    /** Non-static fields of a class chain below {@code stopAt}, superclass first, accessible. */
    static synchronized List<Field> fieldsOf(Class<?> type, Class<?> stopAt){
        List<Field> all = fieldCache.computeIfAbsent(type, t -> {
            List<Class<?>> chain = new ArrayList<>();
            for(Class<?> c = t; c != null && c != Object.class; c = c.getSuperclass()) chain.addFirst(c);
            List<Field> out = new ArrayList<>();
            for(Class<?> c : chain){
                if(isJdk(c)) continue;
                for(Field f : c.getDeclaredFields()){
                    if(Modifier.isStatic(f.getModifiers()) || f.isSynthetic() && !f.getName().startsWith("arg$")) continue;
                    try{
                        f.setAccessible(true);
                    }catch(RuntimeException e){
                        continue;
                    }
                    out.add(f);
                }
            }
            return out;
        });
        if(stopAt == null) return all;
        return all.stream().filter(f -> !f.getDeclaringClass().isAssignableFrom(stopAt)).toList();
    }

    static boolean isJdk(Class<?> type){
        String name = type.getName();
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.") || name.startsWith("sun.");
    }

    private static synchronized IdentityHashMap<Object, String> statics(){
        if(statics != null) return statics;
        statics = new IdentityHashMap<>();
        for(String holder : staticHolders){
            Class<?> type;
            try{
                type = Class.forName(holder, true, Views.class.getClassLoader());
            }catch(ClassNotFoundException e){
                continue;
            }
            String prefix = type.getSimpleName().isEmpty() ? holder : holder.substring(holder.lastIndexOf('.') + 1).replace('$', '.');
            for(Field f : type.getDeclaredFields()){
                if(!Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                try{
                    f.setAccessible(true);
                    Object value = f.get(null);
                    if(value != null && !(value instanceof Content) && !(value instanceof Color) && !value.getClass().isArray()
                    && !(value instanceof Iterable<?>)){
                        statics.putIfAbsent(value, prefix + "." + f.getName());
                    }
                }catch(ReflectiveOperationException | RuntimeException ignored){
                }
            }
        }
        return statics;
    }

    private static final class Viewer{
        final int maxDepth;
        final Object root;
        /** Objects already written under this root, and where. */
        final IdentityHashMap<Object, String> seen = new IdentityHashMap<>();

        Viewer(int maxDepth, Object root){
            this.maxDepth = maxDepth;
            this.root = root;
        }

        Object view(Object o, int depth, String path){
            switch(o){
                case null -> {
                    return null;
                }
                case String s -> {
                    return s;
                }
                case Boolean b -> {
                    return b;
                }
                case Character c -> {
                    return String.valueOf(c);
                }
                case Float f -> {
                    return f.isNaN() || f.isInfinite() ? f.toString() : f;
                }
                case Double d -> {
                    return d.isNaN() || d.isInfinite() ? d.toString() : d;
                }
                case Number n -> {
                    return n;
                }
                case Enum<?> e -> {
                    return e.name();
                }
                case Class<?> c -> {
                    return className(c);
                }
                case Color c -> {
                    return "#" + c;
                }
                case Team t -> {
                    return map("$team", t.name);
                }
                case byte[] b -> {
                    return map("$hex", Hex.of(b));
                }
                case Fi f -> {
                    return f.path();
                }
                default -> {}
            }
            if(o instanceof Content c && o != root) return map("$ref", ref(c));
            String stat = statics().get(o);
            if(stat != null && o != root) return map("$static", stat);
            switch(o){
                case Building b -> {
                    if(o != root) return map("$building", b.block == null ? null : b.block.name, "x", b.tileX(), "y", b.tileY());
                }
                case Entityc e -> {
                    if(o != root) return map("$entity", className(e.getClass()).replace("mindustry.gen.", ""), "id", e.id());
                }
                case Tile t -> {
                    return map("$tile", List.of((int)t.x, (int)t.y));
                }
                case TechNode n -> {
                    return map("$techNode", view(n.content, depth, path + ".content"));
                }
                case Vec2 v -> {
                    return List.of(view(v.x, depth, path), view(v.y, depth, path));
                }
                case Vec3 v -> {
                    return List.of(view(v.x, depth, path), view(v.y, depth, path), view(v.z, depth, path));
                }
                case Point2 p -> {
                    return List.of(p.x, p.y);
                }
                case Rect r -> {
                    return List.of(view(r.x, depth, path), view(r.y, depth, path), view(r.width, depth, path), view(r.height, depth, path));
                }
                default -> {}
            }

            Class<?> type = o.getClass();
            if(type.isHidden() || type.getName().contains("$$Lambda")) return lambda(o, depth, path);
            for(String prefix : skippedPrefixes){
                if(type.getName().startsWith(prefix)) return map("$class", className(type), "$skipped", true);
            }

            String first = seen.get(o);
            if(first != null) return map("$same", first);
            boolean container = type.isArray() || o instanceof Iterable<?> || o instanceof java.util.Map<?, ?> || isArcMap(o)
                || o instanceof IntSeq || o instanceof FloatSeq || o instanceof LongSeq || o instanceof BoolSeq || o instanceof ShortSeq
                || o instanceof ByteSeq || o instanceof IntSet || o instanceof Bits;
            if(!container && depth >= maxDepth) return map("$class", className(type), "$depth", true);
            seen.put(o, path);

            if(type.isArray()){
                int length = Array.getLength(o);
                List<Object> out = new ArrayList<>(length);
                for(int i = 0; i < length; i++) out.add(view(Array.get(o, i), depth + 1, path + "[" + i + "]"));
                return out;
            }
            //before the Iterable cases: Arc maps iterate over one reused Entry
            List<Object[]> entries = entries(o);
            if(entries != null){
                boolean ordered = o instanceof OrderedMap<?, ?> || o instanceof LinkedHashMap<?, ?> || o instanceof SortedMap<?, ?> || o instanceof ArrayMap<?, ?>;
                return mapEntries(entries, !ordered, depth, path);
            }
            switch(o){
                case IntSeq s -> {
                    return list(Arrays.copyOf(s.items, s.size));
                }
                case FloatSeq s -> {
                    List<Object> out = new ArrayList<>();
                    for(int i = 0; i < s.size; i++) out.add(view(s.items[i], depth, path));
                    return out;
                }
                case LongSeq s -> {
                    return list(Arrays.copyOf(s.items, s.size));
                }
                case BoolSeq s -> {
                    List<Object> out = new ArrayList<>();
                    for(int i = 0; i < s.size; i++) out.add(s.items[i]);
                    return out;
                }
                case ShortSeq s -> {
                    List<Object> out = new ArrayList<>();
                    for(int i = 0; i < s.size; i++) out.add(s.items[i]);
                    return out;
                }
                case ByteSeq s -> {
                    return map("$hex", Hex.of(Arrays.copyOf(s.items, s.size)));
                }
                case IntSet s -> {
                    List<Integer> out = new ArrayList<>();
                    var it = s.iterator();
                    while(it.hasNext) out.add(it.next());
                    Collections.sort(out);
                    return new ArrayList<Object>(out);
                }
                case Bits b -> {
                    List<Object> out = new ArrayList<>();
                    for(int i = b.nextSetBit(0); i >= 0; i = b.nextSetBit(i + 1)) out.add(i);
                    return out;
                }
                case OrderedSet<?> s -> {
                    return elements(new ArrayList<>(s.orderedItems().list()), false, depth, path);
                }
                case ObjectSet<?> s -> {
                    return elements(copy(s), true, depth, path);
                }
                case arc.struct.EnumSet<?> s -> {
                    return elements(new ArrayList<>(Arrays.asList(s.array).subList(0, s.size)), false, depth, path);
                }
                case Seq<?> s -> {
                    return elements(new ArrayList<>(s.list()), false, depth, path);
                }
                case arc.struct.Queue<?> q -> {
                    List<Object> items = new ArrayList<>();
                    for(int i = 0; i < q.size; i++) items.add(q.get(i));
                    return elements(items, false, depth, path);
                }
                case java.util.List<?> l -> {
                    return elements(new ArrayList<>(l), false, depth, path);
                }
                case java.util.Set<?> s -> {
                    return elements(new ArrayList<>(s), !(s instanceof SequencedSet<?>), depth, path);
                }
                case Iterable<?> i -> {
                    List<Object> items = new ArrayList<>();
                    i.forEach(items::add);
                    return elements(items, false, depth, path);
                }
                default -> {}
            }
            if(isJdk(type)) return jdk(o);

            Map<String, Object> out = map("$class", className(type));
            putFields(out, o, null, depth, path);
            return out;
        }

        void putFields(Map<String, Object> out, Object o, Class<?> stopAt, int depth, String path){
            Set<String> names = new HashSet<>();
            List<Field> fields = fieldsOf(o.getClass(), stopAt);
            for(Field f : fields){
                String key = names.add(f.getName()) ? f.getName() : f.getDeclaringClass().getSimpleName() + "." + f.getName();
                Object value;
                try{
                    value = f.get(o);
                }catch(ReflectiveOperationException | RuntimeException e){
                    out.put(key, map("$error", e.toString()));
                    continue;
                }
                out.put(key, view(value, depth + 1, path + "." + key));
            }
        }

        Object lambda(Object o, int depth, String path){
            Class<?> type = o.getClass();
            String name = type.getName();
            int cut = name.indexOf("$$Lambda");
            Map<String, Object> out = map("$lambda", cut < 0 ? className(type) : name.substring(0, cut));
            if(type.getInterfaces().length > 0) out.put("$type", type.getInterfaces()[0].getName());
            for(Field f : type.getDeclaredFields()){
                if(Modifier.isStatic(f.getModifiers())) continue;
                try{
                    f.setAccessible(true);
                    out.put(f.getName(), view(f.get(o), depth + 1, path + "." + f.getName()));
                }catch(ReflectiveOperationException | RuntimeException e){
                    out.put(f.getName(), map("$unreadable", f.getType().getName()));
                }
            }
            return out;
        }

        List<Object> elements(List<Object> items, boolean sort, int depth, String path){
            List<Object> out = new ArrayList<>(items.size());
            for(int i = 0; i < items.size(); i++) out.add(view(items.get(i), depth + 1, path + "[" + i + "]"));
            if(sort) out.sort(Comparator.comparing(String::valueOf));
            return out;
        }

        Object mapEntries(List<Object[]> entries, boolean sort, int depth, String path){
            List<Object[]> rendered = new ArrayList<>(entries.size());
            boolean stringKeys = true;
            for(Object[] e : entries){
                Object key = keyOf(e[0]);
                if(!(key instanceof String)) stringKeys = false;
                rendered.add(new Object[]{key, e[1]});
            }
            if(sort) rendered.sort(Comparator.comparing(e -> String.valueOf(e[0])));
            if(stringKeys){
                Map<String, Object> out = new LinkedHashMap<>();
                for(Object[] e : rendered) out.put((String)e[0], view(e[1], depth + 1, path + "." + e[0]));
                return out;
            }
            List<Object> out = new ArrayList<>();
            for(int i = 0; i < rendered.size(); i++){
                Object[] e = rendered.get(i);
                out.add(map("key", e[0] instanceof String ? e[0] : view(e[0], depth + 1, path + "[" + i + "].key"),
                    "value", view(e[1], depth + 1, path + "[" + i + "].value")));
            }
            return out;
        }

        /** Map keys that read well as JSON keys become strings; others stay objects. */
        Object keyOf(Object key){
            return switch(key){
                case null -> "null";
                case String s -> s;
                case Number n -> n.toString();
                case Boolean b -> b.toString();
                case Character c -> c.toString();
                case Enum<?> e -> e.name();
                case Content c -> ref(c);
                case Team t -> "team:" + t.name;
                case Class<?> c -> className(c);
                default -> key;
            };
        }
    }

    /** Other JDK objects: their toString when it is a value (no identity hash), else just the class. */
    static Object jdk(Object o){
        try{
            if(o.getClass().getMethod("toString").getDeclaringClass() != Object.class){
                String s = String.valueOf(o);
                if(!s.matches("(?s).*@[0-9a-f]{4,}.*")) return s;
            }
        }catch(ReflectiveOperationException | RuntimeException ignored){
        }
        return map("$class", className(o.getClass()));
    }

    static boolean isArcMap(Object o){
        return o instanceof ObjectMap<?, ?> || o instanceof IntMap<?> || o instanceof ObjectIntMap<?> || o instanceof ObjectFloatMap<?>
            || o instanceof IntIntMap || o instanceof IntFloatMap || o instanceof LongMap<?> || o instanceof ArrayMap<?, ?>;
    }

    /** Map entries copied out first: Arc reuses its iterators and entry objects. */
    static List<Object[]> entries(Object o){
        List<Object[]> out = new ArrayList<>();
        switch(o){
            case OrderedMap<?, ?> m -> {
                for(var e : m.entries()) out.add(new Object[]{e.key, e.value});
            }
            case ObjectMap<?, ?> m -> {
                for(var e : m.entries()) out.add(new Object[]{e.key, e.value});
            }
            case IntMap<?> m -> {
                for(var e : m.entries()) out.add(new Object[]{e.key, e.value});
            }
            case ObjectIntMap<?> m -> {
                for(var e : m.entries()) out.add(new Object[]{e.key, e.value});
            }
            case ObjectFloatMap<?> m -> {
                for(var e : m.entries()) out.add(new Object[]{e.key, e.value});
            }
            case IntIntMap m -> {
                for(var e : m.entries()) out.add(new Object[]{e.key, e.value});
            }
            case IntFloatMap m -> {
                for(var e : m.entries()) out.add(new Object[]{e.key, e.value});
            }
            case LongMap<?> m -> {
                for(var e : m.entries()) out.add(new Object[]{e.key, e.value});
            }
            case ArrayMap<?, ?> m -> {
                for(int i = 0; i < m.size; i++) out.add(new Object[]{m.keys[i], m.values[i]});
            }
            case java.util.Map<?, ?> m -> {
                for(var e : m.entrySet()) out.add(new Object[]{e.getKey(), e.getValue()});
            }
            default -> {
                return null;
            }
        }
        return out;
    }

    private static List<Object> copy(Iterable<?> items){
        List<Object> out = new ArrayList<>();
        for(Object item : items) out.add(item);
        return out;
    }

    private static List<Object> list(int[] values){
        List<Object> out = new ArrayList<>(values.length);
        for(int v : values) out.add(v);
        return out;
    }

    private static List<Object> list(long[] values){
        List<Object> out = new ArrayList<>(values.length);
        for(long v : values) out.add(v);
        return out;
    }

    /** An insertion-ordered map from alternating keys and values. */
    public static Map<String, Object> map(Object... keyValues){
        Map<String, Object> out = new LinkedHashMap<>();
        for(int i = 0; i < keyValues.length; i += 2) out.put((String)keyValues[i], keyValues[i + 1]);
        return out;
    }

    private Views(){}
}
