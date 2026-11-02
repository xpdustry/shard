package com.xpdustry.shard.content.bake;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.load.*;
import com.xpdustry.shard.content.schema.*;

import java.util.*;

/** Baked content: per type, in wire id order, the struct a booted vanilla would hold. */
public final class Baked{
    private final Loaded loaded;
    private final EnumMap<ContentType, List<Struct>> content;
    private final EnumMap<ContentType, List<String>> names;

    Baked(Loaded loaded, EnumMap<ContentType, List<Struct>> content, EnumMap<ContentType, List<String>> names){
        this.loaded = loaded;
        this.content = content;
        this.names = new EnumMap<>(ContentType.class);
        names.forEach((type, list) -> this.names.put(type, List.copyOf(list)));
    }

    public Loaded loaded(){
        return loaded;
    }

    public Schema schema(){
        return loaded.schema();
    }

    public List<Struct> of(ContentType type){
        return content.get(type);
    }

    public List<String> names(ContentType type){
        return names.get(type);
    }

    /**
     * This content with the structs of {@code replaced} in place of its own, per type, ids and names unchanged: what a
     * content patch makes of it ({@code content.patch.DataPatcher}), which edits baked values and bakes nothing again.
     */
    public Baked with(Map<ContentType, List<Struct>> replaced){
        var out = new EnumMap<ContentType, List<Struct>>(content);
        replaced.forEach((type, structs) -> {
            if(structs.size() != content.get(type).size()){
                throw new IllegalArgumentException(type.yamlName() + ": " + structs.size() + " structs for " + content.get(type).size() + " ids");
            }
            out.put(type, List.copyOf(structs));
        });
        return new Baked(loaded, out, names);
    }

    /** The baked struct of {@code name}, or null. */
    public Struct get(ContentType type, String name){
        int id = names.get(type).indexOf(name);
        return id < 0 ? null : content.get(type).get(id);
    }
}
