package com.xpdustry.shard.compat.v160.rules;

import java.util.*;

/**
 * A parsed Arc JSON value (Arc's {@code JsonValue}): an object or array with ordered children, a string, a number
 * that keeps its text, a boolean or null. Object members keep their order and duplicates, as Arc's do. The
 * {@code as*} coercions are Arc's.
 */
public final class JsonValue{
    public enum Type{OBJECT, ARRAY, STRING, DOUBLE, LONG, BOOLEAN, NULL}

    private final Type type;
    private final String text;
    private final double doubleValue;
    private final long longValue;
    private final List<JsonValue> children;
    private final List<String> names;

    private JsonValue(Type type, String text, double doubleValue, long longValue, List<JsonValue> children, List<String> names){
        this.type = type;
        this.text = text;
        this.doubleValue = doubleValue;
        this.longValue = longValue;
        this.children = children;
        this.names = names;
    }

    public static JsonValue object(){
        return new JsonValue(Type.OBJECT, null, 0, 0, new ArrayList<>(), new ArrayList<>());
    }

    public static JsonValue array(){
        return new JsonValue(Type.ARRAY, null, 0, 0, new ArrayList<>(), null);
    }

    /** A string, or null for JSON null (as Arc's {@code new JsonValue((String)null)}). */
    public static JsonValue string(String value){
        return value == null ? NULL : new JsonValue(Type.STRING, value, 0, 0, null, null);
    }

    public static JsonValue number(double value, String text){
        return new JsonValue(Type.DOUBLE, text, value, (long)value, null, null);
    }

    public static JsonValue number(long value, String text){
        return new JsonValue(Type.LONG, text, value, value, null, null);
    }

    public static JsonValue bool(boolean value){
        return value ? TRUE : FALSE;
    }

    public static final JsonValue NULL = new JsonValue(Type.NULL, null, 0, 0, null, null);
    private static final JsonValue TRUE = new JsonValue(Type.BOOLEAN, null, 1, 1, null, null);
    private static final JsonValue FALSE = new JsonValue(Type.BOOLEAN, null, 0, 0, null, null);

    /** Appends a member to an object. */
    public JsonValue add(String name, JsonValue value){
        if(type != Type.OBJECT) throw new IllegalStateException("not an object");
        names.add(name);
        children.add(value);
        return this;
    }

    /** Appends an element to an array. */
    public JsonValue add(JsonValue value){
        if(type != Type.ARRAY) throw new IllegalStateException("not an array");
        children.add(value);
        return this;
    }

    public Type type(){
        return type;
    }

    public boolean isObject(){
        return type == Type.OBJECT;
    }

    public boolean isArray(){
        return type == Type.ARRAY;
    }

    public boolean isString(){
        return type == Type.STRING;
    }

    public boolean isNumber(){
        return type == Type.DOUBLE || type == Type.LONG;
    }

    public boolean isBoolean(){
        return type == Type.BOOLEAN;
    }

    public boolean isNull(){
        return type == Type.NULL;
    }

    /** Children of an object or array, in order; empty for other values. */
    public List<JsonValue> children(){
        return children == null ? List.of() : Collections.unmodifiableList(children);
    }

    /** The member names of an object, parallel to {@link #children()}. */
    public List<String> names(){
        return names == null ? List.of() : Collections.unmodifiableList(names);
    }

    public int size(){
        return children == null ? 0 : children.size();
    }

    /** The first member named {@code name}, ignoring case, or null (Arc's {@code get(String)}). */
    public JsonValue get(String name){
        if(names == null) return null;
        for(int i = 0; i < names.size(); i++){
            if(names.get(i) != null && names.get(i).equalsIgnoreCase(name)) return children.get(i);
        }
        return null;
    }

    public boolean has(String name){
        return get(name) != null;
    }

    /** Arc's {@code asString()}: numbers as their text, booleans as {@code true}/{@code false}, null as null. */
    public String asString(){
        return switch(type){
            case STRING -> text;
            case DOUBLE -> text != null ? text : Double.toString(doubleValue);
            case LONG -> text != null ? text : Long.toString(longValue);
            case BOOLEAN -> longValue != 0 ? "true" : "false";
            case NULL -> null;
            case OBJECT, ARRAY -> throw new IllegalStateException("value cannot be converted to string: " + type);
        };
    }

    /** Arc's {@code asFloat()}. */
    public float asFloat(){
        return switch(type){
            case STRING -> Float.parseFloat(text);
            case DOUBLE -> (float)doubleValue;
            case LONG -> (float)longValue;
            case BOOLEAN -> longValue != 0 ? 1 : 0;
            default -> throw new IllegalStateException("value cannot be converted to float: " + type);
        };
    }

    /** Arc's {@code asInt()}. */
    public int asInt(){
        return switch(type){
            case STRING -> Integer.parseInt(text);
            case DOUBLE -> (int)doubleValue;
            case LONG -> (int)longValue;
            case BOOLEAN -> longValue != 0 ? 1 : 0;
            default -> throw new IllegalStateException("value cannot be converted to int: " + type);
        };
    }

    /** Arc's {@code asBoolean()}. */
    public boolean asBoolean(){
        return switch(type){
            case STRING -> text.equalsIgnoreCase("true");
            case DOUBLE -> doubleValue != 0;
            case LONG, BOOLEAN -> longValue != 0;
            default -> throw new IllegalStateException("value cannot be converted to boolean: " + type);
        };
    }

    /** Arc's {@code getString(name, default)}. */
    public String getString(String name, String defaultValue){
        JsonValue child = get(name);
        return child == null || !child.isValue() || child.isNull() ? defaultValue : child.asString();
    }

    /** Arc's {@code getInt(name, default)}. */
    public int getInt(String name, int defaultValue){
        JsonValue child = get(name);
        return child == null || !child.isValue() || child.isNull() ? defaultValue : child.asInt();
    }

    /** Arc's {@code getFloat(name, default)}. */
    public float getFloat(String name, float defaultValue){
        JsonValue child = get(name);
        return child == null || !child.isValue() || child.isNull() ? defaultValue : child.asFloat();
    }

    /** Not an object or array. */
    public boolean isValue(){
        return type != Type.OBJECT && type != Type.ARRAY;
    }

    @Override
    public String toString(){
        return ArcJson.write(this);
    }
}
