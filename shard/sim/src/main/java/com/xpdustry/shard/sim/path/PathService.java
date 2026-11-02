package com.xpdustry.shard.sim.path;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

import static com.xpdustry.shard.sim.path.Flowfield.*;
import static com.xpdustry.shard.sim.path.PathCost.*;

/**
 * The world's path service (note 22, ARCHITECTURE.md §9): flowfields for wave AI and RTS orders, repaired in place
 * once per tick. It runs inside the world's tick, with no threads.
 *
 * <p><b>Fields.</b> {@link #field(int, PathCost)} is vanilla's {@code EnemyCoreField} for a team and cost class:
 * enemy cores, plus the spawns for the default team in wave mode, plus one random building flag for the wave team
 * under {@code randomWaveAI}. {@link #field(int, PathCost, int)} is an RTS field towards one tile, with
 * {@code ControlPathfinder}'s costs. Both return a handle and count a reference; {@link #release(int)} drops it. A new
 * wave-AI field is built whole at the next flush. RTS fields are built in request order under
 * {@link PathRules#rtsPopBudget()} pops per tick, and freed {@value #EXPIRY_TICKS} ticks after their last release.
 * Wave-AI fields live as long as the world, as in vanilla.
 *
 * <p><b>Changes.</b> {@link #tileChanged(int)} queues a tile; {@link #flush(World)}, in {@link Phase#PATH_FLUSH},
 * re-reads the queued tiles, repacks their 3×3 rings and repairs every field. A batch over 5% of the tiles rebuilds
 * instead. Fields are read after the flush, in the same tick: units, then everything else.
 *
 * <p><b>State.</b> The service is {@link Persistence#AUTHORITATIVE}, because some of it can't be rebuilt from the
 * world: the field registry (handles, references, release ticks, RTS build progress and queue order), each wave-AI
 * field's target set (random-flag targets depend on building status at the flush), the rules, the queued tiles, and
 * the view of the world as of the last flush (it differs from the world at a tick boundary by the changes the next
 * flush will apply, and by vanilla's health and {@code legSolid} samples). The fields themselves, the packed tiles and
 * the threat layer are derived: {@link #write} stores the authoritative part, as the checkpoint's {@code paths} section
 * ({@link SectionPart}), and {@link #read} rebuilds the rest to exactly the state it had. On a world whose content
 * defines no path table ({@link PathBlocks#of}) the service is inactive, holds nothing and is
 * {@link Persistence#DERIVED}.
 *
 * <p>Owner: WP53.
 */
@Register
public final class PathService implements SectionPart{
    /** 30 s: an RTS field with no holders is freed this long after its last release. */
    public static final int EXPIRY_TICKS = 30 * 60;
    private static final int VERSION = 2;
    /** Above this share of the map, a change batch rebuilds instead of repairing. */
    private static final int FULL_DIVISOR = 20;
    // Geometry.d8 order, for descent
    private static final int[] D8X = {1, 1, 0, -1, -1, -1, 0, 1}, D8Y = {0, 1, 1, 1, 0, -1, -1, -1};
    /** {@code Vars.tilesize}. */
    private static final float TILE = 8f;

    private PathBlocks blocks;
    private PathRules rules = PathRules.DEFAULT, pending;
    private PathGrid grid;
    private Threat threat;
    private FieldEngine engine;
    private final ArrayList<Flowfield> slots = new ArrayList<>();
    private final TreeMap<Long, Integer> byKey = new TreeMap<>();
    private final IntSeq free = new IntSeq(), queue = new IntSeq();
    private final IntSeq dirty = new IntSeq();
    private Bits dirtyBits;
    private boolean invalidated;
    private long now;
    // derived from the view, sorted
    private final IntSeq cores = new IntSeq(), spawns = new IntSeq(), turrets = new IntSeq();
    // flush scratch
    private int[] mark;
    private int markGen;
    private final IntSeq changed = new IntSeq(), ring = new IntSeq(), packedPos = new IntSeq(),
        packedNew = new IntSeq(), candidates = new IntSeq(), wide = new IntSeq(), added = new IntSeq(), removed = new IntSeq();
    private long[] oldViews = new long[64];
    /** Batches that rebuilt fields instead of repairing them (whole-map refreshes count once), for metrics. */
    long fallbacks;
    /**
     * Whether this tick's flush re-read the whole world for {@link #invalidateAll}: a map load, after which the
     * {@link ControlPathfinder} starts over. Set and read within one {@code PATH_FLUSH}, so never state.
     */
    boolean reloaded;

    public PathService(){
    }

    @Override
    public void init(World world){
        blocks = PathBlocks.of(world.content());
        if(blocks == null) return;
        int width = world.tiles().width(), height = world.tiles().height();
        grid = new PathGrid(width, height, blocks, rules);
        grid.refreshAll(world);
        engine = new FieldEngine(grid, null);
        dirtyBits = new Bits(grid.size);
        mark = new int[grid.size];
        rebuildSets();
    }

