package com.xpdustry.shard.sim.path;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

import static com.xpdustry.shard.sim.path.PathCost.*;

/**
 * Vanilla's {@code ControlPathfinder} ({@code ControlPathfinder.java}): the RTS pathfinder that {@code CommandAI}'s
 * move and {@code LogicAI}'s {@code pathfind} ask (WP71). It is hierarchical:
 * <ul>
 *   <li>the map is cut into clusters of {@value #CLUSTER_SIZE}×{@value #CLUSTER_SIZE} tiles, built lazily per team
 *   and cost type. Each cluster edge has portals, runs of tiles open on both sides; A* inside the cluster connects
 *   its portals ({@code updateCluster}, {@code updateInnerEdges});</li>
 *   <li>a query finds the portal nearest the unit and the one nearest the goal, then A* over the portal graph picks
 *   the clusters the path crosses ({@code initializePathRequest});</li>
 *   <li>one flow field per (goal, cost type, team), over those clusters only, steers the unit; a later query towards
 *   the same goal adds its clusters to the same field;</li>
 *   <li>a tile change re-derives the clusters it touches, and the paths whose fields cover them are recomputed.</li>
 * </ul>
 * {@link #pathPosition} is {@code getPathPosition}: a straight line when nothing costly is in the way, else the
 * farthest tile down the field in clear sight, up to 30 tiles ahead.
 *
 * <p><b>Timing</b> (ARCHITECTURE.md §1, "Wall-clock and thread timing"). Vanilla runs the searches on a thread of its
 * own, which wakes every {@code 1000 / 30} ms, gives each field 12 ms of wall time per wake, rechecks invalidated
 * paths when more than 1,000 ms have passed, and lets a unit move its goal by up to 4 tiles without a new search
 * within 1,000 ms. Shard runs the same work on the tick, at vanilla's nominal schedule:
 * <ul>
 *   <li>one pass of the thread's loop every {@value #PASS_TICKS} ticks, in {@link Phase#PATH_FLUSH}
 *   ({@link ControlFlush}), after the tick's tile changes have reached the path grid;</li>
 *   <li>the invalid check when more than {@value #INVALIDATE_TICKS} ticks have passed since the last, the first
 *   {@value #INVALIDATE_TICKS} ticks after the start, as vanilla's first is 1,000 ms late;</li>
 *   <li>{@value #MAX_STEPS} steps of {@value #STEP_INTERVAL} + 1 field pops per field per pass, where vanilla reads
 *   the clock: 12 ms at the rate vanilla's own searches ran on a boat VM ({@code ControlTimingsTest});</li>
 *   <li>{@value #RECOMPUTE_TICKS} ticks for the recompute window.</li>
 * </ul>
 * What the main thread posts to vanilla's thread (a new request, a missing field cluster, a tile's clusters) waits
 * for the next pass, as it does in vanilla; what the thread posts back ({@code Core.app.post}: the field list) is
 * applied at once, since nothing reads it before vanilla's next frame would. The per-frame clean-up vanilla hangs on
 * {@code Trigger.update} runs right after the pass. Oracles pin vanilla to this schedule ({@code VanillaControlPath}).
 *
 * <p><b>Order.</b> Vanilla keys requests by unit in an {@code ObjectMap} and collects invalid ones in an
 * {@code ObjectSet}, both iterated in identity-hash order, which changes between JVM runs. Shard keeps both in
 * insertion order (Arc's {@code OrderedMap} and {@code OrderedSet}, which the oracles pin vanilla to). The cluster
 * update sets are Arc's {@code IntSet} ({@link IntSet}), so they iterate as vanilla's.
 *
 * <p><b>A fixed leak.</b> When a unit's goal moves, vanilla replaces its request and marks the old one for removal by
 * setting its {@code lastUpdateId} to -1000 (ControlPathfinder.java:1314), but the clean-up only visits the map the old
 * request was just replaced in, so the old request stays in the thread's list for the rest of the map: every change
 * to a cluster its field covered recomputes it again, for a goal nobody wants. Shard drops the replaced request at
 * the replacement, through the same queue the clean-up would have used.
 *
 * <p><b>State.</b> The part is {@link Persistence#AUTHORITATIVE}: clusters reflect the tiles as of their last
 * update, which a pending tile change makes stale until the next pass, and fields hold a search mid-way. A
 * checkpoint stores all of it ({@code control-paths} section); a fork copies it. On a world whose content has no
 * path table the part is inactive and {@link Persistence#DERIVED}.
 *
 * <p>Owner: WP71.
 */
@Register
public final class ControlPathfinder implements SectionPart{
    static final int CLUSTER_SIZE = 12;
    /** {@code costIdGround}, {@code costIdHover}, {@code costIdLegs}, {@code costIdNaval}. */
    public static final int COST_GROUND = 0, COST_HOVER = 1, COST_LEGS = 2, COST_NAVAL = 3;
    static final int COSTS = 4;
    /** {@code costTypes}, by cost id. */
    private static final PathCost[] COST_TYPES = {GROUND, HOVER, LEGS, NAVAL};
    /** {@code updateInterval}: {@code 1000 / updateFPS} ms, 33 ms at 30 passes a second. */
    static final int PASS_TICKS = 2;
    /** {@code invalidateCheckInterval}: 1,000 ms. */
    static final int INVALIDATE_TICKS = 60;
    /** {@code getPathPosition}'s recompute window: 1,000 ms. */
    static final int RECOMPUTE_TICKS = 60;
    /** {@code updateStepInterval}: field pops between two clock reads. */
    static final int STEP_INTERVAL = 200;
    /**
     * {@code maxUpdate}, 12 ms of field pops per field per pass, as steps of {@value #STEP_INTERVAL} + 1 pops: vanilla's
     * {@code updateFields} pops about 23,800 tiles a ms on a large boat VM ({@code ControlTimingsTest}).
     */
    static final int MAX_STEPS = 1400;
    /** A request that skipped this many updates is dropped; a field, {@value #FIELD_TIMEOUT}. */
    static final int REQUEST_TIMEOUT = 10, FIELD_TIMEOUT = 30;
    /** {@code getPathPosition}'s {@code maxIterations}. */
    static final int MAX_ITERATIONS = 30;
    private static final int VERSION = 2;
    private static final float TILESIZE = 8f;

    static final int[] OFFSETS = {
        1, 0, // right: bottom to top
        0, 1, // top: left to right
        0, 0, // left: bottom to top
        0, 0  // bottom: left to right
    };
    static final int[] MOVE_DIRS = {
        0, 1,
        1, 0,
        0, 1,
        1, 0
    };
    /** Also {@code Geometry.d4}. */
    static final int[] NEXT_OFFSETS = {
        1, 0,
        0, 1,
        -1, 0,
        0, -1
    };

    /** A request that can't be served: the destination is outside the map area. */
    private static final Result OUTSIDE = new Result(true, false, -1, 0f, 0f);
    /** Nothing to do this frame: a request was just posted. */
    private static final Result WAIT = new Result(false, false, -1, 0f, 0f);
    private static final Result UNREACHABLE = new Result(true, false, -1, 0f, 0f);

    private boolean active;
    private int width, height, cwidth, cheight;
    /** Team, then cost id, then cluster index; built lazily. */
    private Cluster[][][] clusters = new Cluster[256][][];
    /** {@code clustersToUpdate} and {@code clustersToInnerUpdate}: empty between passes, but their tables keep growing. */
    private IntSet clustersToUpdate = new IntSet(), clustersToInnerUpdate = new IntSet();
    /** The largest size each update set reached: their table sizes, which order their iteration. */
    private int updateHigh, innerHigh;
    /** {@code queue}: what the main thread and the last pass posted for the next pass. */
    private final ArrayList<Task> queue = new ArrayList<>();
    /** {@code unitRequests}, in insertion order; a replaced unit keeps its place. */
    private final ArrayList<Request> unitRequests = new ArrayList<>();
    private final TreeMap<Integer, Request> byUnit = new TreeMap<>();
    /** {@code threadPathRequests}: an unordered {@code Seq}, so a removal moves the last request into the gap. */
    private final ArrayList<Request> threadPathRequests = new ArrayList<>();
    /** {@code fields}, by {@link #fieldKey}. */
    private final TreeMap<Long, Field> fields = new TreeMap<>();
    /** {@code fieldList}: an unordered {@code Seq}. */
    private final ArrayList<Field> fieldList = new ArrayList<>();
    /** {@code invalidRequests}, in insertion order. */
    private final ArrayList<Request> invalidRequests = new ArrayList<>();
    /**
     * {@code state.updateId}: the frames the game ran before this one's {@code Trigger.update}, then one more once it ran
     * unpaused. A paused frame doesn't count it ({@code Logic.update} bumps it only when not paused), so it is the tick
     * less {@link #frozenTicks}. Derived from the tick and {@link #frozenTicks}.
     */
    private long updateId;
    /**
     * The thread's wall clock in ticks: the tick, then the next once the flush ran, paused or not. Vanilla's thread runs on
     * {@code Time.millis} through a pause, so its pass schedule, invalid check and recompute window ({@link #nextPass},
     * {@link #lastInvalidCheck}, {@code lastRecomputeTime}) read this, not {@link #updateId}. Derived from the tick.
     */
    private long clock;
    /** The paused ticks so far ({@link World#frozen()}), by which {@link #updateId} trails the tick. */
    private long frozenTicks;
    private boolean started;
    private long nextPass, lastInvalidCheck;
    /** {@code world.tileChanges}: it keys each request's raycast cache. */
    private int tileChanges = -1;
    /** Field pops per field per pass, in steps; 0 for no limit (oracles, which run vanilla's fields to the end). */
    private int steps = MAX_STEPS;
    private int nextId;
    /** Field pops since this part was made, for metrics and the budget's calibration; not state. */
    long pops;

    // bound at each entry point, never state
    private PathGrid grid;
    private FieldEngine engine;
    // scratch
    private final PathfindQueue innerFrontier = new PathfindQueue(), nodeFrontier = new PathfindQueue();
    private final IntIntMap innerCosts = new IntIntMap(), nodeCosts = new IntIntMap(), cameFrom = new IntIntMap();
    private static final int INFINITE = Float.floatToRawIntBits(Float.POSITIVE_INFINITY);

    public ControlPathfinder(){
    }

    /** The world's control pathfinder, or null where its content has no path table. */
    public static ControlPathfinder of(World world){
        ControlPathfinder control = world.find(ControlPathfinder.class);
        return control == null || !control.active ? null : control;
    }

    @Override
    public void init(World world){
        active = PathBlocks.of(world.content()) != null;
        width = world.tiles().width();
        height = world.tiles().height();
        cwidth = (width + CLUSTER_SIZE - 1) / CLUSTER_SIZE;
        cheight = (height + CLUSTER_SIZE - 1) / CLUSTER_SIZE;
    }

    public boolean active(){
        return active;
    }

    /** Vanilla's {@code pathCostId} of a cost class. */
    public static int costId(PathCost cls){
        return switch(cls){
            case GROUND, GROUND_LARGE -> COST_GROUND;
            case HOVER -> COST_HOVER;
            case LEGS -> COST_LEGS;
            case NAVAL -> COST_NAVAL;
        };
    }

