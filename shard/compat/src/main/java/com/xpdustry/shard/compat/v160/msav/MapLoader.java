package com.xpdustry.shard.compat.v160.msav;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.blocks.environment.*;
import com.xpdustry.shard.compat.v160.build.*;
import com.xpdustry.shard.compat.v160.filters.*;
import com.xpdustry.shard.compat.v160.msav.SaveFile.*;
import com.xpdustry.shard.compat.v160.rules.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.compat.v160.world.MapState.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.ContentType;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.content.patch.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.blocks.sandbox.LaunchPayload;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.fire.*;
import com.xpdustry.shard.sim.puddle.*;
import com.xpdustry.shard.sim.game.*;
import com.xpdustry.shard.sim.logic.*;
import com.xpdustry.shard.sim.objectives.*;
import com.xpdustry.shard.sim.path.*;
import com.xpdustry.shard.sim.fog.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.weather.*;
import com.xpdustry.shard.sim.world.*;

import com.xpdustry.shard.wire.v160_7.*;

import java.util.*;
import java.util.function.*;

/**
 * Loads a {@link SaveFile} into a new {@link World} as vanilla's {@code SaveVersion.readMap} and
 * {@code readEntities} would, and keeps what the world doesn't model yet in a {@link MapState}.
 *
 * <p>Tiles go straight onto the grid, between {@link Buildings#beginLoad()} and {@link Buildings#endLoad(boolean)}:
 * floors and overlays as columns, then a {@link Buildings#setBlock} per block origin in tile order, so buildings get
 * vanilla's entity ids, then tile data. Each building's {@link BuildingBase} is read into it; the rest of its chunk goes
 * to its kind's codec if it has one for the chunk's revision, else to the {@link PassthroughBuilding} the kind registry
 * made. Passthrough bytes keep the file's ids, which {@link MapState#source} records.
 *
 * <p>Vanilla's {@code LegacyBlock}s load with their base read and their body dropped, and {@link Buildings#endLoad}
 * removes or replaces them, as vanilla's map end does.
 *
 * <p>Building creation draws vanilla's entity ids and generator values ({@link Building#create}); the units and
 * buildings vanilla constructs while it reads payloads Shard keeps as bytes draw theirs through {@link ReadDraws}, right
 * after each building's and entity's read. {@link MapState#unknownDraws} names the first it can't tell.
 *
 * <p>Saved units come to life ({@link SavedUnits}): each is created, given its saved id and added, in file order, as
 * vanilla's {@code readWorldEntities} does, with linked storage already merged into the cores by the map's end. Their
 * chunks stay in {@link MapState#entities} too, which the world stream still sends until it projects live units.
 * {@link #loadMap} ends as {@code World.loadMap} does, pruning the active teams under the file's rules; {@link #play} is
 * then {@code logic.play()}.
 *
 * <p>A file's data assets ({@link #filePatches}) are read as vanilla's {@code readDataPatches} reads them, before
 * anything else: its patches, applied by {@link DataPatcher}, make the world's own content, a patch of this loader's
 * epoch with its ids and names, so the world is created with that content whatever the template's; the assets go to
 * {@link MapState#assets} and the section the world writes back to {@link MapState#patches}, as vanilla writes them.
 *
 * <p>Refused, with a message: patches Shard can't apply as vanilla does, content assets, external assets stored by
 * hash only (Shard has no asset cache to serve them from), patches over content other than vanilla's, and a building
 * block without saved state (only a codec could make its defaults).
 *
 * <p>{@link #load} is vanilla's {@code SaveIO.load}; {@link #loadMap} is {@code World.loadMap}, which also runs the
 * map's generation filters ({@link MapFilters}) just before the map ends loading.
 */
public final class MapLoader{
    private final Epoch epoch;
    private final BuildingCodecs codecs;
    private final RulesJson json;
    private final MapRules mapRules;
    private final byte[] patches;

    /** Loads files into worlds of {@code epoch}'s content. */
    public MapLoader(Epoch epoch, BuildingCodecs codecs){
        this.epoch = epoch;
        this.codecs = codecs;
        this.json = new RulesJson(epoch);
        this.mapRules = new MapRules(json);
        this.patches = WorldStreamWriter.patches(epoch);
    }

