package com.xpdustry.shard.sim.tile;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * The tile grid as flat primitive columns of {@code width × height} (ARCHITECTURE.md §3 "Tables"), vanilla's
 * {@code Tiles} and the state of its {@code Tile}s. Inside Shard a tile is {@code pos = y * width + x}; on the wire it
 * is {@code Point2.pack(x, y)}. {@link Tile} is the object-style view, and {@code Buildings} owns the placement
 * primitives that change blocks with vanilla's callbacks, journal entries and events. The setters here are raw: they
 * keep the derived bitsets and the change list, nothing else.
 *
 * <p>Columns and how checkpoints treat them ({@link Persistence}):
 * <ul>
 *   <li>authoritative: {@code floor}, {@code overlay}, {@code block} (content wire ids, 0 is air), {@code team} (the
 *   building's team on every tile it covers, else 0, derelict), {@code build} (the building registry index on every
 *   tile of a building, else -1), vanilla's per-tile {@code data}, {@code floorData}, {@code overlayData} and
 *   {@code extraData}, the pending change list (see "Changes" below), which is stale on purpose between ticks, and
 *   whether changes are recorded;</li>
 *   <li>derived ({@link Derived}), rebuilt by {@link #rebuildDerived()}: the predicate bitsets {@code solid},
 *   {@code legSolid}, {@code static}, {@code deep} and {@code liquid}. {@code buildSolid}, the tiles whose building's
 *   {@code checkSolid()} is true, is derived from the buildings and rebuilt by {@code Buildings}.</li>
 * </ul>
 * The {@link BlockTable} is the pinned content's, shared and immutable. Both kinds of state are copied and hashed;
 * {@link #write} and {@link #read} are the checkpoint's {@code #tiles} section.
 *
 * <p><b>Changes.</b> Every setter that changes a column records the tile once, with its {@link TileState} from before,
 * in the <i>pending</i> list. {@link #beginTick()} turns the pending list into <i>the tick's</i> list, which
 * {@link #changes} hands to any number of readers in any phase, and {@link #endTick()} clears it: so each tick's
 * readers see exactly the changes made since the previous tick began, whichever phase made them, and none twice.
 *
 * <p>Owner: WP11 (tiles, placement), with WP27 for the environment predicates.
 */
public final class TileGrid implements TileSolidity{
    /** {@code Vars.darkRadius}: how far static wall darkness reaches into a wall mass. */
    public static final int DARK_RADIUS = 4;
    /** {@code ColoredWall.flagApplyDarkness}. */
    public static final int COLORED_WALL_DARK = 2;

    private final int width, height;
    private final short[] floor, overlay, block;
    private final byte[] team;
    private final int[] build;
    private final byte[] data, floorData, overlayData;
    private final int[] extraData;
    @Derived("rebuildDerived(): the block table's predicates of the columns and buildSolid")
    private final long[] solid, legSolid, statics, deep, liquid;
    @Derived("Buildings.rebuildDerived(): each building's checkSolid()")
    private final long[] buildSolid;
    @Derived("read(): the positions in changedPos")
    private final long[] marked;
    private int[] changedPos;
    private long[] changedBefore;
    private int changes;
    @Derived("empty between ticks: endTick() clears the tick's changes")
    private int[] tickPos;
    @Derived("empty between ticks: endTick() clears the tick's changes")
    private long[] tickBefore;
    @Derived("empty between ticks: endTick() clears the tick's changes")
    private int tickChanges;
    private boolean recording = true;
    @Derived("the content's, which World binds when it makes the grid")
    private BlockTable table;
    /** Moves with every change to what {@link #hash} reads, so {@code World.hash} can reuse the tiles' last hash. */
    @Derived("a change counter, only ever compared with its own earlier values")
    private long version;

    public TileGrid(int width, int height){
        if(width <= 0 || height <= 0 || (long)width * height > Integer.MAX_VALUE){
            throw new IllegalArgumentException("bad grid size " + width + "×" + height);
        }
        this.width = width;
        this.height = height;
        int n = width * height, words = (n + 63) >>> 6;
        floor = new short[n];
        overlay = new short[n];
        block = new short[n];
        team = new byte[n];
        build = new int[n];
        Arrays.fill(build, -1);
        data = new byte[n];
        floorData = new byte[n];
        overlayData = new byte[n];
        extraData = new int[n];
        solid = new long[words];
        legSolid = new long[words];
        statics = new long[words];
        deep = new long[words];
        liquid = new long[words];
        buildSolid = new long[words];
        marked = new long[words];
        changedPos = new int[16];
        changedBefore = new long[16];
        tickPos = new int[16];
        tickBefore = new long[16];
    }

    /** A grid bound to {@code table}, all air. */
    public TileGrid(int width, int height, BlockTable table){
        this(width, height);
        bind(table);
    }

    private TileGrid(TileGrid source){
        width = source.width;
        height = source.height;
        floor = source.floor.clone();
        overlay = source.overlay.clone();
        block = source.block.clone();
        team = source.team.clone();
        build = source.build.clone();
        data = source.data.clone();
        floorData = source.floorData.clone();
        overlayData = source.overlayData.clone();
        extraData = source.extraData.clone();
        solid = source.solid.clone();
        legSolid = source.legSolid.clone();
        statics = source.statics.clone();
        deep = source.deep.clone();
        liquid = source.liquid.clone();
        buildSolid = source.buildSolid.clone();
        marked = source.marked.clone();
        changedPos = source.changedPos.clone();
        changedBefore = source.changedBefore.clone();
        changes = source.changes;
        tickPos = source.tickPos.clone();
        tickBefore = source.tickBefore.clone();
        tickChanges = source.tickChanges;
        recording = source.recording;
        table = source.table;
        version = source.version;
    }

    public TileGrid copy(){
        return new TileGrid(this);
    }

    /**
     * Binds the content's block table and rebuilds the derived bitsets. The {@code World} binds its content's table
     * when it makes its grid; a grid must be bound before its predicates mean anything.
     */
    public void bind(BlockTable table){
        if(table.blocks() == 0) throw new IllegalArgumentException("a block table needs air, block 0");
        this.table = Objects.requireNonNull(table, "table");
        version++;
        rebuildDerived();
    }

    /** The bound block table, or null. */
    public BlockTable table(){
        return table;
    }

    public int width(){
        return width;
    }

    public int height(){
        return height;
    }

    public int size(){
        return build.length;
    }

    public boolean inBounds(int x, int y){
        return x >= 0 && y >= 0 && x < width && y < height;
    }

    /** {@link TileSolidity}'s answer from the predicate bitsets: true outside the grid. */
    @Override
    public boolean solid(Solidity kind, int x, int y){
        if(!inBounds(x, y)) return true;
        int pos = pos(x, y);
        return switch(kind){
            case SOLID -> solid(pos);
            case LEGS -> legSolid(pos);
            case WATER -> waterSolid(pos);
        };
    }

    /** {@code y * width + x}; the caller checks bounds. */
    public int pos(int x, int y){
        return y * width + x;
    }

    public int x(int pos){
        return pos % width;
    }

    public int y(int pos){
        return pos / width;
    }

    /** The grid position of a wire position ({@code Point2.pack(x, y)}), or -1 when it is outside the map. */
    public int fromWire(int wire){
        int x = Point2.x(wire), y = Point2.y(wire);
        return inBounds(x, y) ? pos(x, y) : -1;
    }

    /** The wire position, {@code Point2.pack(x, y)}. */
    public int toWire(int pos){
        return Point2.pack(x(pos), y(pos));
    }

    // columns

    public short floor(int pos){
        return floor[pos];
    }

    public void floor(int pos, int id){
        version++;
        touch(pos);
        floor[pos] = (short)id;
        refresh(pos);
    }

    public short overlay(int pos){
        return overlay[pos];
    }

    public void overlay(int pos, int id){
        version++;
        touch(pos);
        overlay[pos] = (short)id;
        refresh(pos);
    }

    public short block(int pos){
        return block[pos];
    }

    public void block(int pos, int id){
        version++;
        touch(pos);
        block[pos] = (short)id;
        refresh(pos);
    }

    /** The team of the tile's building, or 0 (derelict), as {@code Tile.team()}. */
    public byte team(int pos){
        return team[pos];
    }

    public void team(int pos, int id){
        version++;
        touch(pos);
        team[pos] = (byte)id;
    }

    /** The building registry index at {@code pos}, or -1. */
    public int build(int pos){
        return build[pos];
    }

    public void build(int pos, int index){
        version++;
        touch(pos);
        build[pos] = index;
    }

    /** Vanilla's {@code Tile.data}: block data; static walls keep their darkness here. */
    public byte data(int pos){
        return data[pos];
    }

    public void data(int pos, int value){
        version++;
        touch(pos);
        data[pos] = (byte)value;
        refresh(pos);
    }

    public byte floorData(int pos){
        return floorData[pos];
    }

    public void floorData(int pos, int value){
        version++;
        floorData[pos] = (byte)value;
    }

    public byte overlayData(int pos){
        return overlayData[pos];
    }

    public void overlayData(int pos, int value){
        version++;
        overlayData[pos] = (byte)value;
    }

    public int extraData(int pos){
        return extraData[pos];
    }

    public void extraData(int pos, int value){
        version++;
        extraData[pos] = value;
    }

    /** Whether the tile's building says it is solid ({@code build.checkSolid()}), as {@code Buildings} keeps it. */
    public boolean buildSolid(int pos){
        return get(buildSolid, pos);
    }

    public void buildSolid(int pos, boolean value){
        if(get(buildSolid, pos) == value) return;
        version++;
        touch(pos);
        set(buildSolid, pos, value);
        refresh(pos);
    }

    /** The tile's columns, for a journal before-image. */
    public TileImage image(int pos){
        return new TileImage(pos, floor[pos], overlay[pos], block[pos], team[pos], build[pos], data[pos], floorData[pos],
            overlayData[pos], extraData[pos]);
    }

    /** The tile's {@link TileState}. */
    public long packed(int pos){
        int flags = (get(solid, pos) ? TileState.FLAG_SOLID : 0) | (get(legSolid, pos) ? TileState.FLAG_LEG_SOLID : 0)
            | (get(deep, pos) ? TileState.FLAG_DEEP : 0) | (get(liquid, pos) ? TileState.FLAG_LIQUID : 0)
            | (get(statics, pos) ? TileState.FLAG_STATIC : 0) | (build[pos] != -1 ? TileState.FLAG_BUILDING : 0);
        return TileState.pack(floor[pos], overlay[pos], block[pos], team[pos], flags);
    }

    // predicates (vanilla Tile), kept as bitsets

    /** {@code Tile.solid()}: {@code block.solid || floor.solid || build.checkSolid()}. Ground units collide with it. */
    public boolean solid(int pos){
        return get(solid, pos);
    }

    /** {@code Tile.legSolid()}: {@code staticDarkness() >= 2 || (floor.solid && block == air)}. */
    public boolean legSolid(int pos){
        return get(legSolid, pos);
    }

    /** {@code block.isStatic()}: a static wall layer block. */
    public boolean isStatic(int pos){
        return get(statics, pos);
    }

    /** {@code Tile.isDeep()}: {@code block == air && floor.isDeep()}. */
    public boolean isDeep(int pos){
        return get(deep, pos);
    }

    /** {@code floor.isLiquid}. */
    public boolean isLiquid(int pos){
        return get(liquid, pos);
    }

    /** Naval units' predicate ({@code EntityCollisions.waterSolid}): {@code solid() || !floor.isLiquid}. */
    public boolean waterSolid(int pos){
        return get(solid, pos) || !get(liquid, pos);
    }

    /** The words of a predicate bitset, bit {@code pos & 63} of word {@code pos >>> 6}. Read only. */
    public long[] solidBits(){
        return solid;
    }

    public long[] legSolidBits(){
        return legSolid;
    }

    public long[] deepBits(){
        return deep;
    }

    public long[] liquidBits(){
        return liquid;
    }

    // predicates (vanilla Tile), computed

    /** {@code Tile.passable()}. */
    public boolean passable(int pos){
        int b = block[pos], f = floor[pos];
        return !((table.solid(f) && (b == 0 || table.solidifes(b))) || (table.solid(b) && !table.destructible(b) && !table.update(b)));
    }

    /** {@code Tile.synthetic()}: the block was placed by someone. */
    public boolean synthetic(int pos){
        return table.synthetic(block[pos]);
    }

    /** {@code Tile.breakable()}. */
    public boolean breakable(int pos){
        int b = block[pos];
        return table.destructible(b) || table.breakable(b) || table.update(b);
    }

    /** {@code Tile.dangerous()}: the floor drowns or damages, and no solid block covers it. */
    public boolean dangerous(int pos){
        int f = floor[pos];
        return !table.solid(block[pos]) && (table.isDeep(f) || table.damages(f));
    }

    /** {@code Tile.staticDarkness()}. */
    public int staticDarkness(int pos){
        int b = block[pos];
        return table.solid(b) && table.fillsTile(b) && !table.synthetic(b) ? data[pos] : 0;
    }

    /** {@code Tile.drop()}: the item mined here, or {@link BlockTable#NONE}. */
    public int drop(int pos){
        int o = overlay[pos];
        return o == 0 || table.itemDrop(o) == BlockTable.NONE ? table.itemDrop(floor[pos]) : table.itemDrop(o);
    }

    /** {@code Tile.wallDrop()}: the item mined from the wall here, or {@link BlockTable#NONE}. */
    public int wallDrop(int pos){
        int b = block[pos], o = overlay[pos];
        if(!table.solid(b)) return BlockTable.NONE;
        if(table.itemDrop(b) != BlockTable.NONE) return table.itemDrop(b);
        return table.wallOre(o) && !table.synthetic(b) ? table.itemDrop(o) : BlockTable.NONE;
    }

    /** {@code Tile.shouldSaveData()}. */
    public boolean shouldSaveData(int pos){
        return table.saveData(floor[pos]) || table.saveData(overlay[pos]) || table.saveData(block[pos]);
    }

    /** The floor a unit standing here feels ({@code PosComp.floorOn()}): air when a block covers the tile. */
    public int floorOn(int pos){
        return block[pos] != 0 ? 0 : floor[pos];
    }

    /** {@code floor.drownTime}. */
    public float drownTime(int pos){
        return table.drownTime(floor[pos]);
    }

    /** {@code floor.speedMultiplier}. */
    public float speedMultiplier(int pos){
        return table.speedMultiplier(floor[pos]);
    }

    /** {@code floor.dragMultiplier}. */
    public float dragMultiplier(int pos){
        return table.dragMultiplier(floor[pos]);
    }

    /** {@code floor.status}'s id. */
    public int status(int pos){
        return table.status(floor[pos]);
    }

    public float statusDuration(int pos){
        return table.statusDuration(floor[pos]);
    }

    /** {@code floor.damageTaken}, per tick. */
    public float damageTaken(int pos){
        return table.damageTaken(floor[pos]);
    }

    /** {@code floor.attributes.get(attribute)}. */
    public float attribute(int pos, int attribute){
        return table.attribute(floor[pos], attribute);
    }

    /**
     * {@code Block.sumAttribute(attribute, x, y)} for a block of {@code size} at (x, y): the floors' attribute over
     * the footprint, where deep floors count 0 unless the block {@code floats} (vanilla: {@code floating ||
     * placeableLiquid}).
     */
    public float sumAttribute(int attribute, int x, int y, int size, boolean floats){
        if(!inBounds(x, y)) return 0f;
        int offset = Footprint.offset(size);
        float sum = 0f;
        for(int dx = 0; dx < size; dx++){
            for(int dy = 0; dy < size; dy++){
                int wx = x + dx + offset, wy = y + dy + offset;
                if(!inBounds(wx, wy)) continue;
                int f = floor[pos(wx, wy)];
                sum += !floats && table.isDeep(f) ? 0f : table.attribute(f, attribute);
            }
        }
        return sum;
    }

    // darkness

    /**
     * {@code Block.isDarkened(tile)}: whether map-edge darkness applies, by the block's kind. World processors are
     * dark unless {@code worldProcessorsAccessible} ({@code LogicBlock.accessible()}: the editor, a playtest or
     * {@code rules.allowEditWorldProcessors}).
     */
    public boolean isDarkened(int pos, boolean worldProcessorsAccessible){
        int b = block[pos];
        return switch(table.darkness(b)){
            case BlockTable.DARK_COLORED_WALL -> extraData[pos] == COLORED_WALL_DARK;
            case BlockTable.DARK_LOGIC -> table.solid(b) && ((!table.synthetic(b) && table.fillsTile(b))
                || (table.privileged(b) && !worldProcessorsAccessible));
            default -> table.solid(b) && ((!table.synthetic(b) && table.fillsTile(b)) || table.forceDark(b));
        };
    }

    /**
     * {@code World.addDarkness}, run once when a map ends loading: each darkened tile's {@code data} becomes its
     * darkness, 0 to {@link #DARK_RADIUS} by distance from the wall mass's edge, and {@code DARK_RADIUS + 1} when it is
     * fully enclosed. Vanilla never runs it again, so walls placed or removed later keep their neighbours' values.
     */
    public void addDarkness(boolean worldProcessorsAccessible){
        int n = size();
        byte[] dark = new byte[n], next = new byte[n];
        for(int i = 0; i < n; i++){
            if(isDarkened(i, worldProcessorsAccessible)) dark[i] = DARK_RADIUS;
        }
        for(int iteration = 0; iteration < DARK_RADIUS; iteration++){
            for(int i = 0; i < n; i++){
                int x = x(i), y = y(i);
                boolean min = false;
                for(int d = 0; d < 4; d++){
                    int nx = x + Geometry.d4x(d), ny = y + Geometry.d4y(d);
                    if(inBounds(nx, ny) && dark[pos(nx, ny)] < dark[i]){
                        min = true;
                        break;
                    }
                }
                next[i] = (byte)Math.max(0, dark[i] - (min ? 1 : 0));
            }
            System.arraycopy(next, 0, dark, 0, n);
        }
        for(int i = 0; i < n; i++){
            boolean darkened = isDarkened(i, worldProcessorsAccessible);
            if(darkened) data(i, dark[i]);
            if(dark[i] == DARK_RADIUS){
                boolean full = true;
                for(int d = 0; d < 4; d++){
                    int nx = x(i) + Geometry.d4x(d), ny = y(i) + Geometry.d4y(d);
                    // World.java:440 tests the tile's own darkening, not the neighbour's: kept as is
                    if(inBounds(nx, ny) && !(darkened && dark[pos(nx, ny)] == 4)){
                        full = false;
                        break;
                    }
                }
                if(full) data(i, DARK_RADIUS + 1);
            }
        }
    }

    // changes

    /**
     * Starts the tick's change list: every tile recorded since the previous tick began (by that tick's phases or
     * between ticks) becomes this tick's, and the pending list starts empty. {@code World} calls it before its first
     * phase.
     */
    public void beginTick(){
        if(tickChanges != 0) throw new IllegalStateException("the previous tick's changes were never ended");
        // the pending list moves out of the hash; an empty one changes nothing
        if(changes != 0) version++;
        int[] positions = tickPos;
        long[] befores = tickBefore;
        tickPos = changedPos;
        tickBefore = changedBefore;
        tickChanges = changes;
        changedPos = positions;
        changedBefore = befores;
        for(int i = 0; i < tickChanges; i++) set(marked, tickPos[i], false);
        changes = 0;
    }

    /** Clears the tick's change list. {@code World} calls it after its last phase, so the list is empty between ticks. */
    public void endTick(){
        tickChanges = 0;
    }

    /**
     * Hands each tile of the tick's change list to {@code consumer}, once, in order of first change, with its state
     * from before that change. Any number of readers may, in any phase: the list stays as it is until the tick ends,
     * and changes made meanwhile go to the next tick's list. The path service reads it in {@code PATH_FLUSH}; fog,
     * spatial and logic code may too.
     */
    public void changes(TileChangeConsumer consumer){
        for(int i = 0; i < tickChanges; i++) consumer.changed(tickPos[i], tickBefore[i]);
    }

    /** How many tiles the tick's change list holds. */
    public int changeCount(){
        return tickChanges;
    }

    /** How many tiles changed since the tick began (or, between ticks, since the last one began). */
    public int pendingChanges(){
        return changes;
    }

    /** Forgets every change, pending and the tick's, as after a map load, which readers rebuild from instead. */
    public void clearChanges(){
        if(changes != 0) version++;
        for(int i = 0; i < changes; i++) set(marked, changedPos[i], false);
        changes = 0;
        tickChanges = 0;
    }

    /** Whether changes are recorded; map loading turns it off. */
    public void recording(boolean recording){
        this.recording = recording;
    }

    public boolean recording(){
        return recording;
    }

    private void touch(int pos){
        if(!recording || get(marked, pos)) return;
        set(marked, pos, true);
        if(changes == changedPos.length){
            changedPos = Arrays.copyOf(changedPos, changes * 2);
            changedBefore = Arrays.copyOf(changedBefore, changes * 2);
        }
        changedPos[changes] = pos;
        changedBefore[changes] = packed(pos);
        changes++;
    }

    // checkpoints

    /**
     * Writes the authoritative state into a checkpoint's {@code #tiles} section, by field name: the columns, the pending
     * changes and the recording flag. The derived bitsets are rebuilt instead, and the tick's change list is empty
     * between ticks.
     */
    public void write(CheckpointOut out){
        if(tickChanges != 0) throw new IllegalStateException("a checkpoint is taken between ticks, not while a tick's changes are read");
        out.i("width", width).i("height", height);
        out.shorts("floor", floor).shorts("overlay", overlay).shorts("block", block).bytes("team", team).ints("build", build);
        out.bytes("data", data).bytes("floorData", floorData).bytes("overlayData", overlayData).ints("extraData", extraData);
        out.ints("changedPos", changedPos, changes).longs("changedBefore", changedBefore, changes).i("changes", changes)
            .bool("recording", recording);
    }

    /**
     * Reads what {@link #write} stored into this grid, which must have the stored size, and marks the pending changes.
     * The predicate bitsets are left for {@code Buildings.rebuildDerived()}, which needs the restored buildings for
     * {@code buildSolid}.
     */
    public void read(CheckpointIn in){
        if(in.i("width") != width || in.i("height") != height) throw new CheckpointException("the tile grid's size changed");
        version++;
        in.shorts("floor", floor);
        in.shorts("overlay", overlay);
        in.shorts("block", block);
        in.bytes("team", team);
        in.ints("build", build);
        in.bytes("data", data);
        in.bytes("floorData", floorData);
        in.bytes("overlayData", overlayData);
        in.ints("extraData", extraData);
        int[] positions = in.ints("changedPos");
        long[] befores = in.longs("changedBefore");
        int count = in.i("changes");
        if(positions == null || befores == null || positions.length != count || befores.length != count){
            throw new CheckpointException("the pending tile changes are inconsistent");
        }
        clearChanges();
        changedPos = Arrays.copyOf(positions, Math.max(16, count));
        changedBefore = Arrays.copyOf(befores, Math.max(16, count));
        for(int i = 0; i < count; i++){
            if(positions[i] < 0 || positions[i] >= size()) throw new CheckpointException("a pending tile change is outside the map");
            set(marked, positions[i], true);
        }
        changes = count;
        recording = in.bool("recording");
    }

    // derived state

    /** Recomputes the predicate bitsets from the columns and {@code buildSolid}. */
    public void rebuildDerived(){
        if(table == null) return;
        version++;
        for(int pos = 0; pos < size(); pos++) refresh(pos);
    }

    private void refresh(int pos){
        if(table == null) return;
        int b = block[pos], f = floor[pos];
        set(solid, pos, table.solid(b) || table.solid(f) || get(buildSolid, pos));
        set(legSolid, pos, staticDarkness(pos) >= 2 || (table.solid(f) && b == 0));
        set(statics, pos, table.isStatic(b));
        set(deep, pos, b == 0 && table.isDeep(f));
        set(liquid, pos, table.isLiquid(f));
    }

    private static boolean get(long[] bits, int pos){
        return (bits[pos >>> 6] & 1L << pos) != 0;
    }

    private static void set(long[] bits, int pos, boolean value){
        if(value) bits[pos >>> 6] |= 1L << pos;
        else bits[pos >>> 6] &= ~(1L << pos);
    }

    /**
     * A number that moves whenever anything {@link #hash} reads changes, and only then: the columns, the pending
     * changes and the predicate bitsets. Equal versions of one grid hash alike.
     */
    public long version(){
        return version;
    }

    /** The tick's change list is not hashed: it is empty between ticks, where hashes are taken. */
    public void hash(Hasher hasher){
        hasher.i(width).i(height).shorts(floor).shorts(overlay).shorts(block).bytes(team).ints(build);
        hasher.bytes(data).bytes(floorData).bytes(overlayData).ints(extraData);
        hasher.ints(changedPos, 0, changes);
        for(int i = 0; i < changes; i++) hasher.l(changedBefore[i]);
        for(long[] bits : new long[][]{solid, legSolid, statics, deep, liquid, buildSolid}){
            hasher.l(bits.length);
            for(long word : bits) hasher.l(word);
        }
    }
}
