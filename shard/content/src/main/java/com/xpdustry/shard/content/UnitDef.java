package com.xpdustry.shard.content;

import com.xpdustry.shard.content.schema.*;

import java.util.*;

/**
 * The typed, immutable definition of a unit type, as pinned by a {@link ContentEpoch}. The abstract getters are what
 * the unit core reads; the defaults read {@link #fields()}.
 */
public interface UnitDef extends ContentDef{
    /** The wire id, which is the client's content id. */
    @Override
    int id();

    /** The content name ({@code dagger}, {@code flare}…). */
    @Override
    String name();

    @Override
    default ContentType type(){
        return ContentType.UNIT;
    }

    /** Vanilla's baked {@code health}. */
    float health();

    /** The entity class ({@code MechUnit}), which decides the wire class id and the movement shape. */
    default String entity(){
        return fields().s("constructor");
    }

    default float speed(){
        return fields().f("speed");
    }

    default float hitSize(){
        return fields().f("hitSize");
    }

    default float armor(){
        return fields().f("armor");
    }

    default boolean flying(){
        return fields().b("flying");
    }

    /**
     * Vanilla's {@code internal}: block units and the like, which mlog doesn't name and spawn groups don't use. False
     * for hand-built definitions.
     */
    default boolean internal(){
        return baked() && fields().b("internal");
    }

    default float rotateSpeed(){
        return fields().f("rotateSpeed");
    }

    default int itemCapacity(){
        return fields().i("itemCapacity");
    }

    /** The baked attack range ({@code UnitType.init}: the longest weapon range unless set). */
    default float range(){
        return fields().f("range");
    }

    /** The weapons after mirroring, as {@code UnitType.init} leaves them. */
    default List<WeaponDef> weapons(){
        throw new UnsupportedOperationException("unit " + name() + " has no baked weapons");
    }

    /** The {@link #entity()} class: its wire class id and the components it has ({@code mech}, {@code payload}…). */
    default Schema.Entity entityClass(){
        Schema.Entity entity = fields().kind().schema().entity(entity());
        if(entity == null) throw new IllegalStateException("unit " + name() + " has an unknown entity class " + entity());
        return entity;
    }

    /** The abilities, one struct per ability in vanilla's order. */
    default List<Struct> abilities(){
        return fields().structs("abilities");
    }

    /** {@link #abilities()}, typed. */
    default List<AbilityDef> abilityDefs(){
        return abilities().stream().map(AbilityDef::of).toList();
    }

    /** The {@link AbilityDef#kind()} of each ability, in vanilla's order. */
    default List<KindKey> abilityKinds(){
        return abilities().stream().map(a -> a.kind().kindKey()).toList();
    }

    /**
     * {@code controller}: what controls a new unit, a controller kind ({@code no-ai}, {@code missile-ai},
     * {@code builder-ai}…) or a {@code hook:} ({@code hook:default-controller} picks {@link #aiController()} or a
     * command AI).
     */
    default String controller(){
        return fields().s("controller");
    }

    /** {@code aiController}: the AI of units no player commands, a controller kind or a {@code hook:}. */
    default String aiController(){
        return fields().s("aiController");
    }

    default float drag(){
        return fields().f("drag");
    }

    // control

    /** {@code playerControllable}: players may control it; else {@code hook:default-controller} always picks an AI. */
    default boolean playerControllable(){
        return fields().b("playerControllable");
    }

    /** {@code commands}: the unit command names it accepts, in vanilla's order. */
    default List<String> commands(){
        List<String> out = new ArrayList<>();
        for(Object command : fields().list("commands")) out.add(String.valueOf(command));
        return out;
    }

    /** {@code stances}: the unit stance names it may take outside the {@code mine} command, in vanilla's order. */
    default List<String> stances(){
        List<String> out = new ArrayList<>();
        for(Object stance : fields().list("stances")) out.add(String.valueOf(stance));
        return out;
    }

    /** {@code defaultCommand}: the command a new {@code CommandAI} starts with, or null for the first of {@link #commands()}. */
    default String defaultCommand(){
        return fields().s("defaultCommand");
    }

    // movement

    default float accel(){
        return fields().f("accel");
    }

    /** {@code strafePenalty}: how much slower it moves sideways to where it faces. */
    default float strafePenalty(){
        return fields().f("strafePenalty");
    }

    /** {@code boostMultiplier}: the speed factor while boosting. */
    default float boostMultiplier(){
        return fields().f("boostMultiplier");
    }

