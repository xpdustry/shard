package com.xpdustry.shard.content.load;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.schema.*;

import java.util.*;

/**
 * A loaded pack: every content object as authored, by type in wire id order. Names listed in {@code ids.yml} that no
 * prototype defines are derived content ({@code turret-unit-build-tower}): their slot is null until the bake creates
 * them.
 */
public final class Loaded{
    private final Pack pack;
    private final EnumMap<ContentType, List<Authored>> content;
    private final EnumMap<ContentType, List<String>> names;
    private final EnumMap<ContentType, Map<String, Integer>> ids = new EnumMap<>(ContentType.class);

    Loaded(Pack pack, EnumMap<ContentType, List<Authored>> content, EnumMap<ContentType, List<String>> names){
        this.pack = pack;
        this.content = content;
        this.names = names;
        names.forEach((type, list) -> {
            Map<String, Integer> map = new HashMap<>();
            for(int i = 0; i < list.size(); i++) map.put(list.get(i), i);
            ids.put(type, map);
        });
    }

    public Pack pack(){
        return pack;
    }

    public Schema schema(){
        return pack.schema();
    }

    /** Content of {@code type} by id; derived slots are null. */
    public List<Authored> of(ContentType type){
        return Collections.unmodifiableList(content.get(type));
    }

    /** Names of {@code type} by id, derived ones included. */
    public List<String> names(ContentType type){
        return Collections.unmodifiableList(names.get(type));
    }

    /** The id of {@code name}, or -1. */
    public int id(ContentType type, String name){
        return ids.get(type).getOrDefault(name, -1);
    }

    public Authored get(ContentType type, String name){
        int id = id(type, name);
        return id < 0 ? null : content.get(type).get(id);
    }
}
