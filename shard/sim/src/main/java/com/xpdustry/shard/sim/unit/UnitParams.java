package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.schema.*;

import java.util.*;

/**
 * The unit-type parameters the unit core and the spatial index read beyond the base {@link UnitDef} getters: fields of
 * vanilla's {@code UnitType} and what its entity class implies, read once from the definition's baked fields. A
 * hand-built definition (test content) has none and gets {@link #DEFAULTS}, a fresh {@code UnitType}'s values.
 * Immutable; the world keeps one per unit type ({@link UnitTable}), and each unit points at its type's.
 */
public final class UnitParams{
    /** A fresh {@code UnitType}'s values, with a flying entity class and no weapons or abilities. */
    public static final UnitParams DEFAULTS = new UnitParams();

    /** The movement shape the entity class has, in the order vanilla's {@code instanceof} checks would find it. */
    private static final List<String> SHAPES = List.of("mech", "legs", "tank", "crawl", "water-move", "elevation-move");
    private static final List<Locomotion> LOCOMOTIONS = List.of(Locomotion.MECH, Locomotion.LEGS, Locomotion.TANK, Locomotion.CRAWL,
        Locomotion.NAVAL, Locomotion.HOVER);

    private final Locomotion locomotion;
    private final boolean payloads, tethered, timedKill;
    private final float hitSize, drag, armor;
    private final boolean flying, physics, allowLegStep, legPhysicsLayer, hovering, canBoost;
    private final float targetPriority;
    private final boolean targetable, vulnerableWithPayloads, useUnitCap;
    private final float lifetime;
    private final float[] weaponRotations;
    private final int abilities;
    private final int legCount;
    private final float legLength, legBaseOffset;
    private final boolean lockLegBase;
    private final float legStraightness, legStraightLength, baseLegStraightness;
    private final String controller, aiController, defaultCommand;
    private final List<String> commands, stances;
    private final boolean playerControllable;
    private final float health, speed, accel, rotateSpeed, strafePenalty, boostMultiplier, floorMultiplier;
    private final float riseSpeed, descentSpeed, drownTimeMultiplier, fallSpeed, wreckHealthMultiplier, engineSize;
    private final boolean bounded, omniMovement, canDrown, killable, hittable, createWreck, emitWalkEffect, emitWalkSound;
    private final int itemCapacity, envEnabled, envDisabled, envRequired;
    private final boolean dummy;
    private final LegParams legs;
    private final MoveParams move;
    /** The registered components the entity class has, in merge order; set last, since they read the rest. */
    private final UnitComponent[] components;

    private UnitParams(){
        locomotion = Locomotion.FLYING;
        payloads = tethered = timedKill = false;
        hitSize = 6f;
        drag = 0.3f;
        armor = 0f;
        flying = allowLegStep = hovering = canBoost = vulnerableWithPayloads = false;
        physics = legPhysicsLayer = targetable = useUnitCap = true;
        targetPriority = 0f;
        lifetime = 60f * 5f;
        weaponRotations = new float[0];
        abilities = 0;
        legCount = 4;
        legLength = 10f;
        legBaseOffset = legStraightness = baseLegStraightness = 0f;
        lockLegBase = false;
        legStraightLength = 1f;
        controller = DEFAULT_CONTROLLER;
        aiController = DEFAULT_AI;
        defaultCommand = null;
        commands = List.of("move");
        stances = List.of();
        playerControllable = true;
        health = 200f;
        speed = 1.1f;
        accel = 0.5f;
        rotateSpeed = 5f;
        strafePenalty = 0.5f;
        boostMultiplier = floorMultiplier = drownTimeMultiplier = 1f;
        riseSpeed = descentSpeed = 0.08f;
        fallSpeed = 0.018f;
        wreckHealthMultiplier = 0.25f;
        engineSize = 2.5f;
        bounded = omniMovement = canDrown = killable = hittable = createWreck = emitWalkEffect = emitWalkSound = true;
        itemCapacity = 10;
        envEnabled = 1;
        envDisabled = envRequired = 0;
        dummy = false;
        legs = LegParams.DEFAULTS;
        move = MoveParams.DEFAULTS;
        components = UnitComponent.merged(this);
    }

