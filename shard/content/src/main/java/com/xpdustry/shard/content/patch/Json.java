package com.xpdustry.shard.content.patch;

import java.util.*;

/** Writes the plain values patches are made of (maps, lists, strings, numbers, booleans, null) as compact JSON. */
final class Json{
    private Json(){
    }

    static String write(Object value){
        var out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value){
        switch(value){
            case null -> out.append("null");
            case String s -> string(out, s);
            case Boolean b -> out.append(b);
            case Float f -> out.append(number(f));
            case Double d -> out.append(number(d));
            case Number n -> out.append(n);
            case Map<?, ?> m -> {
                out.append('{');
                boolean first = true;
                for(var e : m.entrySet()){
                    if(!first) out.append(',');
                    first = false;
                    string(out, (String)e.getKey());
                    out.append(':');
                    write(out, e.getValue());
                }
                out.append('}');
            }
            case List<?> l -> {
                out.append('[');
                for(int i = 0; i < l.size(); i++){
                    if(i > 0) out.append(',');
                    write(out, l.get(i));
                }
                out.append(']');
            }
            default -> throw new IllegalArgumentException("not a JSON value: " + value.getClass().getName());
        }
    }

    /** The shortest decimal that reads back as the same float ({@code 1.0E-4} is valid JSON). */
    private static String number(float f){
        if(!Float.isFinite(f)) throw new IllegalArgumentException("JSON has no " + f);
        return Float.toString(f);
    }

    private static String number(double d){
        if(!Double.isFinite(d)) throw new IllegalArgumentException("JSON has no " + d);
        return Double.toString(d);
    }

    private static void string(StringBuilder out, String s){
        out.append('"');
        for(int i = 0; i < s.length(); i++){
            char c = s.charAt(i);
            switch(c){
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if(c < 0x20) out.append(String.format("\\u%04x", (int)c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }
}
