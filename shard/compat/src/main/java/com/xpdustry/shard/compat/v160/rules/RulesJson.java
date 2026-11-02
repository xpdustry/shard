package com.xpdustry.shard.compat.v160.rules;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.ContentType;
import com.xpdustry.shard.sim.objectives.*;
import com.xpdustry.shard.sim.rules.*;

import java.util.*;
import java.util.function.*;

/**
 * {@link Rules} as vanilla's {@code JsonIO} writes and reads them: the rules section of a world stream, the
 * {@code rules} tag of a map or save, and {@code setRule}'s payload.
 *
 * <p>Writing is Arc's reflective {@code Json.writeFields} on {@code new Rules()} as prototype: fields in declaration
 * order, a field omitted when it {@code equals} the prototype's, content as names, teams as ids, colours as
 * {@code rrggbbaa}. {@code teams}, {@code attributes} and {@code objectives} have no {@code equals} and are always
 * written. Sets and maps are written in id or name order; vanilla's hash order differs, which no reader sees.
 *
 * <p>Reading is Arc's {@code Json.readFields}: members in order, matched to fields by exact name, unknown names
 * ignored, and the same coercions (a quoted number is a number, a numeric value for a boolean is false). Content
 * names go through vanilla's fallbacks; unknown ones become what vanilla's serializers return (air, dagger, copper,
 * serpulo, or null). A JSON {@code null} for a field the model holds non-null is rejected where vanilla would store a
 * null and fail later.
 */
public final class RulesJson{
    private static final Rules RULES_DEFAULTS = new Rules();
    private static final TeamRule TEAM_DEFAULTS = new TeamRule();
    private static final WeatherEntry WEATHER_DEFAULTS = new WeatherEntry();

    private final ContentEpoch content;
    private final ObjectivesJson objectives;

    /** Rules of {@code content}: its names are what the JSON holds. */
    public RulesJson(ContentEpoch content){
        this.content = content;
        this.objectives = new ObjectivesJson(content);
    }

    /** The objectives, markers and stats JSON of the same content. */
    public ObjectivesJson objectives(){
        return objectives;
    }

    /** The content whose names the JSON holds. */
    public ContentEpoch content(){
        return content;
    }

    /** {@code JsonIO.write(rules)}. */
    public String write(Rules rules){
        var writer = new ArcJson.Writer();
        writeObject(writer, rules, RULES_DEFAULTS, RULE_FIELDS);
        return writer.toString();
    }

    /** The JSON of one field, as vanilla's {@code Call.setRule(name, json)} carries it; null if there is no such field. */
    public String writeField(Rules rules, String name){
        Field<Rules, ?> field = RULE_FIELD_NAMES.get(name);
        if(field == null) return null;
        var writer = new ArcJson.Writer();
        field.write(this, writer, rules);
        return writer.toString();
    }

    /** {@code JsonIO.read(Rules.class, json)}: fresh rules with {@code json}'s fields set. */
    public Rules read(String json){
        return read(new Rules(), json);
    }

    /** {@code JsonIO.read(base, json)}: sets {@code json}'s fields on {@code base} and returns it. */
    public Rules read(Rules base, String json){
        JsonValue value = ArcJson.parse(json.replace("io.anuke.", ""));
        readObject(base, value, RULE_FIELD_NAMES);
        return base;
    }

    /** Vanilla's {@code setRule} receiver: reads one field's JSON into {@code rules}; false if there is no such field. */
    public boolean readField(Rules rules, String name, String json){
        Field<Rules, ?> field = RULE_FIELD_NAMES.get(name);
        if(field == null) return false;
        field.read(this, rules, ArcJson.parse(json));
        return true;
    }

    /** A team's rules as vanilla writes them inside {@code teams}: fields that differ from {@code new TeamRule()}. */
    public String write(TeamRule rule){
        var writer = new ArcJson.Writer();
        writeObject(writer, rule, TEAM_DEFAULTS, TEAM_FIELDS);
        return writer.toString();
    }

    /** One team's rules from JSON, starting from {@code new TeamRule()}, as vanilla reads each {@code teams} entry. */
    public TeamRule readTeamRule(String json){
        var rule = new TeamRule();
        readObject(rule, ArcJson.parse(json), TEAM_FIELD_NAMES);
        return rule;
    }

    /** Spawn groups as the {@code spawns} field holds them. */
    public String writeSpawns(List<SpawnGroup> spawns){
        var writer = new ArcJson.Writer();
        list(SPAWN_GROUP).write(this, writer, spawns);
        return writer.toString();
    }

    /** Spawn groups from a JSON array, as the {@code spawns} field reads them. */
    public List<SpawnGroup> readSpawns(String json){
        return list(SPAWN_GROUP).read(this, ArcJson.parse(json));
    }

    private <O> void writeObject(ArcJson.Writer writer, O object, O defaults, List<Field<O, ?>> fields){
        writer.object();
        for(Field<O, ?> field : fields){
            if(field.isDefault(object, defaults)) continue;
            writer.name(field.name);
            field.write(this, writer, object);
        }
        writer.pop();
    }

    private <O> void readObject(O object, JsonValue json, Map<String, Field<O, ?>> fields){
        if(!json.isObject()) throw new IllegalArgumentException("expected an object, got " + json.type());
        for(int i = 0; i < json.size(); i++){
            Field<O, ?> field = fields.get(json.names().get(i).replace(' ', '_'));
            if(field != null) field.read(this, object, json.children().get(i));
        }
    }

    // region value codecs

    /** Arc's coercions for one value of a field's type, for {@link ObjectivesJson}. */
    static boolean readBool(JsonValue value){
        return BOOL.read(null, value);
    }

    static float readFloat(JsonValue value){
        return FLOAT.read(null, value);
    }

    static int readInt(JsonValue value){
        return INT.read(null, value);
    }

