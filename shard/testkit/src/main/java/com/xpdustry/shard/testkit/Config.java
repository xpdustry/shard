package com.xpdustry.shard.testkit;

import java.util.*;

/**
 * A building config, engine neutral: what vanilla's {@code configure(Object)} takes. The oracle runner turns it into
 * vanilla's object ({@code Item}, {@code Point2}, {@code Integer}…); on Shard the kind's {@link KindAdapter} applies it.
 * Points are passed as given: a block that reads them as relative offsets (bridges) gets relative offsets.
 */
public sealed interface Config{
    /** {@code configure(null)}: clears a sorter, an unloader… */
    Config NONE = new None();

    record None() implements Config{
    }

    record Int(int value) implements Config{
    }

    record Bool(boolean value) implements Config{
    }

    record Text(String value) implements Config{
        public Text{
            Objects.requireNonNull(value, "value");
        }
    }

    record Point(int x, int y) implements Config{
    }

    record Points(List<Point> points) implements Config{
        public Points{
            points = List.copyOf(points);
        }
    }

    /** A content reference: {@code type} is {@code item}, {@code liquid}, {@code block} or {@code unit}. */
    record Content(String type, String name) implements Config{
        public static final List<String> TYPES = List.of("item", "liquid", "block", "unit");

        public Content{
            if(!TYPES.contains(type)) throw new IllegalArgumentException("content config type must be one of " + TYPES + ": " + type);
            Objects.requireNonNull(name, "name");
        }
    }

    static Config item(String name){
        return new Content("item", name);
    }

    static Config liquid(String name){
        return new Content("liquid", name);
    }

    static Config block(String name){
        return new Content("block", name);
    }

    static Config unit(String name){
        return new Content("unit", name);
    }

    static Config of(int value){
        return new Int(value);
    }

    static Config of(boolean value){
        return new Bool(value);
    }

    static Config of(String value){
        return new Text(value);
    }

    static Config point(int x, int y){
        return new Point(x, y);
    }

    static Config points(Point... points){
        return new Points(List.of(points));
    }
}