    /** Whether the world's content defines a path table. An inactive service refuses field requests. */
    public boolean active(){
        return blocks != null;
    }

    // rules

    /** The rules in effect since the last flush. */
    public PathRules rules(){
        return rules;
    }

    /** Sets the rules; they take effect at the next flush. */
    public void rules(PathRules rules){
        pending = Objects.requireNonNull(rules, "rules");
    }

    // fields

    /** A handle on {@code team}'s wave-AI field for {@code cls} (vanilla's {@code EnemyCoreField}), counting a reference. */
    public int field(int team, PathCost cls){
        return acquire(team, cls, ENEMY_CORES);
    }

    /** A handle on {@code team}'s RTS field for {@code cls} towards tile {@code pos}, counting a reference. */
    public int field(int team, PathCost cls, int pos){
        requireActive();
        if(pos < 0 || pos >= grid.size) throw new IndexOutOfBoundsException("tile " + pos + " is outside the map");
        return acquire(team, cls, pos);
    }

    private int acquire(int team, PathCost cls, int target){
        requireActive();
        if(team < 0 || team > 255) throw new IllegalArgumentException("team " + team);
        Objects.requireNonNull(cls, "cls");
        Integer existing = byKey.get(Flowfield.key(team, cls, target));
        if(existing != null){
            slots.get(existing).refs++;
            return existing;
        }
        var field = new Flowfield(team, cls, target, grid.size);
        field.refs = 1;
        layers(field);
        int handle;
        if(free.isEmpty()){
            handle = slots.size();
            slots.add(field);
        }else{
            handle = free.pop();
            slots.set(handle, field);
        }
        byKey.put(field.key(), handle);
        if(field.rts){
            field.targets.add(target);
            engine.start(field);
            queue.add(handle);
        }
        return handle;
    }

    /** Drops a reference taken by {@code field}. */
    public void release(int handle){
        Flowfield field = get(handle);
        if(field.refs <= 0) throw new IllegalStateException("field " + handle + " has no references to release");
        if(--field.refs == 0) field.released = now;
    }

    /** Whether the field is complete. A building RTS field already answers for the tiles it has settled. */
    public boolean ready(int handle){
        return get(handle).state == State.READY;
    }

    /** Live fields. */
    public int fields(){
        return byKey.size();
    }

    /** Vanilla's weight of {@code pos}: its distance to the nearest target, or -1 if no target reaches it (yet). */
    public int distance(int handle, int pos){
        return get(handle).weight(pos);
    }

    /** Whether the field's units may step onto {@code pos} ({@code Flowfield.passable}, {@code ControlPathfinder.passable}). */
    public boolean passable(int handle, int pos){
        Flowfield field = get(handle);
        return PathCost.passable(field.cls, field.rts, engine.baseCost(field, pos));
    }

    /**
     * The tile a unit on {@code pos} should move to next: {@code Pathfinder.getTargetTile} ({@code Pathfinder.java:382})
     * without avoidance. It takes the 8-neighbour with the lowest weight below the unit's own, never cuts a corner past
     * an impassable tile, and never steps a ground unit from a safe tile onto a dangerous one. Returns {@code pos} to
     * stay. On a tile no target reaches the unit stays, as vanilla's -1 weights make it.
     */
    public int next(int handle, int pos){
        return next(handle, pos, null, 0);
    }

    /**
     * {@link #next(int, int)} for a unit that avoids others ({@code getTargetTile}'s {@code avoidanceId}): a
     * neighbour that the avoidance grid marks for a unit of lower id than {@code avoidanceId} is never a candidate.
     * The grid is asked for ({@link AvoidanceProcess#getAvoidance()}, which activates it) only where vanilla asks: past
     * the early returns, and only for an id above 0.
     */
    public int next(int handle, int pos, AvoidanceProcess avoidance, int avoidanceId){
        Flowfield field = get(handle);
        if(field.state == State.PENDING || field.targets.isEmpty() || !field.settled(pos)) return pos;
        int[] avoid = avoidanceId <= 0 || avoidance == null ? null : avoidance.getAvoidance();
        int width = grid.width, height = grid.height, x = pos % width, y = pos / width, value = field.weight(pos);
        int current = -1, best = 0;
        for(int i = 0; i < 8; i++){
            int dx = D8X[i], dy = D8Y[i], ox = x + dx, oy = y + dy;
            if(ox < 0 || oy < 0 || ox >= width || oy >= height) continue;
            int other = ox + oy * width;
            // a tile a lower-id unit covers costs one more, and is never taken
            if(avoid != null && avoid[other] > Integer.MAX_VALUE - avoidanceId) continue;
            // an RTS field still building hasn't decided its frontier yet: not a candidate
            int cost = field.settled(other) ? field.weight(other) : Integer.MAX_VALUE;
            if(cost < value && (current == -1 || cost < best) && passable(field, other)
                && !(dx != 0 && dy != 0 && (!passable(field, ox + y * width) || !passable(field, x + oy * width)))){ // diagonal corner trap
                current = other;
                best = cost;
            }
        }
        boolean ground = !field.rts && (field.cls == GROUND || field.cls == GROUND_LARGE);
        if(current == -1 || best == IMPASSABLE
            || (ground && PathTile.is(grid.tiles[current], PathTile.DANGEROUS) && !PathTile.is(grid.tiles[pos], PathTile.DANGEROUS))){
            return pos;
        }
        return current;
    }

