package com.xpdustry.shard.content.patch;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.content.patch.DataAsset.*;
import com.xpdustry.shard.content.schema.*;
import com.xpdustry.shard.content.schema.FieldType.*;

import java.lang.ref.*;
import java.util.*;

/**
 * Vanilla v160.7's {@code mod.DataPatcher} as a map's patches use it: the patches a world loads with, applied in order
 * to a copy of its base content, give that world's own {@link Epoch} ({@link Epoch#patched}). Other worlds keep theirs,
 * and the world's patch is undone when it unloads, as vanilla's {@code unapply} does, simply by dropping the epoch.
 *
 * <p>The walk is {@code DataPatcher.assign} and {@code resolve} over baked structs: dotted keys
 * ({@code block.conveyor.speed}), nested objects, list elements by index and {@code +} to append, map entries by key
 * and {@code "-"} to remove one, attributes by name. A patch edits baked values and bakes nothing again, as vanilla's
 * doesn't run {@code init()}; then each content it reached runs vanilla's {@code afterPatch}: a unit type's path type
 * and cost are derived again (UnitType.java:1298), a crafter's and a thermal generator's output flags too.
 *
 * <p>What vanilla only warns about and skips, Shard skips too ({@link Applied#warnings}): an unknown content type or
 * name, an index out of bounds, an {@code @NoPatch} field. What Shard can't do like vanilla throws an
 * {@link Unsupported}, and the map is refused: objects with a {@code type} (new bullets and effects take ids clients
 * make up), consumers, controllers, fields Shard's schema doesn't know, bullets (their {@code afterPatch} computes the
 * range again) and the kinds whose {@code afterPatch} rebuilds capacities from their plans.
 */