    private UnitParams(UnitDef type){
        Schema.Entity entity = type.entityClass();
        Locomotion shape = Locomotion.FLYING;
        for(int i = SHAPES.size() - 1; i >= 0; i--){
            if(entity.has(SHAPES.get(i))) shape = LOCOMOTIONS.get(i);
        }
        locomotion = shape;
        payloads = entity.has("payload");
        tethered = entity.has("building-tether");
        timedKill = entity.has("timed-kill");
        hitSize = type.hitSize();
        drag = type.drag();
        armor = type.armor();
        flying = type.flying();
        physics = type.physics();
        allowLegStep = type.allowLegStep();
        legPhysicsLayer = type.legPhysicsLayer();
        hovering = type.hovering();
        canBoost = type.canBoost();
        targetPriority = type.targetPriority();
        targetable = type.targetable();
        vulnerableWithPayloads = type.vulnerableWithPayloads();
        useUnitCap = type.useUnitCap();
        lifetime = type.lifetime();
        List<WeaponDef> weapons = type.weapons();
        weaponRotations = new float[weapons.size()];
        for(int i = 0; i < weaponRotations.length; i++) weaponRotations[i] = weapons.get(i).baseRotation();
        abilities = type.abilities().size();
        legCount = type.legCount();
        legLength = type.legLength();
        legBaseOffset = type.legBaseOffset();
        lockLegBase = type.lockLegBase();
        legStraightness = type.legStraightness();
        legStraightLength = type.legStraightLength();
        baseLegStraightness = type.baseLegStraightness();
        controller = type.controller();
        aiController = type.aiController();
        defaultCommand = type.defaultCommand();
        commands = List.copyOf(type.commands());
        stances = List.copyOf(type.stances());
        playerControllable = type.playerControllable();
        health = type.health();
        speed = type.speed();
        accel = type.accel();
        rotateSpeed = type.rotateSpeed();
        strafePenalty = type.strafePenalty();
        boostMultiplier = type.boostMultiplier();
        floorMultiplier = type.floorMultiplier();
        riseSpeed = type.riseSpeed();
        descentSpeed = type.descentSpeed();
        drownTimeMultiplier = type.drownTimeMultiplier();
        fallSpeed = type.fallSpeed();
        wreckHealthMultiplier = type.wreckHealthMultiplier();
        engineSize = type.engineSize();
        bounded = type.bounded();
        omniMovement = type.omniMovement();
        canDrown = type.canDrown();
        killable = type.killable();
        hittable = type.hittable();
        createWreck = type.createWreck();
        emitWalkEffect = type.emitWalkEffect();
        emitWalkSound = type.emitWalkSound();
        itemCapacity = type.itemCapacity();
        envEnabled = type.envEnabled();
        envDisabled = type.envDisabled();
        envRequired = type.envRequired();
        dummy = entity.name().equals("TargetDummyUnit");
        legs = new LegParams(type.legSpeed(), type.legGroupSize(), type.legMoveSpace(), type.legContinuousMove(),
            type.legForwardScl(), type.legMinLength(), type.legMaxLength(), type.legPairOffset(), type.flipBackLegs(),
            type.flipLegSide(), type.legLengthScl(), type.legSplashDamage(), type.legSplashRange());
        move = new MoveParams(type.baseRotateSpeed(), type.mechStride(), type.crawlSlowdown(), type.crawlSlowdownFrac(),
            type.crushDamage(), type.crushFragile(), type.segmentRotSpeed(), type.segmentMaxRot(), entity.has("underwater-move"));
        components = UnitComponent.merged(this);
    }

