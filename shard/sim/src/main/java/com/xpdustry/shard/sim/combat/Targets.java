package com.xpdustry.shard.sim.combat;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;
import java.util.function.*;

/**
 * The small vanilla methods combat calls on what it hits, for targets that are units or buildings: {@code Teamc},
 * {@code Healthc}, {@code Posc} and {@code Units.invalidateTarget}; and the target searches of vanilla's {@code Units}
 * that mix units and buildings ({@code closestTarget}, {@code bestTarget}, {@code findEnemyTile}, {@code findAllyTile}).
 *
 * <p>The searches take units from {@link SpatialIndex} (its team grids, so its cell order between exact ties) and
 * buildings from each team's {@link Buildings#tree(int) buildingTree}, in Arc's quadtree order, as
 * {@code BlockIndexer.findTile} reads them. Teams are visited in the indexer's {@code activeTeams} order.
 */
public final class Targets{
    /** Vanilla's {@code null} team where a query takes one: every team. */
    public static final int ANY_TEAM = -1;

    /** Vanilla's {@code UnitSorts.BuildingPriorityf}: a building's target priority, highest wins. */
    @FunctionalInterface
    public interface BuildingPriority{
        /** {@code UnitSorts.buildingDefault}: {@code b.block.priority}. */
        BuildingPriority DEFAULT = building -> building.info().priority;

        float priority(Building building);
    }

    private Targets(){
    }

    // ---------------------------------------------------------------- target searches (Units, BlockIndexer)

    /** {@code Units.closestTarget(team, x, y, range)}: valid units, then any building. */
    public static Entity closestTarget(World world, int team, float x, float y, float range){
        return closestTarget(world, team, x, y, range, ANY_TEAM, UnitFilter.VALID, BuildingFilter.ANY);
    }

    /** {@code Units.closestTarget(team, x, y, range, unitPred)}. */
    public static Entity closestTarget(World world, int team, float x, float y, float range, UnitFilter units){
        return closestTarget(world, team, x, y, range, ANY_TEAM, units, BuildingFilter.ANY);
    }

    /** {@code Units.closestTarget(team, x, y, range, unitPred, tilePred)}. */
    public static Entity closestTarget(World world, int team, float x, float y, float range, UnitFilter units,
                                      BuildingFilter tiles){
        return closestTarget(world, team, x, y, range, ANY_TEAM, units, tiles);
    }

    /**
     * {@code Units.closestTarget(team, x, y, range, sourceTeam, unitPred, tilePred)} (Units.java:272): the closest enemy
     * unit ({@link SpatialIndex#closestEnemy}), else the enemy building {@link #findEnemyTile(World, int, float, float,
     * float, BuildingPriority, BuildingFilter, int)} finds. {@code source} is a team id, or {@link #ANY_TEAM} for
     * vanilla's {@code null}; the source team's untargetable buildings count as targets. Derelict finds nothing.
     */
    public static Entity closestTarget(World world, int team, float x, float y, float range, int source, UnitFilter units,
                                      BuildingFilter tiles){
        if(team == VanillaIds.DERELICT) return null;
        SpatialIndex index = SpatialIndex.of(world);
        Unit unit = index == null ? null : index.closestEnemy(team, x, y, range, units);
        return unit != null ? unit : findEnemyTile(world, team, x, y, range, BuildingPriority.DEFAULT, tiles, source);
    }

    /**
     * {@code Units.bestTarget(team, x, y, range, unitPred, tilePred, sort)} (Units.java:284): the best enemy unit by
     * {@code sort} ({@link SpatialIndex#bestEnemy}), else {@link #findEnemyTile(World, int, float, float, float,
     * BuildingFilter)}.
     */
    public static Entity bestTarget(World world, int team, float x, float y, float range, UnitFilter units,
                                    BuildingFilter tiles, UnitSort sort){
        if(team == VanillaIds.DERELICT) return null;
        SpatialIndex index = SpatialIndex.of(world);
        Unit unit = index == null ? null : index.bestEnemy(team, x, y, range, units, sort);
        return unit != null ? unit : findEnemyTile(world, team, x, y, range, tiles);
    }

