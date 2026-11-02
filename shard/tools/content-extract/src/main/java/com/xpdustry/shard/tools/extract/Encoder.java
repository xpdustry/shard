package com.xpdustry.shard.tools.extract;

import arc.audio.*;
import arc.func.*;
import arc.graphics.*;
import arc.struct.*;
import mindustry.ctype.*;
import mindustry.entities.*;
import mindustry.entities.bullet.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.blocks.*;
import mindustry.world.meta.*;

import java.lang.reflect.Array;
import java.util.*;
import java.util.function.*;

import com.xpdustry.shard.tools.extract.FieldType.*;
import com.xpdustry.shard.tools.extract.Kinds.*;

/**
 * Turns vanilla values into plain trees (maps, lists, strings, numbers, booleans, null) that the YAML writer prints
 * and that tests compare. Floats stay {@link Float}, so equality is exact.
 *
 * <ul>
 * <li>{@link Mode#pack}: an object writes only the fields that differ from its kind's defaults. Bullets met for the
 * first time are named by their path and handed to the hoist callback, so they become their own prototypes.
 * <li>{@link Mode#defaults}: as pack, without naming anything (for comparing against defaults).
 * <li>{@link Mode#full}: every field of every object, bullets by the names {@code bulletName} gives.
 * </ul>
 */
final class Encoder{
    enum Mode{
        pack, defaults, full
    }

    final Kinds kinds;
    final Names names;
    final Mode mode;
    private final Function<BulletType, String> bulletName;
    private final BiConsumer<BulletType, String> hoist;
    private final IdentityHashMap<BulletType, String> bulletIds;
    private final Map<Kind, Map<String, Object>> effective;
    private Encoder defaultsEncoder;
    /** The content being encoded: references to it are written {@code self} outside full mode. */
    Object self;

    /** A pack or defaults encoder; {@code bulletIds} is shared and grows as pack mode names bullets. */
    Encoder(Kinds kinds, Mode mode, IdentityHashMap<BulletType, String> bulletIds, BiConsumer<BulletType, String> hoist,
            Map<Kind, Map<String, Object>> effective){
        this.kinds = kinds;
        this.names = kinds.names;
        this.mode = mode;
        this.bulletIds = bulletIds;
        this.hoist = hoist;
        this.effective = effective;
        this.bulletName = null;
    }

    /** A full encoder: bullets are written as {@code bulletName} names them. */
    Encoder(Kinds kinds, Function<BulletType, String> bulletName){
        this.kinds = kinds;
        this.names = kinds.names;
        this.mode = Mode.full;
        this.bulletName = bulletName;
        this.bulletIds = null;
        this.hoist = null;
        this.effective = null;
    }

    /** The encoder that writes defaults: this one, or a {@link Mode#defaults} twin sharing names and memos. */
    Encoder defaults(){
        if(mode == Mode.defaults) return this;
        if(defaultsEncoder == null) defaultsEncoder = new Encoder(kinds, Mode.defaults, bulletIds, null, effective);
        return defaultsEncoder;
    }

