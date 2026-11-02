package com.xpdustry.shard.content.load;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.schema.*;
import com.xpdustry.shard.content.schema.FieldType.*;
import com.xpdustry.shard.content.yaml.*;

import java.util.*;

/**
 * Loads a pack (ARCHITECTURE.md §5, note 20): parse every file, apply overlays, resolve inheritance on the raw YAML,
 * assign wire ids, then bind each concrete prototype against its kind's schema. Every error is collected with its
 * {@code file:line:column}; {@link #load} throws a {@link ContentException} listing all of them.
 *
 * <p>Inheritance follows Space Station 14: {@code parent} is one name or a list; the prototype's own fields win, then
 * the first parent, then the next. Sub-objects of the same kind merge field by field; everything else (lists, maps,
 * scalars, sub-objects of another kind) is replaced whole. {@code id}, {@code abstract} and {@code parent} are never
 * inherited; a parent must have the same type.
 */
public final class Loader{
    private final Pack pack;
    private final Schema schema;
    private final Diagnostics diagnostics;
    private final EnumMap<ContentType, LinkedHashMap<String, Prototype>> prototypes = new EnumMap<>(ContentType.class);
    private final Map<Prototype, Node.Mapping> resolved = new IdentityHashMap<>();
    private final Set<Prototype> resolving = Collections.newSetFromMap(new IdentityHashMap<>());
    private final EnumMap<ContentType, List<String>> names = new EnumMap<>(ContentType.class);
    private final EnumMap<ContentType, Map<String, Integer>> ids = new EnumMap<>(ContentType.class);
    private ContentType current;

    private Loader(Pack pack, Diagnostics diagnostics){
        this.pack = pack;
        this.schema = pack.schema();
        this.diagnostics = diagnostics;
        for(ContentType type : ContentType.values()) prototypes.put(type, new LinkedHashMap<>());
    }

    /** Loads {@code pack}, throwing a {@link ContentException} with every error. */
    public static Loaded load(Pack pack){
        var diagnostics = new Diagnostics();
        Loaded loaded = load(pack, diagnostics);
        diagnostics.check();
        return loaded;
    }

    /** Loads {@code pack}; errors go to {@code diagnostics}, and the result is only usable when there are none. */
    public static Loaded load(Pack pack, Diagnostics diagnostics){
        return new Loader(pack, diagnostics).run();
    }

    private Loaded run(){
        for(Pack.Source source : pack.files()) read(source);
        assignIds();
        var binder = new Binder(schema, diagnostics, this::check);
        EnumMap<ContentType, List<Authored>> content = new EnumMap<>(ContentType.class);
        for(ContentType type : ContentType.values()){
            List<String> list = names.get(type);
            Authored[] slots = new Authored[list.size()];
            for(Prototype p : prototypes.get(type).values()){
                if(p.isAbstract()) continue;
                current = type;
                Authored a = bind(p, binder);
                if(a != null) slots[a.id()] = a;
            }
            content.put(type, Arrays.asList(slots));
        }
        return new Loaded(pack, content, names);
    }

    // reading and overlays

    private void read(Pack.Source source){
        Node root = YamlReader.read(source.path(), source.text(), diagnostics);
        if(root == null) return;
        if(!(root instanceof Node.Sequence docs)){
            diagnostics.error(root, "a prototype file is a list of prototypes (- type: …)");
            return;
        }
        for(Node doc : docs.items()){
            Prototype p = Prototype.read(doc, diagnostics);
            if(p == null) continue;
            var map = prototypes.get(p.type());
            Prototype old = map.get(p.id());
            if(p.overlay()){
                if(old == null) diagnostics.error(p.mark(), p.where() + ": overlay of a prototype that isn't defined above");
                else map.put(p.id(), overlay(old, p));
            }else if(old != null){
                diagnostics.error(p.mark(), p.where() + " is already defined at " + old.mark() + " (use overlay: true to change it)");
            }else{
                map.put(p.id(), p);
            }
        }
    }

    /**
     * {@code base} with {@code overlay}'s removals and fields applied. The overlay can't change the kind. The result
     * is marked at the overlay, the last place that wrote the content, where later errors about it point.
     */
    private Prototype overlay(Prototype base, Prototype overlay){
        String kind = overlay.kind();
        if(kind != null && base.kind() != null && !kind.equals(base.kind())){
            diagnostics.error(overlay.fields().get("kind"), overlay.where() + ": an overlay can't change the kind (" + base.kind() + ")");
        }
        Node.Mapping fields = base.fields();
        for(String name : overlay.remove()){
            if(!fields.has(name)) diagnostics.error(overlay.mark(), overlay.where() + ": remove " + name + ", which the prototype doesn't set");
            fields = fields.without(name);
        }
        fields = merge(overlay.fields().without("kind"), fields, schema.kind(base.kind() == null ? "" : base.kind()));
        return new Prototype(base.type(), base.id(), base.parents(), base.isAbstract(), false, List.of(), base.wire(), fields, overlay.mark());
    }

