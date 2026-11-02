package com.xpdustry.shard.logic;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.lang.*;

import java.util.*;

/**
 * Live logic state for Shard's own checkpoints, forks and hashes: everything a {@link Processor} and a
 * {@link LogicWorld} need to continue exactly as they would have, which {@link LogicSave} (vanilla's save body) does
 * not hold: the logic generator, the throttle's averages, parking, the counter's mode, links as they were last
 * followed, rate-limit deadlines and the host's own per-instruction state. Never v160.7 bytes.
 *
 * <p>The VM writes its own values (numbers, strings, the table's content and teams, {@link LAccess}, lists) and the
 * plain values a sensor may hand it (boxed primitives, {@code byte[]}, {@link Point2}) itself, and hands everything
 * else to {@link Out#host}: the host's objects (buildings, units), which it stores as references.
 * The same traversal feeds a hash, so a hash covers exactly what a checkpoint stores.
 */
public final class LogicState{
    /** The format of {@link #write}; a checkpoint with another one is refused. */
    public static final int VERSION = 1;

    // value tags
    static final int NULL = 0, NUM = 1, STRING = 2, CONTENT = 3, TEAM = 4, ACCESS = 5, LIST = 6, HOST = 7, BOOL = 8, INT = 9,
        LONG = 10, FLOAT = 11, BYTES = 12, POINT = 13;
    private static final LAccess[] access = LAccess.values();
    private static final ContentType[] contentTypes = ContentType.values();

    private LogicState(){
    }

    /** Where state goes: a checkpoint's bytes and references, or a hash. */
    public interface Out{
        void bool(boolean value);

        void i(int value);

        void l(long value);

        void f(float value);

        void d(double value);

        /** A string, or null. */
        void str(String value);

        /** An object of the host's, never null. */
        void host(Object value);
    }

    /** Where state comes back from, in {@link Out}'s order. */
    public interface In{
        boolean bool();

        int i();

        long l();

        float f();

        double d();

        String str();

        Object host();
    }

    /** An {@link Out} into bytes, with the host's objects collected in order for the caller to store as references. */
    public static final class Writer implements Out{
        private final Writes out;
        private final List<Object> objects;

        public Writer(Writes out, List<Object> objects){
            this.out = out;
            this.objects = objects;
        }

        @Override
        public void bool(boolean value){
            out.bool(value);
        }

        @Override
        public void i(int value){
            out.i(value);
        }

        @Override
        public void l(long value){
            out.l(value);
        }

        @Override
        public void f(float value){
            out.f(value);
        }

        @Override
        public void d(double value){
            out.d(value);
        }

        @Override
        public void str(String value){
            out.bool(value != null);
            if(value != null) out.str(value);
        }

        @Override
        public void host(Object value){
            out.i(objects.size());
            objects.add(Objects.requireNonNull(value));
        }
    }

    /** {@link Writer}'s bytes and objects, read back. */
    public static final class Reader implements In{
        private final Reads in;
        private final List<?> objects;

        public Reader(Reads in, List<?> objects){
            this.in = in;
            this.objects = objects;
        }

        @Override
        public boolean bool(){
            return in.bool();
        }

        @Override
        public int i(){
            return in.i();
        }

        @Override
        public long l(){
            return in.l();
        }

        @Override
        public float f(){
            return in.f();
        }

        @Override
        public double d(){
            return in.d();
        }

        @Override
        public String str(){
            return in.bool() ? in.str() : null;
        }

        @Override
        public Object host(){
            int index = in.i();
            if(index < 0 || index >= objects.size()) throw new IllegalArgumentException("host object " + index + " of " + objects.size());
            return Objects.requireNonNull(objects.get(index), "host object " + index);
        }
    }

    /**
     * Writes one value the VM holds (null, a string, the table's content or team, {@link LAccess}, a list, a plain
     * value) or a host object: a memory cell's contents, for instance.
     */
    public static void writeValue(Out out, Object value){
        value(out, value);
    }