    /** A content object or bullet: {@code kind} then its fields; in pack mode only those that differ from defaults. */
    Map<String, Object> content(Object content, String path, Function<Prop, Object> valueOf){
        Kind kind = kinds.kind(content.getClass());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind.key);
        Encoder defaults = defaults();
        Object outer = self, outerDefaults = defaults.self;
        self = content;
        defaults.self = content;
        try{
            fields(kind, content, path, valueOf, out);
        }finally{
            self = outer;
            defaults.self = outerDefaults;
        }
        return out;
    }

    private void fields(Kind kind, Object object, String path, Function<Prop, Object> valueOf, Map<String, Object> out){
        Map<String, Object> defaults = mode == Mode.full ? null : effective(kind);
        List<Prop> props = new ArrayList<>(kind.all);
        props.sort(Comparator.comparing(p -> p.name));
        for(Prop prop : props){
            Object raw = valueOf == null ? prop.get(object) : valueOf.apply(prop);
            String child = path.endsWith("/") ? path + prop.name : path + "." + prop.name;
            // a bullet nothing has named yet was made for this object (new MassDriverBolt() in a constructor): never a default
            boolean fresh = defaults != null && raw instanceof BulletType b && !bulletIds.containsKey(b);
            if(defaults != null && !fresh){
                Object plain = defaults().encode(raw, prop.type, child);
                if(Objects.equals(plain, defaults.get(prop.name))) continue;
            }
            out.put(prop.name, encode(raw, prop.type, child));
        }
    }

    /** The values a fresh instance of {@code kind} has, encoded; see {@link Defaults}. */
    Map<String, Object> effective(Kind kind){
        return Defaults.effective(this, kind);
    }

    Map<Kind, Map<String, Object>> effectiveMemo(){
        return effective;
    }

    Object encode(Object v, FieldType type, String path){
        if(v == null) return null;
        return switch(type){
            case Scalar s -> scalar(v, s);
            case EnumType e -> ((Enum<?>)v).name();
            case Ref r -> ref(v, r, path);
            case Named n -> named(v, n, path);
            case Special s -> special(v, s, path);
            case ListType l -> list(v, l, path);
            case SetType s -> set(v, s, path);
            case MapType m -> map(v, m, path);
            case Obj o -> struct(v, path);
        };
    }

    private Object scalar(Object v, Scalar s){
        return switch(s.name()){
            case "bool" -> (Boolean)v;
            case "int" -> v instanceof Character c ? (int)c : ((Number)v).intValue();
            case "long" -> ((Number)v).longValue();
            case "float" -> ((Number)v).floatValue();
            case "double" -> ((Number)v).doubleValue();
            case "string" -> mode != Mode.full && v.toString().startsWith(Kinds.placeholder) ? null : v.toString();
            case "color" -> "#" + v;
            default -> throw new IllegalArgumentException(s.name());
        };
    }

    private Object ref(Object v, Ref r, String path){
        if(v == self && mode != Mode.full) return "self";
        if(v instanceof BulletType b) return bullet(b, path);
        if(r.type() == null) return ((Content)v).getContentType().name() + ":" + ((MappableContent)v).name;
        return ((MappableContent)v).name;
    }

    private String bullet(BulletType b, String path){
        if(mode == Mode.full) return bulletName.apply(b);
        String id = bulletIds.get(b);
        if(id != null) return id;
        // a class default that is a bullet its constructor creates: no default, every prototype writes its own
        if(mode == Mode.defaults) return null;
        int slash = path.indexOf('/');
        id = path;
        if(slash < 0) throw new IllegalStateException("bullet path without owner: " + path);
        bulletIds.put(b, id);
        hoist.accept(b, id);
        return id;
    }

    private Object named(Object v, Named n, String path){
        String name = names.named(v);
        if(name != null){
            if(!name.startsWith(n.prefix() + ":")) throw new IllegalStateException(path + ": " + name + " in a " + n.prefix() + " field");
            return name;
        }
        if(Names.isLambda(v)) return "hook:" + names.hook(v);
        if(v instanceof Sound && !(v instanceof RandomSound)) return "sound:?";
        return struct(v, path);
    }

    private Object special(Object v, Special s, String path){
        return switch(s.name()){
            case "hook" -> Names.isLambda(v) ? "hook:" + names.hook(v) : struct(v, path);
            case "entity" -> {
                Object entity = ((Prov<?>)v).get();
                yield entity == null ? null : entity.getClass().getSimpleName();
            }
            case "ai" -> ai(v);
            case "attribute" -> ((Attribute)v).name;
            case "attributes" -> {
                Map<String, Object> m = new LinkedHashMap<>();
                for(Attribute a : Attribute.all){
                    float f = ((Attributes)v).get(a);
                    if(f != 0f) m.put(a.name, f);
                }
                yield m;
            }
            case "stack" -> {
                var stack = (ItemStack)v;
                yield single(stack.item == null ? "null" : stack.item.name, stack.amount);
            }
            case "stacks" -> stacks(v, path);
            case "liquid-stack" -> {
                var stack = (LiquidStack)v;
                yield single(stack.liquid == null ? "null" : stack.liquid.name, stack.amount);
            }
            case "liquid-stacks" -> stacks(v, path);
            case "payload-stack" -> {
                var stack = (PayloadStack)v;
                yield single(stack.item == null ? "null" : stack.item.name, stack.amount);
            }
            case "team" -> ((Team)v).id;
            default -> throw new IllegalArgumentException(s.name());
        };
    }

    /** Item and liquid stack lists as one ordered map; duplicates fall back to a list of single-entry maps. */
    private Object stacks(Object v, String path){
        List<Object> list = new ArrayList<>();
        each(v, list::add);
        Map<String, Object> map = new LinkedHashMap<>();
        boolean duplicate = false;
        for(Object e : list){
            String key;
            Object amount;
            if(e instanceof ItemStack s){
                key = s.item.name;
                amount = s.amount;
            }else{
                var s = (LiquidStack)e;
                key = s.liquid.name;
                amount = s.amount;
            }
            if(map.put(key, amount) != null) duplicate = true;
        }
        if(!duplicate) return map;
        List<Object> out = new ArrayList<>();
        for(Object e : list) out.add(e instanceof ItemStack s ? single(s.item.name, s.amount) : single(((LiquidStack)e).liquid.name, ((LiquidStack)e).amount));
        return out;
    }

    private static Map<String, Object> single(String key, Object value){
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

    /** A unit's controller factory: {@code default}, or the kind of controller it builds, or a hook. */
    @SuppressWarnings("unchecked")
    private Object ai(Object v){
        if(!Names.isLambda(v)) return "hook:" + v.getClass().getSimpleName();
        String hook = names.hook(v);
        if(!hook.contains("#")) return "hook:" + hook;
        Object controller = null;
        try{
            if(v instanceof Prov<?> p) controller = p.get();
            else if(v instanceof Func<?, ?> f) controller = ((Func<Object, Object>)f).get(null);
        }catch(Throwable ignored){
        }
        return controller == null ? "hook:" + hook : Names.kind(controller.getClass());
    }

    private Object list(Object v, ListType l, String path){
        List<Object> out = new ArrayList<>();
        int[] i = {0};
        each(v, e -> out.add(encode(e, l.element(), path + "." + i[0]++)));
        return out;
    }

    private Object set(Object v, SetType s, String path){
        List<Object> elements = new ArrayList<>();
        each(v, elements::add);
        elements.sort(order(s.element()));
        List<Object> out = new ArrayList<>();
        for(Object e : elements) out.add(encode(e, s.element(), path));
        return out;
    }

    @SuppressWarnings("unchecked")
    private Object map(Object v, MapType m, String path){
        List<Object> keys = new ArrayList<>();
        Function<Object, Object> get;
        boolean ordered = v instanceof OrderedMap<?, ?>;
        if(v instanceof ObjectMap<?, ?> map){
            for(Object k : map.keys().toSeq()) keys.add(k);
            get = k -> ((ObjectMap<Object, Object>)map).get(k);
        }else if(v instanceof ObjectFloatMap<?> map){
            for(Object k : map.keys().toSeq()) keys.add(k);
            get = k -> ((ObjectFloatMap<Object>)map).get(k, 0f);
        }else if(v instanceof ObjectIntMap<?> map){
            for(Object k : map.keys().toSeq()) keys.add(k);
            get = k -> ((ObjectIntMap<Object>)map).get(k, 0);
        }else{
            throw new IllegalArgumentException(path + ": not a map: " + v.getClass());
        }
        if(!ordered) keys.sort(order(m.key()));
        Map<String, Object> out = new LinkedHashMap<>();
        for(Object k : keys){
            String key = String.valueOf(encode(k, m.key(), path));
            out.put(key, encode(get.apply(k), m.value(), path + "." + key));
        }
        return out;
    }

    /** Pack content by id, enums by ordinal, everything else (planets too) by its encoded text, as {@code :content} orders sets. */
    private Comparator<Object> order(FieldType type){
        if(type instanceof Ref r && r.type() != null && Extract.types.contains(r.type())) return Comparator.comparingInt(o -> ((Content)o).id);
        if(type instanceof EnumType) return Comparator.comparingInt(o -> ((Enum<?>)o).ordinal());
        return Comparator.comparing(o -> String.valueOf(defaults().encode(o, type, "")));
    }

    /** A sub-object: {@code kind} and its fields (only non-default ones in pack mode). */
    Map<String, Object> struct(Object v, String path){
        Kind kind = kinds.kind(v.getClass());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind.key);
        fields(kind, v, path, null, out);
        return out;
    }

    static void each(Object v, Consumer<Object> action){
        if(v instanceof Seq<?> seq){
            for(int i = 0; i < seq.size; i++) action.accept(seq.get(i));
        }else if(v instanceof arc.struct.EnumSet<?> set){
            for(int i = 0; i < set.size; i++) action.accept(set.array[i]);
        }else if(v instanceof ObjectSet<?> set){
            for(Object o : set.toSeq()) action.accept(o);
        }else if(v.getClass().isArray()){
            int n = Array.getLength(v);
            for(int i = 0; i < n; i++) action.accept(Array.get(v, i));
        }else if(v instanceof IntSeq seq){
            for(int i = 0; i < seq.size; i++) action.accept(seq.get(i));
        }else if(v instanceof FloatSeq seq){
            for(int i = 0; i < seq.size; i++) action.accept(seq.get(i));
        }else{
            throw new IllegalArgumentException("not a collection: " + v.getClass());
        }
    }
}
