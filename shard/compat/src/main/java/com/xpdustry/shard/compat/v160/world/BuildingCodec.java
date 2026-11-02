package com.xpdustry.shard.compat.v160.world;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;

/**
 * The save bytes of one ported kind (vanilla {@code Building.write}/{@code read}): what a map section, a save and a
 * world stream hold after the chunk's revision byte and the {@link BuildingBase}. Register implementations with
 * {@code @Register}; the world-stream writer and the msav importer find a building's by its
 * {@link KindRegistry#key(BlockDef)}, and a building without one must be a {@link PassthroughBuilding} read from a map.
 *
 * <p>Owners: the family owner of each kind ({@code compat.v160.blocks.<family>}).
 */
@Registry
public interface BuildingCodec{
    /** The kind whose buildings this codec reads and writes: a {@link KindRegistry#key(BlockDef)}. */
    KindKey kind();

    /** The revision {@link #write} produces: vanilla's {@code version()} for the kind's building class. */
    int revision();

    /** Whether {@link #read} understands {@code revision}. */
    default boolean reads(int revision){
        return revision == revision();
    }

    /** Writes the kind's fields after the base, with content ids in {@code ids}' stream id space. */
    void write(Building building, Writes out, ContentHeader.IdMapping ids);

    /** Reads the kind's fields of {@code revision} into a building the importer just created and read the base of. */
    void read(Building building, Reads in, int revision, ContentHeader.IdMapping ids);

    /**
     * Reads past the kind's fields of {@code revision} without a building, for a building inside a block payload
     * ({@link BuildingCodecs#readBody}): the same reads as {@link #read}, on a {@link Tape}.
     */
    void skip(Tape in, int revision);

    /**
     * {@link #skip(Tape, int)} for a building of {@code block}, for kinds whose layout depends on the block (a cultivator's
     * legacy warmup). {@link BuildingCodecs#readBody} calls this one.
     */
    default void skip(Tape in, int revision, BlockDef block){
        skip(in, revision);
    }
}