    // inheritance

    /** The prototype's fields with its parents' merged in, {@code kind} included. */
    private Node.Mapping resolve(Prototype p){
        Node.Mapping known = resolved.get(p);
        if(known != null) return known;
        if(!resolving.add(p)){
            diagnostics.error(p.mark(), p.where() + " is its own ancestor");
            return p.fields();
        }
        Node.Mapping base = null;
        List<String> parents = p.parents();
        for(int i = parents.size() - 1; i >= 0; i--){
            Prototype parent = prototypes.get(p.type()).get(parents.get(i));
            if(parent == null){
                diagnostics.error(p.mark(), p.where() + ": unknown parent " + p.type().yamlName() + " '" + parents.get(i) + "'");
                continue;
            }
            Node.Mapping fields = resolve(parent);
            base = base == null ? fields : merge(fields, base, kindOf(fields, base));
        }
        Node.Mapping out = base == null ? p.fields() : merge(p.fields(), base, kindOf(p.fields(), base));
        resolving.remove(p);
        resolved.put(p, out);
        return out;
    }

    private KindSchema kindOf(Node.Mapping child, Node.Mapping parent){
        Node k = child.has("kind") ? child.get("kind") : parent.get("kind");
        return k instanceof Node.Scalar s ? schema.kind(s.value()) : null;
    }

    /**
     * {@code child} over {@code parent}: the child's keys win. Where both hold a sub-object of the same kind (a field
     * of {@code kind} that holds objects), the two merge recursively.
     */
    static Node.Mapping merge(Node.Mapping child, Node.Mapping parent, KindSchema kind){
        var entries = new LinkedHashMap<>(parent.entries());
        var keys = new LinkedHashMap<>(parent.keys());
        child.entries().forEach((name, value) -> {
            Node old = entries.get(name);
            FieldSpec field = kind == null ? null : kind.field(name);
            if(field != null && old instanceof Node.Mapping o && value instanceof Node.Mapping v && holdsObjects(field.type())){
                String vk = kindKey(v), ok = kindKey(o);
                if(vk == null || vk.equals(ok)){
                    String key = vk != null ? vk : ok != null ? ok : field.type() instanceof Obj obj ? obj.base() : null;
                    value = merge(v, o, key == null ? null : kind.schema().kind(key));
                }
            }
            entries.put(name, value);
            keys.put(name, child.keyMark(name));
        });
        return new Node.Mapping(entries, keys, child.mark());
    }

    private static boolean holdsObjects(FieldType type){
        return type instanceof Obj || type instanceof Named n && n.allowsObject() || type == FieldType.HOOK;
    }

    private static String kindKey(Node.Mapping m){
        return m.get("kind") instanceof Node.Scalar s && !s.isNull() ? s.value() : null;
    }

    // ids

    /** Listed names keep their {@code ids.yml} id; content the list doesn't name follows, in load order. */
    private void assignIds(){
        for(ContentType type : ContentType.values()){
            List<String> list = new ArrayList<>(pack.ids().of(type));
            Set<String> listed = new HashSet<>(list);
            if(listed.size() != list.size()) diagnostics.error(Mark.none, pack.name() + ": ids.yml lists a " + type.yamlName() + " twice");
            for(Prototype p : prototypes.get(type).values()){
                if(p.isAbstract()) continue;
                boolean vanilla = listed.contains(p.id());
                if(p.wire() == Prototype.Wire.vanilla && !vanilla){
                    diagnostics.error(p.mark(), p.where() + ": wire: vanilla, but ids.yml has no " + type.yamlName() + " " + p.id());
                }else if(p.wire() == Prototype.Wire.added && vanilla){
                    diagnostics.error(p.mark(), p.where() + ": wire: added, but the client already has a " + type.yamlName() + " " + p.id());
                }else if(p.wire() == null && !vanilla){
                    diagnostics.error(p.mark(), p.where() + " is not in ids.yml: new content needs wire: added");
                }
                if(!vanilla) list.add(p.id());
            }
            Map<String, Integer> map = new HashMap<>();
            for(int i = 0; i < list.size(); i++) map.put(list.get(i), i);
            names.put(type, list);
            ids.put(type, map);
        }
    }

    // binding

