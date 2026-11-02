package com.xpdustry.shard.sim.objectives;

import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code MapLocales} ({@code state.mapLocales}): a map's own text bundles, by locale, which
 * {@code localeprint} reads. The map's meta and the world stream carry them as JSON, which {@code :compat} keeps as
 * the map wrote it; this is the lookup.
 *
 * <p>Vanilla reads the server's locale from its settings, whose default ({@code "default"}) means the JVM's default
 * language, so a vanilla server's {@code localeprint} depends on the host's environment (MapLocales.java:105). Shard's
 * locale is always {@link #LOCALE}. A key the map lacks reads as vanilla's {@code "???key???"}: vanilla would first try
 * the game's own bundle, which a Shard server doesn't ship.
 */
public final class MapLocales implements Checkpointable{
    /** The server's locale. */
    public static final String LOCALE = "en";

    private TreeMap<String, TreeMap<String, String>> bundles = new TreeMap<>();

    /** Adds {@code value} for {@code key} to {@code locale}'s bundle. */
    public void put(String locale, String key, String value){
        bundles.computeIfAbsent(locale, k -> new TreeMap<>()).put(key, value);
    }

    public boolean isEmpty(){
        return bundles.isEmpty();
    }

    /** {@code getProperty(key)}. */
    public String getProperty(String key){
        Map<String, String> bundle = bundles.get(LOCALE);
        if(bundle == null || !bundle.containsKey(key)) return "???" + key + "???";
        return bundle.get(key);
    }

    /** {@code containsProperty(key)}. */
    public boolean containsProperty(String key){
        Map<String, String> bundle = bundles.get(LOCALE);
        return bundle != null && bundle.containsKey(key);
    }

    public MapLocales copy(){
        var out = new MapLocales();
        bundles.forEach((locale, bundle) -> out.bundles.put(locale, new TreeMap<>(bundle)));
        return out;
    }

    public void hash(Hasher hasher){
        hasher.i(bundles.size());
        bundles.forEach((locale, bundle) -> {
            hasher.str(locale).i(bundle.size());
            bundle.forEach((key, value) -> {
                hasher.str(key);
                ObjectiveMarker.hash(hasher, value);
            });
        });
    }

    @Override
    public void write(CheckpointOut out){
        var flat = new ArrayList<String>();
        bundles.forEach((locale, bundle) -> bundle.forEach((key, value) -> {
            flat.add(locale);
            flat.add(key);
            flat.add(value);
        }));
        out.strs("bundles", flat);
    }

    @Override
    public void read(CheckpointIn in){
        bundles = new TreeMap<>();
        List<String> flat = in.strs("bundles");
        for(int i = 0; i < flat.size(); i += 3) put(flat.get(i), flat.get(i + 1), flat.get(i + 2));
    }
}