public final class DataPatcher{
    private static final int cacheSize = 32;
    /**
     * The epochs are held weakly: an epoch lives while a world uses it and no longer, so a server cycling through many
     * maps doesn't keep their patched content. What stays (up to {@link #cacheSize} entries) is the small rest of an
     * {@link Applied}: its kept patches and warnings.
     */
    private static final Map<String, Cached> cache = new LinkedHashMap<>(16, 0.75f, true){
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest){
            return size() > cacheSize;
        }
    };

    private record Cached(WeakReference<Epoch> epoch, List<PatchAsset> kept, List<String> warnings){
        Applied get(){
            Epoch e = epoch.get();
            return e == null ? null : new Applied(e, kept, warnings);
        }
    }

    /** How many entries the cache holds, live or not: for tests. */
    static int cached(){
        synchronized(cache){
            return cache.size();
        }
    }

    /** How many of the cache's epochs are still alive: for tests. */
    static int alive(){
        synchronized(cache){
            int n = 0;
            for(Cached c : cache.values()) if(c.epoch.get() != null) n++;
            return n;
        }
    }

    /** {@code DataPatcher.nameToType}: vanilla's type names without an underscore, lower case. */
    private static final Set<String> vanillaTypes = Set.of("item", "block", "bullet", "liquid", "status", "unit", "weather", "sector",
        "error", "planet", "team", "unitcommand", "unitstance");
    /** Kinds whose {@code afterPatch} derives fields Shard bakes and doesn't derive again. */
    private static final List<String> refusedKinds = List.of("unit-factory", "reconstructor", "unit-assembler", "build-turret");
    /** {@code ControlPathfinder.costTypes}, whose index is {@code pathCostId}. */
    private static final List<String> costTypes = List.of("cost:costGround", "cost:costHover", "cost:costLegs", "cost:costNaval");

    /** A patch Shard can't apply as vanilla does. */
    public static final class Unsupported extends RuntimeException{
        Unsupported(String message){
            super(message, null, false, false);
        }
    }

    /**
     * The result of applying a world's patches.
     *
     * @param epoch the world's content: {@code base} itself when the patches change nothing
     * @param kept the patches vanilla keeps, in order: those a {@code requiredPlanets} filter dropped are gone
     * @param warnings what vanilla would warn about, in order, each prefixed with its patch's path
     */
    public record Applied(Epoch epoch, List<PatchAsset> kept, List<String> warnings){
        public Applied{
            kept = List.copyOf(kept);
            warnings = List.copyOf(warnings);
        }
    }

    private final Epoch base;
    private final EnumMap<ContentType, Struct[]> content = new EnumMap<>(ContentType.class);
    private final Set<Long> visited = new LinkedHashSet<>();
    private final List<String> warnings = new ArrayList<>();
    private String patch = "";

    private DataPatcher(Epoch base){
        this.base = base;
        for(ContentType type : ContentType.values()) content.put(type, base.baked().of(type).toArray(new Struct[0]));
    }

    /**
     * {@link #apply(Epoch, List, String)}, cached by the base's hash, the planet and the patches, so worlds of one map
     * share one epoch.
     */
    public static Applied of(Epoch base, List<PatchAsset> patches, String planet){
        var key = new StringBuilder(base.hash()).append('\0').append(planet);
        for(PatchAsset p : patches) key.append('\0').append(p.path()).append('\0').append(p.text());
        String k = key.toString();
        synchronized(cache){
            Cached hit = cache.get(k);
            Applied live = hit == null ? null : hit.get();
            if(live != null) return live;
        }
        Applied applied = apply(base, patches, planet);
        synchronized(cache){
            Cached raced = cache.get(k);
            Applied live = raced == null ? null : raced.get();
            if(live != null) return live;
            cache.put(k, new Cached(new WeakReference<>(applied.epoch()), applied.kept(), applied.warnings()));
        }
        return applied;
    }

    /**
     * {@code DataPatcher.apply(patches, content)} on a dedicated server: each patch parsed, its {@code requiredPlanets}
     * checked against {@code planet} (the world's rules' planet, which {@code readDataPatches} reads first), its
     * {@code name} dropped, then every member assigned from the root in order; at the end, {@code afterPatch} on every
     * content a patch reached, in the order it was first reached.
     *
     * @throws Unsupported if a patch needs what Shard can't do as vanilla does, or vanilla's reader may read it otherwise
     */
    public static Applied apply(Epoch base, List<PatchAsset> patches, String planet){
        if(base.isPatched()) throw new IllegalArgumentException("patches don't stack: " + base + " is already patched");
        var patcher = new DataPatcher(base);
        var kept = new ArrayList<PatchAsset>();
        for(PatchAsset asset : patches){
            patcher.patch = asset.path();
            Object parsed;
            try{
                parsed = PatchJson.read(asset.text());
            }catch(PatchJson.ParseException e){
                throw new Unsupported(asset.path() + ": " + e.getMessage() + "; vanilla's reader may read it otherwise");
            }
            if(!(parsed instanceof PatchJson.Obj value)){
                throw new Unsupported(asset.path() + ": a patch must be a JSON object, and vanilla keeps one that isn't as an error");
            }
            if(planet != null && value.has("requiredPlanets")){
                Object required = value.get("requiredPlanets");
                value = value.without("requiredPlanets");
                if(!requiredPlanets(required).contains(planet)) continue;
            }
            kept.add(asset);
            value = value.without("name");
            for(var member : value.members()) patcher.assign(Root.ROOT, member.getKey(), member.getValue());
        }
        patcher.patch = "";
        patcher.afterPatch();
        var replaced = new EnumMap<ContentType, List<Struct>>(ContentType.class);
        patcher.content.forEach((type, structs) -> replaced.put(type, Arrays.asList(structs)));
        Epoch epoch = Epoch.patched(base, base.baked().with(replaced));
        return new Applied(epoch, kept, patcher.warnings);
    }

    private static List<String> requiredPlanets(Object required){
        if(required instanceof List<?> list){
            var out = new ArrayList<String>();
            for(Object o : list) out.add(String.valueOf(o));
            return out;
        }
        return List.of(String.valueOf(required));
    }

    // targets

    private sealed interface Target{
    }

    private enum Root implements Target{
        ROOT
    }

    /** {@code content.getNamesBy(type)}, the map a type name resolves to. */
    private record Names(ContentType type) implements Target{
    }

    /** A value inside one content: the steps from its struct (a {@link FieldSpec}, an index or a key) and its type. */
    private record At(ContentType type, int id, List<Object> steps, FieldType fieldType) implements Target{
        At then(Object step, FieldType next){
            var out = new ArrayList<>(steps);
            out.add(step);
            return new At(type, id, out, next);
        }

        String describe(Epoch base){
            var out = new StringBuilder(type.yamlName()).append('.').append(base.get(type, id).name());
            for(Object s : steps) out.append('.').append(s instanceof FieldSpec f ? f.javaName() : s);
            return out.toString();
        }
    }

    private void warn(String message){
        warnings.add(patch + ": " + message);
    }

    private Unsupported unsupported(String message){
        return new Unsupported(patch + ": " + message);
    }

    // DataPatcher.assign

    private void assign(Target object, String field, Object value){
        if(field == null || field.isEmpty()) return;
        if(field.indexOf('.') != -1){
            String[] path = field.split("\\.");
            for(int i = 0; i < path.length - 1; i++){
                Target next = resolve(object, path[i]);
                if(next == null){
                    warn("failed to resolve " + path[i]);
                    return;
                }
                object = next;
            }
            field = path[path.length - 1];
        }
        switch(object){
            case Root r -> {
                if(value instanceof PatchJson.Obj obj){
                    for(var child : obj.members()) assign(Root.ROOT, field + "." + child.getKey(), child.getValue());
                }else{
                    warn("content '" + field + "' cannot be assigned");
                }
            }
            case Names names -> assignContent(names, field, value);
            case At at -> assignIn(at, field, value);
        }
    }

    /** The {@code ObjectMap} branch on a type's name map: a content's fields, one by one. */
    private void assignContent(Names names, String name, Object value){
        if(value instanceof String s && s.equals("-")){
            throw unsupported(names.type().yamlName() + "." + name + ": \"-\" takes the content out of vanilla's name map");
        }
        ContentDef def = names.type() == ContentType.BULLET ? null : base.find(names.type(), name);
        if(!(value instanceof PatchJson.Obj obj)){
            throw unsupported(names.type().yamlName() + "." + name + ": replacing a whole content");
        }
        if(def == null){
            warn("new content must not be instantiated: " + names.type().yamlName() + "." + name);
            return;
        }
        At at = content(names.type(), def.id());
        for(var child : obj.members()) assign(at, child.getKey(), child.getValue());
    }

    private At content(ContentType type, int id){
        if(type == ContentType.BULLET){
            throw unsupported("bullet " + base.get(type, id).name() + ": its afterPatch computes its range again, which Shard doesn't yet");
        }
        visited.add((long)type.ordinal() << 32 | id);
        return new At(type, id, List.of(), null);
    }

    private void assignIn(At at, String field, Object value){
        Object node = get(at);
        switch(node){
            case List<?> list -> {
                FieldType element = element(at.fieldType());
                if(element == null) throw unsupported(at.describe(base) + " is a " + at.fieldType().name() + ", which a patch can't index");
                if(field.equals("+")){
                    var out = new ArrayList<Object>(list);
                    if(value instanceof List<?> many){
                        for(Object o : many) out.add(convert(at.describe(base) + ".+", element, o));
                    }else{
                        out.add(convert(at.describe(base) + ".+", element, value));
                    }
                    set(at, at.fieldType() instanceof SetType st ? canonicalSet(at.describe(base), st, dedupe(out)) : out);
                    return;
                }
                if(at.fieldType() instanceof SetType) throw unsupported(at.describe(base) + ": a set's elements have no order to index");
                Integer index = index(field, list.size());
                if(index == null) return;
                assignValue(at.then(index, element), value);
            }
            case Map<?, ?> map -> {
                if(FieldType.ATTRIBUTES.equals(at.fieldType())){
                    if(!(value instanceof Number n)){
                        warn("attribute value must be a number: " + value);
                        return;
                    }
                    var out = copy(map);
                    out.put(field, n.floatValue());
                    set(at, out);
                    return;
                }
                if(!(at.fieldType() instanceof MapType mt)) throw unsupported(at.describe(base) + " is a " + at.fieldType().name() + ", which a patch can't key");
                if(value instanceof String s && s.equals("-")){
                    var out = copy(map);
                    out.remove(key(at, mt, field));
                    set(at, out);
                    return;
                }
                assignValue(at.then(key(at, mt, field), mt.value()), value);
            }
            case Struct struct -> {
                FieldSpec spec = javaField(struct.kind(), field);
                if(spec == null){
                    if(field.equals("consumes") || at.type() == ContentType.UNIT && Set.of("type", "controller", "aiController").contains(field)){
                        throw unsupported(at.describe(base) + "." + field + ": vanilla rebuilds it by code Shard doesn't port");
                    }
                    throw unsupported(at.describe(base) + "." + field + ": Shard's schema has no such field of " + struct.kind().key()
                        + ", so it can't tell what vanilla does with it");
                }
                if(spec.noPatch()){
                    warn("field '" + at.describe(base) + "." + field + "' cannot be edited");
                    return;
                }
                if(spec.type() instanceof Ref ref && ref.type() == ContentType.BULLET && value instanceof PatchJson.Obj obj && !obj.has("type")){
                    // an object edits the bullet itself, which Shard holds as content of its own
                    Object name = struct.get(spec);
                    if(name == null) throw unsupported(at.describe(base) + "." + field + ": a new bullet");
                    At bullet = content(ContentType.BULLET, base.find(ContentType.BULLET, (String)name).id());
                    for(var child : obj.members()) assign(bullet, child.getKey(), child.getValue());
                    return;
                }
                assignValue(at.then(spec, spec.type()), value);
            }
            case null -> warn("failed to resolve " + at.describe(base) + "." + field);
            default -> warn("unknown field '" + field + "' for " + at.describe(base));
        }
    }

    /** {@code assignValue}: an object edits the value in place, field by field; anything else replaces it. */
    private void assignValue(At at, Object value){
        Object previous = get(at);
        FieldType type = at.fieldType();
        if(value instanceof PatchJson.Obj obj && previous != null && !obj.has("type") && !FieldType.ATTRIBUTES.equals(type)){
            if(type instanceof Ref ref && ref.type() == ContentType.BULLET){
                At bullet = content(ContentType.BULLET, base.find(ContentType.BULLET, (String)previous).id());
                for(var child : obj.members()) assign(bullet, child.getKey(), child.getValue());
                return;
            }
            if(type instanceof Ref || type instanceof Named || type instanceof Scalar || type instanceof EnumType){
                throw unsupported(at.describe(base) + ": an object for a " + type.name());
            }
            for(var child : obj.members()){
                if(child.getKey() != null) assign(at, child.getKey(), child.getValue());
            }
            return;
        }
        if(value instanceof PatchJson.Obj && type instanceof Ref ref && ref.type() != null){
            warn("new content must not be instantiated: " + at.describe(base));
            return;
        }
        set(at, convert(at.describe(base), type, value));
    }

    // DataPatcher.resolve

    private Target resolve(Target object, String field){
        return switch(object){
            case Root r -> {
                if(!vanillaTypes.contains(field)){
                    warn("invalid content type: " + field);
                    yield null;
                }
                ContentType type = ContentType.of(field);
                if(type == null) throw unsupported("content type " + field + ": Shard holds none of it");
                yield new Names(type);
            }
            case Names names -> {
                // bullets aren't MappableContent: their name map is empty
                ContentDef def = names.type() == ContentType.BULLET ? null : base.find(names.type(), field);
                if(def == null){
                    warn("no key found: '" + field + "'");
                    yield null;
                }
                yield content(names.type(), def.id());
            }
            case At at -> resolveIn(at, field);
        };
    }

    private Target resolveIn(At at, String field){
        Object node = get(at);
        return switch(node){
            case List<?> list -> {
                FieldType element = element(at.fieldType());
                if(element == null) throw unsupported(at.describe(base) + " is a " + at.fieldType().name() + ", which a patch can't index");
                if(at.fieldType() instanceof SetType) throw unsupported(at.describe(base) + ": a set's elements have no order to index");
                Integer index = index(field, list.size());
                yield index == null ? null : follow(at.then(index, element));
            }
            case Map<?, ?> map -> {
                if(!(at.fieldType() instanceof MapType mt)) throw unsupported(at.describe(base) + " is a " + at.fieldType().name() + ", which a patch can't key");
                String key = key(at, mt, field);
                if(!map.containsKey(key)){
                    warn("no key found: '" + field + "'");
                    yield null;
                }
                yield follow(at.then(key, mt.value()));
            }
            case Struct struct -> {
                FieldSpec spec = javaField(struct.kind(), field);
                if(spec == null) throw unsupported(at.describe(base) + "." + field + ": Shard's schema has no such field of " + struct.kind().key());
                if(spec.noPatch()){
                    warn("field '" + at.describe(base) + "." + field + "' cannot be edited");
                    yield null;
                }
                yield follow(at.then(spec, spec.type()));
            }
            case null -> null;
            default -> {
                warn("unknown field: '" + field + "' for " + at.describe(base));
                yield null;
            }
        };
    }

    /** A bullet reference resolves into the bullet: vanilla holds the object, Shard its name. */
    private Target follow(At at){
        if(at.fieldType() instanceof Ref ref && ref.type() == ContentType.BULLET && get(at) instanceof String name){
            return content(ContentType.BULLET, base.find(ContentType.BULLET, name).id());
        }
        return at;
    }

    private Integer index(String field, int length){
        int i;
        try{
            i = Integer.parseInt(field);
        }catch(NumberFormatException e){
            warn("invalid number for array access: '" + field + "'");
            return null;
        }
        if(i < 0 || i >= length){
            warn("number outside of array bounds: '" + field + "' (length is " + length + ")");
            return null;
        }
        return i;
    }

    private String key(At at, MapType type, String field){
        if(type.key() instanceof Ref || type.key() == Scalar.STRING || type.key() instanceof EnumType) return field;
        throw unsupported(at.describe(base) + ": map keys of type " + type.key().name());
    }

    private static FieldType element(FieldType type){
        return type instanceof ListType l ? l.element() : type instanceof SetType s ? s.element() : null;
    }

    /** The field a patch names: by its Java name, the subclass's where one hides another (Arc's {@code getFields}). */
    private static FieldSpec javaField(KindSchema kind, String javaName){
        FieldSpec found = null;
        for(FieldSpec f : kind.fields()){
            if(f.javaName().equals(javaName) && (found == null || f.owner().depth() > found.owner().depth())) found = f;
        }
        return found;
    }

    private static LinkedHashMap<String, Object> copy(Map<?, ?> map){
        var out = new LinkedHashMap<String, Object>();
        map.forEach((k, v) -> out.put((String)k, v));
        return out;
    }

    private static List<Object> dedupe(List<Object> values){
        var out = new ArrayList<Object>();
        for(Object v : values) if(!out.contains(v)) out.add(v);
        return out;
    }

    // reading and writing values

    private Object get(At at){
        Object node = content.get(at.type())[at.id()];
        for(Object step : at.steps()) node = step(node, step);
        return node;
    }

    private static Object step(Object node, Object step){
        return switch(step){
            case FieldSpec f -> ((Struct)node).get(f);
            case Integer i -> ((List<?>)node).get(i);
            case String k -> ((Map<?, ?>)node).get(k);
            default -> throw new IllegalStateException("step " + step);
        };
    }

    private void set(At at, Object value){
        Struct[] structs = content.get(at.type());
        structs[at.id()] = (Struct)with(structs[at.id()], at.steps(), 0, value);
    }

    private static Object with(Object node, List<Object> steps, int from, Object value){
        if(from == steps.size()) return value;
        Object step = steps.get(from);
        Object child = with(step(node, step), steps, from + 1, value);
        return switch(step){
            case FieldSpec f -> {
                Object[] values = ((Struct)node).values();
                values[f.slot()] = child;
                yield Struct.of(((Struct)node).kind(), values);
            }
            case Integer i -> {
                var out = new ArrayList<Object>((List<?>)node);
                out.set(i, child);
                yield out;
            }
            case String k -> {
                var out = copy((Map<?, ?>)node);
                out.put(k, child);
                yield out;
            }
            default -> throw new IllegalStateException("step " + step);
        };
    }

    /** {@code Json.readValue(type, jsonValue)} for the values Shard can hold as vanilla's parser makes them. */
    private Object convert(String at, FieldType type, Object json){
        if(json == PatchJson.NULL) throw unsupported(at + ": null, which most fields refuse and some take");
        if(json instanceof PatchJson.Obj o && o.has("type")) throw unsupported(at + ": a new " + o.get("type") + " object gets ids clients make up");
        return switch(type){
            case Scalar s -> scalar(at, s, json);
            case EnumType e -> {
                if(json instanceof String text) yield text;
                throw unsupported(at + ": " + json + " for an enum");
            }
            case Ref ref -> {
                if(!(json instanceof String name)) throw unsupported(at + ": " + json + " for a " + ref.name());
                if(ref.type() == ContentType.BULLET) throw unsupported(at + ": a bullet by name (" + name + ")");
                if(ref.type() == null) throw unsupported(at + ": a " + ref.name() + " reference");
                if(base.find(ref.type(), name) == null) throw unsupported(at + ": no " + ref.type().yamlName() + " named " + name);
                yield name;
            }
            case Named named -> {
                if(!(json instanceof String name)) throw unsupported(at + ": " + json + " for a " + named.registry());
                if(named.registry().equals("cost")) throw unsupported(at + ": vanilla's parser can't read path costs");
                yield named.registry() + ":" + name;
            }
            case ListType list -> {
                if(!(json instanceof List<?> items)) throw unsupported(at + ": " + json + " for a list");
                var out = new ArrayList<Object>(items.size());
                for(int i = 0; i < items.size(); i++) out.add(convert(at + "." + i, list.element(), items.get(i)));
                yield out;
            }
            case SetType set -> {
                if(!(json instanceof List<?> items)) throw unsupported(at + ": " + json + " for a set");
                var out = new ArrayList<Object>(items.size());
                for(int i = 0; i < items.size(); i++) out.add(convert(at + "." + i, set.element(), items.get(i)));
                yield canonicalSet(at, set, dedupe(out));
            }
            case MapType map -> {
                if(!(json instanceof PatchJson.Obj obj)) throw unsupported(at + ": " + json + " for a map");
                var out = new LinkedHashMap<String, Object>();
                for(var m : obj.members()) out.put(m.getKey(), convert(at + "." + m.getKey(), map.value(), m.getValue()));
                yield out;
            }
            case Special special -> special(at, special, json);
            case Obj o -> throw unsupported(at + ": a whole " + o.base() + " object");
        };
    }

    private Object scalar(String at, Scalar type, Object json){
        return switch(type){
            case BOOL -> {
                if(json instanceof Boolean b) yield b;
                throw unsupported(at + ": " + json + " for a boolean");
            }
            case INT -> (int)integer(at, json);
            case LONG -> integer(at, json);
            // JsonValue.asFloat: a double's (float) cast, a long's (float) conversion
            case FLOAT -> json instanceof Long l ? (Object)(float)(long)l : (Object)(float)real(at, json);
            case DOUBLE -> json instanceof Long l ? (Object)(double)(long)l : (Object)real(at, json);
            case STRING -> {
                if(json instanceof String s) yield s;
                throw unsupported(at + ": " + json + " for a string");
            }
            case COLOR -> {
                if(json instanceof String s) yield color(at, s);
                throw unsupported(at + ": " + json + " for a color");
            }
        };
    }

    /** {@code JsonValue.asLong}: a double's {@code (long)} cast. */
    private long integer(String at, Object json){
        if(json instanceof Long l) return l;
        if(json instanceof Double d) return (long)(double)d;
        throw unsupported(at + ": " + json + " for a number");
    }

    private double real(String at, Object json){
        if(json instanceof Double d) return d;
        throw unsupported(at + ": " + json + " for a number");
    }

    /** {@code Color.valueOf(hex)}: {@code rrggbb} or {@code rrggbbaa}, an optional {@code #}; as Shard's {@code #rrggbbaa}. */
    private String color(String at, String hex){
        String digits = hex.startsWith("#") ? hex.substring(1) : hex;
        if(!(digits.length() == 6 || digits.length() == 8) || !digits.chars().allMatch(c -> Character.digit(c, 16) >= 0)){
            throw unsupported(at + ": color " + hex);
        }
        return "#" + digits.toLowerCase(Locale.ROOT) + (digits.length() == 6 ? "ff" : "");
    }

    private Object special(String at, Special type, Object json){
        return switch(type.name()){
            case "stacks", "liquid-stacks" -> {
                if(!(json instanceof List<?> items)) throw unsupported(at + ": " + json + " for stacks");
                var out = new LinkedHashMap<String, Object>();
                ContentType of = type.name().startsWith("liquid") ? ContentType.LIQUID : ContentType.ITEM;
                for(Object item : items){
                    var stack = stack(at, of, item);
                    if(out.put(stack.getKey(), stack.getValue()) != null) throw unsupported(at + ": two stacks of " + stack.getKey());
                }
                yield out;
            }
            case "stack", "liquid-stack" -> {
                var stack = stack(at, type.name().startsWith("liquid") ? ContentType.LIQUID : ContentType.ITEM, json);
                yield Map.of(stack.getKey(), stack.getValue());
            }
            case "attribute" -> {
                if(json instanceof String s) yield s;
                throw unsupported(at + ": " + json + " for an attribute");
            }
            default -> throw unsupported(at + ": a " + type.name() + " value");
        };
    }

    /** {@code "copper/35"} (ContentParser.java:466) as Shard's {@code {copper: 35}}. */
    private Map.Entry<String, Object> stack(String at, ContentType type, Object json){
        if(!(json instanceof String text) || text.indexOf('/') < 0) throw unsupported(at + ": stack " + json + "; Shard reads the name/amount form only");
        String name = text.substring(0, text.indexOf('/')), amount = text.substring(text.indexOf('/') + 1);
        if(base.find(type, name) == null) throw unsupported(at + ": no " + type.yamlName() + " named " + name);
        try{
            Object value = type == ContentType.LIQUID ? (Object)Float.parseFloat(amount) : (Object)Integer.parseInt(amount);
            return Map.entry(name, value);
        }catch(NumberFormatException e){
            throw unsupported(at + ": stack amount " + amount);
        }
    }

    /** A set's canonical order: content id for references, else text ({@link SetType}). */
    private List<Object> canonicalSet(String at, SetType type, List<Object> values){
        var out = new ArrayList<>(values);
        if(type.element() instanceof Ref ref && ref.type() != null){
            out.sort(Comparator.comparingInt(v -> base.id(ref.type(), (String)v)));
        }else if(type.element() instanceof EnumType){
            throw unsupported(at + ": a set of enum constants, whose order Shard can't tell");
        }else{
            out.sort(Comparator.comparing(String::valueOf));
        }
        return out;
    }

    // afterPatch

    /** {@code afterPatch} on every content a patch reached, in the order it was first reached. */
    private void afterPatch(){
        for(long key : visited){
            ContentType type = ContentType.values()[(int)(key >>> 32)];
            int id = (int)key;
            Struct[] structs = content.get(type);
            Struct s = structs[id];
            KindSchema kind = s.kind();
            String name = type.yamlName() + "." + base.get(type, id).name();
            for(String refused : refusedKinds){
                if(kind.isA(refused)){
                    throw new Unsupported(name + ": the afterPatch of a " + refused + " derives its capacities again, which Shard doesn't yet");
                }
            }
            if(kind.isA("consume-generator") && !Objects.equals(s.get("itemDurationMultipliers"), base.get(type, id).fields().get("itemDurationMultipliers"))){
                throw new Unsupported(name + ".itemDurationMultipliers: afterPatch hands it to the efficiency consumer, which Shard doesn't yet");
            }
            Struct.Builder b = s.toBuilder();
            if(type == ContentType.UNIT) unitAfterPatch(b);
            if(kind.isA("generic-crafter")){
                // GenericCrafter.java:141
                if(b.get("outputItems") != null) b.set("hasItems", true);
                b.set("outputsLiquid", b.get("outputLiquids") != null);
                if(b.get("outputLiquids") != null) b.set("hasLiquids", true);
            }
            if(kind.isA("thermal-generator") && b.get("outputLiquid") != null){
                // ThermalGenerator.java:47
                b.set("outputsLiquid", true);
                b.set("hasLiquids", true);
            }
            structs[id] = b.build();
        }
    }

    /**
     * UnitType.java:1298: the path type and cost are reset and derived again ({@code initPathType}). Vanilla quirk kept:
     * a unit whose Java code assigned them (a value the class wouldn't derive) loses it to any patch of the unit.
     */
    private static void unitAfterPatch(Struct.Builder u){
        boolean naval = u.b("naval"), legs = u.b("allowLegStep"), hovering = u.b("hovering");
        // Pathfinder.costNaval, costLegs, costNone, costHover, costGround
        u.set("flowfieldPathType", naval ? 2 : legs ? 1 : u.b("flying") ? 4 : hovering ? 5 : 0);
        String cost = naval ? "cost:costNaval" : legs ? "cost:costLegs" : hovering ? "cost:costHover" : "cost:costGround";
        u.set("pathCost", cost);
        u.set("pathCostId", Math.max(costTypes.indexOf(cost), 0));
    }
}
