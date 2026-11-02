package com.xpdustry.shard.content.yaml;

import java.util.*;

/**
 * An immutable YAML node with the place it was written. Scalars keep their text: the schema decides whether
 * {@code 1} is an int, a float or a string, so the reader never guesses.
 */
public sealed interface Node{
    Mark mark();

    /** A scalar. {@code quoted} is true for {@code "…"} and {@code '…'}, which are always strings. */
    record Scalar(String value, boolean quoted, Mark mark) implements Node{
        /** True for an unquoted {@code null} or {@code ~}. */
        public boolean isNull(){
            return !quoted && (value.equals("null") || value.equals("~") || value.isEmpty());
        }

        @Override
        public String toString(){
            return quoted ? '"' + value + '"' : value;
        }
    }

    record Sequence(List<Node> items, Mark mark) implements Node{
        public Sequence{
            items = List.copyOf(items);
        }

        @Override
        public String toString(){
            return items.toString();
        }
    }

    /** A mapping with string keys, in written order. {@code keys} holds where each key was written. */
    record Mapping(SequencedMap<String, Node> entries, Map<String, Mark> keys, Mark mark) implements Node{
        public Mapping{
            entries = new ReadOnlyMap<>(new LinkedHashMap<>(entries));
            keys = Collections.unmodifiableMap(new LinkedHashMap<>(keys));
        }

        public Node get(String key){
            return entries.get(key);
        }

        public boolean has(String key){
            return entries.containsKey(key);
        }

        /** Where {@code key} was written, or this mapping's own mark. */
        public Mark keyMark(String key){
            return keys.getOrDefault(key, mark);
        }

        /** A copy with {@code key} set (appended when new), the key marked at {@code at}. */
        public Mapping with(String key, Node value, Mark at){
            var e = new LinkedHashMap<>(entries);
            var k = new LinkedHashMap<>(keys);
            e.put(key, value);
            k.putIfAbsent(key, at);
            return new Mapping(e, k, mark);
        }

        /** A copy without {@code key}. */
        public Mapping without(String key){
            var e = new LinkedHashMap<>(entries);
            var k = new LinkedHashMap<>(keys);
            e.remove(key);
            k.remove(key);
            return new Mapping(e, k, mark);
        }

        @Override
        public String toString(){
            return entries.toString();
        }
    }

    static Scalar scalar(String value){
        return new Scalar(value, false, Mark.none);
    }

    static Mapping mapping(Mark mark){
        return new Mapping(new LinkedHashMap<>(), Map.of(), mark);
    }
}
