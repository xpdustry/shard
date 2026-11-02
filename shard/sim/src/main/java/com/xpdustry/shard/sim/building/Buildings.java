package com.xpdustry.shard.sim.building;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.power.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;
import java.util.function.*;

/**
 * The world's dense building registry, which {@link TileGrid}'s {@code build} column indexes (ARCHITECTURE.md §3), and
 * the placement primitives: vanilla's {@code Tile.setBlock}, {@code setFloor}, {@code setOverlay} and
 * {@code Building.changeTeam}, with their multiblock linking, proximity updates and callbacks in vanilla's order.
 * Freed indexes are reused most recent first, so indexes depend only on the order of creations and removals. Its
 * order is not gameplay order: that is the update list, {@link World#builds()}.
 *
 * <p>These are mechanics, not rules: whether a placement is valid (WP30) is the caller's business. Every primitive
 * records each tile it changes in the world's {@link Journal} once per call, with the call's {@link Cause} and the
 * tile's before-image, marks it in the grid's change list, and publishes {@link TileEvent}s where vanilla fires its
 * tile events. Between {@link #beginLoad()} and {@link #endLoad(boolean)} it behaves like vanilla while
 * {@code world.isGenerating()}: no proximity, journal, events or change list, until the end rebuilds them.
 *
 * <p>Proximity order (see {@link Building}): vanilla collects neighbours into the static {@code ObjectSet}s
 * {@code Building.tmpTiles} and {@code Tile.tileSet}, keyed by identity hash codes, and iterates them in table order.
 * That order differs between JVMs, so the client and a vanilla server rarely agree on it either. Shard keeps vanilla's
 * every other rule and iterates the neighbours in first-seen edge order instead, which is what vanilla does when those
 * sets are insertion-ordered (the oracle tests check it that way).
 *
 * <p>Lifecycle, as vanilla's {@code Building.init}: a new building joins the update list if its block updates, then
 * runs {@code checkAllowUpdate} and {@link Building#created()}. {@link #kill} is {@code Call.buildDestroyed},
 * {@link #configure} is {@code Call.tileConfig}; both publish a {@link BuildingEvent} after their local effects.
 *
 * <p>The block index (vanilla's {@code BlockIndexer} for buildings: flags, active teams, damaged lists) and the chunk
 * index follow the same tile events, and {@link #targets()} answers {@link BuildingTargets} over them.
 *
 * <p>State: the registry, every building and the block index's lists are {@link Persistence#AUTHORITATIVE}, stored by
 * {@link #write}; the chunk index ({@link #eachBlock}) is {@link Persistence#DERIVED}, rebuilt by
 * {@link #rebuildDerived()}, and the state of a placement or load in progress is empty at every tick boundary, where
 * checkpoints are taken.
 *
 * <p>Owner: WP11 (placement, multiblocks, proximity) with WP20 (the building base).
 */
public final class Buildings{
    private final World world;
    private Building[] slots;
    private int high;
    private int count;
    private final IntSeq free;
    /** {@code world.tileChanges} (World.java:35-45): tile change events since the map loaded, from -1. */
    private int tileChanges = -1;
    @Derived("rebuildDerived(): buckets sorted by origin, so they depend only on which buildings stand")
    private final BuildingChunks chunks;
    /** Origins of the {@code setBlock} calls in progress: vanilla's {@code Tile.changing}. Empty between calls. */
    @Derived("empty between placements")
    private final IntSeq changing = new IntSeq();
    /** Tiles journaled by the outermost call in progress. Empty between calls. */
    @Derived("empty between placements")
    private final IntSeq journaled = new IntSeq();
    @Derived("Cause.SERVER between placements")
    private Cause cause = Cause.SERVER;
    @Derived("0 between placements")
    private int depth;
    @Derived("false: checkpoints refuse a world that is loading a map")
    private boolean loading;
    /** Vanilla's {@code BlockIndexer} for buildings: its lists are written under their own names by {@link #write}. */
    private final BlockIndex blockIndex;
    @Derived("per block id, read from the content on first use")
    private BlockInfo[] infos;
    @Derived("the world's WorldRules part, found on first use")
    private WorldRules rulesPart;
    @Derived("default rules, for worlds without a WorldRules part")
    private Rules defaultRules;
    @Derived("a view over the indexes")
    private final ChunkTargets targets;
    @Derived("the world's TileWatcher parts, found on first use")
    private TileWatcher[] watchers;
    @Derived("the world's DestroyWatcher parts, found on first use")
    private DestroyWatcher[] destroyWatchers;
    /** Registry index and team of buildings filed under a team other than theirs, read by {@link #rebuildDerived()}. */
    @Derived("empty except between read and rebuildDerived()")
    private final IntSeq restoredTeams = new IntSeq();

    /** The registry of {@code world}, whose tile grid is bound to its content's {@code BlockTable}. */
    public Buildings(World world){
        this.world = world;
        this.slots = new Building[64];
        this.free = new IntSeq();
        this.chunks = new BuildingChunks(world.tiles().width(), world.tiles().height());
        this.blockIndex = new BlockIndex(world.tiles().width() * Footprint.TILE_SIZE, world.tiles().height() * Footprint.TILE_SIZE);
        this.infos = new BlockInfo[world.content().blocks()];
        this.targets = new ChunkTargets(this);
    }

    private Buildings(Buildings source, CopyContext context){
        if(source.loading || source.depth > 0) throw new IllegalStateException("fork only between placements and loads");
        this.world = context.world();
        this.slots = new Building[source.slots.length];
        this.high = source.high;
        this.count = source.count;
        this.free = source.free.copy();
        this.tileChanges = source.tileChanges;
        this.chunks = source.chunks.copy();
        for(int i = 0; i < high; i++) slots[i] = context.map(source.slots[i]);
        this.blockIndex = source.blockIndex.copy(context);
        this.infos = source.infos;
        this.targets = new ChunkTargets(this);
    }

