package com.xpdustry.shard.sim.building;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;

import java.util.*;
import java.util.function.*;

/**
 * Binds content kind keys to {@link Building} subclasses (ARCHITECTURE.md §3, §5 "Kinds"). The built-in registry
 * reads every {@link Kind} class from the registration index, and every {@link TileKinds} key: a tile kind is
 * supported with no building class, and never instantiated. An unsupported kind is a clear load error, unless the
 * registry was made {@link #withPassthrough()}: then unregistered kinds get {@link PassthroughBuilding}s.
 *
 * <p>A block resolves by one of its two keys ({@link #key(BlockDef)}): its {@link BlockDef#kind()} (its vanilla block
 * class) when that is a tile kind, else its {@link BlockDef#buildKind()} (the class that declares its building). A
 * {@code Building} subclass ports a vanilla building class, whose state, save bytes and sync it owns, so block classes
 * that share a building class share the Shard class and differ only by their definitions' parameters. Blocks without a
 * building have no building class of their own (every environment block's build kind is {@code block}), so their
 * block class is what tells a static wall from a floor.
 */
public final class KindRegistry{
    private final TreeMap<KindKey, Binding> kinds = new TreeMap<>();
    private final boolean passthrough;

    private KindRegistry(KindRegistry source, boolean passthrough){
        kinds.putAll(source.kinds);
        this.passthrough = passthrough;
    }

    private KindRegistry(List<Class<? extends Building>> classes, List<KindKey> tileKinds, String tileOrigin){
        passthrough = false;
        for(Class<? extends Building> type : classes){
            String kind = Registries.key(type, Kind.class);
            if(kind == null) throw new IllegalArgumentException(type.getName() + " has no @Kind");
            KindKey key;
            try{
                key = KindKey.of(kind);
            }catch(IllegalArgumentException e){
                throw new IllegalArgumentException("@Kind on " + type.getName() + ": " + e.getMessage(), e);
            }
            bind(new Binding(key, type, Registries.constructor(type), type.getName()));
        }
        for(KindKey key : tileKinds) bind(new Binding(key, null, null, tileOrigin));
    }

    private void bind(Binding binding){
        Binding previous = kinds.putIfAbsent(binding.key(), binding);
        if(previous != null){
            throw new IllegalArgumentException("kind " + binding.key() + " is registered twice: by " + previous.origin()
                + " and " + binding.origin());
        }
    }

    /** Every {@code @Kind} class and {@link TileKinds} key on the class path of {@code :sim}. */
    public static KindRegistry builtIn(){
        return BuiltIn.KINDS;
    }

    /** Exactly these {@code @Kind} classes, for tests and tools. */
    public static KindRegistry of(List<Class<? extends Building>> classes){
        return of(classes, List.of());
    }

    /** Exactly these {@code @Kind} classes and these tile kinds, for tests and tools. */
    public static KindRegistry of(List<Class<? extends Building>> classes, List<KindKey> tileKinds){
        return new KindRegistry(classes, tileKinds, "a tile kind");
    }

    /**
     * This registry, binding every kind it doesn't support to {@link PassthroughBuilding}, so content with unported
     * blocks (a real map) loads. Registered kinds keep their classes.
     */
    public KindRegistry withPassthrough(){
        return passthrough ? this : new KindRegistry(this, true);
    }

    /**
     * This registry with {@code key} bound to {@code type}, replacing its class if it had one: for tests and tools
     * that instrument a kind. {@code type} carries no {@link Kind}, which would register it on the whole class path,
     * so {@code constructor} makes its buildings.
     */
    public <B extends Building> KindRegistry withKind(KindKey key, Class<B> type, Supplier<? extends B> constructor){
        if(tileKind(key)) throw new IllegalArgumentException("kind " + key + " is a tile kind: its blocks have no building");
        var registry = new KindRegistry(this, passthrough);
        registry.kinds.put(key, new Binding(key, type, constructor, type.getName()));
        return registry;
    }

