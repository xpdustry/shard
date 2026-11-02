package com.xpdustry.shard.server.config;

import java.net.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/**
 * The type of a config value: how a YAML scalar or list, or an environment string, becomes a typed value, how it
 * prints, and its JSON Schema. {@link #parse} throws {@link IllegalArgumentException} with a message that names the
 * allowed values; the loader adds the location.
 */
public sealed interface ConfigType<T>{
    /** Parses one scalar: a YAML plain or quoted scalar, or an environment variable's value. */
    T parse(String text);

    /** Parses a YAML sequence. Only list types accept one. */
    default T parseItems(List<String> items){
        throw new IllegalArgumentException("expected " + describe() + ", got a list");
    }

    /** Whether YAML values of this type are sequences. */
    default boolean isList(){
        return false;
    }

    /** The value as it would be written in YAML, and in {@code config explain}. */
    default String format(T value){
        return String.valueOf(value);
    }

    /** A short description of the allowed values, for errors and the reference. */
    String describe();

    /** This type's JSON Schema keywords, without {@code description} or {@code default}. */
    Map<String, Object> schema();

    /** The default as JSON Schema writes it (a string for durations and sizes). */
    default Object json(T value){
        return format(value);
    }

    @SuppressWarnings("unchecked")
    default String formatAny(Object value){
        return format((T)value);
    }

    @SuppressWarnings("unchecked")
    default Object jsonAny(Object value){
        return json((T)value);
    }

    /** {@code true} or {@code false}, nothing else (vanilla read any other spelling as false). */
    record Bool() implements ConfigType<Boolean>{
        @Override
        public Boolean parse(String text){
            return switch(text){
                case "true" -> true;
                case "false" -> false;
                default -> throw new IllegalArgumentException("expected true or false, got '" + text + "'");
            };
        }

        @Override
        public Object json(Boolean value){
            return value;
        }

        @Override
        public String describe(){
            return "true or false";
        }

        @Override
        public Map<String, Object> schema(){
            return ordered("type", "boolean");
        }
    }

    /** A decimal integer in {@code [min, max]}. */
    record Int(int min, int max) implements ConfigType<Integer>{
        @Override
        public Integer parse(String text){
            int value;
            try{
                value = Integer.parseInt(text.replace("_", ""));
            }catch(NumberFormatException e){
                throw new IllegalArgumentException("expected " + describe() + ", got '" + text + "'");
            }
            if(value < min || value > max) throw new IllegalArgumentException("expected " + describe() + ", got " + value);
            return value;
        }

        @Override
        public Object json(Integer value){
            return value;
        }

        @Override
        public String describe(){
            return max == Integer.MAX_VALUE ? "an integer >= " + min : "an integer in [" + min + ", " + max + "]";
        }

        @Override
        public Map<String, Object> schema(){
            var schema = ordered("type", "integer");
            schema.put("minimum", min);
            if(max != Integer.MAX_VALUE) schema.put("maximum", max);
            return schema;
        }
    }

    /** A string of at most {@code maxLength} characters, matching {@code pattern} when there is one. */
    record Text(int maxLength, Pattern pattern, String patternHelp) implements ConfigType<String>{
        public Text(int maxLength){
            this(maxLength, null, null);
        }

        @Override
        public String parse(String text){
            if(text.length() > maxLength){
                throw new IllegalArgumentException("expected at most " + maxLength + " characters, got " + text.length());
            }
            if(pattern != null && !pattern.matcher(text).matches()){
                throw new IllegalArgumentException("expected " + patternHelp + ", got '" + text + "'");
            }
            return text;
        }

        @Override
        public String format(String value){
            return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
        }

        @Override
        public Object json(String value){
            return value;
        }

        @Override
        public String describe(){
            return pattern == null ? "a string of at most " + maxLength + " characters" : patternHelp;
        }