    public Buildings copy(CopyContext context){
        return new Buildings(this, context);
    }

    World world(){
        return world;
    }

    /**
     * {@code world.tileChanges}: how many tile change events fired since the map loaded, which starts it at -1. Only
     * changes count; a beam node recasts its beams when it moved ({@code BeamNode}).
     */
    public int tileChanges(){
        return tileChanges;
    }

    /** What the building base reads from {@code block}'s definition, made once per block. */
    public BlockInfo info(BlockDef block){
        BlockInfo info = infos[block.id()];
        if(info == null || info.block != block) info = infos[block.id()] = BlockInfo.of(block, world.content());
        return info;
    }

    /** The world's rules: its {@link WorldRules} part's, or defaults for a world without one. */
    public Rules rules(){
        if(rulesPart == null && defaultRules == null){
            for(WorldPart part : world.parts()){
                if(part instanceof WorldRules rules) rulesPart = rules;
            }
            if(rulesPart == null) defaultRules = new Rules();
        }
        return rulesPart != null ? rulesPart.rules : defaultRules;
    }

    /** The world's parts that watch building changes ({@link TileWatcher}), found once the parts exist. */
    private TileWatcher[] watchers(){
        if(watchers == null){
            List<WorldPart> parts = world.parts();
            if(parts == null) return new TileWatcher[0];
            var found = new ArrayList<TileWatcher>();
            for(WorldPart part : parts) if(part instanceof TileWatcher watcher) found.add(watcher);
            watchers = found.toArray(TileWatcher[]::new);
        }
        return watchers;
    }

    /** The world's parts that hear of destroyed buildings ({@link DestroyWatcher}), found once the parts exist. */
    private DestroyWatcher[] destroyWatchers(){
        if(destroyWatchers == null){
            List<WorldPart> parts = world.parts();
            if(parts == null) return new DestroyWatcher[0];
            var found = new ArrayList<DestroyWatcher>();
            for(WorldPart part : parts) if(part instanceof DestroyWatcher watcher) found.add(watcher);
            destroyWatchers = found.toArray(DestroyWatcher[]::new);
        }
        return destroyWatchers;
    }

    /** Vanilla's {@code BlockDestroyEvent}, from {@link Building#killed()}: every {@link DestroyWatcher} hears of it. */
    void destroying(Building building){
        if(loading) return;
        for(DestroyWatcher watcher : destroyWatchers()) watcher.destroying(building);
    }

    private void watch(int pos){
        for(TileWatcher watcher : watchers()) watcher.buildingChanged(pos);
    }

    /** The cause of the placement or call in progress: {@link Cause#SERVER} outside one. */
    public Cause cause(){
        return cause;
    }

    // placement

    /**
     * Creates a building of {@code block} with its origin at (x, y), refusing a footprint that leaves the map or
     * covers another building. Blocks with {@code update} join the update list. Otherwise it is {@link #setBlock}.
     */
    public Building create(BlockDef block, int team, int x, int y, int rotation, Cause cause){
        TileGrid tiles = world.tiles();
        int offset = Footprint.offset(block.size());
        for(int dx = 0; dx < block.size(); dx++){
            for(int dy = 0; dy < block.size(); dy++){
                int tx = x + dx + offset, ty = y + dy + offset;
                if(!tiles.inBounds(tx, ty)) throw new IllegalStateException(block.name() + " at " + x + "," + y + " leaves the map");
                if(tiles.build(tiles.pos(tx, ty)) != -1){
                    throw new IllegalStateException(block.name() + " at " + x + "," + y + " overlaps " + at(tiles.pos(tx, ty)));
                }
            }
        }
        Building building = setBlock(x, y, block, team, rotation, cause);
        if(building == null) throw new IllegalStateException(block.name() + " has no building");
        return building;
    }

    /** Removes {@code building}: its origin tile becomes air, as vanilla's {@code tile.remove()}. */
    public void remove(Building building, Cause cause){
        if(building.index() == -1 || slots[building.index()] != building){
            throw new IllegalArgumentException(building + " is not in this world's registry");
        }
        TileGrid tiles = world.tiles();
        setBlock(tiles.x(building.tile()), tiles.y(building.tile()), world.content().block(0), 0, 0, cause);
    }

    /**
     * Vanilla's {@code Tile.setBlock(type, team, rotation)}: replaces whatever is at (x, y) with {@code type}, origin
     * there. Buildings it overlaps are removed, a multiblock links the tiles of its footprint that are in the map, and
     * proximity is updated with every callback, in vanilla's order. Returns the new building at (x, y), or null when
     * {@code type} has none (air, environment blocks) or (x, y) is outside the map.
     */
    public Building setBlock(int x, int y, BlockDef type, int team, int rotation, Cause cause){
        return setBlock(x, y, type, team, rotation, null, cause);
    }

    /**
     * Vanilla's {@code Tile.setBlock(build.block, build.team, rotation, () -> build)} ({@code BuildPayload.place}): as
     * {@link #setBlock}, but the tile takes {@code payload}, a building off the map, instead of a new one. Vanilla's
     * {@code Building.init} for an initialized building runs: a powered one starts a new graph, which draws its
     * updater's id, then it takes the tile and rotation, joins the update list and runs {@link Building#recreated()}.
     * The caller runs {@link Building#dropped()} after.
     */
    public Building place(Building payload, int x, int y, int rotation, Cause cause){
        if(payload.index() != -1) throw new IllegalArgumentException(payload + " is still on the map");
        return setBlock(x, y, payload.block(), payload.team, rotation, payload, cause);
    }

