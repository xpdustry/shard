package com.xpdustry.shard.sim.building;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.sim.entity.*;

import java.util.*;

/**
 * An object value as an event keeps it ({@link BuildingEvent.Configured}, a processor's synced variable): immutable,
 * with world references as stable ids, so the event stays the fact it was when published, whatever later changes the
 * array it came from or the building it named (review 38, "Configured events retain mutable values"). The values are
 * vanilla's {@code TypeIO.writeObject} set in {@code :sim}'s terms ({@link Configs}).
 *
 * <p>{@link #of} keeps immutable values as they are: null, boxed primitives, strings, {@link ContentRef} and
 * {@link ContentRef.Foreign}, {@link Point2}, enums (a sensor's {@code LAccess}) and the records here. It turns arrays
 * into records, nested arrays included, a {@link Vec2} into {@link Vec}, a building into its origin tile
 * ({@link BuildingAt}) and any other entity into its id ({@link EntityId}). Anything else is refused, as
 * {@code TypeIO.writeObject} refuses it.
 *
 * <p>Every value a client may send ({@code readObjectSafe}, review 39) has a sim value {@link #of} keeps: the plain
 * ones above, and {@link Team}, {@link TechNode} and {@link IntArray} for the tags {@code :sim} has no type of its own
 * for. A unit command is content ({@link ContentRef.Foreign}), as vanilla reads it.
 */
public sealed interface ConfigValue{
    /**
     * {@code value} as an event keeps it.
     *
     * @throws IllegalArgumentException for a value {@code TypeIO.writeObject} can't write
     */
    static Object of(Object value){
        return switch(value){
            case null -> null;
            case ConfigValue frozen -> frozen;
            case Boolean b -> b;
            case Number n when n instanceof Integer || n instanceof Long || n instanceof Float || n instanceof Double
                || n instanceof Short || n instanceof Byte -> n;
            case Character c -> c;
            case String s -> s;
            case ContentRef ref -> ref;
            case ContentRef.Foreign ref -> ref;
            case Point2 point -> point;
            case Enum<?> constant -> constant;
            case byte[] bytes -> new Bytes(bytes);
            case boolean[] booleans -> new Booleans(booleans);
            case int[] ints -> new Ints(ints);
            case Point2[] points -> new Points(Arrays.asList(points));
            case Vec2[] vecs -> {
                var out = new ArrayList<Vec>(vecs.length);
                for(Vec2 vec : vecs) out.add(vec == null ? null : new Vec(vec.x, vec.y));
                yield new Vecs(out);
            }
            case Object[] values -> {
                var out = new ArrayList<Object>(values.length);
                for(Object element : values) out.add(of(element));
                yield new Values(out);
            }
            case Vec2 vec -> new Vec(vec.x, vec.y);
            case Building building -> new BuildingAt(building.tile());
            case Entity entity -> new EntityId(entity.id());
            default -> throw new IllegalArgumentException("an event can't keep a " + value.getClass().getName());
        };
    }

    /** A {@code byte[]}, such as a processor's compressed code. */
    record Bytes(byte[] bytes) implements ConfigValue{
        public Bytes{
            bytes = bytes.clone();
        }

        /** A copy. */
        @Override
        public byte[] bytes(){
            return bytes.clone();
        }

        @Override
        public boolean equals(Object other){
            return other instanceof Bytes o && Arrays.equals(bytes, o.bytes);
        }

        @Override
        public int hashCode(){
            return Arrays.hashCode(bytes);
        }

        @Override
        public String toString(){
            return "Bytes" + Arrays.toString(bytes);
        }
    }

    /** A {@code boolean[]}. */
    record Booleans(boolean[] booleans) implements ConfigValue{
        public Booleans{
            booleans = booleans.clone();
        }

        /** A copy. */
        @Override
        public boolean[] booleans(){
            return booleans.clone();
        }

        @Override
        public boolean equals(Object other){
            return other instanceof Booleans o && Arrays.equals(booleans, o.booleans);
        }

        @Override
        public int hashCode(){
            return Arrays.hashCode(booleans);
        }

        @Override
        public String toString(){
            return "Booleans" + Arrays.toString(booleans);
        }
    }

    /** An {@code int[]}. */
    record Ints(int[] ints) implements ConfigValue{
        public Ints{
            ints = ints.clone();
        }

        /** A copy. */
        @Override
        public int[] ints(){
            return ints.clone();
        }

        @Override
        public boolean equals(Object other){
            return other instanceof Ints o && Arrays.equals(ints, o.ints);
        }

        @Override
        public int hashCode(){
            return Arrays.hashCode(ints);
        }

        @Override
        public String toString(){
            return "Ints" + Arrays.toString(ints);
        }
    }

    /** A {@code Point2[]}, such as a power node's links; null elements stay null. */
    record Points(List<Point2> points) implements ConfigValue{
        public Points{
            points = Collections.unmodifiableList(new ArrayList<>(points));
        }
    }

    /** An {@code Object[]}, each element as {@link #of} keeps it; null elements stay null. */
    record Values(List<Object> values) implements ConfigValue{
        public Values{
            var out = new ArrayList<Object>(values.size());
            for(Object element : values) out.add(of(element));
            values = Collections.unmodifiableList(out);
        }
    }

    /** A {@link Vec2}. */
    record Vec(float x, float y) implements ConfigValue{
    }

    /** A {@code Vec2[]}; null elements stay null. */
    record Vecs(List<Vec> vecs) implements ConfigValue{
        public Vecs{
            vecs = Collections.unmodifiableList(new ArrayList<>(vecs));
        }
    }

    /**
     * An {@code int[]} as the wire carries one (tag 21; only vanilla's target dummy takes it), told apart from an
     * {@code IntSeq}, which is an {@code int[]} in {@code :sim} ({@link Ints}).
     */
    record IntArray(int[] ints) implements ConfigValue{
        public IntArray{
            ints = ints.clone();
        }

        /** A copy. */
        @Override
        public int[] ints(){
            return ints.clone();
        }

        @Override
        public boolean equals(Object other){
            return other instanceof IntArray o && Arrays.equals(ints, o.ints);
        }

        @Override
        public int hashCode(){
            return Arrays.hashCode(ints);
        }

        @Override
        public String toString(){
            return "IntArray" + Arrays.toString(ints);
        }
    }

    /**
     * The tech node of {@code content}, a {@link ContentRef} or a {@link ContentRef.Foreign} (a sector, a planet):
     * vanilla's {@code content.techNode}, by its content.
     */
    record TechNode(Object content) implements ConfigValue{
        public TechNode{
            if(!(content instanceof ContentRef) && !(content instanceof ContentRef.Foreign)) throw new IllegalArgumentException("not content: " + content);
        }
    }

    /** A building, by its origin tile ({@code y * width + x}): vanilla writes its position. */
    record BuildingAt(int pos) implements ConfigValue{
    }

    /** Another entity (a unit), by its id: vanilla writes its id. */
    record EntityId(int id) implements ConfigValue{
    }

    /** A team, by id: vanilla writes a {@code Team} as its id. */
    record Team(int id) implements ConfigValue{
    }
}