    private boolean passable(Flowfield field, int pos){
        return PathCost.passable(field.cls, field.rts, engine.baseCost(field, pos));
    }

    // line of sight, with RTS costs (ControlPathfinder.java:1374)

    /**
     * Whether a straight walk from tile {@code from} to tile {@code to} avoids every tile costing more than open
     * ground: {@code !ControlPathfinder.raycast}, a diagonal Bresenham walk.
     */
    public boolean lineOfSight(int team, PathCost cls, int from, int to){
        requireActive();
        int width = grid.width, height = grid.height;
        int x = from % width, x2 = to % width, dx = Math.abs(x2 - x), sx = x < x2 ? 1 : -1;
        int y = from / width, y2 = to / width, dy = Math.abs(y2 - y), sy = y < y2 ? 1 : -1;
        int err = dx - dy;
        while(x >= 0 && y >= 0 && x < width && y < height){
            int cost = engine.rtsCost(team, cls, x + y * width);
            if(cost == IMPASSABLE || cost >= 2) return false;
            if(x == x2 && y == y2) return true;
            int e2 = 2 * err;
            if(e2 > -dy){
                err -= dy;
                x += sx;
            }
            if(e2 < dx){
                err += dx;
                y += sy;
            }
        }
        return false;
    }

    /**
     * Whether a unit whose hitbox spans {@code rectSize} world units can move along the segment from (startX, startY)
     * to (endX, endY), walking tiles {@code from} to {@code to}: {@code !ControlPathfinder.raycastRect}
     * ({@code :1432}). A tile blocks when it costs at least {@code max(50, cost(from) + 1)} (capped at the solid cap);
     * a blocking side neighbour blocks only when the segment crosses its rectangle.
     */
    public boolean lineOfSight(int team, PathCost cls, int from, int to, float startX, float startY, float endX, float endY, float rectSize){
        requireActive();
        int width = grid.width, height = grid.height;
        int initial = PathCost.control(cls, team, grid.tiles[from]);
        int x = from % width, x2 = to % width, dx = Math.abs(x2 - x), sx = x < x2 ? 1 : -1;
        int y = from / width, y2 = to / width, dy = Math.abs(y2 - y), sy = y < y2 ? 1 : -1;
        int err = dx - dy;
        while(x >= 0 && y >= 0 && x < width && y < height){
            if(!nearPassable(initial, team, cls, x + y * width)
                || overlap(initial, team, cls, x + 1, y, startX, startY, endX, endY, rectSize)
                || overlap(initial, team, cls, x - 1, y, startX, startY, endX, endY, rectSize)
                || overlap(initial, team, cls, x, y + 1, startX, startY, endX, endY, rectSize)
                || overlap(initial, team, cls, x, y - 1, startX, startY, endX, endY, rectSize)){
                return false;
            }
            if(x == x2 && y == y2) return true;
            int e2 = 2 * err;
            if(e2 > -dy){
                err -= dy;
                x += sx;
            }
            if(e2 < dx){
                err += dx;
                y += sy;
            }
        }
        return false;
    }

    private boolean nearPassable(int initial, int team, PathCost cls, int pos){
        int amount = PathCost.control(cls, team, grid.tiles[pos]);
        return amount != IMPASSABLE && amount < Math.min(Math.max(50, initial + 1), SOLID_CAP);
    }

    private boolean overlap(int initial, int team, PathCost cls, int x, int y, float startX, float startY, float endX, float endY, float rectSize){
        if(x < 0 || y < 0 || x >= grid.width || y >= grid.height) return false;
        if(!nearPassable(initial, team, cls, x + y * grid.width)){
            return intersectSegmentRectangleFast(startX, startY, endX, endY, x * TILE - rectSize / 2f, y * TILE - rectSize / 2f, rectSize, rectSize);
        }
        return false;
    }