    private Building setBlock(int x, int y, BlockDef type, int team, int rotation, Building payload, Cause cause){
        TileGrid tiles = world.tiles();
        if(!tiles.inBounds(x, y)) return null;
        int pos = tiles.pos(x, y);
        Cause outer = enter(cause);
        try{
            setBlockAt(pos, type, team, rotation, payload);
        }finally{
            exit(outer);
        }
        Building building = at(pos);
        return building != null && building.tile() == pos ? building : null;
    }

    /**
     * {@code block.newBuilding().create(block, team)} with {@code tile = emptyTile} ({@code new BuildPayload(block,
     * team)}, {@code Payload.read}): a building of {@code block} that stands nowhere, with vanilla's construction draws
     * (its id, its field initializers', its power graph updater's id), and Shard's {@link Building#created()}, where kinds
     * allocate what vanilla's field initializers do. Not in the registry nor the update list.
     */
    public Building createPayload(BlockDef block, int team){
        Building building = world.kinds().instantiate(block);
        building.create(world, block, team, -1, -1, 0);
        building.set(0f, 0f);
        building.created();
        return building;
    }

    /** Tile.java:240-299. */
    private void setBlockAt(int pos, BlockDef type, int team, int rotation){
        setBlockAt(pos, type, team, rotation, null);
    }

    private void setBlockAt(int pos, BlockDef type, int team, int rotation, Building payload){
        TileGrid tiles = world.tiles();
        BlockTable table = tiles.table();
        changing.add(pos);
        int id = type.id();
        if(table.forceTeam(id) != BlockTable.NONE) team = table.forceTeam(id);
        preChanged(pos);
        journal(pos);
        tiles.block(pos, id);
        changeBuild(pos, type, team, Mathf.mod(rotation, 4), payload);
        Building entity = at(pos);
        if(entity != null) entity.team = team;
        if(type.size() > 1){
            int x = tiles.x(pos), y = tiles.y(pos), size = type.size(), offset = Footprint.offset(size);
            // two passes: the first clears whatever is there, the second links the tiles to this block
            for(int pass = 0; pass < 2; pass++){
                for(int dx = 0; dx < size; dx++){
                    for(int dy = 0; dy < size; dy++){
                        int wx = dx + offset + x, wy = dy + offset + y;
                        if((wx == x && wy == y) || !tiles.inBounds(wx, wy)) continue;
                        int other = tiles.pos(wx, wy);
                        if(pass == 0){
                            setBlockAt(other, world.content().block(0), 0, 0);
                        }else{
                            link(other, id, entity);
                        }
                    }
                }
            }
            link(pos, id, entity);
        }
        changed(pos);
        changing.removeValue(pos);
        blockChanged(pos, type);
    }

    /**
     * {@code Block.blockChanged(tile)}, after every {@code setBlock}, loading included. Of vanilla's overrides only
     * {@code ColoredWall}'s acts on a server: a new colored wall without a colour gets its default.
     */
    private void blockChanged(int pos, BlockDef type){
        if(type.baked() && type.isA(COLORED_WALL)) defaultColor(pos, type);
    }

    /** {@code Floor.floorChanged(tile)}, after {@code setFloor} changes it: {@code ColoredFloor}'s, as {@link #blockChanged}. */
    private void floorChanged(int pos, BlockDef floor){
        if(floor.baked() && floor.isA(COLORED_FLOOR)) defaultColor(pos, floor);
    }

    private void defaultColor(int pos, BlockDef block){
        TileGrid tiles = world.tiles();
        if(tiles.extraData(pos) == 0) tiles.extraData(pos, rgba(block.fields().s("defaultColor")));
    }

    /** {@code Color.rgba()} of a {@code #rrggbbaa} colour. */
    private static int rgba(String color){
        String hex = color.startsWith("#") ? color.substring(1) : color;
        if(hex.length() == 6) hex += "ff";
        return (int)Long.parseLong(hex, 16);
    }

    private static final KindKey COLORED_WALL = KindKey.of("colored-wall"), COLORED_FLOOR = KindKey.of("colored-floor");

    private void link(int pos, int block, Building building){
        TileGrid tiles = world.tiles();
        journal(pos);
        tiles.block(pos, block);
        tiles.build(pos, building == null ? -1 : building.index());
        tiles.team(pos, building == null ? 0 : building.team);
        tiles.buildSolid(pos, building != null && building.checkSolid());
    }

    /** Tile.java:609-643. */
    private void preChanged(int pos){
        TileGrid tiles = world.tiles();
        firePreChanged(pos);
        Building building = at(pos);
        if(building == null) return;
        // only the tile being set calls onRemoved, once, even for a multiblock
        building.onRemoved();
        removeFromProximity(building);
        if(building.block().size() > 1){
            int cx = tiles.x(building.tile()), cy = tiles.y(building.tile()), size = building.block().size();
            int offset = Footprint.offset(size);
            for(int dx = 0; dx < size; dx++){
                for(int dy = 0; dy < size; dy++){
                    int wx = cx + dx + offset, wy = cy + dy + offset;
                    if(!tiles.inBounds(wx, wy)) continue;
                    int other = tiles.pos(wx, wy);
                    if(other == pos) continue;
                    // the linked tiles are reset by hand, so their own preChanged never runs
                    firePreChanged(other);
                    link(other, 0, null);
                    fireChanged(other);
                }
            }
        }
    }