    /** Field pops per field per pass, in steps of {@value #STEP_INTERVAL} + 1; 0 lifts the limit. */
    public void steps(int steps){
        if(steps < 0) throw new IllegalArgumentException("steps " + steps);
        this.steps = steps;
    }

    private void bind(World world){
        PathService paths = world.find(PathService.class);
        grid = paths.grid();
        engine = paths.engine();
    }

    private void requireActive(){
        if(!active) throw new IllegalStateException("this world's content defines no path table (PathBlocks)");
    }

    // region queries

    /**
     * What {@link #pathPosition(Unit, float, float, float, float)} answers: whether the goal can't be reached,
     * whether to move, and where to; {@code next} is the tile ({@code y * width + x}) a blocked straight line stopped
     * at, else -1.
     */
    public record Result(boolean unreachable, boolean move, int next, float x, float y){
    }

    /** {@code getPathPosition(unit, destination)}. */
    public Result pathPosition(Unit unit, float destX, float destY){
        return pathPosition(unit, destX, destY, destX, destY);
    }

    /**
     * {@code getPathPosition(unit, destination, mainDestination)} (ControlPathfinder.java:1132): where a ground unit
     * should head next to reach {@code dest}; its request, field and goal are {@code main}'s tile. The first call
     * for a goal posts a search and answers "don't move"; later calls follow the field once the next pass has built
     * it.
     */
    public Result pathPosition(Unit unit, float destX, float destY, float mainX, float mainY){
        requireActive();
        World world = unit.world();
        bind(world);
        TileGrid tiles = world.tiles();
        int costId = costId(pathCost(unit));
        int tileOn = unit.tileOn();
        int team = unit.team, tileX = unit.tileX(), tileY = unit.tileY(),
            packedPos = tileX + tileY * width,
            destTileX = toTile(mainX), destTileY = toTile(mainY),
            actualDestX = toTile(destX), actualDestY = toTile(destY),
            actualDestPos = actualDestX + actualDestY * width,
            initialCost = tileOn == -1 ? 0 : tcost(team, costId, tileOn),
            destPos = destTileX + destTileY * width;

        // do not allow commanding into areas outside the map bounds
        if(!inMapArea(actualDestX, actualDestY)) return OUTSIDE;

        Request request = byUnit.get(unit.id());
        // hitboxTile: the ray can't have thickness, so the tile rect has the tile size in it
        float tileRectSize = TILESIZE + Math.min(unit.hitSize * 0.66f, 7.8f);

        int lastRaycastTile = request == null || tileChanges != request.lastWorldUpdate ? -1 : request.lastRaycastTile;
        boolean raycastResult = request != null && request.lastRaycastResult;

        // cache raycast results to run every time the world updates, and every tile the unit crosses
        if(lastRaycastTile != packedPos){
            // near the destination, the permissive variant that ignores the edges of walls
            raycastResult = unit.within(destX, destY, TILESIZE * 2.5f)
                ? !raycastRect(initialCost, unit.x, unit.y, destX, destY, team, costId, tileX, tileY, actualDestX, actualDestY, tileRectSize)
                : !raycast(team, costId, tileX, tileY, actualDestX, actualDestY);
            if(request != null){
                request.lastRaycastTile = packedPos;
                request.lastRaycastResult = raycastResult;
                request.lastWorldUpdate = tileChanges;
            }
        }

        // if the destination can be trivially reached in a straight line, do that
        if(raycastResult) return new Result(false, true, -1, destX, destY);

        boolean any = false;
        long fieldKey = fieldKey(request != null ? request.destination : destPos, costId, team);

        // use the existing request if it exists; a goal may only move a little, twice a second
        if(request != null && (request.destination == destPos
            || (clock - request.lastRecomputeTime < RECOMPUTE_TICKS
            && Mathf.dst(destTileX, destTileY, request.destination % width, request.destination / width) <= 4f))){
            request.lastUpdateId = updateId;

            int initialTileOn = tileOn;
            Field fieldCache = fields.get(fieldKey);
            if(fieldCache == null) fieldCache = request.oldCache;

            if(fieldCache != null && tileOn != -1){
                Field old = request.oldCache;
                Field targetCache = old != null ? old : fieldCache;
                boolean requeue = old == null;
                // the old field can't be relevant once the new one is complete
                if(fieldCache != request.oldCache && fieldCache.frontier.isEmpty() && old != null){
                    request.oldCache = null;
                }

                fieldCache.lastUpdateId = updateId;
                int i = 0, next = -1;
                boolean recalc = false;
                boolean canDrown = unit.params().canDrown();

                if(packedPos == actualDestPos){
                    request.lastTargetTile = tileOn;
                }else if(initialTileOn != request.lastTile || request.lastTargetTile == -1){
                    boolean anyNearSolid = false;

                    // find the next tile until one near a solid block is discovered
                    while(i++ < MAX_ITERATIONS){
                        int onX = tileOn % width, onY = tileOn / width;
                        int value = getCost(targetCache, onX, onY, requeue);

                        int current = -1, minCost = 0;
                        for(int dir = 0; dir < 4; dir++){
                            int dx = onX + NEXT_OFFSETS[dir * 2], dy = onY + NEXT_OFFSETS[dir * 2 + 1];
                            if(dx < 0 || dy < 0 || dx >= width || dy >= height) continue;

                            int packed = dx + dy * width;
                            int otherCost = getCost(targetCache, dx, dy, requeue), relCost = otherCost - value;

                            if(relCost > 2 || otherCost <= 0) anyNearSolid = true;

                            if((value == 0 || otherCost < value) && otherCost != IMPASSABLE
                                && ((otherCost != 0 && (current == -1 || otherCost < minCost)) || packed == actualDestPos || packed == destPos)
                                && passable(team, costId, packed)){
                                current = packed;
                                minCost = otherCost;
                                // no need to keep searching
                                if(packed == destPos || packed == actualDestPos) break;
                            }
                        }

                        if(!(current == -1 || (canDrown && tiles.dangerous(current) && !tiles.dangerous(tileOn)))){
                            // when anyNearSolid is false, no solid tile was met so far, and a raycast is a waste
                            int curX = current % width, curY = current / width;
                            if(anyNearSolid && !(tiles.dangerous(tileOn) && costId == COST_GROUND)
                                && raycastRect(initialCost, unit.x, unit.y, curX * TILESIZE, curY * TILESIZE, team, costId,
                                initialTileOn % width, initialTileOn / width, curX, curY, tileRectSize)){
                                if(tileOn == initialTileOn){
                                    recalc = true;
                                    any = true;
                                }
                                next = current;
                                break;
                            }else{
                                tileOn = current;
                                any = true;
                                if(current == destPos || current == actualDestPos) break;
                            }
                        }else{
                            break;
                        }
                    }

                    request.lastTargetTile = any ? tileOn : -1;
                }

                if(request.lastTargetTile != -1){
                    request.lastTile = recalc ? -1 : initialTileOn;
                    return new Result(false, true, next, request.lastTargetTile % width * TILESIZE, request.lastTargetTile / width * TILESIZE);
                }
            }
        }else{
            // destroy the old one immediately, it's invalid now
            Field previousField = null;
            if(request != null){
                request.lastUpdateId = -1000;
                Field current = fields.get(fieldKey(request.destination, costId, team));
                if(current != null && current.frontier.isEmpty()){
                    previousField = current;
                }else if(request.oldCache != null){
                    previousField = request.oldCache;
                }else{
                    previousField = current;
                }
                // the fixed leak (class comment): the clean-up's own steps, which vanilla never reaches for this request
                request.invalidated = true;
                post(new Drop(request));
            }

            Request made = new Request(nextId++, unit.id(), unit, team, costId, destPos, updateId);
            putRequest(unit, made);
            made.oldCache = previousField;
            made.lastRecomputeTime = clock;
            // on the pathfinding thread: initialize the request
            post(new Start(made));
            return WAIT;
        }

        return request.notFound ? UNREACHABLE : WAIT;
    }

    /** {@code ControlPathfinder.isNearObstacle}: whether a straight walk between the tiles meets a costly tile. */
    public boolean isNearObstacle(Unit unit, int x1, int y1, int x2, int y2){
        requireActive();
        bind(unit.world());
        return raycast(unit.team, costId(pathCost(unit)), x1, y1, x2, y2);
    }

    /**
     * {@code raycastFastAvoid} (ControlPathfinder.java:1401), for {@code UnitGroup}: the first tile a 4-connected walk
     * from (x1, y1) to (x2, y2) should avoid, as {@code Point2.pack}, or 0.
     */
    public int raycastFastAvoid(World world, int team, PathCost cls, int x1, int y1, int x2, int y2){
        requireActive();
        bind(world);
        int costId = costId(cls);
        int x = x1, dx = Math.abs(x2 - x), sx = x < x2 ? 1 : -1;
        int y = y1, dy = Math.abs(y2 - y), sy = y < y2 ? 1 : -1;
        int err = dx - dy;
        while(x >= 0 && y >= 0 && x < width && y < height){
            if(avoid(team, costId, x + y * width)) return Point2.pack(x, y);
            if(x == x2 && y == y2) return 0;
            // no diagonals
            if(2 * err + dy > dx - 2 * err){
                err -= dy;
                x += sx;
            }else{
                err += dx;
                y += sy;
            }
        }
        return 0;
    }

    /** {@code UnitComp.isPathImpassable}: a ground unit can't enter tile (x, y) at all. */
    public boolean pathImpassable(Unit unit, int x, int y){
        requireActive();
        bind(unit.world());
        return !unit.isFlying() && x >= 0 && y >= 0 && x < width && y < height
            && tcost(unit.team, costId(pathCost(unit)), x + y * width) == IMPASSABLE;
    }

    private static PathCost pathCost(Unit unit){
        return unit.world().unitTable().ai(unit.type()).pathCost;
    }

    /** {@code World.toTile}. */
    private static int toTile(float coord){
        return Math.round(coord / TILESIZE);
    }

    /** {@code world.isInMapArea(x, y)}, with the area the costs use: the path rules as of the last flush. */
    private boolean inMapArea(int x, int y){
        if(x < 0 || y < 0 || x >= width || y >= height) return false;
        PathRules rules = grid.rules;
        return !rules.limitMapArea() || (rules.limitX() <= x && rules.limitX() + rules.limitWidth() >= x
            && rules.limitY() <= y && rules.limitY() + rules.limitHeight() >= y);
    }

    // endregion
    // region costs

    /** {@code tcost}: {@code cost.getCost(team, tiles[pos])}, without the map-area limit. */
    private int tcost(int team, int costId, int pos){
        return control(COST_TYPES[costId], team, grid.tiles[pos]);
    }

    /** {@code cost}: with the map-area limit for teams that aren't AI. */
    private int cost(int team, int costId, int pos){
        return engine.rtsCost(team, COST_TYPES[costId], pos);
    }

    private boolean avoid(int team, int costId, int pos){
        int cost = cost(team, costId, pos);
        return cost == IMPASSABLE || cost >= 2;
    }

    private boolean passable(int team, int costId, int pos){
        int amount = tcost(team, costId, pos);
        return amount != IMPASSABLE && amount < SOLID_CAP;
    }

    private boolean nearPassable(int initialCost, int team, int costId, int pos){
        int amount = tcost(team, costId, pos);
        return amount != IMPASSABLE && amount < Math.min(Math.max(50, initialCost + 1), SOLID_CAP);
    }