    /** {@code byte}: as an int, narrowed. */
    static byte readByte(JsonValue value){
        return (byte)(int)INT.read(null, value);
    }

    static String readString(JsonValue value){
        return STRING.read(null, value);
    }

    static Color readColor(JsonValue value){
        return COLOR.read(null, value);
    }

    static int readTeam(JsonValue value){
        return TEAM.read(null, value);
    }

    /** How one field type is written and read. */
    private interface Codec<T>{
        void write(RulesJson json, ArcJson.Writer writer, T value);

        T read(RulesJson json, JsonValue value);
    }

    private static final Codec<Boolean> BOOL = new Codec<>(){
        @Override
        public void write(RulesJson json, ArcJson.Writer writer, Boolean value){
            writer.value(value);
        }

        @Override
        public Boolean read(RulesJson json, JsonValue value){
            return switch(value.type()){
                case BOOLEAN -> value.asBoolean();
                // Arc reads a number into a boolean field through Boolean.valueOf(text), which is always false.
                case DOUBLE, LONG -> false;
                case STRING -> Boolean.parseBoolean(value.asString());
                default -> throw unconvertible(value, "boolean");
            };
        }
    };

    private static final Codec<Float> FLOAT = new Codec<>(){
        @Override
        public void write(RulesJson json, ArcJson.Writer writer, Float value){
            writer.value(value);
        }

        @Override
        public Float read(RulesJson json, JsonValue value){
            return switch(value.type()){
                case DOUBLE, LONG -> value.asFloat();
                case STRING -> {
                    // Arc accepts a trailing "f" (and "f,") on quoted floats.
                    String text = value.asString();
                    if(text.endsWith("f,")) text = text.substring(0, text.length() - 2);
                    else if(text.endsWith("f")) text = text.substring(0, text.length() - 1);
                    try{
                        yield Float.valueOf(text);
                    }catch(NumberFormatException e){
                        throw unconvertible(value, "float");
                    }
                }
                default -> throw unconvertible(value, "float");
            };
        }
    };

    private static final Codec<Integer> INT = new Codec<>(){
        @Override
        public void write(RulesJson json, ArcJson.Writer writer, Integer value){
            writer.value(value);
        }

        @Override
        public Integer read(RulesJson json, JsonValue value){
            return switch(value.type()){
                case DOUBLE, LONG -> value.asInt();
                case STRING -> {
                    try{
                        yield Integer.valueOf(value.asString());
                    }catch(NumberFormatException e){
                        throw unconvertible(value, "int");
                    }
                }
                default -> throw unconvertible(value, "int");
            };
        }
    };

    /** A nullable string: numbers and booleans read as their text, as Arc does. */
    private static final Codec<String> STRING = new Codec<>(){
        @Override
        public void write(RulesJson json, ArcJson.Writer writer, String value){
            writer.value(value);
        }

        @Override
        public String read(RulesJson json, JsonValue value){
            return switch(value.type()){
                case NULL -> null;
                case STRING, DOUBLE, LONG, BOOLEAN -> value.asString();
                // Arc reads a wrapped string ({class:java.lang.String,value:...}) through its "value" member.
                case OBJECT -> value.getString("value", null);
                case ARRAY -> throw unconvertible(value, "string");
            };
        }
    };

    /** Vanilla's {@code MusicContainer} serializer: the name, and "" for anything not a string. */
    private static final Codec<String> MUSIC = new Codec<>(){
        @Override
        public void write(RulesJson json, ArcJson.Writer writer, String value){
            writer.value(value);
        }

        @Override
        public String read(RulesJson json, JsonValue value){
            return value.isString() ? value.asString() : "";
        }
    };

    /** A string set element: null is rejected, as Arc's {@code ObjectSet} rejects null keys. */
    private static final Codec<String> SET_STRING = new Codec<>(){
        @Override
        public void write(RulesJson json, ArcJson.Writer writer, String value){
            writer.value(value);
        }

        @Override
        public String read(RulesJson json, JsonValue value){
            String out = STRING.read(json, value);
            if(out == null) throw new IllegalArgumentException("sets cannot hold null");
            return out;
        }
    };

    /** Vanilla's {@code Team} serializer: the id, masked to a byte. */
    private static final Codec<Integer> TEAM = new Codec<>(){
        @Override
        public void write(RulesJson json, ArcJson.Writer writer, Integer value){
            writer.value(value);
        }

        @Override
        public Integer read(RulesJson json, JsonValue value){
            return INT.read(json, value) & 0xff;
        }
    };

    /** Vanilla's {@code Color} serializer: {@code rrggbbaa}. */
    private static final Codec<Color> COLOR = new Codec<>(){
        @Override
        public void write(RulesJson json, ArcJson.Writer writer, Color value){
            writer.value(value.hex());
        }

        @Override
        public Color read(RulesJson json, JsonValue value){
            if(value.isString()) return Color.valueOf(value.asString());
            // Vanilla quirk: a hex colour of only digits is written unquoted and reads back as a number, and Arc then
            // reads the colour's fields from it, of which a number has none: transparent black.
            if(!value.isObject()) return new Color(0f, 0f, 0f, 0f);
            return new Color(value.getFloat("r", 0f), value.getFloat("g", 0f), value.getFloat("b", 0f), value.getFloat("a", 0f));
        }
    };

    /** Vanilla's {@code Block} serializer: unknown names fall back to old names, then air. */
    private static final Codec<Integer> BLOCK = new Codec<>(){
        @Override
        public void write(RulesJson json, ArcJson.Writer writer, Integer value){
            writer.value(json.name(ContentType.BLOCK, value));
        }

        @Override
        public Integer read(RulesJson json, JsonValue value){
            return json.block(value.asString());
        }
    };

