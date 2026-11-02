package com.xpdustry.shard.content.bake;

import com.xpdustry.shard.content.schema.*;

import java.util.*;

/**
 * A struct being baked: the bake ports vanilla's imperative {@code init()} code, so it works on mutable copies. Nested
 * structs are {@code Mut}s too, lists are {@link ArrayList}s and maps {@link LinkedHashMap}s; {@link #freeze()} turns the
 * tree back into immutable values. Field access is by name and fails loudly for a field the kind doesn't have.
 *
 * <p>Names resolve as the code of {@link #scope} sees them: an init ported from {@code Block} reads {@code Block.size}
 * even on a {@code Cliff}, whose own {@code size} hides it. {@link #view} gives such a scoped view of the same object.
 */
final class Mut{
    final KindSchema kind;
    /** The kind whose fields names resolve to; the object's kind or an ancestor. */
    final KindSchema scope;
    /** The object this is a view of, or itself. */
    final Mut base;
    private final Object[] values;

    private Mut(KindSchema kind, Object[] values){
        this.kind = kind;
        this.scope = kind;
        this.base = this;
        this.values = values;
    }

    private Mut(Mut base, KindSchema scope){
        this.kind = base.kind;
        this.scope = scope;
        this.base = base;
        this.values = base.values;
    }

    /** This object, with names resolved as {@code scope}'s code sees them. */
    Mut view(KindSchema scope){
        return scope == base.scope ? base : new Mut(base, scope);
    }

    /** A fresh object of {@code kind}: its class defaults, as vanilla's constructor leaves them. */
    static Mut create(KindSchema kind){
        return thaw(kind.defaultStruct());
    }

    static Mut thaw(Struct struct){
        Object[] values = struct.values();
        for(int i = 0; i < values.length; i++) values[i] = thawValue(values[i]);
        return new Mut(struct.kind(), values);
    }

    @SuppressWarnings("unchecked")
    static Object thawValue(Object value){
        return switch(value){
            case Struct s -> thaw(s);
            case List<?> l -> {
                List<Object> out = new ArrayList<>(l.size());
                for(Object o : l) out.add(thawValue(o));
                yield out;
            }
            case Map<?, ?> m -> {
                Map<String, Object> out = new LinkedHashMap<>();
                ((Map<String, Object>)m).forEach((k, v) -> out.put(k, thawValue(v)));
                yield out;
            }
            case null, default -> value;
        };
    }

    Struct freeze(){
        Object[] out = new Object[values.length];
        for(int i = 0; i < out.length; i++) out[i] = freezeValue(values[i]);
        return Struct.of(kind, out);
    }

    @SuppressWarnings("unchecked")
    static Object freezeValue(Object value){
        return switch(value){
            case Mut m -> m.freeze();
            case List<?> l -> {
                List<Object> out = new ArrayList<>(l.size());
                for(Object o : l) out.add(freezeValue(o));
                yield out;
            }
            case Map<?, ?> m -> {
                Map<String, Object> out = new LinkedHashMap<>();
                ((Map<String, Object>)m).forEach((k, v) -> out.put(k, freezeValue(v)));
                yield out;
            }
            case null, default -> value;
        };
    }

    /** A deep copy, as vanilla's {@code copy()} of weapons and parts gives. */
    Mut copy(){
        return thaw(freeze());
    }

    private int slot(String name){
        FieldSpec f = scope.field(name);
        if(f == null) throw new IllegalArgumentException("kind " + scope.key() + " has no field " + name);
        return f.slot();
    }

    boolean has(String name){
        return scope.field(name) != null;
    }

    /** Whether this is kind {@code key} or extends it. */
    boolean is(String key){
        return kind.isA(key);
    }

    Object get(String name){
        return values[slot(name)];
    }

    float f(String name){
        return (Float)get(name);
    }

    int i(String name){
        return (Integer)get(name);
    }

    boolean b(String name){
        return (Boolean)get(name);
    }

    String s(String name){
        return (String)get(name);
    }

    Mut m(String name){
        return (Mut)get(name);
    }

    /** Whether two muts are the same object (views of one object are). */
    boolean same(Mut other){
        return other != null && base == other.base;
    }

    @SuppressWarnings("unchecked")
    List<Object> list(String name){
        return (List<Object>)get(name);
    }

    /** The structs of a list field. */
    @SuppressWarnings("unchecked")
    List<Mut> muts(String name){
        return (List<Mut>)(List<?>)get(name);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> map(String name){
        return (Map<String, Object>)get(name);
    }

    Mut set(String name, Object value){
        Object v = value instanceof Struct s ? thaw(s) : value;
        values[slot(name)] = v instanceof Mut m ? m.base : v;
        return this;
    }

    @Override
    public String toString(){
        return kind.key() + freeze();
    }
}
