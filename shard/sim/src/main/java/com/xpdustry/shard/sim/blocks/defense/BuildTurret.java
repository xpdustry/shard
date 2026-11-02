package com.xpdustry.shard.sim.blocks.defense;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.ai.*;
import com.xpdustry.shard.sim.blocks.storage.*;
import com.xpdustry.shard.sim.build.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code BuildTurret.BuildTurretBuild} (BuildTurret.java), the build tower: a turret whose block unit (of the
 * bake's {@code turret-unit-<block>} type, with the block's build range and speed) builds. Every {@code targetInterval}
 * ticks without a plan it takes the first of its team's rebuild plans in range that it may place and afford, moving
 * that plan to the back of the queue so another builder takes the next, or else mimics a unit building a site in range.
 * It drops plans that stopped being valid, and builds through the unit's builder ({@link Builder#update}) at its
 * potential efficiency times its time scale. Heal suppression stops it.
 *
 * <p>The block unit is made at construction ({@link #constructed()}), never added, and stands on the tower. Not ported: a player controlling the tower ({@code isControlled()} is false), and its sensors
 * ({@code buildX}, {@code building}). Saved: the aim and the unit's plan queue.
 */
@Kind("build-turret")
public final class BuildTurret extends Building implements Checkpointable{
    /** What constructing a block unit draws from the generator ({@code UnitType.create}), after its entity id. */
    private static final int BLOCK_UNIT_DRAWS = 3;

    /** {@code rotation}: where it points, in degrees; it follows the unit's. */
    public float turretRotation = 90f;
    public float activationTimer, warmup;
    /** The entity id the block unit took at construction. */
    public int unitId = -1;
    /** {@code unit}: the block unit, under {@link #unitId}. */
    public Unit unit;
    public Unit following;
    /**
     * {@code lastPlan}: the team plan last taken, kept by identity as vanilla keeps it, so its {@code removed} flag is
     * the team's. A copy or a restore names it by its index in the team's plans ({@link #lastPlanIndex}), resolved on
     * first use, or keeps a detached removed plan.
     */
    public BlockPlan lastPlan;
    /** {@link #lastPlan}'s index in the team's plans while unresolved, else -1. */
    public int lastPlanIndex = -1;

    @Derived("the block's parameters, read on first use")
    private Params params;

    record Params(int timerTarget, int timerTarget2, int targetInterval, float range, float activationTime, UnitDef unitType){
        static Params of(World world, BlockDef block){
            if(!block.baked()) return new Params(0, 1, 15, 80f, 0f, null);
            var f = block.fields();
            return new Params(f.i("timerTarget"), f.i("timerTarget2"), f.i("targetInterval"), f.f("range"), f.f("activationTime"),
                world.content().unit("turret-unit-" + block.name()));
        }
    }

    Params params(){
        if(params == null) params = Params.of(world(), block());
        return params;
    }

    /** {@code unit = unitType.create(team)}, then {@code unit.rotation(90f)}: the block unit, its id and draws. */
    @Override
    protected void constructed(){
        World world = world();
        unitId = world.ids().next();
        for(int i = 0; i < BLOCK_UNIT_DRAWS; i++) world.rand().nextLong();
        UnitDef type = params().unitType;
        if(type == null) return;
        // the type's controller was drawn with the unit; the unit is never added, so it never runs
        unit = Unit.createDrawn(world, type, team, unitId, CommandAi.blank());
        unit.blockTile = this;
        unit.rotation = 90f;
    }

    /**
     * The build tower whose block unit has entity id {@code unitId}, or null: how a Call that names a builder writes
     * a block unit ({@code TypeIO.writeUnit}'s kind 1, by its tile). Block units are never in the world's unit list.
     */
    public static BuildTurret ownerOf(World world, int unitId){
        Buildings buildings = world.buildings();
        var teams = buildings.activeTeams();
        for(int i = 0; i < teams.size; i++){
            for(Building b : buildings.flagged(teams.get(i), BlockFlag.turret)){
                if(b instanceof BuildTurret tower && tower.unitId == unitId) return tower;
            }
        }
        return null;
    }

    /** {@code unit()}: the block unit, put on the tower and its team. */
    public Unit unit(){
        tile(unit);
        return unit;
    }

    /** {@code BlockUnitComp.tile(this)} and {@code team(team)}. */
    private void tile(Unit unit){
        unit.maxHealth = block().health();
        unit.health = health;
        unit.hitSize = block().size() * Footprint.TILE_SIZE * 0.7f;
        unit.set(x(), y());
        unit.team = team;
    }

    @Override
    public void placed(){
        super.placed();
        activationTimer = params().activationTime;
    }

    @Override
    public void updateTile(){
        Params p = params();
        World world = world();
        Unit unit = unit();
        lastPlan();
        // only cares about where the unit itself is looking
        turretRotation = unit.rotation;
        if(Builder.activelyBuilding(unit)){
            BuildPlan plan = Builder.buildPlan(unit);
            unit.lookAt(Angles.angle(x(), y(), Builder.planX(world, plan), Builder.planY(world, plan)));
        }
        if(checkSuppression()) efficiency = potentialEfficiency = 0f;
        unit.statuses.buildSpeedMultiplier = potentialEfficiency * timeScale;
        unit.statuses.speedMultiplier = potentialEfficiency * timeScale;
        warmup = Mathf.lerpDelta(warmup, Builder.activelyBuilding(unit) ? efficiency : 0f, 0.1f);
        // isControlled() is false: players can't take a build tower over yet
        unit.updateBuilding = true;
        if(following != null){
            // validate follower
            if(!following.isValid() || !Builder.activelyBuilding(following)){
                following = null;
                unit.plans.clear();
            }else{
                // set to follower's first build plan, whatever that is
                unit.plans.clear();
                unit.plans.addFirst(Builder.buildPlan(following));
                lastPlan = null;
            }
        }else if(Builder.buildPlan(unit) == null && timer(p.timerTarget, p.targetInterval)){
            // search for new stuff
            searchPlans(p, unit);
        }else if(Builder.buildPlan(unit) != null){
            // validate building
            if(validate(p, unit)) return;
        }
        // please do not commit suicide
        for(int i = unit.plans.size() - 1; i >= 0; i--){
            BuildPlan plan = unit.plans.get(i);
            if(world.buildings().at(plan.x, plan.y) == this) unit.plans.remove(i);
        }
        Builder.update(unit);
    }

    private void searchPlans(Params p, Unit unit){
        World world = world();
        Rules rules = rules();
        List<BlockPlan> blocks = Teams.of(world).get(team).plans();
        Core core = Teams.of(world).core(team);
        for(int i = 0; i < blocks.size(); i++){
            BlockPlan block = blocks.get(i);
            if(within(block.x * Footprint.TILE_SIZE, block.y * Footprint.TILE_SIZE, p.range)){
                BlockDef btype = world.content().block(block.block);
                if(Build.validPlace(world, btype, unit.team, block.x, block.y, block.rotation)
                    && (rules.infiniteResources || rules.teams.get(team).infiniteResources || hasRequirements(core, btype, rules.buildCostMultiplier))){
                    Builder.addBuild(unit, BuildPlan.place(block.x, block.y, block.rotation, btype, BuildPlan.copyConfig(block.config)), true);
                    // shift build plan to tail so next unit builds something else
                    blocks.addLast(blocks.remove(i));
                    lastPlan = block;
                    break;
                }
            }
        }
        // still not building, find someone to mimic
        if(Builder.buildPlan(unit) == null){
            following = null;
            SpatialIndex.of(world).nearby(team, x(), y(), p.range, u -> {
                if(following != null) return;
                if(Builder.canBuild(u) && Builder.activelyBuilding(u)){
                    BuildPlan plan = Builder.buildPlan(u);
                    Building build = world.buildings().at(plan.x, plan.y);
                    if(build instanceof ConstructBuild && within(build.x(), build.y(), p.range)) following = u;
                }
            });
        }
    }

    /** {@code team.items().has(btype.requirements, multiplier)}: the core holds each requirement, scaled and rounded. */
    private boolean hasRequirements(Core core, BlockDef block, float multiplier){
        PlaceInfo info = Construction.of(world()).info(block.id());
        for(int i = 0; i < info.items.length; i++){
            int amount = Mathf.round(info.amounts[i] * multiplier);
            if(core == null ? amount > 0 : !core.items.has(info.items[i], amount)) return false;
        }
        return true;
    }

    /** The validation branch; true when it returned early, as vanilla's update does after dropping a plan. */
    private boolean validate(Params p, Unit unit){
        World world = world();
        BuildPlan req = Builder.buildPlan(unit);
        // clear break plan if another player is breaking something
        if(!req.breaking && timer(p.timerTarget2, 30f)){
            Players players = Players.of(world);
            if(players != null){
                var list = players.list();
                for(int i = 0; i < list.size(); i++){
                    Player player = list.get(i);
                    Unit other = player.unit();
                    if(player.team != team || other == null || !Builder.canBuild(other)) continue;
                    BuildPlan theirs = Builder.buildPlan(other);
                    if(Builder.activelyBuilding(other) && theirs.x == req.x && theirs.y == req.y && theirs.breaking){
                        unit.plans.removeFirst();
                        // remove from list of plans
                        Teams.of(world).get(team).plans().removeIf(plan -> plan.x == req.x && plan.y == req.y);
                        return true;
                    }
                }
            }
        }
        Building at = world.tiles().inBounds(req.x, req.y) ? world.buildings().at(req.x, req.y) : null;
        boolean valid = !(lastPlan() != null && lastPlan.removed)
            && ((at instanceof ConstructBuild cons && cons.current == req.block)
            || (req.breaking ? Build.validBreak(world, unit.team, req.x, req.y)
            : Build.validPlace(world, world.content().block(req.block), unit.team, req.x, req.y, req.rotation)));
        if(!valid){
            // discard invalid request
            unit.plans.removeFirst();
            lastPlan = null;
        }
        return false;
    }

    /** {@link #lastPlan}, resolved from {@link #lastPlanIndex} after a copy or a restore. */
    public BlockPlan lastPlan(){
        if(lastPlanIndex >= 0){
            lastPlan = Teams.of(world()).get(team).plans().get(lastPlanIndex);
            lastPlanIndex = -1;
        }
        return lastPlan;
    }

    /** Where {@link #lastPlan} is in the team's plans: its index, -1 for none, -2 when it is no longer there. */
    private int lastPlanSlot(){
        if(lastPlanIndex >= 0) return lastPlanIndex;
        if(lastPlan == null) return -1;
        List<BlockPlan> plans = Teams.of(world()).get(team).plans();
        for(int i = 0; i < plans.size(); i++) if(plans.get(i) == lastPlan) return i;
        return -2;
    }

    /** A plan that is no longer the team's: all vanilla reads of it is that it was removed. */
    private BlockPlan detached(){
        var out = new BlockPlan(lastPlan.x, lastPlan.y, lastPlan.rotation, lastPlan.block, null);
        out.removed = lastPlan.removed;
        return out;
    }

    @Override
    public boolean shouldConsume(){
        return unit.plans.size() > 0 && !isHealSuppressed();
    }

    @Override
    public BlockStatus status(double stateTick){
        return activationTimer <= 0 ? super.status(stateTick) : BlockStatus.inactive;
    }

    @Override
    public float range(){
        return params().range;
    }

    /** The save's {@code unit.rotation(rotation)}: the read aim turns the unit too. */
    public void readRotation(float rotation){
        turretRotation = rotation;
        unit.rotation = rotation;
    }

    @Override
    public void write(CheckpointOut out){
        out.f("turretRotation", turretRotation).f("activationTimer", activationTimer).f("warmup", warmup).i("unitId", unitId)
            .ref("unit", unit).ref("following", following);
        int slot = lastPlanSlot();
        out.i("lastPlanIndex", slot).obj("lastPlan", slot == -2 ? detached() : null);
    }

    @Override
    public void read(CheckpointIn in){
        turretRotation = in.f("turretRotation");
        activationTimer = in.f("activationTimer");
        warmup = in.f("warmup");
        unitId = in.i("unitId");
        unit = in.ref("unit", Unit.class);
        following = in.ref("following", Unit.class);
        int slot = in.i("lastPlanIndex");
        BlockPlan stored = in.obj("lastPlan", BlockPlan.class);
        lastPlanIndex = Math.max(slot, -1);
        lastPlan = slot == -2 ? stored : null;
    }

    /** A restored unit has its type's stats ({@code restoreType}); the tower's are put back, as each update does. */
    @Override
    public void restored(){
        if(unit != null) tile(unit);
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (BuildTurret)into;
        out.turretRotation = turretRotation;
        out.activationTimer = activationTimer;
        out.warmup = warmup;
        out.unitId = unitId;
        out.unit = context.map(unit);
        out.following = context.map(following);
        int slot = lastPlanSlot();
        out.lastPlanIndex = Math.max(slot, -1);
        out.lastPlan = slot == -2 ? detached() : null;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(turretRotation).f(activationTimer).f(warmup).i(unitId).bool(unit != null);
        if(unit != null) unit.hashState(hasher);
        int slot = lastPlanSlot();
        hasher.i(following == null ? -1 : following.id()).i(slot);
        if(slot == -2) hasher.i(lastPlan.x).i(lastPlan.y).i(lastPlan.block).bool(lastPlan.removed);
    }
}