    /** Vanilla's {@code UnitType} serializer: null or unknown names read as dagger. */
    private static final Codec<Integer> UNIT = new Codec<>(){
        @Override
        public void write(RulesJson json, ArcJson.Writer writer, Integer value){
            writer.value(json.name(ContentType.UNIT, value));
        }

        @Override
        public Integer read(RulesJson json, JsonValue value){
            int id = json.byName(ContentType.UNIT, value.asString());
            return id < 0 ? VanillaIds.DAGGER : id;
        }
    };

    /** Vanilla's {@code Item} serializer: null or unknown names read as copper. */
    private static final Codec<Integer> ITEM = new Codec<>(){
        @Override
        public void write(RulesJson json, ArcJson.Writer writer, Integer value){
            writer.value(json.name(ContentType.ITEM, value));
        }

        @Override
        public Integer read(RulesJson json, JsonValue value){
            int id = json.byName(ContentType.ITEM, value.asString());
            return id < 0 ? VanillaIds.COPPER : id;
        }
    };

    /** Vanilla's {@code Weather} serializer: -1 is null, and unknown names read as null. */
    private static final Codec<Integer> WEATHER = new Codec<>(){
        @Override
        public void write(RulesJson json, ArcJson.Writer writer, Integer value){
            writer.value(value < 0 ? null : json.name(ContentType.WEATHER, value));
        }

        @Override
        public Integer read(RulesJson json, JsonValue value){
            return json.byName(ContentType.WEATHER, value.asString());
        }
    };

    /** Vanilla's {@code Planet} serializer: -1 is null, and unknown names read as serpulo. */
    private static final Codec<Integer> PLANET = new Codec<>(){
        @Override
        public void write(RulesJson json, ArcJson.Writer writer, Integer value){
            writer.value(value < 0 ? null : json.content.planets().get(value));
        }

        @Override
        public Integer read(RulesJson json, JsonValue value){
            String name = value.asString();
            if(name == null) return -1;
            int id = json.content.planets().indexOf(name);
            return id < 0 ? VanillaIds.SERPULO : id;
        }
    };

    /** Vanilla's {@code ItemStack} serializer: {@code {item, amount}}, both required. */
    private static final Codec<ItemStack> ITEM_STACK = new Codec<>(){
        @Override
        public void write(RulesJson json, ArcJson.Writer writer, ItemStack value){
            writer.object().name("item");
            ITEM.write(json, writer, value.item());
            writer.name("amount").value(value.amount()).pop();
        }

        @Override
        public ItemStack read(RulesJson json, JsonValue value){
            JsonValue item = value.get("item"), amount = value.get("amount");
            if(item == null || amount == null) throw new IllegalArgumentException("item stack needs item and amount: " + value);
            return new ItemStack(ITEM.read(json, item), amount.asInt());
        }
    };

    /** A non-null {@code Seq}: a JSON array. */
    private static <T> Codec<List<T>> list(Codec<T> element){
        return new Codec<>(){
            @Override
            public void write(RulesJson json, ArcJson.Writer writer, List<T> value){
                writer.array();
                for(T item : value) element.write(json, writer, item);
                writer.pop();
            }

            @Override
            public List<T> read(RulesJson json, JsonValue value){
                if(!value.isArray()) throw unconvertible(value, "array");
                var out = new ArrayList<T>(value.size());
                for(JsonValue child : value.children()) out.add(element.read(json, child));
                return out;
            }
        };
    }

    /** A nullable {@code Seq}. */
    private static <T> Codec<List<T>> nullableList(Codec<T> element){
        Codec<List<T>> list = list(element);
        return new Codec<>(){
            @Override
            public void write(RulesJson json, ArcJson.Writer writer, List<T> value){
                if(value == null){
                    writer.value(null);
                }else{
                    list.write(json, writer, value);
                }
            }

            @Override
            public List<T> read(RulesJson json, JsonValue value){
                return value.isNull() ? null : list.read(json, value);
            }
        };
    }

    /** An {@code ObjectSet}: written {@code {values:[...]}}; read from that or from a plain array. */
    private static <T extends Comparable<T>> Codec<TreeSet<T>> set(Codec<T> element){
        return new Codec<>(){
            @Override
            public void write(RulesJson json, ArcJson.Writer writer, TreeSet<T> value){
                writer.object().name("values").array();
                for(T item : value) element.write(json, writer, item);
                writer.pop().pop();
            }

            @Override
            public TreeSet<T> read(RulesJson json, JsonValue value){
                JsonValue values = switch(value.type()){
                    case ARRAY -> value;
                    case OBJECT -> value.get("values");
                    default -> throw unconvertible(value, "set");
                };
                var out = new TreeSet<T>();
                if(values != null) for(JsonValue child : values.children()) out.add(element.read(json, child));
                return out;
            }
        };
    }

    /** {@code blockLimits}, an {@code ObjectIntMap<Block>}: block names to counts. */
    private static final Codec<TreeMap<Integer, Integer>> BLOCK_LIMITS = new Codec<>(){
        @Override
        public void write(RulesJson json, ArcJson.Writer writer, TreeMap<Integer, Integer> value){
            writer.object();
            value.forEach((block, limit) -> writer.name(json.name(ContentType.BLOCK, block)).value(limit));
            writer.pop();
        }

        @Override
        public TreeMap<Integer, Integer> read(RulesJson json, JsonValue value){
            if(!value.isObject()) throw unconvertible(value, "object");
            var out = new TreeMap<Integer, Integer>();
            for(int i = 0; i < value.size(); i++) out.put(json.block(value.names().get(i)), value.children().get(i).asInt());
            return out;
        }
    };

    /**
     * {@code tags}, a {@code StringMap}. Its field has no element type, so Arc wraps each value with its class:
     * {@code {key:{class:java.lang.String,value:...}}}. Plain values read too; nulls are dropped.
     */
    private static final Codec<TreeMap<String, String>> TAGS = new Codec<>(){
        @Override
        public void write(RulesJson json, ArcJson.Writer writer, TreeMap<String, String> value){
            writer.object();
            value.forEach((key, tag) -> writer.name(key).object().name("class").value("java.lang.String").name("value").value(tag).pop());
            writer.pop();
        }

        @Override
        public TreeMap<String, String> read(RulesJson json, JsonValue value){
            if(!value.isObject()) throw unconvertible(value, "object");
            var out = new TreeMap<String, String>();
            for(int i = 0; i < value.size(); i++){
                String tag = STRING.read(json, value.children().get(i));
                if(tag != null) out.put(value.names().get(i), tag);
            }
            return out;
        }
    };