    /** Arc's {@code Intersector.intersectSegmentRectangleFast}. */
    private static boolean intersectSegmentRectangleFast(float startX, float startY, float endX, float endY, float rectX, float rectY, float rectW, float rectH){
        float deltaX = endX - startX, deltaY = endY - startY, x = rectX + rectW / 2, y = rectY + rectH / 2, halfX = rectW / 2f, halfY = rectH / 2f;
        float scaleX = 1.0f / deltaX, scaleY = 1.0f / deltaY;
        int signX = Mathf.sign(scaleX), signY = Mathf.sign(scaleY);
        float nearTimeX = (x - signX * halfX - startX) * scaleX, nearTimeY = (y - signY * halfY - startY) * scaleY;
        float farTimeX = (x + signX * halfX - startX) * scaleX, farTimeY = (y + signY * halfY - startY) * scaleY;
        return nearTimeX < farTimeY && nearTimeY < farTimeX && Math.max(nearTimeX, nearTimeY) < 1 && Math.min(farTimeX, farTimeY) > 0;
    }

    // changes

    /**
     * Queues tile {@code pos} for the next flush: its block, floor, overlay, team, building or solidity changed. The
     * PATH_FLUSH handler feeds it from the tile grid's change list; a building whose solidity changes without a tile
     * change (a door) calls it directly.
     */
    public void tileChanged(int pos){
        if(blocks == null) return;
        if(!dirtyBits.getAndSet(pos)) dirty.add(pos);
    }

    /** Re-reads every tile at the next flush and rebuilds every field: after a map load. */
    public void invalidateAll(){
        invalidated = true;
    }

    /**
     * Runs the full refresh that {@link #invalidateAll} queued, and any pending rules, now rather than in the next
     * {@code PATH_FLUSH}. A host calls it between ticks, off the tick threads, before a freshly loaded world first
     * ticks: on a big map the refresh takes tens of ms. Between ticks the tick's change list is empty, so this is
     * the same work the first tick's flush would have done, and the hash after that tick is the same.
     */
    public void settle(World world){
        flush(world);
    }

    /** The packed path tile at {@code pos}, as of the last flush. */
    public int packed(int pos){
        requireActive();
        return grid.tiles[pos];
    }

    /**
     * Applies queued changes and rules, then builds new wave-AI fields and spends the RTS budget. Runs once per tick
     * in {@link Phase#PATH_FLUSH}.
     */
    public void flush(World world){
        if(blocks == null) return;
        now = world.ticks();
        expire();
        reloaded = invalidated;
        boolean full = invalidated, layers = false, targets = false;
        if(pending != null){
            PathRules next = pending;
            pending = null;
            if(!next.equals(rules)){
                full = full || next.coreCapture() != rules.coreCapture();
                layers = !sameCosts(next, rules);
                targets = true;
                rules = next;
                grid.rules = next;
                if(next.threatPaths() != (threat != null)) setThreat(next.threatPaths());
            }
        }
        if(full || dirty.size > grid.size / FULL_DIVISOR){
            refresh(world);
        }else{
            apply(world, layers, targets);
        }
        for(Flowfield field : slots){
            if(field != null && field.state == State.PENDING){
                // requested under the rules before this flush's
                layers(field);
                field.targets = targetsOf(world, field);
                engine.build(field);
            }
        }
        spendBudget();
    }

    /** Whether two rule sets price every tile alike for every field. */
    private static boolean sameCosts(PathRules a, PathRules b){
        return a.sizeAwarePaths() == b.sizeAwarePaths() && a.threatPaths() == b.threatPaths() && a.limitMapArea() == b.limitMapArea()
            && a.limitX() == b.limitX() && a.limitY() == b.limitY() && a.limitWidth() == b.limitWidth() && a.limitHeight() == b.limitHeight()
            && a.waves() == b.waves() && a.attackMode() == b.attackMode() && a.pvp() == b.pvp() && a.defaultTeam() == b.defaultTeam();
    }

    private void setThreat(boolean on){
        threat = on ? new Threat(grid.width, grid.height) : null;
        if(on){
            for(int i = 0; i < turrets.size; i++){
                int pos = turrets.get(i);
                long view = grid.view[pos];
                threat.stamp(PathGrid.team(view), pos, blocks.turretRange(PathGrid.block(view)), 1, null);
            }
        }
        engine.threat = threat;
    }

    private void layers(Flowfield field){
        field.large = field.cls == GROUND_LARGE && rules.sizeAwarePaths();
        field.threat = !field.rts && rules.threatPaths();
    }