        @Override
        public Map<String, Object> schema(){
            var schema = ordered("type", "string");
            schema.put("maxLength", maxLength);
            if(pattern != null) schema.put("pattern", pattern.pattern());
            return schema;
        }
    }

    /** A YAML list of strings (in an environment variable: comma-separated). */
    record TextList(int maxItems, int maxLength) implements ConfigType<List<String>>{
        @Override
        public List<String> parse(String text){
            if(text.isBlank()) return List.of();
            return parseItems(Arrays.stream(text.split(",")).map(String::trim).toList());
        }

        @Override
        public List<String> parseItems(List<String> items){
            if(items.size() > maxItems) throw new IllegalArgumentException("expected at most " + maxItems + " items, got " + items.size());
            for(String item : items){
                if(item.length() > maxLength){
                    throw new IllegalArgumentException("expected items of at most " + maxLength + " characters, got " + item.length());
                }
            }
            return List.copyOf(items);
        }

        @Override
        public boolean isList(){
            return true;
        }

        @Override
        public String format(List<String> value){
            var text = new Text(maxLength);
            return value.stream().map(text::format).toList().toString();
        }

        @Override
        public Object json(List<String> value){
            return value;
        }

        @Override
        public String describe(){
            return "a list of at most " + maxItems + " strings";
        }

        @Override
        public Map<String, Object> schema(){
            var schema = ordered("type", "array");
            schema.put("maxItems", maxItems);
            schema.put("items", ordered("type", "string", "maxLength", maxLength));
            return schema;
        }
    }

    /**
     * A duration with one unit: {@code 250ms}, {@code 30s}, {@code 10m}, {@code 3h}, {@code 7d}; {@code 0} alone is
     * zero. Prints with the largest unit that divides it.
     */
    record Span(Duration min, Duration max) implements ConfigType<Duration>{
        static final Pattern FORMAT = Pattern.compile("(\\d{1,12})(ms|s|m|h|d)");

        @Override
        public Duration parse(String text){
            Duration value;
            if(text.equals("0")){
                value = Duration.ZERO;
            }else{
                var matcher = FORMAT.matcher(text);
                if(!matcher.matches()) throw new IllegalArgumentException("expected " + describe() + ", got '" + text + "'");
                long amount = Long.parseLong(matcher.group(1));
                value = switch(matcher.group(2)){
                    case "ms" -> Duration.ofMillis(amount);
                    case "s" -> Duration.ofSeconds(amount);
                    case "m" -> Duration.ofMinutes(amount);
                    case "h" -> Duration.ofHours(amount);
                    default -> Duration.ofDays(amount);
                };
            }
            if(value.compareTo(min) < 0 || value.compareTo(max) > 0){
                throw new IllegalArgumentException("expected " + describe() + ", got " + format(value));
            }
            return value;
        }

        @Override
        public String format(Duration value){
            long millis = value.toMillis();
            if(millis == 0) return "0s";
            for(var unit : new Object[][]{{86_400_000L, "d"}, {3_600_000L, "h"}, {60_000L, "m"}, {1000L, "s"}}){
                long size = (long)unit[0];
                if(millis % size == 0) return millis / size + (String)unit[1];
            }
            return millis + "ms";
        }

        @Override
        public String describe(){
            return "a duration from " + format(min) + " to " + format(max) + " (like 250ms, 30s, 10m, 3h, 7d)";
        }

        @Override
        public Map<String, Object> schema(){
            return ordered("type", "string", "pattern", "^(0|\\d{1,12}(ms|s|m|h|d))$");
        }
    }

    /** A byte count with a binary unit: {@code 512KiB}, {@code 5MiB}, {@code 1GiB}, or plain bytes. */
    record Size(long min, long max) implements ConfigType<Long>{
        static final Pattern FORMAT = Pattern.compile("(\\d{1,15})(B|KiB|MiB|GiB)?");

