package com.xpdustry.shard.sim.persist;

import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.entity.*;

import java.lang.reflect.*;
import java.util.*;

/**
 * Reads the fields of one object from a checkpoint section, by name, in the order {@link CheckpointOut} wrote them.
 * Each read is checked against the class's stored {@link Schema}: reading a field the checkpoint doesn't have at
 * that position (a renamed, added or reordered field) fails with a {@link CheckpointException} that names both.
 */
public final class CheckpointIn{
    final Source source;
    final Reading reading;
    private Schema.Builder[] schemas = new Schema.Builder[8];
    private int[] cursors = new int[8];
    private int depth = -1;

    CheckpointIn(Source source, Reading reading){
        this.source = source;
        this.reading = reading;
    }

    public boolean bool(String name){
        field(name, FieldType.BOOL);
        return source.bool();
    }

    public byte b(String name){
        field(name, FieldType.BYTE);
        return (byte)source.b();
    }

    public short s(String name){
        field(name, FieldType.SHORT);
        return source.s();
    }

    public int i(String name){
        field(name, FieldType.INT);
        return source.i();
    }

    public long l(String name){
        field(name, FieldType.LONG);
        return source.l();
    }

    public float f(String name){
        field(name, FieldType.FLOAT);
        return source.f();
    }

    public double d(String name){
        field(name, FieldType.DOUBLE);
        return source.d();
    }

    public String str(String name){
        field(name, FieldType.STRING);
        return source.str();
    }

    public <E extends Enum<E>> E e(String name, Class<E> type){
        field(name, FieldType.ENUM);
        String constant = source.str();
        if(constant == null) return null;
        try{
            return Enum.valueOf(type, constant);
        }catch(IllegalArgumentException e){
            throw new CheckpointException(type.getName() + " has no constant " + constant + " any more");
        }
    }

    public byte[] bytes(String name){
        field(name, FieldType.BYTES);
        int length = source.i();
        if(length == -1) return null;
        var out = new byte[source.length(length, 1)];
        source.bytes(out, 0, length);
        return out;
    }

    public short[] shorts(String name){
        field(name, FieldType.SHORTS);
        int length = source.i();
        if(length == -1) return null;
        var out = new short[source.length(length, 2)];
        source.shorts(out, 0, length);
        return out;
    }

    /** Reads into {@code into}, which must have the stored length (a final array, a tile column). */
    public void shorts(String name, short[] into){
        field(name, FieldType.SHORTS);
        source.shorts(into, 0, expect(into.length));
    }

    public int[] ints(String name){
        field(name, FieldType.INTS);
        int length = source.i();
        if(length == -1) return null;
        var out = new int[source.length(length, 4)];
        source.ints(out, 0, length);
        return out;
    }

    public void ints(String name, int[] into){
        field(name, FieldType.INTS);
        source.ints(into, 0, expect(into.length));
    }

    public long[] longs(String name){
        field(name, FieldType.LONGS);
        int length = source.i();
        if(length == -1) return null;
        var out = new long[source.length(length, 8)];
        source.longs(out, 0, length);
        return out;
    }

    public float[] floats(String name){
        field(name, FieldType.FLOATS);
        int length = source.i();
        if(length == -1) return null;
        var out = new float[source.length(length, 4)];
        source.floats(out, 0, length);
        return out;
    }

    public void floats(String name, float[] into){
        field(name, FieldType.FLOATS);
        source.floats(into, 0, expect(into.length));
    }

    public double[] doubles(String name){
        field(name, FieldType.DOUBLES);
        int length = source.i();
        if(length == -1) return null;
        var out = new double[source.length(length, 8)];
        source.doubles(out, 0, length);
        return out;
    }

    public void bytes(String name, byte[] into){
        field(name, FieldType.BYTES);
        source.bytes(into, 0, expect(into.length));
    }

    /** A stored {@code IntSeq}, or null. */
    public IntSeq intSeq(String name){
        field(name, FieldType.INT_SEQ);
        int capacity = source.i();
        if(capacity == -1) return null;
        var out = new IntSeq(true, 0);
        readSeq(capacity, out);
        return out;
    }

    /** Reads a stored, non-null {@code IntSeq} into {@code into} (a final field), replacing its array. */
    public void intSeq(String name, IntSeq into){
        field(name, FieldType.INT_SEQ);
        int capacity = source.i();
        if(capacity == -1) throw new CheckpointException(name + " is stored as null but its field is final");
        readSeq(capacity, into);
    }

    private void readSeq(int capacity, IntSeq into){
        var items = new int[source.length(capacity, 4)];
        source.ints(items, 0, capacity);
        int size = source.i();
        if(size < 0 || size > capacity) throw source.corrupt("an IntSeq of size " + size + " in " + capacity);
        into.items = items;
        into.size = size;
        into.ordered = source.bool();
    }