    /** {@code floorMultiplier}: how much the floor's speed multiplier applies. */
    default float floorMultiplier(){
        return fields().f("floorMultiplier");
    }

    /** {@code riseSpeed}: how fast a boosting mech gains elevation. */
    default float riseSpeed(){
        return fields().f("riseSpeed");
    }

    default float descentSpeed(){
        return fields().f("descentSpeed");
    }

    /** {@code bounded}: kept inside the world's bounds. */
    default boolean bounded(){
        return fields().b("bounded");
    }

    /** {@code omniMovement}: moves in any direction, not only where it faces. */
    default boolean omniMovement(){
        return fields().b("omniMovement");
    }

    default float engineSize(){
        return fields().f("engineSize");
    }

    /** {@code emitWalkEffect}: walking makes the floor's walk effect, whose draws matter. */
    default boolean emitWalkEffect(){
        return fields().b("emitWalkEffect");
    }

    default boolean emitWalkSound(){
        return fields().b("emitWalkSound");
    }

    /** {@code envEnabled}: the {@code Env} flags it may live in. */
    default int envEnabled(){
        return fields().i("envEnabled");
    }

    default int envDisabled(){
        return fields().i("envDisabled");
    }

    default int envRequired(){
        return fields().i("envRequired");
    }

    /** {@code physics}: takes part in unit pushing. */
    default boolean physics(){
        return fields().b("physics");
    }

    /** {@code naval}: moves on deep water only. */
    default boolean naval(){
        return fields().b("naval");
    }

    /** {@code allowLegStep}: legs step over walls that aren't leg-solid. */
    default boolean allowLegStep(){
        return fields().b("allowLegStep");
    }

    /** {@code legPhysicsLayer}: legged units push each other in a layer of their own. */
    default boolean legPhysicsLayer(){
        return fields().b("legPhysicsLayer");
    }

    default boolean hovering(){
        return fields().b("hovering");
    }

    /** {@code isEnemy}: counts toward {@code state.enemies} on the wave team (supports and builders don't). */
    default boolean isEnemy(){
        return fields().b("isEnemy");
    }

    /** {@code immunities}: the names of the statuses this type ignores. */
    default List<String> immunities(){
        List<String> out = new ArrayList<>();
        for(Object status : fields().list("immunities")) out.add(String.valueOf(status));
        return out;
    }

    default boolean canBoost(){
        return fields().b("canBoost");
    }

    /** {@code targetPriority}: targeting prefers higher, whatever the distance. */
    default float targetPriority(){
        return fields().f("targetPriority");
    }

    default boolean targetable(){
        return fields().b("targetable");
    }

    /** {@code vulnerableWithPayloads}: an untargetable type is targetable while it carries a payload. */
    default boolean vulnerableWithPayloads(){
        return fields().b("vulnerableWithPayloads");
    }

    /** {@code useUnitCap}: counts against the team's unit cap. */
    default boolean useUnitCap(){
        return fields().b("useUnitCap");
    }

    /** {@code lifetime}: how long a timed-kill unit (a missile) lives, in ticks. */
    default float lifetime(){
        return fields().f("lifetime");
    }

    // mining, building, carrying

    /** {@code mineTier}: the hardest ore it mines, or -1 for none. */
    default int mineTier(){
        return fields().i("mineTier");
    }

    default float mineSpeed(){
        return fields().f("mineSpeed");
    }

    default float mineRange(){
        return fields().f("mineRange");
    }

    /** {@code mineFloor}: it mines ores on floors. */
    default boolean mineFloor(){
        return fields().b("mineFloor");
    }

    /** {@code mineWalls}: it mines ores on walls. */
    default boolean mineWalls(){
        return fields().b("mineWalls");
    }

    /** {@code mineHardnessScaling}: harder ores take longer to mine (15 frames per hardness, else a flat 15). */
    default boolean mineHardnessScaling(){
        return fields().b("mineHardnessScaling");
    }

    /** {@code boostWhenMining}: a ground miner AI boosts while it mines. */
    default boolean boostWhenMining(){
        return fields().b("boostWhenMining");
    }

    /** {@code boostWhenBuilding}: a ground builder AI boosts while it builds. */
    default boolean boostWhenBuilding(){
        return fields().b("boostWhenBuilding");
    }

    /** {@code canAttack}: whether a builder AI may shoot ({@code BuilderAI.shouldShoot}). */
    default boolean canAttack(){
        return fields().b("canAttack");
    }

