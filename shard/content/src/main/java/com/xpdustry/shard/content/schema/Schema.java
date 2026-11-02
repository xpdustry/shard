package com.xpdustry.shard.content.schema;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.yaml.*;

import java.io.*;
import java.nio.charset.*;
import java.util.*;

/**
 * The schema of a content pack ({@code kinds.yml}): every kind with its fields, the names of the shared statics
 * ({@code fx:}, {@code sound:}…), the enums, and the unit entity classes. Immutable after {@link #read}.
 */
public final class Schema{
    /** A unit entity class ({@code MechUnit}): its wire class id and the movement shapes it implements. */
    public record Entity(String name, int classId, List<String> traits){
        public boolean has(String trait){
            return traits.contains(trait);
        }
    }

    private final Map<String, Set<String>> registries = new HashMap<>();
    private final Map<String, List<String>> enums = new HashMap<>();
    private final Map<String, Entity> entities = new TreeMap<>();
    private final TreeMap<String, KindSchema> kinds = new TreeMap<>();
    private final EnumMap<ContentType, KindSchema> roots = new EnumMap<>(ContentType.class);

    private Schema(){
    }

    private static volatile Schema vanilla;

    /** The vanilla v160.7 schema, {@code content/vanilla/kinds.yml} on the class path. */
    public static Schema vanilla(){
        Schema s = vanilla;
        if(s == null){
            synchronized(Schema.class){
                s = vanilla;
                if(s == null){
                    var diagnostics = new Diagnostics();
                    String path = "content/vanilla/kinds.yml";
                    Node root = YamlReader.read(path, resource(path), diagnostics);
                    diagnostics.check();
                    vanilla = s = read(root, diagnostics);
                    diagnostics.check();
                }
            }
        }
        return s;
    }

