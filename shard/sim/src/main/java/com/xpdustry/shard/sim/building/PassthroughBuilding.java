package com.xpdustry.shard.sim.building;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * A building whose kind isn't ported yet (ARCHITECTURE.md §5 "Passthrough"). It keeps its block's wire id and the
 * common building state, and carries the kind's save bytes opaquely so the world writes them back unchanged.
 * {@link KindRegistry#withPassthrough()} binds every unregistered kind to it.
 *
 * <p>The bytes come only from a known length-delimited input: a v160.7 map's building chunk, read by the msav loader
 * (W1.7), which parses the base ({@code writeBase}) into the {@link Building} fields and hands the rest here with
 * {@link #load(byte[], int, String)}:
 * <ul>
 *   <li>{@code body}: the block-specific bytes after the base, exactly as read;</li>
 *   <li>{@code revision}: the chunk's building version byte, which the body's layout depends on;</li>
 *   <li>{@code epoch}: the hash of the content epoch the map was read with.</li>
 * </ul>
 * {@link #body(ContentEpoch)} gives them back only for that same epoch.
 *
 * <p>Fenced, explicitly: it simulates nothing ({@link #updateTile()} does nothing, but the building keeps its place in
 * the update list, so the list order stays vanilla's); a building placed live, with no source bytes, has no body to
 * write; and every gameplay entry point that would need the kind's behaviour must call {@link #unported(String)}.
 * Base state (health, team, rotation, enabled, modules) works as for any building. Checkpoints store the bytes as
 * they are: they are authoritative.
 */
public final class PassthroughBuilding extends Building implements Checkpointable{
    private static final byte[] NONE = {};
    private static final KindKey SOLID_PUMP = KindKey.of("solid-pump");

    private byte[] body = NONE;
    private int revision = -1;
    private String epoch;

    /** Attaches the save bytes read from a map. Call once, right after the building is created. */
    public void load(byte[] body, int revision, String epoch){
        if(hasBody()) throw new IllegalStateException(this + " already carries its save bytes");
        if(revision < 0 || revision > 255) throw new IllegalArgumentException("revision is a byte: " + revision);
        this.body = body.clone();
        this.revision = revision;
        this.epoch = Objects.requireNonNull(epoch, "epoch");
    }

    /** Whether this building came from save bytes; one placed live has none. */
    public boolean hasBody(){
        return epoch != null;
    }

    /** The building version byte of the source chunk, or -1. */
    public int revision(){
        return revision;
    }

    /** The content hash the bytes were read with, or null. */
    public String epoch(){
        return epoch;
    }

    /**
     * The block-specific save bytes, a copy, for writing back in {@code target}. Refused when there are none or when
     * {@code target} is not the epoch they were read with: an id or layout change would corrupt them.
     */
    public byte[] body(ContentEpoch target){
        if(!hasBody()) throw unported("save a live-placed building");
        if(!epoch.equals(target.hash())){
            throw new IllegalStateException(this + ": save bytes are from content " + epoch + ", not " + target.hash());
        }
        return body.clone();
    }

    /** The body's length, for codecs that size a chunk first. */
    public int bodyLength(){
        return body.length;
    }

    /** Unported kinds don't read their consumers either: efficiency keeps the file's. */
    @Override
    public void updateConsumption(){
    }

    /** Neighbours give it nothing: it would hold what it can't simulate. */
    @Override
    public boolean acceptItem(Building source, int item){
        return false;
    }

    @Override
    public boolean acceptLiquid(Building source, int liquid){
        return false;
    }

    /** Unported kinds don't simulate. */
    @Override
    public void updateTile(){
    }

    /**
     * Vanilla quirk kept for the solid pumps (water and oil extractors), as it reaches the power graphs: their
     * {@code onProximityUpdate} calls {@code super.onProximityAdded()} instead (SolidPump.java:151), so any change
     * next to one merges its graph again, and it never wakes. The rest of it (boost, valid tiles) is the kind's.
     */
    @Override
    public void onProximityUpdate(){
        if(block().baked() && block().isA(SOLID_PUMP)){
            onProximityAdded();
        }else{
            super.onProximityUpdate();
        }
    }

    /** The exception for a gameplay action this building can't do. Throw it: {@code throw building.unported("…")}. */
    public UnsupportedOperationException unported(String action){
        return new UnsupportedOperationException("cannot " + action + ": " + block().name() + " (kind " + block().kind()
            + ") is not ported; its building is a passthrough");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (PassthroughBuilding)into;
        out.body = body.length == 0 ? NONE : body.clone();
        out.revision = revision;
        out.epoch = epoch;
    }

    @Override
    public void write(CheckpointOut out){
        out.bytes("body", body).i("revision", revision).str("epoch", epoch);
    }

    @Override
    public void read(CheckpointIn in){
        byte[] stored = in.bytes("body");
        body = stored == null || stored.length == 0 ? NONE : stored;
        revision = in.i("revision");
        epoch = in.str("epoch");
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(revision).bytes(body).bool(epoch != null);
        if(epoch != null) hasher.str(epoch);
    }
}