    /**
     * What a file's patch region gives the world: the assets as vanilla holds them once loaded, the content they make
     * and the section the world writes back ({@code writeDataPatches(stream, false)}).
     */
    public record FilePatches(DataAssets assets, Epoch content, byte[] section){
    }

    /** A loaded world and the state beside it. */
    public record Loaded(World world, MapState state){
    }

    /** The rules the server's {@code host} command plays the map with ({@code Map.applyRules(mode)}). */
    public Rules hostRules(SaveFile save, Gamemode mode){
        return mapRules.apply(save.meta().get("rules"), mode);
    }

    /** The rules loading the file as a save gives ({@code SaveVersion.readRules}, outside the campaign). */
    public Rules saveRules(SaveFile save){
        Rules rules = json.read(save.meta().getOrDefault("rules", "{}"));
        if(rules.spawns.isEmpty()) rules.spawns = mapRules.defaultWaves();
        if(rules.planet == VanillaIds.SERPULO && rules.hasEnv(Env.SCORCHING)) rules.planet = VanillaIds.EREKIR;
        return rules;
    }

    /**
     * Loads {@code save} into a new world made from {@code template}, resized to the map, playing with {@code rules}.
     * The template's content must be this loader's epoch, or a view of it with the same hash. While it loads, the
     * world has the file's own rules ({@link #saveRules}), as vanilla's {@code SaveIO.load}: buildings, cores and saved
     * units are set up under them; {@code rules} replace them at the end.
     */
    public Loaded load(SaveFile save, WorldConfig template, Rules rules){
        return load(save, template, rules, false);
    }

    /** {@link #load}, hosting {@code save} as a map: its generation filters run before the map ends loading. */
    public Loaded loadMap(SaveFile save, WorldConfig template, Rules rules){
        return load(save, template, rules, true);
    }

    /** The content a world loaded from {@code save} plays with: this loader's epoch, or the patch the file makes of it. */
    public Epoch contentOf(SaveFile save){
        FilePatches file = filePatches(save);
        return file == null ? epoch : file.content();
    }

    /**
     * {@code readDataPatches} on a dedicated server: the file's assets, its patches applied to this loader's epoch under
     * the planet of the file's rules ({@code requiredPlanets}); null for a file without assets, or whose patch is this
     * epoch's own (a save of a world of Shard's content).
     *
     * @throws MsavException for what this loader refuses (the class javadoc)
     */
    public FilePatches filePatches(SaveFile save){
        if(!save.hasPatches() || save.version() >= 13 && Arrays.equals(save.patches(), patches)) return null;
        if(!epoch.hash().equals(Epoch.vanilla().hash())){
            throw new MsavException("the file carries data patches, which Shard applies to vanilla's content only, not to " + epoch);
        }
        DataAssets assets;
        try{
            assets = DataAssets.loaded(DataAssets.read(save.patches(), save.version()));
        }catch(IllegalArgumentException e){
            throw new MsavException("msav v" + save.version() + ": the data patches region: " + e.getMessage(), e);
        }
        if(!assets.contents().isEmpty()){
            throw new MsavException("the file's data patches add content (" + assets.contents().getFirst().path()
                + "), whose ids clients would make up, and Shard adds none");
        }
        for(DataAsset.ExternalAsset external : assets.externals()){
            if(!external.held() && external.hash() != null){
                throw new MsavException("the file's data patches name asset " + external.path() + " by hash only, and Shard has no asset cache to serve it from");
            }
        }
        DataPatcher.Applied applied;
        try{
            applied = DataPatcher.of(epoch, assets.patches(), planet(save));
        }catch(DataPatcher.Unsupported e){
            throw new MsavException("the file's data patches can't be applied as vanilla applies them: " + e.getMessage(), e);
        }
        DataAssets kept = assets.retaining(applied.kept());
        return new FilePatches(kept, applied.epoch(), kept.write());
    }

