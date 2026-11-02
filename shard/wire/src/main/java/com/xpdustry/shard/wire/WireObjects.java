package com.xpdustry.shard.wire;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.wire.Codecs.*;

/**
 * {@code TypeIO.writeObject}/{@code readObject}: the tagged union behind building and plan configs, logic data and
 * effect data (TypeIO.java:72-308). Java values carry tags 1-4, 10, 11, 14, 16, 21 and 22 directly; the records below
 * stand for the vanilla types behind the other tags, with {@link Point2} for tags 7-8 and {@link Codecs.Vec2} for
 * 18-19.
 *
 * <p>Two read paths, as vanilla has them:
 * <ul>
 * <li>{@link #readObjectSafe}, for every value a client sends: strings up to 1,200 encoded bytes, arrays up to 1,000.
 * <li>{@link #readObject}, for values from the server or a save: strings up to 65,535 bytes, arrays only up to 200
 *     (quirk: the trusted path is the stricter one).
 * </ul>
 * Both reject nested arrays except {@code int[]} (tag 21), which has no size limit but its i16.
 */
public final class WireObjects{
    public static final int NULL = 0, INT = 1, LONG = 2, FLOAT = 3, STRING = 4, CONTENT = 5, INT_SEQ = 6, POINT2 = 7,
        POINT2_ARRAY = 8, TECH_NODE = 9, BOOL = 10, DOUBLE = 11, BUILDING = 12, LACCESS = 13, BYTES = 14, LEGACY = 15,
        BOOL_ARRAY = 16, UNIT = 17, VEC2_ARRAY = 18, VEC2 = 19, TEAM = 20, INT_ARRAY = 21, OBJECT_ARRAY = 22, UNIT_COMMAND = 23;

    /** {@code TypeIO.maxArraySize}, the array limit of the safe path and of every write. */
    public static final int MAX_ARRAY = 1000;
    /** The unsafe path's array limit (TypeIO.java:220). */
    public static final int MAX_ARRAY_UNSAFE = 200;
    /** {@code TypeIO.maxByteArraySize}, on both paths. */
    public static final int MAX_BYTES = 40_000;
    /** A {@code Point2[]} has a u8 count. */
    public static final int MAX_POINTS = 255;
    /** The safe path's string limit, in encoded bytes. */
    public static final int MAX_SAFE_STRING = 1200;
    /** {@code ContentType.all.length} and {@code LAccess.all.length}; tests check them against the schema's enums. */
    public static final int CONTENT_TYPES = 18, LACCESS_VALUES = 77;

    /** Tag 5, {@code Content}: a content type ordinal and an id. */
    public record ContentRef(int type, int id){}

    /** Tag 6, {@code IntSeq}: an i16 count of i32s. */
    public record IntSeqValue(int[] items){}

    /** Tag 9, {@code TechNode}: the node of an unlockable content. */
    public record TechNodeRef(int type, int id){}

    /** Tag 12, {@code Building}: its tile position. */
    public record BuildingRef(int pos){}

    /** Tag 13, {@code LAccess}: an ordinal below 77, sent as an i16. */
    public record LAccessRef(int ordinal){}

    /** Tag 17, {@code Unit}: its id. */
    public record UnitIdRef(int id){}

    /** Tag 20, {@code Team}. */
    public record TeamRef(int id){}

    /** Tag 23, {@code UnitCommand}: written as an i16, read as a u16. */
    public record UnitCommandRef(int id){}

    private WireObjects(){}

    /** {@code writeObject}, with its limits: arrays up to 1,000 (bytes 40,000, points 255), strings 65,535 bytes. */
    public static void writeObject(Writes write, Object value){
        check(value, false, true);
        write(write, value);
    }

    /**
     * {@code writeObject} for a value a client reads with {@code readObjectSafe}: strings must also fit its 1,200 bytes.
     * Vanilla's writer doesn't check this, and its client then fails to read the packet.
     */
    public static void writeObjectSafe(Writes write, Object value){
        check(value, true, true);
        write(write, value);
    }

