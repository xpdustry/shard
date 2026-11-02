package com.xpdustry.shard.content.patch;

import java.util.*;

/**
 * Reads a patch's text as vanilla's {@code DataPatcher.apply} does: {@code Jval.read} (Arc's HJSON reader), whose
 * plain output Arc's {@code JsonReader} reads again into a {@code JsonValue}. What that pair keeps is modelled here:
 * <ul>
 *   <li>object members in document order, duplicate keys included (Jval's {@code JsonMap.putAdd});</li>
 *   <li>numbers as a {@code Long} when they have no fraction or exponent and fit, else a {@code Double};</li>
 *   <li>HJSON: braces optional around the root, quoteless keys and values, single quotes, optional commas,
 *   {@code #}, {@code //} and {@code /* *}{@code /} comments.</li>
 * </ul>
 * Multiline {@code '''} strings are refused: Shard doesn't port their indentation rules.
 */
final class PatchJson{
    /** An object's members in document order; a key may repeat. */
    record Obj(List<Map.Entry<String, Object>> members){
        Obj{
            members = List.copyOf(members);
        }

        boolean has(String key){
            for(var m : members) if(m.getKey().equals(key)) return true;
            return false;
        }

        /** The first member named {@code key}, as {@code JsonValue.get} finds it, or null. */
        Object get(String key){
            for(var m : members) if(m.getKey().equals(key)) return m.getValue();
            return null;
        }

        Obj without(String key){
            var out = new ArrayList<Map.Entry<String, Object>>();
            for(var m : members) if(!m.getKey().equals(key)) out.add(m);
            return new Obj(out);
        }
    }

    /** JSON {@code null}. */
    static final Object NULL = new Object(){
        @Override
        public String toString(){
            return "null";
        }
    };

    /** A text vanilla's reader would refuse, or one Shard doesn't read. */
    static final class ParseException extends RuntimeException{
        ParseException(String message){
            super(message, null, false, false);
        }
    }

    private final String text;
    private int index;
    private boolean isArray;

    private PatchJson(String text){
        this.text = text;
    }

    /** The value {@code text} holds: an {@link Obj}, a {@code List}, a {@code String}, {@code Long}, {@code Double}, {@code Boolean} or {@link #NULL}. */
    static Object read(String text){
        var parser = new PatchJson(text);
        parser.skipWhiteSpace();
        int c = parser.current();
        if(c == '[' || c == '{') return parser.checkTrailing(parser.value());
        int start = parser.index;
        try{
            return parser.checkTrailing(parser.object(true));
        }catch(ParseException e){
            parser.index = start;
            try{
                return parser.checkTrailing(parser.value());
            }catch(ParseException ignored){
            }
            throw e;
        }
    }

    private int current(){
        return index < text.length() ? text.charAt(index) : -1;
    }

    private int peek(){
        return index + 1 < text.length() ? text.charAt(index + 1) : -1;
    }

    private ParseException error(String message){
        return new ParseException(message + " at character " + index);
    }

    private Object checkTrailing(Object value){
        skipWhiteSpace();
        if(current() >= 0) throw error("extra characters in input");
        return value;
    }

