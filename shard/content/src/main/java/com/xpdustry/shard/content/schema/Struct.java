package com.xpdustry.shard.content.schema;

import java.util.*;

/**
 * An immutable instance of a kind: one value per field slot, typed by the field's {@link FieldType}. Content
 * definitions and their sub-objects (weapons, consumers, shoot patterns…) are structs. Equality is structural and
 * exact (floats by bits).
 */
public final class Struct{
    private final KindSchema kind;
    private final Object[] values;
    private int hash;

    /** Takes ownership of {@code values}; every value must already be immutable. */
    Struct(KindSchema kind, Object[] values){
        this.kind = kind;
        this.values = values;
    }

    /** A struct of {@code kind} with one value per slot; lists and maps are copied unmodifiable. */
    public static Struct of(KindSchema kind, Object[] values){
        if(values.length != kind.slots().size()){
            throw new IllegalArgumentException(kind.key() + " has " + kind.slots().size() + " slots, not " + values.length);
        }
        Object[] copy = new Object[values.length];
        for(int i = 0; i < copy.length; i++) copy[i] = freeze(values[i]);
        return new Struct(kind, copy);
    }

    public KindSchema kind(){
        return kind;
    }

    /** The value of every slot, in a fresh array. */
    public Object[] values(){
        return values.clone();
    }

    public Object get(FieldSpec field){
        if(field.slot() >= values.length || kind.slots().get(field.slot()) != field){
            throw new IllegalArgumentException(field + " is not a field of kind " + kind.key());
        }
        return values[field.slot()];
    }

    /** The value of field {@code name}; throws for a field the kind doesn't have. */
    public Object get(String name){
        return values[spec(name).slot()];
    }

    public boolean has(String name){
        return kind.field(name) != null;
    }

    public float f(String name){
        return (Float)get(name);
    }

    public int i(String name){
        return (Integer)get(name);
    }

    public boolean b(String name){
        return (Boolean)get(name);
    }

    public String s(String name){
        return (String)get(name);
    }

    public Struct struct(String name){
        return (Struct)get(name);
    }

    @SuppressWarnings("unchecked")
    public List<Object> list(String name){
        return (List<Object>)get(name);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> map(String name){
        return (Map<String, Object>)get(name);
    }

    /** The structs in list field {@code name}. */
    public List<Struct> structs(String name){
        List<Struct> out = new ArrayList<>();
        for(Object o : list(name)) out.add((Struct)o);
        return out;
    }

    private FieldSpec spec(String name){
        FieldSpec f = kind.field(name);
        if(f == null) throw new IllegalArgumentException("kind " + kind.key() + " has no field " + name);
        return f;
    }

    public Builder toBuilder(){
        return new Builder(kind, values.clone());
    }

    /**
     * The plain tree: an ordered map with {@code kind} and every field in slot order, sub-objects as nested maps.
     * The extractor writes vanilla's values in the same shape, so the two compare with {@code equals}.
     */
    public Map<String, Object> toPlain(){
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind.key());
        for(FieldSpec f : kind.fields()) out.put(f.name(), plain(values[f.slot()]));
        return out;
    }

    /** {@code value} with every struct replaced by its {@link #toPlain()} map. */
    public static Object plain(Object value){
        return switch(value){
            case Struct s -> s.toPlain();
            case List<?> l -> {
                List<Object> out = new ArrayList<>(l.size());
                for(Object o : l) out.add(plain(o));
                yield out;
            }
            case Map<?, ?> m -> {
                Map<String, Object> out = new LinkedHashMap<>();
                m.forEach((k, v) -> out.put((String)k, plain(v)));
                yield out;
            }
            case null, default -> value;
        };
    }

    @Override
    public boolean equals(Object o){
        return o instanceof Struct s && s.kind == kind && Arrays.equals(values, s.values);
    }

    @Override
    public int hashCode(){
        int h = hash;
        if(h == 0) hash = h = 31 * kind.key().hashCode() + Arrays.hashCode(values);
        return h;
    }

    @Override
    public String toString(){
        return toPlain().toString();
    }

    /** A mutable copy, for the bake. {@link #build()} freezes lists and maps that were set. */
    public static final class Builder{
        private final KindSchema kind;
        private final Object[] values;

        Builder(KindSchema kind, Object[] values){
            this.kind = kind;
            this.values = values;
        }

        public KindSchema kind(){
            return kind;
        }

        public Object get(String name){
            return values[spec(name).slot()];
        }

        public float f(String name){
            return (Float)get(name);
        }

        public int i(String name){
            return (Integer)get(name);
        }

        public boolean b(String name){
            return (Boolean)get(name);
        }

        public String s(String name){
            return (String)get(name);
        }

        @SuppressWarnings("unchecked")
        public List<Object> list(String name){
            return (List<Object>)get(name);
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> map(String name){
            return (Map<String, Object>)get(name);
        }

        public Struct struct(String name){
            return (Struct)get(name);
        }

        public boolean has(String name){
            return kind.field(name) != null;
        }

        public Builder set(String name, Object value){
            FieldSpec f = spec(name);
            values[f.slot()] = freeze(value);
            return this;
        }

        private FieldSpec spec(String name){
            FieldSpec f = kind.field(name);
            if(f == null) throw new IllegalArgumentException("kind " + kind.key() + " has no field " + name);
            return f;
        }

        public Struct build(){
            return new Struct(kind, values.clone());
        }
    }

    /** An immutable copy of a list or map value (nested ones too); other values are already immutable. */
    static Object freeze(Object value){
        return switch(value){
            case List<?> l -> {
                List<Object> out = new ArrayList<>(l.size());
                for(Object o : l) out.add(freeze(o));
                yield Collections.unmodifiableList(out);
            }
            case Map<?, ?> m -> {
                Map<String, Object> out = new LinkedHashMap<>();
                m.forEach((k, v) -> out.put((String)k, freeze(v)));
                yield Collections.unmodifiableMap(out);
            }
            case null, default -> value;
        };
    }
}