    /** Throws if {@code value} can't be written, or ({@code safe}) read back by {@code readObjectSafe}. */
    static void check(Object value, boolean safe, boolean allowArrays){
        int length = switch(value){
            case String s -> {
                int bytes = Codecs.utfLength(s);
                if(safe && bytes > MAX_SAFE_STRING) throw new WireException("string of " + bytes + " bytes, over readObjectSafe's " + MAX_SAFE_STRING);
                Codecs.checkUtf(s);
                yield -1;
            }
            case IntSeqValue v -> v.items.length;
            case Point2[] p -> {
                if(p.length > MAX_POINTS) throw new WireException(p.length + " points, over " + MAX_POINTS);
                yield 0;
            }
            case byte[] b -> {
                if(b.length > MAX_BYTES) throw new WireException(b.length + " bytes, over " + MAX_BYTES);
                yield 0;
            }
            case boolean[] b -> b.length;
            case Codecs.Vec2[] v -> v.length;
            case int[] i -> i.length;
            case Object[] o -> {
                if(o.length <= MAX_ARRAY) for(Object element : o) check(element, safe, false);
                yield o.length;
            }
            case null, default -> -1;
        };
        if(length > MAX_ARRAY) throw new WireException("array of " + length + ", over " + MAX_ARRAY);
        if(length >= 0 && !allowArrays && !(value instanceof int[])) throw new WireException("nested arrays are not allowed");
    }

    static void write(Writes write, Object value){
        switch(value){
            case null -> write.b(NULL);
            case Integer i -> {
                write.b(INT);
                write.i(i);
            }
            case Long l -> {
                write.b(LONG);
                write.l(l);
            }
            case Float f -> {
                write.b(FLOAT);
                write.f(f);
            }
            case String s -> {
                write.b(STRING);
                Codecs.writeString(write, s);
            }
            case ContentRef c -> {
                write.b(CONTENT);
                write.b(c.type);
                write.s(c.id);
            }
            case IntSeqValue v -> {
                write.b(INT_SEQ);
                write.s(v.items.length);
                for(int i : v.items) write.i(i);
            }
            case Point2 p -> {
                write.b(POINT2);
                write.i(p.x());
                write.i(p.y());
            }
            case Point2[] points -> {
                write.b(POINT2_ARRAY);
                write.b(points.length);
                for(Point2 p : points) write.i(p.pack());
            }
            case TechNodeRef t -> {
                write.b(TECH_NODE);
                write.b(t.type);
                write.s(t.id);
            }
            case Boolean b -> {
                write.b(BOOL);
                write.bool(b);
            }
            case Double d -> {
                write.b(DOUBLE);
                write.d(d);
            }
            case BuildingRef b -> {
                write.b(BUILDING);
                write.i(b.pos);
            }
            case LAccessRef a -> {
                write.b(LACCESS);
                write.s(a.ordinal);
            }
            case byte[] bytes -> {
                write.b(BYTES);
                write.i(bytes.length);
                write.b(bytes);
            }
            case boolean[] bools -> {
                write.b(BOOL_ARRAY);
                write.i(bools.length);
                for(boolean b : bools) write.bool(b);
            }
            case UnitIdRef u -> {
                write.b(UNIT);
                write.i(u.id);
            }
            case Codecs.Vec2[] vecs -> {
                write.b(VEC2_ARRAY);
                write.s(vecs.length);
                for(Codecs.Vec2 v : vecs){
                    write.f(v.x());
                    write.f(v.y());
                }
            }
            case Codecs.Vec2 v -> {
                write.b(VEC2);
                write.f(v.x());
                write.f(v.y());
            }
            case TeamRef t -> {
                write.b(TEAM);
                write.b(t.id);
            }
            case int[] ints -> {
                write.b(INT_ARRAY);
                Codecs.writeInts(write, ints);
            }
            case Object[] objects -> {
                write.b(OBJECT_ARRAY);
                write.i(objects.length);
                for(Object o : objects) write(write, o);
            }
            case UnitCommandRef c -> {
                write.b(UNIT_COMMAND);
                write.s(c.id);
            }
            default -> throw new WireException("no object tag for " + value.getClass().getName());
        }
    }

    /** {@code readObjectSafe}: what vanilla's server accepts from a client. */
    public static Object readObjectSafe(Reads read, WireContext context){
        return readValue(read, read.b(), true, true, context);
    }

    /** {@code readObject}: values from the server or a save. */
    public static Object readObject(Reads read, WireContext context){
        return readValue(read, read.b(), false, true, context);
    }