        @Override
        public Long parse(String text){
            var matcher = FORMAT.matcher(text);
            if(!matcher.matches()) throw new IllegalArgumentException("expected " + describe() + ", got '" + text + "'");
            long amount = Long.parseLong(matcher.group(1));
            String unit = matcher.group(2) == null ? "B" : matcher.group(2);
            long value = switch(unit){
                case "KiB" -> amount << 10;
                case "MiB" -> amount << 20;
                case "GiB" -> amount << 30;
                default -> amount;
            };
            if(value < min || value > max) throw new IllegalArgumentException("expected " + describe() + ", got " + format(value));
            return value;
        }

        @Override
        public String format(Long value){
            for(var unit : new Object[][]{{30, "GiB"}, {20, "MiB"}, {10, "KiB"}}){
                long size = 1L << (int)unit[0];
                if(value != 0 && value % size == 0) return value / size + (String)unit[1];
            }
            return value + "B";
        }

        @Override
        public String describe(){
            return "a size from " + format(min) + " to " + format(max) + " (like 512KiB, 5MiB, 1GiB)";
        }

        @Override
        public Map<String, Object> schema(){
            return ordered("type", "string", "pattern", "^\\d{1,15}(B|KiB|MiB|GiB)?$");
        }
    }

    /** One of an enum's constants, written in lower case with dashes. */
    record Choice<E extends Enum<E>>(Class<E> type) implements ConfigType<E>{
        @Override
        public E parse(String text){
            for(E constant : type.getEnumConstants()){
                if(name(constant).equals(text)) return constant;
            }
            throw new IllegalArgumentException("expected " + describe() + ", got '" + text + "'");
        }

        @Override
        public String format(E value){
            return name(value);
        }

        @Override
        public String describe(){
            return "one of " + String.join(", ", names());
        }

        @Override
        public Map<String, Object> schema(){
            return ordered("type", "string", "enum", names());
        }

        List<String> names(){
            return Arrays.stream(type.getEnumConstants()).map(Choice::name).toList();
        }

        static String name(Enum<?> constant){
            return constant.name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /**
     * An IP address literal, never a host name (a name would mean a DNS lookup at load time). With
     * {@code loopbackOnly}, only {@code 127.0.0.0/8} and {@code ::1}: the dev box and the ops API never bind anything
     * else (ARCHITECTURE.md §7).
     */
    record Address(boolean loopbackOnly) implements ConfigType<InetAddress>{
        @Override
        public InetAddress parse(String text){
            InetAddress address;
            try{
                // four dotted parts: the JDK would also read 127.1 as 127.0.0.1
                if(text.indexOf(':') < 0 && !text.matches("\\d{1,3}(\\.\\d{1,3}){3}")) throw new IllegalArgumentException();
                address = InetAddress.ofLiteral(text);
            }catch(IllegalArgumentException e){
                throw new IllegalArgumentException("expected " + describe() + ", got '" + text + "'");
            }
            if(loopbackOnly && !address.isLoopbackAddress()){
                throw new IllegalArgumentException("expected a loopback address (127.0.0.1 or ::1), got " + text
                    + "; expose this through an authenticated reverse proxy instead");
            }
            return address;
        }

        @Override
        public String format(InetAddress value){
            return value.getHostAddress();
        }

        @Override
        public String describe(){
            return loopbackOnly ? "a loopback IP address (127.0.0.1 or ::1)" : "an IP address literal";
        }

        @Override
        public Map<String, Object> schema(){
            var schema = ordered("type", "string");
            schema.put("format", "ip-address");
            if(loopbackOnly) schema.put("pattern", "^(127(\\.\\d{1,3}){3}|::1)$");
            return schema;
        }
    }

    private static Map<String, Object> ordered(Object... pairs){
        var map = new LinkedHashMap<String, Object>();
        for(int i = 0; i < pairs.length; i += 2) map.put((String)pairs[i], pairs[i + 1]);
        return map;
    }
}