    /** Raw JSON kept as text (objectives, planet background); null stays null. */
    private static final Codec<String> RAW = new Codec<>(){
        @Override
        public void write(RulesJson json, ArcJson.Writer writer, String value){
            if(value == null){
                writer.value(null);
            }else{
                writer.tree(ArcJson.parse(value));
            }
        }

        @Override
        public String read(RulesJson json, JsonValue value){
            return value.isNull() ? null : ArcJson.write(value);
        }
    };

    /** {@code objectives}: the {@code MapObjectives} serializer ({@link ObjectivesJson}). */
    private static final Codec<MapObjectives> OBJECTIVES = new Codec<>(){
        @Override
        public void write(RulesJson json, ArcJson.Writer writer, MapObjectives value){
            json.objectives.writeObjectives(writer, value);
        }

        @Override
        public MapObjectives read(RulesJson json, JsonValue value){
            return json.objectives.readObjectives(value);
        }
    };

    private static final Codec<TeamRules> TEAMS = new Codec<>(){
        @Override
        public void write(RulesJson json, ArcJson.Writer writer, TeamRules value){
            writer.object();
            for(int team = 0; team < VanillaIds.TEAMS; team++){
                TeamRule rule = value.peek(team);
                if(rule == null) continue;
                writer.name(Integer.toString(team));
                json.writeObject(writer, rule, TEAM_DEFAULTS, TEAM_FIELDS);
            }
            writer.pop();
        }

        @Override
        public TeamRules read(RulesJson json, JsonValue value){
            var out = new TeamRules();
            for(int i = 0; i < value.size(); i++){
                String name = value.names().isEmpty() ? null : value.names().get(i);
                int team;
                try{
                    team = Integer.parseInt(name);
                }catch(NumberFormatException e){
                    throw new IllegalArgumentException("team rules need team ids as names, got " + name);
                }
                if(team < 0 || team >= VanillaIds.TEAMS) throw new IllegalArgumentException("no team " + team);
                JsonValue child = value.children().get(i);
                if(child.isNull()){
                    out.set(team, null);
                }else{
                    var rule = new TeamRule();
                    json.readObject(rule, child, TEAM_FIELD_NAMES);
                    out.set(team, rule);
                }
            }
            return out;
        }
    };

    private static final Codec<Attributes> ATTRIBUTES = new Codec<>(){
        @Override
        public void write(RulesJson json, ArcJson.Writer writer, Attributes value){
            writer.object();
            for(int i = 0; i < Attributes.NAMES.size(); i++){
                if(value.get(i) != 0) writer.name(Attributes.NAMES.get(i)).value(value.get(i));
            }
            writer.pop();
        }

        @Override
        public Attributes read(RulesJson json, JsonValue value){
            var out = new Attributes();
            for(int i = 0; i < Attributes.NAMES.size(); i++) out.set(i, value.getFloat(Attributes.NAMES.get(i), 0f));
            return out;
        }
    };

    /** Vanilla's {@code SpawnGroup.write}/{@code read}, which are hand-written with their own names and defaults. */
    private static final Codec<SpawnGroup> SPAWN_GROUP = new Codec<>(){
        @Override
        public void write(RulesJson json, ArcJson.Writer writer, SpawnGroup group){
            writer.object();
            writer.name("type").value(json.name(ContentType.UNIT, group.type));
            if(group.begin != 0) writer.name("begin").value(group.begin);
            if(group.end != SpawnGroup.NEVER) writer.name("end").value(group.end);
            if(group.spacing != 1) writer.name("spacing").value(group.spacing);
            if(group.max != 40) writer.name("max").value(group.max);
            if(group.unitScaling != SpawnGroup.NEVER) writer.name("scaling").value(group.unitScaling);
            if(group.shields != 0) writer.name("shields").value(group.shields);
            if(group.shieldScaling != 0) writer.name("shieldScaling").value(group.shieldScaling);
            if(group.unitAmount != 1) writer.name("amount").value(group.unitAmount);
            if(group.effect >= 0) writer.name("effect").value(json.name(ContentType.STATUS, group.effect));
            if(group.spawn != -1) writer.name("spawn").value(group.spawn);
            if(group.payloads != null && !group.payloads.isEmpty()){
                writer.name("payloads").array();
                for(int unit : group.payloads) writer.value(json.name(ContentType.UNIT, unit));
                writer.pop();
            }
            if(group.items != null && group.items.amount() > 0){
                writer.name("items");
                ITEM_STACK.write(json, writer, group.items);
            }
            if(group.team >= 0) writer.name("team").value(group.team);
            writer.pop();
        }

        @Override
        public SpawnGroup read(RulesJson json, JsonValue data){
            var group = new SpawnGroup();
            String type = data.getString("type", "dagger");
            int unit = json.content.id(ContentType.UNIT, ContentLookup.UNIT_RENAMES.getOrDefault(type, type));
            // internal unit types (block, turret-unit-build-tower) are replaced with dagger
            group.type = unit < 0 || json.content.unit(unit).internal() ? VanillaIds.DAGGER : unit;
            group.begin = data.getInt("begin", 0);
            group.end = data.getInt("end", SpawnGroup.NEVER);
            group.spacing = data.getInt("spacing", 1);
            group.max = data.getInt("max", 40);
            group.unitScaling = data.getFloat("scaling", SpawnGroup.NEVER);
            group.shields = data.getFloat("shields", 0);
            group.shieldScaling = data.getFloat("shieldScaling", 0);
            group.unitAmount = data.getInt("amount", 1);
            group.spawn = data.getInt("spawn", -1);
            JsonValue payloads = data.get("payloads");
            if(payloads != null){
                if(!payloads.isArray()) throw unconvertible(payloads, "array");
                group.payloads = new ArrayList<>();
                // SpawnGroup.java:145: names through content.unit, unknown ones dropped.
                for(JsonValue name : payloads.children()){
                    int id = json.byName(ContentType.UNIT, name.asString());
                    if(id >= 0) group.payloads.add(id);
                }
            }
            JsonValue items = data.get("items");
            if(items != null) group.items = ITEM_STACK.read(json, items);
            JsonValue team = data.get("team");
            if(team != null) group.team = team.asInt() & 0xff;
            JsonValue effect = data.get("effect");
            if(effect != null && effect.isNumber() && effect.asInt() == 8){
                // Vanilla quirk: 8 was the boss effect's numeric id; other numbers mean none.
                group.effect = VanillaIds.STATUS_BOSS;
            }else{
                group.effect = json.content.id(ContentType.STATUS, effect != null && effect.isString() ? effect.asString() : "none");
            }
            return group;
        }
    };

