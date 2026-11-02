package com.xpdustry.shard.tools.wirelift;

import org.snakeyaml.engine.v2.api.*;

import java.util.*;
import java.util.regex.*;

/**
 * The wire schema of one build: what {@link SchemaEmit} lifts and {@link WireGen} compiles. {@link #yaml()} is the
 * committed {@code wire/schema/<build>.yaml}; {@link #parse} reads it back strictly (unknown keys fail).
 *
 * @param enums     enums the wire carries as ordinals, constants in ordinal order ({@code X.all[ordinal]})
 * @param layouts   one per distinct {@code writeSync} body (plus save-only classes), by name
 * @param entities  every generated entity class, by class id ({@code EntityMapping.idMap})
 * @param casts     each type a packet casts a read entity to ({@code Entity<Player>}), with the entity class ids that
 *                  are one; vanilla's read fails for an entity of any other class
 * @param packets   every registered packet, by id (registration order)
 * @param problems  lifted bodies {@link SchemaEmit} did not recognise; {@link WireGen} refuses to run while any remain
 */
record Schema(String build, Map<String, List<String>> enums, Map<String, Layout> layouts, List<Entity> entities,
              Map<String, List<Integer>> casts, List<Packet> packets, List<String> problems){

    /**
     * One field. {@code codec} is a closed vocabulary (see {@link WireGen}): a primitive, a TypeIO name,
     * {@code ref<content>} or {@code seq<element>} with a {@code count} codec.
     *
     * @param local  {@code @SyncLocal}: always written, read and dropped by the client for its own entity
     * @param interp {@code lerp} or {@code slerp} when the client interpolates the field, else null
     */
    record Field(String name, String codec, String count, boolean local, String interp){}

    /**
     * The sync body (null when never synced) and the save body after its {@code i16} revision (null when never saved).
     * {@code old} holds the save bodies of earlier revisions that vanilla still reads, each named after the first class
     * and revision that wrote it ({@code MechUnit.2}). An old body's field named {@code _} is read and dropped, as are
     * fields the layout no longer has. {@code defaults}: the fields a fresh entity holds at other than zero (a
     * constructor's constants, -1 for null content), which old bodies that lack them leave so.
     */
    record Layout(String name, List<Field> sync, List<Field> save, SequencedMap<String, List<Field>> old, SortedMap<String, String> defaults){
        Layout(String name, List<Field> sync, List<Field> save, SequencedMap<String, List<Field>> old){
            this(name, sync, save, old, new TreeMap<>());
        }

        Layout(String name, List<Field> sync, List<Field> save){
            this(name, sync, save, new LinkedHashMap<>());
        }
    }

    /**
     * {@code layout} is null for classes that are neither synced nor saved; {@code revision} is -1 without a save.
     * {@code unit}: a subclass of {@code mindustry.gen.Unit}, the only classes a unit payload may carry. {@code old}:
     * each earlier revision vanilla still reads, to the name of its body in the layout's {@code old}, or {@link #SAVE}
     * when it reads like the current revision.
     */
    record Entity(int id, String className, String layout, int revision, SortedMap<Integer, String> old, boolean serialize, boolean unit){
        Entity(int id, String className, String layout, int revision, boolean serialize, boolean unit){
            this(id, className, layout, revision, new TreeMap<>(), serialize, unit);
        }
    }

    /**
     * {@code direction} is who reads it, lifted from {@code allow(boolean server)}: {@code C>S}, {@code S>C} or
     * {@code both}. A {@code framework} packet parses itself in {@code read()} and has no {@code Call} method.
     * {@code layout} is the lifted write order in compact form, with what only the read side shows: {@code ObjectSafe}
     * and entity casts ({@code Entity<Player>}).
     */
    record Packet(int id, String name, String direction, boolean framework, String priority, String reliable, String layout){}

    /** The body name of an old revision that reads like the current one. */
    static final String SAVE = "save";

    private static final Pattern PLAIN = Pattern.compile("[A-Za-z_][\\w.$<>]*");
    private static final Set<String> KEYWORDS = Set.of("true", "false", "null", "yes", "no", "on", "off", "y", "n");

    String yaml(){
        StringBuilder out = new StringBuilder();
        out.append("# Lifted from the vanilla jar's bytecode by :tools:wirelift (WireLift). Do not edit: run ./gradlew :tools:wirelift:lift.\n");
        out.append("build: ").append(quote(build)).append('\n');
        out.append("# Enums the wire carries as ordinals; constants in ordinal order (X.all[ordinal]).\n");
        out.append("enums:\n");
        enums.forEach((name, constants) -> out.append("  ").append(name).append(": [")
            .append(String.join(", ", constants.stream().map(Schema::quote).toList())).append("]\n"));
        out.append("# One layout per distinct writeSync body, named after its most generic class. sync: the writeSync fields in wire\n");
        out.append("# order (local: @SyncLocal, interp: the client interpolates it). save: write() after its i16 revision (entities).\n");
        out.append("# defaults: what a fresh entity holds other than zero. old: earlier save bodies read() still reads, named after the\n");
        out.append("# first class and revision that wrote them; _ is dropped.\n");
        out.append("layouts:\n");
        for(Layout layout : layouts.values()){
            out.append("  ").append(layout.name).append(":\n");
            fields(out, "sync", layout.sync);
            fields(out, "save", layout.save);
            if(!layout.defaults.isEmpty()){
                out.append("    defaults: {").append(String.join(", ", layout.defaults.entrySet().stream().map(d -> quote(d.getKey()) + ": " + d.getValue()).toList())).append("}\n");
            }
            if(!layout.old.isEmpty()){
                out.append("    old:\n");
                layout.old.forEach((body, fields) -> fields(out, "  " + quote(body), fields));
            }
        }
        out.append("# EntityMapping.idMap: the class id is the u8 before a synced entity and the first byte of a save chunk.\n");
        out.append("entities:\n");
        for(Entity e : entities){
            out.append("  - {id: ").append(e.id).append(", class: ").append(quote(e.className));
            if(e.layout != null) out.append(", layout: ").append(quote(e.layout));
            if(e.revision >= 0) out.append(", revision: ").append(e.revision);
            if(!e.old.isEmpty()){
                out.append(", old: {").append(String.join(", ", e.old.entrySet().stream().map(r -> r.getKey() + ": " + quote(r.getValue())).toList())).append('}');
            }
            out.append(", serialize: ").append(e.serialize);
            if(e.unit) out.append(", unit: true");
            out.append("}\n");
        }
        out.append("# The types packets cast a read entity to, and the entity classes that are one: vanilla's read fails for others.\n");
        out.append("casts:").append(casts.isEmpty() ? " {}\n" : "\n");
        casts.forEach((type, ids) -> out.append("  ").append(type).append(": [")
            .append(String.join(", ", ids.stream().map(String::valueOf).toList())).append("]\n"));
        out.append("# Ids are positions in registration order: Net.<clinit>, then Call.registerPackets(). dir: who reads it (allow()).\n");
        out.append("packets:\n");
        for(Packet p : packets){
            out.append("  - {id: ").append(p.id).append(", name: ").append(quote(p.name)).append(", dir: ").append(quote(p.direction))
                .append(p.framework ? ", framework: true" : "").append(", priority: ").append(p.priority).append(", reliable: ").append(quote(p.reliable))
                .append(", layout: \"").append(escape(p.layout)).append("\"}\n");
        }
        out.append("# Lifted bodies SchemaEmit did not recognise. WireGen refuses to run while any remain.\n");
        out.append("problems:").append(problems.isEmpty() ? " []\n" : "\n");
        for(String problem : problems) out.append("  - \"").append(escape(problem)).append("\"\n");
        return out.toString();
    }

    private static void fields(StringBuilder out, String key, List<Field> fields){
        if(fields == null) return;
        String indent = key.startsWith("  ") ? "  " : "";
        out.append("    ").append(key).append(":\n");
        for(Field f : fields){
            out.append(indent).append("      - {name: ").append(quote(f.name)).append(", codec: ").append(quote(f.codec));
            if(f.count != null) out.append(", count: ").append(quote(f.count));
            if(f.local) out.append(", local: true");
            if(f.interp != null) out.append(", interp: ").append(f.interp);
            out.append("}\n");
        }
    }

    private static String quote(String s){
        return PLAIN.matcher(s).matches() && !KEYWORDS.contains(s.toLowerCase(Locale.ROOT)) ? s : "\"" + escape(s) + "\"";
    }

    private static String escape(String s){
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    //region reading

    static Schema parse(String yaml){
        var root = map(new Load(LoadSettings.builder().build()).loadFromString(yaml), "the schema",
            "build", "enums", "layouts", "entities", "casts", "packets", "problems");
        Map<String, List<String>> enums = new LinkedHashMap<>();
        map(root.get("enums"), "enums").forEach((name, constants) -> enums.put(name, list(constants, "enum " + name).stream().map(String::valueOf).toList()));
        Map<String, Layout> layouts = new LinkedHashMap<>();
        map(root.get("layouts"), "layouts").forEach((name, value) -> {
            var layout = map(value, "layout " + name, "sync", "save", "defaults", "old");
            SequencedMap<String, List<Field>> old = new LinkedHashMap<>();
            if(layout.containsKey("old")) map(layout.get("old"), "old bodies of " + name).forEach((body, fields) -> old.put(body, fields(fields, name + "." + body)));
            SortedMap<String, String> defaults = new TreeMap<>();
            if(layout.containsKey("defaults")) map(layout.get("defaults"), "defaults of " + name).forEach((field, v) -> defaults.put(field, String.valueOf(v)));
            layouts.put(name, new Layout(name, fields(layout.get("sync"), name), fields(layout.get("save"), name), old, defaults));
        });
        List<Entity> entities = new ArrayList<>();
        for(Object value : list(root.get("entities"), "entities")){
            var e = map(value, "an entity", "id", "class", "layout", "revision", "old", "serialize", "unit");
            SortedMap<Integer, String> old = new TreeMap<>();
            if(e.containsKey("old")){
                if(!(e.get("old") instanceof Map<?, ?> revisions)) throw new IllegalArgumentException("old revisions of " + e.get("class") + " is not a mapping");
                for(var r : revisions.entrySet()){
                    if(!(r.getKey() instanceof Integer revision) || !(r.getValue() instanceof String body)){
                        throw new IllegalArgumentException("old revisions of " + e.get("class") + " map revisions to body names, not " + r);
                    }
                    old.put(revision, body);
                }
            }
            entities.add(new Entity(integer(e, "id"), string(e, "class"), (String)e.get("layout"),
                e.containsKey("revision") ? integer(e, "revision") : -1, old, Boolean.TRUE.equals(e.get("serialize")), Boolean.TRUE.equals(e.get("unit"))));
        }
        Map<String, List<Integer>> casts = new LinkedHashMap<>();
        map(root.get("casts"), "casts").forEach((type, ids) -> casts.put(type, list(ids, "cast " + type).stream().map(id -> {
            if(!(id instanceof Integer i)) throw new IllegalArgumentException("cast " + type + " has a class id that is not an integer: " + id);
            return i;
        }).toList()));
        List<Packet> packets = new ArrayList<>();
        for(Object value : list(root.get("packets"), "packets")){
            var p = map(value, "a packet", "id", "name", "dir", "framework", "priority", "reliable", "layout");
            packets.add(new Packet(integer(p, "id"), string(p, "name"), string(p, "dir"), Boolean.TRUE.equals(p.get("framework")), string(p, "priority"),
                string(p, "reliable"), string(p, "layout")));
        }
        List<String> problems = list(root.get("problems"), "problems").stream().map(String::valueOf).toList();
        return new Schema(string(root, "build"), enums, layouts, entities, casts, packets, problems);
    }

    private static List<Field> fields(Object value, String layout){
        if(value == null) return null;
        List<Field> out = new ArrayList<>();
        for(Object item : list(value, "fields of " + layout)){
            var f = map(item, "a field of " + layout, "name", "codec", "count", "local", "interp");
            out.add(new Field(string(f, "name"), string(f, "codec"), (String)f.get("count"), Boolean.TRUE.equals(f.get("local")), (String)f.get("interp")));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value, String what, String... keys){
        if(!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException(what + " is not a mapping: " + value);
        if(keys.length > 0){
            for(Object key : map.keySet()){
                if(!Arrays.asList(keys).contains(key)) throw new IllegalArgumentException(what + " has an unknown key '" + key + "'");
            }
        }
        return (Map<String, Object>)map;
    }

    private static List<?> list(Object value, String what){
        if(!(value instanceof List<?> list)) throw new IllegalArgumentException(what + " is not a list: " + value);
        return list;
    }

    private static String string(Map<String, Object> map, String key){
        if(!(map.get(key) instanceof String s)) throw new IllegalArgumentException("'" + key + "' is not a string in " + map);
        return s;
    }

    private static int integer(Map<String, Object> map, String key){
        if(!(map.get(key) instanceof Integer i)) throw new IllegalArgumentException("'" + key + "' is not an integer in " + map);
        return i;
    }

    //endregion
}