    /** {@code Units.findEnemyTile(team, x, y, range, pred)} (Units.java:222): nothing for derelict. */
    public static Building findEnemyTile(World world, int team, float x, float y, float range, BuildingFilter filter){
        if(team == VanillaIds.DERELICT) return null;
        return findEnemyTile(world, team, x, y, range, BuildingPriority.DEFAULT, filter, ANY_TEAM);
    }

    /**
     * {@code indexer.findEnemyTile(team, x, y, range, priority, pred, source)} (BlockIndexer.java:460): over the active
     * teams but {@code team} (and derelict, unless {@code coreCapture}), each team's best discovered building by
     * {@link #findTile}, then across teams the higher {@code priority}, else the closer edge at no lower priority.
     */
    public static Building findEnemyTile(World world, int team, float x, float y, float range, BuildingPriority priority,
                                         BuildingFilter filter, int source){
        Buildings buildings = world.buildings();
        boolean coreCapture = buildings.rules().coreCapture;
        IntSeq active = buildings.activeTeamsView();
        Building target = null;
        float targetDist = 0f;
        for(int i = 0; i < active.size; i++){
            int enemy = active.get(i);
            if(enemy == team || (enemy == VanillaIds.DERELICT && !coreCapture)) continue;
            Building candidate = findTile(world, enemy, x, y, range,
                b -> filter.test(b) && buildings.isDiscovered(b, team), true, source);
            if(candidate == null) continue;
            float dist = candidate.dst(x, y) - candidate.hitSize() / 2f;
            if(target == null || (dist < targetDist && priority.priority(candidate) >= priority.priority(target))
                || priority.priority(candidate) > priority.priority(target)){
                target = candidate;
                targetDist = dist;
            }
        }
        return target;
    }

    /** {@code Units.findAllyTile(team, x, y, range, pred)}: {@code indexer.findTile(team, …, pred)}, by distance only. */
    public static Building findAllyTile(World world, int team, float x, float y, float range, BuildingFilter filter){
        return findTile(world, team, x, y, range, filter, false, ANY_TEAM);
    }

    /**
     * {@code indexer.findTile(team, x, y, range, pred, usePriority, source)} (BlockIndexer.java:498): among
     * {@code team}'s buildings whose box meets the range square, in tree order, the one whose edge is closest and
     * within {@code range}; with {@code usePriority}, a higher block priority wins regardless of distance. Untargetable
     * blocks are skipped unless the building is on team {@code source}.
     */
    public static Building findTile(World world, int team, float x, float y, float range, BuildingFilter filter,
                                    boolean usePriority, int source){
        BuildingTree tree = world.buildings().tree(team);
        if(tree == null) return null;
        // the quadtree fills breturnArray first, then the loop tests each candidate
        var found = new ArrayList<Building>();
        tree.intersect(x - range, y - range, range * 2f, range * 2f, found::add);
        Building closest = null;
        float dst = 0f;
        for(Building next : found){
            if(!filter.test(next) || (next.team != source && !next.info().targetable)) continue;
            float bdst = next.dst(x, y) - next.hitSize() / 2f;
            if(bdst < range && (closest == null
                || (bdst < dst && (!usePriority || closest.info().priority <= next.info().priority))
                || (usePriority && closest.info().priority < next.info().priority))){
                dst = bdst;
                closest = next;
            }
        }
        return closest;
    }

    /**
     * {@code Units.closestBuilding(team, x, y, range, pred)} (Units.java:229): {@code team}'s building with the closest
     * edge within {@code range} (inclusive), the last one visited winning a tie.
     */
    public static Building closestBuilding(World world, int team, float x, float y, float range, BuildingFilter filter){
        BuildingTree tree = world.buildings().tree(team);
        if(tree == null) return null;
        var closest = new ClosestBuilding(x, y, range, filter);
        tree.intersect(x - range, y - range, range * 2f, range * 2f, closest);
        return closest.result;
    }

    /** {@code Units.closestBuilding}'s lambda, with its static {@code buildResult} and {@code cdist}. */
    private static final class ClosestBuilding implements Consumer<Building>{
        final float x, y, range;
        final BuildingFilter filter;
        Building result;
        float cdist = 0f;

