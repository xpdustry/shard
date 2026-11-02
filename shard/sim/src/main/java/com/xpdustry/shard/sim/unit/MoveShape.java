package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * A unit's movement shape: the state and rules of vanilla's movement components ({@code MechComp}, {@code LegsComp},
 * {@code TankComp}, {@code CrawlComp}, {@code WaterMoveComp}, {@code ElevationMoveComp}), one subclass per
 * {@link Locomotion}. It holds what those components keep that the server simulates, syncs or saves; trails, tread
 * and splash effects are client-only and left out.
 *
 * <p>The base class is a class without a movement component, {@link Flying}: every hook's default is what
 * {@code UnitComp}, {@code PosComp} and {@code VelComp} do. A shape overrides the hooks its component replaces or
 * extends ({@code @Replace} or a merged body), and registers a {@link UnitComponent} for its {@code update()} (see
 * the {@code sim.unit} package comment): {@link MechComponent}, {@link LegsComponent}, {@link TankComponent} and
 * {@link CrawlComponent}. {@code WaterMoveComp}'s update only moves its trails, and {@code ElevationMoveComp} has none.
 *
 * <p>Every field is {@link Persistence#AUTHORITATIVE}.
 */
public abstract sealed class MoveShape permits MoveShape.Mech, MoveShape.Legs, MoveShape.Tank, MoveShape.Crawl,
    MoveShape.Naval, MoveShape.Hover, MoveShape.Flying{

    /** A new shape for {@code locomotion}, in the state of a freshly constructed entity. */
    public static MoveShape of(Locomotion locomotion){
        return switch(locomotion){
            case MECH -> new Mech();
            case LEGS -> new Legs();
            case TANK -> new Tank();
            case CRAWL -> new Crawl();
            case NAVAL -> new Naval();
            case HOVER -> new Hover();
            case FLYING -> new Flying();
        };
    }

    public abstract Locomotion locomotion();

    /**
     * Vanilla's {@code solidity()}: the predicate that stops {@code unit} on tiles, or null when it moves freely. No
     * vanilla unit overrides {@code ignoreSolids()}, so it is always false here.
     */
    public abstract Solidity solidity(Unit unit);

    public abstract MoveShape copy();

    public abstract void hash(Hasher hasher);

    /** The merged {@code add()} body of the component, after {@code HitboxComp}'s and before {@code UnitComp}'s. */
    public void added(Unit unit){
    }

    /** A merged {@code destroy()} body that runs before {@code UnitComp.destroy}'s ({@code LegsComp}'s, priority -1). */
    public void destroyed(Unit unit){
    }

    /** A merged {@code moveAt(vector, accel)} body, before {@code UnitComp}'s ({@code MechComp} marks a step). */
    public void beforeMoveAt(Unit unit, Vec2 vector){
    }

    /** A merged {@code approach(vector)} body, before {@code UnitComp}'s. */
    public void beforeApproach(Unit unit, Vec2 vector){
    }

    /**
     * {@code UnitComp.rotateMove(vec)}: accelerates along the current rotation by {@code vec}'s length and turns toward
     * {@code vec} ({@code MechComp} replaces it).
     */
    public void rotateMove(Unit unit, Vec2 vec){
        unit.moveAt(new Vec2().trns(unit.rotation, vec.len()));
        if(!vec.isZero()){
            unit.rotation = Angles.moveToward(unit.rotation, vec.angle(), unit.params().rotateSpeed() * unit.statuses.speedMultiplier);
        }
    }

    /**
     * {@code UnitComp.floorSpeedMultiplier()}: the floor's speed multiplier to the type's {@code floorMultiplier}, air
     * when flying or hovering, times the status speed ({@code TankComp}, {@code CrawlComp} and {@code WaterMoveComp}
     * replace it).
     */
    public float floorSpeedMultiplier(Unit unit){
        int on = unit.isFlying() || unit.params().hovering() ? 0 : unit.floorOn();
        float floor = unit.world().tiles().table().speedMultiplier(on), exponent = unit.params().floorMultiplier();
        // pow(x, 1) is x exactly (StrictMath.pow's contract), and most types' floorMultiplier is 1: skip the call
        return (exponent == 1f ? floor : Mathf.pow(floor, exponent)) * unit.statuses.speedMultiplier;
    }

    /** {@code UnitComp.drownFloor()}: the floor id that may drown the unit, or -1 for none ({@code floorOn()}). */
    public int drownFloor(Unit unit){
        return unit.floorOn();
    }

    /** {@code PosComp.onSolid()}: outside the map or on a solid tile ({@code WaterMoveComp} replaces it). */
    public boolean onSolid(Unit unit){
        return TileSolidity.of(unit.world()).solid(Solidity.SOLID, unit.tileX(), unit.tileY());
    }

    /**
     * {@code UnitComp.collisionLayer()}: which physics world pushes the unit. Legs that may step have their own, then
     * grounded units, then the rest fly ({@code UnderwaterMoveComp} replaces it).
     */
    public int collisionLayer(Unit unit){
        UnitParams params = unit.params();
        return params.allowLegStep() && params.legPhysicsLayer() ? UnitPhysics.LAYER_LEGS
            : unit.isGrounded() ? UnitPhysics.LAYER_GROUND : UnitPhysics.LAYER_FLYING;
    }

    /** Whether the unit can never be hit or targeted ({@code UnderwaterMoveComp}'s {@code hittable} and {@code targetable}). */
    public boolean submerged(Unit unit){
        return false;
    }

    /**
     * {@code unloaded()}: the unit left a factory, a payload or a spawn point ({@code LegsComp} resets its legs, which
     * draws from the random).
     */
    public void unloaded(Unit unit){
    }

    /** {@code ElevationMoveComp.solidity}: ground rules unless flying. */
    static Solidity elevated(Unit unit){
        return unit.isFlying() ? null : Solidity.SOLID;
    }

    /**
     * {@code MechComp}: the legs walk toward the movement ({@code baseRotation}), and {@code rotateMove} turns them,
     * not the body. Its update is {@link MechComponent}; the step effects are client-only.
     */
    public static final class Mech extends MoveShape implements Checkpointable{
        /** Synced (slerped) leg direction. */
        public float baseRotation;
        public float walkTime, walkExtension;
        /** Set by a controlled move since the last update; only then do the legs turn and walk. */
        public boolean walked;

        @Override
        public Locomotion locomotion(){
            return Locomotion.MECH;
        }

        @Override
        public Solidity solidity(Unit unit){
            return elevated(unit);
        }

        /** {@code MechComp.moveAt}: a non-zero move marks a step. */
        @Override
        public void beforeMoveAt(Unit unit, Vec2 vector){
            if(!vector.isZero()) walked = true;
        }

        /** {@code MechComp.approach}: {@code !vector.isZero(0.001f)} marks a step. */
        @Override
        public void beforeApproach(Unit unit, Vec2 vector){
            if(!(vector.len2() < 0.001f)) walked = true;
        }

        /** {@code MechComp.rotateMove}: forward along the legs, which turn toward {@code vec}, without the status speed. */
        @Override
        public void rotateMove(Unit unit, Vec2 vec){
            unit.moveAt(new Vec2().trns(baseRotation, vec.len()));
            if(!vec.isZero()){
                // rotateSpeed * Math.max(Time.delta, 1)
                baseRotation = Angles.moveToward(baseRotation, vec.angle(), unit.params().rotateSpeed());
            }
        }

        /** {@code MechComp.drownFloor}: a large mech drowns only when the eight tiles around it are deep too. */
        @Override
        public int drownFloor(Unit unit){
            if(unit.hitSize >= 12 && unit.canDrown()){
                var tiles = unit.world().tiles();
                for(Point2 p : Geometry.d8){
                    // world.floorWorld: air, which is never deep, outside the map
                    int tx = Math.round((unit.x + p.x() * 8) / 8f), ty = Math.round((unit.y + p.y() * 8) / 8f);
                    if(!tiles.inBounds(tx, ty) || !tiles.table().isDeep(tiles.floor(tiles.pos(tx, ty)))) return -1;
                }
            }
            return unit.floorOn();
        }

        /** {@code walkExtend(true)}: how many strides into its four-stride cycle the mech is. */
        static float walkExtendScaled(float walkTime, float stride){
            return walkTime % (stride * 4) / stride;
        }

        @Override
        public MoveShape copy(){
            var out = new Mech();
            out.baseRotation = baseRotation;
            out.walkTime = walkTime;
            out.walkExtension = walkExtension;
            out.walked = walked;
            return out;
        }

        @Override
        public void hash(Hasher hasher){
            hasher.f(baseRotation).f(walkTime).f(walkExtension).bool(walked);
        }

        @Override
        public void write(CheckpointOut out){
            out.f("baseRotation", baseRotation).f("walkTime", walkTime).f("walkExtension", walkExtension).bool("walked", walked);
        }

        @Override
        public void read(CheckpointIn in){
            baseRotation = in.f("baseRotation");
            walkTime = in.f("walkTime");
            walkExtension = in.f("walkExtension");
            walked = in.bool("walked");
        }
    }

    /** {@code LegsComp}. {@link Unit#add()} places the legs; WP44 ports the leg update. */
    public static final class Legs extends MoveShape implements Checkpointable{
        public Leg[] legs = {};
        public float totalLength, moveSpace, baseRotation;
        public final Vec2 curMoveOffset = new Vec2();
        /** The deep floor under a leg (content id), or -1. */
        public int lastDeepFloor = -1;

        @Override
        public Locomotion locomotion(){
            return Locomotion.LEGS;
        }

        @Override
        public Solidity solidity(Unit unit){
            return unit.params().allowLegStep() ? Solidity.LEGS : Solidity.SOLID;
        }

        /** {@code LegsComp.add}: new legs, which draws the step phase. */
        @Override
        public void added(Unit unit){
            reset(unit, unit.params().legLength());
        }

        /** {@code LegsComp.unloaded}: new legs of length 1, which draws the step phase. */
        @Override
        public void unloaded(Unit unit){
            reset(unit, 1f);
        }

        /** {@code LegsComp.drownFloor}: the deep floor under every leg, or none. */
        @Override
        public int drownFloor(Unit unit){
            return lastDeepFloor;
        }

        /**
         * {@code LegsComp.update}: turns the base toward the movement, steps the legs in groups and finds the deep floor
         * under all of them. Step effects are client-only; a step's splash damage goes to {@link UnitDamage}.
         */
        public void update(Unit unit){
            UnitParams params = unit.params();
            UnitParams.LegParams type = params.legs();
            if(Mathf.dst(unit.deltaX, unit.deltaY) > 0.001f){
                baseRotation = Angles.moveToward(baseRotation, Mathf.angle(unit.deltaX, unit.deltaY), params.rotateSpeed());
            }
            if(params.lockLegBase()) baseRotation = unit.rotation;
            float legLength = params.legLength();
            if(legs.length != params.legCount()) reset(unit, legLength);
            int div = Math.max(legs.length / type.groupSize(), 2);
            moveSpace = legLength / 1.6f / (div / 2f) * type.moveSpace();
            totalLength += type.continuousMove() ? params.speed() * unit.statuses.speedMultiplier : Mathf.dst(unit.deltaX, unit.deltaY);
            float trns = moveSpace * 0.85f * type.forwardScl();
            boolean moving = unit.moving();
            var target = !moving ? new Vec2() : new Vec2().trns(Angles.angle(unit.deltaX, unit.deltaY), trns);
            Vec2 moveOffset = curMoveOffset.lerpDelta(target.x, target.y, 0.1f);
            var tiles = unit.world().tiles();
            lastDeepFloor = -1;
            int deeps = 0;
            var baseOffset = new Vec2();
            var legDest = new Vec2();
            var jointDest = new Vec2();
            var relative = new Vec2();
            for(int i = 0; i < legs.length; i++){
                float dstRot = legAngle(params, i);
                legOffset(params, baseOffset, i).add(unit.x, unit.y);
                Leg l = legs[i];
                clamp(l, baseOffset, type, legLength);
                float stageF = (totalLength + i * type.pairOffset()) / moveSpace;
                int stage = (int)stageF;
                int group = stage % div;
                boolean move = i % div == group;
                boolean side = i < legs.length / 2;
                // back legs have reversed directions
                boolean backLeg = Math.abs((i + 0.5f) - legs.length / 2f) <= 0.501f;
                if(backLeg && type.flipBackLegs()) side = !side;
                if(type.flipSide()) side = !side;
                l.moving = move;
                l.stage = moving ? stageF % 1f : Mathf.lerpDelta(l.stage, 0f, 0.1f);
                int tx = Math.round(l.base.x / 8f), ty = Math.round(l.base.y / 8f);
                int tile = tiles.inBounds(tx, ty) ? tiles.pos(tx, ty) : -1;
                if(tile != -1 && tiles.isDeep(tile)){
                    deeps++;
                    lastDeepFloor = tiles.floor(tile);
                }
                if(l.group != group){
                    if(!move && (moving || !type.continuousMove()) && i % div == l.group && type.splashDamage() > 0 && !unit.statuses.disarmed){
                        UnitDamage.of(unit.world()).legSplash(unit, l.base.x, l.base.y);
                        // after the splash, a prop under the leg breaks (a no-op if legSplash already broke it)
                        if(tile != -1 && unit.world().unitTable().shapes().unitMoveBreakable(tiles.block(tile))) Crush.breakProp(unit, tile);
                    }
                    l.group = group;
                }
                legDest.trns(dstRot, legLength * type.lengthScl()).add(baseOffset).add(moveOffset);
                solve(legLength / 2f, legLength / 2f, relative.set(l.base).sub(baseOffset), side, jointDest);
                jointDest.add(baseOffset);
                if(move){
                    float moveFract = stageF % 1f;
                    l.base.lerpDelta(legDest.x, legDest.y, moveFract);
                    l.joint.lerpDelta(jointDest.x, jointDest.y, moveFract / 2f);
                }
                l.joint.lerpDelta(jointDest.x, jointDest.y, type.speed() / 4f);
                clamp(l, baseOffset, type, legLength);
            }
            // a leg on land keeps the unit from drowning
            if(deeps != legs.length || !tiles.table().isDeep(unit.floorOn())) lastDeepFloor = -1;
        }

        /** The joint and base kept between the type's minimum and maximum lengths from where the leg joins. */
        private static void clamp(Leg leg, Vec2 baseOffset, UnitParams.LegParams type, float legLength){
            leg.joint.sub(baseOffset).clampLength(type.minLength() * legLength / 2f, type.maxLength() * legLength / 2f).add(baseOffset);
            leg.base.sub(baseOffset).clampLength(type.minLength() * legLength, type.maxLength() * legLength).add(baseOffset);
        }

        /** Arc's {@code InverseKinematics.solve(lengthA, lengthB, end, side, result)}. */
        static boolean solve(float lengthA, float lengthB, Vec2 end, boolean side, Vec2 result){
            var attractor = new Vec2(end).rotate(side ? 1 : -1).setLength(lengthA + lengthB).add(end.x / 2f, end.y / 2f);
            var axis = new Vec2(end).nor();
            var normal = new Vec2(attractor).sub(new Vec2(axis).scl(attractor.dot(axis))).nor();
            float m0x = axis.x, m0y = normal.x, m1x = axis.y, m1y = normal.y;
            result.set(axis.dot(end), normal.dot(end));
            float len = result.len();
            float dist = Math.max(0, Math.min(lengthA, (len + (lengthA * lengthA - lengthB * lengthB) / len) / 2));
            float sx = dist, sy = Mathf.sqrt(lengthA * lengthA - dist * dist);
            result.set(m0x * sx + m0y * sy, m1x * sx + m1y * sy);
            return dist > 0 && dist < lengthA;
        }

        /** Vanilla's {@code resetLegs(legLength)}: a new set of legs spread around the unit, and a random step phase. */
        public void reset(Unit unit, float legLength){
            UnitParams params = unit.params();
            legs = new Leg[params.legCount()];
            if(params.lockLegBase()) baseRotation = unit.rotation;
            var offset = new Vec2();
            for(int i = 0; i < legs.length; i++){
                var leg = new Leg();
                float rotation = legAngle(params, i);
                legOffset(params, offset, i).add(unit.x, unit.y);
                leg.joint.trns(rotation, legLength / 2f).add(offset);
                leg.base.trns(rotation, legLength).add(offset);
                legs[i] = leg;
            }
            totalLength = unit.world().rand().random(100f);
        }

        /** {@code legOffset}: where leg {@code index} joins the body, relative to the unit. */
        Vec2 legOffset(UnitParams params, Vec2 out, int index){
            out.trns(defaultLegAngle(index), params.legBaseOffset());
            if(params.legStraightness() > 0){
                var straight = new Vec2().trns(defaultLegAngle(index) - baseRotation, params.legBaseOffset());
                straight.y = Mathf.sign(straight.y) * params.legBaseOffset() * params.legStraightLength();
                straight.rotate(baseRotation);
                out.lerp(straight, params.baseLegStraightness());
            }
            return out;
        }

        /** {@code legAngle}: the outward angle of leg {@code index}. */
        float legAngle(UnitParams params, int index){
            if(params.legStraightness() > 0){
                return Mathf.slerp(defaultLegAngle(index), (index >= legs.length / 2 ? -90 : 90f) + baseRotation,
                    params.legStraightness());
            }
            return defaultLegAngle(index);
        }

        float defaultLegAngle(int index){
            return baseRotation + 360f / legs.length * index + (360f / legs.length / 2f);
        }

        @Override
        public MoveShape copy(){
            var out = new Legs();
            out.legs = new Leg[legs.length];
            for(int i = 0; i < legs.length; i++) out.legs[i] = legs[i].copy();
            out.totalLength = totalLength;
            out.moveSpace = moveSpace;
            out.baseRotation = baseRotation;
            out.curMoveOffset.set(curMoveOffset);
            out.lastDeepFloor = lastDeepFloor;
            return out;
        }

        @Override
        public void hash(Hasher hasher){
            hasher.i(legs.length);
            for(Leg leg : legs) leg.hash(hasher);
            hasher.f(totalLength).f(moveSpace).f(baseRotation).f(curMoveOffset.x).f(curMoveOffset.y).i(lastDeepFloor);
        }

        @Override
        public void write(CheckpointOut out){
            out.list("legs", Arrays.asList(legs)).f("totalLength", totalLength).f("moveSpace", moveSpace)
                .f("baseRotation", baseRotation).obj("curMoveOffset", curMoveOffset).i("lastDeepFloor", lastDeepFloor);
        }

        @Override
        public void read(CheckpointIn in){
            List<Leg> stored = in.list("legs", Leg.class);
            if(stored == null || stored.contains(null)) throw new CheckpointException("a unit's legs are missing");
            legs = stored.toArray(Leg[]::new);
            totalLength = in.f("totalLength");
            moveSpace = in.f("moveSpace");
            baseRotation = in.f("baseRotation");
            curMoveOffset.set(in.obj("curMoveOffset", Vec2.class));
            lastDeepFloor = in.i("lastDeepFloor");
        }
    }

    /** Vanilla's {@code Leg}: where one leg stands and how far through its step it is. */
    public static final class Leg implements Checkpointable{
        public final Vec2 joint = new Vec2(), base = new Vec2();
        public int group;
        public boolean moving;
        public float stage;

        Leg copy(){
            var out = new Leg();
            out.joint.set(joint);
            out.base.set(base);
            out.group = group;
            out.moving = moving;
            out.stage = stage;
            return out;
        }

        void hash(Hasher hasher){
            hasher.f(joint.x).f(joint.y).f(base.x).f(base.y).i(group).bool(moving).f(stage);
        }

        @Override
        public void write(CheckpointOut out){
            out.obj("joint", joint).obj("base", base).i("group", group).bool("moving", moving).f("stage", stage);
        }

        @Override
        public void read(CheckpointIn in){
            joint.set(in.obj("joint", Vec2.class));
            base.set(in.obj("base", Vec2.class));
            group = in.i("group");
            moving = in.bool("moving");
            stage = in.f("stage");
        }
    }

    /**
     * {@code TankComp}: treads that slow over solid tiles, crush enemy buildings and props under them, and drown only
     * when every tile under them is deep. Its update is {@link TankComponent}; the tread dust and sound are client-only.
     */
    public static final class Tank extends MoveShape implements Checkpointable{
        public float treadTime, lastSlowdown = 1f;
        /** Set by a controlled move since the last update. */
        public boolean walked;
        /** The deep floor under the whole tank (content id), or -1. */
        public int lastDeepFloor = -1;

        @Override
        public Locomotion locomotion(){
            return Locomotion.TANK;
        }

        @Override
        public Solidity solidity(Unit unit){
            return elevated(unit);
        }

        /** {@code TankComp.moveAt}: {@code !vector.isZero(0.001f)} marks a step. */
        @Override
        public void beforeMoveAt(Unit unit, Vec2 vector){
            if(!(vector.len2() < 0.001f)) walked = true;
        }

        /** {@code TankComp.approach}. */
        @Override
        public void beforeApproach(Unit unit, Vec2 vector){
            if(!(vector.len2() < 0.001f)) walked = true;
        }

        /** {@code TankComp.floorSpeedMultiplier}: {@code UnitComp}'s, times the slowdown over solid tiles. */
        @Override
        public float floorSpeedMultiplier(Unit unit){
            return super.floorSpeedMultiplier(unit) * lastSlowdown;
        }

        /** {@code TankComp.drownFloor}: the deep floor under the whole tank. */
        @Override
        public int drownFloor(Unit unit){
            return unit.canDrown() ? lastDeepFloor : -1;
        }

        @Override
        public MoveShape copy(){
            var out = new Tank();
            out.treadTime = treadTime;
            out.lastSlowdown = lastSlowdown;
            out.walked = walked;
            out.lastDeepFloor = lastDeepFloor;
            return out;
        }

        @Override
        public void hash(Hasher hasher){
            hasher.f(treadTime).f(lastSlowdown).bool(walked).i(lastDeepFloor);
        }

        @Override
        public void write(CheckpointOut out){
            out.f("treadTime", treadTime).f("lastSlowdown", lastSlowdown).bool("walked", walked).i("lastDeepFloor", lastDeepFloor);
        }

        @Override
        public void read(CheckpointIn in){
            treadTime = in.f("treadTime");
            lastSlowdown = in.f("lastSlowdown");
            walked = in.bool("walked");
            lastDeepFloor = in.i("lastDeepFloor");
        }
    }

    /**
     * {@code CrawlComp}: collides like legs that can step, even when flying, slows over solid tiles, crushes enemy
     * buildings under it and drowns when most of it is over deep floor. Its update is {@link CrawlComponent}.
     */
    public static final class Crawl extends MoveShape implements Checkpointable{
        public float segmentRot, crawlTime, lastCrawlSlowdown = 1f;
        /** The deep floor under most of the crawler (content id), or -1. */
        public int lastDeepFloor = -1;

        @Override
        public Locomotion locomotion(){
            return Locomotion.CRAWL;
        }

        /** {@code CrawlComp.add}: the segments start along the body. */
        @Override
        public void added(Unit unit){
            segmentRot = unit.rotation;
        }

        @Override
        public Solidity solidity(Unit unit){
            return Solidity.LEGS;
        }

        /**
         * {@code CrawlComp.floorSpeedMultiplier}: deep floors count as 0.45, and the slowdown over solid tiles applies;
         * hovering doesn't lift it off the floor.
         */
        @Override
        public float floorSpeedMultiplier(Unit unit){
            int on = unit.isFlying() ? 0 : unit.floorOn();
            var table = unit.world().tiles().table();
            float floor = table.isDeep(on) ? 0.45f : table.speedMultiplier(on);
            return Mathf.pow(floor, unit.params().floorMultiplier()) * unit.statuses.speedMultiplier * lastCrawlSlowdown;
        }

        /** {@code CrawlComp.drownFloor}. */
        @Override
        public int drownFloor(Unit unit){
            return lastDeepFloor;
        }

        @Override
        public MoveShape copy(){
            var out = new Crawl();
            out.segmentRot = segmentRot;
            out.crawlTime = crawlTime;
            out.lastCrawlSlowdown = lastCrawlSlowdown;
            out.lastDeepFloor = lastDeepFloor;
            return out;
        }

        @Override
        public void hash(Hasher hasher){
            hasher.f(segmentRot).f(crawlTime).f(lastCrawlSlowdown).i(lastDeepFloor);
        }

        @Override
        public void write(CheckpointOut out){
            out.f("segmentRot", segmentRot).f("crawlTime", crawlTime).f("lastCrawlSlowdown", lastCrawlSlowdown)
                .i("lastDeepFloor", lastDeepFloor);
        }

        @Override
        public void read(CheckpointIn in){
            segmentRot = in.f("segmentRot");
            crawlTime = in.f("crawlTime");
            lastCrawlSlowdown = in.f("lastCrawlSlowdown");
            lastDeepFloor = in.i("lastDeepFloor");
        }
    }

    /**
     * {@code WaterMoveComp}: only its trails are state, and they are client-only. Units stand on water only, and move
     * 1.3 times faster except on floors marked {@code shallow} (sand and darksand water; vanilla's {@code shallow-water}
     * isn't). With {@code UnderwaterMoveComp} (no v160.7 class has it) they are also in the underwater physics layer
     * and can't be hit or targeted.
     */
    public static final class Naval extends MoveShape{
        @Override
        public Locomotion locomotion(){
            return Locomotion.NAVAL;
        }

        @Override
        public Solidity solidity(Unit unit){
            return unit.isFlying() ? null : Solidity.WATER;
        }

        /** {@code WaterMoveComp.onSolid}: {@code waterSolid} at the unit's tile. */
        @Override
        public boolean onSolid(Unit unit){
            return TileSolidity.of(unit.world()).solid(Solidity.WATER, unit.tileX(), unit.tileY());
        }

        /** {@code WaterMoveComp.floorSpeedMultiplier}: 1 on a shallow floor, 1.3 elsewhere (air included), times the status speed. */
        @Override
        public float floorSpeedMultiplier(Unit unit){
            int on = unit.isFlying() ? 0 : unit.floorOn();
            return (unit.world().unitTable().shapes().shallow(on) ? 1f : 1.3f) * unit.statuses.speedMultiplier;
        }

        /** {@code UnderwaterMoveComp.collisionLayer}. */
        @Override
        public int collisionLayer(Unit unit){
            return unit.params().move().underwater() ? UnitPhysics.LAYER_UNDERWATER : super.collisionLayer(unit);
        }

        /** {@code UnderwaterMoveComp}: {@code hittable()} and {@code targetable(team)} are always false. */
        @Override
        public boolean submerged(Unit unit){
            return unit.params().move().underwater();
        }

        @Override
        public MoveShape copy(){
            return new Naval();
        }

        @Override
        public void hash(Hasher hasher){
        }
    }

    /** {@code ElevationMoveComp} alone. */
    public static final class Hover extends MoveShape{
        @Override
        public Locomotion locomotion(){
            return Locomotion.HOVER;
        }

        @Override
        public Solidity solidity(Unit unit){
            return elevated(unit);
        }

        @Override
        public MoveShape copy(){
            return new Hover();
        }

        @Override
        public void hash(Hasher hasher){
        }
    }

    /** No movement component: {@code VelComp.solidity()} is null, so moves are plain additions. */
    public static final class Flying extends MoveShape{
        @Override
        public Locomotion locomotion(){
            return Locomotion.FLYING;
        }

        @Override
        public Solidity solidity(Unit unit){
            return null;
        }

        @Override
        public MoveShape copy(){
            return new Flying();
        }

        @Override
        public void hash(Hasher hasher){
        }
    }
}
