package com.xpdustry.shard.testkit;

import java.util.*;

/**
 * One scripted change to a world, applied between ticks. Every engine runs the same actions; their YAML form is the
 * {@code do:} name in each javadoc. Tiles are tile coordinates; unit positions are world units (8 per tile).
 */
public sealed interface Action{
    /**
     * {@code place}: a building of {@code block} centered on (x, y), as the map editor places it (nothing is consumed,
     * no construction), then {@code config} if it isn't {@link Config#NONE}.
     */
    record Place(String block, int x, int y, int rotation, String team, Config config) implements Action{
        public Place{
            Objects.requireNonNull(block, "block");
            Objects.requireNonNull(team, "team");
            Objects.requireNonNull(config, "config");
            if(rotation < 0 || rotation > 3) throw new IllegalArgumentException("rotation must be 0..3: " + rotation);
        }
    }

    /** {@code remove}: the building covering (x, y) goes, and its tiles become air. */
    record Remove(int x, int y) implements Action{
    }

    /** {@code configure}: configures the building covering (x, y), as a server-side {@code configureAny}. */
    record Configure(int x, int y, Config config) implements Action{
        public Configure{
            Objects.requireNonNull(config, "config");
        }
    }

    /** {@code items}: adds {@code amount} of {@code item} straight to the item module of the building at (x, y). */
    record Items(int x, int y, String item, int amount) implements Action{
        public Items{
            Objects.requireNonNull(item, "item");
        }
    }

    /** {@code liquid}: adds {@code amount} of {@code liquid} straight to the liquid module of the building at (x, y). */
    record Liquid(int x, int y, String liquid, float amount) implements Action{
        public Liquid{
            Objects.requireNonNull(liquid, "liquid");
        }
    }

    /** {@code core-items}: adds {@code amount} of {@code item} to {@code team}'s core (its first core in vanilla). */
    record CoreItems(String team, String item, int amount) implements Action{
        public CoreItems{
            Objects.requireNonNull(team, "team");
            Objects.requireNonNull(item, "item");
        }
    }

    /** {@code spawn}: a unit of {@code unit}, added at world position (x, y), as vanilla's {@code UnitType.spawn}. */
    record Spawn(String unit, String team, float x, float y, float rotation) implements Action{
        public Spawn{
            Objects.requireNonNull(unit, "unit");
            Objects.requireNonNull(team, "team");
        }
    }

    /** {@code rules}: sets rule fields by vanilla's names ({@code waveSpacing}, {@code waveTimer}…). */
    record Rules(Map<String, Object> set) implements Action{
        public Rules{
            set = plain(set);
        }
    }

    /**
     * Anything else, by name: each runner looks up a handler registered under {@code name}
     * ({@code OracleRunner.on}, {@code ShardRunner.on}). In YAML, any other {@code do:} name, with the remaining keys
     * as {@code args}.
     */
    record Custom(String name, Map<String, Object> args) implements Action{
        public Custom{
            Objects.requireNonNull(name, "name");
            args = plain(args);
        }
    }

    /**
     * Values as YAML reads them back, so a scenario built in Java equals its YAML round trip: integers that fit are
     * {@code Integer}, floats are {@code Double} (by their shortest decimal form), maps are sorted by key (so the
     * written form doesn't depend on a {@code Map.of}'s order) and lists are copied.
     */
    private static Map<String, Object> plain(Map<String, Object> map){
        var out = new TreeMap<String, Object>();
        map.forEach((key, value) -> out.put(Objects.requireNonNull(key, "key"), plainValue(value)));
        return Collections.unmodifiableMap(out);
    }

    @SuppressWarnings("unchecked")
    private static Object plainValue(Object value){
        return switch(value){
            case null -> null;
            case Float f -> Double.parseDouble(f.toString());
            case Long l when l == l.intValue() -> l.intValue();
            case Short s -> s.intValue();
            case Byte b -> b.intValue();
            case Character c -> c.toString();
            case Integer i -> i;
            case Long l -> l;
            case Double d -> d;
            case Boolean b -> b;
            case String s -> s;
            case Enum<?> e -> e.name();
            case Map<?, ?> m -> plain((Map<String, Object>)m);
            case List<?> list -> Collections.unmodifiableList(list.stream().map(Action::plainValue).toList());
            default -> throw new IllegalArgumentException("not a plain value (number, boolean, string, list, map): " + value.getClass().getName());
        };
    }

    /** An action and the tick it runs at: after {@code at} ticks, before tick {@code at + 1}. */
    record Timed(long at, Action action){
        public Timed{
            if(at < 0) throw new IllegalArgumentException("negative tick " + at);
            Objects.requireNonNull(action, "action");
        }
    }
}
