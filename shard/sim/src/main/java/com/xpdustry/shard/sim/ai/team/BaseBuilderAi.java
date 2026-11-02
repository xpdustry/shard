package com.xpdustry.shard.sim.ai.team;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.ai.team.BaseRegistry.*;
import com.xpdustry.shard.sim.blocks.storage.*;
import com.xpdustry.shard.sim.build.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.path.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code BaseBuilderAI} (BaseBuilderAI.java) for one team, which {@code rules.teams[team].buildAi} turns on
 * outside PvP. Each frame it fills the team's first core with every item; every 6 s, with {@code aiCoreSpawn}, it makes
 * a core unit while the team has fewer than two per core. It walks the team's ground field from the last ground spawn
 * to an enemy core, {@value #PATH_STEP} tiles a frame, and keeps the tiles along that path (and their neighbours) free
 * of solid blocks; every 3 minutes it walks it again. When the team has no rebuild plans left, it tries up to
 * {@value #ATTEMPTS} random spots within 150 tiles of a random core (or spawn, for the wave team without cores): a spot
 * on an ore takes a random base part that needs that ore, any other a random part with no need 1% of the time. A part
 * is placed, at a random quarter turn, only where all of it may be built, off the path, away from the spawns, its
 * drills over its ore and no other; its blocks become the team's plans, which the team's builders then build.
 *
 * <p>State: the timers, the path ({@code path}, the last complete walk) and the walk in progress, and the handle on
 * the ground field it reads. A loaded save starts a new one, as vanilla's {@code logic.reset()} does.
 */
public final class BaseBuilderAi implements Checkpointable{
    static final int ATTEMPTS = 6, CORE_UNIT_MULTIPLIER = 2;
    static final float EMPTY_CHANCE = 0.01f;
    static final int TIMER_STEP = 0, TIMER_SPAWN = 1, TIMER_REFRESH_PATH = 2;
    static final float PLACE_INTERVAL_MIN = 12f, PLACE_INTERVAL_MAX = 2f;
    static final int PATH_STEP = 50;
    static final KindKey PAYLOAD_CONVEYOR = KindKey.of("payload-conveyor"), PAYLOAD_BLOCK = KindKey.of("payload-block"),
        DRILL = KindKey.of("drill");

    int team;
    boolean foundPath;
    /** {@code timer}'s times, by id. Vanilla's {@code Interval} has 4; it uses three. */
    final float[] timers = new float[3];
    /** {@code path}: the tiles of the last complete walk and their neighbours, as {@code Point2} positions. */
    IntSet path = new IntSet();
    /** {@code calcPath}: the walk in progress. */
    IntSet calcPath = new IntSet();
    /** {@code calcTile}: where the walk is, as a grid position, or -1. */
    int calcTile = -1;
    boolean calculating, startedCalculating;
    int calcCount, totalCalcs;
    /** The handle on the team's ground field ({@code pathfinder.getField(team, costGround, fieldCore)}), or -1. */
    int field = -1;

    /** For a checkpoint to fill. */
    public BaseBuilderAi(){
    }

    BaseBuilderAi(int team){
        this.team = team;
    }

    public int team(){
        return team;
    }

    /** {@code timer.getTimes()}, for tests. */
    public float[] timers(){
        return timers.clone();
    }

    /** Whether a walk reached an enemy core, for tests. */
    public boolean foundPath(){
        return foundPath;
    }

    /** {@code path}'s positions, sorted, for tests. */
    public int[] path(){
        return sorted(path);
    }

    /** {@code Interval.get(id, time)}. */
    private boolean timer(World world, int id, float time){
        float now = world.timers().time();
        boolean got = now - timers[id] >= time || now < timers[id];
        if(got) timers[id] = now;
        return got;
    }

    /** {@code update()}. */
    void update(World world, BaseRegistry bases){
        Teams teams = Teams.of(world);
        TeamData data = teams.get(team);
        Rules rules = RtsAi.rules(world);
        TileGrid tiles = world.tiles();

        // fill cores
        if(!data.cores().isEmpty()){
            Core core = data.cores().getFirst();
            for(int item = 0; item < world.content().items(); item++) core.items.set(item, core.getMaximumAccepted(item));
        }

        if(rules.teams.get(team).aiCoreSpawn && timer(world, TIMER_SPAWN, 60 * 6f) && data.hasCore()){
            UnitDef type = RtsAi.coreUnit(world, data.core());
            int coreUnits = TeamUnits.of(world).count(team, type);
            // create AI core unit(s)
            if(!rules.editor && coreUnits < data.cores().size() * CORE_UNIT_MULTIPLIER){
                RtsAi.spawnCoreUnit(world, type, team, data.cores());
            }
        }

        // refresh path
        if(!calculating && (timer(world, TIMER_REFRESH_PATH, 3f * 60f * 60f) || !startedCalculating) && data.hasCore()){
            calculating = true;
            startedCalculating = true;
            calcPath.clear();
        }

        // didn't find tile in time
        if(calculating && calcCount >= tiles.width() * tiles.height()){
            calculating = false;
            calcCount = 0;
            calcPath.clear();
            totalCalcs++;
        }

        // calculate path for units so schematics are not placed on it
        if(calculating){
            if(calcTile == -1){
                WaveSpawner.of(world).eachGroundSpawn(world, (x, y) -> calcTile = tiles.inBounds(x, y) ? tiles.pos(x, y) : -1);
                if(calcTile == -1) calculating = false;
            }else{
                walk(world, tiles);
            }
        }

        // only schedule when there's something to build
        if((foundPath || !calculating) && data.plans().isEmpty()
            && timer(world, TIMER_STEP, Mathf.lerp(PLACE_INTERVAL_MIN, PLACE_INTERVAL_MAX, rules.teams.get(team).buildAiTier))){
            Rand rand = world.rand();
            WaveSpawner spawner = WaveSpawner.of(world);
            for(int i = 0; i < ATTEMPTS; i++){
                int range = 150;
                float[] pos = randomPosition(world, data, rules);
                // when there are no random positions, do nothing
                if(pos == null) return;
                // Tmp.v1.rnd(Mathf.random(range)): range is an int, so the length is a whole number of tiles
                float length = Mathf.random(rand, range);
                float theta = Mathf.random(rand, 0f, Mathf.PI2);
                float vx = Mathf.cos(theta) * length, vy = Mathf.sin(theta) * length;
                int wx = (int)(RtsAi.toTile(pos[0]) + vx), wy = (int)(RtsAi.toTile(pos[1]) + vy);
                // world.tiles.getc(wx, wy): clamped into the map
                int tx = Mathf.clamp(wx, 0, tiles.width() - 1), ty = Mathf.clamp(wy, 0, tiles.height() - 1);

                // try not to block the spawn point
                if(nearSpawn(world, spawner, tiles, tx, ty)) continue;

                List<BasePart> parts = null;
                // pick a completely random base part, and place it a random location ((yes, very intelligent))
                int drop = tiles.drop(tiles.pos(tx, ty));
                if(drop != BlockTable.NONE && !bases.forResource(ContentRef.item(drop)).isEmpty()){
                    parts = bases.forResource(ContentRef.item(drop));
                }else if(Mathf.chance(rand, EMPTY_CHANCE)){
                    parts = bases.parts;
                }

                if(parts != null){
                    BasePart part = parts.get(RtsAi.seqRandom(rand, parts.size()));
                    if(tryPlace(world, data, part, tx, ty)) break;
                }
            }
        }
    }

    /** One frame of the walk: up to {@value #PATH_STEP} steps down the ground field, from the spawn to an enemy core. */
    private void walk(World world, TileGrid tiles){
        PathService paths = world.part(PathService.class);
        if(field == -1) field = paths.field(team, PathCost.GROUND);
        if(!paths.ready(field)) return;
        int width = tiles.width();
        for(int i = 0; i < PATH_STEP; i++){
            int minCost = Integer.MAX_VALUE;
            int cx = tiles.x(calcTile), cy = tiles.y(calcTile);
            boolean foundAny = false;
            for(int d = 0; d < 4; d++){
                int nx = cx + Geometry.d4x(d), ny = cy + Geometry.d4y(d);
                if(!tiles.inBounds(nx, ny)) continue;
                int packed = nx + ny * width;
                int weight = paths.distance(field, packed);
                if(weight < minCost && weight != -1){
                    minCost = weight;
                    calcTile = packed;
                    foundAny = true;
                }
            }
            // didn't find anything, break out of loop, this will trigger a clear later
            if(!foundAny){
                calcCount = Integer.MAX_VALUE;
                break;
            }
            int x = tiles.x(calcTile), y = tiles.y(calcTile);
            calcPath.add(Point2.pack(x, y));
            for(int d = 0; d < 8; d++){
                Point2 p = Geometry.d8(d);
                calcPath.add(Point2.pack(p.x() + x, p.y() + y));
            }
            // found the end
            if(world.buildings().at(calcTile) instanceof Core core && core.team != team){
                // clean up calculations and flush results
                calculating = false;
                calcCount = 0;
                path.clear();
                path.addAll(calcPath);
                calcPath.clear();
                calcTile = -1;
                totalCalcs++;
                foundPath = true;
                break;
            }
            calcCount++;
        }
    }

    /** {@code randomPosition()}: a random core, or for the wave team without cores a random spawn; or null. */
    private float[] randomPosition(World world, TeamData data, Rules rules){
        if(data.hasCore()){
            Core core = data.cores().get(RtsAi.seqRandom(world.rand(), data.cores().size()));
            return new float[]{core.x(), core.y()};
        }else if(team == rules.waveTeam){
            TileGrid tiles = world.tiles();
            IntSeq spawns = WaveSpawner.of(world).spawns(world);
            if(spawns.isEmpty()) return null;
            int spawn = spawns.get(RtsAi.seqRandom(world.rand(), spawns.size));
            return new float[]{tiles.x(spawn) * 8f, tiles.y(spawn) * 8f};
        }
        return null;
    }

    /** {@code spawner.getSpawns().contains(t -> t.within(tile, tilesize * 40f))}. */
    private static boolean nearSpawn(World world, WaveSpawner spawner, TileGrid tiles, int x, int y){
        IntSeq spawns = spawner.spawns(world);
        float range = 8f * 40f;
        for(int i = 0; i < spawns.size; i++){
            int spawn = spawns.get(i);
            float dx = x * 8f - tiles.x(spawn) * 8f, dy = y * 8f - tiles.y(spawn) * 8f;
            if(dx * dx + dy * dy <= range * range) return true;
        }
        return false;
    }

    /** {@code tryPlace(part, x, y)}: whether the part was queued as the team's plans. */
    private boolean tryPlace(World world, TeamData data, BasePart part, int x, int y){
        Rand rand = world.rand();
        TileGrid tiles = world.tiles();
        int rotation = Mathf.range(rand, 2);
        var axis = new Vec2((int)(part.width() / 2f), (int)(part.height() / 2f));
        SchematicRotation.Rotated result = SchematicRotation.rotate(part, rotation);
        int rotdeg = rotation * 90;
        var rotator = new Vec2(part.centerX(), part.centerY()).rotateAround(axis, rotdeg);
        // bottom left schematic corner
        int cx = x - (int)rotator.x;
        int cy = y - (int)rotator.y;

        // check valid placeability
        for(Stile tile : result.tiles()){
            int realX = tile.x() + cx, realY = tile.y() + cy;
            if(!Build.validPlace(world, tile.block(), team, realX, realY, tile.rotation())) return false;

            if(tile.block().isA(PAYLOAD_CONVEYOR) || tile.block().isA(PAYLOAD_BLOCK)){
                // near a building; vanilla quirk: it looks around the schematic's own coordinates, not the map's
                int size = tile.block().size();
                for(int e = 0; e < Footprint.edgeCount(size); e++){
                    if(world.buildings().at(tile.x() + Footprint.edgeX(size, e), tile.y() + Footprint.edgeY(size, e)) != null) return false;
                }
            }

            // may intersect AI path
            if(tile.block().solid() && tiles.inBounds(realX, realY) && covers(tiles, tile.block().size(), realX, realY, path)) return false;
        }

        // make sure at least X% of resource requirements are met
        int[] counts = new int[2];
        boolean anyDrills = false;
        if(part.required() != null && part.required().type() == ContentType.ITEM){
            for(Stile tile : result.tiles()){
                if(tile.block().isA(DRILL)){
                    anyDrills = true;
                    int size = tile.block().size(), o = Footprint.offset(size);
                    // iterateTaken: every tile of a multiblock, else the one
                    for(int dx = 0; dx < size; dx++){
                        for(int dy = 0; dy < size; dy++){
                            int drop = tiles.drop(tiles.pos(dx + tile.x() + cx + o, dy + tile.y() + cy + o));
                            if(drop == part.required().id()){
                                counts[0]++;
                            }else if(drop != BlockTable.NONE){
                                counts[1]++;
                            }
                        }
                    }
                }
            }
        }

        // fail if not enough fit requirements
        if(anyDrills && (counts[1] != 0 || counts[0] == 0)) return false;

        // queue it
        for(Stile tile : result.tiles()){
            data.plans().add(new BlockPlan(cx + tile.x(), cy + tile.y(), tile.rotation(), tile.block().id(), tile.config()));
        }
        return true;
    }

    /** {@code wtile.getLinkedTilesAs(block, tmpTiles).contains(t -> path.contains(t.pos()))}. */
    private static boolean covers(TileGrid tiles, int size, int x, int y, IntSet path){
        int o = Footprint.offset(size);
        for(int dx = 0; dx < size; dx++){
            for(int dy = 0; dy < size; dy++){
                int tx = x + dx + o, ty = y + dy + o;
                if(tiles.inBounds(tx, ty) && path.contains(Point2.pack(tx, ty))) return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- state

    BaseBuilderAi copy(){
        var out = new BaseBuilderAi(team);
        out.foundPath = foundPath;
        System.arraycopy(timers, 0, out.timers, 0, timers.length);
        out.path = new IntSet(path);
        out.calcPath = new IntSet(calcPath);
        out.calcTile = calcTile;
        out.calculating = calculating;
        out.startedCalculating = startedCalculating;
        out.calcCount = calcCount;
        out.totalCalcs = totalCalcs;
        out.field = field;
        return out;
    }

    void hash(Hasher hasher){
        hasher.i(team).bool(foundPath).f(timers[0]).f(timers[1]).f(timers[2]).i(calcTile).bool(calculating)
            .bool(startedCalculating).i(calcCount).i(totalCalcs).i(field);
        for(int[] set : new int[][]{sorted(path), sorted(calcPath)}){
            hasher.i(set.length);
            for(int value : set) hasher.i(value);
        }
    }

    static int[] sorted(IntSet set){
        IntSeq seq = set.toSeq();
        int[] out = Arrays.copyOf(seq.items, seq.size);
        Arrays.sort(out);
        return out;
    }

    @Override
    public void write(CheckpointOut out){
        out.i("team", team).bool("foundPath", foundPath).floats("timers", timers).ints("path", sorted(path))
            .ints("calcPath", sorted(calcPath)).i("calcTile", calcTile).bool("calculating", calculating)
            .bool("startedCalculating", startedCalculating).i("calcCount", calcCount).i("totalCalcs", totalCalcs).i("field", field);
    }

    @Override
    public void read(CheckpointIn in){
        team = in.i("team");
        foundPath = in.bool("foundPath");
        in.floats("timers", timers);
        path = IntSet.with(in.ints("path"));
        calcPath = IntSet.with(in.ints("calcPath"));
        calcTile = in.i("calcTile");
        calculating = in.bool("calculating");
        startedCalculating = in.bool("startedCalculating");
        calcCount = in.i("calcCount");
        totalCalcs = in.i("totalCalcs");
        field = in.i("field");
    }
}