    /** {@code mineItems}: the item names it may mine, in vanilla's order. */
    default List<String> mineItems(){
        List<String> out = new ArrayList<>();
        for(Object item : fields().list("mineItems")) out.add((String)item);
        return out;
    }

    /** {@code buildSpeed}: 0 for units that can't build. */
    default float buildSpeed(){
        return fields().f("buildSpeed");
    }

    default float buildRange(){
        return fields().f("buildRange");
    }

    /** {@code payloadCapacity}: the payload area it carries, in world units squared. */
    default float payloadCapacity(){
        return fields().f("payloadCapacity");
    }

    /** {@code allowedInPayloads}: other units and payload blocks may carry it. */
    default boolean allowedInPayloads(){
        return fields().b("allowedInPayloads");
    }

    /** {@code pickupUnits}: a payload unit of this type picks up units, not only buildings. */
    default boolean pickupUnits(){
        return fields().b("pickupUnits");
    }

    /** {@code ammoCapacity}, used when the rules enable unit ammo. */
    default int ammoCapacity(){
        return fields().i("ammoCapacity");
    }

    // damage and death

    /** {@code canDrown}: it drowns on deep liquid. */
    default boolean canDrown(){
        return fields().b("canDrown");
    }

    default float drownTimeMultiplier(){
        return fields().f("drownTimeMultiplier");
    }

    /** {@code canHeal}: its bullets heal friendly buildings. */
    default boolean canHeal(){
        return fields().b("canHeal");
    }

    /** {@code crashDamageMultiplier}: scales the damage a falling flyer deals. */
    default float crashDamageMultiplier(){
        return fields().f("crashDamageMultiplier");
    }

    default float fallSpeed(){
        return fields().f("fallSpeed");
    }

    /** {@code killable}: damage can kill it. */
    default boolean killable(){
        return fields().b("killable");
    }

    /** {@code hittable}: bullets hit it. */
    default boolean hittable(){
        return fields().b("hittable");
    }

    /** {@code createWreck}: its death leaves a wreck. */
    default boolean createWreck(){
        return fields().b("createWreck");
    }

    default float wreckHealthMultiplier(){
        return fields().f("wreckHealthMultiplier");
    }

    /** {@code logicControllable}: processors may bind and control it. */
    default boolean logicControllable(){
        return fields().b("logicControllable");
    }

    /**
     * {@code aimDst}: the shortest aim a player's pointer sets (WeaponsComp.java:62), baked as {@code UnitType.init}
     * does: twice the hit size with a fixed weapon, else half.
     */
    default float aimDst(){
        return fields().f("aimDst");
    }

    /** {@code coreUnitDock}: a player leaving a unit for its core gets this type back in place ({@code unitClear}). */
    default boolean coreUnitDock(){
        return fields().b("coreUnitDock");
    }

    /** {@code targetAir}: its weapons can hit flying units ({@code canTarget}). */
    default boolean targetAir(){
        return fields().b("targetAir");
    }

    /** {@code targetGround}: its weapons can hit ground units and buildings ({@code canTarget}). */
    default boolean targetGround(){
        return fields().b("targetGround");
    }

    // movement shapes

    /** {@code baseRotateSpeed}: how fast a mech's legs turn toward where it walks. */
    default float baseRotateSpeed(){
        return fields().f("baseRotateSpeed");
    }

    /** {@code mechStride}: the length of a mech's step. */
    default float mechStride(){
        return fields().f("mechStride");
    }

    /** {@code crawlSlowdown}: the speed multiplier of a tank or crawler over solid tiles. */
    default float crawlSlowdown(){
        return fields().f("crawlSlowdown");
    }

    /** {@code crawlSlowdownFrac}: the fraction of solid tiles under a tank or crawler at which it is slowest. */
    default float crawlSlowdownFrac(){
        return fields().f("crawlSlowdownFrac");
    }

    /** {@code crushDamage}: what a tank or crawler deals per tick to enemy buildings under it. */
    default float crushDamage(){
        return fields().f("crushDamage");
    }

    /** {@code crushFragile}: a tank destroys enemy {@code crushFragile} buildings around it. */
    default boolean crushFragile(){
        return fields().b("crushFragile");
    }

    /** {@code segmentRotSpeed}: how fast a crawler's segments follow its rotation. */
    default float segmentRotSpeed(){
        return fields().f("segmentRotSpeed");
    }

    /** {@code segmentMaxRot}: how far a crawler's segments may lag its rotation. */
    default float segmentMaxRot(){
        return fields().f("segmentMaxRot");
    }

    // legged units

