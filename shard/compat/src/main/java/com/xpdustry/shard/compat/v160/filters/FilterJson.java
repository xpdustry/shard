package com.xpdustry.shard.compat.v160.filters;

import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.rules.*;
import com.xpdustry.shard.content.*;

import java.util.*;
import java.util.function.*;

/**
 * The {@code genfilters} tag as vanilla reads it: {@code JsonIO.read(Seq.class, json)}, Arc's {@code Json} with a
 * class tag per filter type ({@code JsonIO.java:342}), unknown fields ignored, block names through the save fallbacks.
 *
 * <p>Two kinds of bad input: what makes Arc's reader throw ({@link Invalid}), after which vanilla falls back to the
 * default filters; and what it reads into something {@code World.loadMap} then fails on (an entry without a class, a
 * null list), which Shard refuses with an {@link MsavException}. Objects where vanilla expects a plain value are refused
 * too: Arc's object handling there depends on classes Shard doesn't model.
 */
final class FilterJson{
    /** Arc's reader threw: vanilla uses the default filters instead. */
    static final class Invalid extends RuntimeException{
        Invalid(String message){
            super(message, null, false, false);
        }
    }

    enum Type{FLOAT, INT, BOOL, BLOCK, STRING, DROPS}

    record Field(String name, Type type, Object initial){
    }

    /** A filter class: its tag, its fields with vanilla's initial values (block names), and a constructor from field values. */
    record Kind(String className, List<Field> fields, Function<Values, GenerateFilter> create){
        /** {@code Strings.camelize(simpleName.replace("Filter", ""))}. */
        String tag(){
            String name = className.replace("Filter", "");
            return Character.toLowerCase(name.charAt(0)) + name.substring(1);
        }
    }

    /** Field values by name. */
    record Values(Map<String, Object> values){
        float f(String name){
            return (Float)values.get(name);
        }

        int i(String name){
            return (Integer)values.get(name);
        }

        boolean b(String name){
            return (Boolean)values.get(name);
        }

        BlockDef block(String name){
            return (BlockDef)values.get(name);
        }

        String s(String name){
            return (String)values.get(name);
        }

        @SuppressWarnings("unchecked")
        List<RandomItemFilter.Drop> drops(String name){
            return (List<RandomItemFilter.Drop>)values.get(name);
        }
    }

    private static Field f(String name, float initial){
        return new Field(name, Type.FLOAT, initial);
    }

    private static Field i(String name, int initial){
        return new Field(name, Type.INT, initial);
    }

    private static Field b(String name, boolean initial){
        return new Field(name, Type.BOOL, initial);
    }

    private static Field block(String name, String initial){
        return new Field(name, Type.BLOCK, initial);
    }