    /** The baked {@code controller} of a type that keeps vanilla's default ({@code UnitType.controller}). */
    public static final String DEFAULT_CONTROLLER = "hook:default-controller";
    /** The baked {@code aiController} of a type that keeps vanilla's default ({@code UnitType.aiController}). */
    public static final String DEFAULT_AI = "hook:default-ai";
    /**
     * The baked {@code controller} of the core units ({@code alpha}, {@code beta}, {@code gamma}):
     * {@code u -> u.team.isAI() ? new BuilderAI(true, 400f) : new CommandAI()}.
     */
    public static final String CORE_UNIT_CONTROLLER = "hook:core-unit-controller";

    /** The parameters of {@code type}, read from its fields, or {@link #DEFAULTS} if it has none. */
    public static UnitParams of(UnitDef type){
        return type.baked() ? new UnitParams(type) : DEFAULTS;
    }

    /** Which movement shape the type's entity class has. */
    public Locomotion locomotion(){
        return locomotion;
    }

    /** Whether the entity class has {@code PayloadComp}. */
    public boolean payloads(){
        return payloads;
    }

    /** Whether the entity class has {@code BuildingTetherComp}. */
    public boolean tethered(){
        return tethered;
    }

    /** Whether the entity class has {@code TimedKillComp} (missiles). */
    public boolean timedKill(){
        return timedKill;
    }

    public float hitSize(){
        return hitSize;
    }

    public float drag(){
        return drag;
    }

    public float armor(){
        return armor;
    }

    /** {@code flying}: spawns at elevation 1. */
    public boolean flying(){
        return flying;
    }

    /** {@code physics}: takes part in unit pushing. */
    public boolean physics(){
        return physics;
    }

    public boolean allowLegStep(){
        return allowLegStep;
    }

    public boolean legPhysicsLayer(){
        return legPhysicsLayer;
    }

    public boolean hovering(){
        return hovering;
    }

    public boolean canBoost(){
        return canBoost;
    }

    public float targetPriority(){
        return targetPriority;
    }

    public boolean targetable(){
        return targetable;
    }

    public boolean vulnerableWithPayloads(){
        return vulnerableWithPayloads;
    }

    public boolean useUnitCap(){
        return useUnitCap;
    }

    /** {@code lifetime}, for timed-kill units. */
    public float lifetime(){
        return lifetime;
    }

    /** How many weapons ({@code weapons.size}, mirrors included), so how many mounts. */
    public int weaponCount(){
        return weaponRotations.length;
    }

    /** {@code weapons.get(index).baseRotation}, a new mount's rotation. */
    public float weaponBaseRotation(int index){
        return weaponRotations[index];
    }

    /** How many abilities ({@code abilities.size}). */
    public int abilities(){
        return abilities;
    }

    /** {@code legCount}, for legged units. */
    public int legCount(){
        return legCount;
    }

    public float legLength(){
        return legLength;
    }

    public float legBaseOffset(){
        return legBaseOffset;
    }

    /** {@code lockLegBase}: the legs turn with the body. */
    public boolean lockLegBase(){
        return lockLegBase;
    }

    public float legStraightness(){
        return legStraightness;
    }

    public float legStraightLength(){
        return legStraightLength;
    }

    public float baseLegStraightness(){
        return baseLegStraightness;
    }

    /**
     * The baked {@code controller}: {@link #DEFAULT_CONTROLLER}, an AI's kind name ({@code builder-ai},
     * {@code no-ai}, {@code missile-ai}…) or a hook naming one of vanilla's lambdas. {@link Controllers} reads it.
     */
    public String controller(){
        return controller;
    }

    /** The baked {@code aiController}: {@link #DEFAULT_AI} or an AI's kind name. */
    public String aiController(){
        return aiController;
    }

    /** The baked {@code defaultCommand}'s name ({@code UnitType.init} sets it to the first command), or null. */
    public String defaultCommand(){
        return defaultCommand;
    }

    /** The baked {@code commands}' names, in order. */
    public List<String> commands(){
        return commands;
    }

