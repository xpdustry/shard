package com.xpdustry.shard.sim.entity;

import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * The common base of buildings, units and the other world objects (vanilla's {@code EntityComp}): the world it
 * belongs to, its vanilla entity id, whether it is added, and its attachments.
 *
 * <p>References between entities are plain object references. Anything that can outlive its target checks
 * {@link #isAdded()} (or the subclass's {@code isValid()}), as vanilla does.
 *
 * <p>Subclasses copy and hash their own state by overriding {@link #copyState(Entity, CopyContext)} and
 * {@link #hashState(Hasher)}, calling {@code super} first. Every field must be copied: the fork tests compare a copy
 * field by field.
 */
public abstract class Entity implements Copyable{
    private static final Object[] NO_ATTACHMENTS = {};

    private World world;
    private int id = -1;
    private boolean added;
    private Object[] attachments = NO_ATTACHMENTS;
    @Derived("EntityGroups.read: the position in Groups.sync, or -1")
    int syncIndex = -1;
    @Derived("EntityGroups.read: the position in Groups.all, or -1")
    int allIndex = -1;

    /** Binds a new entity to its world and draws its vanilla entity id. Call once, before anything else. */
    protected final void created(World world){
        if(this.world != null) throw new IllegalStateException(this + " already belongs to a world");
        this.world = Objects.requireNonNull(world, "world");
        this.id = world.ids().next();
    }

    /**
     * Binds a new entity to its world with an id drawn earlier: a block unit, whose id its turret drew when it was
     * constructed (WP60). Call once, before anything else.
     */
    protected final void created(World world, int id){
        if(this.world != null) throw new IllegalStateException(this + " already belongs to a world");
        this.world = Objects.requireNonNull(world, "world");
        this.id = id;
    }

    /**
     * Binds a new pooled entity to its world: Arc's {@code Pool.obtain} gives it a free object's id, or a fresh one
     * ({@link EntityIds#obtain(Pooled)}). Call once, before anything else.
     */
    protected final void created(World world, Pooled pool){
        if(this.world != null) throw new IllegalStateException(this + " already belongs to a world");
        this.world = Objects.requireNonNull(world, "world");
        this.id = world.ids().obtain(pool);
    }

    public final World world(){
        return world;
    }

    /** The vanilla entity id; for units it is the wire id. */
    public final int id(){
        return id;
    }

    /** Sets the id read from a save. The caller also calls {@link EntityIds#checkNextId(int)}. */
    public final void id(int id){
        this.id = id;
    }

    /** Whether this entity is in its world's groups. Vanilla quirk kept: a sleeping building is not added. */
    public final boolean isAdded(){
        return added;
    }

    /** Joins this entity's groups; does nothing if already added. */
    public abstract void add();

    /** Leaves this entity's groups; does nothing if not added. */
    public abstract void remove();

    /**
     * One update of an entity in {@link EntityGroups#all()} (vanilla's {@code Groups.all.update()}), from the
     * {@code MISC} phase. Units and buildings have their own update lists and phases.
     */
    public void update(){
    }

    /** For {@link #add()} and {@link #remove()} implementations. */
    protected final void added(boolean added){
        this.added = added;
    }

    /** The value attached under {@code key}, or null. */
    public final <T> T attachment(AttachmentKey<T> key){
        return key.id() < attachments.length ? key.cast(attachments[key.id()]) : null;
    }

    /** Attaches {@code value} under {@code key}; null detaches. */
    public final <T> void attach(AttachmentKey<T> key, T value){
        if(key.id() >= attachments.length){
            if(value == null) return;
            attachments = Arrays.copyOf(attachments, key.id() + 1);
        }
        attachments[key.id()] = value;
    }

    @Override
    public final void copyTo(Copyable into, CopyContext context){
        copyState((Entity)into, context);
    }

    /** Copies this entity's state into {@code into}, an empty instance of the same class. Call {@code super} first. */
    protected void copyState(Entity into, CopyContext context){
        into.world = context.world();
        into.id = id;
        into.added = added;
        into.syncIndex = syncIndex;
        into.allIndex = allIndex;
        if(attachments.length > 0){
            into.attachments = new Object[attachments.length];
            for(int i = 0; i < attachments.length; i++){
                if(attachments[i] != null) into.attachments[i] = AttachmentKey.byId(i).copy(attachments[i], context);
            }
        }
    }

    /** Feeds this entity's authoritative state to {@code hasher}. Call {@code super} first. */
    public void hashState(Hasher hasher){
        hasher.i(id).bool(added);
        // Attachment ids follow key creation order, which may differ between runs: combine by key name instead.
        long combined = 0;
        int count = 0;
        for(int i = 0; i < attachments.length; i++){
            AttachmentKey<?> key = attachments[i] == null ? null : AttachmentKey.byId(i);
            if(key != null && key.persistence() == Persistence.AUTHORITATIVE){
                combined += key.hash(attachments[i]);
                count++;
            }
        }
        hasher.i(count).l(combined);
    }
}