    /** Re-reads the whole world and rebuilds every field. Building RTS fields start over. */
    private void refresh(World world){
        fallbacks++;
        grid.refreshAll(world);
        for(int i = 0; i < dirty.size; i++) dirtyBits.clear(dirty.get(i));
        dirty.clear();
        invalidated = false;
        rebuildSets();
        setThreat(rules.threatPaths());
        for(Flowfield field : slots){
            if(field == null) continue;
            layers(field);
            switch(field.state){
                case READY -> {
                    if(!field.rts) field.targets = targetsOf(world, field);
                    engine.build(field);
                }
                case BUILDING -> engine.start(field);
                case PENDING -> {
                }
            }
        }
    }

    /** One incremental batch: the queued tiles, then every field's repair. */
    private void apply(World world, boolean layers, boolean targets){
        // 1. the view: what changed since the last flush
        changed.clear();
        for(int i = 0; i < dirty.size; i++){
            int pos = dirty.get(i);
            dirtyBits.clear(pos);
            long after = grid.read(world, pos), before = grid.view[pos];
            if(after == before) continue;
            if(changed.size == oldViews.length) oldViews = Arrays.copyOf(oldViews, oldViews.length * 2);
            oldViews[changed.size] = before;
            changed.add(pos);
            grid.view[pos] = after;
        }
        dirty.clear();
        if(changed.isEmpty() && !layers && !targets) return;

        // 2. the packed tiles of every 3x3 ring around a change (not written yet)
        int gen = nextMark();
        ring.clear();
        ring(changed, gen);
        packedPos.clear();
        packedNew.clear();
        for(int i = 0; i < ring.size; i++){
            int pos = ring.get(i), packed = grid.pack(pos);
            if(packed != grid.tiles[pos]){
                packedPos.add(pos);
                packedNew.add(packed);
            }
        }

        // 3. target sources and turrets; threat stamps wait until the old costs are taken
        boolean sources = targets;
        gen = nextMark();
        candidates.clear();
        for(int i = 0; i < packedPos.size; i++) mark(candidates, packedPos.get(i), gen);
        for(int i = 0; i < changed.size; i++){
            int pos = changed.get(i);
            sources |= updateSets(pos, oldViews[i], grid.view[pos]);
            if(threat != null){
                if(turret(oldViews[i])) threat.covered(pos, blocks.turretRange(PathGrid.block(oldViews[i])), wide);
                if(turret(grid.view[pos])) threat.covered(pos, blocks.turretRange(PathGrid.block(grid.view[pos])), wide);
                for(int j = 0; j < wide.size; j++) mark(candidates, wide.get(j), gen);
                wide.clear();
            }
        }
        if(rules.randomWaveAI() && !changed.isEmpty()) sources = true;
        // tiles whose clearance may move: the candidates and their 8 neighbours
        wide.clear();
        boolean anyLarge = false;
        for(Flowfield field : slots) anyLarge |= field != null && engine.reach(field) > 0;
        if(anyLarge){
            int wideGen = nextMark();
            for(int i = 0; i < candidates.size; i++){
                int pos = candidates.get(i), x = pos % grid.width, y = pos / grid.width;
                for(int dy = -1; dy <= 1; dy++){
                    for(int dx = -1; dx <= 1; dx++){
                        int ox = x + dx, oy = y + dy;
                        if(ox >= 0 && oy >= 0 && ox < grid.width && oy < grid.height) mark(wide, ox + oy * grid.width, wideGen);
                    }
                }
            }
        }

        // 4. every field's old costs over the tiles it may see change
        int limit = grid.size / FULL_DIVISOR;
        var oldCosts = new int[slots.size()][];
        for(int s = 0; s < slots.size(); s++){
            Flowfield field = slots.get(s);
            if(field == null || field.state != State.READY || layers) continue;
            IntSeq tiles = engine.reach(field) > 0 ? wide : candidates;
            if(tiles.size > limit) continue;
            int[] costs = oldCosts[s] = new int[tiles.size];
            for(int i = 0; i < tiles.size; i++) costs[i] = engine.cost(field, tiles.get(i));
        }

        // 5. write the new packed tiles and stamps
        for(int i = 0; i < packedPos.size; i++) grid.tiles[packedPos.get(i)] = packedNew.get(i);
        if(threat != null){
            for(int i = 0; i < changed.size; i++){
                int pos = changed.get(i);
                long before = oldViews[i], after = grid.view[pos];
                if(turret(before)) threat.stamp(PathGrid.team(before), pos, blocks.turretRange(PathGrid.block(before)), -1, null);
                if(turret(after)) threat.stamp(PathGrid.team(after), pos, blocks.turretRange(PathGrid.block(after)), 1, null);
            }
        }

        // 6. repair, rebuild, or restart
        for(int s = 0; s < slots.size(); s++){
            Flowfield field = slots.get(s);
            if(field == null || field.state == State.PENDING) continue;
            if(layers) layers(field);
            IntSeq tiles = engine.reach(field) > 0 ? wide : candidates;
            if(field.state == State.BUILDING){
                // a build that has looked at a changed tile can't go on: finish it now on the new costs
                if(layers || touched(field, tiles)) engine.build(field);
                continue;
            }
            added.clear();
            removed.clear();
            if(!field.rts && sources){
                IntSeq next = targetsOf(world, field);
                diff(field.targets, next, added, removed);
                field.targets = next;
            }
            if(oldCosts[s] == null || tiles.size + added.size + removed.size > limit){
                if(layers || !tiles.isEmpty() || !added.isEmpty() || !removed.isEmpty()){
                    fallbacks++;
                    engine.build(field);
                }
            }else if(!tiles.isEmpty() || !added.isEmpty() || !removed.isEmpty()){
                engine.repair(field, tiles, oldCosts[s], added, removed);
            }
        }
    }

