package com.xpdustry.shard.compat.v160.rules;

import java.util.*;
import java.util.regex.*;

/**
 * Arc's JSON dialect: what {@code JsonIO} writes ({@code OutputType.minimal}: unquoted names and values where Arc
 * allows) and what Arc's lenient {@code JsonReader} reads (unquoted strings, comments, newlines as commas).
 *
 * <p>{@link Writer} is Arc's {@code JsonWriter} in minimal mode, quoting and number formatting included;
 * {@link #parse(String)} classifies unquoted tokens as Arc does (only {@code 0-9 + - . e E} can make a number).
 */
public final class ArcJson{
    private static final Pattern MINIMAL_NAME = Pattern.compile("^[^\":,}/ ][^:]*$");
    private static final Pattern MINIMAL_VALUE = Pattern.compile("^[^\":,{\\[\\]/ ][^}\\],]*$");
    private static final Pattern JAVASCRIPT_NAME = Pattern.compile("^[a-zA-Z_$][a-zA-Z_$0-9]*$");

    /** Parses one value; trailing whitespace and comments are allowed, anything else is an error. */
    public static JsonValue parse(String json){
        var parser = new Parser(json);
        parser.skip();
        JsonValue value = parser.value();
        parser.skip();
        if(parser.position < json.length()) throw parser.error("unexpected text after the value");
        return value;
    }

    /** {@code value} in minimal Arc JSON. Numbers keep the text they were parsed from. */
    public static String write(JsonValue value){
        var writer = new Writer();
        writer.tree(value);
        return writer.toString();
    }

    /** Arc's {@code OutputType.minimal.quoteValue} for a string. */
    public static String quoteValue(String string){
        StringBuilder buffer = escape(string);
        if(!string.equals("true") && !string.equals("false") && !string.equals("null")
            && !string.contains("//") && !string.contains("/*")){
            int length = buffer.length();
            if(length > 0 && buffer.charAt(length - 1) != ' ' && MINIMAL_VALUE.matcher(buffer).matches()){
                return buffer.toString();
            }
        }
        return '"' + buffer.toString().replace("\"", "\\\"") + '"';
    }

    /** Arc's {@code OutputType.minimal.quoteName}, which falls through to the javascript rule. */
    public static String quoteName(String name){
        StringBuilder buffer = escape(name);
        if(!name.contains("//") && !name.contains("/*") && MINIMAL_NAME.matcher(buffer).matches()) return buffer.toString();
        if(JAVASCRIPT_NAME.matcher(buffer).matches()) return buffer.toString();
        return '"' + buffer.toString().replace("\"", "\\\"") + '"';
    }

    private static StringBuilder escape(String string){
        var buffer = new StringBuilder(string.length() + 8);
        for(int i = 0; i < string.length(); i++){
            char c = string.charAt(i);
            switch(c){
                case '\\' -> buffer.append("\\\\");
                case '\r' -> buffer.append("\\r");
                case '\n' -> buffer.append("\\n");
                case '\t' -> buffer.append("\\t");
                default -> buffer.append(c);
            }
        }
        return buffer;
    }

    /** Arc's number text: integral values (including {@code -0.0}) as a long, others with their own {@code toString}. */
    public static String number(Number number){
        long longValue = number.longValue();
        if(number.doubleValue() == longValue) return Long.toString(longValue);
        return number.toString();
    }

    /** Arc's {@code JsonWriter} in minimal mode: names, nested objects and arrays, and values. */
    public static final class Writer implements JsonSink{
        private final StringBuilder out = new StringBuilder();
        private final ArrayDeque<boolean[]> stack = new ArrayDeque<>(); // {isArray, needsComma}
        private boolean named;

        @Override
        public Writer name(String name){
            boolean[] current = stack.peek();
            if(current == null || current[0]) throw new IllegalStateException("current item must be an object");
            if(!current[1]){
                current[1] = true;
            }else{
                out.append(',');
            }
            out.append(quoteName(name)).append(':');
            named = true;
            return this;
        }

        @Override
        public Writer object(){
            requireCommaOrName();
            out.append('{');
            stack.push(new boolean[]{false, false});
            return this;
        }

        @Override
        public Writer array(){
            requireCommaOrName();
            out.append('[');
            stack.push(new boolean[]{true, false});
            return this;
        }

        @Override
        public Writer pop(){
            if(named) throw new IllegalStateException("expected a value after a name");
            out.append(stack.pop()[0] ? ']' : '}');
            return this;
        }

        /** A string, number, boolean or null, formatted as Arc's {@code JsonWriter.value}. */
        @Override
        public Writer value(Object value){
            requireCommaOrName();
            out.append(switch(value){
                case null -> "null";
                case Number number -> number(number);
                case Boolean bool -> bool.toString();
                default -> quoteValue(value.toString());
            });
            return this;
        }

        /** A parsed tree; numbers keep their original text. */
        public Writer tree(JsonValue value){
            switch(value.type()){
                case OBJECT -> {
                    object();
                    for(int i = 0; i < value.size(); i++){
                        name(value.names().get(i));
                        tree(value.children().get(i));
                    }
                    pop();
                }
                case ARRAY -> {
                    array();
                    for(JsonValue child : value.children()) tree(child);
                    pop();
                }
                case STRING -> value(value.asString());
                case DOUBLE, LONG -> {
                    requireCommaOrName();
                    out.append(value.asString());
                }
                case BOOLEAN -> value(value.asBoolean());
                case NULL -> value(null);
            }
            return this;
        }