    /** Tile.java:645-670; {@code payload} is the building {@code entityprov} gives, or null for a new one. */
    private void changeBuild(int pos, BlockDef type, int team, int rotation, Building payload){
        TileGrid tiles = world.tiles();
        Building previous = at(pos);
        if(previous != null){
            int size = previous.block().size();
            previous.remove();
            journal(pos);
            tiles.build(pos, -1);
            tiles.team(pos, 0);
            tiles.buildSolid(pos, false);
            unregister(previous);
            // vanilla quirk: the edges are taken around this tile, which is not the old building's origin when a
            // linked tile is replaced
            for(Building other : edgeBuildings(tiles.x(pos), tiles.y(pos), size, -1)) updateProximity(other);
        }
        if(tiles.table().hasBuilding(type.id())){
            int index = free.isEmpty() ? high++ : free.pop();
            if(index == slots.length) slots = Arrays.copyOf(slots, slots.length * 2);
            Building building;
            if(payload != null){
                building = payload;
                building.rebound(pos, index, rotation);
            }else{
                building = world.kinds().instantiate(type);
                building.create(world, type, team, pos, index, rotation);
            }
            slots[index] = building;
            count++;
            journal(pos);
            tiles.build(pos, index);
            tiles.team(pos, team);
            tiles.buildSolid(pos, building.checkSolid());
            // Building.init: join the update list, then checkAllowUpdate() and created()
            if(type.update()) building.add();
            building.checkAllowUpdate();
            if(payload != null) building.recreated();
            else building.created();
        }
    }

    /** Tile.java:672-693. */
    private void changed(int pos){
        TileGrid tiles = world.tiles();
        if(!loading){
            Building building = at(pos);
            if(building != null){
                updateProximity(building);
            }else{
                // no building to update proximity for us: tell the neighbours directly
                int x = tiles.x(pos), y = tiles.y(pos);
                for(int d = 0; d < 4; d++){
                    int nx = x + Geometry.d4x(d), ny = y + Geometry.d4y(d);
                    if(!tiles.inBounds(nx, ny)) continue;
                    Building other = at(tiles.pos(nx, ny));
                    if(other != null && !changing.contains(other.tile())) other.onProximityUpdate();
                }
            }
        }
        fireChanged(pos);
    }

    /** {@code TilePreChangeEvent}: the indexer drops the building whose origin this is. */
    private void firePreChanged(int pos){
        if(loading) return;
        Building building = at(pos);
        if(building != null && building.tile() == pos) unindex(building);
        for(TileWatcher watcher : watchers()) watcher.tileChanging(world, pos);
    }

    /** {@code TileChangeEvent}: the indexer takes the building whose origin this is; observers hear of it. */
    private void fireChanged(int pos){
        if(loading) return;
        tileChanges++;
        watch(pos);
        TileGrid tiles = world.tiles();
        Building building = at(pos);
        if(building != null && building.tile() == pos) index(building);
        for(TileWatcher watcher : watchers()) watcher.tileChanged(world, pos);
        world.publish(new TileEvent.Changed(world.ticks(), pos, tiles.block(pos), tiles.team(pos), cause));
    }

    private void unregister(Building building){
        unindex(building);
        slots[building.index()] = null;
        free.add(building.index());
        count--;
        building.unbound();
    }

    /** {@code BlockIndexer.process}: files the building under its team, unless it is already filed. */
    private void index(Building building){
        if(chunks.indexedTeam(building) != -1) return;
        TileGrid tiles = world.tiles();
        chunks.add(building, tiles.x(building.tile()), tiles.y(building.tile()));
        blockIndex.add(building, building.team);
    }

    /** {@code BlockIndexer.removeIndex}: drops the building from the team it is filed under. */
    private void unindex(Building building){
        int team = chunks.indexedTeam(building);
        if(team == -1) return;
        TileGrid tiles = world.tiles();
        chunks.remove(building, tiles.x(building.tile()), tiles.y(building.tile()));
        blockIndex.remove(building, team);
    }

    /** Vanilla's {@code Tile.setFloor}: the building on the tile, if any, hears of it. */
    public void setFloor(int x, int y, BlockDef floor, Cause cause){
        TileGrid tiles = world.tiles();
        int pos = tiles.pos(x, y), previous = tiles.floor(pos);
        if(previous == floor.id()) return;
        Cause outer = enter(cause);
        try{
            journal(pos);
            tiles.floor(pos, floor.id());
            Building building = at(pos);
            if(building != null) building.onProximityUpdate();
            if(!loading) world.publish(new TileEvent.FloorChanged(world.ticks(), pos, previous, floor.id(), cause));
            floorChanged(pos, floor);
        }finally{
            exit(outer);
        }
    }

    /** Vanilla's {@code Tile.setOverlay}. */
    public void setOverlay(int x, int y, BlockDef overlay, Cause cause){
        TileGrid tiles = world.tiles();
        int pos = tiles.pos(x, y), previous = tiles.overlay(pos);
        if(previous == overlay.id()) return;
        Cause outer = enter(cause);
        try{
            journal(pos);
            tiles.overlay(pos, overlay.id());
            if(!loading){
                world.publish(new TileEvent.OverlayChanged(world.ticks(), pos, previous, overlay.id(), cause));
                Building building = at(pos);
                if(building != null) building.onProximityUpdate();
            }
        }finally{
            exit(outer);
        }
    }

    /**
     * Vanilla's {@code Building.changeTeam(next)}: re-indexes the building, rewrites its tiles' team, updates its
     * proximity and runs {@code checkAllowUpdate}. Always change a standing building's team here, never through the
     * {@code team} field. A building that is not {@link Building#isValid()} (dead, still standing) keeps its index
     * entries under its old team, as vanilla. A powered building reflows its power graph before its proximity is
     * rebuilt ({@link PowerGraph#teamChanged}).
     */
    public void changeTeam(Building building, int team, Cause cause){
        changeTeam(building, team, cause, true);
    }