        ClosestBuilding(float x, float y, float range, BuildingFilter filter){
            this.x = x;
            this.y = y;
            this.range = range;
            this.filter = filter;
        }

        @Override
        public void accept(Building b){
            if(!filter.test(b)) return;
            float dst = b.dst(x, y) - b.hitSize() / 2f;
            if(dst <= range && (result == null || dst <= cdist)){
                cdist = dst;
                result = b;
            }
        }
    }

    // ---------------------------------------------------------------- targets

    /** {@code UnitComp.checkTarget(air, ground)}: grounded units for ground weapons, flying ones for air weapons. */
    public static boolean checkTarget(Unit unit, boolean air, boolean ground){
        return (unit.isGrounded() && ground) || (unit.isFlying() && air);
    }

    /** {@code x()} of a unit or a building. */
    public static float x(Entity target){
        return target instanceof Unit unit ? unit.x : ((Building)target).x();
    }

    /** {@code y()} of a unit or a building. */
    public static float y(Entity target){
        return target instanceof Unit unit ? unit.y : ((Building)target).y();
    }

    /** {@code team()} of a unit or a building. */
    public static int team(Entity target){
        return target instanceof Unit unit ? unit.team : ((Building)target).team;
    }

    /** {@code hitSize()} of a unit or a building ({@code Sized}). */
    public static float hitSize(Entity target){
        return target instanceof Unit unit ? unit.hitSize : ((Building)target).hitSize();
    }

    /** {@code Healthc.isValid()}. */
    public static boolean isValid(Entity target){
        return target instanceof Unit unit ? unit.isValid() : target instanceof Building building && building.isValid();
    }

    /** {@code within(x, y, dst)}: strictly closer than {@code dst}. */
    public static boolean within(Entity target, float x, float y, float dst){
        return Mathf.within(x(target), y(target), x, y, dst);
    }

    /**
     * {@code indexer.eachBlock(null, x, y, range, pred, cons)} (BlockIndexer.java:328), by way of {@code allBuildings}:
     * every active team's quadtree in turn collects the buildings whose box meets the square, then each one whose
     * centre is within {@code range + hitSize / 2} and that passes {@code pred} is visited, privileged blocks excluded.
     */
    public static void eachBlock(World world, float x, float y, float range, Predicate<Building> pred, Consumer<Building> action){
        Buildings buildings = world.buildings();
        IntSeq teams = buildings.activeTeams();
        var found = new ArrayList<Building>();
        for(int i = 0; i < teams.size; i++){
            BuildingTree tree = buildings.tree(teams.get(i));
            if(tree != null) tree.intersect(x - range, y - range, range * 2f, range * 2f, found::add);
        }
        var table = world.tiles().table();
        for(Building building : found){
            if(Mathf.within(building.x(), building.y(), x, y, range + building.hitSize() / 2f) && pred.test(building)
                && !table.privileged(building.block().id())){
                action.accept(building);
            }
        }
    }

    /** {@code owner instanceof Healthc h && !h.dead() → h.kill()}, as {@code killShooter} does. */
    public static void kill(Entity owner){
        if(owner instanceof Unit unit && !unit.dead) unit.kill();
        else if(owner instanceof Building building && !building.dead) building.kill();
    }

    /** {@code owner instanceof Healthc o → o.heal(amount)}, as lifesteal does. */
    public static void heal(Entity owner, float amount){
        if(owner instanceof Unit unit) unit.heal(amount);
        else if(owner instanceof Building building) building.heal(amount);
    }

    /** The unit's type's {@code knockbackMultiplier}. */
    public static float knockbackMultiplier(Unit unit){
        return Arms.of(unit).knockbackMultiplier();
    }

    /**
     * {@code Units.invalidateTarget(target, team, x, y, range)}: true when there is nothing to shoot at there: no target,
     * out of range (plus half its size), on {@code team}, dead or gone, or an untargetable unit.
     */
    public static boolean invalidateTarget(Entity target, int team, float x, float y, float range){
        return target == null
            || (range != Float.MAX_VALUE && !within(target, x, y, range + hitSize(target) / 2f))
            || team(target) == team
            || !isValid(target)
            || (target instanceof Unit unit && !unit.targetable(team));
    }
}