    private boolean solid(int team, int costId, int x, int y){
        if(x < 0 || y < 0 || x >= width || y >= height) return true;
        int cost = cost(team, costId, x + y * width);
        return cost == IMPASSABLE || cost >= SOLID_CAP;
    }

    // endregion
    // region raycasts

    /** {@code raycast} (ControlPathfinder.java:1374): true if a diagonal walk meets a tile to avoid, or leaves the map. */
    private boolean raycast(int team, int costId, int x1, int y1, int x2, int y2){
        int x = x1, dx = Math.abs(x2 - x), sx = x < x2 ? 1 : -1;
        int y = y1, dy = Math.abs(y2 - y), sy = y < y2 ? 1 : -1;
        int e2, err = dx - dy;
        while(x >= 0 && y >= 0 && x < width && y < height){
            if(avoid(team, costId, x + y * width)) return true;
            if(x == x2 && y == y2) return false;
            e2 = 2 * err;
            if(e2 > -dy){
                err -= dy;
                x += sx;
            }
            if(e2 < dx){
                err += dx;
                y += sy;
            }
        }
        return true;
    }

    private boolean overlap(int initialCost, int team, int costId, int x, int y, float startX, float startY, float endX, float endY, float rectSize){
        if(x < 0 || y < 0 || x >= width || y >= height) return false;
        if(!nearPassable(initialCost, team, costId, x + y * width)){
            return intersectSegmentRectangleFast(startX, startY, endX, endY, x * TILESIZE - rectSize / 2f, y * TILESIZE - rectSize / 2f, rectSize, rectSize);
        }
        return false;
    }

