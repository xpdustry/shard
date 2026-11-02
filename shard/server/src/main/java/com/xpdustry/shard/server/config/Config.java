package com.xpdustry.shard.server.config;

import java.util.*;

/**
 * One resolved, immutable config generation: a value for every key in {@link ConfigKeys#ALL}, and where it came
 * from. {@link #world(String)} gives a world's view, with that world's overrides on top. Precedence, low to high
 * (each layer only for the keys it sets):
 *
 * <ol>
 *   <li>the defaults in {@link ConfigKeys};</li>
 *   <li>{@code shard.yml};</li>
 *   <li>{@code SHARD__<SECTION>__<KEY>} environment variables;</li>
 *   <li>{@code worlds/<id>.yml}, world-scoped keys only;</li>
 *   <li>{@code SHARD__WORLDS__<ID>__<SECTION>__<KEY>}, world-scoped keys only.</li>
 * </ol>
 */
public final class Config{
    private final Map<ConfigKey<?>, Entry> host;
    private final Map<String, Map<ConfigKey<?>, Entry>> worlds;
    private final String world;

    Config(Map<ConfigKey<?>, Entry> host, Map<String, Map<ConfigKey<?>, Entry>> worlds){
        this(host, worlds, null);
    }

    private Config(Map<ConfigKey<?>, Entry> host, Map<String, Map<ConfigKey<?>, Entry>> worlds, String world){
        this.host = host;
        this.worlds = worlds;
        this.world = world;
    }

    /** Only the compiled defaults. */
    public static Config defaults(){
        var host = new LinkedHashMap<ConfigKey<?>, Entry>();
        for(var key : ConfigKeys.ALL) host.put(key, new Entry(key.defaultValue(), Source.DEFAULT));
        return new Config(Collections.unmodifiableMap(host), Map.of());
    }

    @SuppressWarnings("unchecked")
    public <T> T get(ConfigKey<T> key){
        return (T)entry(key).value();
    }

    /** Where the value of {@code key} comes from. */
    public Source source(ConfigKey<?> key){
        return entry(key).source();
    }

    Entry entry(ConfigKey<?> key){
        if(world != null){
            var entry = worlds.getOrDefault(world, Map.of()).get(key);
            if(entry != null) return entry;
        }
        var entry = host.get(key);
        if(entry == null) throw new IllegalArgumentException("not a config key: " + key);
        return entry;
    }

    /**
     * This config as world {@code id} sees it. A world with no overrides sees the host values. Only world-scoped keys
     * differ between worlds.
     */
    public Config world(String id){
        if(!ConfigKeys.WORLD_ID.matcher(id).matches()) throw new IllegalArgumentException("bad world id: " + id);
        return new Config(host, worlds, id);
    }

    /** The world this view is for, or null for the host view. */
    public String worldId(){
        return world;
    }

    /** The worlds with at least one override, sorted. */
    public SortedSet<String> worldsWithOverrides(){
        return new TreeSet<>(worlds.keySet());
    }

    Map<ConfigKey<?>, Entry> hostEntries(){
        return host;
    }

    Map<String, Map<ConfigKey<?>, Entry>> worldEntries(){
        return worlds;
    }

    /**
     * Every key's effective value and source in this view, one per line, secrets redacted:
     * {@code net.port = 7000  (env SHARD__NET__PORT)}.
     */
    public List<String> explain(){
        var lines = new ArrayList<String>();
        for(var key : ConfigKeys.ALL){
            var entry = entry(key);
            lines.add(key.path() + " = " + key.display(entry.value()) + "  (" + entry.source() + ")");
        }
        return lines;
    }

    /** A value and where it came from. */
    record Entry(Object value, Source source){
    }

    /** One layer's location for a value: a file position, an environment variable, or the default. */
    public record Source(Layer layer, String location){
        static final Source DEFAULT = new Source(Layer.DEFAULT, "default");

        @Override
        public String toString(){
            return location;
        }
    }

    public enum Layer{ DEFAULT, FILE, ENV, WORLD_FILE, WORLD_ENV }
}
