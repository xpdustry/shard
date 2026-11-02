package com.xpdustry.shard.common.registry;

import java.lang.annotation.*;
import java.util.*;
import java.util.function.*;

/**
 * Reads the registration index. Each source set's annotation processor generates a {@link RegistryIndex} listing that
 * source set's {@link Registry} types and {@link Register} classes, with a constructor reference for each; this class
 * merges every one on the class path. Results are sorted by binary class name, so they don't depend on class path
 * order. Nothing here uses reflection, resources or class names.
 */
public final class Registries{
    /** The registered classes of {@code registry}, sorted by name. */
    public static <T> List<Class<? extends T>> classes(Class<T> registry){
        var out = new ArrayList<Class<? extends T>>();
        for(Entry entry : Index.LOADED.entries(registry)){
            @SuppressWarnings("unchecked") // the processor checked that the class extends or implements the registry
            var type = (Class<? extends T>)entry.type();
            out.add(type);
        }
        return List.copyOf(out);
    }

    /** A new instance of each registered class of {@code registry}, in {@link #classes(Class)} order. */
    public static <T> List<T> instances(Class<T> registry){
        var out = new ArrayList<T>();
        for(Entry entry : Index.LOADED.entries(registry)) out.add(registry.cast(entry.constructor().get()));
        return List.copyOf(out);
    }

    /** Calls the public no-argument constructor of registered class {@code type}; constructor exceptions propagate. */
    public static <T> T instantiate(Class<T> type){
        return constructor(type).get();
    }

    /** The public no-argument constructor of registered class {@code type}. Throws if {@code type} isn't registered. */
    public static <T> Supplier<T> constructor(Class<T> type){
        Entry entry = Index.LOADED.types.get(type);
        if(entry == null){
            throw new IllegalArgumentException(type.getName() + " is not registered (no @Register or registration"
                + " annotation in a source set the indexer compiled)");
        }
        Supplier<?> constructor = entry.constructor();
        return () -> type.cast(constructor.get());
    }

    /**
     * The {@code value()} of the registration annotation {@code annotation} on registered class {@code type}, such as
     * {@code "conveyor"} for {@code @Kind("conveyor")}; null if {@code type} isn't registered or doesn't carry it.
     */
    public static String key(Class<?> type, Class<? extends Annotation> annotation){
        Entry entry = Index.LOADED.types.get(type);
        return entry == null ? null : entry.keys().get(annotation.getName());
    }

    /**
     * The shape of record {@code type}, which implements a {@link Shaped} type. Throws if the index has none: the
     * record is private, local, or in a source set the indexer didn't compile.
     */
    @SuppressWarnings("unchecked") // shapes are declared by their own class
    public static <R extends Record> Shape<R> shape(Class<R> type){
        Shape<?> shape = Index.LOADED.shapes.get(type);
        if(shape == null) throw new IllegalArgumentException(type.getName() + " has no shape: is it a record of a @Shaped type?");
        return (Shape<R>)shape;
    }

    /** A record's components, in order, and its canonical constructor. See {@link Shaped}. */
    public record Shape<R extends Record>(Function<R, Object[]> components, Function<Object[], R> constructor, boolean holdsObjects){
    }

    /** A registered class: its binary name, its constructor and its registration annotations' values. */
    private record Entry(String name, Class<?> type, Supplier<?> constructor, Map<String, String> keys){
    }

    /** Every {@link RegistryIndex} on the class path, merged once. Maps are looked up, never iterated. */
    private static final class Index implements RegistryIndex.Declarations{
        static final Index LOADED = load();

        final Map<String, TreeMap<String, Entry>> registries = new HashMap<>();
        final Map<Class<?>, Entry> types = new HashMap<>();
        final Map<Class<?>, Shape<?>> shapes = new HashMap<>();

        private static Index load(){
            var index = new Index();
            var seen = new TreeSet<String>(); // a class path can hold the same source set twice (classes and a jar)
            for(RegistryIndex part : ServiceLoader.load(RegistryIndex.class)){
                if(seen.add(part.getClass().getName())) part.declare(index);
            }
            return index;
        }

        Collection<Entry> entries(Class<?> registry){
            TreeMap<String, Entry> entries = registries.get(registry.getName());
            if(entries == null) throw new IllegalArgumentException(registry.getName() + " is not annotated @Registry");
            return entries.values();
        }

        @Override
        public void registry(String registry){
            registries.computeIfAbsent(registry, r -> new TreeMap<>());
        }

        @Override
        public <T> void type(String name, Class<T> type, Supplier<? extends T> constructor, String... registries){
            var entry = new Entry(name, type, constructor, new HashMap<>());
            if(types.putIfAbsent(type, entry) != null) throw new IllegalStateException(name + " is indexed twice");
            for(String registry : registries){
                registry(registry);
                this.registries.get(registry).put(name, entry);
            }
        }

        @Override
        public void key(Class<?> type, String annotation, String value){
            types.get(type).keys().put(annotation, value);
        }

        @Override
        public <R extends Record> void shape(Class<R> type, Function<R, Object[]> components, Function<Object[], R> constructor,
                                             boolean holdsObjects){
            shapes.put(type, new Shape<>(components, constructor, holdsObjects));
        }
    }

    private Registries(){}
}