    private void ring(IntSeq centres, int gen){
        for(int i = 0; i < centres.size; i++){
            int pos = centres.get(i), x = pos % grid.width, y = pos / grid.width;
            for(int dy = -1; dy <= 1; dy++){
                for(int dx = -1; dx <= 1; dx++){
                    int ox = x + dx, oy = y + dy;
                    if(ox >= 0 && oy >= 0 && ox < grid.width && oy < grid.height) mark(ring, ox + oy * grid.width, gen);
                }
            }
        }
    }

    private int nextMark(){
        if(++markGen == Integer.MAX_VALUE){
            Arrays.fill(mark, 0);
            markGen = 1;
        }
        return markGen;
    }

    private void mark(IntSeq out, int pos, int gen){
        if(mark[pos] != gen){
            mark[pos] = gen;
            out.add(pos);
        }
    }

    /** Whether a building RTS field has evaluated, or may have evaluated, the cost of any of {@code tiles}. */
    private boolean touched(Flowfield field, IntSeq tiles){
        int width = grid.width, height = grid.height;
        for(int i = 0; i < tiles.size; i++){
            int pos = tiles.get(i), x = pos % width, y = pos / width;
            if(field.dist[pos] != INF) return true;
            for(int d = 0; d < 4; d++){
                int ox = x + PathGrid.DX[d], oy = y + PathGrid.DY[d];
                if(ox >= 0 && oy >= 0 && ox < width && oy < height && field.dist[ox + oy * width] != INF) return true;
            }
        }
        return false;
    }

    /** Spends the per-tick pop budget on building RTS fields, in request order. */
    private void spendBudget(){
        long budget = rules.rtsPopBudget();
        for(int i = 0; i < queue.size && budget > 0; ){
            Flowfield field = slots.get(queue.get(i));
            if(field.state == State.BUILDING) budget -= engine.step(field, budget);
            if(field.state != State.BUILDING) queue.removeIndex(i);
            else i++;
        }
    }

    /** Frees RTS fields nobody has held for {@value #EXPIRY_TICKS} ticks. */
    private void expire(){
        for(int handle = 0; handle < slots.size(); handle++){
            Flowfield field = slots.get(handle);
            if(field == null || !field.rts || field.refs > 0 || now - field.released < EXPIRY_TICKS) continue;
            byKey.remove(field.key());
            slots.set(handle, null);
            free.add(handle);
            queue.removeValue(handle);
        }
    }

    // target sources

    private boolean core(long view){
        return PathGrid.center(view) && blocks.is(PathGrid.block(view), PathBlocks.CORE);
    }

    private boolean turret(long view){
        return PathGrid.center(view) && PathGrid.team(view) != 0 && blocks.turretRange(PathGrid.block(view)) > 0f;
    }

    private void rebuildSets(){
        cores.clear();
        spawns.clear();
        turrets.clear();
        for(int pos = 0; pos < grid.size; pos++){
            long view = grid.view[pos];
            if(core(view)) cores.add(pos);
            if(PathGrid.onSpawn(view)) spawns.add(pos);
            if(turret(view)) turrets.add(pos);
        }
    }

    /** Moves {@code pos} between the sorted sets; true if the core or spawn sets (or a core's team) changed. */
    private boolean updateSets(int pos, long before, long after){
        boolean sources = false;
        if(core(before) != core(after) || (core(after) && PathGrid.team(before) != PathGrid.team(after))){
            setMember(cores, pos, core(after));
            sources = true;
        }
        if(PathGrid.onSpawn(before) != PathGrid.onSpawn(after)){
            setMember(spawns, pos, PathGrid.onSpawn(after));
            sources = true;
        }
        if(turret(before) != turret(after)) setMember(turrets, pos, turret(after));
        return sources;
    }

    private static void setMember(IntSeq sorted, int value, boolean member){
        int at = Arrays.binarySearch(sorted.items, 0, sorted.size, value);
        if(member && at < 0) sorted.insert(-at - 1, value);
        else if(!member && at >= 0) sorted.removeIndex(at);
    }