    /** {@link #withKind(KindKey, Class, Supplier)} with a registered class's constructor. */
    public <B extends Building> KindRegistry withKind(KindKey key, Class<B> type){
        return withKind(key, type, Registries.constructor(type));
    }

    /** Whether unregistered kinds fall back to {@link PassthroughBuilding}. */
    public boolean passthrough(){
        return passthrough;
    }

    /** Whether {@code key} gets a {@link PassthroughBuilding}. */
    public boolean passesThrough(KindKey key){
        return passthrough && !kinds.containsKey(key);
    }

    /** Whether {@code block}'s buildings are {@link PassthroughBuilding}s. */
    public boolean passesThrough(BlockDef block){
        return passesThrough(key(block));
    }

    /** The key {@code block} resolves by: its kind if that is a tile kind, else its build kind (see the class comment). */
    public KindKey key(BlockDef block){
        KindKey kind = block.kind();
        return tileKind(kind) ? kind : block.buildKind();
    }

    /** The registered kind keys, tile kinds included, in order. */
    public List<KindKey> keys(){
        return List.copyOf(kinds.keySet());
    }

    public boolean supports(KindKey key){
        return kinds.containsKey(key);
    }

    /** Whether {@code key} is a registered {@link TileKinds} key: its blocks have no building. */
    public boolean tileKind(KindKey key){
        Binding binding = kinds.get(key);
        return binding != null && binding.type() == null;
    }

    /** The class of {@code block}'s buildings: {@link #type(KindKey)} of its {@link #key(BlockDef)}. */
    public Class<? extends Building> type(BlockDef block){
        return type(key(block));
    }

    /** The class registered for {@code key}, or {@link PassthroughBuilding}. A tile kind has none: that throws. */
    public Class<? extends Building> type(KindKey key){
        if(passesThrough(key)) return PassthroughBuilding.class;
        Binding binding = binding(key, null);
        if(binding.type() == null) throw new IllegalStateException("kind " + key + " is a tile kind: its blocks have no building");
        return binding.type();
    }

    /** Throws, naming every block of {@code content} whose kind isn't registered; passes through without checking. */
    public void requireSupported(ContentEpoch content){
        if(passthrough) return;
        var missing = new ArrayList<String>();
        for(int id = 0; id < content.blocks(); id++){
            BlockDef block = content.block(id);
            KindKey key = key(block);
            if(supports(key)) continue;
            missing.add(block.name() + " (" + (key.equals(block.kind()) ? key : block.kind() + ", building " + key) + ")");
        }
        if(!missing.isEmpty()){
            throw new IllegalStateException("unsupported kinds in content " + content.hash() + ": " + String.join(", ", missing));
        }
    }

    /** A new, unbound building of {@code block}'s kind. {@link Buildings#create} is how a world makes one. */
    Building instantiate(BlockDef block){
        KindKey key = key(block);
        if(passesThrough(key)) return new PassthroughBuilding();
        Binding binding = binding(key, block);
        if(binding.type() == null){
            throw new IllegalStateException("block " + block.name() + " has a building, but its kind " + key
                + " is a tile kind, which has none: a content error");
        }
        return binding.constructor().get();
    }

    private Binding binding(KindKey key, BlockDef block){
        Binding binding = kinds.get(key);
        if(binding == null){
            throw new IllegalStateException("unsupported kind " + key + (block == null ? "" : " for block " + block.name()));
        }
        return binding;
    }

    /** A kind's class and constructor, both null for a tile kind; {@code origin} names who registered it. */
    private record Binding(KindKey key, Class<? extends Building> type, Supplier<? extends Building> constructor, String origin){
    }

    private static final class BuiltIn{
        static final KindRegistry KINDS = builtIn();

        private static KindRegistry builtIn(){
            var registry = new KindRegistry(Registries.classes(Building.class), List.of(), null);
            for(TileKinds family : Registries.instances(TileKinds.class)){
                for(KindKey key : family.keys()) registry.bind(new Binding(key, null, null, family.getClass().getName()));
            }
            return registry;
        }
    }
}