    /**
     * The planet {@code readDataPatches} sets before it applies patches: the file's rules' {@code planet}, serpulo if it
     * is missing or unknown. Vanilla's versions 11 and 12 read no planet and keep the last game's; Shard reads the
     * file's there too.
     */
    private String planet(SaveFile save){
        List<String> planets = epoch.planets();
        try{
            int planet = json.read(save.meta().getOrDefault("rules", "{}")).planet;
            if(planet >= 0 && planet < planets.size()) return planets.get(planet);
        }catch(RuntimeException ignored){
        }
        return "serpulo";
    }

    private Loaded load(SaveFile save, WorldConfig template, Rules rules, boolean hostsMap){
        ContentEpoch content = template.content();
        if(!epoch.serves(content)){
            throw new IllegalArgumentException("the template's content " + content.hash() + " is not " + epoch + " or a patch of it");
        }
        FilePatches file = filePatches(save);
        if(file != null){
            if(!content.hash().equals(epoch.hash()) && !content.hash().equals(file.content().hash())){
                throw new IllegalArgumentException("the template's content " + content.hash() + " is not the file's patched content " + file.content().hash());
            }
            content = file.content();
        }else if(!content.hash().equals(epoch.hash())){
            throw new IllegalArgumentException("the template's content " + content.hash() + " is a patch, and the file carries none");
        }
        SaveMap map = save.map();
        var config = new WorldConfig(content, map.width(), map.height(), template.seed(), template.systems(),
            template.kinds(), template.inboxCapacity(), template.journal());
        var world = new World(config);
        // SaveIO.load reads the file's own rules first (SaveVersion.readRules); the game's apply once it has loaded
        WorldRules worldRules = world.part(WorldRules.class);
        worldRules.rules = saveRules(save);
        // SaveVersion.readMeta sets state.tick, wave and wavetime before the map is read: a LogicFilter's globals read them
        float tick = parseFloat(save.meta().get("tick"), 0f);
        LogicPart logic = LogicPart.of(world);
        if(logic != null) logic.clock(tick);
        // vanilla's Time.time keeps running across SaveIO.load, so loaded buildings' Interval timers fire on the first
        // frame; a hosted map is a new game, whose clock starts at 0
        if(!hostsMap) world.timers().continueFrom(tick);
        WaveState waves = WaveState.of(world);
        if(waves != null){
            waves.wave = parseInt(save.meta().get("wave"), 0);
            waves.wavetime = parseFloat(save.meta().get("wavetime"), new Rules().waveSpacing);
        }
        ContentHeader.IdMapping ids = save.header().mapping(content);
        var draws = new ReadDraws(world, codecs, ids);
        world.buildings().beginLoad();
        loadFloors(world, map, ids);
        loadBlocks(world, map, ids, draws, save.version() <= 3);
        loadData(world, map);
        if(hostsMap) MapFilters.onMapLoad(world, save.meta(), saveRules(save), ids, draws.unknown());
        // endLoad runs vanilla's map end and the indexer's and World's WorldLoadEvent handlers; the pathfinder's resets
        world.buildings().endLoad(worldProcessorsAccessible(save));
        for(WorldPart part : world.parts()){
            if(part instanceof PathService paths) paths.invalidateAll();
        }
        // WaveSpawner's WorldLoadEvent handler: the spawn points of the loaded map
        WaveSpawner spawner = WaveSpawner.of(world);
        if(spawner != null) spawner.reset(world);
        MapState state = state(world, save, draws);
        if(file != null){
            state.assets = file.assets();
            state.patches = file.section();
        }
        objectiveState(world, save, hostsMap);
        afterReadAll(world);
        if(hostsMap) checkCores(world);
        worldRules.rules = rules;
        return new Loaded(world, state);
    }

    /** {@code SaveVersion.readWorldEntities}' last step: every building read, in read order, runs {@code afterReadAll()}. */
    private static void afterReadAll(World world){
        TileGrid tiles = world.tiles();
        for(int pos = 0; pos < tiles.size(); pos++){
            Building building = world.buildings().at(pos);
            if(building != null && building.tile() == pos) building.afterReadAll();
        }
    }

