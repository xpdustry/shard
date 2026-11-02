package com.xpdustry.shard.compat.v160.world;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.patch.*;

import java.util.*;

/**
 * What a loaded map or save holds that the world does not model yet, kept beside it so the world stream and the v13
 * writer can put it back: the source's id space, metadata, team plans, saved entities, markers and custom chunks. Each
 * field moves into {@code :sim} when its owner models it; the javadoc names the owner. Tiles, their data and buildings
 * are the world's.
 */
public final class MapState{
    /**
     * The content header that passthrough building bodies ({@code PassthroughBuilding}), entity bytes and plan configs
     * are in, or null when nothing passed through (streams then use the current content's header). Bundled maps have
     * other ids than the epoch's, and these bytes are opaque, so streams keep this id space; it is not checkpointed.
     */
    public ContentHeader source;
    /**
     * The map's data assets as vanilla holds them once loaded ({@code state.data}): its patches, which made the world's
     * content, and its external assets (sprites, sounds…), which a join offers before the world (W5.4). Empty for a
     * map without any.
     */
    public DataAssets assets = DataAssets.NONE;
    /**
     * The data patch section the world stream and saves write ({@code writeDataPatches(stream, false)} of
     * {@link #assets}), or null for a world whose content is its epoch's, which writes its epoch's own patch.
     */
    public byte[] patches;
    /** {@code MapLocales} JSON (WP74). */
    public String locales = "{}";
    /** The map's tags ({@code state.map.tags}): what the world stream sends and saves keep. */
    public LinkedHashMap<String, String> tags = new LinkedHashMap<>();
    /** The wave counter and countdown (waves WP). */
    public int wave = 1;
    public float wavetime;
    /** {@code state.tick} when the world was loaded; the stream adds the world's ticks. */
    public double tick;
    /** {@code GlobalVars.rand}, the logic {@code rand} state (WP18): vanilla's {@code new Rand(0)} until then. */
    public long seed0 = new Rand(0).seed0, seed1 = new Rand(0).seed1;
    /** {@code EntityMapping.customIdMap} entries of the source (mod entity classes). */
    public List<EntityMappingEntry> entityMapping = new ArrayList<>();
    /**
     * The file's build plans by team, as {@code readTeamBlocks} read them. Loading moves them into the world's
     * {@code TeamData.plans} (construction), which streams and saves write from.
     */
    public List<TeamPlans> plans = new ArrayList<>();
    /** Saved entities, passed through (unit WPs). */
    public List<EntityChunk> entities = new ArrayList<>();
    /**
     * The ids of the {@link #entities} that came to life as the world's entities (units, weather states): a world
     * stream writes the live entities instead of these chunks ({@link WorldProjection}).
     */
    public SortedSet<Integer> live = new TreeSet<>();
    /**
     * The entity class each unit that came to life was saved with, carried units included, by entity id: a vanilla
     * entity keeps its class, a legacy one too, which its writes use ({@code EntityProjector#classOf}).
     */
    public SortedMap<Integer, Integer> classes = new TreeMap<>();
    /** {@code MapMarkers} as UBJSON (WP74), or null for none. */
    public byte[] markers;
    /** Custom save chunks, passed through. */
    public List<CustomChunk> customChunks = new ArrayList<>();
    /**
     * The first building or entity whose read's draws Shard can't tell ({@code ReadDraws}), or null: entity ids and the
     * generator differ from vanilla's after it.
     */
    public String unknownDraws;

    /** An {@code EntityMapping.customIdMap} entry: a class id and the entity name it stands for. */
    public record EntityMappingEntry(int id, String name){}

    /** One build plan of {@code writeTeamBlocks}; {@code config} is the raw {@code TypeIO} object, its tag included. */
    public record TeamPlan(int x, int y, int rotation, int block, byte[] config){}

    /** A team's build plans, in order. */
    public record TeamPlans(int team, List<TeamPlan> plans){}

    /** A saved entity: its class id, entity id and {@code write} bytes (which start with the entity's revision). */
    public record EntityChunk(int classId, int id, byte[] body){}

    /** A named custom chunk ({@code SaveVersion.addCustomChunk}). */
    public record CustomChunk(String name, byte[] bytes){}
}
