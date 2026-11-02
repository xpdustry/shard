package com.xpdustry.shard.content;

import com.xpdustry.shard.content.schema.*;

/**
 * A unit type's ability, an {@code ability} struct or one of its subkinds ({@code force-field-ability}…). Not content:
 * it has no id and lives in its unit's {@link UnitDef#abilityDefs()}.
 *
 * <p>The getters below are the fields the server reads, named as vanilla names them. Most kinds share a few
 * ({@code reload}, {@code range}, {@code amount}…); each getter says which kinds declare it, and is only valid on
 * those. References to other content ({@code unit}, {@code liquid}, {@code effect}, {@code status}, {@code bullet})
 * resolve against the epoch the ability belongs to.
 */
public interface AbilityDef{
    /** The ability's fields. */
    Struct fields();

    /** A view of {@code fields}. */
    static AbilityDef of(Struct fields){
        return () -> fields;
    }

    /** The ability's vanilla class ({@code force-field-ability}). */
    default KindKey kind(){
        return fields().kind().kindKey();
    }

    /** Whether its kind is {@code kind} or extends it. */
    default boolean isA(KindKey kind){
        return fields().kind().isA(kind.name());
    }

    /** {@code max}: the shield a {@code ForceFieldAbility} or {@code ShieldArcAbility} fills on {@code created}. */
    default float max(){
        return fields().f("max");
    }

    // shared by several kinds

    /** {@code reload}: repair, status, shield regen, energy and suppression fields. */
    default float reload(){
        return fields().f("reload");
    }

    /** {@code range}: repair, status, shield regen, energy and suppression fields. */
    default float range(){
        return fields().f("range");
    }

    /** {@code amount}: a float for repair and shield regen fields, regen and liquid explosions. */
    default float amount(){
        return fields().f("amount");
    }

    /** {@code amount} as an int: how many units a {@code SpawnDeathAbility} spawns. */
    default int count(){
        return fields().i("amount");
    }

    /** {@code radius}: force fields and shield arcs. */
    default float radius(){
        return fields().f("radius");
    }

    /** {@code regen}: force fields and shield arcs, per tick. */
    default float regen(){
        return fields().f("regen");
    }

    /** {@code cooldown}: force fields and shield arcs, after a break. */
    default float cooldown(){
        return fields().f("cooldown");
    }

    /** {@code healPercent}: repair and energy fields. */
    default float healPercent(){
        return fields().f("healPercent");
    }

    /** {@code sameTypeHealMult}: repair and energy fields. */
    default float sameTypeHealMult(){
        return fields().f("sameTypeHealMult");
    }

    /** {@code maxTargets}: repair and energy fields. */
    default int maxTargets(){
        return fields().i("maxTargets");
    }

    /** {@code x}: the offset of an energy field, suppression field, shield arc or moving lightning. */
    default float x(){
        return fields().f("x");
    }

    /** {@code y}: as {@link #x()}. */
    default float y(){
        return fields().f("y");
    }

    /** {@code damage}: energy fields and moving lightning. */
    default float damage(){
        return fields().f("damage");
    }

    /** {@code liquid}: liquid explosions and liquid regen, or null. */
    default LiquidDef liquid(ContentEpoch content){
        String ref = fields().s("liquid");
        return ref == null ? null : content.liquid(ref);
    }

    /** {@code unit}: what a {@code SpawnDeathAbility} or {@code UnitSpawnAbility} spawns, or null. */
    default UnitDef unit(ContentEpoch content){
        String ref = fields().s("unit");
        return ref == null ? null : content.unit(ref);
    }

    // ForceFieldAbility

    /** {@code sides}: the shield polygon's. */
    default int sides(){
        return fields().i("sides");
    }

    /** {@code rotation}: the shield polygon's. */
    default float rotation(){
        return fields().f("rotation");
    }

    // RepairFieldAbility

    default boolean smartHeal(){
        return fields().b("smartHeal");
    }

    default float smartHealPercent(){
        return fields().f("smartHealPercent");
    }

    default float smartHealStrength(){
        return fields().f("smartHealStrength");
    }

    default float smartDowntime(){
        return fields().f("smartDowntime");
    }

    default float smartInterval(){
        return fields().f("smartInterval");
    }

    default float randDesync(){
        return fields().f("randDesync");
    }