    /** A weather entry, written reflectively against {@code new WeatherEntry()}. */
    private static final Codec<WeatherEntry> WEATHER_ENTRY = new Codec<>(){
        @Override
        public void write(RulesJson json, ArcJson.Writer writer, WeatherEntry value){
            json.writeObject(writer, value, WEATHER_DEFAULTS, WEATHER_FIELDS);
        }

        @Override
        public WeatherEntry read(RulesJson json, JsonValue value){
            var entry = new WeatherEntry();
            json.readObject(entry, value, WEATHER_FIELD_NAMES);
            return entry;
        }
    };

    // endregion
    // region fields

    /**
     * One reflected field: its JSON name, accessors and codec. {@code compared} is false for fields whose vanilla type
     * has no {@code equals}, which Arc therefore always writes.
     */
    private record Field<O, T>(String name, Function<O, T> get, BiConsumer<O, T> set, Codec<T> codec, boolean compared, boolean nullable){
        boolean isDefault(O object, O defaults){
            return compared && Objects.equals(get.apply(object), get.apply(defaults));
        }

        void write(RulesJson json, ArcJson.Writer writer, O object){
            T value = get.apply(object);
            if(value == null){
                writer.value(null);
            }else{
                codec.write(json, writer, value);
            }
        }

        void read(RulesJson json, O object, JsonValue value){
            T read = value.isNull() && nullable ? null : codec.read(json, value);
            if(read == null && !nullable) throw new IllegalArgumentException(name + " cannot be null");
            set.accept(object, read);
        }
    }

    private static <O, T> Field<O, T> field(String name, Function<O, T> get, BiConsumer<O, T> set, Codec<T> codec){
        return new Field<>(name, get, set, codec, true, false);
    }

    private static <O, T> Field<O, T> nullable(String name, Function<O, T> get, BiConsumer<O, T> set, Codec<T> codec){
        return new Field<>(name, get, set, codec, true, true);
    }

    private static <O, T> Field<O, T> always(String name, Function<O, T> get, BiConsumer<O, T> set, Codec<T> codec){
        return new Field<>(name, get, set, codec, false, false);
    }

