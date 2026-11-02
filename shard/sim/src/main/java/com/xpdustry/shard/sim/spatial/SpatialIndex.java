package com.xpdustry.shard.sim.spatial;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * The unit grids of note 29 and the unit queries of vanilla's {@code Units}, with vanilla's answers.
 *
 * <p>Two kinds of grid stand in for vanilla's unit quadtrees:
 * <ul>
 *   <li>one per team ({@code TeamData.unitTree}), filled at {@code TEAMS} by {@link TeamStats} from {@code Groups.unit};
 *   as {@code QuadTree.insert} does, a unit whose hitbox is outside the quadtree bounds is left out. The team queries
 *   ({@link #nearby(int, float, float, float, float, UnitVisitor) nearby(team…)}, {@link #nearbyEnemies},
 *   {@link #closestEnemy}, {@link #bestEnemy}, {@link #closestTarget}) read these;</li>
 *   <li>one for every unit ({@code Groups.unit}'s tree), filled at {@code PHYSICS} by {@link EntityPhysics}; as
 *   {@code QuadTree.fill} does, it keeps every unit. {@link #intersect},
 *   {@link #count}, {@link #any}, {@link #nearbyCheck} and {@link #anyEntities} read it.</li>
 * </ul>
 * So, as in vanilla, a unit added mid-tick is in the all-units grid from the next {@code PHYSICS} and in its team's grid
 * from the next {@code TEAMS}, and a removed unit stays in both until they are filled again; the predicates of the
 * vanilla queries filter it ({@code dead}, {@code isValid}) where vanilla's do.
 *
 * <p>{@link #nearbyEnemies} hands out units in vanilla's own order: per enemy team, its quadtree as {@code insert}
 * built it ({@link UnitTree}). Its callers act on every unit they get (a bullet hits the first, splash damage and
 * lightning hit each in turn), so the order is part of the result. {@link #nearby(int, float, float, float, float, UnitVisitor) nearby(team…)} and
 * {@link #nearbyAnyTeam} do too. The other queries read the grids: their visitors see
 * units in cell order, and best-of queries ({@link #closestEnemy}, {@link #bestEnemy}) break exact ties by that order
 * where vanilla breaks them by its tree's. Teams are visited in ascending id, as {@code state.teams.present} is. Units in fog to the searching team are left out ({@code inFogTo}, {@code sim.fog}).
 *
 * <p>Queries keep no scratch: each one carries its state in a small visitor of its own, so a visitor may run another
 * query.
 *
 * <p>{@link Persistence#AUTHORITATIVE}: the grids' members (see {@link UnitGrid}); their placement is rebuilt on demand
 * and dropped at the end of every tick by {@link IndexExpiry}.
 */
@Register
public final class SpatialIndex implements WorldPart, Checkpointable{
    /** {@code Team.derelict}. */
    static final int DERELICT = 0;
    private static final int TEAMS = TeamUnits.TEAMS;

    /** The map's size; stored, since a restored part gets no {@code init}. */
    private int tilesWide, tilesHigh;
    private UnitGrid all;
    /** Per team, null until the team has a unit. */
    private final UnitGrid[] teams;
    /** The teams whose grid has members, ascending. */
    private int[] present;
    private int presentCount;
    /** {@link #closestEnemy}'s visitors, one per nesting depth (a filter may query again), reused: none per query. */
    @Derived("scratch, emptied when each query returns")
    private ClosestEnemy[] closestEnemies = {new ClosestEnemy()};
    @Derived("scratch: how many closestEnemy queries are running")
    private int closestDepth;

    public SpatialIndex(){
        teams = new UnitGrid[TEAMS];
        present = new int[4];
    }

    private SpatialIndex(SpatialIndex source, CopyContext context){
        // the scratch visitors, idle between ticks: as many fresh ones
        closestEnemies = new ClosestEnemy[source.closestEnemies.length];
        for(int i = 0; i < closestEnemies.length; i++) if(source.closestEnemies[i] != null) closestEnemies[i] = new ClosestEnemy();
        tilesWide = source.tilesWide;
        tilesHigh = source.tilesHigh;
        all = source.all.copy(context);
        teams = new UnitGrid[TEAMS];
        for(int team = 0; team < TEAMS; team++){
            if(source.teams[team] != null) teams[team] = source.teams[team].copy(context);
        }
        present = source.present.clone();
        presentCount = source.presentCount;
    }

    @Override
    public void init(World world){
        tilesWide = world.tiles().width();
        tilesHigh = world.tiles().height();
        all = new UnitGrid(tilesWide, tilesHigh);
    }

    /** The world's index, or null in a world built without it. */
    public static SpatialIndex of(World world){
        return world.find(SpatialIndex.class);
    }

    // ---------------------------------------------------------------- fills

    /** {@code Teams.updateTeamStats}: refills every team's grid from {@code units}, in order. */
    public void fillTeams(ActiveList<Unit> units){
        for(int team = 0; team < TEAMS; team++){
            if(teams[team] != null) teams[team].clear();
        }
        // World.getQuadBounds, which QuadTree.insert checks
        float boundsW = tilesWide * 8 + CellGrid.MARGIN * 2, boundsH = tilesHigh * 8 + CellGrid.MARGIN * 2;
        for(int i = 0; i < units.size(); i++){
            Unit unit = units.get(i);
            if(unit.type() == null || !UnitGrid.overlaps(unit, -CellGrid.MARGIN, -CellGrid.MARGIN, boundsW, boundsH)) continue;
            UnitGrid grid = teams[unit.team];
            if(grid == null) grid = teams[unit.team] = new UnitGrid(tilesWide, tilesHigh);
            grid.add(unit);
        }
        presentCount = 0;
        for(int team = 0; team < TEAMS; team++){
            UnitGrid grid = teams[team];
            if(grid == null || grid.size() == 0) continue;
            grid.sort();
            if(presentCount == present.length) present = Arrays.copyOf(present, presentCount * 2);
            present[presentCount++] = team;
        }
    }

    /** {@code Groups.unit.updatePhysics()}, the tree part: refills the all-units grid from {@code units}, in order. */
    public void fillUnits(ActiveList<Unit> units){
        all.clear();
        for(int i = 0; i < units.size(); i++) all.add(units.get(i));
        all.sort();
    }

    /** Drops every grid's placement; the members stay. End of tick. */
    void expire(){
        all.expire();
        for(int i = 0; i < presentCount; i++) teams[present[i]].expire();
    }

    // ---------------------------------------------------------------- all units (Groups.unit)

    /** {@code Units.nearby(x, y, width, height, cons)}, which is {@code Groups.unit.intersect}. */
    public void intersect(float x, float y, float width, float height, UnitVisitor visitor){
        all.intersect(x, y, width, height, visitor);
    }

    /** {@code Units.nearbyCheck}: true as soon as {@code check} accepts a unit. */
    public boolean nearbyCheck(float x, float y, float width, float height, UnitFilter check){
        return all.check(x, y, width, height, check);
    }

    /** {@code Units.any(x, y, width, height, filter)}. */
    public boolean any(float x, float y, float width, float height, UnitFilter filter){
        return all.check(x, y, width, height, filter);
    }

    /** {@code Units.count(x, y, width, height, filter)}. */
    public int count(float x, float y, float width, float height, UnitFilter filter){
        var counter = new Counter(filter);
        all.intersect(x, y, width, height, counter);
        return counter.count;
    }

    /** {@code Units.count(x, y, size, filter)}: the square of side {@code size} centred on (x, y). */
    public int count(float x, float y, float size, UnitFilter filter){
        return count(x - size / 2f, y - size / 2f, size, size, filter);
    }

    /**
     * {@code Units.anyEntities(x, y, width, height, ground)}: whether a unit's tile hitbox overlaps the rectangle, among
     * ground units ({@code isGrounded() && !allowLegStep}) when {@code ground}, else among the others.
     */
    public boolean anyEntities(float x, float y, float width, float height, boolean ground){
        return nearbyCheck(x, y, width, height, new TileOverlap(x, y, width, height, ground, null));
    }

    /** {@code Units.anyEntities(x, y, size)}: ground units on the square of side {@code size} centred on (x, y). */
    public boolean anyEntities(float x, float y, float size){
        return anyEntities(x - size / 2f, y - size / 2f, size, size, true);
    }

    /** {@code Units.anyEntities(x, y, width, height, check)}: units {@code check} accepts whose tile hitbox overlaps. */
    public boolean anyEntities(float x, float y, float width, float height, UnitFilter check){
        return nearbyCheck(x, y, width, height, new TileOverlap(x, y, width, height, false, check));
    }

    // ---------------------------------------------------------------- team grids

    /**
     * {@code Units.nearby(team, x, y, width, height, cons)}: {@code team.data().tree().intersect}, in the tree's order
     * ({@link UnitGrid#intersectTree}): a cut-off (repair fields) and a tie (energy fields) depend on it.
     */
    public void nearby(int team, float x, float y, float width, float height, UnitVisitor visitor){
        UnitGrid grid = teams[team];
        if(grid != null) grid.intersectTree(x, y, width, height, visitor);
    }

    /** {@code Units.nearby(team, x, y, radius, cons)}: also within {@code radius} plus half the hit size. */
    public void nearby(int team, float x, float y, float radius, UnitVisitor visitor){
        nearby(team, x - radius, y - radius, radius * 2f, radius * 2f, new Within(x, y, radius, visitor));
    }

    /** {@code Units.nearby(null, x, y, width, height, cons)}: every present team's tree in turn, in its own order. */
    public void nearbyAnyTeam(float x, float y, float width, float height, UnitVisitor visitor){
        for(int i = 0; i < presentCount; i++) teams[present[i]].intersectTree(x, y, width, height, visitor);
    }

    /**
     * {@code Units.nearbyEnemies(team, x, y, width, height, cons)}: every present team but {@code team}, each in its
     * quadtree's order ({@link UnitGrid#intersectTree}), as {@code EntityCollisions} and {@code Damage} see them.
     */
    public void nearbyEnemies(int team, float x, float y, float width, float height, UnitVisitor visitor){
        for(int i = 0; i < presentCount; i++){
            if(present[i] != team) teams[present[i]].intersectTree(x, y, width, height, visitor);
        }
    }

    /** {@link #nearbyEnemies} in cell order, for the best-of queries. */
    private void gridEnemies(int team, float x, float y, float width, float height, UnitVisitor visitor){
        for(int i = 0; i < presentCount; i++){
            if(present[i] != team) teams[present[i]].intersect(x, y, width, height, visitor);
        }
    }

    /** {@code Units.nearbyEnemies(team, x, y, radius, cons)}. */
    public void nearbyEnemies(int team, float x, float y, float radius, UnitVisitor visitor){
        nearbyEnemies(team, x - radius, y - radius, radius * 2f, radius * 2f, new Within(x, y, radius, visitor));
    }

    /**
     * {@code Units.nearEnemy}: whether a unit of another non-derelict team overlaps the rectangle, or one of its
     * turrets does ({@link BuildingTargets#anyEnemyTurret}).
     */
    public boolean nearEnemy(int team, float x, float y, float width, float height, BuildingTargets buildings){
        for(int i = 0; i < presentCount; i++){
            int other = present[i];
            if(other != team && other != DERELICT && teams[other].any(x, y, width, height)) return true;
        }
        return buildings.anyEnemyTurret(team, x, y, width, height);
    }

    /**
     * {@code Units.closestEnemy}: the enemy unit with the highest {@code targetPriority}, then the smallest
     * {@code dst2 - hitSize²} below {@code range²}, among the live, targetable, visible units {@code filter} accepts.
     */
    public Unit closestEnemy(int team, float x, float y, float range, UnitFilter filter){
        if(team == DERELICT) return null;
        if(closestDepth == closestEnemies.length) closestEnemies = Arrays.copyOf(closestEnemies, closestDepth * 2);
        ClosestEnemy closest = closestEnemies[closestDepth];
        if(closest == null) closestEnemies[closestDepth] = closest = new ClosestEnemy();
        closest.start(team, x, y, range, filter);
        closestDepth++;
        try{
            gridEnemies(team, x - range, y - range, range * 2f, range * 2f, closest);
            return closest.result;
        }finally{
            closestDepth--;
            closest.start(0, 0f, 0f, 0f, null);
        }
    }

    /**
     * {@code Units.bestEnemy}: as {@link #closestEnemy} but within {@code range} plus half the hit size, ranked by
     * {@code sort} after priority.
     */
    public Unit bestEnemy(int team, float x, float y, float range, UnitFilter filter, UnitSort sort){
        if(team == DERELICT) return null;
        var best = new BestEnemy(team, x, y, range, filter, sort);
        gridEnemies(team, x - range, y - range, range * 2f, range * 2f, best);
        return best.result;
    }

    /** {@code Units.closest(team, x, y, range, predicate)}: the nearest valid unit of {@code team}. */
    public Unit closest(int team, float x, float y, float range, UnitFilter filter){
        return closest(team, x, y, range, filter, UnitSort.CLOSEST);
    }

    /** {@code Units.closest(team, x, y, range, predicate, sort)}. */
    public Unit closest(int team, float x, float y, float range, UnitFilter filter, UnitSort sort){
        var closest = new Closest(x, y, filter, sort);
        nearby(team, x, y, range, closest);
        return closest.result;
    }

    /** {@code Units.closestOverlap}: as {@link #closest} but only the hitbox must overlap the range square. */
    public Unit closestOverlap(int team, float x, float y, float range, UnitFilter filter){
        var closest = new Closest(x, y, filter, UnitSort.CLOSEST);
        nearby(team, x - range, y - range, range * 2f, range * 2f, closest);
        return closest.result;
    }

    /** {@code Units.closestTarget(team, x, y, range)}: valid units, any building. */
    public Entity closestTarget(int team, float x, float y, float range, BuildingTargets buildings){
        return closestTarget(team, x, y, range, UnitFilter.VALID, BuildingFilter.ANY, -1, buildings);
    }

    /**
     * {@code Units.closestTarget(team, x, y, range, sourceTeam, unitPred, tilePred)}: the {@link #closestEnemy}, else the
     * enemy building {@code buildings} finds ({@code BlockIndexer.findEnemyTile}). {@code source} is a team id or -1.
     */
    public Entity closestTarget(int team, float x, float y, float range, UnitFilter units, BuildingFilter tiles, int source,
                                BuildingTargets buildings){
        if(team == DERELICT) return null;
        Unit unit = closestEnemy(team, x, y, range, units);
        return unit != null ? unit : buildings.findEnemyTile(team, x, y, range, tiles, source);
    }

    /** {@code Units.bestTarget}: the {@link #bestEnemy}, else the enemy building. */
    public Entity bestTarget(int team, float x, float y, float range, UnitFilter units, BuildingFilter tiles, UnitSort sort,
                             BuildingTargets buildings){
        if(team == DERELICT) return null;
        Unit unit = bestEnemy(team, x, y, range, units, sort);
        return unit != null ? unit : buildings.findEnemyTile(team, x, y, range, tiles, -1);
    }

    // ---------------------------------------------------------------- part

    @Override
    public WorldPart copy(CopyContext context){
        return new SpatialIndex(this, context);
    }

    /** A team's grid, for the teams that have one: the rest stay null, as they were. */
    private record TeamGrid(int team, UnitGrid grid){
    }

    @Override
    public void write(CheckpointOut out){
        var grids = new ArrayList<TeamGrid>();
        for(int team = 0; team < TEAMS; team++){
            if(teams[team] != null) grids.add(new TeamGrid(team, teams[team]));
        }
        out.i("tilesWide", tilesWide).i("tilesHigh", tilesHigh).obj("all", all).list("teams", grids)
            .ints("present", present, presentCount).i("presentCount", presentCount);
    }

    @Override
    public void read(CheckpointIn in){
        tilesWide = in.i("tilesWide");
        tilesHigh = in.i("tilesHigh");
        all = in.obj("all", UnitGrid.class);
        if(all == null) throw new CheckpointException("the all-units grid is missing");
        all.bind(tilesWide, tilesHigh);
        for(TeamGrid row : in.list("teams", TeamGrid.class)){
            if(row.team() < 0 || row.team() >= TEAMS || row.grid() == null) throw new CheckpointException("a team grid is corrupt");
            teams[row.team()] = row.grid();
            row.grid().bind(tilesWide, tilesHigh);
        }
        int[] stored = in.ints("present");
        presentCount = in.i("presentCount");
        if(stored == null || stored.length != presentCount) throw new CheckpointException("the present teams are inconsistent");
        for(int team : stored) if(team < 0 || team >= TEAMS || teams[team] == null) throw new CheckpointException("team " + team + " is present without a grid");
        present = Arrays.copyOf(stored, Math.max(4, presentCount));
    }

    @Override
    public void hash(Hasher hasher){
        hash(hasher, true);
    }

    /** {@code fills} false leaves the grids' fill hitboxes out, as the hash was before W6.1 ({@code SpatialHashToggleTest}). */
    void hash(Hasher hasher, boolean fills){
        all.hash(hasher, fills);
        hasher.i(presentCount);
        for(int i = 0; i < presentCount; i++){
            hasher.i(present[i]);
            teams[present[i]].hash(hasher, fills);
        }
    }

    // ---------------------------------------------------------------- the vanilla lambdas, as named visitors

    /** {@code Units.closestEnemy}'s lambda, with its static {@code result}, {@code cdist} and {@code cpriority}. */
    private static final class ClosestEnemy implements UnitVisitor{
        int team;
        float x, y, range;
        UnitFilter filter;
        Unit result;
        float cdist, cpriority;

        ClosestEnemy(){
            start(0, 0f, 0f, 0f, null);
        }

        /** A new query: as a new visitor would start it. */
        void start(int team, float x, float y, float range, UnitFilter filter){
            this.team = team;
            this.x = x;
            this.y = y;
            this.range = range;
            this.filter = filter;
            result = null;
            cdist = 0f;
            cpriority = -99999f;
        }

        @Override
        public void visit(Unit e){
            if(e.dead || !filter.test(e) || e.team == DERELICT || !e.targetable(team) || e.inFogTo(team)) return;
            float dst2 = e.dst2(x, y) - (e.hitSize * e.hitSize);
            float priority = e.targetPriority();
            if(dst2 < range * range && (result == null || dst2 < cdist || priority > cpriority) && priority >= cpriority){
                result = e;
                cdist = dst2;
                cpriority = priority;
            }
        }
    }

    /** {@code Units.bestEnemy}'s lambda. */
    private static final class BestEnemy implements UnitVisitor{
        final int team;
        final float x, y, range;
        final UnitFilter filter;
        final UnitSort sort;
        Unit result;
        float cdist = 0f, cpriority = -99999f;

        BestEnemy(int team, float x, float y, float range, UnitFilter filter, UnitSort sort){
            this.team = team;
            this.x = x;
            this.y = y;
            this.range = range;
            this.filter = filter;
            this.sort = sort;
        }

        @Override
        public void visit(Unit e){
            if(e.dead || !filter.test(e) || e.team == DERELICT || !e.within(x, y, range + e.hitSize / 2f)
                || !e.targetable(team) || e.inFogTo(team)) return;
            float cost = sort.cost(e, x, y);
            float priority = e.targetPriority();
            if((result == null || cost < cdist || priority > cpriority) && priority >= cpriority){
                result = e;
                cdist = cost;
                cpriority = priority;
            }
        }
    }

    /** {@code Units.closest}'s lambda. */
    private static final class Closest implements UnitVisitor{
        final float x, y;
        final UnitFilter filter;
        final UnitSort sort;
        Unit result;
        float cdist = 0f;

        Closest(float x, float y, UnitFilter filter, UnitSort sort){
            this.x = x;
            this.y = y;
            this.filter = filter;
            this.sort = sort;
        }

        @Override
        public void visit(Unit e){
            if(!e.isValid() || !filter.test(e)) return;
            float dist = sort.cost(e, x, y);
            if(result == null || dist < cdist){
                result = e;
                cdist = dist;
            }
        }
    }

    /** The circle wrapper of {@code Units.nearby(team, x, y, radius, cons)} and {@code nearbyEnemies}. */
    private record Within(float x, float y, float radius, UnitVisitor visitor) implements UnitVisitor{
        @Override
        public void visit(Unit unit){
            if(unit.within(x, y, radius + unit.hitSize / 2f)) visitor.visit(unit);
        }
    }

    /** {@code Units.count}'s lambda and its static {@code intResult}. */
    private static final class Counter implements UnitVisitor{
        final UnitFilter filter;
        int count;

        Counter(UnitFilter filter){
            this.filter = filter;
        }

        @Override
        public void visit(Unit unit){
            if(filter.test(unit)) count++;
        }
    }

    /** {@code Units.anyEntityLambda} (with {@code check} null) and the lambda of {@code anyEntities(…, check)}. */
    private record TileOverlap(float x, float y, float width, float height, boolean ground, UnitFilter check)
        implements UnitFilter{
        @Override
        public boolean test(Unit unit){
            if(check != null ? check.test(unit) : (unit.isGrounded() && !unit.params().allowLegStep()) == ground){
                // HitboxComp.hitboxTile
                float size = Math.min(unit.hitSize * 0.66f, 7.8f), left = unit.x - size / 2f, bottom = unit.y - size / 2f;
                return left < x + width && left + size > x && bottom < y + height && bottom + size > y;
            }
            return false;
        }
    }
}
