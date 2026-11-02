package com.xpdustry.shard.compat.v160.world;

import com.xpdustry.shard.compat.v160.blocks.environment.*;
import com.xpdustry.shard.compat.v160.build.*;
import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.compat.v160.rules.*;
import com.xpdustry.shard.content.ContentType;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.objectives.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.fog.*;
import com.xpdustry.shard.sim.blocks.sandbox.LaunchPayload;
import com.xpdustry.shard.sim.fire.Fire;
import com.xpdustry.shard.sim.puddle.Puddle;
import com.xpdustry.shard.sim.unit.Player;
import com.xpdustry.shard.sim.unit.Unit;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.weather.*;
import com.xpdustry.shard.sim.world.*;

import com.xpdustry.shard.wire.v160_7.*;

import java.util.*;

/**
 * Freezes what a world stream needs from a world and its {@link MapState}, at a tick boundary, on the simulation
 * thread: the data patch section (the map's own assets, or the world's epoch's patch), tiles in the stream's ids with their data, every building's
 * chunk (passthrough bytes or its codec's), the live units ({@link #entities}), the rules JSON and copies of the rest.
 * {@link WorldStreamWriter} then encodes the result anywhere.
 *
 * <p>A world whose content is a map's patch of the epoch ({@code Epoch#serves}) projects as one of the epoch: the patch
 * keeps ids and names, and its section carries the patch to clients.
 *
 * <p>The stream's content header is the epoch's, or, when bytes passed through from a save, that save's header
 * {@linkplain ContentHeader#extend extended} with the epoch, so the bytes keep their meaning.
 */
public final class WorldProjection{
    private final Epoch epoch;
    private final BuildingCodecs codecs;
    private final RulesJson rules;
    private final byte[] patches;

    /** Projects worlds of {@code epoch}'s content. */
    public WorldProjection(Epoch epoch, BuildingCodecs codecs){
        this.epoch = epoch;
        this.codecs = codecs;
        this.rules = new RulesJson(epoch);
        this.patches = WorldStreamWriter.patches(epoch);
    }

    /** The content header a stream of a world with {@code state} uses. */
    public ContentHeader header(MapState state){
        return state.source == null ? ContentHeader.of(epoch) : state.source.extend(epoch);
    }

    /** {@link #capture(World, MapState, PlayerState)} for {@code player}, a live player of {@code world}, as it is now. */
    public WorldStreamSource capture(World world, MapState state, Player player){
        return capture(world, state, PlayerState.of(new EntityProjector(world.content(), codecs).player(player)));
    }

    /** A save's capture ({@code writeEntities}): every live unit, whatever the fog. */
    public WorldStreamSource capture(World world, MapState state, PlayerState player){
        return capture(world, state, player, false);
    }

    /**
     * The world stream {@code player} joins with ({@code NetworkIO.writeWorld}): as a save's, but with fog the units
     * in fog to the player's team are left out ({@code u -> !u.inFogTo(player.team())}, NetworkIO.java:68).
     */
    public WorldStreamSource stream(World world, MapState state, PlayerState player){
        return capture(world, state, player, true);
    }

    private WorldStreamSource capture(World world, MapState state, PlayerState player, boolean stream){
        if(!epoch.serves(world.content())){
            throw new IllegalArgumentException("the world's content " + world.content().hash() + " is not " + epoch + " or a patch of it");
        }
        ContentHeader header = header(state);
        ContentHeader.IdMapping ids = header.mapping(epoch);
        // the wave and its countdown are the world's once it has a wave state (W2.11's WaveState)
        WaveState waves = WaveState.of(world);
        // a map's own patches, as it loaded them (MapState.patches); else the epoch's
        return new WorldStreamSource(state.patches != null ? state.patches : patches,
            rules.write(world.part(WorldRules.class).rules),
            state.locales,
            Collections.unmodifiableSequencedMap(new LinkedHashMap<>(state.tags)),
            waves == null ? state.wave : waves.wave, waves == null ? state.wavetime : waves.wavetime, state.tick + world.ticks(),
            state.seed0, state.seed1,
            player, header, map(world, ids),
            List.copyOf(state.entityMapping), TeamPlansCodec.project(world, ids),
            entities(world, state, ids, stream ? viewer(world, player.team()) : -1),
            markers(world, state),
            customChunks(world, state));
    }

    /** {@code MapMarkers.write}: the world's markers, or the file's in a world without them. */
    private byte[] markers(World world, MapState state){
        ObjectiveState objectives = ObjectiveState.of(world);
        if(objectives != null) return rules.objectives().writeMarkers(objectives.markers);
        return state.markers == null ? WorldStreamWriter.NO_MARKERS : state.markers.clone();
    }

    /** The team whose fog filters a stream's units: {@code -1} (none) without fog. */
    public static int viewer(World world, int team){
        return world.part(WorldRules.class).rules.fog ? team : -1;
    }

