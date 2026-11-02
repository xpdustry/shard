package com.xpdustry.shard.sim.entity;

import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Names a typed value that subsystems attach to buildings and units without new fields (ARCHITECTURE.md §3
 * "Attachments"). Each key has a small dense id that indexes the entity's attachment array, a copier for forks, a
 * hash function and a {@link Persistence} class for checkpoints.
 *
 * <p>Create keys once, in a {@code static final} field of the owning subsystem. Ids follow creation order, which can
 * differ between runs, so nothing persistent or hashed depends on them: hashes combine attachments by key name, and
 * checkpoints store them by name.
 *
 * @param <T> the value type
 */
public final class AttachmentKey<T>{
    private static final ArrayList<AttachmentKey<?>> KEYS = new ArrayList<>();
    private static volatile AttachmentKey<?>[] byId = {};

    private final int id;
    private final String name;
    private final Class<T> type;
    private final Copier<T> copier;
    private final Hash<T> hash;
    private final Persistence persistence;

    private AttachmentKey(int id, String name, Class<T> type, Copier<T> copier, Hash<T> hash, Persistence persistence){
        this.id = id;
        this.name = name;
        this.type = type;
        this.copier = copier;
        this.hash = hash;
        this.persistence = persistence;
    }

    /**
     * A new key. {@code name} is unique ({@code "<module>.<subsystem>.<what>"} by convention); {@code persistence} is
     * {@link Persistence#AUTHORITATIVE} or {@link Persistence#DERIVED}.
     */
    public static <T> AttachmentKey<T> create(String name, Class<T> type, Persistence persistence, Copier<T> copier, Hash<T> hash){
        Objects.requireNonNull(name, "name");
        if(persistence == Persistence.EXTERNAL) throw new IllegalArgumentException("attachments are world state: " + name);
        synchronized(KEYS){
            for(AttachmentKey<?> key : KEYS){
                if(key.name.equals(name)) throw new IllegalArgumentException("attachment key " + name + " already exists");
            }
            var key = new AttachmentKey<>(KEYS.size(), name, type, copier, hash, persistence);
            KEYS.add(key);
            byId = KEYS.toArray(AttachmentKey<?>[]::new);
            return key;
        }
    }

    static AttachmentKey<?> byId(int id){
        return byId[id];
    }

    public int id(){
        return id;
    }

    public String name(){
        return name;
    }

    public Persistence persistence(){
        return persistence;
    }

    T cast(Object value){
        return type.cast(value);
    }

    Object copy(Object value, CopyContext context){
        return copier.copy(type.cast(value), context);
    }

    long hash(Object value){
        var hasher = new Hasher().str(name);
        hash.hash(type.cast(value), hasher);
        return hasher.value();
    }

    @Override
    public String toString(){
        return "AttachmentKey[" + name + "]";
    }

    /** Copies a value for a fork; immutable values return themselves. */
    @FunctionalInterface
    public interface Copier<T>{
        T copy(T value, CopyContext context);
    }

    /** Feeds a value's authoritative state to a hasher. */
    @FunctionalInterface
    public interface Hash<T>{
        void hash(T value, Hasher hasher);
    }
}
