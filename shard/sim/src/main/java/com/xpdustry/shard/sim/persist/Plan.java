package com.xpdustry.shard.sim.persist;

import java.util.*;

/**
 * How objects of one class are stored, found once per class by {@link Codecs#plan(Class)}. World objects (entities,
 * shared {@code Copyable}s) are created by the reader and then filled; owned objects (modules, controllers, records,
 * parts) are read whole.
 */
abstract class Plan{
    final Class<?> type;

    Plan(Class<?> type){
        this.type = type;
    }

    /** Owned objects are written inline with {@code obj()}; world objects by reference with {@code ref()}. */
    abstract boolean owned();

    /** Writes every stored field of {@code value}, foundation layers first. */
    abstract void write(Object value, CheckpointOut out);

    /** Reads into {@code value}, a blank instance, in {@link #write}'s order. */
    abstract void fill(Object value, CheckpointIn in);

    /** A blank instance to fill, for owned objects, parts and shared objects; entities are made by the reader. */
    Object create(){
        throw new CheckpointException(type.getName() + " can't be created blank");
    }

    /** A new owned instance read from {@code in}. Records and modules build themselves from their fields. */
    Object read(CheckpointIn in){
        Object value = create();
        fill(value, in);
        return value;
    }

    /** After the whole world is restored: the object's own rebuild of its derived fields. */
    void restored(Object value){
    }

    /** A layer of an entity's fields: a foundation base ({@code Entity}, {@code Building}, {@code Unit}). */
    interface Layer<T>{
        void write(T value, CheckpointOut out);

        void read(T value, CheckpointIn in);
    }

    /** The class's own state beyond its foundation layers. */
    abstract static class Own{
        abstract void write(Object value, CheckpointOut out);

        abstract void read(Object value, CheckpointIn in);

        abstract Object create(Class<?> type);

        void restored(Object value){
        }
    }

    static final class Self extends Own{
        @Override
        void write(Object value, CheckpointOut out){
            ((Checkpointable)value).write(out);
        }

        @Override
        void read(Object value, CheckpointIn in){
            ((Checkpointable)value).read(in);
        }

        @Override
        Object create(Class<?> type){
            return Codecs.construct(type);
        }

        @Override
        void restored(Object value){
            ((Checkpointable)value).restored();
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static final class ByCodec extends Own{
        final ClassCodec codec;

        ByCodec(ClassCodec<?> codec){
            this.codec = codec;
        }

        @Override
        void write(Object value, CheckpointOut out){
            codec.write(value, out);
        }

        @Override
        void read(Object value, CheckpointIn in){
            codec.read(value, in);
        }

        @Override
        Object create(Class<?> type){
            return codec.create();
        }

        @Override
        void restored(Object value){
            codec.restored(value);
        }
    }

    /** A class with no fields beyond its layers (or only {@link com.xpdustry.shard.sim.world.Derived} ones). */
    static final class Stateless extends Own{
        @Override
        void write(Object value, CheckpointOut out){
        }

        @Override
        void read(Object value, CheckpointIn in){
        }

        @Override
        Object create(Class<?> type){
            return Codecs.construct(type);
        }
    }

    /** A class with fields but no way to store them: capturing one fails, naming the fields. */
    static final class Missing extends Own{
        final String problem;

        Missing(Class<?> type, List<String> fields){
            this(type.getName() + " has fields " + fields + " but neither implements Checkpointable nor has a"
                + " registered ClassCodec; store them, or mark rebuilt ones @Derived");
        }

        Missing(String problem){
            this.problem = problem;
        }

        @Override
        void write(Object value, CheckpointOut out){
            throw new CheckpointException(problem);
        }

        @Override
        void read(Object value, CheckpointIn in){
            throw new CheckpointException(problem);
        }

        @Override
        Object create(Class<?> type){
            throw new CheckpointException(problem);
        }
    }

    /** Entities: the foundation layers, then the class's own state. Created by the reader from the object types. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static final class EntityPlan extends Plan{
        final Layer[] layers;
        final Own own;

        EntityPlan(Class<?> type, List<Layer<?>> layers, Own own){
            super(type);
            this.layers = layers.toArray(Layer[]::new);
            this.own = own;
        }

        @Override
        boolean owned(){
            return false;
        }

        @Override
        void write(Object value, CheckpointOut out){
            for(Layer layer : layers) layer.write(value, out);
            own.write(value, out);
        }

        @Override
        void fill(Object value, CheckpointIn in){
            for(Layer layer : layers) layer.read(value, in);
            own.read(value, in);
        }

        /** A blank entity, not yet bound to a world: the reader binds it, the coverage check only writes it. */
        @Override
        Object create(){
            return own.create(type);
        }

        @Override
        void restored(Object value){
            own.restored(value);
        }
    }

    /** Objects stored only through their own state: shared {@code Copyable}s, owned objects, parts. */
    static final class OwnPlan extends Plan{
        final Own own;
        final boolean owned;

        OwnPlan(Class<?> type, Own own, boolean owned){
            super(type);
            this.own = own;
            this.owned = owned;
        }

        @Override
        boolean owned(){
            return owned;
        }

        @Override
        void write(Object value, CheckpointOut out){
            own.write(value, out);
        }

        @Override
        void fill(Object value, CheckpointIn in){
            own.read(value, in);
        }

        @Override
        Object create(){
            return own.create(type);
        }

        @Override
        void restored(Object value){
            own.restored(value);
        }
    }
}
