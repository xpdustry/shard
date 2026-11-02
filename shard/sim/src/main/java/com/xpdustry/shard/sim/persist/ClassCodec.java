package com.xpdustry.shard.sim.persist;

import com.xpdustry.shard.common.registry.*;

import java.lang.reflect.*;

/**
 * Stores the fields of a class that can't implement {@link Checkpointable} itself: another owner's class, a test
 * fixture. Register it with {@code @Register}; it covers exactly {@link #type()}, not subclasses. For an entity it
 * stores the class's own fields, after the foundation bases, as {@link Checkpointable#write} would.
 *
 * @param <T> the class
 */
@Registry
public interface ClassCodec<T>{
    Class<T> type();

    void write(T value, CheckpointOut out);

    void read(T value, CheckpointIn in);

    /**
     * A blank instance to {@link #read} into, for owned objects and parts. The default calls the public
     * no-argument constructor; entities are always created by {@code sim.persist} instead.
     */
    default T create(){
        try{
            return type().getConstructor().newInstance();
        }catch(InvocationTargetException e){
            throw new CheckpointException("constructor of " + type().getName() + " failed", e.getCause());
        }catch(ReflectiveOperationException e){
            throw new CheckpointException(type().getName() + " has no public no-argument constructor; override create()", e);
        }
    }

    /** Called once the world is restored, as {@link Checkpointable#restored()}. */
    default void restored(T value){
    }
}