    /** The baked {@code stances}' names, in order ({@code UnitType.init} fills them from the type's weapons). */
    public List<String> stances(){
        return stances;
    }

    public boolean playerControllable(){
        return playerControllable;
    }

    public float health(){
        return health;
    }

    public float speed(){
        return speed;
    }

    public float accel(){
        return accel;
    }

    public float rotateSpeed(){
        return rotateSpeed;
    }

    public float strafePenalty(){
        return strafePenalty;
    }

    public float boostMultiplier(){
        return boostMultiplier;
    }

    public float floorMultiplier(){
        return floorMultiplier;
    }

    public float riseSpeed(){
        return riseSpeed;
    }

    public float descentSpeed(){
        return descentSpeed;
    }

    public float drownTimeMultiplier(){
        return drownTimeMultiplier;
    }

    public float fallSpeed(){
        return fallSpeed;
    }

    public float wreckHealthMultiplier(){
        return wreckHealthMultiplier;
    }

    public float engineSize(){
        return engineSize;
    }

    /** {@code bounded}: kept inside the map. */
    public boolean bounded(){
        return bounded;
    }

    public boolean omniMovement(){
        return omniMovement;
    }

    public boolean canDrown(){
        return canDrown;
    }

    public boolean killable(){
        return killable;
    }

    public boolean hittable(){
        return hittable;
    }

    public boolean createWreck(){
        return createWreck;
    }

    /** {@code emitWalkEffect}: a grounded unit counts its steps, for splashes. */
    public boolean emitWalkEffect(){
        return emitWalkEffect;
    }

    /** {@code emitWalkSound}: each splash draws a sound pitch from the random, on a server too. */
    public boolean emitWalkSound(){
        return emitWalkSound;
    }

    public int itemCapacity(){
        return itemCapacity;
    }

    /** Vanilla's {@code supportsEnv(env)}. */
    public boolean supportsEnv(int env){
        return (envEnabled & env) != 0 && (envDisabled & env) == 0 && (envRequired == 0 || (envRequired & env) == envRequired);
    }

    /** Whether the entity class is {@code TargetDummyUnit} ({@code TargetDummyComp}). */
    public boolean dummy(){
        return dummy;
    }

    /** The {@code LegsComp} step parameters. */
    public LegParams legs(){
        return legs;
    }

    /** The {@code UnitType} fields the mech, tank, crawl and naval shapes move by. */
    public MoveParams move(){
        return move;
    }

    /** The merged {@code update()} of the entity class: its registered {@link UnitComponent}s, in order. */
    public UnitComponent[] components(){
        return components;
    }

    /**
     * The {@code UnitType} fields of the other movement components: {@code MechComp}'s {@code baseRotateSpeed} and
     * {@code mechStride}, {@code TankComp}'s and {@code CrawlComp}'s slowdown over solid tiles and crush damage,
     * {@code CrawlComp}'s segment rotation, and whether the class has {@code UnderwaterMoveComp}.
     */
    public record MoveParams(float baseRotateSpeed, float mechStride, float crawlSlowdown, float crawlSlowdownFrac,
                             float crushDamage, boolean crushFragile, float segmentRotSpeed, float segmentMaxRot,
                             boolean underwater){
        // a fresh UnitType's, with mechStride as UnitType.init bakes it for the default hit size
        static final MoveParams DEFAULTS = new MoveParams(5f, 4f + (6f - 8f) / 2.1f, 0.5f, 0.55f, 0f, false, 1f, 30f, false);
    }

    /** The {@code UnitType} fields {@code LegsComp.update} steps by. */
    public record LegParams(float speed, int groupSize, float moveSpace, boolean continuousMove, float forwardScl,
                            float minLength, float maxLength, float pairOffset, boolean flipBackLegs, boolean flipSide,
                            float lengthScl, float splashDamage, float splashRange){
        static final LegParams DEFAULTS = new LegParams(0.1f, 2, 1f, false, 1f, 0f, 1.75f, 0f, true, false, 1f, 0f, 5f);
    }
}
