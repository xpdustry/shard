package com.xpdustry.shard.wire;

import com.xpdustry.shard.common.io.*;

/**
 * The content and world facts that change how bytes are laid out or whether vanilla accepts them, as narrow read-only
 * lookups (note 25, inventory §11). {@code :compat} implements it per world over the pinned content epoch and map size;
 * tests implement it over vanilla. Content ids are the client's (wire) ids.
 */
public interface WireContext{
    /** {@code StatusEffect.dynamic}: a dynamic status carries a flag byte and up to 7 floats (TypeIO.java:1031). */
    boolean statusDynamic(int status);

    /**
     * {@code content.getByID(type, id) != null}, by {@code ContentType} ordinal. Vanilla dereferences what some reads
     * return, so an unknown id fails them: a status effect in {@code readStatus} (TypeIO.java:1064), and a unit type in
     * a unit's {@code read} and {@code readSync} (their {@code afterRead} and {@code afterSync} call {@code setType}).
     * The default accepts every id, so a context over real content must override it.
     */
    default boolean contentExists(int type, int id){
        return true;
    }

    /** {@code Block.rotate}: a client plan carries a rotation byte only for these (TypeIO.java:728). */
    boolean blockRotates(int block);

    /** {@code content.block(id) != null}: a client plan with any other block is rejected (TypeIO.java:747). */
    boolean blockExists(int block);

    /**
     * {@code content.getByID(type, id)} is an {@code UnlockableContent}. A tech node object (tag 9) reads its
     * {@code techNode}, so vanilla rejects it for anything else: a null throws, a non-unlockable fails its cast
     * (TypeIO.java:253).
     */
    boolean unlockable(int type, int id);

    /**
     * {@code world.tile(pos) != null} for a packed position. A build plan outside the world ends right after its
     * position: vanilla reads neither its block nor its config (TypeIO.java:667), so the next bytes are the next plan.
     */
    boolean tileExists(int pos);

    /**
     * The class id of the synced entity with this id ({@code Groups.sync.getByID}), or -1 if there is none. Packets
     * cast the entities they read, so vanilla rejects an id naming an entity of the wrong class (a unit where a player
     * goes), but reads an unknown id as null.
     */
    int syncedClass(int id);

    /**
     * Reads a building's {@code writeAll} (its base, then its block's body) inside a block payload and returns those
     * bytes. The body has no length prefix and its layout depends on the block's kind and version, so only
     * {@code :compat}'s building codecs know where it ends.
     */
    default byte[] readBuildingBody(Reads read, int block, int version){
        throw new WireException("block " + block + " in a payload: reading building bodies needs :compat's building codecs");
    }
}