    /**
     * {@code changeTeam(next, updatePower)} (BuildingComp.java:1796): as {@link #changeTeam(Building, int, Cause)},
     * but without {@code updatePower} the power graph is left as it was, for a caller that reflows it afterwards
     * ({@code Tile.setTeams}).
     */
    public void changeTeam(Building building, int team, Cause cause, boolean updatePower){
        if(building.team == team) return;
        BlockTable table = world.tiles().table();
        // BuildingComp.java:1798 overwrites the team with forceTeam before reading the old one: kept as is
        if(table.forceTeam(building.block().id()) != BlockTable.NONE) building.team = table.forceTeam(building.block().id());
        int last = building.team;
        if(last == team) return;
        boolean was = building.isValid();
        Cause outer = enter(cause);
        try{
            if(was && !loading) unindex(building);
            building.team = team;
            TileGrid tiles = world.tiles();
            if(building.index() != -1){
                Footprint.each(tiles, tiles.x(building.tile()), tiles.y(building.tile()), building.block().size(), pos -> {
                    if(tiles.build(pos) == building.index()){
                        journal(pos);
                        tiles.team(pos, team);
                    }
                });
            }
            if(building.power != null && updatePower) PowerGraph.teamChanged(building);
            if(was){
                if(!loading) index(building);
                if(!loading) world.publish(new TileEvent.TeamChanged(world.ticks(), building.tile(), last, team, cause));
                updateProximity(building);
            }
            if(!loading && building.index() != -1){
                Footprint.each(tiles, tiles.x(building.tile()), tiles.y(building.tile()), building.block().size(), pos -> {
                    if(tiles.build(pos) == building.index()) watch(pos);
                });
            }
            building.checkAllowUpdate();
        }finally{
            exit(outer);
        }
    }

    /** A kind whose {@link Building#checkSolid()} answer changed calls this, so the grid's solidity follows. */
    public void solidityChanged(Building building){
        TileGrid tiles = world.tiles();
        boolean solid = building.checkSolid();
        Footprint.each(tiles, tiles.x(building.tile()), tiles.y(building.tile()), building.block().size(), pos -> {
            if(tiles.build(pos) == building.index()) tiles.buildSolid(pos, solid);
        });
    }

    // proximity

    /** BuildingComp.java:1886-1913. */
    private void updateProximity(Building building){
        TileGrid tiles = world.tiles();
        building.proximityClear();
        Building[] near = edgeBuildings(tiles.x(building.tile()), tiles.y(building.tile()), building.block().size(), building.team);
        for(Building other : near) other.proximityAddUnique(building);
        for(Building other : near) building.proximityAdd(other);
        building.onProximityAdded();
        building.onProximityUpdate();
        for(Building other : near) other.onProximityUpdate();
    }

    /** BuildingComp.java:1866-1884: every neighbour hears of it, whatever its team. */
    private void removeFromProximity(Building building){
        TileGrid tiles = world.tiles();
        building.onProximityRemoved();
        for(Building other : edgeBuildings(tiles.x(building.tile()), tiles.y(building.tile()), building.block().size(), -1)){
            other.proximityRemove(building);
            other.onProximityUpdate();
        }
        building.proximityClear();
        building.afterProximityRemoved();
    }

    /**
     * The distinct buildings on the edges of a {@code size} footprint at (x, y), in first-seen edge order; only those
     * of {@code team} unless it is -1. Vanilla's same-team check comes before its set add, so the filter is the same.
     */
    private Building[] edgeBuildings(int x, int y, int size, int team){
        TileGrid tiles = world.tiles();
        int edges = Footprint.edgeCount(size), found = 0;
        var out = new Building[edges];
        outer:
        for(int i = 0; i < edges; i++){
            int ex = x + Footprint.edgeX(size, i), ey = y + Footprint.edgeY(size, i);
            if(!tiles.inBounds(ex, ey)) continue;
            Building other = at(tiles.pos(ex, ey));
            if(other == null || (team != -1 && other.team != team)) continue;
            for(int j = 0; j < found; j++){
                if(out[j] == other) continue outer;
            }
            out[found++] = other;
        }
        return found == edges ? out : Arrays.copyOf(out, found);
    }

    // map loading

    /**
     * Vanilla's {@code world.beginMapLoad()}: until {@link #endLoad}, placements skip proximity, the journal, events
     * and the change list, as vanilla's do while the world is generating. A loader sets columns and calls
     * {@link #setBlock} for each building's origin.
     */
    public void beginLoad(){
        if(depth > 0) throw new IllegalStateException("load inside a placement");
        loading = true;
        world.tiles().recording(false);
    }

    public boolean loading(){
        return loading;
    }

    /**
     * Vanilla's {@code world.endMapLoad()} and its {@code WorldLoadEvent}s. In tile order, a {@code LegacyBlock} removes
     * itself (a legacy unit factory becomes its replacement, at the tile's team and the building's rotation), and every
     * other tile with a building updates its building's proximity (so a multiblock does it once per tile it covers, as
     * vanilla); static walls get their darkness ({@link TileGrid#addDarkness}) and the derived state is rebuilt. Then
     * the {@code WorldLoadEvent} handlers: the indexer files every building in tile order, and every building in the
     * update list runs {@code checkAllowUpdate} (the others keep the file's {@code enabled}), and the
     * {@link #tileChanges()} count restarts. The change list starts empty.
     *
     * @param worldProcessorsAccessible vanilla's {@code LogicBlock.accessible()} for privileged blocks: the editor,
     *     a playtest or {@code rules.allowEditWorldProcessors}
     */
    public void endLoad(boolean worldProcessorsAccessible){
        if(!loading) throw new IllegalStateException("endLoad without beginLoad");
        TileGrid tiles = world.tiles();
        for(int pos = 0; pos < tiles.size(); pos++){
            BlockDef block = world.content().block(tiles.block(pos));
            if(block.legacy()){
                removeLegacy(pos, block);
                continue;
            }
            if(tiles.build(pos) != -1) updateProximity(slots[tiles.build(pos)]);
        }
        tiles.addDarkness(worldProcessorsAccessible);
        rebuildDerived();
        blockIndex.clear();
        for(int pos = 0; pos < tiles.size(); pos++){
            Building building = at(pos);
            if(building != null && building.tile() == pos) blockIndex.add(building, building.team);
        }
        var builds = world.builds();
        for(int i = 0; i < builds.size(); i++) builds.get(i).checkAllowUpdate();
        tiles.clearChanges();
        tiles.recording(true);
        // World's WorldLoadEvent handler
        tileChanges = -1;
        loading = false;
        for(TileWatcher watcher : watchers()) watcher.loaded(world);
    }