    private Authored bind(Prototype p, Binder binder){
        Node.Mapping fields = resolve(p);
        String key = kindKey(fields);
        if(key == null){
            diagnostics.error(p.mark(), p.where() + " needs a kind (its own or a parent's)");
            return null;
        }
        KindSchema kind = schema.kind(key);
        if(kind == null){
            diagnostics.error(fields.get("kind"), p.where() + ": unknown kind '" + key + "'");
            return null;
        }
        if(kind.contentType() != p.type()){
            diagnostics.error(fields.get("kind"), p.where() + ": kind " + key + " is not a " + p.type().yamlName() + " kind");
            return null;
        }
        if(kind.isAbstract()){
            diagnostics.error(fields.get("kind"), p.where() + ": kind " + key + " is abstract");
            return null;
        }
        Struct struct = binder.struct(fields, kind, p.id());
        struct = (Struct)resolveValues(struct, new Obj(key), p);
        return new Authored(p.type(), ids.get(p.type()).get(p.id()), p.id(), struct, p.wire() == Prototype.Wire.added, p.mark());
    }

    /** Whether {@code name} is a valid reference of {@code ref}. */
    private String check(Ref ref, String name){
        if(name.equals(Binder.SELF)){
            return ref.type() == current ? null : "self here would be a " + current.yamlName() + ", not a " + ref.name();
        }
        if(ref == Binder.PAYLOAD){
            return ids.get(ContentType.BLOCK).containsKey(name) || ids.get(ContentType.UNIT).containsKey(name) ? null
                : "unknown block or unit '" + name + "'";
        }
        if(ref.type() != null) return ids.get(ref.type()).containsKey(name) ? null : "unknown " + ref.name() + " '" + name + "'";
        return switch(ref.name()){
            case "unit-command", "unit-stance" -> pack.ids().list(ref.name()).contains(name) ? null : "unknown " + ref.name() + " '" + name + "'";
            case "content" -> {
                int colon = name.indexOf(':');
                ContentType type = ContentType.of(name.substring(0, colon));
                yield type == null || ids.get(type).containsKey(name.substring(colon + 1)) ? null : "unknown " + type.yamlName() + " '" + name.substring(colon + 1) + "'";
            }
            default -> null;
        };
    }

    /**
     * Replaces {@code self} with the prototype's own name and puts set elements in canonical order (content by id,
     * enum constants by ordinal, the rest by text), so equal content gives equal values.
     */
    private Object resolveValues(Object value, FieldType type, Prototype p){
        if(value == null) return null;
        return switch(type){
            case Ref r when value.equals(Binder.SELF) -> p.id();
            case Obj o -> {
                var s = (Struct)value;
                Struct.Builder b = null;
                for(FieldSpec f : s.kind().fields()){
                    if(!mayHold(f.type())) continue;
                    Object v = s.get(f), r = resolveValues(v, f.type(), p);
                    if(r != v){
                        if(b == null) b = s.toBuilder();
                        b.set(f.name(), r);
                    }
                }
                yield b == null ? s : b.build();
            }
            case Named n when value instanceof Struct -> resolveValues(value, new Obj(((Struct)value).kind().key()), p);
            case Special sp when value instanceof Struct -> resolveValues(value, new Obj(((Struct)value).kind().key()), p);
            case ListType l -> list((List<?>)value, l.element(), p, false);
            case SetType s -> list((List<?>)value, s.element(), p, true);
            case MapType m -> {
                Map<String, Object> out = new LinkedHashMap<>();
                boolean changed = false;
                for(var e : ((Map<?, ?>)value).entrySet()){
                    Object r = resolveValues(e.getValue(), m.value(), p);
                    changed |= r != e.getValue();
                    out.put((String)e.getKey(), r);
                }
                yield changed ? Collections.unmodifiableMap(out) : value;
            }
            default -> value;
        };
    }

    private Object list(List<?> list, FieldType element, Prototype p, boolean set){
        List<Object> out = new ArrayList<>(list.size());
        boolean changed = false;
        for(Object o : list){
            Object r = resolveValues(o, element, p);
            changed |= r != o;
            out.add(r);
        }
        if(set){
            List<Object> sorted = new ArrayList<>(out);
            sorted.sort(order(element));
            changed |= !sorted.equals(out);
            out = sorted;
        }
        return changed ? Collections.unmodifiableList(out) : list;
    }

    private Comparator<Object> order(FieldType type){
        if(type instanceof Ref r && r.type() != null){
            Map<String, Integer> byName = ids.get(r.type());
            return Comparator.comparingInt(o -> byName.getOrDefault((String)o, Integer.MAX_VALUE));
        }
        if(type instanceof EnumType e && schema.enumConstants(e.enumName()) != null){
            List<String> constants = schema.enumConstants(e.enumName());
            return Comparator.comparingInt(o -> constants.indexOf((String)o));
        }
        return Comparator.comparing(String::valueOf);
    }

    /** Whether values of {@code type} can hold {@code self} or sets. */
    private static boolean mayHold(FieldType type){
        return switch(type){
            case Ref r -> true;
            case Obj o -> true;
            case Named n -> n.allowsObject();
            case Special s -> s == FieldType.HOOK;
            case ListType l -> true;
            case SetType s -> true;
            case MapType m -> mayHold(m.value());
            default -> false;
        };
    }
}
