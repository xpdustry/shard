package com.xpdustry.shard.content.schema;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.schema.FieldType.*;
import com.xpdustry.shard.content.yaml.*;

import java.util.*;
import java.util.regex.*;

/**
 * Binds YAML nodes to the plain values {@link FieldType} describes, against a {@link Schema}. Every problem goes to
 * the diagnostics at the node that caused it, and binding goes on with a null in its place, so one load reports
 * every error. Unknown fields are errors: a typo never silently keeps the default.
 */
public final class Binder{
    /** Checks content references; the loader passes one that knows every name in the pack. */
    @FunctionalInterface
    public interface Refs{
        /** Null when {@code name} is a valid reference for {@code ref}, else why it isn't. */
        String check(Ref ref, String name);
    }

    /** References in stacks: the item, liquid, or block or unit of a payload stack. */
    public static final Ref ITEM = new Ref("item", ContentType.ITEM), LIQUID = new Ref("liquid", ContentType.LIQUID),
        PAYLOAD = new Ref("payload", null);

    /** The reference to the content being defined, in a default or a prototype ({@code blendGroup: self}). */
    public static final String SELF = "self";

    private static final Pattern integer = Pattern.compile("[-+]?\\d+"),
        decimal = Pattern.compile("[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?"),
        color = Pattern.compile("#?([0-9a-fA-F]{6}|[0-9a-fA-F]{8})"),
        hook = Pattern.compile("hook:[A-Za-z0-9_$.#-]+");

    private final Schema schema;
    private final Diagnostics diagnostics;
    private final Refs refs;

    /** {@code refs} may be null: references are then only checked for shape (class defaults name no content). */
    public Binder(Schema schema, Diagnostics diagnostics, Refs refs){
        this.schema = schema;
        this.diagnostics = diagnostics;
        this.refs = refs;
    }

    /** A struct of {@code kind}: its class defaults, with the fields {@code map} sets. {@code kind:} keys are skipped. */
    public Struct struct(Node.Mapping map, KindSchema kind, String where){
        Object[] values = kind.defaults().clone();
        fill(values, map, kind, where, Set.of("kind"));
        return new Struct(kind, values);
    }

    /**
     * Binds the fields {@code map} sets into {@code values} (a struct's slots), skipping {@code reserved} keys.
     * Unknown fields are reported at their key.
     */
    public void fill(Object[] values, Node.Mapping map, KindSchema kind, String where, Set<String> reserved){
        map.entries().forEach((name, node) -> {
            if(reserved.contains(name)) return;
            FieldSpec field = kind.field(name);
            if(field == null){
                diagnostics.error(map.keyMark(name), where + ": kind " + kind.key() + " has no field '" + name + "'" + suggest(kind, name));
                return;
            }
            values[field.slot()] = bind(node, field.type(), where + "." + name);
        });
    }

    /** {@code node} as a plain value of {@code type}; null (and a diagnostic) when it doesn't fit. */
    public Object bind(Node node, FieldType type, String where){
        if(node instanceof Node.Scalar s && s.isNull()){
            if(type instanceof Scalar sc && sc != Scalar.STRING && sc != Scalar.COLOR){
                return fail(node, where, "a " + sc.text() + " can't be null");
            }
            return null;
        }
        return switch(type){
            case Scalar s -> scalar(node, s, where);
            case EnumType e -> enumConstant(node, e, where);
            case Ref r -> ref(node, r, where);
            case Named n -> named(node, n, where);
            case Special s -> special(node, s, where);
            case ListType l -> list(node, l.element(), where, false);
            case SetType s -> list(node, s.element(), where, true);
            case MapType m -> map(node, m, where);
            case Obj o -> obj(node, schema.kind(o.base()), where);
        };
    }

    private Object scalar(Node node, Scalar type, String where){
        if(!(node instanceof Node.Scalar s)) return fail(node, where, "expected a " + type.text() + ", got a " + shape(node));
        String text = s.value();
        if(type == Scalar.STRING) return text;
        if(s.quoted() && type != Scalar.COLOR) return fail(node, where, "expected a " + type.text() + ", got the string \"" + text + "\"");
        try{
            return switch(type){
                case BOOL -> switch(text){
                    case "true" -> true;
                    case "false" -> false;
                    default -> fail(node, where, "expected true or false, got '" + text + "'");
                };
                case INT -> integer.matcher(text).matches() ? Integer.parseInt(text) : fail(node, where, "expected an int, got '" + text + "'");
                case LONG -> integer.matcher(text).matches() ? Long.parseLong(text) : fail(node, where, "expected a long, got '" + text + "'");
                case FLOAT -> {
                    Double d = real(text);
                    yield d == null ? fail(node, where, "expected a float, got '" + text + "'") : (Object)Float.parseFloat(special(text));
                }
                case DOUBLE -> {
                    Double d = real(text);
                    yield d == null ? fail(node, where, "expected a double, got '" + text + "'") : (Object)d;
                }
                case COLOR -> {
                    if(!color.matcher(text).matches()) yield fail(node, where, "expected a color #rrggbbaa, got '" + text + "'");
                    String hex = (text.startsWith("#") ? text.substring(1) : text).toLowerCase(Locale.ROOT);
                    yield "#" + (hex.length() == 6 ? hex + "ff" : hex);
                }
                case STRING -> text;
            };
        }catch(NumberFormatException e){
            return fail(node, where, "'" + text + "' is out of range for a " + type.text());
        }
    }

