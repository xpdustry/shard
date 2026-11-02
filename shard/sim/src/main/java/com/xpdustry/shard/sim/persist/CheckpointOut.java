package com.xpdustry.shard.sim.persist;

import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Writes the fields of one object into a checkpoint section, by name (see {@link Checkpointable}). Each method writes
 * one field and returns this writer. Names are checked: the first object of a class records the class's
 * {@link Schema}, and every later one must write the same names and types in the same order.
 *
 * <p>References to world objects ({@link #ref}) become object indexes. Owned objects ({@link #obj}, {@link #list})
 * are written inline with their class and their own fields. Content definitions are stored by wire id.
 */
public final class CheckpointOut{
    final Sink sink;
    final Capturing capturing;
    private Schema.Builder[] schemas = new Schema.Builder[8];
    private int[] cursors = new int[8];
    private boolean[] recording = new boolean[8];
    private int depth = -1;

    CheckpointOut(Sink sink, Capturing capturing){
        this.sink = sink;
        this.capturing = capturing;
    }

    public CheckpointOut bool(String name, boolean value){
        field(name, FieldType.BOOL);
        sink.b(value ? 1 : 0);
        return this;
    }

    /** A byte: the low 8 bits of {@code value}. */
    public CheckpointOut b(String name, int value){
        field(name, FieldType.BYTE);
        sink.b(value);
        return this;
    }

    /** A short: the low 16 bits of {@code value}. */
    public CheckpointOut s(String name, int value){
        field(name, FieldType.SHORT);
        sink.s(value);
        return this;
    }

    public CheckpointOut i(String name, int value){
        field(name, FieldType.INT);
        sink.i(value);
        return this;
    }

    public CheckpointOut l(String name, long value){
        field(name, FieldType.LONG);
        sink.l(value);
        return this;
    }

    /** A float, by its raw bits: {@code -0f} and NaN payloads survive. */
    public CheckpointOut f(String name, float value){
        field(name, FieldType.FLOAT);
        sink.f(value);
        return this;
    }

    public CheckpointOut d(String name, double value){
        field(name, FieldType.DOUBLE);
        sink.d(value);
        return this;
    }

    /** A string or null. */
    public CheckpointOut str(String name, String value){
        field(name, FieldType.STRING);
        sink.str(value);
        return this;
    }

    /** An enum constant or null, by name. */
    public CheckpointOut e(String name, Enum<?> value){
        field(name, FieldType.ENUM);
        sink.str(value == null ? null : value.name());
        return this;
    }

    public CheckpointOut bytes(String name, byte[] values){
        return bytes(name, values, values == null ? 0 : values.length);
    }

    /** The first {@code length} values, or null; reads back as an array of that length. */
    public CheckpointOut bytes(String name, byte[] values, int length){
        field(name, FieldType.BYTES);
        if(length(values, length)) sink.bytes(values, 0, length);
        return this;
    }

    public CheckpointOut shorts(String name, short[] values){
        return shorts(name, values, values == null ? 0 : values.length);
    }

    public CheckpointOut shorts(String name, short[] values, int length){
        field(name, FieldType.SHORTS);
        if(length(values, length)) sink.shorts(values, 0, length);
        return this;
    }

    public CheckpointOut ints(String name, int[] values){
        return ints(name, values, values == null ? 0 : values.length);
    }

    public CheckpointOut ints(String name, int[] values, int length){
        field(name, FieldType.INTS);
        if(length(values, length)) sink.ints(values, 0, length);
        return this;
    }

    public CheckpointOut longs(String name, long[] values){
        return longs(name, values, values == null ? 0 : values.length);
    }

    public CheckpointOut longs(String name, long[] values, int length){
        field(name, FieldType.LONGS);
        if(length(values, length)) sink.longs(values, 0, length);
        return this;
    }

    public CheckpointOut floats(String name, float[] values){
        return floats(name, values, values == null ? 0 : values.length);
    }

    public CheckpointOut floats(String name, float[] values, int length){
        field(name, FieldType.FLOATS);
        if(length(values, length)) sink.floats(values, 0, length);
        return this;
    }

    public CheckpointOut doubles(String name, double[] values){
        return doubles(name, values, values == null ? 0 : values.length);
    }

    public CheckpointOut doubles(String name, double[] values, int length){
        field(name, FieldType.DOUBLES);
        if(length(values, length)) sink.doubles(values, 0, length);
        return this;
    }

    /** An {@code IntSeq} or null: its whole backing array, its size and its order flag. */
    public CheckpointOut intSeq(String name, IntSeq values){
        field(name, FieldType.INT_SEQ);
        if(values == null){
            sink.i(-1);
            return this;
        }
        sink.i(values.items.length);
        sink.ints(values.items, 0, values.items.length);
        sink.i(values.size);
        sink.b(values.ordered ? 1 : 0);
        return this;
    }

    /** A reference to a world object (an entity or another {@code Copyable}) or null. */
    public CheckpointOut ref(String name, Copyable value){
        field(name, FieldType.REF);
        sink.i(capturing.indexOf(value));
        return this;
    }

    /** The first {@code length} references of {@code values}, in order, nulls kept; or null. */
    public CheckpointOut refs(String name, Copyable[] values, int length){
        field(name, FieldType.REFS);
        if(length(values, length)){
            for(int i = 0; i < length; i++) sink.i(capturing.indexOf(values[i]));
        }
        return this;
    }

    /** Strings in iteration order, nulls kept, or null: a list, a sorted set, a sorted map's keys and values. */
    public CheckpointOut strs(String name, Collection<String> values){
        field(name, FieldType.STRINGS);
        if(values == null){
            sink.i(-1);
            return this;
        }
        sink.i(values.size());
        for(String value : values) sink.str(value);
        return this;
    }

    /**
     * An object this one owns, or null: a module, a controller, a record. It is written with its class and its own
     * fields, through its {@link Checkpointable} methods, its {@link ClassCodec} or, for records, its components.
     */
    public CheckpointOut obj(String name, Object value){
        field(name, FieldType.OBJECT);
        object(value);
        return this;
    }

    /** A list of owned objects (see {@link #obj}), or null. Nulls inside it are kept. */
    public CheckpointOut list(String name, List<?> values){
        field(name, FieldType.LIST);
        if(values == null){
            sink.i(-1);
            return this;
        }
        sink.i(values.size());
        for(Object value : values) object(value);
        return this;
    }

    /** A block definition or null, by wire id. */
    public CheckpointOut block(String name, BlockDef block){
        field(name, FieldType.BLOCK);
        sink.i(block == null ? -1 : block.id());
        return this;
    }

    /** A unit type or null, by wire id. */
    public CheckpointOut unitType(String name, UnitDef type){
        field(name, FieldType.UNIT_TYPE);
        sink.i(type == null ? -1 : type.id());
        return this;
    }

    /** Declares a field that the reader sets without stored bytes. */
    public CheckpointOut implicit(String name){
        field(name, FieldType.IMPLICIT);
        return this;
    }

    /**
     * Declares a field that is rebuilt after restore rather than stored, for a class this codec can't annotate with
     * {@link Derived}.
     */
    public CheckpointOut derived(String name){
        field(name, FieldType.DERIVED);
        return this;
    }

    /** The entity's authoritative attachments, sorted by key name, through their {@link AttachmentCodec}s. */
    void attachments(String name, Entity entity){
        field(name, FieldType.ATTACHMENTS);
        var values = (Object[])Access.ENTITY_ATTACHMENTS.get(entity);
        var held = new TreeMap<String, Object>();
        for(int id = 0; id < values.length; id++){
            if(values[id] == null) continue;
            AttachmentKey<?> key = Access.key(id);
            if(key.persistence() == Persistence.AUTHORITATIVE) held.put(key.name(), values[id]);
        }
        sink.i(held.size());
        held.forEach((key, value) -> {
            AttachmentCodec<Object> codec = Codecs.attachment(key);
            sink.i(capturing.attachmentIndex(key));
            begin(capturing.attachmentSchema(key));
            codec.write(value, this);
            end();
        });
    }

    /** An owned object: its class index, then its fields. */
    void object(Object value){
        if(value == null){
            sink.i(-1);
            return;
        }
        Plan plan = Codecs.plan(value.getClass());
        if(!plan.owned()){
            throw new CheckpointException(value.getClass().getName() + " is a world object; store a reference to it with ref()");
        }
        sink.i(capturing.classIndex(value.getClass()));
        begin(capturing.schema(value.getClass()));
        plan.write(value, this);
        end();
    }

    /** Starts the fields of one object under {@code schema}. */
    void begin(Schema.Builder schema){
        for(int at = 0; at <= depth; at++){
            if(schemas[at] == schema && recording[at]){
                throw new CheckpointException(schema.key + " contains itself before its first instance was written; nested instances of one class aren't supported");
            }
        }
        if(++depth == schemas.length){
            schemas = Arrays.copyOf(schemas, depth * 2);
            cursors = Arrays.copyOf(cursors, depth * 2);
            recording = Arrays.copyOf(recording, depth * 2);
        }
        schemas[depth] = schema;
        cursors[depth] = 0;
        recording[depth] = !schema.frozen;
    }

    void end(){
        Schema.Builder schema = schemas[depth];
        if(recording[depth]){
            schema.frozen = true;
        }else if(cursors[depth] != schema.size){
            throw new CheckpointException(schema.key + " wrote " + cursors[depth] + " fields where its first instance wrote "
                + schema.size + ": fields must not be conditional");
        }
        schemas[depth] = null;
        depth--;
    }

    private void field(String name, FieldType type){
        if(depth < 0) throw new IllegalStateException("no object is being written");
        Schema.Builder schema = schemas[depth];
        int cursor = cursors[depth]++;
        if(recording[depth]){
            schema.add(name, type);
        }else if(!schema.matches(cursor, name, type)){
            throw new CheckpointException(schema.key + " writes " + name + ":" + type.name().toLowerCase(Locale.ROOT)
                + " as field " + cursor + " where its first instance wrote " + schema.describe(cursor) + ": fields must not be conditional");
        }
    }

    /** Writes an array's length, -1 for null; true if elements follow. */
    private boolean length(Object values, int length){
        if(values == null){
            sink.i(-1);
            return false;
        }
        sink.i(length);
        return true;
    }
}