    private static final List<Field<Rules, ?>> RULE_FIELDS = List.of(
        field("allowEditRules", r -> r.allowEditRules, (r, v) -> r.allowEditRules = v, BOOL),
        field("infiniteResources", r -> r.infiniteResources, (r, v) -> r.infiniteResources = v, BOOL),
        field("coreBuildAndConfig", r -> r.coreBuildAndConfig, (r, v) -> r.coreBuildAndConfig = v, BOOL),
        always("teams", r -> r.teams, (r, v) -> r.teams = v, TEAMS),
        field("waveTimer", r -> r.waveTimer, (r, v) -> r.waveTimer = v, BOOL),
        field("waveSending", r -> r.waveSending, (r, v) -> r.waveSending = v, BOOL),
        field("waves", r -> r.waves, (r, v) -> r.waves = v, BOOL),
        field("airUseSpawns", r -> r.airUseSpawns, (r, v) -> r.airUseSpawns = v, BOOL),
        field("wavesSpawnAtCores", r -> r.wavesSpawnAtCores, (r, v) -> r.wavesSpawnAtCores = v, BOOL),
        field("pvp", r -> r.pvp, (r, v) -> r.pvp = v, BOOL),
        field("pvpAutoPause", r -> r.pvpAutoPause, (r, v) -> r.pvpAutoPause = v, BOOL),
        field("pauseDisabled", r -> r.pauseDisabled, (r, v) -> r.pauseDisabled = v, BOOL),
        field("waitEnemies", r -> r.waitEnemies, (r, v) -> r.waitEnemies = v, BOOL),
        field("attackMode", r -> r.attackMode, (r, v) -> r.attackMode = v, BOOL),
        field("editor", r -> r.editor, (r, v) -> r.editor = v, BOOL),
        field("derelictRepair", r -> r.derelictRepair, (r, v) -> r.derelictRepair = v, BOOL),
        field("canGameOver", r -> r.canGameOver, (r, v) -> r.canGameOver = v, BOOL),
        field("coreCapture", r -> r.coreCapture, (r, v) -> r.coreCapture = v, BOOL),
        field("reactorExplosions", r -> r.reactorExplosions, (r, v) -> r.reactorExplosions = v, BOOL),
        field("possessionAllowed", r -> r.possessionAllowed, (r, v) -> r.possessionAllowed = v, BOOL),
        field("schematicsAllowed", r -> r.schematicsAllowed, (r, v) -> r.schematicsAllowed = v, BOOL),
        field("damageExplosions", r -> r.damageExplosions, (r, v) -> r.damageExplosions = v, BOOL),
        field("fire", r -> r.fire, (r, v) -> r.fire = v, BOOL),
        field("randomWaveAI", r -> r.randomWaveAI, (r, v) -> r.randomWaveAI = v, BOOL),
        field("unitPayloadUpdate", r -> r.unitPayloadUpdate, (r, v) -> r.unitPayloadUpdate = v, BOOL),
        field("unitPayloadsExplode", r -> r.unitPayloadsExplode, (r, v) -> r.unitPayloadsExplode = v, BOOL),
        field("unitCapVariable", r -> r.unitCapVariable, (r, v) -> r.unitCapVariable = v, BOOL),
        field("hideSpawns", r -> r.hideSpawns, (r, v) -> r.hideSpawns = v, BOOL),
        field("solarMultiplier", r -> r.solarMultiplier, (r, v) -> r.solarMultiplier = v, FLOAT),
        field("unitBuildSpeedMultiplier", r -> r.unitBuildSpeedMultiplier, (r, v) -> r.unitBuildSpeedMultiplier = v, FLOAT),
        field("unitCostMultiplier", r -> r.unitCostMultiplier, (r, v) -> r.unitCostMultiplier = v, FLOAT),
        field("unitDamageMultiplier", r -> r.unitDamageMultiplier, (r, v) -> r.unitDamageMultiplier = v, FLOAT),
        field("unitHealthMultiplier", r -> r.unitHealthMultiplier, (r, v) -> r.unitHealthMultiplier = v, FLOAT),
        field("unitCrashDamageMultiplier", r -> r.unitCrashDamageMultiplier, (r, v) -> r.unitCrashDamageMultiplier = v, FLOAT),
        field("unitMineSpeedMultiplier", r -> r.unitMineSpeedMultiplier, (r, v) -> r.unitMineSpeedMultiplier = v, FLOAT),
        field("unitFactoryActivationDelay", r -> r.unitFactoryActivationDelay, (r, v) -> r.unitFactoryActivationDelay = v, FLOAT),
        field("ghostBlocks", r -> r.ghostBlocks, (r, v) -> r.ghostBlocks = v, BOOL),
        field("showOtherTeamPings", r -> r.showOtherTeamPings, (r, v) -> r.showOtherTeamPings = v, BOOL),
        field("logicUnitControl", r -> r.logicUnitControl, (r, v) -> r.logicUnitControl = v, BOOL),
        field("logicUnitBuild", r -> r.logicUnitBuild, (r, v) -> r.logicUnitBuild = v, BOOL),
        field("logicUnitDeconstruct", r -> r.logicUnitDeconstruct, (r, v) -> r.logicUnitDeconstruct = v, BOOL),
        field("worldProcessorPlayerLink", r -> r.worldProcessorPlayerLink, (r, v) -> r.worldProcessorPlayerLink = v, BOOL),
        field("allowEditWorldProcessors", r -> r.allowEditWorldProcessors, (r, v) -> r.allowEditWorldProcessors = v, BOOL),
        field("disableWorldProcessors", r -> r.disableWorldProcessors, (r, v) -> r.disableWorldProcessors = v, BOOL),
        field("blockHealthMultiplier", r -> r.blockHealthMultiplier, (r, v) -> r.blockHealthMultiplier = v, FLOAT),
        field("blockDamageMultiplier", r -> r.blockDamageMultiplier, (r, v) -> r.blockDamageMultiplier = v, FLOAT),
        field("buildCostMultiplier", r -> r.buildCostMultiplier, (r, v) -> r.buildCostMultiplier = v, FLOAT),
        field("buildSpeedMultiplier", r -> r.buildSpeedMultiplier, (r, v) -> r.buildSpeedMultiplier = v, FLOAT),
        field("deconstructRefundMultiplier", r -> r.deconstructRefundMultiplier, (r, v) -> r.deconstructRefundMultiplier = v, FLOAT),
        field("objectiveTimerMultiplier", r -> r.objectiveTimerMultiplier, (r, v) -> r.objectiveTimerMultiplier = v, FLOAT),
        field("enemyCoreBuildRadius", r -> r.enemyCoreBuildRadius, (r, v) -> r.enemyCoreBuildRadius = v, FLOAT),
        field("polygonCoreProtection", r -> r.polygonCoreProtection, (r, v) -> r.polygonCoreProtection = v, BOOL),
        field("placeRangeCheck", r -> r.placeRangeCheck, (r, v) -> r.placeRangeCheck = v, BOOL),
        field("cleanupDeadTeams", r -> r.cleanupDeadTeams, (r, v) -> r.cleanupDeadTeams = v, BOOL),
        field("onlyDepositCore", r -> r.onlyDepositCore, (r, v) -> r.onlyDepositCore = v, BOOL),
        field("allowCoreUnloaders", r -> r.allowCoreUnloaders, (r, v) -> r.allowCoreUnloaders = v, BOOL),
        field("itemDepositCooldown", r -> r.itemDepositCooldown, (r, v) -> r.itemDepositCooldown = v, FLOAT),
        field("coreDestroyClear", r -> r.coreDestroyClear, (r, v) -> r.coreDestroyClear = v, BOOL),
        field("hideBannedBlocks", r -> r.hideBannedBlocks, (r, v) -> r.hideBannedBlocks = v, BOOL),
        field("allowEnvironmentDeconstruct", r -> r.allowEnvironmentDeconstruct, (r, v) -> r.allowEnvironmentDeconstruct = v, BOOL),
        field("instantBuild", r -> r.instantBuild, (r, v) -> r.instantBuild = v, BOOL),
        field("blockWhitelist", r -> r.blockWhitelist, (r, v) -> r.blockWhitelist = v, BOOL),
        field("unitWhitelist", r -> r.unitWhitelist, (r, v) -> r.unitWhitelist = v, BOOL),
        field("dropZoneRadius", r -> r.dropZoneRadius, (r, v) -> r.dropZoneRadius = v, FLOAT),
        field("waveSpacing", r -> r.waveSpacing, (r, v) -> r.waveSpacing = v, FLOAT),
        field("initialWaveSpacing", r -> r.initialWaveSpacing, (r, v) -> r.initialWaveSpacing = v, FLOAT),
        field("winWave", r -> r.winWave, (r, v) -> r.winWave = v, INT),
        field("unitCap", r -> r.unitCap, (r, v) -> r.unitCap = v, INT),
        field("disableUnitCap", r -> r.disableUnitCap, (r, v) -> r.disableUnitCap = v, BOOL),
        field("dragMultiplier", r -> r.dragMultiplier, (r, v) -> r.dragMultiplier = v, FLOAT),
        field("env", r -> r.env, (r, v) -> r.env = v, INT),
        always("attributes", r -> r.attributes, (r, v) -> r.attributes = v, ATTRIBUTES),
        nullable("sector", r -> r.sector, (r, v) -> r.sector = v, STRING),
        nullable("ambientMusic", r -> r.ambientMusic, (r, v) -> r.ambientMusic = v, nullableList(MUSIC)),
        nullable("darkMusic", r -> r.darkMusic, (r, v) -> r.darkMusic = v, nullableList(MUSIC)),
        field("alwaysPlayMusic", r -> r.alwaysPlayMusic, (r, v) -> r.alwaysPlayMusic = v, BOOL),
        field("disableMusic", r -> r.disableMusic, (r, v) -> r.disableMusic = v, BOOL),
        field("musicVolume", r -> r.musicVolume, (r, v) -> r.musicVolume = v, FLOAT),
        field("spawns", r -> r.spawns, (r, v) -> r.spawns = v, list(SPAWN_GROUP)),
        field("loadout", r -> r.loadout, (r, v) -> r.loadout = v, list(ITEM_STACK)),
        field("weather", r -> r.weather, (r, v) -> r.weather = v, list(WEATHER_ENTRY)),
        field("blockLimits", r -> r.blockLimits, (r, v) -> r.blockLimits = v, BLOCK_LIMITS),
        field("bannedBlocks", r -> r.bannedBlocks, (r, v) -> r.bannedBlocks = v, set(BLOCK)),
        field("bannedUnits", r -> r.bannedUnits, (r, v) -> r.bannedUnits = v, set(UNIT)),
        field("revealedBlocks", r -> r.revealedBlocks, (r, v) -> r.revealedBlocks = v, set(BLOCK)),
        field("researched", r -> r.researched, (r, v) -> r.researched = v, set(SET_STRING)),
        always("objectives", r -> r.objectives, (r, v) -> r.objectives = v, OBJECTIVES),
        field("objectiveFlags", r -> r.objectiveFlags, (r, v) -> r.objectiveFlags = v, set(SET_STRING)),
        field("fog", r -> r.fog, (r, v) -> r.fog = v, BOOL),
        field("staticFog", r -> r.staticFog, (r, v) -> r.staticFog = v, BOOL),
        field("staticColor", r -> r.staticColor, (r, v) -> r.staticColor = v, COLOR),
        field("dynamicColor", r -> r.dynamicColor, (r, v) -> r.dynamicColor = v, COLOR),
        field("lighting", r -> r.lighting, (r, v) -> r.lighting = v, BOOL),
        field("ambientLight", r -> r.ambientLight, (r, v) -> r.ambientLight = v, COLOR),
        field("unitLight", r -> r.unitLight, (r, v) -> r.unitLight = v, BOOL),
        field("defaultTeam", r -> r.defaultTeam, (r, v) -> r.defaultTeam = v, TEAM),
        field("waveTeam", r -> r.waveTeam, (r, v) -> r.waveTeam = v, TEAM),
        field("cloudColor", r -> r.cloudColor, (r, v) -> r.cloudColor = v, COLOR),
        nullable("modeName", r -> r.modeName, (r, v) -> r.modeName = v, STRING),
        nullable("mission", r -> r.mission, (r, v) -> r.mission = v, STRING),
        field("coreIncinerates", r -> r.coreIncinerates, (r, v) -> r.coreIncinerates = v, BOOL),
        field("borderDarkness", r -> r.borderDarkness, (r, v) -> r.borderDarkness = v, BOOL),
        field("limitMapArea", r -> r.limitMapArea, (r, v) -> r.limitMapArea = v, BOOL),
        field("limitX", r -> r.limitX, (r, v) -> r.limitX = v, INT),
        field("limitY", r -> r.limitY, (r, v) -> r.limitY = v, INT),
        field("limitWidth", r -> r.limitWidth, (r, v) -> r.limitWidth = v, INT),
        field("limitHeight", r -> r.limitHeight, (r, v) -> r.limitHeight = v, INT),
        field("disableOutsideArea", r -> r.disableOutsideArea, (r, v) -> r.disableOutsideArea = v, BOOL),
        field("tags", r -> r.tags, (r, v) -> r.tags = v, TAGS),
        nullable("customBackgroundCallback", r -> r.customBackgroundCallback, (r, v) -> r.customBackgroundCallback = v, STRING),
        nullable("backgroundTexture", r -> r.backgroundTexture, (r, v) -> r.backgroundTexture = v, STRING),
        field("backgroundSpeed", r -> r.backgroundSpeed, (r, v) -> r.backgroundSpeed = v, FLOAT),
        field("backgroundScl", r -> r.backgroundScl, (r, v) -> r.backgroundScl = v, FLOAT),
        field("backgroundOffsetX", r -> r.backgroundOffsetX, (r, v) -> r.backgroundOffsetX = v, FLOAT),
        field("backgroundOffsetY", r -> r.backgroundOffsetY, (r, v) -> r.backgroundOffsetY = v, FLOAT),
        nullable("planetBackground", r -> r.planetBackground, (r, v) -> r.planetBackground = v, RAW),
        field("planet", r -> r.planet, (r, v) -> r.planet = v, PLANET),
        field("allowLogicData", r -> r.allowLogicData, (r, v) -> r.allowLogicData = v, BOOL)
    );

