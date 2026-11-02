package com.xpdustry.shard.sim.ai;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.build.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.path.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code LogicAI} (LogicAI.java): the controller a processor puts on a unit with {@code ucontrol},
 * {@code uradar} or {@code ulocate} ({@code sim.logic.UnitStatements}). It moves as the last command said, aims at a
 * position ({@code target}) or an object ({@code targetp}), boosts, and hands the unit back to its type's controller
 * ({@link Unit#resetController()}) once no processor has controlled it for {@link #CONTROL_TIMEOUT} frames, or once its
 * processor is gone.
 *
 * <p>Making one draws what vanilla's {@code new LogicAI()} draws: the two AI timers, then an entity id for
 * {@code posTarget}, a {@code PosTeam} that is never added ({@link #posId}). The position target is kept as its
 * coordinates; as {@code PosTeam} is neither a building nor a {@code Hitboxc}, weapons aim at it with no lead and
 * bullets aim at its tile, which is what a null mount target gives Shard's weapons, so mounts hold null for it.
 *
 * <p>The per-instruction caches ({@code execCache}, {@code radars}) are keyed by vanilla's instruction object: here
 * the processor's building id, its code's {@code generation} and the statement's index ({@link Cached}).
 *
 * <p>{@code pathfind}: a ground unit asks the world's {@link ControlPathfinder}, as vanilla asks its own
 * ({@code controlPath.getPathPosition}), which runs on vanilla's nominal schedule instead of a thread
 * (ARCHITECTURE.md §1, "Wall-clock and thread timing").
 *
 * <p>Owner: WP69.
 */
public final class LogicAi extends AiController{
    public static final String KIND = "logic-ai";
    /** {@code transferDelay}: the least time between two item or payload transfers of one unit. */
    public static final float TRANSFER_DELAY = 60f * 1.5f;
    /** {@code logicControlTimeout}: frames without control after which the unit goes back to its AI. */
    public static final float CONTROL_TIMEOUT = 60f * 10f;

    public LUnitControl control = LUnitControl.idle;
    public float moveX, moveY, moveRad;
    public float controlTimer = CONTROL_TIMEOUT, targetTimer;
    /** {@code controller}: the processor building that controls the unit, or null. */
    public Building controller;
    /** {@code plan}: the plan {@code ucontrol build} and {@code deconstruct} reuse; it is the queue's own object. */
    public BuildPlan plan = new BuildPlan();
    /** {@code aimControl}: {@code target}, {@code targetp}, or {@code stop} for no aim. */
    public LUnitControl aimControl = LUnitControl.stop;
    public boolean boost;
    /** {@code mainTarget}: what {@code targetp} aims at, a unit or a building. */
    public Entity mainTarget;
    public boolean shoot;
    /** {@code posTarget}: its entity id, drawn when vanilla's {@code PosTeam.create()} draws it, and its position. */
    public int posId = -1;
    public float posX, posY;
    /** {@code execCache}: in the order vanilla's map would hold its keys doesn't matter, as it is only looked up. */
    public final List<Cached> cache = new ArrayList<>();
    /** {@code radars}: the instructions that used up this AI's targeting window, as (processor, generation, index). */
    public final IntSeq radars = new IntSeq();
    /**
     * After a copy or a restore, the index of {@link #plan} in its unit's queue, which the unit's own copy of the
     * object stands in for once the world relinks it ({@link #relink}); -1 when it isn't queued.
     */
    public int planLink = -1;

    /** Vanilla's {@code new LogicAI()}: the AI timers, then {@code posTarget}'s entity id. */
    public LogicAi(World world){
        super(world, KIND);
        posId = world.ids().next();
    }

    /** A blank controller for a checkpoint to fill: no draws. */
    public LogicAi(){
    }

    /**
     * {@code readController}'s {@code new LogicAI()} for a saved unit, without its draws, which the save reader makes
     * itself: the control timer full, and the processor at the saved position ({@code controller}, maybe null).
     */
    public static LogicAi read(Building controller){
        var ai = new LogicAi();
        ai.kind = KIND;
        ai.controlTimer = CONTROL_TIMEOUT;
        ai.controller = controller;
        return ai;
    }

    private LogicAi(LogicAi source, CopyContext context){
        super(source, context);
        control = source.control;
        moveX = source.moveX;
        moveY = source.moveY;
        moveRad = source.moveRad;
        controlTimer = source.controlTimer;
        targetTimer = source.targetTimer;
        controller = context.map(source.controller);
        plan = copy(source.plan);
        planLink = source.queuedPlan();
        aimControl = source.aimControl;
        boost = source.boost;
        mainTarget = context.map(source.mainTarget);
        shoot = source.shoot;
        posId = source.posId;
        posX = source.posX;
        posY = source.posY;
        for(Cached entry : source.cache) cache.add(entry.copy(context));
        radars.addAll(source.radars);
    }

    /** The index of {@link #plan} in the unit's queue, by identity, or -1. */
    private int queuedPlan(){
        if(planLink != -1) return planLink;
        if(unit == null) return -1;
        for(int i = 0; i < unit.plans.size(); i++){
            if(unit.plans.get(i) == plan) return i;
        }
        return -1;
    }

    /** Points {@link #plan} at the queue's object again after a copy or a restore ({@link #planLink}). */
    public void relink(Unit unit){
        if(planLink != -1 && planLink < unit.plans.size()) plan = unit.plans.get(planLink);
        planLink = -1;
    }

    // region LogicAI

    @Override
    public boolean keepState(){
        return true;
    }

    /** {@code checkTargetTimer(radar)}: whether {@code key}'s instruction may search again in this window. */
    public boolean checkTargetTimer(int processor, int generation, int index){
        for(int i = 0; i < radars.size; i += 3){
            if(radars.get(i) == processor && radars.get(i + 1) == generation && radars.get(i + 2) == index) return false;
        }
        radars.add(processor);
        radars.add(generation);
        radars.add(index);
        return true;
    }

    /** {@code execCache.get(instruction, Cache::new)}: the instruction's entry, made empty if it has none. */
    public Cached cached(int processor, int generation, int index){
        for(Cached entry : cache){
            if(entry.processor == processor && entry.generation == generation && entry.index == index) return entry;
        }
        var made = new Cached(processor, generation, index);
        cache.add(made);
        return made;
    }

    @Override
    public void updateMovement(){
        World world = unit.world();
        if(targetTimer > 0f){
            targetTimer -= 1f;
        }else{
            radars.clear();
            targetTimer = 40f;
        }

        // timeout when not controlled by logic for a while
        if(controlTimer > 0 && controller != null && controller.isValid()){
            controlTimer -= 1f;
        }else{
            unit.resetController();
            return;
        }

        switch(control){
            case move -> moveTo(moveX, moveY, 1f, 30f);
            case approach -> moveTo(moveX, moveY, moveRad - 7f, 7, true, null);
            case pathfind -> {
                if(unit.isFlying()){
                    moveTo(moveX, moveY, 1f, 30f);
                }else{
                    ControlPathfinder control = ControlPathfinder.of(world);
                    ControlPathfinder.Result result = control == null ? null : control.pathPosition(unit, moveX, moveY);
                    if(result != null && result.move()){
                        // Vec2.epsilonEquals: within 4.1 on each axis
                        boolean near = Math.abs(result.x() - moveX) <= 4.1f && Math.abs(result.y() - moveY) <= 4.1f;
                        moveTo(result.x(), result.y(), 1f, near ? 30f : 0f);
                    }
                }
            }
            case autoPathfind -> autoPathfind();
            case stop -> Builder.clearBuilding(unit);
            default -> {}
        }

        UnitParams params = unit.params();
        if(params.canBoost() && !params.flying()){
            boolean shouldBoost = boost || unit.onSolid() || (unit.isFlying() && !unit.canLand());
            unit.elevation = Mathf.approachDelta(unit.elevation, shouldBoost ? 1f : 0f, shouldBoost ? params.riseSpeed() : params.descentSpeed());
        }

        // look where moving if there's nothing to aim at
        if(!shoot || !params.omniMovement()){
            unit.lookAt(prefRotation(unit));
        }else if(type().hasWeapons && unit.mounts.length > 0 && !Weapon.of(unit, unit.mounts[0]).ignoreRotation){
            // if there is, look at the object
            unit.lookAt(unit.mounts[0].aimX, unit.mounts[0].aimY);
        }
    }

    /** {@code autoPathfind}: to the closest enemy core, or the wave spawn, unless already in half range of the core. */
    private void autoPathfind(){
        Rules rules = UnitCalls.rules(unit.world());
        float range = type().range;
        Building core = closestEnemyCore();
        if(core == null || !unit.within(core.x(), core.y(), range * 0.5f)){
            boolean move = true;
            int spawner = -1;
            if(rules.waves && unit.team == rules.defaultTeam){
                spawner = closestSpawner();
                if(spawner != -1 && withinTile(spawner, rules.dropZoneRadius + 120f)) move = false;
            }
            if(move){
                if(unit.isFlying()){
                    if(core != null){
                        moveTo(core.x(), core.y(), range * 0.5f);
                    }else if(spawner != -1){
                        int width = unit.world().tiles().width();
                        moveTo(spawner % width * TILESIZE, spawner / width * TILESIZE, range * 0.5f);
                    }
                }else{
                    pathfind();
                }
            }
        }
    }

    // endregion
    // region aiming

    @Override
    public boolean checkTarget(Entity target, float x, float y, float range){
        return false;
    }

    /** Always retarget. */
    @Override
    public boolean retarget(){
        return true;
    }

    @Override
    public boolean invalid(Entity target){
        return false;
    }

    @Override
    public boolean shouldShoot(){
        return shoot && !(unit.params().canBoost() && boost);
    }

    /** {@code target(...)}: the main target for {@code targetp}; the position target is {@link #aimsAtPosition()}. */
    @Override
    public Entity target(float x, float y, float range, boolean air, boolean ground){
        return aimControl == LUnitControl.targetp ? mainTarget : null;
    }

    /** Whether the AI aims at {@code posTarget} ({@code aimControl == target}). */
    public boolean aimsAtPosition(){
        return aimControl == LUnitControl.target;
    }

    /**
     * {@code AIController.updateWeapons()} with LogicAI's overrides: it retargets every frame, keeps any target, never
     * counts a frame without one, and its target may be {@code posTarget}.
     */
    @Override
    public void updateWeapons(){
        float rotation = unit.rotation - 90;
        boolean position = aimsAtPosition();
        target = findMainTarget(unit.x, unit.y, type().range, type().targetAir, type().targetGround);
        // invalid(target) is always false
        noTargetTime = 0f;
        unit.isShooting = false;

        for(WeaponMount mount : unit.mounts){
            Weapon weapon = Weapon.of(unit, mount);
            float wrange = weapon.range();

            if(!weapon.controllable || weapon.noAttack) continue;
            if(!weapon.aiControllable){
                mount.rotate = false;
                continue;
            }

            float mountX = unit.x + Angles.trnsx(rotation, weapon.x, weapon.y), mountY = unit.y + Angles.trnsy(rotation, weapon.x, weapon.y);
            // singleTarget or not, every mount takes the main target (findTarget is target)
            mount.target = target;
            boolean aimed = position || mount.target != null;
            boolean shoot = false;

            if(aimed){
                float tx = position ? posX : Targets.x(mount.target), ty = position ? posY : Targets.y(mount.target);
                float size = position ? 0f : Targets.hitSize(mount.target) / 2f;
                shoot = Mathf.within(tx, ty, mountX, mountY, wrange + size) && shouldShoot();

                if(type().autoDropBombs && !shoot){
                    if(bomberTarget == null || !bomberTarget.isAdded()
                        || !Targets.within(bomberTarget, unit.x, unit.y, unit.hitSize / 2f + Targets.hitSize(bomberTarget) / 2f)){
                        bomberTarget = Targets.closestTarget(unit.world(), unit.team, unit.x, unit.y, unit.hitSize, u -> !u.isFlying(), t -> true);
                    }
                    shoot = bomberTarget != null;
                }

                Vec2 to = position ? Predict.intercept(unit.x, unit.y, posX, posY, weapon.bullet.keepVelocity ? -unit.deltaX : 0f,
                    weapon.bullet.keepVelocity ? -unit.deltaY : 0f, weapon.bullet.speed) : Predict.intercept(unit, mount.target, weapon.bullet);
                mount.aimX = to.x;
                mount.aimY = to.y;
            }

            mount.shoot = mount.rotate = shoot;
            if(!shouldFire()) mount.shoot = false;
            unit.isShooting |= mount.shoot;

            // noTargetTime never reaches the rotate-back timer
            if(shoot){
                unit.aimX = mount.aimX;
                unit.aimY = mount.aimY;
            }
        }
    }

    // endregion
    // region state

    private static BuildPlan copy(BuildPlan plan){
        var out = BuildPlan.place(plan.x, plan.y, plan.rotation, plan.block, BuildPlan.copyConfig(plan.config));
        out.breaking = plan.breaking;
        out.progress = plan.progress;
        out.initialized = plan.initialized;
        out.stuck = plan.stuck;
        out.cachedValid = plan.cachedValid;
        out.worldContext = plan.worldContext;
        return out;
    }

    @Override
    public UnitController copy(CopyContext context){
        return new LogicAi(this, context);
    }

    @Override
    public void hash(Hasher hasher){
        super.hash(hasher);
        hasher.i(control.ordinal()).f(moveX).f(moveY).f(moveRad).f(controlTimer).f(targetTimer)
            .i(controller == null ? -1 : controller.id()).i(aimControl.ordinal()).bool(boost)
            .i(mainTarget == null ? -1 : mainTarget.id()).bool(shoot).i(posId).f(posX).f(posY);
        hasher.i(plan.x).i(plan.y).i(plan.rotation).i(plan.block).bool(plan.breaking).f(plan.progress).bool(plan.initialized)
            .bool(plan.stuck).i(queuedPlan());
        hasher.i(cache.size());
        for(Cached entry : cache) entry.hash(hasher);
        hasher.i(radars.size);
        for(int i = 0; i < radars.size; i++) hasher.i(radars.get(i));
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.e("control", control).f("moveX", moveX).f("moveY", moveY).f("moveRad", moveRad).f("controlTimer", controlTimer)
            .f("targetTimer", targetTimer).ref("controller", controller).obj("plan", plan).i("planLink", queuedPlan())
            .e("aimControl", aimControl).bool("boost", boost).ref("mainTarget", mainTarget).bool("shoot", shoot).i("posId", posId)
            .f("posX", posX).f("posY", posY).list("cache", cache).intSeq("radars", radars);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        control = in.e("control", LUnitControl.class);
        moveX = in.f("moveX");
        moveY = in.f("moveY");
        moveRad = in.f("moveRad");
        controlTimer = in.f("controlTimer");
        targetTimer = in.f("targetTimer");
        controller = in.ref("controller", Building.class);
        plan = in.obj("plan", BuildPlan.class);
        planLink = in.i("planLink");
        aimControl = in.e("aimControl", LUnitControl.class);
        boost = in.bool("boost");
        mainTarget = in.ref("mainTarget", Entity.class);
        shoot = in.bool("shoot");
        posId = in.i("posId");
        posX = in.f("posX");
        posY = in.f("posY");
        cache.clear();
        cache.addAll(in.list("cache", Cached.class));
        in.intSeq("radars", radars);
        if(plan == null || control == null || aimControl == null) throw new CheckpointException("a logic AI is missing a part");
    }

    @Override
    public String toString(){
        return "LogicAi(" + control + ")";
    }

    // endregion

    /**
     * One {@code execCache} entry: the instruction it belongs to (its processor's building id, code generation and
     * index), and what it cached: {@code radar}'s unit, or {@code ulocate}'s {@code Cache} (position, found, building).
     */
    public static final class Cached implements Checkpointable{
        public int processor, generation, index;
        /** {@code radar}: the unit found, or null. */
        public Entity found;
        /** {@code ulocate}'s {@code Cache}. */
        public float x, y;
        public boolean any;
        public Building build;

        public Cached(){
        }

        Cached(int processor, int generation, int index){
            this.processor = processor;
            this.generation = generation;
            this.index = index;
        }

        Cached copy(CopyContext context){
            var out = new Cached(processor, generation, index);
            out.found = context.map(found);
            out.x = x;
            out.y = y;
            out.any = any;
            out.build = context.map(build);
            return out;
        }

        void hash(Hasher hasher){
            hasher.i(processor).i(generation).i(index).i(found == null ? -1 : found.id()).f(x).f(y).bool(any).i(build == null ? -1 : build.id());
        }

        @Override
        public void write(CheckpointOut out){
            out.i("processor", processor).i("generation", generation).i("index", index).ref("found", found).f("x", x).f("y", y)
                .bool("any", any).ref("build", build);
        }

        @Override
        public void read(CheckpointIn in){
            processor = in.i("processor");
            generation = in.i("generation");
            index = in.i("index");
            found = in.ref("found", Entity.class);
            x = in.f("x");
            y = in.f("y");
            any = in.bool("any");
            build = in.ref("build", Building.class);
        }
    }

    /** {@code new LogicAI()} for the {@code logic-ai} kind. */
    @Register
    public static final class Kind implements AiKinds{
        @Override
        public AiController create(World world, String kind){
            return KIND.equals(kind) ? new LogicAi(world) : null;
        }
    }
}
