package com.xpdustry.shard.content;

import java.util.*;

/**
 * A frozen content snapshot, with dense arrays indexed by wire id (ARCHITECTURE.md §5). Every world pins one epoch;
 * worlds with the same content hash share it, so an epoch is immutable and safe to read from any thread.
 *
 * <p>This is the contract the simulation reads; {@code content.epoch.Epoch} is the implementation that packs build.
 * The defaults let hand-built test content implement only blocks and units.
 */
public interface ContentEpoch{
    /** A stable hash of the whole epoch. Checkpoints record it, and restoring one needs an epoch with the same hash. */
    String hash();

    /** The number of block ids; block ids are {@code 0 .. blocks() - 1}. */
    int blocks();

    /** The block with this wire id. */
    BlockDef block(int id);

    /** The number of item ids; an item module holds one count per item. */
    int items();

    /** The number of liquid ids; a liquid module holds one amount per liquid. */
    int liquids();

    /** The number of unit type ids. */
    int units();

    /** The unit type with this wire id. */
    UnitDef unit(int id);

    /** The number of ids of {@code type}. */
    default int count(ContentType type){
        return switch(type){
            case BLOCK -> blocks();
            case ITEM -> items();
            case LIQUID -> liquids();
            case UNIT -> units();
            default -> 0;
        };
    }

    /** The content of {@code type} with wire id {@code id}. */
    default ContentDef get(ContentType type, int id){
        return switch(type){
            case BLOCK -> block(id);
            case UNIT -> unit(id);
            default -> throw new UnsupportedOperationException("content " + hash() + " has no " + type.yamlName() + " definitions");
        };
    }

    /** The content of {@code type} named {@code name}, or null. */
    default ContentDef find(ContentType type, String name){
        for(int id = 0, n = count(type); id < n; id++){
            ContentDef def = get(type, id);
            if(def.name().equals(name)) return def;
        }
        return null;
    }

    default ItemDef item(int id){
        return (ItemDef)get(ContentType.ITEM, id);
    }

    default LiquidDef liquid(int id){
        return (LiquidDef)get(ContentType.LIQUID, id);
    }

    default StatusDef status(int id){
        return (StatusDef)get(ContentType.STATUS, id);
    }

    default BulletDef bullet(int id){
        return (BulletDef)get(ContentType.BULLET, id);
    }

    default WeatherDef weather(int id){
        return (WeatherDef)get(ContentType.WEATHER, id);
    }

    /** The block named {@code name}, or null. */
    default BlockDef block(String name){
        return (BlockDef)find(ContentType.BLOCK, name);
    }

    /** The unit type named {@code name}, or null. */
    default UnitDef unit(String name){
        return (UnitDef)find(ContentType.UNIT, name);
    }

    /** The item named {@code name}, or null. */
    default ItemDef item(String name){
        return (ItemDef)find(ContentType.ITEM, name);
    }

    /** The liquid named {@code name}, or null. */
    default LiquidDef liquid(String name){
        return (LiquidDef)find(ContentType.LIQUID, name);
    }

    /** The status effect named {@code name}, or null. */
    default StatusDef status(String name){
        return (StatusDef)find(ContentType.STATUS, name);
    }

    /** The bullet named {@code name} (a hoisted path like {@code duo/ammoTypes.copper}), or null. */
    default BulletDef bullet(String name){
        return (BulletDef)find(ContentType.BULLET, name);
    }

    /** The weather named {@code name}, or null. */
    default WeatherDef weather(String name){
        return (WeatherDef)find(ContentType.WEATHER, name);
    }

    /** The names of {@code type}'s content, indexed by wire id. */
    default List<String> names(ContentType type){
        var out = new ArrayList<String>(count(type));
        for(int id = 0, n = count(type); id < n; id++) out.add(get(type, id).name());
        return Collections.unmodifiableList(out);
    }

    /** The wire id of the {@code type} named exactly {@code name}, or -1. */
    default int id(ContentType type, String name){
        ContentDef def = find(type, name);
        return def == null ? -1 : def.id();
    }

    // What the client numbers that Shard has no definitions for: names only, in the client's id order.

    /** Vanilla's {@code UnitCommand} names by id. */
    default List<String> unitCommands(){
        return List.of();
    }

    /** Vanilla's {@code UnitStance} names by id. */
    default List<String> unitStances(){
        return List.of();
    }

    /** Vanilla's sector names by id, as the content header lists them. */
    default List<String> sectors(){
        return List.of();
    }

    /** Vanilla's planet names by id ({@code rules.planet} names one). */
    default List<String> planets(){
        return List.of();
    }

    /**
     * The names of {@code type}'s content by logic id, not wire id ({@code GlobalVars.lookupContent}, from the client's
     * {@code logicids.dat}): what mlog's {@code lookup} reads, and the length is {@code @<type>Count}. Only blocks, units,
     * items and liquids have logic ids; a name this epoch lacks looks up as null.
     */
    default List<String> logicIds(ContentType type){
        return List.of();
    }

    /** Vanilla's {@code Team.baseTeams}: teams 0 to 5, which mlog names as {@code @derelict} to {@code @blue}. */
    int BASE_TEAMS = 6;

    /** {@code Team.all.length}: team ids are a byte. */
    int TEAMS = 256;

    /** The teams vanilla names (Team.java:33-41), by id; the others are {@code team#<id>}. */
    List<String> NAMED_TEAMS = List.of("derelict", "sharded", "crux", "malis", "green", "blue", "neoplastic");

    /** {@code Team.get(id).name}. */
    default String teamName(int id){
        if(id < 0 || id >= TEAMS) throw new IllegalArgumentException("no team " + id);
        return id < NAMED_TEAMS.size() ? NAMED_TEAMS.get(id) : "team#" + id;
    }
}
