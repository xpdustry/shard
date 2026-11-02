package com.xpdustry.shard.common.registry;

import java.util.function.*;

/**
 * One source set's part of the registration index, as Java: {@code :tools:indexer} generates an implementation per
 * compiled source set ({@code com.xpdustry.shard.sim.SimRegistryIndex}, {@code ...SimTestRegistryIndex}...) and lists
 * it in {@code META-INF/services}. {@link Registries} loads every one with {@link java.util.ServiceLoader}, so
 * nothing needs reflection or {@code Class.forName}.
 */
public interface RegistryIndex{
    /** Declares this source set's {@link Registry} types and registered classes to {@code index}. */
    void declare(Declarations index);

    /**
     * What a generated index calls; its methods only record. Registries and annotations go by binary name, as they
     * need not be public; registered classes are public, so they go by class.
     */
    interface Declarations{
        /** The type of binary name {@code registry} carries {@link Registry}. */
        void registry(String registry);

        /**
         * {@code type}, binary name {@code name}, is registered under each of the {@code registries}, by binary name,
         * and {@code constructor} calls its public no-argument constructor.
         */
        <T> void type(String name, Class<T> type, Supplier<? extends T> constructor, String... registries);

        /** {@code type} carries the registration annotation of binary name {@code annotation} with this {@code value()}. */
        void key(Class<?> type, String annotation, String value);

        /**
         * Record {@code type}, which implements a {@link Shaped} type, reads its components with {@code components}
         * and is made from them by {@code constructor}. {@code holdsObjects} is whether a component is neither
         * primitive, nor a {@code String}, nor an enum.
         */
        <R extends Record> void shape(Class<R> type, Function<R, Object[]> components, Function<Object[], R> constructor,
                                      boolean holdsObjects);
    }
}