    /** {@code EnemyCoreField.getPositions} ({@code Pathfinder.java:555}), sorted and distinct. */
    IntSeq targetsOf(World world, Flowfield field){
        var out = new IntSeq();
        int team = field.team;
        if(rules.randomWaveAI() && team == rules.waveTeam()) randomTargets(world, team, out);
        for(int i = 0; i < cores.size; i++){
            int pos = cores.get(i), other = PathGrid.team(grid.view[pos]);
            if(other == team || (other == 0 && !rules.coreCapture())) continue;
            out.add(pos);
        }
        // spawn points are also enemies
        if(rules.waves() && team == rules.defaultTeam()) out.addAll(spawns);
        out.sort();
        int distinct = 0;
        for(int i = 0; i < out.size; i++){
            if(i == 0 || out.items[i] != out.items[distinct - 1]) out.items[distinct++] = out.items[i];
        }
        out.size = distinct;
        return out;
    }

    /**
     * The {@code randomWaveAI} targets: up to five draws of a building flag; the first flag whose enemy buildings
     * include an active, targetable one adds all of those. Two deviations: vanilla seeds the draw with the field's
     * identity hash outside wave mode, which Shard drops; and {@code status() != noInput} is approximated by
     * {@code efficiency > 0} until the building base ports {@code status()}.
     */
    private void randomTargets(World world, int team, IntSeq out){
        // quirk: vanilla adds the field's hashCode() to the non-wave seed (Pathfinder.java:557)
        var rand = new Rand(rules.waves() ? rules.wave() : (int)(now / 5400));
        Buildings buildings = world.buildings();
        int max = 1;
        for(int attempt = 0; attempt < 5 && max > 0; attempt++){
            int flag = 1 << (PathBlocks.RANDOM_TARGET_SHIFT + rand.random(PathBlocks.RANDOM_TARGET_FLAGS - 1));
            boolean any = false;
            for(int i = 0; i < buildings.indexLimit(); i++){
                Building other = buildings.get(i);
                if(other == null || other.team == team || (other.team == 0 && !rules.coreCapture())) continue;
                int block = other.block().id();
                if(!blocks.is(block, flag) || !blocks.is(block, PathBlocks.TARGETABLE)) continue;
                if((other.items != null && other.items.total > 0) || other.efficiency > 0f){
                    out.add(other.tile());
                    any = true;
                }
            }
            if(any) max--;
        }
    }

    /** The sorted differences between two sorted sets. */
    private static void diff(IntSeq before, IntSeq after, IntSeq added, IntSeq removed){
        int i = 0, j = 0;
        while(i < before.size || j < after.size){
            if(j >= after.size || (i < before.size && before.items[i] < after.items[j])) removed.add(before.items[i++]);
            else if(i >= before.size || after.items[j] < before.items[i]) added.add(after.items[j++]);
            else{
                i++;
                j++;
            }
        }
    }

    Flowfield get(int handle){
        Flowfield field = handle >= 0 && handle < slots.size() ? slots.get(handle) : null;
        if(field == null) throw new IllegalArgumentException("no field " + handle);
        return field;
    }

    // for the tests in this package

    PathGrid grid(){
        return grid;
    }

    FieldEngine engine(){
        return engine;
    }

    Threat threat(){
        return threat;
    }

    List<Flowfield> slots(){
        return Collections.unmodifiableList(slots);
    }

    private void requireActive(){
        if(blocks == null) throw new IllegalStateException("this world's content defines no path table (PathBlocks)");
    }

    // world part

    @Override
    public Persistence persistence(){
        return blocks == null ? Persistence.DERIVED : Persistence.AUTHORITATIVE;
    }

    @Override
    public void rebuild(World world){
        // only an inactive service is derived, and it holds nothing
        init(world);
    }

    @Override
    public WorldPart copy(CopyContext context){
        var out = new PathService();
        out.blocks = blocks;
        out.rules = rules;
        out.pending = pending;
        if(blocks == null) return out;
        out.grid = new PathGrid(grid);
        out.threat = threat == null ? null : threat.copy();
        out.engine = new FieldEngine(out.grid, out.threat);
        for(Flowfield field : slots) out.slots.add(field == null ? null : field.copy());
        out.byKey.putAll(byKey);
        out.free.addAll(free);
        out.queue.addAll(queue);
        out.dirty.addAll(dirty);
        out.dirtyBits = dirtyBits.copy();
        out.invalidated = invalidated;
        out.now = now;
        out.cores.addAll(cores);
        out.spawns.addAll(spawns);
        out.turrets.addAll(turrets);
        out.mark = new int[grid.size];
        return out;
    }