    static Object readValue(Reads read, int tag, boolean safe, WireContext context){
        return readValue(read, tag, safe, true, context);
    }

    static Object readValue(Reads read, int tag, boolean safe, boolean allowArrays, WireContext context){
        int maxArray = safe ? MAX_ARRAY : MAX_ARRAY_UNSAFE;
        return switch(tag){
            case NULL -> null;
            case INT -> read.i();
            case LONG -> read.l();
            case FLOAT -> read.f();
            case STRING -> read.b() != 0 ? Codecs.str(read, safe ? MAX_SAFE_STRING : 0) : null;
            case CONTENT -> {
                int type = contentType(read.b());
                yield new ContentRef(type, read.s());
            }
            case INT_SEQ -> {
                noNesting(allowArrays);
                int[] items = new int[length(read, read.s(), maxArray, 4)];
                for(int i = 0; i < items.length; i++) items[i] = read.i();
                yield new IntSeqValue(items);
            }
            case POINT2 -> new Point2(read.i(), read.i());
            case POINT2_ARRAY -> {
                noNesting(allowArrays);
                Point2[] points = new Point2[length(read, read.ub(), MAX_POINTS, 4)];
                for(int i = 0; i < points.length; i++) points[i] = Point2.unpack(read.i());
                yield points;
            }
            case TECH_NODE -> {
                int type = contentType(read.b());
                int id = read.s();
                //vanilla dereferences the content's techNode: a missing or non-unlockable content throws
                if(!context.unlockable(type, id)) throw new WireException("tech node of content " + type + ":" + id + ", which is not unlockable");
                yield new TechNodeRef(type, id);
            }
            case BOOL -> read.bool();
            case DOUBLE -> read.d();
            case BUILDING -> new BuildingRef(read.i());
            case LACCESS -> {
                int ordinal = read.s();
                if(ordinal < 0 || ordinal >= LACCESS_VALUES) throw new WireException("LAccess " + ordinal + " out of range");
                yield new LAccessRef(ordinal);
            }
            case BYTES -> {
                noNesting(allowArrays);
                yield read.b(length(read, read.i(), MAX_BYTES, 1));
            }
            case LEGACY -> {
                read.b();
                yield null;
            }
            case BOOL_ARRAY -> {
                noNesting(allowArrays);
                boolean[] bools = new boolean[length(read, read.i(), maxArray, 1)];
                for(int i = 0; i < bools.length; i++) bools[i] = read.bool();
                yield bools;
            }
            case UNIT -> new UnitIdRef(read.i());
            case VEC2_ARRAY -> {
                noNesting(allowArrays);
                Codecs.Vec2[] vecs = new Codecs.Vec2[length(read, read.s(), maxArray, 8)];
                for(int i = 0; i < vecs.length; i++) vecs[i] = new Codecs.Vec2(read.f(), read.f());
                yield vecs;
            }
            case VEC2 -> new Codecs.Vec2(read.f(), read.f());
            case TEAM -> new TeamRef(read.ub());
            //quirk: readInts checks neither nesting nor size
            case INT_ARRAY -> Codecs.readInts(read);
            case OBJECT_ARRAY -> {
                noNesting(allowArrays);
                Object[] objects = new Object[length(read, read.i(), maxArray, 1)];
                for(int i = 0; i < objects.length; i++) objects[i] = readValue(read, read.b(), safe, false, context);
                yield objects;
            }
            case UNIT_COMMAND -> new UnitCommandRef(read.us());
            default -> throw new WireException("unknown object type " + tag);
        };
    }

    /** {@code ContentType.all[ordinal]}: the ordinal must be one of the 18. */
    static int contentType(int ordinal){
        if(ordinal < 0 || ordinal >= CONTENT_TYPES) throw new WireException("content type " + ordinal + " out of range");
        return ordinal;
    }

    static void noNesting(boolean allowArrays){
        if(!allowArrays) throw new WireException("nested arrays are not allowed");
    }

    /** Vanilla checks {@code length > max} and then allocates, which fails for a negative length. */
    static int length(Reads read, int length, int max, int elementBytes){
        if(length > max) throw new WireException("array of " + length + ", over " + max);
        return Codecs.count(read, length, elementBytes, "array");
    }
}