    private static boolean white(int c){
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private static boolean punctuator(int c){
        return c == '{' || c == '}' || c == '[' || c == ']' || c == ',' || c == ':';
    }

    private void skipWhiteSpace(){
        while(true){
            int c = current();
            if(white(c)){
                index++;
            }else if(c == '#' || c == '/' && peek() == '/'){
                while(current() >= 0 && current() != '\n') index++;
            }else if(c == '/' && peek() == '*'){
                index += 2;
                while(current() >= 0 && !(current() == '*' && peek() == '/')) index++;
                if(current() >= 0) index += 2;
            }else{
                return;
            }
        }
    }

    private Object value(){
        return switch(current()){
            case '"', '\'' -> string(true);
            case '[' -> array();
            case '{' -> object(false);
            default -> quoteless();
        };
    }

    private Obj object(boolean withoutBraces){
        if(!withoutBraces) index++;
        var members = new ArrayList<Map.Entry<String, Object>>();
        skipWhiteSpace();
        while(true){
            if(withoutBraces){
                if(current() < 0) break;
            }else{
                if(current() < 0) throw error("end of input while parsing an object");
                if(current() == '}'){
                    index++;
                    break;
                }
            }
            String name = name();
            skipWhiteSpace();
            if(current() != ':') throw error("expected ':'");
            index++;
            skipWhiteSpace();
            members.add(new AbstractMap.SimpleImmutableEntry<>(name, value()));
            skipWhiteSpace();
            if(current() == ','){
                index++;
                skipWhiteSpace();
            }
        }
        return new Obj(members);
    }

    private List<Object> array(){
        isArray = true;
        index++;
        var out = new ArrayList<Object>();
        skipWhiteSpace();
        if(current() == ']'){
            // Jval quirk kept: an empty array leaves the flag set
            index++;
            return out;
        }
        while(true){
            skipWhiteSpace();
            out.add(value());
            skipWhiteSpace();
            if(current() == ','){
                index++;
                skipWhiteSpace();
            }
            if(current() == ']'){
                index++;
                break;
            }
            if(current() < 0) throw error("end of input while parsing an array");
        }
        // Jval's flag is not a stack: an inner array's end leaves it false for the rest of the outer one
        isArray = false;
        return out;
    }

    private String name(){
        int c = current();
        if(c == '"' || c == '\'') return string(false);
        var name = new StringBuilder();
        int space = -1;
        while(true){
            c = current();
            if(c == ':'){
                if(name.isEmpty()) throw error("':' with no key name");
                if(space >= 0 && space != name.length()) throw error("whitespace in a key name");
                return name.toString();
            }else if(white(c)){
                if(space < 0) space = name.length();
            }else if(c < ' '){
                throw error("name is not closed");
            }else if(punctuator(c)){
                throw error("'" + (char)c + "' where a key name was expected");
            }else{
                name.append((char)c);
            }
            index++;
        }
    }

    private String string(boolean allowMultiline){
        int exit = current();
        index++;
        var out = new StringBuilder();
        while(current() >= 0 && current() != exit){
            if(current() == '\\'){
                index++;
                int c = current();
                switch(c){
                    case '"', '\'', '#', '/', '\\' -> out.append((char)c);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if(index + 4 >= text.length()) throw error("expected a hexadecimal digit");
                        String hex = text.substring(index + 1, index + 5);
                        for(char h : hex.toCharArray()) if(Character.digit(h, 16) < 0) throw error("expected a hexadecimal digit");
                        out.append((char)Integer.parseInt(hex, 16));
                        index += 4;
                    }
                    default -> throw error("expected a valid escape sequence");
                }
                index++;
            }else{
                out.append(text.charAt(index++));
            }
        }
        if(current() >= 0) index++;
        if(allowMultiline && exit == '\'' && current() == '\'' && out.isEmpty()){
            throw error("a multiline ''' string, which Shard doesn't read");
        }
        return out.toString();
    }

    /** {@code readTfnns}: true, false, null, a number or a quoteless string, up to the end of its line. */
    private Object quoteless(){
        int start = index;
        int first = current();
        if(first < 0) throw error("end of input where a value was expected");
        if(punctuator(first)) throw error("'" + (char)first + "' where a quoteless string was expected");
        while(true){
            index++;
            int c = current();
            boolean comment = c == '#' || c == '/' && (peek() == '/' || peek() == '*');
            boolean eol = c < 0 || c == '\r' || c == '\n' || c == ',' && isArray || c == ']' || c == '}' || comment;
            if(eol || c == ','){
                int stop = index;
                if(first == 'f' || first == 'n' || first == 't'){
                    String word = text.substring(start, stop).strip();
                    if(word.equals("false")) return Boolean.FALSE;
                    if(word.equals("null")) return NULL;
                    if(word.equals("true")) return Boolean.TRUE;
                }else if(first == '-' || first >= '0' && first <= '9'){
                    Object n = number(text, start, stop);
                    if(n != null) return n;
                }
                if(eol){
                    int end = stop;
                    if(end > start && text.charAt(end - 1) == ',') end--;
                    return trim(text.substring(start, end));
                }
            }
        }
    }

    private static String trim(String s){
        int a = 0, b = s.length();
        while(a < b && s.charAt(a) <= ' ') a++;
        while(b > a && s.charAt(b - 1) <= ' ') b--;
        return s.substring(a, b);
    }

    /** {@code Hparser.tryParseNumber(buf, from, to, false)}: a number filling the span up to trailing whitespace, else null. */
    static Object number(String buf, int from, int to){
        int i = from;
        if(i < to && buf.charAt(i) == '-') i++;
        if(i >= to) return null;
        char first = buf.charAt(i++);
        if(!digit(first)) return null;
        if(first == '0' && i < to && digit(buf.charAt(i))) return null;
        while(i < to && digit(buf.charAt(i))) i++;
        if(i < to && buf.charAt(i) == '.'){
            i++;
            if(i >= to || !digit(buf.charAt(i++))) return null;
            while(i < to && digit(buf.charAt(i))) i++;
        }
        if(i < to && Character.toLowerCase(buf.charAt(i)) == 'e'){
            i++;
            if(i < to && (buf.charAt(i) == '+' || buf.charAt(i) == '-')) i++;
            if(i >= to || !digit(buf.charAt(i++))) return null;
            while(i < to && digit(buf.charAt(i))) i++;
        }
        int last = i;
        while(i < to && white(buf.charAt(i))) i++;
        if(i < to) return null;
        String number = buf.substring(from, last);
        if(number.indexOf('.') < 0 && number.indexOf('e') < 0 && number.indexOf('E') < 0){
            try{
                return Long.parseLong(number);
            }catch(NumberFormatException ignored){
            }
        }
        return Double.parseDouble(number);
    }

    private static boolean digit(char c){
        return c >= '0' && c <= '9';
    }
}