    /**
     * The end of {@code World.loadMap} on a headless server: {@code state.teams.getActive()}, still under the file's
     * rules, checks the map has a core, which drops the teams no longer active from the active list before the game's
     * rules apply. Vanilla quirk kept: a wave team without cores leaves it when the file's rules have no waves, and
     * never comes back. A map without cores loads; the caller decides whether to host it.
     */
    private static void checkCores(World world){
        Teams teams = Teams.of(world);
        if(teams != null) teams.getActive(world);
    }

    private static void loadFloors(World world, SaveMap map, ContentHeader.IdMapping ids){
        TileGrid tiles = world.tiles();
        int stone = world.content().id(ContentType.BLOCK, "stone");
        for(int i = 0; i < tiles.size(); i++){
            int floor = ids.toCurrent(ContentType.BLOCK, map.floors()[i]);
            // Vanilla quirk (SaveVersion.readMap): an air floor becomes stone's current id, which the file's content
            // header then maps like any other id from the file.
            if(floor == 0) floor = ids.toCurrent(ContentType.BLOCK, stone);
            int overlay = ids.toCurrent(ContentType.BLOCK, map.overlays()[i]);
            if(floor < 0 || overlay < 0) throw new MsavException("tile " + i + " has an invalid floor or overlay id");
            tiles.floor(i, floor);
            tiles.overlay(i, overlay);
        }
    }

    /** Block origins in tile order, as vanilla's {@code tile.setBlock(block)}; tiles covered by a building are skipped. */
    private void loadBlocks(World world, SaveMap map, ContentHeader.IdMapping ids, ReadDraws draws, boolean legacyFile){
        TileGrid tiles = world.tiles();
        Buildings buildings = world.buildings();
        for(int i = 0; i < tiles.size(); i++){
            if(map.parts().get(i)) continue;
            BlockDef block = world.content().block(Math.max(ids.toCurrent(ContentType.BLOCK, map.blocks()[i]), 0));
            int x = tiles.x(i), y = tiles.y(i);
            String where = block.name() + " at (" + x + ", " + y + ")";
            if(!block.hasBuilding()){
                // A chunk under a block without a building is skipped, as vanilla does.
                buildings.setBlock(x, y, block, 0, 0, Cause.SERVER);
                continue;
            }
            BuildingChunk chunk = map.buildings().get(i);
            if(chunk == null) throw new MsavException(where + " has no saved state, and only a ported kind could give it its defaults");
            create(world, ids, block, x, y, chunk, where, draws, legacyFile);
        }
    }

    private void create(World world, ContentHeader.IdMapping ids, BlockDef block, int x, int y, BuildingChunk chunk, String where,
        ReadDraws draws, boolean legacyFile){
        var in = new Reads(chunk.body());
        BuildingBase.Header base;
        try{
            base = BuildingBase.header(in);
        }catch(RuntimeException e){
            throw new MsavException(where + ": the chunk is too short for a building base", e);
        }
        // vanilla sets the block with no team, then readBase sets the building's: the grid takes it from the start
        Building building = world.buildings().setBlock(x, y, block, base.team(), base.rotation(), Cause.SERVER);
        // SaveVersion.java:349: enabled again after setBlock's checkAllowUpdate, unless the base says otherwise
        building.enabled = true;
        try{
            BuildingBase.read(building, base, in, ids, pos -> world.buildings().at(Point2.x(pos), Point2.y(pos)));
        }catch(RuntimeException e){
            throw new MsavException(where + ": " + e.getMessage(), e);
        }
        if(legacyFile){
            // LegacySaveVersion.readMap: the health as saved, uncapped, and the team through tile.setTeam, whose
            // changeTeam checks allowUpdate again unless the team is still derelict
            building.health = base.health();
            if(base.team() != VanillaIds.DERELICT) building.checkAllowUpdate();
        }
        // a legacy block's body is dropped: endLoad removes the block
        if(block.legacy()) return;
        KindKey kind = world.kinds().key(block);
        BuildingCodec codec = codecs.get(kind);
        if(codec != null && codec.reads(chunk.revision())){
            codec.read(building, in, chunk.revision(), ids);
        }else if(building instanceof PassthroughBuilding passthrough){
            byte[] body = in.b(in.remaining());
            passthrough.load(body, chunk.revision() & 0xff, world.content().hash());
            draws.building(building, body);
        }else{
            throw new MsavException(where + ": kind " + kind + " has no codec for building revision " + chunk.revision());
        }
    }

