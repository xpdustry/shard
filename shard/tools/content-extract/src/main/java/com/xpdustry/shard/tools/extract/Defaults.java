package com.xpdustry.shard.tools.extract;

import java.util.*;

import com.xpdustry.shard.tools.extract.Kinds.*;

/**
 * Class defaults, as {@code kinds.yml} writes them and {@code :content} reads them back.
 *
 * <ul>
 * <li><b>declared</b>: a field's default in the class that declares it, from a fresh instance of that class. An
 * abstract class has none, so it takes the value most of its instantiable subclasses start with.
 * <li><b>base</b> of a kind: its parent's effective defaults plus its own fields' declared defaults.
 * <li><b>effective</b>: the values of a fresh instance (or the base, for a kind that can't be built).
 * <li><b>overrides</b>: effective minus base, the {@code defaults:} block of a kind in {@code kinds.yml}.
 * </ul>
 */
final class Defaults{
    static Map<String, Object> effective(Encoder encoder, Kind kind){
        var memo = encoder.effectiveMemo();
        Map<String, Object> known = memo.get(kind);
        if(known != null) return known;
        memo.put(kind, Map.of());
        Map<String, Object> out = new LinkedHashMap<>();
        Object fresh = encoder.kinds.fresh(kind);
        if(fresh != null){
            Encoder defaults = encoder.defaults();
            Object outer = defaults.self;
            defaults.self = fresh;
            try{
                for(Prop prop : kind.all) out.put(prop.name, defaults.encode(prop.get(fresh), prop.type, "<default>." + prop.name));
            }finally{
                defaults.self = outer;
            }
        }else{
            out.putAll(base(encoder, kind));
        }
        memo.put(kind, out);
        return out;
    }

    static Map<String, Object> base(Encoder encoder, Kind kind){
        Map<String, Object> out = new LinkedHashMap<>();
        if(kind.parent != null) out.putAll(effective(encoder, kind.parent));
        out.putAll(declared(encoder, kind));
        return out;
    }

    static Map<String, Object> overrides(Encoder encoder, Kind kind){
        Map<String, Object> base = base(encoder, kind), effective = effective(encoder, kind), out = new LinkedHashMap<>();
        for(Prop prop : kind.all){
            Object value = effective.get(prop.name);
            if(!Objects.equals(value, base.get(prop.name))) out.put(prop.name, value);
        }
        return out;
    }

    /** The defaults of the fields {@code kind} declares. */
    static Map<String, Object> declared(Encoder encoder, Kind kind){
        Map<String, Object> out = new LinkedHashMap<>();
        Object fresh = encoder.kinds.fresh(kind);
        if(fresh != null){
            Map<String, Object> effective = effective(encoder, kind);
            for(Prop prop : kind.fields) out.put(prop.name, effective.get(prop.name));
            return out;
        }
        List<Kind> descendants = new ArrayList<>();
        for(Kind other : encoder.kinds.byClass.values()){
            if(other != kind && kind.type.isAssignableFrom(other.type) && encoder.kinds.fresh(other) != null) descendants.add(other);
        }
        descendants.sort(Comparator.comparing(k -> k.key));
        for(Prop prop : kind.fields){
            Map<Object, Integer> counts = new LinkedHashMap<>();
            for(Kind d : descendants){
                counts.merge(Optional.ofNullable(effective(encoder, d).get(prop.name)), 1, Integer::sum);
            }
            Object best = zero(prop.type);
            int bestCount = 0;
            for(var e : counts.entrySet()){
                if(e.getValue() > bestCount){
                    best = ((Optional<?>)e.getKey()).orElse(null);
                    bestCount = e.getValue();
                }
            }
            out.put(prop.name, best);
        }
        return out;
    }

    /** What a field holds before anything sets it: false, 0, or null. */
    static Object zero(FieldType type){
        if(type instanceof FieldType.Scalar s){
            return switch(s.name()){
                case "bool" -> false;
                case "int" -> 0;
                case "long" -> 0L;
                case "float" -> 0f;
                case "double" -> 0d;
                default -> null;
            };
        }
        return null;
    }
}
