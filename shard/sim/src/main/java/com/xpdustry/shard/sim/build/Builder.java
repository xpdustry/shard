package com.xpdustry.shard.sim.build;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.storage.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code BuilderComp} (BuilderComp.java): a unit working through its {@link Unit#plans}, the first plan
 * being the one it builds. {@link #update} is the component's {@code update()}, which {@link BuilderComponent} runs in
 * the merged unit update; the other methods are the component's, for controllers, AI and the weapons that aim at the
 * plan ({@code BuildWeapon}).
 *
 * <p>Each tick a builder drops plans that are done ({@link #validatePlans}), then spends its build counter, one plan
 * step per whole tick of it, up to 10: it moves to the front the first plan in range it can afford
 * ({@link #shouldSkip}), begins the site with {@link Build#beginPlace} or {@link Build#beginBreak}, and builds or breaks
 * it at {@code 1 / buildCost × buildSpeed × multipliers} per step.
 *
 * <p>A plan's position is its block's centre ({@code BuildPlan.drawx()}). A breaking plan made from the wire names
 * the block that covered its tile then; Shard's breaking plans hold no block, so their centre is that of the block
 * covering the tile now, which is the same until that block changes size under the plan.
 */
public final class Builder{
    private Builder(){
    }

    /** {@code canBuild()}: the type builds and its status lets it. */
    public static boolean canBuild(Unit unit){
        return Construction.of(unit.world()).buildSpeed(unit.type()) > 0 && unit.statuses.buildSpeedMultiplier > 0;
    }

    /** {@code isBuilding()}: the queue holds plans. */
    public static boolean isBuilding(Unit unit){
        return !unit.plans.isEmpty();
    }

    /** {@code buildPlan()}: the plan at the front of the queue, or null. */
    public static BuildPlan buildPlan(Unit unit){
        return unit.plans.isEmpty() ? null : unit.plans.first();
    }

    /** {@code activelyBuilding()}: building, and within range of the first plan (any range with infinite resources). */
    public static boolean activelyBuilding(Unit unit){
        if(isBuilding(unit)){
            World world = unit.world();
            BuildPlan plan = buildPlan(unit);
            Rules rules = UnitCalls.rules(world);
            if(!rules.editor && !unit.within(planX(world, plan), planY(world, plan), rules.infiniteResources ? Float.MAX_VALUE : Construction.of(world).buildRange(unit.type()))){
                return false;
            }
        }
        return isBuilding(unit) && unit.updateBuilding;
    }

    /** {@code clearBuilding()}. */
    public static void clearBuilding(Unit unit){
        unit.plans.clear();
    }

    /**
     * {@code addBuild(place, tail)}: replaces any plan at the same tile, takes over a standing site's progress, and
     * queues {@code place} at the back or the front.
     */
    public static void addBuild(Unit unit, BuildPlan place, boolean tail){
        if(!canBuild(unit)) return;
        PlanQueue plans = unit.plans;
        for(int i = 0; i < plans.size(); i++){
            BuildPlan plan = plans.get(i);
            if(plan.x == place.x && plan.y == place.y){
                plans.remove(i);
                break;
            }
        }
        if(unit.world().buildings().at(place.x, place.y) instanceof ConstructBuild site) place.progress = site.progress;
        if(tail) plans.addLast(place);
        else plans.addFirst(place);
    }

    /** {@code removeBuild(x, y, breaking)}: drops the plan of that kind at that tile. */
    public static void removeBuild(Unit unit, int x, int y, boolean breaking){
        PlanQueue plans = unit.plans;
        for(int i = 0; i < plans.size(); i++){
            BuildPlan plan = plans.get(i);
            if(plan.breaking == breaking && plan.x == x && plan.y == y){
                plans.remove(i);
                return;
            }
        }
    }

    /** {@code plan.drawx()}: the x of the plan's block's centre. */
    public static float planX(World world, BuildPlan plan){
        return plan.x * Footprint.TILE_SIZE + planOffset(world, plan);
    }

    /** {@code plan.drawy()}. */
    public static float planY(World world, BuildPlan plan){
        return plan.y * Footprint.TILE_SIZE + planOffset(world, plan);
    }

    private static float planOffset(World world, BuildPlan plan){
        if(plan.block >= 0) return Footprint.worldOffset(world.content().block(plan.block).size());
        TileGrid tiles = world.tiles();
        if(!tiles.inBounds(plan.x, plan.y)) return 0f;
        return Footprint.worldOffset(tiles.table().size(tiles.block(tiles.pos(plan.x, plan.y))));
    }

    /** {@code tile.drawx()}: the tile's position plus the offset of the block covering it, whatever its origin. */
    private static float tileX(World world, int x, int y){
        TileGrid tiles = world.tiles();
        return x * Footprint.TILE_SIZE + Footprint.worldOffset(tiles.table().size(tiles.block(tiles.pos(x, y))));
    }

    private static float tileY(World world, int x, int y){
        TileGrid tiles = world.tiles();
        return y * Footprint.TILE_SIZE + Footprint.worldOffset(tiles.table().size(tiles.block(tiles.pos(x, y))));
    }

    /** {@code plan.isRotation(team)}: the plan rotates the team's standing block of the same kind. */
    public static boolean isRotation(World world, BuildPlan plan, int team){
        if(plan.breaking) return false;
        TileGrid tiles = world.tiles();
        if(!tiles.inBounds(plan.x, plan.y)) return false;
        int pos = tiles.pos(plan.x, plan.y);
        Building build = world.buildings().at(pos);
        return tiles.team(pos) == team && tiles.block(pos) == plan.block && build != null && build.rotation != plan.rotation;
    }

    /** {@code plan.isDerelictRepair()}: the plan's block stands here, derelict, and the rules repair it. */
    public static boolean isDerelictRepair(World world, BuildPlan plan){
        if(plan.breaking || !UnitCalls.rules(world).derelictRepair) return false;
        TileGrid tiles = world.tiles();
        if(!tiles.inBounds(plan.x, plan.y)) return false;
        int pos = tiles.pos(plan.x, plan.y);
        return tiles.team(pos) == VanillaIds.DERELICT && tiles.block(pos) == plan.block && world.buildings().at(pos) != null;
    }

    /**
     * {@code validatePlans()} (BuilderComp.java:57): drops plans outside the map, breaking plans of empty tiles, and
     * placing plans already done (the same block, at the same rotation if it rotates, not a derelict copy to repair; or
     * the floor or overlay already there).
     */
    public static void validatePlans(Unit unit){
        World world = unit.world();
        TileGrid tiles = world.tiles();
        PlanQueue plans = unit.plans;
        for(int i = 0; i < plans.size(); ){
            BuildPlan plan = plans.get(i);
            if(done(world, tiles, plan)){
                plans.remove(i);
            }else{
                i++;
            }
        }
    }

    private static boolean done(World world, TileGrid tiles, BuildPlan plan){
        if(!tiles.inBounds(plan.x, plan.y)) return true;
        int pos = tiles.pos(plan.x, plan.y);
        int block = tiles.block(pos);
        if(plan.breaking) return block == 0;
        Building build = world.buildings().at(pos);
        PlaceInfo info = Build.info(world, plan.block);
        boolean isSameDerelict = build != null && block == plan.block && build.tileX() == plan.x && build.tileY() == plan.y
            && tiles.team(pos) == VanillaIds.DERELICT;
        return ((build != null && build.rotation == plan.rotation && !isSameDerelict) || !info.rotate)
            // the block must be the same, but not derelict and the same
            && ((block == plan.block && !isSameDerelict)
            // same floor or overlay
            || (info.isOverlay && plan.block == tiles.overlay(pos) || (info.isFloor && plan.block == tiles.floor(pos))));
    }

    /**
     * {@code shouldSkip(plan, core)} (BuilderComp.java:246): a placing plan the core can't pay for, while resources
     * aren't infinite: stuck and short of the whole cost, or short of up to 15 of an item it needs.
     */
    public static boolean shouldSkip(World world, BuildPlan plan, int team, Core core){
        Rules rules = UnitCalls.rules(world);
        if(rules.infiniteResources || rules.teams.get(team).infiniteResources || plan.breaking || core == null
            || isRotation(world, plan, team) || isDerelictRepair(world, plan)){
            return false;
        }
        PlaceInfo info = Build.info(world, plan.block);
        if(plan.stuck && !Build.hasAll(core.items, info)) return true;
        for(int i = 0; i < info.items.length; i++){
            if(!core.items.has(info.items[i], Math.min(info.amounts[i], 15)) && Mathf.round(info.amounts[i] * rules.buildCostMultiplier) > 0) return true;
        }
        return false;
    }

    /** {@code allowBuildCurrent}: the wave team builds anything visible; others what is unlocked and placeable here. */
    static boolean allowBuild(World world, PlaceInfo block, int team){
        Rules rules = UnitCalls.rules(world);
        return rules.editor || (rules.waves && team == rules.waveTeam && Build.isVisible(world, block))
            || (Build.environmentBuildable(world, block) && Build.isPlaceable(world, block));
    }

    /** {@code updateBuildLogic()} (BuilderComp.java:76), the component's update. */
    public static void update(Unit unit){
        World world = unit.world();
        if(!Construction.present(world)) return;
        Construction construction = Construction.of(world);
        UnitDef type = unit.type();
        float buildSpeed = construction.buildSpeed(type);
        if(buildSpeed <= 0f) return;
        validatePlans(unit);
        if(!unit.updateBuilding || !canBuild(unit)) return;
        Rules rules = UnitCalls.rules(world);
        int team = unit.team;
        float finalPlaceDst = rules.infiniteResources ? Float.MAX_VALUE : construction.buildRange(type);
        boolean infinite = rules.infiniteResources || rules.teams.get(team).infiniteResources;
        unit.buildCounter += 1f;
        if(Float.isNaN(unit.buildCounter) || Float.isInfinite(unit.buildCounter)) unit.buildCounter = 0f;
        unit.buildCounter = Math.min(unit.buildCounter, 10f);
        boolean instant = rules.instantBuild && rules.infiniteResources;
        // a random attempt to fix a freeze that only occurs on Android
        PlanQueue plans = unit.plans;
        int maxPerFrame = instant ? plans.size() : 10, count = 0;
        Teams teams = Teams.of(world);
        Core core = teams == null ? null : teams.core(team);
        if(core == null && !infinite) return;
        TileGrid tiles = world.tiles();
        Buildings buildings = world.buildings();
        while((unit.buildCounter >= 1 || instant) && count++ < maxPerFrame && plans.size() > 0){
            unit.buildCounter -= 1f;
            // find the next build plan
            if(plans.size() > 1){
                int total = 0;
                int size = plans.size();
                float bestDst = Float.MAX_VALUE;
                boolean foundAny = false;
                int bestIndex = -1;
                while(total < size){
                    BuildPlan plan = plans.first();
                    float dx = planX(world, plan) - unit.x, dy = planY(world, plan) - unit.y;
                    float dst = dx * dx + dy * dy;
                    boolean within = dst <= finalPlaceDst * finalPlaceDst;
                    // a valid plan within range: stop looking
                    if(within && !shouldSkip(world, plan, team, core)){
                        foundAny = true;
                        break;
                    }else if(within && dst < bestDst){
                        // still bad, but at least within build radius
                        bestIndex = total;
                        bestDst = dst;
                    }
                    plans.removeFirst();
                    plans.addLast(plan);
                    total++;
                }
                // all the plans were useless and the current one can't be reached: skip to the closest, if any
                BuildPlan first = plans.first();
                if(!foundAny && bestIndex > 0 && !unit.within(planX(world, first), planY(world, first), finalPlaceDst)){
                    for(int i = 0; i < bestIndex; i++) plans.addLast(plans.removeFirst());
                }
            }
            BuildPlan current = plans.first();
            int pos = tiles.pos(current.x, current.y);
            if(!unit.within(tileX(world, current.x, current.y), tileY(world, current.x, current.y), finalPlaceDst)) continue;
            PlaceInfo block = current.breaking ? null : Build.info(world, current.block);
            boolean allowBuildCurrent = block != null && allowBuild(world, block, team);
            if(!(buildings.at(pos) instanceof ConstructBuild cb)){
                if(!current.initialized && !current.breaking
                    && Build.validPlaceIgnoreUnits(world, block.block, team, current.x, current.y, current.rotation, true, true) && allowBuildCurrent){
                    if(Build.checkNoUnitOverlap(world, block.block, current.x, current.y)){
                        boolean hasAll = infinite || isRotation(world, current, team)
                            // derelict repair
                            || (tiles.team(pos) == VanillaIds.DERELICT && tiles.block(pos) == current.block && buildings.at(pos) != null
                            && Build.info(world, tiles.block(pos)).allowDerelictRepair && rules.derelictRepair)
                            // at least 1 item of each type first
                            || hasOneOfEach(core, block, rules.buildCostMultiplier);
                        if(hasAll){
                            Build.beginPlace(world, unit, block.block, team, current.x, current.y, current.rotation, block.instantBuild ? current.config : null);
                            if(block.instantBuild){
                                if(plans.size() > 0) plans.removeFirst();
                                continue;
                            }
                        }else{
                            current.stuck = true;
                        }
                    }else{
                        // a unit is blocking the plan: skip it
                        plans.removeFirst();
                        plans.addLast(current);
                        continue;
                    }
                }else if(!current.initialized && current.breaking && Build.validBreak(world, team, current.x, current.y)){
                    Build.beginBreak(world, unit, team, current.x, current.y);
                }else{
                    plans.removeFirst();
                    continue;
                }
            }else if((tiles.team(pos) != team && tiles.team(pos) != VanillaIds.DERELICT)
                || (!current.breaking && (cb.current != current.block || cb.tile() != pos))){
                plans.removeFirst();
                continue;
            }
            // BuildSelectEvent isn't modelled
            if(buildings.at(pos) instanceof ConstructBuild && !current.initialized) current.initialized = true;
            // no build entity: stop building
            if(!(buildings.at(pos) instanceof ConstructBuild entity)) continue;
            float bs = 1f / entity.buildCost * buildSpeed * unit.statuses.buildSpeedMultiplier * rules.buildSpeed(team);
            if(current.breaking){
                entity.deconstruct(unit, core, bs);
            }else if(allowBuildCurrent){
                // only unlocked blocks
                entity.construct(unit, core, bs, current.config);
            }
            current.stuck = Mathf.equal(current.progress, entity.progress);
            current.progress = entity.progress;
        }
    }

    /** At least one of each requirement, or none when the scaled amount rounds to 0. */
    private static boolean hasOneOfEach(Core core, PlaceInfo block, float multiplier){
        for(int i = 0; i < block.items.length; i++){
            if(!core.items.has(block.items[i], Math.min(Mathf.round(block.amounts[i] * multiplier), 1))) return false;
        }
        return true;
    }
}