        private void requireCommaOrName(){
            boolean[] current = stack.peek();
            if(current == null) return;
            if(current[0]){
                if(!current[1]){
                    current[1] = true;
                }else{
                    out.append(',');
                }
            }else{
                if(!named) throw new IllegalStateException("a name must be set");
                named = false;
            }
        }

        @Override
        public String toString(){
            return out.toString();
        }
    }

    private static final class Parser{
        final String text;
        int position;

        Parser(String text){
            this.text = text;
        }

        IllegalArgumentException error(String message){
            int from = Math.max(0, position - 20), to = Math.min(text.length(), position + 20);
            return new IllegalArgumentException("Arc JSON: " + message + " at " + position + ": ..." + text.substring(from, to) + "...");
        }

        /** Skips whitespace and comments. */
        void skip(){
            while(position < text.length()){
                char c = text.charAt(position);
                if(c == ' ' || c == '\t' || c == '\r' || c == '\n'){
                    position++;
                }else if(c == '/' && position + 1 < text.length() && text.charAt(position + 1) == '/'){
                    while(position < text.length() && text.charAt(position) != '\n') position++;
                }else if(c == '/' && position + 1 < text.length() && text.charAt(position + 1) == '*'){
                    int end = text.indexOf("*/", position + 2);
                    if(end < 0) throw error("unterminated comment");
                    position = end + 2;
                }else{
                    return;
                }
            }
        }

        JsonValue value(){
            if(position >= text.length()) throw error("expected a value");
            char c = text.charAt(position);
            if(c == '{') return object();
            if(c == '[') return array();
            if(c == '"') return JsonValue.string(quoted());
            return unquotedValue();
        }

        JsonValue object(){
            position++;
            JsonValue object = JsonValue.object();
            while(true){
                skip();
                if(position >= text.length()) throw error("unterminated object");
                char c = text.charAt(position);
                if(c == '}'){
                    position++;
                    return object;
                }
                if(c == ','){
                    position++;
                    continue;
                }
                String name = c == '"' ? quoted() : unquoted(true);
                skip();
                if(position >= text.length() || text.charAt(position) != ':') throw error("expected ':' after " + name);
                position++;
                skip();
                object.add(name, value());
            }
        }

        JsonValue array(){
            position++;
            JsonValue array = JsonValue.array();
            while(true){
                skip();
                if(position >= text.length()) throw error("unterminated array");
                char c = text.charAt(position);
                if(c == ']'){
                    position++;
                    return array;
                }
                if(c == ','){
                    position++;
                    continue;
                }
                array.add(value());
            }
        }

        String quoted(){
            int start = ++position;
            boolean escaped = false;
            while(position < text.length()){
                char c = text.charAt(position);
                if(c == '\\'){
                    escaped = true;
                    position += 2;
                }else if(c == '"'){
                    String raw = text.substring(start, position++);
                    return escaped ? unescape(raw) : raw;
                }else{
                    position++;
                }
            }
            throw error("unterminated string");
        }

        /** An unquoted name (ends at {@code :}) or value (ends at {@code } ] ,}), up to a newline or comment, trimmed. */
        String unquoted(boolean name){
            int start = position;
            boolean escaped = false;
            loop:
            while(position < text.length()){
                char c = text.charAt(position);
                switch(c){
                    case '\\' -> escaped = true;
                    case '/' -> {
                        if(position + 1 < text.length() && (text.charAt(position + 1) == '/' || text.charAt(position + 1) == '*')) break loop;
                    }
                    case '\r', '\n' -> {
                        break loop;
                    }
                    case ':' -> {
                        if(name) break loop;
                    }
                    case '}', ']', ',' -> {
                        if(!name) break loop;
                    }
                    default -> {
                    }
                }
                position++;
            }
            int end = position;
            while(end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
            String raw = text.substring(start, end);
            return escaped ? unescape(raw) : raw;
        }

        JsonValue unquotedValue(){
            String value = unquoted(false);
            if(value.isEmpty()) throw error("expected a value");
            switch(value){
                case "true" -> {
                    return JsonValue.bool(true);
                }
                case "false" -> {
                    return JsonValue.bool(false);
                }
                case "null" -> {
                    return JsonValue.NULL;
                }
                default -> {
                }
            }
            boolean couldBeDouble = false, couldBeLong = true;
            for(int i = 0; i < value.length(); i++){
                char c = value.charAt(i);
                if((c >= '0' && c <= '9') || c == '-' || c == '+') continue;
                if(c == '.' || c == 'e' || c == 'E'){
                    couldBeDouble = true;
                    couldBeLong = false;
                    continue;
                }
                couldBeDouble = false;
                couldBeLong = false;
                break;
            }
            try{
                if(couldBeDouble) return JsonValue.number(Double.parseDouble(value), value);
                if(couldBeLong) return JsonValue.number(Long.parseLong(value), value);
            }catch(NumberFormatException ignored){
                // as Arc: a token that only looks numeric is a string
            }
            return JsonValue.string(value);
        }

        String unescape(String value){
            var buffer = new StringBuilder(value.length());
            for(int i = 0; i < value.length(); ){
                char c = value.charAt(i++);
                if(c != '\\'){
                    buffer.append(c);
                    continue;
                }
                if(i == value.length()) break;
                c = value.charAt(i++);
                if(c == 'u'){
                    buffer.append(Character.toChars(Integer.parseInt(value.substring(i, i + 4), 16)));
                    i += 4;
                    continue;
                }
                buffer.append(switch(c){
                    case '"', '\\', '/' -> c;
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> throw error("illegal escaped character \\" + c);
                });
            }
            return buffer.toString();
        }
    }

    private ArcJson(){}
}