    /** Tile data, which vanilla assigns after {@code setBlock}; neither resets it. */
    private static void loadData(World world, SaveMap map){
        TileGrid tiles = world.tiles();
        map.oldData().forEach((pos, data) -> tiles.data(pos, data));
        map.data().forEach((pos, packed) -> TileDataCodec.unpack(packed, tiles, pos));
    }

    /**
     * {@code LogicBlock.accessible()} for privileged blocks while the map loads, which darkens them when false: the
     * file's own rules are the game's then, and a dedicated server neither edits nor playtests.
     */
    private boolean worldProcessorsAccessible(SaveFile save){
        try{
            Rules rules = json.read(save.meta().getOrDefault("rules", "{}"));
            return rules.editor || rules.allowEditWorldProcessors;
        }catch(RuntimeException e){
            return false;
        }
    }

    /**
     * {@code state.markers}, {@code state.mapLocales} and {@code state.stats} from the file (SaveVersion.java:160, 467).
     * A hosted map is a new game, whose stats {@code Logic.play} resets.
     */
    private void objectiveState(World world, SaveFile save, boolean hostsMap){
        ObjectiveState objectives = ObjectiveState.of(world);
        if(objectives == null) return;
        ObjectivesJson codec = json.objectives();
        objectives.locales = codec.readLocales(save.meta().getOrDefault("locales", "{}"));
        if(!hostsMap) objectives.stats = codec.readStats(save.meta().getOrDefault("stats", "{}"));
        if(save.markers() != null){
            var tiles = world.tiles();
            objectives.markers.load(codec.readMarkers(save.markers(), pos -> {
                int x = Point2.x(pos), y = Point2.y(pos);
                return tiles.inBounds(x, y) && world.buildings().at(x, y) != null;
            }));
        }
    }

    private MapState state(World world, SaveFile save, ReadDraws draws){
        var state = new MapState();
        SequencedMap<String, String> meta = save.meta();
        state.source = save.header();
        state.locales = meta.getOrDefault("locales", "{}");
        state.tags = new LinkedHashMap<>(meta);
        state.wave = parseInt(meta.get("wave"), 0);
        // Vanilla quirk: the default is the waveSpacing of the rules the game had before, which are fresh on a host.
        state.wavetime = parseFloat(meta.get("wavetime"), new Rules().waveSpacing);
        state.tick = parseFloat(meta.get("tick"), 0f);
        state.entityMapping = new ArrayList<>(save.entityMapping());
        state.plans = new ArrayList<>(save.plans());
        // the teams' rebuild plans are the world's from here on (construction, WP30)
        TeamPlansCodec.load(world, codecs, state.plans, save.header().mapping(world.content()));
        var units = new SavedUnits(world, codecs, save.header().mapping(world.content()));
        var hazards = new SavedHazards(world, new FileWireContext(world, codecs, save.header().mapping(world.content())),
            save.header().mapping(world.content()));
        IntUnaryOperator classes = classes(world, save.entityMapping());
        state.entities = entities(world, save.entities(), classes, draws, units, hazards, state.classes);
        units.carriedClasses().forEach((id, saved) -> state.classes.put(id, classes.applyAsInt(saved)));
        for(int i = 0; i < world.units().size(); i++) state.live.add(world.units().get(i).id());
        Weathers weathers = Weathers.of(world);
        if(weathers != null) for(int i = 0; i < weathers.list().size(); i++) state.live.add(weathers.list().get(i).id());
        // saved fires, puddles and launch payloads that came to life, in Groups.all
        EntityGroups groups = EntityGroups.of(world);
        if(groups != null){
            for(int i = 0; i < groups.all().size(); i++){
                Entity entity = groups.all().get(i);
                if(entity instanceof Fire || entity instanceof Puddle || entity instanceof LaunchPayload) state.live.add(entity.id());
            }
        }
        state.unknownDraws = draws.unknown() != null ? draws.unknown() : units.unknown();
        state.markers = save.markers();
        state.customChunks = customChunks(world, save.customChunks());
        return state;
    }

