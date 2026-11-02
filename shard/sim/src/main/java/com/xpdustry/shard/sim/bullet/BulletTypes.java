package com.xpdustry.shard.sim.bullet;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.schema.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;
import java.util.function.*;

/**
 * The {@link BulletType} of every bullet in a content epoch, by content id. Each bullet runs the class registered
 * for its kind with {@link BulletKind}; a kind without one runs its nearest registered ancestor's class, or the base
 * {@code bullet-type}'s, and is {@link #unsupported() reported} (ARCHITECTURE.md §9 "Combat": unsupported content is
 * reported explicitly). Immutable once built; {@link Bullets#types(World)} keeps one per world.
 */
public final class BulletTypes{
    private static final String BASE = "bullet-type";

    private final ContentEpoch content;
    private final BulletType[] types;
    private final List<String> unsupported;

    private BulletTypes(ContentEpoch content){
        this.content = content;
        int count = content.count(ContentType.BULLET);
        types = new BulletType[count];
        var missing = new TreeSet<String>();
        for(int id = 0; id < count; id++){
            BulletDef def = content.bullet(id);
            BulletType type = instantiate(def, missing);
            type.load(def, content);
            types[id] = type;
        }
        for(BulletType type : types) type.link(this);
        unsupported = List.copyOf(missing);
    }

    /** The bullet types of {@code world}'s content. */
    public static BulletTypes of(World world){
        return new BulletTypes(world.content());
    }

    /** The bullet types of {@code content}. */
    public static BulletTypes of(ContentEpoch content){
        return new BulletTypes(content);
    }

    public ContentEpoch content(){
        return content;
    }

    /** The type of bullet {@code id}. */
    public BulletType get(int id){
        return types[id];
    }

    /** The type of {@code def}, or null for none. */
    public BulletType get(BulletDef def){
        return def == null ? null : types[def.id()];
    }

    public int size(){
        return types.length;
    }

    /** The kinds that run an ancestor's behaviour because none of their own is ported, sorted. */
    public List<String> unsupported(){
        return unsupported;
    }

    private static BulletType instantiate(BulletDef def, Set<String> missing){
        if(!def.baked()) return new BulletType();
        KindSchema kind = def.fields().kind();
        for(KindSchema at = kind; at != null; at = at.parent()){
            Supplier<? extends BulletType> constructor = Registered.KINDS.get(at.key());
            if(constructor == null && !at.key().equals(BASE)) continue;
            if(at != kind) missing.add(kind.key());
            BulletType type = constructor == null ? new BulletType() : constructor.get();
            if(at != kind) type.unsupported();
            return type;
        }
        missing.add(kind.key());
        BulletType type = new BulletType();
        type.unsupported();
        return type;
    }

    private static final class Registered{
        static final SortedMap<String, Supplier<? extends BulletType>> KINDS = new TreeMap<>();

        static{
            for(Class<? extends BulletType> type : Registries.classes(BulletType.class)){
                String kind = Registries.key(type, BulletKind.class);
                if(kind == null) throw new IllegalArgumentException(type.getName() + " has no @BulletKind");
                var previous = KINDS.put(kind, Registries.constructor(type));
                if(previous != null) throw new IllegalArgumentException("bullet kind " + kind + " is registered twice");
            }
        }

        private Registered(){
        }
    }
}
