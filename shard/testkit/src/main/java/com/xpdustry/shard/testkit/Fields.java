package com.xpdustry.shard.testkit;

import com.xpdustry.shard.sim.building.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * The kind-specific fields of one Shard building, as a {@link KindAdapter} writes them: under vanilla's field names,
 * with vanilla's meaning, so the diff can pair them with the oracle's. Values go through {@link Values#of} with
 * {@link ShardNames#ref}, so buildings, units and definitions become references and names.
 */
public final class Fields{
    private static final ConcurrentHashMap<Class<?>, List<Field>> PUBLIC_FIELDS = new ConcurrentHashMap<>();

    private final ShardNames names;
    private final LinkedHashMap<String, Object> values = new LinkedHashMap<>();

    Fields(ShardNames names){
        this.names = names;
    }

    public ShardNames names(){
        return names;
    }

    /** Sets a field; values {@link Values} can't represent are an error. */
    public Fields put(String name, Object value){
        Object normalized = Values.of(value, names::ref);
        if(normalized == Values.UNSUPPORTED){
            throw new IllegalArgumentException("field " + name + ": cannot project a " + value.getClass().getName() + " (convert it first)");
        }
        values.put(name, normalized);
        return this;
    }

    public Fields remove(String name){
        values.remove(name);
        return this;
    }

    /**
     * Every public instance field declared below {@link Building}, superclass fields first, by its Java name. This is
     * the default projection of a kind without an adapter. Fields whose values can't be projected are left out.
     */
    public Fields reflect(Building building){
        for(Field field : publicFields(building.getClass())){
            Object value;
            try{
                value = field.get(building);
            }catch(IllegalAccessException e){
                throw new IllegalStateException(e);
            }
            Object normalized = Values.of(value, names::ref);
            if(normalized != Values.UNSUPPORTED) values.put(field.getName(), normalized);
        }
        return this;
    }

    Map<String, Object> values(){
        return values;
    }

    private static List<Field> publicFields(Class<?> type){
        return PUBLIC_FIELDS.computeIfAbsent(type, t -> {
            var chain = new ArrayList<Class<?>>();
            for(Class<?> c = t; c != null && c != Building.class; c = c.getSuperclass()) chain.addFirst(c);
            var out = new ArrayList<Field>();
            for(Class<?> c : chain){
                for(Field field : c.getDeclaredFields()){
                    int modifiers = field.getModifiers();
                    if(Modifier.isPublic(modifiers) && !Modifier.isStatic(modifiers) && !field.isSynthetic()) out.add(field);
                }
            }
            return List.copyOf(out);
        });
    }
}