    /** Reads a value {@link #writeValue} wrote, with content and teams from {@code table}. */
    public static Object readValue(In in, Globals table){
        return value(in, table);
    }

    /** {@link #copy}, for a host that copies values the VM handed it. */
    public static Object copyValue(Object value, java.util.function.UnaryOperator<Object> remap){
        return copy(value, remap);
    }

    /** A slot's object (or {@link Rt#NUM}), a link target, a host scratch value. */
    static void value(Out out, Object value){
        switch(value){
            case null -> out.i(NULL);
            case String s -> {
                out.i(STRING);
                out.str(s);
            }
            case ContentDef def -> {
                out.i(CONTENT);
                out.i(def.type().ordinal());
                out.i(def.id());
            }
            case LogicTeam team -> {
                out.i(TEAM);
                out.i(team.id());
            }
            case LAccess type -> {
                out.i(ACCESS);
                out.i(type.ordinal());
            }
            case List<?> list -> {
                out.i(LIST);
                out.i(list.size());
                for(Object element : list) value(out, element);
            }
            case Boolean b -> {
                out.i(BOOL);
                out.bool(b);
            }
            case Integer i -> {
                out.i(INT);
                out.i(i);
            }
            case Long l -> {
                out.i(LONG);
                out.l(l);
            }
            case Float f -> {
                out.i(FLOAT);
                out.f(f);
            }
            case byte[] bytes -> {
                out.i(BYTES);
                out.i(bytes.length);
                for(byte b : bytes) out.i(b);
            }
            case Point2 point -> {
                out.i(POINT);
                out.i(point.x());
                out.i(point.y());
            }
            default -> {
                if(value == Rt.NUM){
                    out.i(NUM);
                }else{
                    out.i(HOST);
                    out.host(value);
                }
            }
        }
    }

    static Object value(In in, Globals table){
        int tag = in.i();
        return switch(tag){
            case NULL -> null;
            case NUM -> Rt.NUM;
            case STRING -> in.str();
            case CONTENT -> {
                ContentType type = contentTypes[in.i()];
                yield table.content().get(type, in.i());
            }
            case TEAM -> table.team(in.i());
            case ACCESS -> access[in.i()];
            case LIST -> {
                int size = in.i();
                var list = new ArrayList<Object>(size);
                for(int i = 0; i < size; i++) list.add(value(in, table));
                yield list;
            }
            case HOST -> in.host();
            case BOOL -> in.bool();
            case INT -> in.i();
            case LONG -> in.l();
            case FLOAT -> in.f();
            case BYTES -> {
                var bytes = new byte[in.i()];
                for(int i = 0; i < bytes.length; i++) bytes[i] = (byte)in.i();
                yield bytes;
            }
            case POINT -> new Point2(in.i(), in.i());
            default -> throw new IllegalArgumentException("unknown logic value tag " + tag);
        };
    }

    /**
     * A value for a copy of the state in another world: the VM's own values as they are (they are immutable or the
     * table's, which worlds share), lists element by element, and the host's objects through {@code remap}.
     */
    static Object copy(Object value, java.util.function.UnaryOperator<Object> remap){
        return switch(value){
            case null -> null;
            case String s -> s;
            case ContentDef def -> def;
            case LogicTeam team -> team;
            case LAccess type -> type;
            case List<?> list -> {
                var out = new ArrayList<Object>(list.size());
                for(Object element : list) out.add(copy(element, remap));
                yield out;
            }
            case Boolean b -> b;
            case Integer i -> i;
            case Long l -> l;
            case Float f -> f;
            case byte[] bytes -> bytes.clone();
            case Point2 point -> point;
            default -> value == Rt.NUM ? value : remap.apply(value);
        };
    }

    static void floats(Out out, float[] values){
        out.i(values == null ? -1 : values.length);
        if(values != null) for(float value : values) out.f(value);
    }

    static float[] floats(In in){
        int length = in.i();
        if(length < 0) return null;
        var values = new float[length];
        for(int i = 0; i < length; i++) values[i] = in.f();
        return values;
    }
}
