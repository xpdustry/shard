package com.xpdustry.shard.testkit;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

/**
 * Turns field values into projection values that both engines agree on: {@code null}, booleans, numbers (in their own
 * boxed type: a {@code float} stays a {@code Float}), strings, lists and string-keyed maps.
 *
 * <ul>
 *   <li>enums become their {@code name()}; characters become strings;</li>
 *   <li>arrays and collections become lists, element by element;</li>
 *   <li>engine objects go through the engine's {@code refs} function first: a building becomes {@code "@x,y"}, a unit
 *   {@code "#id"} and content its name (see {@link #building(int, int)}, {@link #unit(int)});</li>
 *   <li>anything else is {@link #UNSUPPORTED}, and the projection leaves the field out.</li>
 * </ul>
 */
public final class Values{
    /** A value no projection can hold; the field is left out. */
    public static final Object UNSUPPORTED = new Object(){
        @Override
        public String toString(){
            return "(unsupported)";
        }
    };

    /** How a reference to building (x, y) is projected. */
    public static String building(int x, int y){
        return "@" + x + "," + y;
    }

    /** How a reference to the unit with entity id {@code id} is projected. */
    public static String unit(int id){
        return "#" + id;
    }

    /**
     * Normalizes {@code value}. {@code refs} maps engine objects (buildings, units, content) to a projection value,
     * or returns {@link #UNSUPPORTED} for what it doesn't know; it sees every non-JDK object first.
     */
    public static Object of(Object value, Function<Object, Object> refs){
        return of(value, refs, 0);
    }

    private static Object of(Object value, Function<Object, Object> refs, int depth){
        if(depth > 4) return UNSUPPORTED;
        switch(value){
            case null -> {
                return null;
            }
            case Boolean b -> {
                return b;
            }
            case Number n -> {
                return n instanceof Integer || n instanceof Long || n instanceof Float || n instanceof Double
                    || n instanceof Short || n instanceof Byte ? n : UNSUPPORTED;
            }
            case String s -> {
                return s;
            }
            case Character c -> {
                return c.toString();
            }
            case Enum<?> e -> {
                return e.name();
            }
            default -> {
            }
        }
        Class<?> type = value.getClass();
        if(type.isArray()){
            int length = Array.getLength(value);
            var out = new ArrayList<>(length);
            for(int i = 0; i < length; i++){
                Object element = of(Array.get(value, i), refs, depth + 1);
                if(element == UNSUPPORTED) return UNSUPPORTED;
                out.add(element);
            }
            return out;
        }
        if(value instanceof Collection<?> collection){
            var out = new ArrayList<>(collection.size());
            for(Object item : collection){
                Object element = of(item, refs, depth + 1);
                if(element == UNSUPPORTED) return UNSUPPORTED;
                out.add(element);
            }
            return out;
        }
        if(value instanceof Map<?, ?> map){
            var out = new TreeMap<String, Object>();
            for(var entry : map.entrySet()){
                if(!(entry.getKey() instanceof String key)) return UNSUPPORTED;
                Object element = of(entry.getValue(), refs, depth + 1);
                if(element == UNSUPPORTED) return UNSUPPORTED;
                out.put(key, element);
            }
            return out;
        }
        Object mapped = refs.apply(value);
        if(mapped == UNSUPPORTED || mapped == value) return UNSUPPORTED;
        return of(mapped, refs, depth + 1);
    }

    /**
     * Numbers are equal by value, whatever their boxed type; when either is a {@code Float} they compare as floats, so
     * a float read back from JSON as a double still matches. NaN equals NaN.
     */
    public static boolean same(Object a, Object b){
        if(a instanceof Number x && b instanceof Number y){
            if(x instanceof Float || y instanceof Float){
                float fx = x.floatValue(), fy = y.floatValue();
                return fx == fy || (Float.isNaN(fx) && Float.isNaN(fy));
            }
            double dx = x.doubleValue(), dy = y.doubleValue();
            return dx == dy || (Double.isNaN(dx) && Double.isNaN(dy));
        }
        return Objects.equals(a, b);
    }

    /** A value as the reports print it: strings quoted, floats with their own shortest form. */
    public static String show(Object value){
        return switch(value){
            case null -> "null";
            case String s -> '"' + s + '"';
            default -> value.toString();
        };
    }

    private Values(){}
}
