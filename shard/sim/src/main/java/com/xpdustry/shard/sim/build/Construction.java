package com.xpdustry.shard.sim.build;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Construction's world state, which vanilla keeps in statics: the place sound's clock ({@code ConstructBlock.lastPlayed},
 * {@code lastTime}, {@code pitchSeq}), whether the map had a core zone ({@code indexer.isBlockPresent(coreZone)}), and
 * an upgraded core's items between {@code beforePlaceBegan} and {@code placeBegan}. It also hears of destroyed buildings
 * ({@link DestroyWatcher}), which become rebuild plans ({@link RebuildPlans}), and holds the per-world tables
 * construction reads: each block's {@link PlaceInfo} and each unit type's build speed and range.
 *
 * <p>The sound clock is a random draw a headless vanilla server makes: {@code constructFinish} computes the place
 * sound's pitch when {@code shouldPlay()}, at most every 32 ms of wall time, and the pitch draws
 * {@code Mathf.random(0.7f, 1.3f)} when the last one was 480 ms or more ago. A world has no wall clock, so this one
 * runs on the tick count at 60 ticks a second, as a server keeping up does.
 *
 * <p>{@link Persistence#AUTHORITATIVE}: the clock and the core zone flag.
 */
@Register
public final class Construction implements WorldPart, Checkpointable, DestroyWatcher{
    /** A time before any: the statics start at 0, decades of wall time ago. */
    static final long NEVER = Long.MIN_VALUE / 4;
    static final double MILLIS_PER_TICK = 1000.0 / 60.0;

    /** The ticks the place sound last played and its pitch was last computed. */
    long lastPlayed = NEVER, lastTime = NEVER;
    int pitchSeq;
    /** Whether a core zone floor was seen on the map: never unset, as vanilla's indexer never clears it. */
    boolean coreZonePresent;
    @Derived("null between calls: set by a core's beforePlaceBegan, taken by its placeBegan in the same beginPlace")
    ItemModule nextItems;
    @Derived("per block id, read from the content on first use")
    private PlaceInfo[] infos;
    @Derived("per unit id, read from the content on first use")
    private float[] buildSpeed, buildRange;
    @Derived("the world's content, which the tables are read from: set by init and of()")
    private ContentEpoch content;
    @Derived("the world tick deleteVisits counts in: checkpoints are taken between ticks, and a new tick starts at zero")
    private long deleteTick = -1;
    @Derived("the positions and plans deletePlans visited in deleteTick; see deleteTick")
    private long deleteVisits;

    @Override
    public void init(World world){
        content = world.content();
    }

    /**
     * The world's construction state. A world built without the part (a test's own systems) gets a fresh one each
     * call, which answers the tables; its builders don't build ({@link #present}).
     */
    public static Construction of(World world){
        Construction construction = world.find(Construction.class);
        if(construction == null){
            construction = new Construction();
            construction.content = world.content();
            return construction;
        }
        if(construction.content == null) construction.content = world.content();
        return construction;
    }

    /** Counts {@code visits} positions and plans {@link RebuildPlans#delete} went through in this tick. */
    void deleteVisited(World world, long visits){
        if(deleteTick != world.ticks()){
            deleteTick = world.ticks();
            deleteVisits = 0;
        }
        deleteVisits += visits;
    }

    /** The world tick {@link #deleteVisits} last counted in. */
    public long deleteTick(){
        return deleteTick;
    }

    /** The positions and plans {@code deletePlans} went through in {@link #deleteTick}: its cost, as a count. */
    public long deleteVisits(){
        return deleteVisits;
    }

    /** Whether the world has the part, so construction may change it. */
    public static boolean present(World world){
        return world.find(Construction.class) != null;
    }

    /** The placement data of the block with content id {@code block}. */
    public PlaceInfo info(int block){
        if(infos == null) infos = new PlaceInfo[content.blocks()];
        PlaceInfo info = infos[block];
        if(info == null) info = infos[block] = PlaceInfo.of(content.block(block), content);
        return info;
    }

    /** {@code type.buildSpeed}: 0 for units that can't build. */
    public float buildSpeed(UnitDef type){
        tables();
        return buildSpeed[type.id()];
    }

    /** {@code type.buildRange}. */
    public float buildRange(UnitDef type){
        tables();
        return buildRange[type.id()];
    }

    private void tables(){
        if(buildSpeed != null) return;
        buildSpeed = new float[content.units()];
        buildRange = new float[content.units()];
        for(int id = 0; id < buildSpeed.length; id++){
            UnitDef type = content.unit(id);
            // a fresh UnitType's: no building, Vars.buildingRange
            buildSpeed[id] = type.baked() ? type.buildSpeed() : 0f;
            buildRange[id] = type.baked() ? type.buildRange() : 220f;
        }
    }

    /**
     * {@code constructFinish}'s place sound: {@code shouldPlay()} and, for a block whose pitch changes,
     * {@code calcPitch(true)}, which draws from the world's random when the last pitch is old.
     */
    void placeSound(World world, PlaceInfo block){
        long now = world.ticks();
        if(millis(now, lastPlayed) < 32) return;
        lastPlayed = now;
        if(!placePitchChange(block)) return;
        if(millis(now, lastTime) < 16 * 30){
            lastTime = now;
            pitchSeq++;
            if(pitchSeq > 30) pitchSeq = 0;
        }else{
            pitchSeq = 0;
            lastTime = now;
            Mathf.random(world.rand(), 0.7f, 1.3f);
        }
    }

    /** The tick the place sound last played on, for oracle tests that drive vanilla's clock the same way. */
    public long lastPlayed(){
        return lastPlayed;
    }

    /** The tick the place sound's pitch was last computed on. */
    public long lastPitch(){
        return lastTime;
    }

    /** {@code pitchSeq}: 0 right after a pitch drew from the random. */
    public int pitchSeq(){
        return pitchSeq;
    }

    private static double millis(long now, long then){
        return then == NEVER ? Double.MAX_VALUE : (now - then) * MILLIS_PER_TICK;
    }

    private static boolean placePitchChange(PlaceInfo block){
        return !block.block.baked() || !block.block.fields().has("placePitchChange") || block.block.fields().b("placePitchChange");
    }

    /**
     * {@code indexer.isBlockPresent(Blocks.coreZone)}: a core zone floor under a tile that isn't a static wall.
     * Vanilla marks it at load and on every tile change and never unmarks it; Shard looks until it finds one, then keeps
     * the answer.
     */
    boolean coreZonePresent(World world){
        if(coreZonePresent) return true;
        int zone = world.content().id(ContentType.BLOCK, "core-zone");
        if(zone < 0) return false;
        TileGrid tiles = world.tiles();
        for(int pos = 0; pos < tiles.size(); pos++){
            if(tiles.floor(pos) == zone && !tiles.isStatic(pos)){
                coreZonePresent = true;
                break;
            }
        }
        return coreZonePresent;
    }

    @Override
    public void destroying(Building building){
        RebuildPlans.destroyed(building);
    }

    // state

    @Override
    public WorldPart copy(CopyContext context){
        var out = new Construction();
        out.lastPlayed = lastPlayed;
        out.lastTime = lastTime;
        out.pitchSeq = pitchSeq;
        out.coreZonePresent = coreZonePresent;
        out.infos = infos;
        out.buildSpeed = buildSpeed;
        out.buildRange = buildRange;
        out.content = content;
        return out;
    }

    @Override
    public void hash(Hasher hasher){
        hasher.l(lastPlayed).l(lastTime).i(pitchSeq).bool(coreZonePresent);
    }

    @Override
    public void write(CheckpointOut out){
        out.l("lastPlayed", lastPlayed).l("lastTime", lastTime).i("pitchSeq", pitchSeq).bool("coreZonePresent", coreZonePresent);
    }

    @Override
    public void read(CheckpointIn in){
        lastPlayed = in.l("lastPlayed");
        lastTime = in.l("lastTime");
        pitchSeq = in.i("pitchSeq");
        coreZonePresent = in.bool("coreZonePresent");
    }
}