    /**
     * The file's custom chunks, with the static fog vanilla holds once it has loaded the file in place of the file's
     * own ({@link StaticFog}), under the file's rules, which are still the world's.
     */
    private static List<MapState.CustomChunk> customChunks(World world, List<MapState.CustomChunk> chunks){
        var out = new ArrayList<MapState.CustomChunk>(chunks.size() + 1);
        byte[] saved = null;
        for(MapState.CustomChunk chunk : chunks){
            if(chunk.name().equals(StaticFog.CHUNK)) saved = chunk.bytes();
        }
        // readCustomChunks: the file's static fog over what the WorldLoadEvent drew (FogControl.read)
        FogControl live = FogControl.of(world);
        if(live != null && saved != null) live.readChunk(saved);
        Rules rules = world.part(WorldRules.class).rules;
        byte[] fog = live == null ? StaticFog.afterLoad(world, rules, saved)
            : live.shouldWrite(rules) || saved != null ? live.writeChunk(world.tiles().width(), world.tiles().height()) : null;
        boolean placed = false;
        for(MapState.CustomChunk chunk : chunks){
            if(!chunk.name().equals(StaticFog.CHUNK)) out.add(chunk);
            else if(fog != null && !placed){
                out.add(new MapState.CustomChunk(StaticFog.CHUNK, fog));
                placed = true;
            }
        }
        if(fog != null && !placed) out.add(new MapState.CustomChunk(StaticFog.CHUNK, fog));
        return out;
    }

    /**
     * Ids as vanilla gives them: each entity draws one when it is constructed (EntityComp.java:14), which versions
     * without ids keep and others replace with the saved one (SaveVersion.java:510); duplicates are redrawn after the
     * rest. The units and buildings its read constructs for its payloads draw theirs after it. A unit comes to life
     * here ({@link SavedUnits}): constructed with that draw, given its id and added at once, or, with a duplicate id,
     * after the rest with its new one. A weather state comes to life too ({@link SavedUnits#weather}), added as it is
     * constructed (a pooled entity), so a duplicate keeps its saved id. So do fires and puddles ({@link SavedHazards}):
     * constructed (their draws and id), read, given the saved id and added, a duplicate after the rest. A chunk of a class v160.7 doesn't have is
     * skipped unread (SaveVersion.java:503), drawing nothing; its bytes stay.
     */
    private static List<EntityChunk> entities(World world, List<EntityChunk> chunks, IntUnaryOperator classes, ReadDraws draws, SavedUnits units,
                                              SavedHazards hazards, SortedMap<Integer, Integer> saved){
        var out = new ArrayList<EntityChunk>(chunks.size());
        var live = new ArrayList<Unit>(chunks.size());
        var liveHazards = new ArrayList<Entity>(chunks.size());
        var used = new TreeSet<Integer>();
        var duplicates = new ArrayList<Integer>();
        for(EntityChunk chunk : chunks){
            int classId = classes.applyAsInt(chunk.classId());
            if(classId < 0){
                out.add(chunk);
                live.add(null);
                continue;
            }
            Unit unit = SavedUnits.unit(classId) ? units.read(classId, chunk.id(), chunk.body()) : null;
            WeatherState weather = classId == EntityClasses.WEATHER_STATE ? units.weather(chunk.body()) : null;
            Entity hazard = hazards.handles(classId) ? hazards.read(classId, chunk.body()) : null;
            int drawn = unit != null ? unit.id() : weather != null ? weather.id() : hazard != null ? hazard.id() : world.ids().next();
            if(unit == null && weather == null && hazard == null) for(int i = 0; i < constructorDraws(classId); i++) world.rand().nextLong();
            boolean duplicate = false;
            if(chunk.id() == -1){
                used.add(drawn);
                out.add(new EntityChunk(chunk.classId(), drawn, chunk.body()));
            }else{
                world.ids().checkNextId(chunk.id());
                if(unit != null) unit.id(chunk.id());
                if(weather != null) units.renumber(weather, chunk.id());
                if(hazard != null) hazard.id(chunk.id());
                duplicate = !used.add(chunk.id()) && weather == null;
                if(duplicate) duplicates.add(out.size());
                out.add(chunk);
            }
            live.add(unit);
            liveHazards.add(hazard);
            if(unit == null && weather == null && hazard == null){
                if(SavedUnits.unit(classId)) draws.savedUnit(classId, chunk.body());
                else draws.entity(classId, chunk.body());
            }else if(unit != null && !duplicate){
                unit.add();
                saved.put(unit.id(), classId);
            }else if(hazard != null && !duplicate){
                hazard.add();
            }
        }
        for(int index : duplicates){
            EntityChunk chunk = out.get(index);
            int id = world.ids().next();
            out.set(index, new EntityChunk(chunk.classId(), id, chunk.body()));
            Unit unit = live.get(index);
            if(unit != null){
                unit.id(id);
                unit.add();
                saved.put(id, classes.applyAsInt(chunk.classId()));
            }
            Entity hazard = liveHazards.get(index);
            if(hazard != null){
                hazard.id(id);
                hazard.add();
            }
        }
        // Groups.unit.each(Entityc::afterReadAll): a CommandAI's unit attack target, now that every unit is in
        for(Unit unit : live){
            if(unit != null && unit.controller instanceof com.xpdustry.shard.sim.ai.CommandAi ai) ai.afterReadAll(world);
        }
        return out;
    }

