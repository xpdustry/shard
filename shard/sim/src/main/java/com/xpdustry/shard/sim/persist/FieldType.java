package com.xpdustry.shard.sim.persist;

/**
 * The type of one stored field, as {@link CheckpointOut} wrote it. A class's schema is its fields' names and types in
 * write order; the reader checks every field against it, so a checkpoint from code with different fields fails with
 * the field's name instead of reading garbage.
 */
public enum FieldType{
    BOOL, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, STRING, ENUM,
    BYTES, SHORTS, INTS, LONGS, FLOATS, DOUBLES,
    /** An {@code IntSeq}: its whole array, size and order flag. */
    INT_SEQ,
    /** A reference to a world object (an entity or another {@code Copyable}), by object index. */
    REF,
    /** An ordered array of references, nulls kept. */
    REFS,
    /** An ordered list of strings, nulls kept. */
    STRINGS,
    /** An owned object (a module, a controller, a record), by class, then its own fields. */
    OBJECT,
    /** A list of owned objects. */
    LIST,
    /** A content definition, by wire id. */
    BLOCK, UNIT_TYPE,
    /** An entity's attachments, by key name. */
    ATTACHMENTS,
    /** A field the reader sets without stored bytes: an entity's world, a building's block. */
    IMPLICIT,
    /** A field rebuilt after restore instead of stored, declared by the codec of a class it can't annotate. */
    DERIVED;

    private static final FieldType[] VALUES = values();

    static FieldType of(int tag){
        if(tag < 0 || tag >= VALUES.length) throw new CheckpointException("unknown field type " + tag);
        return VALUES[tag];
    }
}