    @Override
    public void hash(Hasher hasher){
        if(blocks == null) return;
        rules.hash(hasher);
        hasher.bool(pending != null);
        if(pending != null) pending.hash(hasher);
        hasher.l(now).bool(invalidated).ints(dirty.items, 0, dirty.size);
        grid.hash(hasher);
        hasher.i(slots.size());
        for(Flowfield field : slots){
            hasher.bool(field != null);
            if(field == null) continue;
            hasher.l(field.key()).i(field.state.ordinal()).i(field.refs).l(field.released).l(field.pops)
                .ints(field.targets.items, 0, field.targets.size);
        }
        hasher.ints(free.items, 0, free.size).ints(queue.items, 0, queue.size);
    }

    // checkpoints

    @Override
    public String section(){
        return "paths";
    }

    /**
     * Writes the authoritative part at a tick boundary. The view is stored as the tiles where it differs from what
     * {@code world} holds now, so a quiet world stores almost nothing.
     */
    @Override
    public void write(World world, Writes out){
        out.i(VERSION);
        out.bool(blocks != null);
        if(blocks == null) return;
        rules.write(out);
        out.bool(pending != null);
        if(pending != null) pending.write(out);
        out.l(now);
        out.bool(invalidated);
        out.i(dirty.size);
        for(int i = 0; i < dirty.size; i++) out.i(dirty.get(i));
        var differs = new IntSeq();
        for(int pos = 0; pos < grid.size; pos++){
            if(grid.view[pos] != grid.read(world, pos)) differs.add(pos);
        }
        out.i(differs.size);
        for(int i = 0; i < differs.size; i++){
            out.i(differs.get(i));
            out.l(grid.view[differs.get(i)]);
        }
        out.i(slots.size());
        for(Flowfield field : slots){
            out.bool(field != null);
            if(field == null) continue;
            out.b(field.team);
            out.b(field.cls.ordinal());
            out.i(field.target);
            out.b(field.state.ordinal());
            out.i(field.refs);
            out.l(field.released);
            out.l(field.pops);
            out.i(field.targets.size);
            for(int i = 0; i < field.targets.size; i++) out.i(field.targets.get(i));
        }
        out.i(free.size);
        for(int i = 0; i < free.size; i++) out.i(free.get(i));
        out.i(queue.size);
        for(int i = 0; i < queue.size; i++) out.i(queue.get(i));
    }

    /**
     * Restores what {@link #write} stored, on a part freshly {@link #init}ed for {@code world}, whose tiles and
     * buildings are already restored, and rebuilds the derived state exactly.
     */
    @Override
    public void read(World world, Reads in){
        int version = in.i();
        if(version != VERSION) throw new IllegalArgumentException("path checkpoint version " + version + ", expected " + VERSION);
        boolean active = in.bool();
        // start from nothing: every derived structure comes from what is read here and from the world
        slots.clear();
        byKey.clear();
        free.clear();
        queue.clear();
        dirty.clear();
        rules = PathRules.DEFAULT;
        pending = null;
        threat = null;
        init(world);
        if(active != (blocks != null)) throw new IllegalStateException("checkpoint and world disagree on whether paths are active");
        if(!active) return;
        rules = PathRules.read(in);
        pending = in.bool() ? PathRules.read(in) : null;
        grid.rules = rules;
        now = in.l();
        invalidated = in.bool();
        for(int i = in.i(); i > 0; i--) tileChanged(in.i());
        for(int pos = 0; pos < grid.size; pos++) grid.view[pos] = grid.read(world, pos);
        for(int i = in.i(); i > 0; i--){
            int pos = in.i();
            grid.view[pos] = in.l();
        }
        grid.packAll();
        rebuildSets();
        setThreat(rules.threatPaths());
        int count = in.i();
        for(int handle = 0; handle < count; handle++){
            if(!in.bool()){
                slots.add(null);
                continue;
            }
            int team = in.ub(), cls = in.ub(), target = in.i();
            var field = new Flowfield(team, PathCost.of(cls), target, grid.size);
            State state = State.values()[in.ub()];
            field.refs = in.i();
            field.released = in.l();
            long pops = in.l();
            for(int i = in.i(); i > 0; i--) field.targets.add(in.i());
            layers(field);
            switch(state){
                case READY -> engine.build(field);
                case BUILDING -> {
                    engine.start(field);
                    engine.step(field, pops);
                }
                case PENDING -> {
                }
            }
            if(field.state != state || field.pops != pops){
                throw new IllegalStateException("field " + handle + " rebuilt to " + field.state + " after " + field.pops
                    + " pops; stored " + state + " after " + pops);
            }
            slots.add(field);
            byKey.put(field.key(), handle);
        }
        for(int i = in.i(); i > 0; i--) free.add(in.i());
        for(int i = in.i(); i > 0; i--) queue.add(in.i());
    }
}