    /**
     * The class each saved class id stands for ({@code readEntityMapping}): the file's entity mapping names some, by
     * class name or unit type, and v160.7's class ids the rest; -1 for none, which vanilla skips.
     */
    private static IntUnaryOperator classes(World world, List<MapState.EntityMappingEntry> mapping){
        var named = new TreeMap<Integer, Integer>();
        for(MapState.EntityMappingEntry entry : mapping) named.put(entry.id(), classNamed(world, entry.name()));
        return id -> named.containsKey(id) ? named.get(id) : EntityClasses.get(id) != null ? id : -1;
    }

    private static int classNamed(World world, String name){
        for(EntityClasses.EntityClass type : EntityClasses.ALL){
            if(type.name().equalsIgnoreCase(name)) return type.id();
        }
        for(int i = 0; i < world.content().units(); i++){
            if(world.content().unit(i).name().equals(name)) return world.content().unit(i).entityClass().classId();
        }
        return -1;
    }

    /**
     * The generator values the constructor of an entity kept as bytes draws, its field initializers: a fire's four
     * timers (FireComp.java:34), a puddle's two (PuddleComp.java:42), a weather state's wind (Weather.java:302).
     */
    private static int constructorDraws(int classId){
        EntityClasses.EntityClass type = EntityClasses.get(classId);
        if(type == null) return 0;
        return switch(type.layout()){
            case "Fire" -> 4;
            case "Puddle" -> 2;
            case "WeatherState" -> 1;
            default -> 0;
        };
    }

    /**
     * Vanilla's {@code logic.play()} on a loaded map, with the rules the game plays with already the world's ({@link
     * Play}): the loadout and heal on the cores, the weather, and the wave countdown and tick in {@code loaded}'s state.
     */
    public static void play(Loaded loaded){
        Play.Started started = Play.play(loaded.world());
        loaded.state().wavetime = started.wavetime();
        loaded.state().tick = started.tick();
    }

    /** {@code StringMap.getInt}: a malformed or missing value is the default. */
    static int parseInt(String value, int fallback){
        if(value == null) return fallback;
        try{
            return Integer.parseInt(value);
        }catch(NumberFormatException e){
            return fallback;
        }
    }

    /** {@code StringMap.getFloat}. */
    static float parseFloat(String value, float fallback){
        if(value == null) return fallback;
        try{
            return Float.parseFloat(value);
        }catch(NumberFormatException e){
            return fallback;
        }
    }
}
