package com.xpdustry.shard.testkit;

/**
 * One leaf the two sides disagree on.
 *
 * @param entity the entity key, as {@link Flat.Key#toString()} prints it
 * @param label the entity's block, unit type or team, or null
 * @param path the leaf, relative to the entity; "" when the entity exists on one side only
 * @param reference the reference side's value, or {@link #ABSENT}
 * @param candidate the candidate side's value, or {@link #ABSENT}
 * @param tolerance the rule that refused it ({@link Tolerances.Rule#describe()})
 * @param before the reference value at the previous checkpoint, where both sides still agreed, or {@link #ABSENT}
 */
public record Difference(long tick, String entity, String label, String path, Object reference, Object candidate,
                         String tolerance, Object before){
    /** The side has no such leaf or entity. */
    public static final Object ABSENT = new Object(){
        @Override
        public String toString(){
            return "(absent)";
        }
    };

    Difference withBefore(Object before){
        return new Difference(tick, entity, label, path, reference, candidate, tolerance, before);
    }

    /** {@code building(5,2).fields.len}. */
    public String where(){
        return path.isEmpty() ? entity : entity + "." + path;
    }

    static String show(Object value){
        return value == ABSENT ? "(absent)" : Values.show(value);
    }
}