    /** A reference to a world object, or null; it must be a {@code type}. */
    public <T> T ref(String name, Class<T> type){
        field(name, FieldType.REF);
        return reading.object(source.i(), type);
    }

    /** References written with {@link CheckpointOut#refs}, in order, or null; each must be a {@code type} or null. */
    @SuppressWarnings("unchecked")
    public <T> T[] refs(String name, Class<T> type){
        field(name, FieldType.REFS);
        int length = source.i();
        if(length == -1) return null;
        var out = (T[])Array.newInstance(type, source.length(length, 4));
        for(int i = 0; i < length; i++) out[i] = reading.object(source.i(), type);
        return out;
    }

    /** Strings written with {@link CheckpointOut#strs}, in order, unmodifiable, or null. */
    public List<String> strs(String name){
        field(name, FieldType.STRINGS);
        int size = source.i();
        if(size == -1) return null;
        var out = new ArrayList<String>(source.length(size, 4));
        for(int i = 0; i < size; i++) out.add(source.str());
        return Collections.unmodifiableList(out);
    }

    /** An owned object written with {@link CheckpointOut#obj}, or null; it must be a {@code type}. */
    public <T> T obj(String name, Class<T> type){
        field(name, FieldType.OBJECT);
        return object(type);
    }

    /** A list of owned objects, unmodifiable, or null. */
    public <T> List<T> list(String name, Class<T> type){
        field(name, FieldType.LIST);
        int size = source.i();
        if(size == -1) return null;
        source.length(size, 4);
        var out = new ArrayList<T>(size);
        for(int i = 0; i < size; i++) out.add(object(type));
        return Collections.unmodifiableList(out);
    }

    public BlockDef block(String name){
        field(name, FieldType.BLOCK);
        return reading.block(source.i());
    }

    public UnitDef unitType(String name){
        field(name, FieldType.UNIT_TYPE);
        return reading.unitType(source.i());
    }

    /** Checks a field {@link CheckpointOut#implicit} declared; the caller sets it. */
    public void implicit(String name){
        field(name, FieldType.IMPLICIT);
    }

    /** Checks a field {@link CheckpointOut#derived} declared; the caller rebuilds it. */
    public void derived(String name){
        field(name, FieldType.DERIVED);
    }

    void attachments(String name, Entity entity){
        field(name, FieldType.ATTACHMENTS);
        int count = source.i();
        source.length(count, 4);
        for(int i = 0; i < count; i++){
            String key = reading.attachmentKey(source.i());
            AttachmentCodec<Object> codec = Codecs.attachment(key);
            begin(reading.schema("@" + key));
            Object value = codec.read(this);
            end();
            entity.attach(codec.key(), value);
        }
    }

    <T> T object(Class<T> type){
        int index = source.i();
        if(index == -1) return null;
        Class<?> stored = reading.type(index);
        if(!type.isAssignableFrom(stored)) throw new CheckpointException("the checkpoint has a " + stored.getName() + " where the code reads a " + type.getName());
        Plan plan = Codecs.plan(stored);
        begin(reading.schema(stored.getName()));
        Object value = plan.read(this);
        end();
        reading.created(value, plan);
        return type.cast(value);
    }

    void begin(Schema.Builder schema){
        if(++depth == schemas.length){
            schemas = Arrays.copyOf(schemas, depth * 2);
            cursors = Arrays.copyOf(cursors, depth * 2);
        }
        schemas[depth] = schema;
        cursors[depth] = 0;
    }

    void end(){
        Schema.Builder schema = schemas[depth];
        if(cursors[depth] != schema.size){
            throw new CheckpointException(schema.key + ": the checkpoint stores " + schema.size + " fields, the code read "
                + cursors[depth] + " (next stored: " + schema.describe(cursors[depth]) + ")");
        }
        schemas[depth] = null;
        depth--;
    }

    private void field(String name, FieldType type){
        if(depth < 0) throw new IllegalStateException("no object is being read");
        Schema.Builder schema = schemas[depth];
        int cursor = cursors[depth]++;
        if(!schema.matches(cursor, name, type)){
            throw new CheckpointException(schema.key + ": the code reads " + name + ":" + type.name().toLowerCase(Locale.ROOT)
                + " as field " + cursor + " but the checkpoint has " + schema.describe(cursor));
        }
    }

    private int expect(int length){
        int stored = source.i();
        if(stored != length) throw new CheckpointException("an array of " + stored + " is stored where the code has room for " + length);
        return length;
    }
}