    private static final List<Field<TeamRule, ?>> TEAM_FIELDS = List.of(
        field("aiCoreSpawn", t -> t.aiCoreSpawn, (t, v) -> t.aiCoreSpawn = v, BOOL),
        field("protectCores", t -> t.protectCores, (t, v) -> t.protectCores = v, BOOL),
        field("checkPlacement", t -> t.checkPlacement, (t, v) -> t.checkPlacement = v, BOOL),
        field("cheat", t -> t.cheat, (t, v) -> t.cheat = v, BOOL),
        field("fillItems", t -> t.fillItems, (t, v) -> t.fillItems = v, BOOL),
        field("infiniteResources", t -> t.infiniteResources, (t, v) -> t.infiniteResources = v, BOOL),
        field("prebuildAi", t -> t.prebuildAi, (t, v) -> t.prebuildAi = v, BOOL),
        field("buildAi", t -> t.buildAi, (t, v) -> t.buildAi = v, BOOL),
        field("buildAiTier", t -> t.buildAiTier, (t, v) -> t.buildAiTier = v, FLOAT),
        field("rtsAi", t -> t.rtsAi, (t, v) -> t.rtsAi = v, BOOL),
        field("rtsMinSquad", t -> t.rtsMinSquad, (t, v) -> t.rtsMinSquad = v, INT),
        field("rtsMaxSquad", t -> t.rtsMaxSquad, (t, v) -> t.rtsMaxSquad = v, INT),
        field("rtsMinWeight", t -> t.rtsMinWeight, (t, v) -> t.rtsMinWeight = v, FLOAT),
        field("unitFactoryActivationDelay", t -> t.unitFactoryActivationDelay, (t, v) -> t.unitFactoryActivationDelay = v, FLOAT),
        field("unitBuildSpeedMultiplier", t -> t.unitBuildSpeedMultiplier, (t, v) -> t.unitBuildSpeedMultiplier = v, FLOAT),
        field("unitDamageMultiplier", t -> t.unitDamageMultiplier, (t, v) -> t.unitDamageMultiplier = v, FLOAT),
        field("unitCrashDamageMultiplier", t -> t.unitCrashDamageMultiplier, (t, v) -> t.unitCrashDamageMultiplier = v, FLOAT),
        field("unitMineSpeedMultiplier", t -> t.unitMineSpeedMultiplier, (t, v) -> t.unitMineSpeedMultiplier = v, FLOAT),
        field("unitCostMultiplier", t -> t.unitCostMultiplier, (t, v) -> t.unitCostMultiplier = v, FLOAT),
        field("unitHealthMultiplier", t -> t.unitHealthMultiplier, (t, v) -> t.unitHealthMultiplier = v, FLOAT),
        field("blockHealthMultiplier", t -> t.blockHealthMultiplier, (t, v) -> t.blockHealthMultiplier = v, FLOAT),
        field("blockDamageMultiplier", t -> t.blockDamageMultiplier, (t, v) -> t.blockDamageMultiplier = v, FLOAT),
        field("buildSpeedMultiplier", t -> t.buildSpeedMultiplier, (t, v) -> t.buildSpeedMultiplier = v, FLOAT),
        field("extraCoreBuildRadius", t -> t.extraCoreBuildRadius, (t, v) -> t.extraCoreBuildRadius = v, FLOAT)
    );

