package com.xpdustry.shard.sim.building;

import com.xpdustry.shard.content.*;

import java.util.*;
import java.util.function.*;

/**
 * A kind's config handlers: vanilla's {@code Block.config(Class, handler)} and {@code configClear(handler)}, which
 * {@link Building#configured} dispatches on. A kind builds one in a static field and returns it from
 * {@link Building#configs()}:
 *
 * <pre>{@code
 * static final Configs<ItemSource> CONFIGS = Configs.of(ItemSource.class)
 *     .on(ContentType.ITEM, (source, item) -> source.outputItem = item)
 *     .clear(source -> source.outputItem = -1);
 * }</pre>
 *
 * <p>Config values are what vanilla's {@code TypeIO.readObject} gives, in {@code :sim}'s terms: null, boxed numbers and
 * booleans, {@code String}, {@code byte[]}, {@code boolean[]}, {@code int[]}, {@code Object[]},
 * {@link com.xpdustry.shard.common.geom.Point2} and {@code Point2[]}, {@link com.xpdustry.shard.common.geom.Vec2}, a
 * {@link Building}, and {@link ContentRef} for content. Handlers are looked up by the value's exact class, as vanilla's
 * {@code ObjectMap} is; content by its type ({@code Item.class}, {@code Block.class}…); null by {@code clear}.
 */
public final class Configs<B extends Building>{
    private static final Configs<Building> NONE = new Configs<>(Building.class);

    private final Class<B> kind;
    private final List<Class<?>> types = new ArrayList<>();
    private final List<BiConsumer<B, Object>> handlers = new ArrayList<>();
    private final List<BiConsumer<B, Integer>> content = new ArrayList<>(Collections.nCopies(ContentType.values().length, null));
    private Consumer<B> clear;

    private Configs(Class<B> kind){
        this.kind = kind;
    }

    /** New, empty handlers for buildings of class {@code kind}. */
    public static <B extends Building> Configs<B> of(Class<B> kind){
        return new Configs<>(kind);
    }

    /** No handlers. */
    public static Configs<?> none(){
        return NONE;
    }

    /** {@code config(type, handler)}: values of exactly class {@code type}. */
    @SuppressWarnings("unchecked")
    public <T> Configs<B> on(Class<T> type, BiConsumer<B, T> handler){
        if(this == NONE) throw new IllegalStateException("none() is shared");
        if(type == ContentRef.class) throw new IllegalArgumentException("register content by its ContentType");
        int at = types.indexOf(type);
        if(at >= 0){
            handlers.set(at, (BiConsumer<B, Object>)handler);
        }else{
            types.add(type);
            handlers.add((BiConsumer<B, Object>)handler);
        }
        return this;
    }

    /** {@code config(Item.class, handler)} and the other content classes: content of {@code type}, by id. */
    public Configs<B> on(ContentType type, BiConsumer<B, Integer> handler){
        if(this == NONE) throw new IllegalStateException("none() is shared");
        content.set(type.ordinal(), handler);
        return this;
    }

    /** {@code configClear(handler)}: a null value. */
    public Configs<B> clear(Consumer<B> handler){
        if(this == NONE) throw new IllegalStateException("none() is shared");
        clear = handler;
        return this;
    }

    /** Whether a handler takes values like {@code value}. */
    public boolean handles(Object value){
        if(value == null) return clear != null;
        if(value instanceof ContentRef ref) return content.get(ref.type().ordinal()) != null;
        return types.contains(value.getClass());
    }

    /**
     * {@code Block.configSenseable()}: whether content configures the block (an item, liquid, block or unit type
     * handler), so {@code sensor @config} gives its config.
     */
    public boolean sensesContent(){
        for(ContentType type : new ContentType[]{ContentType.ITEM, ContentType.LIQUID, ContentType.BLOCK, ContentType.UNIT}){
            if(content.get(type.ordinal()) != null) return true;
        }
        return false;
    }

    /** Runs the handler for {@code value} on {@code building}, returning false when there is none. */
    boolean dispatch(Building building, Object value){
        if(!handles(value)) return false;
        B target = kind.cast(building);
        if(value == null){
            clear.accept(target);
        }else if(value instanceof ContentRef ref){
            content.get(ref.type().ordinal()).accept(target, ref.id());
        }else{
            handlers.get(types.indexOf(value.getClass())).accept(target, value);
        }
        return true;
    }
}
