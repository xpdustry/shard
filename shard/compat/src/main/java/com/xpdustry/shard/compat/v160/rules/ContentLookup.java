package com.xpdustry.shard.compat.v160.rules;

import com.xpdustry.shard.content.*;

import java.util.*;

/**
 * How vanilla v160.7 finds content by name in an epoch: {@code ContentLoader.getByName} with the save-reader renames
 * and fallbacks for blocks, and {@code LegacyIO}'s old unit names. What the rules JSON, the content header and save
 * remaps go through.
 */
public final class ContentLookup{
    /** Vanilla's {@code SaveFileReader.fallback}: old block names that saves may hold, to their current names. */
    public static final Map<String, String> BLOCK_FALLBACK = pairs(
        "dart-mech-pad", "legacy-mech-pad", "dart-ship-pad", "legacy-mech-pad", "javelin-ship-pad", "legacy-mech-pad",
        "trident-ship-pad", "legacy-mech-pad", "glaive-ship-pad", "legacy-mech-pad", "alpha-mech-pad", "legacy-mech-pad",
        "tau-mech-pad", "legacy-mech-pad", "omega-mech-pad", "legacy-mech-pad", "delta-mech-pad", "legacy-mech-pad",
        "draug-factory", "legacy-unit-factory", "spirit-factory", "legacy-unit-factory", "phantom-factory", "legacy-unit-factory",
        "wraith-factory", "legacy-unit-factory", "ghoul-factory", "legacy-unit-factory-air",
        "revenant-factory", "legacy-unit-factory-air", "dagger-factory", "legacy-unit-factory",
        "crawler-factory", "legacy-unit-factory", "titan-factory", "legacy-unit-factory-ground",
        "fortress-factory", "legacy-unit-factory-ground",
        "mass-conveyor", "payload-conveyor", "vestige", "scepter", "turbine-generator", "steam-generator",
        "rocks", "stone-wall", "sporerocks", "spore-wall", "icerocks", "ice-wall", "dunerocks", "dune-wall",
        "sandrocks", "sand-wall", "shalerocks", "shale-wall", "snowrocks", "snow-wall", "saltrocks", "salt-wall",
        "dirtwall", "dirt-wall",
        "ignarock", "basalt", "holostone", "dacite", "holostone-wall", "dacite-wall", "rock", "boulder",
        "snowrock", "snow-boulder", "cliffs", "stone-wall", "craters", "crater-stone", "deepwater", "deep-water",
        "water", "shallow-water", "sand", "sand-floor", "slag", "molten-slag",
        "cryofluidmixer", "cryofluid-mixer", "block-forge", "constructor", "block-unloader", "payload-unloader",
        "block-loader", "payload-loader", "thermal-pump", "impulse-pump", "alloy-smelter", "surge-smelter",
        "steam-vent", "rhyolite-vent", "fabricator", "tank-fabricator", "basic-reconstructor", "refabricator");

    /** Vanilla's {@code SaveFileReader.modContentNameMap}, which {@code getByName} applies to block names. */
    public static final Map<String, String> BLOCK_RENAMES = pairs(
        "craters", "crater-stone", "deepwater", "deep-water", "water", "shallow-water", "slag", "molten-slag");

    /** Vanilla's {@code LegacyIO.unitMap}: old unit names in spawn groups. */
    public static final Map<String, String> UNIT_RENAMES = pairs(
        "titan", "mace", "chaos-array", "scepter", "eradicator", "reign", "eruptor", "atrax", "wraith", "flare",
        "ghoul", "horizon", "revenant", "zenith", "lich", "antumbra", "reaper", "eclipse", "draug", "mono",
        "phantom", "poly", "spirit", "poly");

    private ContentLookup(){
    }

    /** The name of {@code type} {@code id} in {@code content}, or null when out of range. */
    public static String name(ContentEpoch content, ContentType type, int id){
        return id >= 0 && id < content.count(type) ? content.get(type, id).name() : null;
    }

    /** Vanilla's {@code ContentLoader.getByName}: the id of {@code name}, block names through {@link #BLOCK_RENAMES}; or -1. */
    public static int byName(ContentEpoch content, ContentType type, String name){
        if(name == null) return -1;
        return content.id(type, type == ContentType.BLOCK ? BLOCK_RENAMES.getOrDefault(name, name) : name);
    }

    /** {@link #byName}, trying {@link #BLOCK_FALLBACK} first for blocks, as the content header and saves do. */
    public static int byNameWithFallback(ContentEpoch content, ContentType type, String name){
        if(name == null) return -1;
        return byName(content, type, type == ContentType.BLOCK ? BLOCK_FALLBACK.getOrDefault(name, name) : name);
    }

    private static Map<String, String> pairs(String... pairs){
        var map = new TreeMap<String, String>();
        for(int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return Collections.unmodifiableMap(map);
    }
}
