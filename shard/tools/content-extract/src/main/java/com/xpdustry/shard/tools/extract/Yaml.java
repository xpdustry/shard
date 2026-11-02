package com.xpdustry.shard.tools.extract;

import java.util.*;
import java.util.regex.*;

/**
 * A small YAML emitter for plain trees (maps, lists, strings, numbers, booleans, null). Output is deterministic and
 * diff-friendly: short scalar-only maps and lists go on one line, everything else is block style. Floats are written
 * with {@link Float#toString}, which reads back to the same float.
 */
final class Yaml{
    /** Flow collections longer than this go to block style. */
    private static final int flowWidth = 100;
    private static final Pattern number = Pattern.compile("[-+]?(\\.[0-9]+|[0-9]+(\\.[0-9]*)?)([eE][-+]?[0-9]+)?|[-+]?\\.(inf|Inf|INF)|\\.(nan|NaN|NAN)|0x[0-9a-fA-F]+|0o[0-7]+");
    private static final Set<String> reserved = Set.of("true", "false", "True", "False", "TRUE", "FALSE", "null", "Null", "NULL", "~", "");

    private final StringBuilder out = new StringBuilder();

    /** A top-level sequence of documents. */
    static String documents(List<?> documents){
        var yaml = new Yaml();
        for(Object doc : documents){
            yaml.item(doc, 0);
        }
        return yaml.out.toString();
    }

    /** A top-level mapping. */
    static String mapping(Map<?, ?> map){
        var yaml = new Yaml();
        yaml.entries(map, 0);
        return yaml.out.toString();
    }

    private void item(Object value, int indent){
        pad(indent).append("- ");
        if(value instanceof Map<?, ?> m && !m.isEmpty() && flow(m) == null){
            boolean first = true;
            for(var e : m.entrySet()){
                if(!first) pad(indent + 2);
                first = false;
                entry(e.getKey(), e.getValue(), indent + 2);
            }
        }else if(value instanceof List<?> l && !l.isEmpty() && flow(l) == null){
            out.append('\n');
            for(Object o : l) item(o, indent + 2);
        }else{
            out.append(inline(value)).append('\n');
        }
    }

    private void entries(Map<?, ?> map, int indent){
        for(var e : map.entrySet()){
            pad(indent);
            entry(e.getKey(), e.getValue(), indent);
        }
    }

    private void entry(Object key, Object value, int indent){
        out.append(scalar(key, false)).append(':');
        if(value instanceof Map<?, ?> m && !m.isEmpty() && flow(m) == null){
            out.append('\n');
            entries(m, indent + 2);
        }else if(value instanceof List<?> l && !l.isEmpty() && flow(l) == null){
            out.append('\n');
            for(Object o : l) item(o, indent + 2);
        }else{
            out.append(' ').append(inline(value)).append('\n');
        }
    }

    private String inline(Object value){
        if(value instanceof Map<?, ?> m) return m.isEmpty() ? "{}" : flow(m);
        if(value instanceof List<?> l) return l.isEmpty() ? "[]" : flow(l);
        return scalar(value, false);
    }

    /** One-line form, or null when the collection holds collections or would be too wide. */
    private static String flow(Object collection){
        var sb = new StringBuilder();
        if(collection instanceof Map<?, ?> m){
            sb.append('{');
            boolean first = true;
            for(var e : m.entrySet()){
                if(e.getValue() instanceof Map<?, ?> || e.getValue() instanceof List<?>) return null;
                if(!first) sb.append(", ");
                first = false;
                sb.append(scalar(e.getKey(), true)).append(": ").append(scalar(e.getValue(), true));
            }
            sb.append('}');
        }else{
            sb.append('[');
            boolean first = true;
            for(Object o : (List<?>)collection){
                if(o instanceof Map<?, ?> || o instanceof List<?>) return null;
                if(!first) sb.append(", ");
                first = false;
                sb.append(scalar(o, true));
            }
            sb.append(']');
        }
        return sb.length() > flowWidth ? null : sb.toString();
    }

    static String scalar(Object v, boolean flow){
        if(v == null) return "null";
        if(v instanceof Boolean || v instanceof Integer || v instanceof Long) return v.toString();
        if(v instanceof Float f){
            if(f.isNaN()) return ".nan";
            if(f.isInfinite()) return f > 0 ? ".inf" : "-.inf";
            return Float.toString(f);
        }
        if(v instanceof Double d){
            if(d.isNaN()) return ".nan";
            if(d.isInfinite()) return d > 0 ? ".inf" : "-.inf";
            return Double.toString(d);
        }
        String s = v.toString();
        return plain(s, flow) ? s : quote(s);
    }

    private static boolean plain(String s, boolean flow){
        if(reserved.contains(s) || number.matcher(s).matches()) return false;
        if(s.charAt(0) == ' ' || s.charAt(s.length() - 1) == ' ') return false;
        if("-?:,[]{}#&*!|>'\"%@`".indexOf(s.charAt(0)) >= 0) return false;
        if(s.contains(": ") || s.contains(" #") || s.endsWith(":")) return false;
        for(int i = 0; i < s.length(); i++){
            char c = s.charAt(i);
            if(c < ' ' || c > '~') return false;
            if(flow && ",[]{}:".indexOf(c) >= 0) return false;
        }
        return true;
    }

    private static String quote(String s){
        var sb = new StringBuilder("\"");
        for(int i = 0; i < s.length(); i++){
            char c = s.charAt(i);
            switch(c){
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                default -> {
                    if(c < ' ' || c == 0x7f) sb.append(String.format("\\u%04x", (int)c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private StringBuilder pad(int n){
        for(int i = 0; i < n; i++) out.append(' ');
        return out;
    }
}