    /** {@code Maps.allFilterTypes}, in its order, then {@code RandomItemFilter}, which has no tag. */
    static final List<Kind> KINDS = List.of(
        new Kind("NoiseFilter", List.of(f("scl", 40), f("threshold", 0.5f), f("octaves", 3f), f("falloff", 0.5f), f("tilt", 0f),
            block("floor", "stone"), block("block", "stone-wall"), block("target", "air")),
            v -> new NoiseFilter(v.f("scl"), v.f("threshold"), v.f("octaves"), v.f("falloff"), v.f("tilt"), v.block("floor"),
                v.block("block"), v.block("target"))),
        new Kind("ScatterFilter", List.of(f("chance", 0.013f), block("flooronto", "air"), block("floor", "air"), block("block", "air")),
            v -> new ScatterFilter(v.f("chance"), v.block("flooronto"), v.block("floor"), v.block("block"))),
        new Kind("TerrainFilter", List.of(f("scl", 40), f("threshold", 0.9f), f("octaves", 3f), f("falloff", 0.5f), f("magnitude", 1f),
            f("circleScl", 2.1f), f("tilt", 0f), block("floor", "air"), block("block", "stone-wall")),
            v -> new TerrainFilter(v.f("scl"), v.f("threshold"), v.f("octaves"), v.f("falloff"), v.f("magnitude"), v.f("circleScl"),
                v.f("tilt"), v.block("floor"), v.block("block"))),
        new Kind("DistortFilter", List.of(f("scl", 40), f("mag", 5)), v -> new DistortFilter(v.f("scl"), v.f("mag"))),
        new Kind("RiverNoiseFilter", List.of(f("scl", 40), f("threshold", 0f), f("threshold2", 0.1f), f("octaves", 1), f("falloff", 0.5f),
            block("floor", "shallow-water"), block("floor2", "deep-water"), block("block", "sand-wall"), block("target", "air")),
            v -> new RiverNoiseFilter(v.f("scl"), v.f("threshold"), v.f("threshold2"), v.f("octaves"), v.f("falloff"), v.block("floor"),
                v.block("floor2"), v.block("block"), v.block("target"))),
        new Kind("OreFilter", List.of(f("scl", 23), f("threshold", 0.81f), f("octaves", 2f), f("falloff", 0.3f), f("tilt", 0f),
            block("ore", "ore-copper"), block("target", "air")),
            v -> new OreFilter(v.f("scl"), v.f("threshold"), v.f("octaves"), v.f("falloff"), v.f("tilt"), v.block("ore"), v.block("target"))),
        new Kind("OreMedianFilter", List.of(f("radius", 2), f("percentile", 0.5f)), v -> new OreMedianFilter(v.f("radius"), v.f("percentile"))),
        new Kind("MedianFilter", List.of(f("radius", 2), f("percentile", 0.5f)), v -> new MedianFilter(v.f("radius"), v.f("percentile"))),
        new Kind("BlendFilter", List.of(f("radius", 2f), block("block", "sand-floor"), block("floor", "sand-water"), block("ignore", "air")),
            v -> new BlendFilter(v.f("radius"), v.block("block"), v.block("floor"), v.block("ignore"))),
        new Kind("MirrorFilter", List.of(i("angle", 45), b("rotate", false), f("axisX", 0.5f), f("axisY", 0.5f)),
            v -> new MirrorFilter(v.i("angle"), v.b("rotate"), v.f("axisX"), v.f("axisY"))),
        new Kind("ClearFilter", List.of(block("target", "stone"), block("replace", "air"), block("ignore", "air")),
            v -> new ClearFilter(v.block("target"), v.block("replace"), v.block("ignore"))),
        new Kind("CoreSpawnFilter", List.of(i("amount", 1)), v -> new CoreSpawnFilter(v.i("amount"))),
        new Kind("EnemySpawnFilter", List.of(i("amount", 1)), v -> new EnemySpawnFilter(v.i("amount"))),
        new Kind("SpawnPathFilter", List.of(i("radius", 3), block("block", "air")), v -> new SpawnPathFilter(v.i("radius"), v.block("block"))),
        new Kind("LogicFilter", List.of(new Field("code", Type.STRING, null), b("loop", false)),
            v -> new LogicFilter(v.s("code"), v.b("loop"))),
        new Kind("RandomItemFilter", List.of(new Field("drops", Type.DROPS, List.of()), f("chance", 0.3f)),
            v -> new RandomItemFilter(v.drops("drops"), v.f("chance"))));

    private static final String PACKAGE = "mindustry.maps.filters.";

    private final ContentEpoch content;

    FilterJson(ContentEpoch content){
        this.content = content;
    }

    /** {@code JsonIO.read(Seq.class, json)} into filters, after vanilla's {@code io.anuke.} strip. */
    List<GenerateFilter> read(String json){
        JsonValue root;
        try{
            root = ArcJson.parse(json.replace("io.anuke.", ""));
        }catch(RuntimeException e){
            throw new Invalid("genfilters is not valid JSON: " + e.getMessage());
        }
        return switch(root.type()){
            case ARRAY -> {
                var out = new ArrayList<GenerateFilter>();
                for(JsonValue child : root.children()) out.add(filter(child));
                yield out;
            }
            case OBJECT -> {
                // Arc reads the object's fields into a Seq: a class makes it something else, Seq's own fields fill it
                if(root.getString("class", null) != null) throw new Invalid("genfilters is an object with a class, not a list");
                if(root.has("items")){
                    throw new MsavException("genfilters is an object that sets the list's own items; Shard reads only lists");
                }
                // a size past 0 exposes the null slots of the list's empty backing array, which the load then uses
                JsonValue size = root.get("size");
                if(size != null && !(size.isNumber() && size.asInt() == 0)){
                    throw new MsavException("genfilters is an object that sets the list's size to " + size + "; Shard reads only lists");
                }
                yield List.of();
            }
            case NULL -> throw new MsavException("genfilters is null, which vanilla's World.loadMap fails on");
            default -> throw new Invalid("genfilters is not a list");
        };
    }

    private GenerateFilter filter(JsonValue value){
        if(!value.isObject()){
            throw new MsavException("a genfilters entry is " + value + ", not a filter: vanilla's World.loadMap fails on it");
        }
        String className = value.getString("class", null);
        if(className == null) throw new MsavException("a genfilters entry has no class: vanilla's World.loadMap fails on it");
        Kind kind = kind(className);
        var values = new LinkedHashMap<String, Object>();
        for(Field field : kind.fields()){
            values.put(field.name(), field.type() == Type.BLOCK ? block((String)field.initial()) : field.initial());
        }
        List<String> names = value.names();
        List<JsonValue> children = value.children();
        for(int i = 0; i < children.size(); i++){
            String name = names.get(i).replace(" ", "_");
            if(name.equals("seed")){
                // GenerateFilter.seed: read, then replaced by randomize()
                read(Type.INT, children.get(i), kind, name);
                continue;
            }
            for(Field field : kind.fields()){
                if(field.name().equals(name)) values.put(name, read(field.type(), children.get(i), kind, name));
            }
        }
        return kind.create().apply(new Values(values));
    }

