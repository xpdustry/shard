package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.sim.ai.CommandAi;
import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.logic.*;
import com.xpdustry.shard.sim.path.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code AIController} (AIController.java): the state every AI shares and the behaviour the wave AIs build
 * on. An update runs the fallback AI when {@link #useFallback} says so, else {@link #updateVisuals},
 * {@link #updateTargeting} and {@link #updateMovement}; subclasses override those and the targeting hooks, as vanilla's
 * {@code ai.types} do. The AI kinds live in {@code sim.ai} and are made through {@link AiKinds}; this class is also
 * the inert AI of a kind no package ports yet, which only draws its timers.
 *
 * <p>Timers. {@link #timers} is vanilla's {@code Interval(4)}: the {@code Time.time} each slot last fired, where
 * {@code Time.time} is the world's {@link Timers#time()}. Most AIs draw two timers from the random when constructed
 * ({@code AIController.java:40}), so one is made whenever vanilla constructs one, keeping creation's draws in step.
 *
 * <p>{@link #kind} names the vanilla class as the content does: {@code ground-ai}, {@code flying-ai},
 * {@code builder-ai}, {@code missile-ai}, {@code suicide-ai}… The wire tells only {@code AssemblerAI} apart
 * ({@code TypeIO.writeController}); {@link CommandAi} has its own class.
 *
 * <p>Owner: WP54.
 */
public class AiController implements UnitController, Checkpointable{
    public static final String GROUND = "ground-ai", FLYING = "flying-ai", MISSILE = "missile-ai", ASSEMBLER = "assembler-ai";
    /** {@code rotateBackTimer}: idle mounts turn back to their base rotation after this long without a target. */
    protected static final float ROTATE_BACK_TIMER = 60f * 5f;
    protected static final int TIMER_TARGET = 0, TIMER_TARGET2 = 1, TIMER_TARGET3 = 2, TIMER_TARGET4 = 3;
    /** {@code Vars.tilesize}. */
    protected static final float TILESIZE = 8f;

    /** The AI's kind, as the content names it. */
    public String kind;
    /** {@code timer}: vanilla's {@code Interval(4)}, the time each slot last fired. */
    public final float[] timers = new float[4];
    /** {@code fallback}: the AI {@link #useFallback} hands over to, made on first use. */
    public AiController fallback;
    /** {@code noTargetTime}: frames since the last valid target. */
    public float noTargetTime;
    /** {@code target}: the main target being faced, a unit or a building, or null. */
    public Entity target;
    /** {@code bomberTarget}: what an {@code autoDropBombs} type is over. */
    public Entity bomberTarget;
    /** {@code turningAway}: a circling unit too close to its target flies away from it. */
    public boolean turningAway;
    /**
     * The controlled unit: set by {@link #bound} and by every call that passes it. Stored, so that a restored command
     * AI's controller doesn't run {@code init()} again, as a fork's doesn't, and a restore can read the unit's team.
     */
    protected Unit unit;

    /** {@code unit()}: the unit this AI drives, or null before it is bound. */
    public Unit unit(){
        return unit;
    }

    /** Vanilla's constructor: {@code resetTimers()}, which draws two random timers; a subclass may override it, as vanilla's. */
    @SuppressWarnings("this-escape")
    public AiController(World world, String kind){
        this.kind = kind;
        resetTimers(world);
    }

    /** A blank controller for a checkpoint to fill: no draws. */
    public AiController(){
    }

    /** A copy of {@code source}'s state for a fork. */
    protected AiController(AiController source, CopyContext context){
        kind = source.kind;
        System.arraycopy(source.timers, 0, timers, 0, timers.length);
        fallback = source.fallback == null ? null : (AiController)source.fallback.copy(context);
        noTargetTime = source.noTargetTime;
        target = context.map(source.target);
        bomberTarget = context.map(source.bomberTarget);
        turningAway = source.turningAway;
        unit = context.map(source.unit);
    }

    /** {@code resetTimers()}: {@code MissileAI} overrides it with one fixed timer and no draws. */
    protected void resetTimers(World world){
        Rand rand = world.rand();
        timerReset(world, TIMER_TARGET, rand.random(40f));
        timerReset(world, TIMER_TARGET2, rand.random(60f));
    }

    // region timers (arc.util.Interval over Time.time)

    /** {@code Time.time}. */
    protected static float time(World world){
        return world.timers().time();
    }

    /** {@code timer.reset(id, time)}. */
    protected final void timerReset(World world, int id, float time){
        timers[id] = time(world) - time;
    }

    /** {@code timer.get(id, time)}: whether {@code time} passed since slot {@code id} last fired, which it then does. */
    protected final boolean timerGet(int id, float time){
        float now = time(unit.world());
        boolean got = now - timers[id] >= time || now < timers[id];
        if(got) timers[id] = now;
        return got;
    }

    // endregion

    // region UnitController

    @Override
    public void updateUnit(Unit unit){
        this.unit = unit;
        // use the fallback AI when possible
        if(useFallback() && (fallback != null || (fallback = fallback()) != null)){
            if(fallback.unit != unit) fallback.bound(unit);
            fallback.updateUnit(unit);
            return;
        }
        updateVisuals();
        updateTargeting();
        updateMovement();
    }

    /** {@code unit(unit)}: {@code init()} runs when the controlled unit changes. */
    @Override
    public void bound(Unit unit){
        if(this.unit == unit) return;
        this.unit = unit;
        init();
    }

    @Override
    public void hit(Unit unit, Entity owner){
        this.unit = unit;
    }

    /** Called after this controller is assigned a unit ({@code init()}). */
    public void init(){
    }

    /** {@code keepState()}: whether the controller's state survives a read; only {@code CommandAI} and logic say yes. */
    public boolean keepState(){
        return false;
    }

    // endregion

    // region overridable behaviour

    /** {@code fallback()}: the AI to hand over to, or null. Making it draws its timers, as vanilla's constructor does. */
    public AiController fallback(){
        return null;
    }

    /** {@code useFallback()}. */
    public boolean useFallback(){
        return false;
    }

    /** {@code updateVisuals()}: a flying unit wobbles and turns to its preferred rotation. */
    public void updateVisuals(){
        if(unit.isFlying()){
            if(type().wobble) wobble(unit);
            unit.lookAt(prefRotation(unit));
        }
    }

    public void updateMovement(){
    }

    /** {@code updateTargeting()}: an armed unit updates its weapons. */
    public void updateTargeting(){
        if(type().hasWeapons) updateWeapons();
    }

    /** {@code targetInvalidated()}: find a new target at once. */
    public void targetInvalidated(){
        timerReset(unit.world(), TIMER_TARGET, -1f);
    }

    /** {@code updateWeapons()}: retargets, then aims, rotates and fires every AI-controllable mount. */
    public void updateWeapons(){
        AiType type = type();
        float rotation = unit.rotation - 90;
        boolean ret = retarget();
        if(ret) target = findMainTarget(unit.x, unit.y, type.range, type.targetAir, type.targetGround);

        noTargetTime += 1f;

        if(invalid(target)){
            if(target != null && !Targets.isValid(target)) targetInvalidated();
            target = null;
        }else{
            noTargetTime = 0f;
        }

        unit.isShooting = false;

        for(WeaponMount mount : unit.mounts){
            Weapon weapon = Weapon.of(unit, mount);
            float wrange = weapon.range();

            // let uncontrollable weapons do their own thing
            if(!weapon.controllable || weapon.noAttack) continue;

            if(!weapon.aiControllable){
                mount.rotate = false;
                continue;
            }

            float mountX = unit.x + Angles.trnsx(rotation, weapon.x, weapon.y), mountY = unit.y + Angles.trnsy(rotation, weapon.x, weapon.y);

            if(type.singleTarget){
                mount.target = target;
            }else{
                if(ret) mount.target = findTarget(mountX, mountY, wrange, weapon.bullet.collidesAir, weapon.bullet.collidesGround);
                if(checkTarget(mount.target, mountX, mountY, wrange)) mount.target = null;
            }

            boolean shoot = false;

            if(mount.target != null){
                shoot = Targets.within(mount.target, mountX, mountY, wrange + Targets.hitSize(mount.target) / 2f) && shouldShoot();

                if(type.autoDropBombs && !shoot){
                    if(bomberTarget == null || !bomberTarget.isAdded()
                        || !Targets.within(bomberTarget, unit.x, unit.y, unit.hitSize / 2f + Targets.hitSize(bomberTarget) / 2f)){
                        bomberTarget = Targets.closestTarget(unit.world(), unit.team, unit.x, unit.y, unit.hitSize, u -> !u.isFlying(), t -> true);
                    }
                    shoot = bomberTarget != null;
                }

                Vec2 to = Predict.intercept(unit, mount.target, weapon.bullet);
                mount.aimX = to.x;
                mount.aimY = to.y;
            }

            mount.shoot = mount.rotate = shoot;
            if(!shouldFire()) mount.shoot = false;

            unit.isShooting |= mount.shoot;

            if(mount.target == null && !shoot && !Angles.within(mount.rotation, weapon.baseRotation, 0.01f) && noTargetTime >= ROTATE_BACK_TIMER){
                mount.rotate = true;
                var back = new Vec2().trns(unit.rotation + weapon.baseRotation, 5f);
                mount.aimX = mountX + back.x;
                mount.aimY = mountY + back.y;
            }

            if(shoot){
                unit.aimX = mount.aimX;
                unit.aimY = mount.aimY;
            }
        }
    }

    /** {@code checkTarget(target, x, y, range)}: true when the target is no good from there. */
    public boolean checkTarget(Entity target, float x, float y, float range){
        return Targets.invalidateTarget(target, unit.team, x, y, range);
    }

    /** {@code shouldFire()}: whether the unit really fires, rather than only aiming. */
    public boolean shouldFire(){
        return true;
    }

    public boolean shouldShoot(){
        return true;
    }

    /** {@code invalid(target)}: no target, a friend, dead, or untargetable, at any range. */
    public boolean invalid(Entity target){
        return Targets.invalidateTarget(target, unit.team, unit.x, unit.y, Float.MAX_VALUE);
    }

    /** {@code retarget()}: every 40 frames without a target, 90 with one. */
    public boolean retarget(){
        return timerGet(TIMER_TARGET, target == null ? 40 : 90);
    }

    public Entity findMainTarget(float x, float y, float range, boolean air, boolean ground){
        return findTarget(x, y, range, air, ground);
    }

    public Entity findTarget(float x, float y, float range, boolean air, boolean ground){
        return target(x, y, range, air, ground);
    }

    /** {@code target(x, y, range, air, ground)}: the closest enemy unit its weapons hit, else building. */
    public Entity target(float x, float y, float range, boolean air, boolean ground){
        boolean underBlocks = type().targetUnderBlocks;
        return Targets.closestTarget(unit.world(), unit.team, x, y, range, u -> Targets.checkTarget(u, air, ground),
            t -> ground && (underBlocks || !underBullets(t)));
    }

    /** {@code prefSpeed()}. */
    public float prefSpeed(){
        return unit.speed();
    }

    /** {@code stanceChanged()}: a command AI's stances changed ({@code CommandAI.setStance}); only {@code MinerAI} listens. */
    public void stanceChanged(){
    }

    /** {@code commandPosition(pos)}: what a command AI tells the AI it runs. */
    public void commandPosition(Vec2 pos){
    }

    /** {@code commandTarget(target)}. */
    public void commandTarget(Entity target){
    }

    // endregion

    // region helpers

    /** The fields this AI reads of its unit's type. */
    protected final AiType type(){
        return unit.world().unitTable().ai(unit.type());
    }

    /** {@code hasStance(stance)}: only a command AI has stances. */
    public boolean hasStance(int stance){
        return unit.controller instanceof CommandAi ai && ai.hasStance(stance);
    }

    /** {@code stopShooting()}: every mount stops, uncontrollable ones too. */
    public void stopShooting(){
        for(WeaponMount mount : unit.mounts) mount.shoot = false;
    }

    /** {@code faceTarget()}: an omni unit or a mech looks at its target, else where it moves. */
    public void faceTarget(){
        AiType type = type();
        if(unit.params().omniMovement() || unit.shape instanceof MoveShape.Mech){
            if(!Targets.invalidateTarget(target, unit.team, unit.x, unit.y, type.range) && type.faceTarget && type.hasWeapons){
                Vec2 to = Predict.intercept(unit, target, Weapon.of(unit, unit.mounts[0]).bullet);
                unit.lookAt(to.x, to.y);
            }else if(unit.moving()){
                unit.lookAt(unit.vel.angle());
            }
        }
    }

    /** {@code faceMovement()}. */
    public void faceMovement(){
        if((unit.params().omniMovement() || unit.shape instanceof MoveShape.Mech) && unit.moving()){
            unit.lookAt(unit.vel.angle());
        }
    }

    /** {@code targetFlag(x, y, flag, enemy)}: the closest building with the flag, the enemy's or the team's own. */
    public Building targetFlag(float x, float y, BlockFlag flag, boolean enemy){
        if(unit.team == Team.DERELICT) return null;
        Buildings buildings = unit.world().buildings();
        return closest(x, y, enemy ? buildings.enemy(unit.team, flag) : buildings.flagged(unit.team, flag), false);
    }

    /** {@code targetFlagActive(x, y, flag, enemy)}: as {@link #targetFlag}, among targetable buildings with items or not idle. */
    public Building targetFlagActive(float x, float y, BlockFlag flag, boolean enemy){
        if(unit.team == Team.DERELICT) return null;
        Buildings buildings = unit.world().buildings();
        return closest(x, y, enemy ? buildings.enemy(unit.team, flag) : buildings.flagged(unit.team, flag), true);
    }

    /** {@code Geometry.findClosest(x, y, list[, active])}: the first of the nearest, by squared distance. */
    private Building closest(float x, float y, Iterable<Building> list, boolean active){
        Building closest = null;
        float cdist = 0f;
        double tick = 0;
        if(active){
            LogicPart logic = LogicPart.of(unit.world());
            tick = logic == null ? unit.world().ticks() : logic.tick();
        }
        for(Building building : list){
            if(active && !((building.items != null && building.items.any()) || building.status(tick) != BlockStatus.noInput) || active && !building.info().targetable){
                continue;
            }
            float dst = Mathf.dst2(building.x(), building.y(), x, y);
            if(closest == null || dst < cdist){
                closest = building;
                cdist = dst;
            }
        }
        return closest;
    }

    /** {@code getClosestSpawner()}: the nearest wave spawn tile's position, as {@code y * width + x}, or -1. */
    public int closestSpawner(){
        WaveSpawner spawner = WaveSpawner.of(unit.world());
        if(spawner == null) return -1;
        IntSeq spawns = spawner.spawns(unit.world());
        int width = unit.world().tiles().width(), closest = -1;
        float cdist = 0f;
        for(int i = 0; i < spawns.size; i++){
            int pos = spawns.get(i);
            float dst = unit.dst2(pos % width * TILESIZE, pos / width * TILESIZE);
            if(closest == -1 || dst < cdist){
                closest = pos;
                cdist = dst;
            }
        }
        return closest;
    }

    /** {@code unit.within(tile, dst)} for a tile position. */
    protected final boolean withinTile(int pos, float dst){
        int width = unit.world().tiles().width();
        return unit.within(pos % width * TILESIZE, pos / width * TILESIZE, dst);
    }

    /** {@code unit.closestEnemyCore()}. */
    public Building closestEnemyCore(){
        Teams teams = Teams.of(unit.world());
        return teams == null ? null : teams.closestEnemyCore(unit.x, unit.y, unit.team);
    }

    /** {@code unit.closestCore()}. */
    public Building closestCore(){
        Teams teams = Teams.of(unit.world());
        return teams == null ? null : teams.closestCore(unit.x, unit.y, unit.team);
    }

    /** {@code unit.buildOn()}: the building under the unit's centre, or null. */
    public Building buildOn(){
        int pos = unit.tileOn();
        return pos == -1 ? null : unit.world().buildings().at(pos);
    }

    /** {@code unloadPayloads()} (AIController.java:310): a carried unit is dropped once the target building is near. */
    public void unloadPayloads(){
        if(unit.payloads != null && Payloads.hasPayload(unit) && target instanceof Building build && unit.payloads.peek() instanceof Payload.UnitPayload){
            if(unit.within(build.x(), build.y(), Math.max(type().range + 1f, 75f))) Payloads.dropLastPayload(unit, new Cause.Ai(unit.team));
        }
    }

    /** {@code circleAttack(circleLength)}: fly through the target, turn, and come back. */
    public void circleAttack(float circleLength){
        if(target == null) return;

        var vec = new Vec2(Targets.x(target), Targets.y(target)).sub(unit.x, unit.y);

        float ang = unit.angleTo(Targets.x(target), Targets.y(target));
        float diff = Angles.angleDist(ang, unit.rotation);

        if(target instanceof Unit u && u.collisionLayer() == unit.collisionLayer()){
            float avoidDist = u.physicSize() + 30f;
            if(turningAway){
                vec.setLength(prefSpeed()).scl(-1f);
                unit.movePref(vec);

                if(!unit.within(u.x, u.y, type().circleTargetRadius * 0.5f + u.physicSize())){
                    turningAway = false;
                }
                return;
            }else if(unit.within(u.x, u.y, avoidDist)){
                turningAway = true;
            }
        }

        if(diff > 70f && vec.len() < circleLength){
            vec.setAngle(unit.vel.angle());
        }else if(unit.params().omniMovement()){
            // non-omni movement units don't need to do this as the turning is already smoothed out
            vec.setAngle(Angles.moveToward(unit.vel.angle(), vec.angle(), 6f));
        }

        vec.setLength(prefSpeed());

        unit.movePref(vec);
    }

    /** {@code circle(target, circleLength)}. */
    public void circle(float x, float y, float circleLength){
        circle(x, y, circleLength, prefSpeed());
    }

    /** {@code circle(target, circleLength, speed)}: around (x, y), turning harder the closer it gets. */
    public void circle(float x, float y, float circleLength, float speed){
        var vec = new Vec2(x, y).sub(unit.x, unit.y);
        if(vec.len() < circleLength){
            vec.rotate((circleLength - vec.len()) / circleLength * 180f);
        }
        vec.setLength(speed);
        unit.movePref(vec);
    }

    /** {@code moveTo(target, circleLength)}. */
    public void moveTo(float x, float y, float circleLength){
        moveTo(x, y, circleLength, 100f);
    }

    /** {@code moveTo(target, circleLength, smooth)}. */
    public void moveTo(float x, float y, float circleLength, float smooth){
        moveTo(x, y, circleLength, smooth, unit.isFlying(), null, false);
    }

    /** {@code moveTo(target, circleLength, smooth, keepDistance, offset)}. */
    public void moveTo(float x, float y, float circleLength, float smooth, boolean keepDistance, Vec2 offset){
        moveTo(x, y, circleLength, smooth, keepDistance, offset, false);
    }

    /**
     * {@code moveTo(target, circleLength, smooth, keepDistance, offset, arrive)}: toward (x, y), slowing within
     * {@code smooth} of {@code circleLength} away; inside it, back off ({@code keepDistance}) or stop.
     */
    public void moveTo(float x, float y, float circleLength, float smooth, boolean keepDistance, Vec2 offset, boolean arrive){
        float speed = prefSpeed();

        var vec = new Vec2(x, y).sub(unit.x, unit.y);

        float length = circleLength <= 0.001f ? 1f : Mathf.clamp((unit.dst(x, y) - circleLength) / smooth, -1f, 1f);

        vec.setLength(speed * length);

        if(arrive && length > 0){
            float accel = unit.params().accel();
            var v3 = new Vec2(-unit.vel.x / accel * 2f, -unit.vel.y / accel * 2f).add(x - unit.x, y - unit.y);

            if(unit.params().omniMovement() || type().rotateMoveFirst){
                vec.add(v3).limit(speed * length);
            }else{
                // directly move the unit to prevent a backwards movement vector from messing things up
                unit.moveAt(v3.limit(speed * length));
            }
        }

        if(length < -0.5f){
            if(keepDistance){
                vec.rotate(180f);
            }else{
                vec.setZero();
            }
        }else if(length < 0){
            vec.setZero();
        }

        if(offset != null){
            vec.add(offset);
            vec.setLength(speed * length);
        }

        // ignore invalid movement values
        if(Float.isNaN(vec.x) || Float.isNaN(vec.y) || Float.isInfinite(vec.x) || Float.isInfinite(vec.y) || vec.isZero()) return;

        if(!unit.params().omniMovement() && type().rotateMoveFirst){
            float angle = vec.angle();
            unit.lookAt(angle);
            if(Angles.within(unit.rotation, angle, 3f)){
                unit.movePref(vec);
            }
        }else{
            unit.movePref(vec);
        }
    }

    /** {@code pathfind(Pathfinder.fieldCore)}. */
    public void pathfind(){
        pathfind(true, false);
    }

    /**
     * {@code pathfind(fieldCore, stopAtTargetTile, avoidance)}: one step down the team's enemy-core flowfield for the
     * type's cost class, toward the next tile at the preferred speed. With {@code avoidance}, a ground unit steps
     * around tiles lower-id units of AI teams cover ({@link AvoidanceProcess}).
     */
    public void pathfind(boolean stopAtTargetTile, boolean avoidance){
        World world = unit.world();
        PathService paths = world.find(PathService.class);
        int tile = unit.tileOn();
        if(paths == null || !paths.active() || tile == -1) return;
        int avoidanceId = avoidance && unit.collisionLayer() == UnitPhysics.LAYER_GROUND ? unit.id() : 0;
        // pathfinder.getField(team, cost, fieldCore): the reference is the world's, as vanilla's cache is
        int handle = paths.field(unit.team, type().flowCost);
        int next = paths.next(handle, tile, avoidanceId > 0 ? world.find(AvoidanceProcess.class) : null, avoidanceId);
        paths.release(handle);

        TileGrid tiles = world.tiles();
        int nx = tiles.x(next), ny = tiles.y(next);
        if((tile == next && stopAtTargetTile) || !unit.canPass(nx, ny)) return;

        unit.movePref(new Vec2().trns(unit.angleTo(nx * TILESIZE, ny * TILESIZE), prefSpeed()));
    }

    /** {@code World.raycast(x1, y1, x2, y2, cons)}: a diagonal Bresenham walk; true as soon as {@code hit} is. */
    public static boolean raycast(int x1, int y1, int x2, int y2, Raycaster hit){
        int x = x1, dx = Math.abs(x2 - x), sx = x < x2 ? 1 : -1;
        int y = y1, dy = Math.abs(y2 - y), sy = y < y2 ? 1 : -1;
        int e2, err = dx - dy;
        while(true){
            if(hit.accept(x, y)) return true;
            if(x == x2 && y == y2) return false;
            e2 = 2 * err;
            if(e2 > -dy){
                err = err - dy;
                x = x + sx;
            }
            if(e2 < dx){
                err = err + dx;
                y = y + sy;
            }
        }
    }

    /** {@code World.Raycaster}. */
    @FunctionalInterface
    public interface Raycaster{
        boolean accept(int x, int y);
    }

    /** {@code tileX()} of a unit or a building. */
    public static int tileX(Entity target){
        return target instanceof Unit u ? u.tileX() : ((Building)target).tileX();
    }

    /** {@code tileY()} of a unit or a building. */
    public static int tileY(Entity target){
        return target instanceof Unit u ? u.tileY() : ((Building)target).tileY();
    }

    /** {@code block.underBullets}. */
    public static boolean underBullets(Building building){
        return building.block().baked() && building.block().underBullets();
    }

    /** {@code UnitComp.wobble()}: a slow sine drift, by the unit's id and the game time. */
    public static void wobble(Unit unit){
        float time = time(unit.world()) + (unit.id() % 10) * 12;
        unit.x += Mathf.sin(time, 25f, 0.05f) * unit.elevation;
        unit.y += Mathf.cos(time, 25f, 0.05f) * unit.elevation;
    }

    /** {@code UnitComp.prefRotation()}: toward the plan it builds or the ore it mines, else along an omni unit's motion. */
    public static float prefRotation(Unit unit){
        AiType type = unit.world().unitTable().ai(unit.type());
        if(activelyBuilding(unit, type) && type.rotateToBuilding){
            BuildPlan plan = unit.plans.first();
            return unit.angleTo(planX(unit, plan), planY(unit, plan));
        }else if(unit.mineTile != -1){
            return unit.angleTo(Point2.x(unit.mineTile) * TILESIZE, Point2.y(unit.mineTile) * TILESIZE);
        }else if(unit.moving() && unit.params().omniMovement()){
            return unit.vel.angle();
        }
        return unit.rotation;
    }

    /** {@code BuilderComp.activelyBuilding()}: building, and in range of the first plan. */
    static boolean activelyBuilding(Unit unit, AiType type){
        if(unit.plans.isEmpty()) return false;
        BuildPlan plan = unit.plans.first();
        Rules rules = UnitCalls.rules(unit.world());
        if(!rules.editor && plan != null && !unit.within(planX(unit, plan), planY(unit, plan), rules.infiniteResources ? Float.MAX_VALUE : type.buildRange)){
            return false;
        }
        return unit.updateBuilding;
    }

    /** {@code BuildPlan.drawx()}. */
    static float planX(Unit unit, BuildPlan plan){
        return plan.x * TILESIZE + offset(unit, plan);
    }

    static float planY(Unit unit, BuildPlan plan){
        return plan.y * TILESIZE + offset(unit, plan);
    }

    private static float offset(Unit unit, BuildPlan plan){
        if(plan.block < 0) return 0f;
        int size = unit.world().content().block(plan.block).size();
        return (size + 1) % 2 * TILESIZE / 2f;
    }

    // endregion

    // region state

    @Override
    public UnitController copy(CopyContext context){
        return new AiController(this, context);
    }

    @Override
    public void hash(Hasher hasher){
        hasher.str(kind);
        for(float timer : timers) hasher.f(timer);
        hasher.bool(fallback != null);
        if(fallback != null) fallback.hash(hasher);
        hasher.f(noTargetTime).i(target == null ? -1 : target.id()).i(bomberTarget == null ? -1 : bomberTarget.id()).bool(turningAway);
    }

    /** Subclasses store their own state after calling this. */
    @Override
    public void write(CheckpointOut out){
        out.str("kind", kind).floats("timers", timers).obj("fallback", fallback).f("noTargetTime", noTargetTime)
            .ref("target", target).ref("bomberTarget", bomberTarget).bool("turningAway", turningAway).ref("unit", unit);
    }

    @Override
    public void read(CheckpointIn in){
        kind = in.str("kind");
        in.floats("timers", timers);
        fallback = in.obj("fallback", AiController.class);
        noTargetTime = in.f("noTargetTime");
        target = in.ref("target", Entity.class);
        bomberTarget = in.ref("bomberTarget", Entity.class);
        turningAway = in.bool("turningAway");
        unit = in.ref("unit", Unit.class);
    }

    // endregion

    @Override
    public String toString(){
        return "AiController(" + kind + ")";
    }
}