    /** A class path resource as text. */
    public static String resource(String path){
        try(InputStream in = Schema.class.getResourceAsStream("/" + path)){
            if(in == null) throw new UncheckedIOException(new FileNotFoundException("resource " + path));
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
    }

    /** Reads a {@code kinds.yml} tree. Problems go to {@code diagnostics}. */
    public static Schema read(Node root, Diagnostics diagnostics){
        var schema = new Schema();
        if(!(root instanceof Node.Mapping map)){
            diagnostics.error(root, "a schema is a mapping with registries, enums, entities and kinds");
            return schema;
        }
        if(map.get("registries") instanceof Node.Mapping r){
            r.entries().forEach((name, list) -> schema.registries.put(name, new HashSet<>(strings(list, diagnostics))));
        }
        if(map.get("enums") instanceof Node.Mapping e){
            e.entries().forEach((name, list) -> schema.enums.put(name, List.copyOf(strings(list, diagnostics))));
        }
        if(map.get("entities") instanceof Node.Mapping e){
            e.entries().forEach((name, node) -> {
                if(node instanceof Node.Mapping m && m.get("id") instanceof Node.Scalar id){
                    schema.entities.put(name, new Entity(name, Integer.parseInt(id.value()), strings(m.get("traits"), diagnostics)));
                }else{
                    diagnostics.error(node, "entity " + name + " needs an id");
                }
            });
        }
        if(!(map.get("kinds") instanceof Node.Mapping kinds)){
            diagnostics.error(map, "a schema needs kinds");
            return schema;
        }
        kinds.entries().forEach((key, node) -> {
            if(!(node instanceof Node.Mapping k)){
                diagnostics.error(node, "kind " + key + " must be a mapping");
                return;
            }
            ContentType type = k.get("type") instanceof Node.Scalar t ? ContentType.of(t.value()) : null;
            var kind = new KindSchema(schema, key, text(k.get("class")), text(k.get("parent")), text(k.get("build")),
                k.get("abstract") instanceof Node.Scalar a && a.value().equals("true"), type,
                k.get("defaults") instanceof Node.Mapping d ? d : Node.mapping(k.mark()));
            schema.kinds.put(key, kind);
            if(type != null) schema.roots.put(type, kind);
        });
        Map<String, Node.Mapping> fieldNodes = new HashMap<>();
        kinds.entries().forEach((key, node) -> {
            if(node instanceof Node.Mapping k && k.get("fields") instanceof Node.Mapping f) fieldNodes.put(key, f);
        });
        for(KindSchema kind : schema.kinds.values()) declare(schema, kind, fieldNodes, new HashSet<>(), diagnostics);
        return schema;
    }

    /** Links {@code kind} after its parent, so that its fields' slots follow the parent's. */
    private static void declare(Schema schema, KindSchema kind, Map<String, Node.Mapping> fieldNodes, Set<String> path,
                                Diagnostics diagnostics){
        if(kind.linked()) return;
        if(!path.add(kind.key())){
            diagnostics.error(Mark.none, "kind " + kind.key() + " is its own ancestor");
            kind.link(null);
            return;
        }
        KindSchema parent = null;
        if(kind.parentKey() != null){
            parent = schema.kinds.get(kind.parentKey());
            if(parent == null) diagnostics.error(Mark.none, "kind " + kind.key() + ": unknown parent kind " + kind.parentKey());
            else declare(schema, parent, fieldNodes, path, diagnostics);
        }
        kind.link(parent);
        Node.Mapping fields = fieldNodes.get(kind.key());
        if(fields != null){
            for(var e : fields.entries().entrySet()){
                if(!(e.getValue() instanceof Node.Mapping f)){
                    diagnostics.error(e.getValue(), "field " + kind.key() + "." + e.getKey() + " must be a mapping");
                    continue;
                }
                FieldType type;
                try{
                    type = FieldType.parse(text(f.get("type")));
                }catch(IllegalArgumentException ex){
                    diagnostics.error(f, "field " + kind.key() + "." + e.getKey() + ": " + ex.getMessage());
                    continue;
                }
                kind.declared.add(new FieldSpec(e.getKey(), text(f.get("java")), type, Tag.of(text(f.get("tag"))), flag(f, "noPatch"),
                    "extracted".equals(text(f.get("baked"))), kind, kind.nextSlot(), f.has("default") ? f.get("default") : null));
            }
            kind.link(parent);
        }
    }

    private static boolean flag(Node.Mapping m, String key){
        return m.get(key) instanceof Node.Scalar s && s.value().equals("true");
    }

    private static String text(Node node){
        return node instanceof Node.Scalar s && !s.isNull() ? s.value() : null;
    }

    private static List<String> strings(Node node, Diagnostics diagnostics){
        List<String> out = new ArrayList<>();
        if(node instanceof Node.Sequence seq){
            for(Node n : seq.items()){
                if(n instanceof Node.Scalar s) out.add(s.value());
                else diagnostics.error(n, "expected a name");
            }
        }else if(node != null){
            diagnostics.error(node, "expected a list of names");
        }
        return out;
    }

    /** Binds a kind's class defaults to plain values, slot by slot. */
    Object[] bindDefaults(KindSchema kind){
        Object[] values = Arrays.copyOf(kind.parent() != null ? kind.parent().defaults() : new Object[0], kind.slots().size());
        var diagnostics = new Diagnostics();
        var binder = new Binder(this, diagnostics, null);
        for(FieldSpec f : kind.declared){
            values[f.slot()] = f.defaultNode == null ? zero(f.type()) : binder.bind(f.defaultNode, f.type(), kind.key() + "." + f.name());
        }
        kind.overrides.entries().forEach((name, node) -> {
            FieldSpec f = kind.field(name);
            if(f == null) diagnostics.error(node, "kind " + kind.key() + ": default for unknown field " + name);
            else values[f.slot()] = binder.bind(node, f.type(), kind.key() + "." + name);
        });
        diagnostics.check();
        return values;
    }

    /** What a field of {@code type} holds before anything sets it. */
    static Object zero(FieldType type){
        if(type instanceof FieldType.Scalar s){
            return switch(s){
                case BOOL -> false;
                case INT -> 0;
                case LONG -> 0L;
                case FLOAT -> 0f;
                case DOUBLE -> 0d;
                default -> null;
            };
        }
        return null;
    }

    /** The kind {@code key}, or null. */
    public KindSchema kind(String key){
        return kinds.get(key);
    }

    /** Every kind, by key. */
    public Collection<KindSchema> kinds(){
        return Collections.unmodifiableCollection(kinds.values());
    }

    /** The root kind of a content type: {@code block}, {@code unit-type}, {@code bullet-type}… */
    public KindSchema root(ContentType type){
        return roots.get(type);
    }

    /** Whether {@code prefix:name} is a known static. */
    public boolean registered(String registry, String name){
        Set<String> names = registries.get(registry);
        return names != null && names.contains(name);
    }

    public boolean hasRegistry(String registry){
        return registries.containsKey(registry);
    }

    /** The constants of an enum, in ordinal order, or null. */
    public List<String> enumConstants(String name){
        return enums.get(name);
    }

    /** A unit entity class by name ({@code MechUnit}), or null. */
    public Entity entity(String name){
        return entities.get(name);
    }

    public Collection<Entity> entities(){
        return Collections.unmodifiableCollection(entities.values());
    }
}