    /** {@code Json.resolveClass}: a tag, or a class name; anything else makes Arc throw. */
    private static Kind kind(String className){
        for(Kind kind : KINDS){
            if(className.equals(PACKAGE + kind.className()) || (!kind.className().equals("RandomItemFilter") && className.equals(kind.tag()))){
                return kind;
            }
        }
        // GenerateFilter is abstract and other names aren't filters Shard knows; for missing classes and classes that
        // aren't serializable Arc throws, as Shard assumes for all of them
        throw new Invalid("unknown filter class " + className);
    }

    /** {@code Json.readValue(fieldType, elementType, value)}, with the coercions Arc applies to plain values. */
    private Object read(Type type, JsonValue value, Kind kind, String name){
        String where = kind.className() + "." + name;
        if(value.isObject() && type != Type.BLOCK){
            throw new MsavException("genfilters: " + where + " is an object; Shard reads only plain values there");
        }
        return switch(type){
            case FLOAT -> switch(value.type()){
                case DOUBLE, LONG -> value.asFloat();
                case STRING -> parseFloat(value.asString(), where);
                default -> throw new Invalid(where + " is not a number");
            };
            case INT -> switch(value.type()){
                case DOUBLE, LONG -> value.asInt();
                case STRING -> {
                    try{
                        yield Integer.valueOf(value.asString());
                    }catch(NumberFormatException e){
                        throw new Invalid(where + " is not an int");
                    }
                }
                default -> throw new Invalid(where + " is not an int");
            };
            case BOOL -> switch(value.type()){
                case BOOLEAN -> value.asBoolean();
                // numbers become strings first: Boolean.valueOf is false but for "true"
                case DOUBLE, LONG, STRING -> Boolean.valueOf(value.asString());
                default -> throw new Invalid(where + " is not a boolean");
            };
            case BLOCK -> {
                if(value.isObject()){
                    if(value.getString("class", null) != null) throw new MsavException("genfilters: " + where + " is an object with a class");
                    throw new Invalid(where + " is an object, not a block name");
                }
                if(value.isArray()) throw new Invalid(where + " is not a block name");
                // the serializer looks up null like an unknown name
                yield value.isNull() ? content.block(0) : block(value.asString());
            }
            case STRING -> switch(value.type()){
                case NULL -> null;
                case ARRAY -> throw new Invalid(where + " is not a string");
                default -> value.asString();
            };
            case DROPS -> switch(value.type()){
                case ARRAY -> {
                    var drops = new ArrayList<RandomItemFilter.Drop>();
                    for(JsonValue child : value.children()) drops.add(drop(child, where));
                    yield drops;
                }
                case NULL -> throw new MsavException("genfilters: " + where + " is null, which vanilla's World.loadMap fails on");
                default -> throw new Invalid(where + " is not a list");
            };
        };
    }

    /** Arc's float from a string: an {@code f} or {@code f,} suffix is dropped. */
    private static float parseFloat(String string, String where){
        try{
            if(string.endsWith("f")) return Float.parseFloat(string.substring(0, string.length() - 1));
            if(string.endsWith("f,")) return Float.parseFloat(string.substring(0, string.length() - 2));
            return Float.parseFloat(string);
        }catch(NumberFormatException e){
            throw new Invalid(where + " is not a number");
        }
    }

    /** The {@code Block} serializer: a name (save fallbacks second), air when unknown. */
    BlockDef block(String name){
        int id = ContentLookup.byName(content, ContentType.BLOCK, name);
        if(id < 0) id = ContentLookup.byName(content, ContentType.BLOCK, ContentLookup.BLOCK_FALLBACK.getOrDefault(name, ""));
        return content.block(Math.max(id, 0));
    }

    /** The {@code ItemStack} serializer: {@code item} by name (copper when unknown or null) and a required {@code amount}. */
    private RandomItemFilter.Drop drop(JsonValue value, String where){
        JsonValue item = value.isObject() ? value.get("item") : null;
        JsonValue amount = value.isObject() ? value.get("amount") : null;
        if(item == null || amount == null) throw new Invalid(where + " has a drop without an item or amount");
        if(!item.isValue() || !amount.isValue() || amount.isNull()) throw new Invalid(where + " has a malformed drop");
        int count;
        try{
            count = amount.asInt();
        }catch(NumberFormatException e){
            throw new Invalid(where + " has a drop whose amount is not an int");
        }
        int id = item.isNull() ? -1 : ContentLookup.byName(content, ContentType.ITEM, item.asString());
        return new RandomItemFilter.Drop(id < 0 ? content.item("copper") : content.item(id), count);
    }
}