    /**
     * {@code writeCustomChunks}: the static fog only while {@code FogControl.shouldWrite}, as the world's
     * {@link FogControl} holds it now, in the file's chunk's place (or last); in a world without the part, the chunk the
     * map loaded with.
     */
    private static List<MapState.CustomChunk> customChunks(World world, MapState state){
        Rules current = world.part(WorldRules.class).rules;
        FogControl fog = FogControl.of(world);
        if(fog == null){
            if(current.fog && current.staticFog) return List.copyOf(state.customChunks);
            return state.customChunks.stream().filter(chunk -> !chunk.name().equals(StaticFog.CHUNK)).toList();
        }
        MapState.CustomChunk live = fog.shouldWrite(current)
            ? new MapState.CustomChunk(StaticFog.CHUNK, fog.writeChunk(world.tiles().width(), world.tiles().height())) : null;
        var out = new ArrayList<MapState.CustomChunk>(state.customChunks.size() + 1);
        boolean placed = false;
        for(MapState.CustomChunk chunk : state.customChunks){
            if(!chunk.name().equals(StaticFog.CHUNK)) out.add(chunk);
            else if(live != null && !placed){
                out.add(live);
                placed = true;
            }
        }
        if(live != null && !placed) out.add(live);
        return List.copyOf(out);
    }

    /**
     * The entity section of {@code world} ({@code writeWorldEntities}), in {@code ids}' stream ids, as vanilla writes
     * {@code Groups.all}'s serialized entities before {@code Groups.unit} (SaveVersion.java:427): the saved entities
     * that didn't come to life ({@link MapState#live}) other than units, the live fires, puddles and weather states in
     * {@code Groups.all} order, the saved units that didn't come to life, then every live unit in the world's order. A
     * loaded unit keeps the class it was saved with, a legacy one too, as vanilla's entity does. A puddle that lost its
     * tile to another has left {@code Groups.all} and isn't written, as in vanilla.
     */
    public List<MapState.EntityChunk> entities(World world, MapState state, ContentHeader.IdMapping ids){
        return entities(world, state, ids, -1);
    }

    /**
     * {@link #entities(World, MapState, ContentHeader.IdMapping)} as a world stream to {@code viewer}'s team writes them:
     * the live units in fog to it left out ({@code -1}: none). Saved units that didn't come to life stay, as bytes.
     */
    public List<MapState.EntityChunk> entities(World world, MapState state, ContentHeader.IdMapping ids, int viewer){
        var out = new ArrayList<MapState.EntityChunk>();
        var savedUnits = new ArrayList<MapState.EntityChunk>();
        for(MapState.EntityChunk chunk : state.entities){
            if(state.live.contains(chunk.id())) continue;
            EntityClasses.EntityClass type = EntityClasses.get(chunk.classId());
            (type != null && type.unit() ? savedUnits : out).add(chunk);
        }
        var live = new StreamEntities(world, codecs, ids, state.classes);
        EntityGroups groups = EntityGroups.of(world);
        if(groups != null){
            for(int i = 0; i < groups.all().size(); i++){
                Entity entity = groups.all().get(i);
                switch(entity){
                    case WeatherState weather -> out.add(live.chunk(weather, EntityClasses.WEATHER_STATE));
                    case Fire fire -> out.add(live.chunk(fire, EntityClasses.FIRE));
                    case Puddle puddle -> out.add(live.chunk(puddle, EntityClasses.PUDDLE));
                    case LaunchPayload payload -> out.add(live.chunk(payload, EntityClasses.LAUNCH_PAYLOAD));
                    default -> {
                    }
                }
            }
        }
        out.addAll(savedUnits);
        for(int i = 0; i < world.units().size(); i++){
            Unit unit = world.units().get(i);
            if(viewer != -1 && unit.inFogTo(viewer)) continue;
            out.add(live.chunk(unit, live.classOf(unit)));
        }
        return List.copyOf(out);
    }

    /** The map section of {@code world}, in {@code ids}' stream ids. */
    public MapImage map(World world, ContentHeader.IdMapping ids){
        TileGrid tiles = world.tiles();
        int size = tiles.size();
        short[] floors = new short[size], overlays = new short[size], blocks = new short[size];
        int[] centers = new int[size];
        var saveData = new BitSet(size);
        var data = new TreeMap<Integer, Long>();
        for(int i = 0; i < size; i++){
            floors[i] = (short)ids.requireStream(ContentType.BLOCK, tiles.floor(i));
            overlays[i] = (short)ids.requireStream(ContentType.BLOCK, tiles.overlay(i));
            blocks[i] = (short)ids.requireStream(ContentType.BLOCK, tiles.block(i));
            if(tiles.shouldSaveData(i)){
                saveData.set(i);
                data.put(i, TileDataCodec.packed(tiles, i));
            }
            Building building = world.buildings().at(i);
            centers[i] = building == null ? -1 : building.tile();
        }
        var chunks = new TreeMap<Integer, byte[]>();
        world.buildings().each(building -> chunks.put(building.tile(), codecs.chunk(building, world.content(), ids)));
        return new MapImage(tiles.width(), tiles.height(), floors, overlays, blocks, saveData,
            Collections.unmodifiableSortedMap(data), centers, Collections.unmodifiableSortedMap(chunks));
    }
}