    private static final List<Field<WeatherEntry, ?>> WEATHER_FIELDS = List.of(
        field("weather", w -> w.weather, (w, v) -> w.weather = v, WEATHER),
        field("minFrequency", w -> w.minFrequency, (w, v) -> w.minFrequency = v, FLOAT),
        field("maxFrequency", w -> w.maxFrequency, (w, v) -> w.maxFrequency = v, FLOAT),
        field("minDuration", w -> w.minDuration, (w, v) -> w.minDuration = v, FLOAT),
        field("maxDuration", w -> w.maxDuration, (w, v) -> w.maxDuration = v, FLOAT),
        field("cooldown", w -> w.cooldown, (w, v) -> w.cooldown = v, FLOAT),
        field("intensity", w -> w.intensity, (w, v) -> w.intensity = v, FLOAT),
        field("always", w -> w.always, (w, v) -> w.always = v, BOOL)
    );

    private static final Map<String, Field<Rules, ?>> RULE_FIELD_NAMES = byName(RULE_FIELDS);
    private static final Map<String, Field<TeamRule, ?>> TEAM_FIELD_NAMES = byName(TEAM_FIELDS);
    private static final Map<String, Field<WeatherEntry, ?>> WEATHER_FIELD_NAMES = byName(WEATHER_FIELDS);

    private static <O> Map<String, Field<O, ?>> byName(List<Field<O, ?>> fields){
        var out = new TreeMap<String, Field<O, ?>>();
        for(Field<O, ?> field : fields) out.put(field.name, field);
        return Collections.unmodifiableMap(out);
    }

    // endregion

    private String name(ContentType type, int id){
        String name = ContentLookup.name(content, type, id);
        if(name == null) throw new IllegalArgumentException("no " + type.yamlName() + " with id " + id);
        return name;
    }

    private int byName(ContentType type, String name){
        return ContentLookup.byName(content, type, name);
    }

    /** Vanilla's {@code Block} serializer: the name, then its fallback, then air. */
    private int block(String name){
        int id = byName(ContentType.BLOCK, name);
        if(id < 0) id = byName(ContentType.BLOCK, ContentLookup.BLOCK_FALLBACK.getOrDefault(name, ""));
        return Math.max(id, 0);
    }

    private static IllegalArgumentException unconvertible(JsonValue value, String type){
        return new IllegalArgumentException("cannot read " + value + " as " + type);
    }
}