    default int legCount(){
        return fields().i("legCount");
    }

    default float legLength(){
        return fields().f("legLength");
    }

    default float legBaseOffset(){
        return fields().f("legBaseOffset");
    }

    /** {@code lockLegBase}: the legs turn with the body. */
    default boolean lockLegBase(){
        return fields().b("lockLegBase");
    }

    default float legStraightness(){
        return fields().f("legStraightness");
    }

    default float legStraightLength(){
        return fields().f("legStraightLength");
    }

    default float baseLegStraightness(){
        return fields().f("baseLegStraightness");
    }

    default float legSpeed(){
        return fields().f("legSpeed");
    }

    /** {@code legGroupSize}: how many legs move at once. */
    default int legGroupSize(){
        return fields().i("legGroupSize");
    }

    default float legMoveSpace(){
        return fields().f("legMoveSpace");
    }

    default boolean legContinuousMove(){
        return fields().b("legContinuousMove");
    }

    default float legForwardScl(){
        return fields().f("legForwardScl");
    }

    default float legMinLength(){
        return fields().f("legMinLength");
    }

    default float legMaxLength(){
        return fields().f("legMaxLength");
    }

    default float legPairOffset(){
        return fields().f("legPairOffset");
    }

    default boolean flipBackLegs(){
        return fields().b("flipBackLegs");
    }

    default boolean flipLegSide(){
        return fields().b("flipLegSide");
    }

    default float legLengthScl(){
        return fields().f("legLengthScl");
    }

    /** {@code legSplashDamage}: what a leg deals where it lands. */
    default float legSplashDamage(){
        return fields().f("legSplashDamage");
    }

    default float legSplashRange(){
        return fields().f("legSplashRange");
    }

    /** {@code targetUnderBlocks}: whether its autonomous weapons target blocks that bullets pass over. */
    default boolean targetUnderBlocks(){
        return fields().b("targetUnderBlocks");
    }

    /** {@code knockbackMultiplier}: how much bullet knockback moves it; 0 for none. */
    default float knockbackMultiplier(){
        return fields().f("knockbackMultiplier");
    }

    /** {@code missileAccelTime}: a missile type launched at rest accelerates for this long; 0 starts at top speed. */
    default float missileAccelTime(){
        return fields().f("missileAccelTime");
    }

    // AI (WP54)

    /** {@code maxRange}: what {@code unit.range()} returns, baked by {@code UnitType.init} from the weapons. */
    default float maxRange(){
        return fields().f("maxRange");
    }

    /** {@code circleTarget}: the AI flies circles through its target instead of hovering in range. */
    default boolean circleTarget(){
        return fields().b("circleTarget");
    }

    /** {@code circleTargetRadius}: how far past its target a circling unit flies before turning back. */
    default float circleTargetRadius(){
        return fields().f("circleTargetRadius");
    }

    /**
     * {@code targetFlags}: the building flags a flying AI looks for, in order, by vanilla's {@code BlockFlag} names; a
     * null entry means "the closest target".
     */
    default List<String> targetFlags(){
        List<String> out = new ArrayList<>();
        for(Object flag : fields().list("targetFlags")) out.add(flag == null ? null : String.valueOf(flag));
        return out;
    }

    /** {@code faceTarget}: a ground AI turns to aim at its target rather than where it walks. */
    default boolean faceTarget(){
        return fields().b("faceTarget");
    }

    /** {@code singleTarget}: every mount takes the unit's target instead of finding its own. */
    default boolean singleTarget(){
        return fields().b("singleTarget");
    }

    /** {@code autoDropBombs}: a bomber drops on whatever ground target is under it. */
    default boolean autoDropBombs(){
        return fields().b("autoDropBombs");
    }

    /** {@code homingDelay}: frames before a missile unit steers toward its shooter's aim. */
    default float homingDelay(){
        return fields().f("homingDelay");
    }

    /** {@code rotateMoveFirst}: a non-omni unit turns toward where it moves before it moves. */
    default boolean rotateMoveFirst(){
        return fields().b("rotateMoveFirst");
    }

    /** {@code wobble}: a flying AI unit drifts on a slow sine. */
    default boolean wobble(){
        return fields().b("wobble");
    }

    /** {@code rotateToBuilding}: a unit building a plan faces it. */
    default boolean rotateToBuilding(){
        return fields().b("rotateToBuilding");
    }

    /** {@code autoFindTarget}: a commanded unit picks targets on its own. */
    default boolean autoFindTarget(){
        return fields().b("autoFindTarget");
    }
}