    /** {@code LegacyBlock.removeSelf(tile)} and {@code LegacyUnitFactory}'s, while the map is still loading. */
    private void removeLegacy(int pos, BlockDef block){
        TileGrid tiles = world.tiles();
        BlockDef replacement = world.content().block(0);
        if(block.isA(KindKey.of("legacy-unit-factory"))){
            String name = block.fields().s("replacement");
            int id = name == null ? -1 : world.content().id(ContentType.BLOCK, name);
            if(id > 0) replacement = world.content().block(id);
        }
        Building building = at(pos);
        int team = building == null ? VanillaIds.DERELICT : building.team;
        int rotation = building == null ? 0 : building.rotation;
        // Tile.remove() is setBlock(air) with the tile's team; a replacement takes the tile's team and rotation
        if(replacement.id() == 0){
            setBlock(tiles.x(pos), tiles.y(pos), replacement, 0, 0, Cause.SERVER);
        }else{
            setBlock(tiles.x(pos), tiles.y(pos), replacement, team, rotation, Cause.SERVER);
        }
    }

    // derived state

    /**
     * Rebuilds the derived state from the authoritative state: the grid's {@code buildSolid} from each building's
     * {@link Building#checkSolid()}, its predicate bitsets, and the chunk index. A checkpoint restore calls this;
     * it reproduces a live world's derived state exactly.
     */
    public void rebuildDerived(){
        TileGrid tiles = world.tiles();
        boolean recording = tiles.recording();
        tiles.recording(false);
        for(int pos = 0; pos < tiles.size(); pos++){
            Building building = at(pos);
            tiles.buildSolid(pos, building != null && building.checkSolid());
        }
        tiles.rebuildDerived();
        tiles.recording(recording);
        chunks.rebuild(this, restoredTeams);
        restoredTeams.clear();
        blockIndex.rebuildTurrets(this);
    }

    // queries

    /** The building at registry index {@code index}, or null. */
    public Building get(int index){
        return index >= 0 && index < high ? slots[index] : null;
    }

    /** The building covering tile {@code pos}, or null. */
    public Building at(int pos){
        int index = world.tiles().build(pos);
        return index == -1 ? null : slots[index];
    }

    /** The building covering (x, y), or null, also outside the map ({@code world.build(x, y)}). */
    public Building at(int x, int y){
        TileGrid tiles = world.tiles();
        return tiles.inBounds(x, y) ? at(tiles.pos(x, y)) : null;
    }

    /** One past the highest index ever used: every live index is below it. */
    public int indexLimit(){
        return high;
    }

    /** Live buildings. */
    public int count(){
        return count;
    }

    /** Every live building, in registry index order. */
    public void each(Consumer<? super Building> action){
        for(int i = 0; i < high; i++){
            if(slots[i] != null) action.accept(slots[i]);
        }
    }

    /**
     * Vanilla's {@code indexer.eachBlock(team, wx, wy, range, pred, cons)} without the predicate: every building of
     * {@code team} whose centre is within {@code range + size * 4} world units of (wx, wy), privileged blocks
     * excluded. Order: chunk rows, then position (vanilla's is its quadtree's). Returns whether any was visited.
     */
    public boolean eachBlock(int team, float wx, float wy, float range, Consumer<? super Building> action){
        TileGrid tiles = world.tiles();
        float ext = range + chunks.maxSize(team) * Footprint.TILE_SIZE;
        int x0 = (int)Math.floor((wx - ext) / Footprint.TILE_SIZE), y0 = (int)Math.floor((wy - ext) / Footprint.TILE_SIZE);
        int x1 = (int)Math.floor((wx + ext) / Footprint.TILE_SIZE), y1 = (int)Math.floor((wy + ext) / Footprint.TILE_SIZE);
        boolean[] any = {false};
        chunks.each(team, x0, y0, x1, y1, index -> {
            Building building = slots[index];
            int size = building.block().size();
            float bx = tiles.x(building.tile()) * Footprint.TILE_SIZE + Footprint.worldOffset(size);
            float by = tiles.y(building.tile()) * Footprint.TILE_SIZE + Footprint.worldOffset(size);
            if(Mathf.within(bx, by, wx, wy, range + size * Footprint.TILE_SIZE / 2f) && !tiles.table().privileged(building.block().id())){
                any[0] = true;
                action.accept(building);
            }
        });
        return any[0];
    }

    /**
     * Every building of {@code team} whose origin is in a chunk that overlaps tiles {@code x0..x1, y0..y1}
     * (inclusive): a coarse visit for callers that do their own exact test.
     */
    public void eachInChunks(int team, int x0, int y0, int x1, int y1, Consumer<? super Building> action){
        chunks.each(team, x0, y0, x1, y1, index -> action.accept(slots[index]));
    }

    // lifecycle