    /** A YAML real ({@code 1}, {@code -2.5e3}, {@code .inf}, {@code .nan}) as a double, or null. */
    private static Double real(String text){
        if(decimal.matcher(text).matches()) return Double.parseDouble(text);
        return switch(text){
            case ".inf", "+.inf", ".Inf", ".INF" -> Double.POSITIVE_INFINITY;
            case "-.inf", "-.Inf", "-.INF" -> Double.NEGATIVE_INFINITY;
            case ".nan", ".NaN", ".NAN" -> Double.NaN;
            default -> null;
        };
    }

    /** {@code text} in a form {@link Float#parseFloat} reads to the same float: parsing the text straight rounds once. */
    private static String special(String text){
        return switch(text){
            case ".inf", "+.inf", ".Inf", ".INF" -> "Infinity";
            case "-.inf", "-.Inf", "-.INF" -> "-Infinity";
            case ".nan", ".NaN", ".NAN" -> "NaN";
            default -> text;
        };
    }

    private Object enumConstant(Node node, EnumType type, String where){
        String text = text(node, where, type.enumName());
        if(text == null) return null;
        List<String> constants = schema.enumConstants(type.enumName());
        if(constants != null && !constants.contains(text)){
            return fail(node, where, "'" + text + "' is not a " + type.enumName() + "; one of " + constants);
        }
        return text;
    }

    private Object ref(Node node, Ref type, String where){
        String text = text(node, where, "a " + type.name() + " name");
        if(text == null) return null;
        if(type.name().equals("content") && text.indexOf(':') < 0) return fail(node, where, "a content reference is type:name, got '" + text + "'");
        return check(node, type, text, where);
    }

    private Object check(Node node, Ref type, String name, String where){
        if(refs == null) return name;
        String problem = refs.check(type, name);
        return problem == null ? name : fail(node, where, problem);
    }

    private Object named(Node node, Named type, String where){
        if(node instanceof Node.Mapping m){
            if(!type.allowsObject()) return fail(node, where, "a " + type.registry() + " is a name, " + type.registry() + ":<name>");
            return obj(m, null, where);
        }
        String text = text(node, where, type.registry() + ":<name>");
        if(text == null) return null;
        if(text.startsWith("hook:")){
            if(!type.allowsHook()) return fail(node, where, "a " + type.registry() + " can't be a hook");
            return hook.matcher(text).matches() ? text : fail(node, where, "malformed hook name '" + text + "'");
        }
        String prefix = type.registry() + ":";
        if(!text.startsWith(prefix)) return fail(node, where, "expected " + prefix + "<name>, got '" + text + "'");
        String name = text.substring(prefix.length());
        // Sounds the extractor couldn't name (generated or loaded at runtime) are written sound:?; clients play them.
        if(type.registry().equals("sound") && name.equals("?")) return text;
        if(!schema.registered(type.registry(), name)) return fail(node, where, "unknown " + type.registry() + " '" + name + "'");
        return text;
    }

    private Object special(Node node, Special type, String where){
        return switch(type.name()){
            case "entity" -> {
                String text = text(node, where, "an entity class");
                if(text == null) yield null;
                yield schema.entity(text) != null ? text : fail(node, where, "unknown entity class '" + text + "'");
            }
            case "ai" -> {
                String text = text(node, where, "a controller kind or a hook");
                if(text == null) yield null;
                if(text.startsWith("hook:")) yield hook.matcher(text).matches() ? text : fail(node, where, "malformed hook name '" + text + "'");
                yield KindKey.valid(text) ? text : fail(node, where, "a controller is a kind key, got '" + text + "'");
            }
            case "hook" -> {
                if(node instanceof Node.Mapping m) yield obj(m, null, where);
                String text = text(node, where, "hook:<name>");
                if(text == null) yield null;
                yield hook.matcher(text).matches() ? text : fail(node, where, "expected hook:<name>, got '" + text + "'");
            }
            case "stack" -> stack(node, ITEM, Scalar.INT, where);
            case "liquid-stack" -> stack(node, LIQUID, Scalar.FLOAT, where);
            case "payload-stack" -> stack(node, PAYLOAD, Scalar.INT, where);
            case "stacks" -> stacks(node, ITEM, Scalar.INT, where);
            case "liquid-stacks" -> stacks(node, LIQUID, Scalar.FLOAT, where);
            case "attribute" -> text(node, where, "an attribute name");
            case "attributes" -> {
                if(!(node instanceof Node.Mapping m)) yield fail(node, where, "attributes are a mapping attribute: float");
                Map<String, Object> out = new LinkedHashMap<>();
                m.entries().forEach((k, v) -> out.put(k, bind(v, Scalar.FLOAT, where + "." + k)));
                yield Collections.unmodifiableMap(out);
            }
            case "team" -> scalar(node, Scalar.INT, where);
            default -> fail(node, where, "unknown special type " + type.name());
        };
    }