    // StatusFieldAbility

    /** {@code effect}: the status a {@code StatusFieldAbility} applies, or null. */
    default StatusDef effect(ContentEpoch content){
        String ref = fields().s("effect");
        return ref == null ? null : content.status(ref);
    }

    /** {@code duration}: of a {@code StatusFieldAbility}'s status. */
    default float duration(){
        return fields().f("duration");
    }

    default boolean onShoot(){
        return fields().b("onShoot");
    }

    // EnergyFieldAbility

    /** {@code status}: the status an {@code EnergyFieldAbility} applies to what it hits, or null. */
    default StatusDef status(ContentEpoch content){
        String ref = fields().s("status");
        return ref == null ? null : content.status(ref);
    }

    default float statusDuration(){
        return fields().f("statusDuration");
    }

    default boolean targetGround(){
        return fields().b("targetGround");
    }

    default boolean targetAir(){
        return fields().b("targetAir");
    }

    default boolean hitBuildings(){
        return fields().b("hitBuildings");
    }

    default boolean hitUnits(){
        return fields().b("hitUnits");
    }

    // SuppressionFieldAbility

    default float maxDelay(){
        return fields().f("maxDelay");
    }

    default boolean active(){
        return fields().b("active");
    }

    // ShieldArcAbility

    default float angle(){
        return fields().f("angle");
    }

    default float angleOffset(){
        return fields().f("angleOffset");
    }

    default boolean whenShooting(){
        return fields().b("whenShooting");
    }

    default float width(){
        return fields().f("width");
    }

    default float chanceDeflect(){
        return fields().f("chanceDeflect");
    }

    default float reflectBuildingDamage(){
        return fields().f("reflectBuildingDamage");
    }

    default float reflectVel(){
        return fields().f("reflectVel");
    }

    default float reflectTime(){
        return fields().f("reflectTime");
    }

    default float missileUnitMultiplier(){
        return fields().f("missileUnitMultiplier");
    }

    default boolean pushUnits(){
        return fields().b("pushUnits");
    }

    default boolean pushDiffLayer(){
        return fields().b("pushDiffLayer");
    }

    // MoveLightningAbility

    /** {@code chance}: per tick at full speed. */
    default float chance(){
        return fields().f("chance");
    }

    /** {@code length}: of the lightning, none at 0 or below. */
    default int length(){
        return fields().i("length");
    }

    default float minSpeed(){
        return fields().f("minSpeed");
    }

    default float maxSpeed(){
        return fields().f("maxSpeed");
    }

    default boolean alternate(){
        return fields().b("alternate");
    }

    /** {@code bullet}: what a {@code MoveLightningAbility} also fires, or null. */
    default BulletDef bullet(ContentEpoch content){
        String ref = fields().s("bullet");
        return ref == null ? null : content.bullet(ref);
    }

    default float bulletAngle(){
        return fields().f("bulletAngle");
    }

    default float bulletSpread(){
        return fields().f("bulletSpread");
    }

    // UnitSpawnAbility

    default float spawnTime(){
        return fields().f("spawnTime");
    }

    default float spawnX(){
        return fields().f("spawnX");
    }

    default float spawnY(){
        return fields().f("spawnY");
    }

    // SpawnDeathAbility

    default int randAmount(){
        return fields().i("randAmount");
    }

    default float spread(){
        return fields().f("spread");
    }

    default boolean faceOutwards(){
        return fields().b("faceOutwards");
    }

    // LiquidRegenAbility

    default float slurpSpeed(){
        return fields().f("slurpSpeed");
    }

    default float regenPerSlurp(){
        return fields().f("regenPerSlurp");
    }

    default float slurpEffectChance(){
        return fields().f("slurpEffectChance");
    }

    // LiquidExplodeAbility

    default float radAmountScale(){
        return fields().f("radAmountScale");
    }

    default float radScale(){
        return fields().f("radScale");
    }

    default float noiseMag(){
        return fields().f("noiseMag");
    }

    default float noiseScl(){
        return fields().f("noiseScl");
    }

    // RegenAbility

    /** {@code percentAmount}: of the maximum health, per tick. */
    default float percentAmount(){
        return fields().f("percentAmount");
    }

    // ArmorPlateAbility

    default float healthMultiplier(){
        return fields().f("healthMultiplier");
    }
}