    /**
     * {@code Call.buildDestroyed(building)}: {@link Building#killed()} runs (dead, {@code onDestroyed}, the tile
     * becomes air, out of the update list, {@code afterDestroyed}), then {@link BuildingEvent.Destroyed} is published,
     * as the Call is sent after it runs locally. A building no longer on its tiles is not killed again.
     */
    public void kill(Building building, Cause cause){
        if(building == null || building.index() == -1 || slots[building.index()] != building) return;
        int pos = building.tile(), block = building.block().id(), team = building.team;
        Cause outer = enter(cause);
        try{
            building.killed();
        }finally{
            exit(outer);
        }
        if(!loading) world.publish(new BuildingEvent.Destroyed(world.ticks(), pos, block, team, cause));
    }

    /** {@link #kill(Building, Cause)} with the cause in progress. */
    public void kill(Building building){
        kill(building, cause);
    }

    /**
     * {@code Call.tileConfig(player, building, value)} (InputHandler.java:718) once the server allowed it:
     * {@link Building#configured} with {@code builder}, the player's unit or null, then
     * {@link BuildingEvent.Configured}, which the sync layer sends to every player. Permission checks are the caller's.
     */
    public void configure(Building building, com.xpdustry.shard.sim.unit.Unit builder, Object value, Cause cause){
        configure(building, builder, value, cause, false);
    }

    /**
     * {@link #configure} for a client's own {@code tileConfig}, which its client already ran ({@code called = Loc.both}):
     * the caller sends vanilla's forward to the other players, so the event is marked
     * {@link BuildingEvent.Configured#forwarded} and no Call is made of it.
     */
    public void configureForwarded(Building building, com.xpdustry.shard.sim.unit.Unit builder, Object value, Cause cause){
        configure(building, builder, value, cause, true);
    }

    private void configure(Building building, com.xpdustry.shard.sim.unit.Unit builder, Object value, Cause cause, boolean forwarded){
        if(building == null) return;
        Cause outer = enter(cause);
        try{
            building.configured(builder, value);
        }finally{
            exit(outer);
        }
        if(!loading) world.publish(new BuildingEvent.Configured(world.ticks(), building.tile(), building.block().id(), value, cause, forwarded));
    }

    /**
     * {@code rotateBlock}'s local effect (InputHandler.java:708-711): the new rotation, then {@code updateProximity()},
     * which a conveyor finds its next building in, and {@code noSleep()}. The caller publishes the event its Call
     * becomes. The journal keeps no building state yet (WP32), so a rotation isn't journaled.
     */
    public void rotate(Building building, int rotation, Cause cause){
        Cause outer = enter(cause);
        try{
            building.rotation = rotation;
            updateProximity(building);
            building.noSleep();
        }finally{
            exit(outer);
        }
    }

    /**
     * {@code indexer.notifyHealthChanged(building)} alone: the damaged index follows a health set by hand, with no
     * {@code buildHealthUpdate} (construction sets a finished block's health this way).
     */
    public void notifyHealthChanged(Building building){
        blockIndex.healthChanged(building);
    }

    /** {@code world.tileChanges++}: a change no tile event reports, such as a derelict repair. */
    public void countTileChange(){
        tileChanges++;
    }

    /** {@code updateProximity()} on its own, as a derelict repair calls it: no sleep change, no event. */
    public void updateProximity(Building building, Cause cause){
        Cause outer = enter(cause);
        try{
            updateProximity(building);
        }finally{
            exit(outer);
        }
    }

    /** {@code healthChanged()}: the damaged index follows, and the sync layer hears of it. */
    void healthChanged(Building building){
        blockIndex.healthChanged(building);
        if(!loading) world.publish(new BuildingEvent.HealthChanged(world.ticks(), building.tile(), building.health));
    }

    /**
     * {@code allowUpdate()} (BuildingComp.java:628): not derelict, supported by the world's environment, and inside a
     * limited map area unless privileged ({@code Rect.contains} is inclusive).
     */
    boolean allowUpdate(Building building){
        BlockDef block = building.block();
        Rules rules = rules();
        if(building.team == VanillaIds.DERELICT) return false;
        if(!block.baked()) return true;
        int x = building.tileX(), y = building.tileY();
        return block.supportsEnv(rules.env) && (block.privileged() || !rules.limitMapArea || !rules.disableOutsideArea
            || (rules.limitX <= x && rules.limitX + rules.limitWidth >= x && rules.limitY <= y && rules.limitY + rules.limitHeight >= y));
    }

    /**
     * {@code World.checkMapArea(x, y, w, h)}, after the rules' map area changed from (x, y, w, h): disabled buildings
     * outside the old area that reset automatically are enabled again, then every building runs
     * {@code checkAllowUpdate} against the new area.
     */
    public void checkMapArea(int x, int y, int w, int h){
        for(int i = 0; i < high; i++){
            Building building = slots[i];
            if(building == null || !building.isValid()) continue;
            int bx = building.tileX(), by = building.tileY();
            if(!building.enabled && autoResetEnabled(building.block()) && !(x <= bx && x + w >= bx && y <= by && y + h >= by)){
                building.enabled = true;
            }
            building.checkAllowUpdate();
        }
    }

    private static boolean autoResetEnabled(BlockDef block){
        return !block.baked() || !block.fields().has("autoResetEnabled") || block.fields().b("autoResetEnabled");
    }

    // block index

    /** {@code indexer.getFlagged(team, flag)}, in vanilla's order. */
    public List<Building> flagged(int team, BlockFlag flag){
        return blockIndex.flagged(team, flag);
    }

    /** {@code indexer.getEnemy(team, flag)}: the other teams' flagged buildings, teams in id order. */
    public List<Building> enemy(int team, BlockFlag flag){
        return blockIndex.enemy(team, flag, rules().coreCapture);
    }

    /** {@code team.data().buildingTree}: the team's filed buildings by footprint, or null before its first one. */
    public BuildingTree tree(int team){
        return blockIndex.tree(team);
    }