    /** {@code {name: amount}}, one entry. */
    private Object stack(Node node, Ref ref, Scalar amount, String where){
        if(!(node instanceof Node.Mapping m) || m.entries().size() != 1) return fail(node, where, "a stack is {name: amount}");
        var e = m.entries().firstEntry();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(stackName(m, e.getKey(), ref, where), bind(e.getValue(), amount, where + "." + e.getKey()));
        return Collections.unmodifiableMap(out);
    }

    /** {@code {copper: 30, lead: 25}}, in written order. */
    private Object stacks(Node node, Ref ref, Scalar amount, String where){
        if(!(node instanceof Node.Mapping m)) return fail(node, where, "stacks are a mapping {name: amount, …}");
        Map<String, Object> out = new LinkedHashMap<>();
        m.entries().forEach((k, v) -> out.put(stackName(m, k, ref, where), bind(v, amount, where + "." + k)));
        return Collections.unmodifiableMap(out);
    }

    private String stackName(Node.Mapping m, String name, Ref ref, String where){
        if(refs != null){
            String problem = refs.check(ref, name);
            if(problem != null) diagnostics.error(m.keyMark(name), where + ": " + problem);
        }
        return name;
    }

    private Object list(Node node, FieldType element, String where, boolean set){
        if(!(node instanceof Node.Sequence seq)) return fail(node, where, "expected a list, got a " + shape(node));
        List<Object> out = new ArrayList<>(seq.items().size());
        for(int i = 0; i < seq.items().size(); i++){
            Object value = bind(seq.items().get(i), element, where + "." + i);
            if(set && value != null && out.contains(value)){
                diagnostics.error(seq.items().get(i), where + ": " + value + " is in the set twice");
                continue;
            }
            out.add(value);
        }
        return Collections.unmodifiableList(out);
    }

    private Object map(Node node, MapType type, String where){
        if(!(node instanceof Node.Mapping m)) return fail(node, where, "expected a mapping, got a " + shape(node));
        Map<String, Object> out = new LinkedHashMap<>();
        m.entries().forEach((k, v) -> {
            Object key = bind(new Node.Scalar(k, false, m.keyMark(k)), type.key(), where + " key");
            if(key == null) return;
            out.put(String.valueOf(key), bind(v, type.value(), where + "." + k));
        });
        return Collections.unmodifiableMap(out);
    }

    /**
     * A sub-object: {@code kind: <key>} picks the kind ({@code base} when absent); it must be {@code base} or extend it,
     * and be one vanilla can build. A null {@code base} accepts any kind, which must then be given.
     */
    private Object obj(Node node, KindSchema base, String where){
        if(!(node instanceof Node.Mapping m)) return fail(node, where, "expected an object (a mapping), got a " + shape(node));
        KindSchema kind = base;
        if(m.get("kind") instanceof Node.Scalar k && !k.isNull()){
            kind = schema.kind(k.value());
            if(kind == null) return fail(k, where, "unknown kind '" + k.value() + "'");
            if(base != null && !kind.isA(base)) return fail(k, where, "kind " + kind.key() + " is not a " + base.key());
        }else if(m.has("kind")){
            return fail(m.get("kind"), where, "kind must be a kind key");
        }
        if(kind == null) return fail(m, where, "this object needs a kind");
        if(kind.isAbstract()) return fail(m, where, "kind " + kind.key() + " is abstract; pick one that extends it");
        if(kind.contentType() != null) return fail(m, where, "kind " + kind.key() + " is content; reference it by name instead");
        return struct(m, kind, where);
    }

    private String text(Node node, String where, String expected){
        if(node instanceof Node.Scalar s) return s.value();
        fail(node, where, "expected " + expected + ", got a " + shape(node));
        return null;
    }

    private Object fail(Node node, String where, String message){
        diagnostics.error(node, where + ": " + message);
        return null;
    }

    private static String shape(Node node){
        return switch(node){
            case Node.Scalar s -> "scalar '" + s.value() + "'";
            case Node.Sequence q -> "list";
            case Node.Mapping m -> "mapping";
        };
    }

    /** ", did you mean 'x'?" for the closest field name within two edits, or nothing. */
    static String suggest(KindSchema kind, String name){
        String best = null;
        int bestDistance = 3;
        for(FieldSpec f : kind.fields()){
            int d = distance(name.toLowerCase(Locale.ROOT), f.name().toLowerCase(Locale.ROOT));
            if(d < bestDistance){
                best = f.name();
                bestDistance = d;
            }
        }
        return best == null ? "" : ", did you mean '" + best + "'?";
    }

    private static int distance(String a, String b){
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for(int j = 0; j <= b.length(); j++) prev[j] = j;
        for(int i = 1; i <= a.length(); i++){
            cur[0] = i;
            for(int j = 1; j <= b.length(); j++){
                cur[j] = Math.min(Math.min(cur[j - 1], prev[j]) + 1, prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
