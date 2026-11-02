package com.xpdustry.shard.rig;

import java.util.*;

/** Writes maps, lists, strings, numbers, booleans and null as indented JSON. Maps keep their insertion order. */
public final class Json{
    public static String write(Object value){
        var out = new StringBuilder();
        write(value, out, "");
        return out.append('\n').toString();
    }

    static void write(Object value, StringBuilder out, String indent){
        switch(value){
            case null -> out.append("null");
            case Map<?, ?> map -> {
                if(map.isEmpty()){
                    out.append("{}");
                    return;
                }
                out.append("{\n");
                var it = map.entrySet().iterator();
                while(it.hasNext()){
                    var e = it.next();
                    out.append(indent).append("  ");
                    string(String.valueOf(e.getKey()), out);
                    out.append(": ");
                    write(e.getValue(), out, indent + "  ");
                    out.append(it.hasNext() ? ",\n" : "\n");
                }
                out.append(indent).append('}');
            }
            case Collection<?> list -> {
                if(list.isEmpty()){
                    out.append("[]");
                    return;
                }
                out.append("[\n");
                var it = list.iterator();
                while(it.hasNext()){
                    out.append(indent).append("  ");
                    write(it.next(), out, indent + "  ");
                    out.append(it.hasNext() ? ",\n" : "\n");
                }
                out.append(indent).append(']');
            }
            case Number n -> out.append(n);
            case Boolean b -> out.append(b);
            default -> string(String.valueOf(value), out);
        }
    }

    static void string(String s, StringBuilder out){
        out.append('"');
        for(char c : s.toCharArray()){
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