    /** {@code team.data().buildings.size}, without a copy. */
    public int teamBuildingCount(int team){
        return blockIndex.teamBuildingCount(team);
    }

    /** {@code indexer.getFlagged(team, flag).size}, without a copy; entries through {@link #flaggedAt}. */
    public int flaggedCount(int team, BlockFlag flag){
        return blockIndex.flaggedCount(team, flag);
    }

    /** Entry {@code index} of {@code indexer.getFlagged(team, flag)}, below {@link #flaggedCount}. */
    public Building flaggedAt(int team, BlockFlag flag, int index){
        return blockIndex.flaggedAt(team, flag, index);
    }

    /** {@code team.data().buildings}: the team's buildings in vanilla's order ({@code fetch build}). */
    public List<Building> teamBuildings(int team){
        return blockIndex.teamBuildings(team);
    }

    /** {@code team.data().getBuildings(block)}: the team's buildings of one block, in vanilla's order. */
    public List<Building> teamBuildings(int team, BlockDef block){
        return blockIndex.teamBuildings(team, block.id());
    }

    /** Hashes {@link #teamBuildings} lists, for the part that hashes them ({@code sim.logic.LogicUnits}). */
    public void hashTeamLists(Hasher hasher){
        blockIndex.hashTeamLists(hasher);
    }

    /** {@code indexer.getDamaged(team)}: drops healed buildings first, as vanilla. */
    public List<Building> damaged(int team){
        return blockIndex.damaged(team);
    }

    /** {@code indexer.activeTeams}: the teams that had a building since the map loaded, in order. */
    public IntSeq activeTeams(){
        return blockIndex.activeTeams().copy();
    }

    /** The indexer's targeting queries ({@link BuildingTargets#of} finds them). */
    public BuildingTargets targets(){
        return targets;
    }

    /** {@link #activeTeams()} without the copy, for per-frame searches: read it, never change it. */
    public IntSeq activeTeamsView(){
        return blockIndex.activeTeams();
    }

    /** {@code building.isDiscovered(viewer)} (BuildingComp.java:315), as the indexer's targeting reads it. */
    public boolean isDiscovered(Building building, int viewer){
        return targets.isDiscovered(building, viewer);
    }

    boolean anyTurret(int team, java.util.function.Predicate<Building> test){
        return blockIndex.anyTurret(team, test);
    }

    /** The registry index's team in the chunk index, or -1 when it isn't filed. */
    int indexedTeam(Building building){
        return chunks.indexedTeam(building);
    }


    // journal

    private Cause enter(Cause cause){
        Cause outer = this.cause;
        depth++;
        this.cause = Objects.requireNonNull(cause, "cause");
        return outer;
    }

    private void exit(Cause outer){
        cause = outer;
        if(--depth == 0) journaled.clear();
    }

    /** Records the tile's before-image the first time the outermost call in progress changes it. */
    private void journal(int pos){
        if(loading || journaled.contains(pos)) return;
        journaled.add(pos);
        world.journal().record(new Journal.Entry(world.ticks(), cause, world.tiles().image(pos)));
    }

    // checkpoints

    /**
     * Writes the registry into a checkpoint's {@code #buildings} section: its slots as references, in index order, and
     * its free list. The buildings themselves are checkpoint objects. Refused during a placement or a map load.
     */
    public void write(CheckpointOut out){
        if(loading || depth > 0) throw new IllegalStateException("a checkpoint is taken between placements and loads");
        out.implicit("world").i("capacity", slots.length).refs("slots", slots, high).i("high", high).i("count", count)
            .intSeq("free", free).i("tileChanges", tileChanges);
        // the block index's lists follow under their own names
        out.implicit("blockIndex");
        blockIndex.write(out);
        // a dead building still standing after a team change stays filed under its old team (vanilla): kept, so the
        // chunk rebuild files it there too
        var stale = new IntSeq();
        for(int i = 0; i < high; i++){
            Building building = slots[i];
            if(building == null) continue;
            int team = chunks.indexedTeam(building);
            if(team != -1 && team != building.team){
                stale.add(i);
                stale.add(team);
            }
        }
        out.intSeq("staleTeams", stale);
    }

    /** Reads what {@link #write} stored into this empty registry. {@link #rebuildDerived()} then rebuilds the index. */
    public void read(CheckpointIn in){
        if(high != 0) throw new IllegalStateException("only an empty registry is restored");
        in.implicit("world");
        int capacity = in.i("capacity");
        Building[] stored = in.refs("slots", Building.class);
        int high = in.i("high"), count = in.i("count");
        if(stored == null || stored.length != high || high > capacity) throw new CheckpointException("the building registry's slots are inconsistent");
        int live = 0;
        for(int i = 0; i < high; i++){
            if(stored[i] == null) continue;
            if(stored[i].index() != i) throw new CheckpointException(stored[i] + " is in slot " + i + " but remembers " + stored[i].index());
            live++;
        }
        if(live != count) throw new CheckpointException("the registry counts " + count + " buildings but holds " + live);
        slots = Arrays.copyOf(stored, capacity);
        this.high = high;
        this.count = count;
        in.intSeq("free", free);
        tileChanges = in.i("tileChanges");
        in.implicit("blockIndex");
        blockIndex.read(in);
        in.intSeq("staleTeams", restoredTeams);
    }

    public void hash(Hasher hasher){
        hasher.i(high).i(count).ints(free.items, 0, free.size).i(tileChanges);
        for(int i = 0; i < high; i++){
            Building building = slots[i];
            hasher.bool(building != null);
            if(building != null) building.hashState(hasher);
        }
        chunks.hash(hasher);
        blockIndex.hash(hasher);
    }
}