    /** {@code raycastRect} (ControlPathfinder.java:1432): true if the unit's tile rect would hit something on the way. */
    private boolean raycastRect(int initialCost, float startX, float startY, float endX, float endY, int team, int costId,
                                int x1, int y1, int x2, int y2, float rectSize){
        int x = x1, dx = Math.abs(x2 - x), sx = x < x2 ? 1 : -1;
        int y = y1, dy = Math.abs(y2 - y), sy = y < y2 ? 1 : -1;
        int e2, err = dx - dy;
        while(x >= 0 && y >= 0 && x < width && y < height){
            if(!nearPassable(initialCost, team, costId, x + y * width)
                || overlap(initialCost, team, costId, x + 1, y, startX, startY, endX, endY, rectSize)
                || overlap(initialCost, team, costId, x - 1, y, startX, startY, endX, endY, rectSize)
                || overlap(initialCost, team, costId, x, y + 1, startX, startY, endX, endY, rectSize)
                || overlap(initialCost, team, costId, x, y - 1, startX, startY, endX, endY, rectSize)){
                return true;
            }
            if(x == x2 && y == y2) return false;
            e2 = 2 * err;
            if(e2 > -dy){
                err -= dy;
                x += sx;
            }
            if(e2 < dx){
                err += dx;
                y += sy;
            }
        }
        return true;
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

    // endregion
    // region clusters

    /** {@code getCluster}: null if not built yet. */
    Cluster getCluster(int team, int costId, int clusterIndex){
        Cluster[][] dim1 = clusters[team];
        if(dim1 == null) return null;
        Cluster[] dim2 = dim1[costId];
        if(dim2 == null) return null;
        return dim2[clusterIndex];
    }

    /** {@code getCreateCluster}: never null. */
    private Cluster getCreateCluster(int team, int costId, int clusterIndex){
        Cluster result = getCluster(team, costId, clusterIndex);
        return result == null ? updateCluster(team, costId, clusterIndex % cwidth, clusterIndex / cwidth) : result;
    }

    /** {@code updateCluster} (ControlPathfinder.java:454): the cluster's portals, shared with its neighbours, then its inner edges. */
    private Cluster updateCluster(int team, int costId, int cx, int cy){
        Cluster[][] dim1 = clusters[team];
        if(dim1 == null) dim1 = clusters[team] = new Cluster[COSTS][];
        Cluster[] dim2 = dim1[costId];
        if(dim2 == null) dim2 = dim1[costId] = new Cluster[cwidth * cheight];

        Cluster cluster = dim2[cy * cwidth + cx];
        if(cluster == null){
            cluster = dim2[cy * cwidth + cx] = new Cluster();
        }else{
            // reset data
            for(IntSeq p : cluster.portals){
                if(p != null) p.clear();
            }
        }

        for(int direction = 0; direction < 4; direction++){
            int otherX = cx + NEXT_OFFSETS[direction * 2], otherY = cy + NEXT_OFFSETS[direction * 2 + 1];
            // out of bounds, no portals in this direction
            if(otherX < 0 || otherY < 0 || otherX >= cwidth || otherY >= cheight) continue;

            Cluster other = dim2[otherX + otherY * cwidth];
            IntSeq portals;
            if(other == null){
                // create new portals at direction
                portals = cluster.portals[direction] = new IntSeq(4);
            }else{
                // share portals with the other cluster
                portals = cluster.portals[direction] = other.portals[(direction + 2) % 4];
                if(portals == null){
                    portals = cluster.portals[direction] = other.portals[(direction + 2) % 4] = new IntSeq();
                }
                // clear the portals, they're being recalculated now
                portals.clear();
            }

            int addX = MOVE_DIRS[direction * 2], addY = MOVE_DIRS[direction * 2 + 1];
            int baseX = cx * CLUSTER_SIZE + OFFSETS[direction * 2] * (CLUSTER_SIZE - 1),
                baseY = cy * CLUSTER_SIZE + OFFSETS[direction * 2 + 1] * (CLUSTER_SIZE - 1),
                nextBaseX = baseX + NEXT_OFFSETS[direction * 2],
                nextBaseY = baseY + NEXT_OFFSETS[direction * 2 + 1];

            int lastPortal = -1;
            boolean prevSolid = true;
            for(int i = 0; i < CLUSTER_SIZE; i++){
                int x = baseX + addX * i, y = baseY + addY * i;
                // scan for portals
                if(solid(team, costId, x, y) || solid(team, costId, nextBaseX + addX * i, nextBaseY + addY * i)){
                    int previous = i - 1;
                    // hit a wall: a portal between the two points, an inclusive range
                    if(!prevSolid && previous >= lastPortal) portals.add(Point2.pack(previous, lastPortal));
                    prevSolid = true;
                }else{
                    // an empty area: the portal starts here
                    if(prevSolid) lastPortal = i;
                    prevSolid = false;
                }
            }

            // close the last portal
            int previous = CLUSTER_SIZE - 1;
            if(!prevSolid && previous >= lastPortal) portals.add(Point2.pack(previous, lastPortal));
        }

        updateInnerEdges(team, costId, cx, cy, cluster);
        return cluster;
    }

    /** {@code updateInnerEdges} (ControlPathfinder.java:557): the A* cost between each pair of the cluster's portals. */
    private void updateInnerEdges(int team, int costId, int cx, int cy, Cluster cluster){
        int minX = cx * CLUSTER_SIZE, minY = cy * CLUSTER_SIZE,
            maxX = Math.min(minX + CLUSTER_SIZE - 1, width - 1), maxY = Math.min(minY + CLUSTER_SIZE - 1, height - 1);

        // clear all connections: the portals changed
        cluster.portalConnections = new Edges[4][];

        for(int direction = 0; direction < 4; direction++){
            IntSeq portals = cluster.portals[direction];
            if(portals == null) continue;
            int addX = MOVE_DIRS[direction * 2], addY = MOVE_DIRS[direction * 2 + 1];

            for(int i = 0; i < portals.size; i++){
                int portal = portals.items[i],
                    from = Point2.x(portal), to = Point2.y(portal),
                    average = (from + to) / 2,
                    x = addX * average + cx * CLUSTER_SIZE + OFFSETS[direction * 2] * (CLUSTER_SIZE - 1),
                    y = addY * average + cy * CLUSTER_SIZE + OFFSETS[direction * 2 + 1] * (CLUSTER_SIZE - 1);

                for(int otherDir = 0; otherDir < 4; otherDir++){
                    IntSeq otherPortals = cluster.portals[otherDir];
                    if(otherPortals == null) continue;

                    for(int j = 0; j < otherPortals.size; j++){
                        // vanilla's usedEdges: every portal up to and including (direction, i) is done
                        if(otherDir < direction || (otherDir == direction && j <= i)) continue;

                        int other = otherPortals.items[j],
                            otherFrom = Point2.x(other), otherTo = Point2.y(other),
                            otherAverage = (otherFrom + otherTo) / 2,
                            ox = cx * CLUSTER_SIZE + OFFSETS[otherDir * 2] * (CLUSTER_SIZE - 1),
                            oy = cy * CLUSTER_SIZE + OFFSETS[otherDir * 2 + 1] * (CLUSTER_SIZE - 1),
                            otherX = MOVE_DIRS[otherDir * 2] * otherAverage + ox,
                            otherY = MOVE_DIRS[otherDir * 2 + 1] * otherAverage + oy;

                        // a duplicate portal; should never happen
                        if(Point2.pack(x, y) == Point2.pack(otherX, otherY)) continue;

                        float connectionCost = innerAstar(team, costId, minX, minY, maxX, maxY,
                            x + y * width, otherX + otherY * width,
                            MOVE_DIRS[otherDir * 2] * otherFrom + ox, MOVE_DIRS[otherDir * 2 + 1] * otherFrom + oy,
                            MOVE_DIRS[otherDir * 2] * otherTo + ox, MOVE_DIRS[otherDir * 2 + 1] * otherTo + oy);

                        if(connectionCost != -1f){
                            if(cluster.portalConnections[direction] == null) cluster.portalConnections[direction] = new Edges[cluster.portals[direction].size];
                            if(cluster.portalConnections[otherDir] == null) cluster.portalConnections[otherDir] = new Edges[cluster.portals[otherDir].size];
                            if(cluster.portalConnections[direction][i] == null) cluster.portalConnections[direction][i] = new Edges();
                            if(cluster.portalConnections[otherDir][j] == null) cluster.portalConnections[otherDir][j] = new Edges();

                            cluster.portalConnections[direction][i].add(intraEdge(otherDir, j, connectionCost));
                            cluster.portalConnections[otherDir][j].add(intraEdge(direction, i, connectionCost));
                        }
                    }
                }
            }
        }
    }

    /** Manhattan distance between two tile positions. */
    private float heuristic(int a, int b){
        int x = a % width, x2 = b % width, y = a / width, y2 = b / width;
        return Math.abs(x - x2) + Math.abs(y - y2);
    }

    /**
     * {@code innerAstar} (ControlPathfinder.java:647): the cost from {@code startPos} to the nearest tile of the goal
     * rectangle, staying in the cluster's bounds, or -1 if there is no path.
     */
    float innerAstar(int team, int costId, int minX, int minY, int maxX, int maxY, int startPos, int goalPos,
                     int goalX1, int goalY1, int goalX2, int goalY2){
        PathfindQueue frontier = innerFrontier;
        IntIntMap costs = innerCosts;
        frontier.clear();
        costs.clear();

        costs.put(startPos, Float.floatToRawIntBits(0f));
        frontier.add(startPos, 0);

        if(goalX2 < goalX1){
            int tmp = goalX1;
            goalX1 = goalX2;
            goalX2 = tmp;
        }
        if(goalY2 < goalY1){
            int tmp = goalY1;
            goalY1 = goalY2;
            goalY2 = tmp;
        }

        while(frontier.size > 0){
            int current = frontier.poll();
            int cx = current % width, cy = current / width;

            // found the goal (it's in the portal rectangle)
            if((cx >= goalX1 && cy >= goalY1 && cx <= goalX2 && cy <= goalY2) || current == goalPos){
                return Float.intBitsToFloat(costs.get(current, 0));
            }

            for(int d = 0; d < 4; d++){
                int newx = cx + NEXT_OFFSETS[d * 2], newy = cy + NEXT_OFFSETS[d * 2 + 1];
                int next = newx + width * newy;

                if(newx > maxX || newy > maxY || newx < minX || newy < minY || tcost(team, costId, next) == IMPASSABLE) continue;

                // tileCost: currently a flat cost, with the map-area limit
                float add = cost(team, costId, next);
                if(add < 0) continue;

                float newCost = Float.intBitsToFloat(costs.get(current, 0)) + add;
                if(newCost < Float.intBitsToFloat(costs.get(next, INFINITE))){
                    costs.put(next, Float.floatToRawIntBits(newCost));
                    frontier.add(next, newCost + heuristic(next, goalPos));
                }
            }
        }
        return -1f;
    }

    /** {@code makeNodeIndex}: one name per portal, on the right or top edge of its cluster except at the map's left and bottom. */
    int makeNodeIndex(int cx, int cy, int dir, int portal){
        // the direction can only be 2 if the cluster is on the left edge of the map
        if(dir == 2 && cx != 0){
            dir = 0;
            cx--;
        }
        // and 3 on the bottom edge
        if(dir == 3 && cy != 0){
            dir = 1;
            cy--;
        }
        return nodeIndex(cx + cy * cwidth, dir, portal);
    }

    /** {@code findClosestNode} (ControlPathfinder.java:724): the portal of the tile's cluster the tile reaches cheapest, or {@code MAX_VALUE}. */
    int findClosestNode(int team, int costId, int tileX, int tileY){
        int cx = tileX / CLUSTER_SIZE, cy = tileY / CLUSTER_SIZE;
        if(cx < 0 || cy < 0 || cx >= cwidth || cy >= cheight) return Integer.MAX_VALUE;

        Cluster cluster = getCreateCluster(team, costId, cx + cy * cwidth);
        int minX = cx * CLUSTER_SIZE, minY = cy * CLUSTER_SIZE,
            maxX = Math.min(minX + CLUSTER_SIZE - 1, width - 1), maxY = Math.min(minY + CLUSTER_SIZE - 1, height - 1);

        int bestPortalPair = Integer.MAX_VALUE;
        float bestCost = Float.MAX_VALUE;

        // A* to every node, keeping the best
        for(int dir = 0; dir < 4; dir++){
            IntSeq portals = cluster.portals[dir];
            if(portals == null) continue;

            for(int j = 0; j < portals.size; j++){
                int other = portals.items[j],
                    otherFrom = Point2.x(other), otherTo = Point2.y(other),
                    otherAverage = (otherFrom + otherTo) / 2,
                    ox = cx * CLUSTER_SIZE + OFFSETS[dir * 2] * (CLUSTER_SIZE - 1),
                    oy = cy * CLUSTER_SIZE + OFFSETS[dir * 2 + 1] * (CLUSTER_SIZE - 1),
                    otherX = MOVE_DIRS[dir * 2] * otherAverage + ox,
                    otherY = MOVE_DIRS[dir * 2 + 1] * otherAverage + oy;

                float connectionCost = innerAstar(team, costId, minX, minY, maxX, maxY,
                    tileX + tileY * width, otherX + otherY * width,
                    MOVE_DIRS[dir * 2] * otherFrom + ox, MOVE_DIRS[dir * 2 + 1] * otherFrom + oy,
                    MOVE_DIRS[dir * 2] * otherTo + ox, MOVE_DIRS[dir * 2 + 1] * otherTo + oy);

                if(connectionCost != -1f && connectionCost < bestCost){
                    bestPortalPair = Point2.pack(dir, j);
                    bestCost = connectionCost;
                }
            }
        }

        if(bestPortalPair != Integer.MAX_VALUE){
            return makeNodeIndex(cx, cy, Point2.x(bestPortalPair), Point2.y(bestPortalPair));
        }
        return Integer.MAX_VALUE;
    }

    /** Manhattan distance between the middles of two portals, in tiles. */
    private float clusterNodeHeuristic(int team, int costId, int nodeA, int nodeB){
        int clusterA = nodeCluster(nodeA), dirA = nodeDir(nodeA), portalA = nodePortal(nodeA),
            clusterB = nodeCluster(nodeB), dirB = nodeDir(nodeB), portalB = nodePortal(nodeB),
            rangeA = getCreateCluster(team, costId, clusterA).portals[dirA].items[portalA],
            rangeB = getCreateCluster(team, costId, clusterB).portals[dirB].items[portalB];

        float averageA = (Point2.x(rangeA) + Point2.y(rangeA)) / 2f,
            x1 = MOVE_DIRS[dirA * 2] * averageA + (clusterA % cwidth) * CLUSTER_SIZE + OFFSETS[dirA * 2] * (CLUSTER_SIZE - 1) + NEXT_OFFSETS[dirA * 2] / 2f,
            y1 = MOVE_DIRS[dirA * 2 + 1] * averageA + (clusterA / cwidth) * CLUSTER_SIZE + OFFSETS[dirA * 2 + 1] * (CLUSTER_SIZE - 1) + NEXT_OFFSETS[dirA * 2 + 1] / 2f,
            averageB = (Point2.x(rangeB) + Point2.y(rangeB)) / 2f,
            x2 = MOVE_DIRS[dirB * 2] * averageB + (clusterB % cwidth) * CLUSTER_SIZE + OFFSETS[dirB * 2] * (CLUSTER_SIZE - 1) + NEXT_OFFSETS[dirB * 2] / 2f,
            y2 = MOVE_DIRS[dirB * 2 + 1] * averageB + (clusterB / cwidth) * CLUSTER_SIZE + OFFSETS[dirB * 2 + 1] * (CLUSTER_SIZE - 1) + NEXT_OFFSETS[dirB * 2 + 1] / 2f;

        return Math.abs(x1 - x2) + Math.abs(y1 - y2);
    }

    /**
     * {@code clusterAstar} (ControlPathfinder.java:805): the portal nodes from {@code start} (exclusive) to {@code end}
     * into {@code request.resultPath}, or null if {@code end} can't be reached.
     */
    IntSeq clusterAstar(Request request, int costId, int startNodeIndex, int endNodeIndex){
        IntSeq result = request.resultPath;
        if(startNodeIndex == endNodeIndex){
            result.clear();
            result.add(startNodeIndex);
            return result;
        }

        int team = request.team;
        IntIntMap costs = nodeCosts;
        PathfindQueue frontier = nodeFrontier;
        costs.clear();
        cameFrom.clear();
        frontier.clear();

        cameFrom.put(startNodeIndex, startNodeIndex);
        costs.put(startNodeIndex, Float.floatToRawIntBits(0f));
        frontier.add(startNodeIndex, 0);

        boolean foundEnd = false;
        while(frontier.size > 0){
            int current = frontier.poll();
            if(current == endNodeIndex){
                foundEnd = true;
                break;
            }

            int cluster = nodeCluster(current), dir = nodeDir(current), portal = nodePortal(current);
            int cx = cluster % cwidth, cy = cluster / cwidth;

            // an invalid cluster index: a start node of MAX_VALUE
            if(cx >= cwidth || cy >= cheight || cx < 0 || cy < 0) continue;

            Cluster clust = getCreateCluster(team, costId, cluster);
            Edges innerCons = clust.portalConnections[dir] == null || portal >= clust.portalConnections[dir].length ? null : clust.portalConnections[dir][portal];

            // edges for the cluster the node is 'in'
            if(innerCons != null) checkEdges(team, costId, current, endNodeIndex, cx, cy, innerCons);

            // edges that this node 'faces' from the other side
            int nextCx = cx + NEXT_OFFSETS[dir * 2], nextCy = cy + NEXT_OFFSETS[dir * 2 + 1];
            if(nextCx >= 0 && nextCy >= 0 && nextCx < cwidth && nextCy < cheight){
                Cluster nextCluster = getCreateCluster(team, costId, nextCx + nextCy * cwidth);
                int relativeDir = (dir + 2) % 4;
                Edges outerCons = nextCluster.portalConnections[relativeDir] == null || nextCluster.portalConnections[relativeDir].length <= portal ? null : nextCluster.portalConnections[relativeDir][portal];
                if(outerCons != null) checkEdges(team, costId, current, endNodeIndex, nextCx, nextCy, outerCons);
            }
        }

        if(foundEnd){
            result.clear();
            int cur = endNodeIndex;
            while(cur != startNodeIndex){
                result.add(cur);
                cur = cameFrom.get(cur, 0);
            }
            result.reverse();
            return result;
        }
        return null;
    }

    private void checkEdges(int team, int costId, int current, int goal, int cx, int cy, Edges connections){
        for(int i = 0; i < connections.size; i++){
            long con = connections.items[i];
            float cost = edgeCost(con);
            int next = makeNodeIndex(cx, cy, edgeDir(con), edgePortal(con));
            float newCost = Float.intBitsToFloat(nodeCosts.get(current, 0)) + cost;
            if(newCost < Float.intBitsToFloat(nodeCosts.get(next, INFINITE))){
                nodeCosts.put(next, Float.floatToRawIntBits(newCost));
                nodeFrontier.add(next, newCost + clusterNodeHeuristic(team, costId, next, goal));
                cameFrom.put(next, current);
            }
        }
    }

    // endregion
    // region fields

    /** {@code updateFields} (ControlPathfinder.java:906): the field's frontier, at most {@code maxSteps} steps of pops (0: all). */
    void updateFields(Field cache, int maxSteps){
        IntQueue frontier = cache.frontier;
        int[][] fields = cache.fields;
        int goalPos = cache.goalPos, team = cache.team;
        PathCost pcost = COST_TYPES[cache.costId];
        int[] tiles = grid.tiles;
        int counter = 0, taken = 0;

        while(frontier.size > 0){
            int tile = frontier.removeLast();
            pops++;
            int baseX = tile % width, baseY = tile / width;
            int curWeightIndex = (baseX / CLUSTER_SIZE) + (baseY / CLUSTER_SIZE) * cwidth;

            int[] curWeights = fields[curWeightIndex];
            if(curWeights != null){
                int cost = curWeights[baseX % CLUSTER_SIZE + ((baseY % CLUSTER_SIZE) * CLUSTER_SIZE)];

                if(cost != IMPASSABLE){
                    for(int d = 0; d < 4; d++){
                        int px = NEXT_OFFSETS[d * 2], py = NEXT_OFFSETS[d * 2 + 1];
                        // quirk: -1 / 12 is 0, so the left of column 0 passes this and is caught below
                        int dx = baseX + px, dy = baseY + py, clx = dx / CLUSTER_SIZE, cly = dy / CLUSTER_SIZE;
                        if(clx < 0 || cly < 0 || dx >= width || dy >= height) continue;

                        int nextWeightIndex = clx + cly * cwidth;
                        int[] weights = nextWeightIndex == curWeightIndex ? curWeights : fields[nextWeightIndex];
                        // no weights were registered there: not allowed to move this way
                        if(weights == null) continue;

                        int newPos = tile + px + py * width;
                        // can't move back to the goal
                        if(newPos == goalPos) continue;
                        if(dx - clx * CLUSTER_SIZE < 0 || dy - cly * CLUSTER_SIZE < 0) continue;

                        int newPosArray = (dx - clx * CLUSTER_SIZE) + (dy - cly * CLUSTER_SIZE) * CLUSTER_SIZE;
                        int otherCost = control(pcost, team, tiles[newPos]);
                        int oldCost = weights[newPosArray];

                        // a cost of 0 means uninitialized, or the goal, which is handled above
                        if((oldCost == 0 || oldCost > cost + otherCost) && otherCost != IMPASSABLE){
                            frontier.addFirst(newPos);
                            weights[newPosArray] = cost + otherCost;
                        }
                    }
                }
            }

            // vanilla reads the clock every STEP_INTERVAL + 1 pops; Shard counts the steps
            if(maxSteps > 0 && (counter++) >= STEP_INTERVAL){
                counter = 0;
                if(++taken >= maxSteps) return;
            }
        }
    }

    /** {@code addFlowCluster}: a cluster of zeroes, and with {@code addingFrontier}, its neighbours' facing edges back in the frontier. */
    private void addFlowCluster(Field cache, int cx, int cy, boolean addingFrontier){
        // out of bounds
        if(cx < 0 || cy < 0 || cx >= cwidth || cy >= cheight) return;

        int[][] fields = cache.fields;
        int key = cx + cy * cwidth;
        if(fields[key] != null) return;
        fields[key] = new int[CLUSTER_SIZE * CLUSTER_SIZE];

        if(addingFrontier){
            for(int dir = 0; dir < 4; dir++){
                int ox = cx + NEXT_OFFSETS[dir * 2], oy = cy + NEXT_OFFSETS[dir * 2 + 1];
                if(ox < 0 || oy < 0 || ox >= cwidth || oy >= cheight) continue;

                int[] otherField = fields[ox + oy * cwidth];
                if(otherField == null) continue;

                int relOffset = (dir + 2) % 4,
                    movex = MOVE_DIRS[relOffset * 2],
                    movey = MOVE_DIRS[relOffset * 2 + 1],
                    otherx1 = OFFSETS[relOffset * 2] * (CLUSTER_SIZE - 1),
                    othery1 = OFFSETS[relOffset * 2 + 1] * (CLUSTER_SIZE - 1);

                // scan the edge of the cluster
                for(int i = 0; i < CLUSTER_SIZE; i++){
                    int x = otherx1 + movex * i, y = othery1 + movey * i;
                    // not 0: initialized flowfield data
                    if(otherField[x + y * CLUSTER_SIZE] > 0){
                        int worldX = x + ox * CLUSTER_SIZE, worldY = y + oy * CLUSTER_SIZE;
                        // the world position goes back in the frontier, so it recalculates
                        cache.frontier.addFirst(worldX + worldY * width);
                    }
                }
            }
        }
    }

    /**
     * {@code initializePathRequest} (ControlPathfinder.java:1026): the portal path from the unit's cluster to the goal's,
     * then its clusters (and the ones each node faces) into the goal's field, made if it doesn't exist.
     */
    void initializePathRequest(Request request, int team, int costId, int unitX, int unitY, int goalX, int goalY){
        int goalPos = goalX + goalY * width;

        int node = findClosestNode(team, costId, unitX, unitY);
        int dest = findClosestNode(team, costId, goalX, goalY);

        if(dest == Integer.MAX_VALUE){
            request.notFound = true;
            request.oldCache = null;
            return;
        }

        IntSeq nodePath = clusterAstar(request, costId, node, dest);
        // no result found: the old path isn't relevant now, the goal can't be reached anymore
        if(nodePath == null){
            request.notFound = true;
            request.oldCache = null;
            return;
        }

        Field cache = fields.get(fieldKey(goalPos, costId, team));
        // if true, the existing field cells that face new cells go back in the frontier
        boolean addingFrontier = true;

        // create the cache if it doesn't exist
        if(cache == null){
            cache = new Field(nextId++, costId, team, goalPos, cwidth * cheight, updateId);
            fields.put(cache.mapKey, cache);
            // Core.app.post(() -> fieldList.add(cache)): applied at once (class comment)
            fieldList.add(cache);
            cache.frontier.addFirst(goalPos);
            // a new field has nothing to merge
            addingFrontier = false;
        }

        int cx = unitX / CLUSTER_SIZE, cy = unitY / CLUSTER_SIZE;
        addFlowCluster(cache, cx, cy, addingFrontier);

        for(int i = -1; i < nodePath.size; i++){
            int current = i == -1 ? node : nodePath.items[i],
                cluster = nodeCluster(current),
                dir = nodeDir(current),
                ox = cluster % cwidth + NEXT_OFFSETS[dir * 2],
                oy = cluster / cwidth + NEXT_OFFSETS[dir * 2 + 1];

            addFlowCluster(cache, cluster % cwidth, cluster / cwidth, addingFrontier);

            // and the cluster on the other side of the portal
            if(ox >= 0 && oy >= 0 && ox < cwidth && oy < cheight){
                addFlowCluster(cache, ox + oy * cwidth, addingFrontier);
            }
        }
    }

    private void addFlowCluster(Field cache, int cluster, boolean addingFrontier){
        addFlowCluster(cache, cluster % cwidth, cluster / cwidth, addingFrontier);
    }

    private void recalculatePath(Request request){
        initializePathRequest(request, request.team, request.costId, request.tileX(), request.tileY(),
            request.destination % width, request.destination / width);
    }

    /** {@code getCost}: the field's weight at (x, y), or 0 where it has no cluster yet, which it asks for with {@code requeue}. */
    private int getCost(Field cache, int x, int y, boolean requeue){
        int cluster = x / CLUSTER_SIZE + (y / CLUSTER_SIZE) * cwidth;
        int[] field = cache.fields[cluster];
        if(field == null){
            if(!requeue) return 0;
            // vanilla posts one task per read; only the first does anything, so later ones are dropped here
            if(cache.requested == null) cache.requested = new Bits(cwidth * cheight);
            if(!cache.requested.getAndSet(cluster)) post(new Grow(cache, x / CLUSTER_SIZE, y / CLUSTER_SIZE));
            return 0;
        }
        return field[(x % CLUSTER_SIZE) + (y % CLUSTER_SIZE) * CLUSTER_SIZE];
    }

    // endregion
    // region invalidation

    /** {@code updateTile} for one changed tile: its cluster, and the neighbours across an edge it lies on. */
    void updateSingleTile(int pos){
        int x = pos % width, y = pos / width, mx = x % CLUSTER_SIZE, my = y % CLUSTER_SIZE, cx = x / CLUSTER_SIZE,
            cy = y / CLUSTER_SIZE, cluster = cx + cy * cwidth;

        // on the edge of a cluster: the portals may have changed
        if(mx == 0 || my == 0 || mx == CLUSTER_SIZE - 1 || my == CLUSTER_SIZE - 1){
            if(mx == 0) queueClusterUpdate(cx - 1, cy); // left
            if(my == 0) queueClusterUpdate(cx, cy - 1); // bottom
            if(mx == CLUSTER_SIZE - 1) queueClusterUpdate(cx + 1, cy); // right
            if(my == CLUSTER_SIZE - 1) queueClusterUpdate(cx, cy + 1); // top
            queueClusterUpdate(cx, cy);
        }else{
            // inside: only the inner edges
            post(new Repair(cluster, false));
        }
    }

    private void queueClusterUpdate(int cx, int cy){
        if(cx >= 0 && cy >= 0 && cx < cwidth && cy < cheight) post(new Repair(cx + cy * cwidth, true));
    }

    /**
     * {@code clusterChanged}: the requests whose field covers the cluster, or that found nothing, are invalid. Quirk
     * kept: the field looked up is the changed cluster's team and cost type at the request's goal, not the request's.
     */
    private void clusterChanged(int team, int costId, int cx, int cy){
        int index = cx + cy * cwidth;
        for(Request req : threadPathRequests){
            Field field = fields.get(fieldKey(req.destination, costId, team));
            if((field != null && field.fields[index] != null) || req.notFound) markInvalid(req);
        }
    }

    private void markInvalid(Request request){
        if(request.invalid) return;
        request.invalid = true;
        invalidRequests.add(request);
    }

    private void updateClustersComplete(int clusterIndex){
        for(int team = 0; team < clusters.length; team++){
            Cluster[][] dim1 = clusters[team];
            if(dim1 == null) continue;
            for(int costId = 0; costId < dim1.length; costId++){
                Cluster[] dim2 = dim1[costId];
                if(dim2 != null && dim2[clusterIndex] != null){
                    updateCluster(team, costId, clusterIndex % cwidth, clusterIndex / cwidth);
                    clusterChanged(team, costId, clusterIndex % cwidth, clusterIndex / cwidth);
                }
            }
        }
    }

    private void updateClustersInner(int clusterIndex){
        for(int team = 0; team < clusters.length; team++){
            Cluster[][] dim1 = clusters[team];
            if(dim1 == null) continue;
            for(int costId = 0; costId < dim1.length; costId++){
                Cluster[] dim2 = dim1[costId];
                if(dim2 != null && dim2[clusterIndex] != null){
                    updateInnerEdges(team, costId, clusterIndex % cwidth, clusterIndex / cwidth, dim2[clusterIndex]);
                    clusterChanged(team, costId, clusterIndex % cwidth, clusterIndex / cwidth);
                }
            }
        }
    }

    /**
     * The invalid check (ControlPathfinder.java:1574): each invalid request whose field is complete drops it and is
     * recomputed in the next pass, with every request towards the same goal (quirk kept: of any team or cost type)
     * following the dropped field meanwhile. A request whose field is still building waits for the next check.
     */
    void checkInvalid(){
        int kept = 0;
        for(int index = 0; index < invalidRequests.size(); index++){
            Request request = invalidRequests.get(index);
            boolean done = true;
            if(!request.invalidated){
                Field field = fields.get(fieldKey(request.destination, request.costId, request.team));
                if(field != null){
                    // only worth it once the frontier is done; otherwise the unit would follow something incomplete
                    if(field.frontier.isEmpty()){
                        // removed, to be recalculated next pass once recalculatePath runs
                        fields.remove(field.mapKey);
                        removeUnordered(fieldList, field);
                        // the requests that hold it as their old field don't stutter while it's recalculated
                        for(Request other : threadPathRequests){
                            if(other.destination == request.destination){
                                other.oldCache = field;
                                if(other != request) post(new Recalculate(other));
                            }
                        }
                        post(new Recalculate(request));
                    }else{
                        done = false;
                    }
                }else{
                    // no field: a previous request already invalidated it
                    post(new Recalculate(request));
                }
            }
            if(done) request.invalid = false;
            else invalidRequests.set(kept++, request);
        }
        invalidRequests.subList(kept, invalidRequests.size()).clear();
    }

    // endregion
    // region schedule

    /** One {@link Phase#PATH_FLUSH}: the tick's tile changes are posted, then the pass if one is due, then the clean-up. */
    void flush(World world){
        if(!active) return;
        PathService paths = world.find(PathService.class);
        if(paths == null || !paths.active()) return;
        bind(world);
        // a map load: vanilla starts a new pathfinder (WorldLoadEvent)
        if(paths.reloaded) reset();
        long now = world.ticks();
        boolean frozen = world.frozen();
        updateId = now - frozenTicks;
        clock = now;
        if(!started){
            started = true;
            nextPass = now;
            // the thread's first check is 1,000 ms late
            lastInvalidCheck = now + INVALIDATE_TICKS;
        }

        // TileChangeEvent and the team changes: posted to the thread by updateTile
        TileGrid tiles = world.tiles();
        tiles.changes((pos, before) -> updateSingleTile(pos));
        tileChanges += tiles.changeCount();

        if(now >= nextPass){
            nextPass = now + PASS_TICKS;
            pass();
        }
        trigger();
        // state.updateId++ after Trigger.update, unless the game is paused (Logic.java:524-530); the thread's clock runs on
        if(frozen) frozenTicks++;
        else updateId++;
        clock = now + 1;
    }

    /** {@code state.updateId} as it stands: frozen while the world is paused. */
    public long updateId(){
        return updateId;
    }

    /** One wake of vanilla's thread (ControlPathfinder.java:1549), with no clock. */
    void pass(){
        runQueue();
        updateClusters();
        // periodically check for invalidated paths
        if(clock - lastInvalidCheck > INVALIDATE_TICKS){
            lastInvalidCheck = clock;
            checkInvalid();
        }
        updateAllFields(steps);
    }

    /** The pass's cluster updates: whole clusters, then the inner edges of the rest. */
    void updateClusters(){
        clustersToUpdate.each(cluster -> {
            updateClustersComplete(cluster);
            // don't redundantly update the inner edges after recalculating the whole cluster
            clustersToInnerUpdate.remove(cluster);
        });
        clustersToInnerUpdate.each(this::updateClustersInner);
        clustersToInnerUpdate.clear();
        clustersToUpdate.clear();
    }

    /** Each field's frontier, each with its own budget. */
    void updateAllFields(int maxSteps){
        for(Field cache : fields.values()) updateFields(cache, maxSteps);
    }

    /** {@code queue.run()}: the tasks posted since the last pass, in order; what they post waits for the next. */
    void runQueue(){
        if(queue.isEmpty()) return;
        var tasks = new ArrayList<>(queue);
        queue.clear();
        for(Task task : tasks){
            switch(task){
                case Start(Request request) -> {
                    threadPathRequests.add(request);
                    recalculatePath(request);
                }
                case Recalculate(Request request) -> recalculatePath(request);
                case Drop(Request request) -> removeUnordered(threadPathRequests, request);
                case Forget(long key) -> fields.remove(key);
                case Grow(Field cache, int cx, int cy) -> {
                    if(cache.requested != null) cache.requested.clear(cx + cy * cwidth);
                    addFlowCluster(cache, cx, cy, true);
                }
                case Repair(int cluster, boolean edge) -> {
                    if(edge){
                        clustersToUpdate.add(cluster);
                        updateHigh = Math.max(updateHigh, clustersToUpdate.size);
                    }else{
                        clustersToInnerUpdate.add(cluster);
                        innerHigh = Math.max(innerHigh, clustersToInnerUpdate.size);
                    }
                }
            }
        }
    }

    /**
     * Vanilla's {@code Trigger.update} handler (ControlPathfinder.java:239): a request not asked for in
     * {@value #REQUEST_TIMEOUT} updates, or whose unit is gone, leaves the map now and the thread's list at the next
     * pass; a field not read in {@value #FIELD_TIMEOUT} leaves both the same way ({@code Time.run(0)} runs within the
     * frame).
     */
    private void trigger(){
        var staleRequests = new ArrayList<Request>();
        for(Request req : unitRequests){
            if(req.lastUpdateId <= updateId - REQUEST_TIMEOUT || !req.added()){
                req.invalidated = true;
                post(new Drop(req));
                staleRequests.add(req);
            }
        }
        var staleFields = new ArrayList<Field>();
        for(Field field : fieldList){
            if(field.lastUpdateId <= updateId - FIELD_TIMEOUT){
                post(new Forget(field.mapKey));
                staleFields.add(field);
            }
        }
        // the Time.run(0) halves, in post order: unitRequests.remove(req.unit) removes whatever the unit maps to
        for(Request req : staleRequests){
            Request current = byUnit.remove(req.unitId);
            if(current != null) unitRequests.remove(current);
        }
        for(Field field : staleFields) removeUnordered(fieldList, field);
    }

    private void putRequest(Unit unit, Request request){
        Request previous = byUnit.put(unit.id(), request);
        if(previous == null){
            unitRequests.add(request);
        }else{
            // OrderedMap: the key keeps its place
            unitRequests.set(unitRequests.indexOf(previous), request);
        }
    }

    private void post(Task task){
        queue.add(task);
    }

    /** {@code Seq.remove(value)} on an unordered {@code Seq}: the last element fills the gap. */
    private static <T> void removeUnordered(ArrayList<T> list, T value){
        int index = list.indexOf(value);
        if(index < 0) return;
        int last = list.size() - 1;
        list.set(index, list.get(last));
        list.remove(last);
    }

    /** Forgets everything, as vanilla's new pathfinder after a world load. */
    private void reset(){
        clusters = new Cluster[256][][];
        clustersToUpdate = new IntSet();
        clustersToInnerUpdate = new IntSet();
        updateHigh = innerHigh = 0;
        queue.clear();
        unitRequests.clear();
        byUnit.clear();
        threadPathRequests.clear();
        fields.clear();
        fieldList.clear();
        invalidRequests.clear();
        started = false;
        tileChanges = -1;
    }

    // endregion
    // region world part

    @Override
    public Persistence persistence(){
        return active ? Persistence.AUTHORITATIVE : Persistence.DERIVED;
    }

    @Override
    public void rebuild(World world){
        // only an inactive part is derived, and it holds nothing
        init(world);
    }

    /** Every request anything holds, by id: the map, the thread's list, the invalid set and the queue. */
    private TreeMap<Integer, Request> allRequests(){
        var out = new TreeMap<Integer, Request>();
        for(Request request : unitRequests) out.put(request.id, request);
        for(Request request : threadPathRequests) out.put(request.id, request);
        for(Request request : invalidRequests) out.put(request.id, request);
        for(Task task : queue){
            switch(task){
                case Start(Request request) -> out.put(request.id, request);
                case Recalculate(Request request) -> out.put(request.id, request);
                case Drop(Request request) -> out.put(request.id, request);
                default -> {
                }
            }
        }
        return out;
    }

    /** Every field anything holds, by id: the map, the list, the requests' old fields and the queue. */
    private TreeMap<Integer, Field> allFields(Collection<Request> requests){
        var out = new TreeMap<Integer, Field>();
        for(Field field : fields.values()) out.put(field.id, field);
        for(Field field : fieldList) out.put(field.id, field);
        for(Request request : requests) if(request.oldCache != null) out.put(request.oldCache.id, request.oldCache);
        for(Task task : queue) if(task instanceof Grow(Field cache, int cx, int cy)) out.put(cache.id, cache);
        return out;
    }

    @Override
    public WorldPart copy(CopyContext context){
        var out = new ControlPathfinder();
        copyShape(out);
        if(!active) return out;
        TreeMap<Integer, Request> requests = allRequests();
        var fieldCopies = new TreeMap<Integer, Field>();
        for(Field field : allFields(requests.values()).values()) fieldCopies.put(field.id, field.copy());
        var requestCopies = new TreeMap<Integer, Request>();
        for(Request request : requests.values()){
            Request copy = request.copy(context.map(request.unit));
            copy.oldCache = request.oldCache == null ? null : fieldCopies.get(request.oldCache.id);
            requestCopies.put(request.id, copy);
        }
        for(Request request : unitRequests){
            Request copy = requestCopies.get(request.id);
            out.unitRequests.add(copy);
            out.byUnit.put(copy.unitId, copy);
        }
        for(Request request : threadPathRequests) out.threadPathRequests.add(requestCopies.get(request.id));
        for(Request request : invalidRequests) out.invalidRequests.add(requestCopies.get(request.id));
        for(Field field : fields.values()) out.fields.put(field.mapKey, fieldCopies.get(field.id));
        for(Field field : fieldList) out.fieldList.add(fieldCopies.get(field.id));
        for(Task task : queue){
            out.queue.add(switch(task){
                case Start(Request request) -> new Start(requestCopies.get(request.id));
                case Recalculate(Request request) -> new Recalculate(requestCopies.get(request.id));
                case Drop(Request request) -> new Drop(requestCopies.get(request.id));
                case Forget(long key) -> task;
                case Grow(Field cache, int cx, int cy) -> new Grow(fieldCopies.get(cache.id), cx, cy);
                case Repair(int cluster, boolean edge) -> task;
            });
        }
        out.requestedFromQueue();
        // clusters, with their shared portal lists still shared
        var lists = new IdentityHashMap<IntSeq, IntSeq>();
        for(int team = 0; team < clusters.length; team++){
            Cluster[][] dim1 = clusters[team];
            if(dim1 == null) continue;
            Cluster[][] outDim1 = out.clusters[team] = new Cluster[dim1.length][];
            for(int costId = 0; costId < dim1.length; costId++){
                Cluster[] dim2 = dim1[costId];
                if(dim2 == null) continue;
                Cluster[] outDim2 = outDim1[costId] = new Cluster[dim2.length];
                for(int index = 0; index < dim2.length; index++){
                    Cluster cluster = dim2[index];
                    if(cluster == null) continue;
                    var copy = outDim2[index] = new Cluster();
                    for(int d = 0; d < 4; d++){
                        IntSeq list = cluster.portals[d];
                        copy.portals[d] = list == null ? null : lists.computeIfAbsent(list, ControlPathfinder::copyList);
                    }
                    copy.portalConnections = copyConnections(cluster.portalConnections);
                }
            }
        }
        return out;
    }

    private void copyShape(ControlPathfinder out){
        out.active = active;
        out.width = width;
        out.height = height;
        out.cwidth = cwidth;
        out.cheight = cheight;
        out.updateId = updateId;
        out.clock = clock;
        out.frozenTicks = frozenTicks;
        out.started = started;
        out.nextPass = nextPass;
        out.lastInvalidCheck = lastInvalidCheck;
        out.tileChanges = tileChanges;
        out.steps = steps;
        out.nextId = nextId;
        out.updateHigh = updateHigh;
        out.innerHigh = innerHigh;
        out.clustersToUpdate = clustersToUpdate.copy();
        out.clustersToInnerUpdate = clustersToInnerUpdate.copy();
    }

    /** An {@code IntSeq} with the same backing array: entries past the size are read when a neighbour's edges are stale. */
    private static IntSeq copyList(IntSeq list){
        var out = new IntSeq(list.items.length);
        System.arraycopy(list.items, 0, out.items, 0, list.items.length);
        out.size = list.size;
        return out;
    }

    private static Edges[][] copyConnections(Edges[][] connections){
        var out = new Edges[4][];
        for(int d = 0; d < 4; d++){
            if(connections[d] == null) continue;
            out[d] = new Edges[connections[d].length];
            for(int i = 0; i < out[d].length; i++){
                Edges edges = connections[d][i];
                if(edges == null) continue;
                var copy = out[d][i] = new Edges();
                copy.items = edges.items.clone();
                copy.size = edges.size;
            }
        }
        return out;
    }

    /** Rebuilds each field's {@link Field#requested} from the queued {@link Grow} tasks. */
    private void requestedFromQueue(){
        for(Task task : queue){
            if(task instanceof Grow(Field cache, int cx, int cy)){
                if(cache.requested == null) cache.requested = new Bits(cwidth * cheight);
                cache.requested.set(cx + cy * cwidth);
            }
        }
    }

    @Override
    public void hash(Hasher hasher){
        if(!active) return;
        hasher.bool(started).l(nextPass).l(lastInvalidCheck).i(tileChanges).i(steps).i(nextId).i(updateHigh).i(innerHigh)
            .i(clustersToUpdate.size).i(clustersToInnerUpdate.size);
        // only a world that was paused hashes them, so the others keep their hashes
        if(frozenTicks != 0) hasher.l(frozenTicks);
        TreeMap<Integer, Request> requests = allRequests();
        TreeMap<Integer, Field> all = allFields(requests.values());
        hasher.i(all.size());
        for(Field field : all.values()){
            hasher.i(field.id).i(field.costId).i(field.team).i(field.goalPos).l(field.lastUpdateId).i(field.frontier.size);
            for(int i = 0; i < field.frontier.size; i++) hasher.i(field.frontier.get(i));
            for(int index = 0; index < field.fields.length; index++){
                if(field.fields[index] != null) hasher.i(index).ints(field.fields[index]);
            }
            hasher.i(-1);
        }
        hasher.i(requests.size());
        for(Request request : requests.values()){
            hasher.i(request.id).i(request.unitId).bool(request.added()).i(request.tileX()).i(request.tileY()).i(request.team)
                .i(request.costId).i(request.destination).ints(request.resultPath.items, 0, request.resultPath.size)
                .l(request.lastUpdateId).l(request.lastRecomputeTime).bool(request.notFound).bool(request.invalidated)
                .i(request.oldCache == null ? -1 : request.oldCache.id).bool(request.lastRaycastResult)
                .i(request.lastRaycastTile).i(request.lastWorldUpdate).i(request.lastTile).i(request.lastTargetTile);
        }
        hasher.i(unitRequests.size());
        for(Request request : unitRequests) hasher.i(request.id);
        hasher.i(threadPathRequests.size());
        for(Request request : threadPathRequests) hasher.i(request.id);
        hasher.i(invalidRequests.size());
        for(Request request : invalidRequests) hasher.i(request.id);
        hasher.i(fields.size());
        for(Field field : fields.values()) hasher.i(field.id);
        hasher.i(fieldList.size());
        for(Field field : fieldList) hasher.i(field.id);
        hasher.i(queue.size());
        for(Task task : queue){
            switch(task){
                case Start(Request request) -> hasher.i(0).i(request.id);
                case Recalculate(Request request) -> hasher.i(1).i(request.id);
                case Drop(Request request) -> hasher.i(2).i(request.id);
                case Forget(long key) -> hasher.i(3).l(key);
                case Grow(Field cache, int cx, int cy) -> hasher.i(4).i(cache.id).i(cx).i(cy);
                case Repair(int cluster, boolean edge) -> hasher.i(5).i(cluster).bool(edge);
            }
        }
        for(int team = 0; team < clusters.length; team++){
            Cluster[][] dim1 = clusters[team];
            if(dim1 == null) continue;
            for(int costId = 0; costId < dim1.length; costId++){
                Cluster[] dim2 = dim1[costId];
                if(dim2 == null) continue;
                hasher.i(team).i(costId);
                for(int index = 0; index < dim2.length; index++){
                    Cluster cluster = dim2[index];
                    if(cluster == null) continue;
                    hasher.i(index);
                    for(int d = 0; d < 4; d++){
                        IntSeq list = cluster.portals[d];
                        if(list == null) hasher.i(-1);
                        else hasher.i(list.size).ints(list.items);
                        Edges[] edges = cluster.portalConnections[d];
                        hasher.i(edges == null ? -1 : edges.length);
                        if(edges == null) continue;
                        for(Edges e : edges){
                            hasher.i(e == null ? -1 : e.size);
                            if(e != null) for(int i = 0; i < e.size; i++) hasher.l(e.items[i]);
                        }
                    }
                }
            }
        }
    }

    // checkpoints

    @Override
    public String section(){
        return "control-paths";
    }

    @Override
    public void write(World world, Writes out){
        out.i(VERSION);
        out.bool(active);
        if(!active) return;
        out.i(width);
        out.i(height);
        out.bool(started);
        out.l(nextPass);
        out.l(lastInvalidCheck);
        out.l(frozenTicks);
        out.i(tileChanges);
        out.i(steps);
        out.i(nextId);
        out.i(updateHigh);
        out.i(innerHigh);
        TreeMap<Integer, Request> requests = allRequests();
        TreeMap<Integer, Field> all = allFields(requests.values());
        out.i(all.size());
        for(Field field : all.values()){
            out.i(field.id);
            out.b(field.costId);
            out.b(field.team);
            out.i(field.goalPos);
            out.l(field.lastUpdateId);
            out.i(field.frontier.size);
            for(int i = 0; i < field.frontier.size; i++) out.i(field.frontier.get(i));
            int present = 0;
            for(int[] cluster : field.fields) if(cluster != null) present++;
            out.i(present);
            for(int index = 0; index < field.fields.length; index++){
                if(field.fields[index] == null) continue;
                out.i(index);
                for(int value : field.fields[index]) out.i(value);
            }
        }
        out.i(requests.size());
        for(Request request : requests.values()){
            out.i(request.id);
            out.i(request.unitId);
            out.i(request.tileX());
            out.i(request.tileY());
            out.b(request.team);
            out.b(request.costId);
            out.i(request.destination);
            out.i(request.resultPath.size);
            for(int i = 0; i < request.resultPath.size; i++) out.i(request.resultPath.get(i));
            out.l(request.lastUpdateId);
            out.l(request.lastRecomputeTime);
            out.bool(request.notFound);
            out.bool(request.invalidated);
            out.i(request.oldCache == null ? -1 : request.oldCache.id);
            out.bool(request.lastRaycastResult);
            out.i(request.lastRaycastTile);
            out.i(request.lastWorldUpdate);
            out.i(request.lastTile);
            out.i(request.lastTargetTile);
        }
        writeIds(out, unitRequests);
        writeIds(out, threadPathRequests);
        writeIds(out, invalidRequests);
        out.i(fields.size());
        for(Field field : fields.values()) out.i(field.id);
        out.i(fieldList.size());
        for(Field field : fieldList) out.i(field.id);
        out.i(queue.size());
        for(Task task : queue){
            switch(task){
                case Start(Request request) -> {
                    out.b(0);
                    out.i(request.id);
                }
                case Recalculate(Request request) -> {
                    out.b(1);
                    out.i(request.id);
                }
                case Drop(Request request) -> {
                    out.b(2);
                    out.i(request.id);
                }
                case Forget(long key) -> {
                    out.b(3);
                    out.l(key);
                }
                case Grow(Field cache, int cx, int cy) -> {
                    out.b(4);
                    out.i(cache.id);
                    out.i(cx);
                    out.i(cy);
                }
                case Repair(int cluster, boolean edge) -> {
                    out.b(5);
                    out.i(cluster);
                    out.bool(edge);
                }
            }
        }
        for(int team = 0; team < clusters.length; team++){
            Cluster[][] dim1 = clusters[team];
            out.bool(dim1 != null);
            if(dim1 == null) continue;
            for(int costId = 0; costId < COSTS; costId++){
                Cluster[] dim2 = dim1[costId];
                out.bool(dim2 != null);
                if(dim2 == null) continue;
                for(int index = 0; index < dim2.length; index++){
                    Cluster cluster = dim2[index];
                    out.bool(cluster != null);
                    if(cluster == null) continue;
                    for(int d = 0; d < 4; d++){
                        IntSeq list = cluster.portals[d];
                        // left and bottom neighbours come first: a list they share is written once
                        Cluster neighbour = d >= 2 ? neighbour(dim2, index, d) : null;
                        boolean shared = list != null && neighbour != null && neighbour.portals[(d + 2) % 4] == list;
                        out.b(list == null ? 0 : shared ? 1 : 2);
                        if(list != null && !shared){
                            out.i(list.size);
                            out.i(list.items.length);
                            for(int value : list.items) out.i(value);
                        }
                        Edges[] edges = cluster.portalConnections[d];
                        out.i(edges == null ? -1 : edges.length);
                        if(edges == null) continue;
                        for(Edges e : edges){
                            out.i(e == null ? -1 : e.size);
                            if(e != null) for(int i = 0; i < e.size; i++) out.l(e.items[i]);
                        }
                    }
                }
            }
        }
    }

    private Cluster neighbour(Cluster[] dim2, int index, int d){
        int cx = index % cwidth + NEXT_OFFSETS[d * 2], cy = index / cwidth + NEXT_OFFSETS[d * 2 + 1];
        return cx < 0 || cy < 0 || cx >= cwidth || cy >= cheight ? null : dim2[cx + cy * cwidth];
    }

    private static void writeIds(Writes out, List<Request> requests){
        out.i(requests.size());
        for(Request request : requests) out.i(request.id);
    }

    @Override
    public void read(World world, Reads in){
        int version = in.i();
        if(version != VERSION) throw new CheckpointException("control-paths section version " + version + ", expected " + VERSION);
        boolean stored = in.bool();
        reset();
        init(world);
        if(stored != active) throw new CheckpointException("checkpoint and world disagree on whether paths are active");
        if(!active) return;
        if(in.i() != width || in.i() != height) throw new CheckpointException("control-paths section is for another map size");
        started = in.bool();
        nextPass = in.l();
        lastInvalidCheck = in.l();
        frozenTicks = in.l();
        updateId = world.ticks() - frozenTicks;
        clock = world.ticks();
        tileChanges = in.i();
        steps = in.i();
        nextId = in.i();
        updateHigh = in.i();
        innerHigh = in.i();
        // the update sets are empty between passes; their tables grow as they did
        for(int i = 1; i <= updateHigh; i++) clustersToUpdate.add(i);
        clustersToUpdate.clear();
        for(int i = 1; i <= innerHigh; i++) clustersToInnerUpdate.add(i);
        clustersToInnerUpdate.clear();

        var fieldById = new TreeMap<Integer, Field>();
        for(int n = in.i(); n > 0; n--){
            int id = in.i(), costId = in.ub(), team = in.ub(), goalPos = in.i();
            var field = new Field(id, costId, team, goalPos, cwidth * cheight, in.l());
            for(int i = in.i(); i > 0; i--) field.frontier.addLast(in.i());
            for(int i = in.i(); i > 0; i--){
                int[] values = field.fields[in.i()] = new int[CLUSTER_SIZE * CLUSTER_SIZE];
                for(int j = 0; j < values.length; j++) values[j] = in.i();
            }
            fieldById.put(id, field);
        }
        var units = new TreeMap<Integer, Unit>();
        ActiveList<Unit> live = world.units();
        for(int i = 0; i < live.size(); i++) units.put(live.get(i).id(), live.get(i));
        var requestById = new TreeMap<Integer, Request>();
        for(int n = in.i(); n > 0; n--){
            int id = in.i(), unitId = in.i(), lastX = in.i(), lastY = in.i(), team = in.ub(), costId = in.ub(), destination = in.i();
            Unit unit = units.get(unitId);
            var request = new Request(id, unitId, unit, team, costId, destination, 0);
            request.lastX = lastX;
            request.lastY = lastY;
            for(int i = in.i(); i > 0; i--) request.resultPath.add(in.i());
            request.lastUpdateId = in.l();
            request.lastRecomputeTime = in.l();
            request.notFound = in.bool();
            request.invalidated = in.bool();
            int old = in.i();
            request.oldCache = old == -1 ? null : fieldById.get(old);
            request.lastRaycastResult = in.bool();
            request.lastRaycastTile = in.i();
            request.lastWorldUpdate = in.i();
            request.lastTile = in.i();
            request.lastTargetTile = in.i();
            requestById.put(id, request);
        }
        for(int n = in.i(); n > 0; n--){
            Request request = requestById.get(in.i());
            unitRequests.add(request);
            byUnit.put(request.unitId, request);
        }
        for(int n = in.i(); n > 0; n--) threadPathRequests.add(requestById.get(in.i()));
        for(int n = in.i(); n > 0; n--){
            Request request = requestById.get(in.i());
            request.invalid = true;
            invalidRequests.add(request);
        }
        for(int n = in.i(); n > 0; n--){
            Field field = fieldById.get(in.i());
            fields.put(field.mapKey, field);
        }
        for(int n = in.i(); n > 0; n--) fieldList.add(fieldById.get(in.i()));
        for(int n = in.i(); n > 0; n--){
            int kind = in.ub();
            queue.add(switch(kind){
                case 0 -> new Start(requestById.get(in.i()));
                case 1 -> new Recalculate(requestById.get(in.i()));
                case 2 -> new Drop(requestById.get(in.i()));
                case 3 -> new Forget(in.l());
                case 4 -> new Grow(fieldById.get(in.i()), in.i(), in.i());
                case 5 -> new Repair(in.i(), in.bool());
                default -> throw new CheckpointException("control-paths: unknown task " + kind);
            });
        }
        requestedFromQueue();
        for(int team = 0; team < clusters.length; team++){
            if(!in.bool()) continue;
            Cluster[][] dim1 = clusters[team] = new Cluster[COSTS][];
            for(int costId = 0; costId < COSTS; costId++){
                if(!in.bool()) continue;
                Cluster[] dim2 = dim1[costId] = new Cluster[cwidth * cheight];
                for(int index = 0; index < dim2.length; index++){
                    if(!in.bool()) continue;
                    var cluster = dim2[index] = new Cluster();
                    for(int d = 0; d < 4; d++){
                        switch(in.ub()){
                            case 0 -> {
                            }
                            case 1 -> cluster.portals[d] = neighbour(dim2, index, d).portals[(d + 2) % 4];
                            default -> {
                                int size = in.i();
                                var list = cluster.portals[d] = new IntSeq(in.i());
                                for(int i = 0; i < list.items.length; i++) list.items[i] = in.i();
                                list.size = size;
                            }
                        }
                        int length = in.i();
                        if(length == -1) continue;
                        Edges[] edges = cluster.portalConnections[d] = new Edges[length];
                        for(int i = 0; i < length; i++){
                            int size = in.i();
                            if(size == -1) continue;
                            var e = edges[i] = new Edges();
                            for(int j = 0; j < size; j++) e.add(in.l());
                        }
                    }
                }
            }
        }
    }

    // for the tests in this package

    List<Request> unitRequests(){
        return unitRequests;
    }

    List<Request> threadPathRequests(){
        return threadPathRequests;
    }

    List<Request> invalidRequests(){
        return invalidRequests;
    }

    Map<Long, Field> fields(){
        return fields;
    }

    List<Field> fieldList(){
        return fieldList;
    }

    List<Task> queue(){
        return queue;
    }

    int cwidth(){
        return cwidth;
    }

    /** The tick of the next pass, and of the last invalid check: the schedule an oracle drives vanilla by. */
    long nextPass(){
        return nextPass;
    }

    long lastInvalidCheck(){
        return lastInvalidCheck;
    }

    /** Binds the path grid for tests that call the searches directly. */
    void bindForTests(World world){
        bind(world);
    }

    /** A request with no unit, standing on (x, y), for tests that run the searches directly. */
    Request request(int team, int costId, int destination, int x, int y){
        var request = new Request(nextId++, -1, null, team, costId, destination, updateId);
        request.lastX = x;
        request.lastY = y;
        return request;
    }

    /** Posts {@code request} as a new one would be: added to the thread's list and computed at the next pass. */
    void start(Request request){
        post(new Start(request));
    }

    IntSet clustersToUpdate(){
        return clustersToUpdate;
    }

    IntSet clustersToInnerUpdate(){
        return clustersToInnerUpdate;
    }

    Cluster[][][] clusters(){
        return clusters;
    }

    /** A field as {@link #initializePathRequest} reads it, or null. */
    Field field(int goalPos, int costId, int team){
        return fields.get(fieldKey(goalPos, costId, team));
    }

    // endregion
    // region structs

    /** {@code NodeIndex}: cluster in bits 0–21, direction in 22–23, portal in 24–31. */
    static int nodeIndex(int cluster, int dir, int portal){
        return (int)(((long)cluster & 0x3fffffL) | ((long)(dir << 22) & 0xc00000L) | ((long)(portal << 24) & 0xff000000L));
    }

    static int nodeCluster(int node){
        return node & 0x3fffff;
    }

    static int nodeDir(int node){
        return (node >>> 22) & 3;
    }

    static int nodePortal(int node){
        return (node >>> 24) & 0xff;
    }

    /** {@code IntraEdge}: direction in bits 0–7, portal in 8–15, the cost's float bits in 16–47. */
    static long intraEdge(int dir, int portal, float cost){
        return ((long)dir & 0xffL) | (((long)portal << 8) & 0xff00L) | ((long)Float.floatToIntBits(cost) << 16);
    }

    static int edgeDir(long edge){
        return (int)(edge & 0xff);
    }

    static int edgePortal(long edge){
        return (int)((edge >>> 8) & 0xff);
    }

    static float edgeCost(long edge){
        return Float.intBitsToFloat((int)((edge >>> 16) & 0xffffffffL));
    }

    /** {@code FieldIndex}: the goal tile, the cost id and the team. */
    static long fieldKey(int pos, int costId, int team){
        return (pos & 0xffffffffL) | ((long)(costId & 0xff) << 32) | ((long)(team & 0xff) << 40);
    }

    /** One cluster for one team and cost type: its portals per direction (shared with the neighbour) and the edges between them. */
    static final class Cluster{
        IntSeq[] portals = new IntSeq[4];
        /** By direction, then portal: the {@link #intraEdge}s to the other portals. */
        Edges[][] portalConnections = new Edges[4][];
    }

    /** Arc's {@code LongSeq}, for the edges of one portal. */
    static final class Edges{
        long[] items = new long[8];
        int size;

        void add(long value){
            if(size == items.length) items = Arrays.copyOf(items, Math.max(8, (int)(size * 1.75f)));
            items[size++] = value;
        }
    }

    /** {@code FieldCache}: one flow field towards {@link #goalPos}, over the clusters requests asked for. */
    static final class Field{
        final int id, costId, team, goalPos;
        final long mapKey;
        IntQueue frontier = new IntQueue();
        /** By cluster index; 0 means unreached, or the goal. */
        final int[][] fields;
        long lastUpdateId;
        /** Clusters a {@link Grow} task is queued for; derived from the queue. */
        Bits requested;

        Field(int id, int costId, int team, int goalPos, int clusters, long updateId){
            this.id = id;
            this.costId = costId;
            this.team = team;
            this.goalPos = goalPos;
            this.mapKey = fieldKey(goalPos, costId, team);
            this.fields = new int[clusters][];
            this.lastUpdateId = updateId;
        }

        Field copy(){
            var out = new Field(id, costId, team, goalPos, fields.length, lastUpdateId);
            out.frontier = frontier.copy();
            for(int i = 0; i < fields.length; i++) out.fields[i] = fields[i] == null ? null : fields[i].clone();
            return out;
        }
    }

    /** {@code PathRequest}: one unit's goal, its portal path, and what {@link #pathPosition} caches between frames. */
    static final class Request{
        final int id;
        /** The asking unit, or null for one that left the world before a checkpoint, then at its last tile. */
        Unit unit;
        final int unitId;
        int lastX, lastY;
        final int destination, team, costId;
        final IntSeq resultPath = new IntSeq();
        long lastUpdateId, lastRecomputeTime;
        boolean notFound, invalidated;
        /** In {@code invalidRequests}. */
        boolean invalid;
        Field oldCache;
        boolean lastRaycastResult;
        int lastRaycastTile, lastWorldUpdate, lastTile;
        int lastTargetTile = -1;

        Request(int id, int unitId, Unit unit, int team, int costId, int destination, long updateId){
            this.id = id;
            this.unitId = unitId;
            this.unit = unit;
            this.team = team;
            this.costId = costId;
            this.destination = destination;
            this.lastUpdateId = updateId;
        }

        /** A copy for a fork, its unit already mapped; the old field is the caller's to map. */
        Request copy(Unit mapped){
            var out = new Request(id, unitId, mapped, team, costId, destination, lastUpdateId);
            out.lastX = lastX;
            out.lastY = lastY;
            out.resultPath.addAll(resultPath);
            out.lastRecomputeTime = lastRecomputeTime;
            out.notFound = notFound;
            out.invalidated = invalidated;
            out.invalid = invalid;
            out.lastRaycastResult = lastRaycastResult;
            out.lastRaycastTile = lastRaycastTile;
            out.lastWorldUpdate = lastWorldUpdate;
            out.lastTile = lastTile;
            out.lastTargetTile = lastTargetTile;
            return out;
        }

        boolean added(){
            return unit != null && unit.isAdded();
        }

        int tileX(){
            return unit == null ? lastX : unit.tileX();
        }

        int tileY(){
            return unit == null ? lastY : unit.tileY();
        }
    }

    /** What the main thread and the invalid check post for the next pass. */
    sealed interface Task permits Start, Recalculate, Drop, Forget, Grow, Repair{
    }

    /** {@code threadPathRequests.add(request); recalculatePath(request)}. */
    record Start(Request request) implements Task{
    }

    record Recalculate(Request request) implements Task{
    }

    /** {@code threadPathRequests.remove(request)}. */
    record Drop(Request request) implements Task{
    }

    /** {@code fields.remove(key)}: whatever field has the key by then. */
    record Forget(long key) implements Task{
    }

    /** {@code addFlowCluster(cache, cx, cy, true)}. */
    record Grow(Field cache, int cx, int cy) implements Task{
    }

    /** {@code clustersToUpdate.add(cluster)}, or {@code clustersToInnerUpdate} for a tile inside it. */
    record Repair(int cluster, boolean edge) implements Task{
    }

    // endregion
}
