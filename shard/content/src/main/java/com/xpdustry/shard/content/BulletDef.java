package com.xpdustry.shard.content;

/**
 * The typed, immutable definition of a bullet type. Vanilla's bullets are anonymous objects; the pack hoists each into
 * a content object named by where vanilla declares it ({@code duo/ammoTypes.copper}), with ids in vanilla's order.
 */
public interface BulletDef extends ContentDef{
    @Override
    default ContentType type(){
        return ContentType.BULLET;
    }

    default float damage(){
        return fields().f("damage");
    }

    default float speed(){
        return fields().f("speed");
    }

    default float lifetime(){
        return fields().f("lifetime");
    }

    default float drag(){
        return fields().f("drag");
    }

    default float hitSize(){
        return fields().f("hitSize");
    }

    default float splashDamage(){
        return fields().f("splashDamage");
    }

    default float splashDamageRadius(){
        return fields().f("splashDamageRadius");
    }

    default float knockback(){
        return fields().f("knockback");
    }

    default boolean pierce(){
        return fields().b("pierce");
    }

    /** How many targets it pierces, or -1 for no limit. */
    default int pierceCap(){
        return fields().i("pierceCap");
    }

    default boolean collidesAir(){
        return fields().b("collidesAir");
    }

    default boolean collidesGround(){
        return fields().b("collidesGround");
    }

    default boolean collidesTiles(){
        return fields().b("collidesTiles");
    }

    /** The baked reach ({@code BulletType.calculateRange}). */
    default float range(){
        return fields().f("range");
    }

    default float ammoMultiplier(){
        return fields().f("ammoMultiplier");
    }

    default float reloadMultiplier(){
        return fields().f("reloadMultiplier");
    }

    default float buildingDamageMultiplier(){
        return fields().f("buildingDamageMultiplier");
    }

    default float statusDuration(){
        return fields().f("statusDuration");
    }

    default int fragBullets(){
        return fields().i("fragBullets");
    }

    default float accel(){
        return fields().f("accel");
    }

    default float angleOffset(){
        return fields().f("angleOffset");
    }

    default float randomAngleOffset(){
        return fields().f("randomAngleOffset");
    }

    default float createChance(){
        return fields().f("createChance");
    }

    default boolean ignoreSpawnAngle(){
        return fields().b("ignoreSpawnAngle");
    }

    default float velocityScaleRandMin(){
        return fields().f("velocityScaleRandMin");
    }

    default float velocityScaleRandMax(){
        return fields().f("velocityScaleRandMax");
    }

    default float lifeScaleRandMin(){
        return fields().f("lifeScaleRandMin");
    }

    default float lifeScaleRandMax(){
        return fields().f("lifeScaleRandMax");
    }

    default boolean keepVelocity(){
        return fields().b("keepVelocity");
    }

    default boolean scaleKeepVelocity(){
        return fields().b("scaleKeepVelocity");
    }

    default boolean killShooter(){
        return fields().b("killShooter");
    }

    default boolean instantDisappear(){
        return fields().b("instantDisappear");
    }

    default float spawnBulletRandomSpread(){
        return fields().f("spawnBulletRandomSpread");
    }

    default boolean collides(){
        return fields().b("collides");
    }

    default boolean collidesTeam(){
        return fields().b("collidesTeam");
    }

    default boolean collideFloor(){
        return fields().b("collideFloor");
    }

    default boolean collideTerrain(){
        return fields().b("collideTerrain");
    }

    default boolean pierceBuilding(){
        return fields().b("pierceBuilding");
    }

    default boolean removeAfterPierce(){
        return fields().b("removeAfterPierce");
    }

    default float pierceDamageFactor(){
        return fields().f("pierceDamageFactor");
    }

    default boolean pierceArmor(){
        return fields().b("pierceArmor");
    }

    default int pierceFragCap(){
        return fields().i("pierceFragCap");
    }

    default boolean laserBullet(){
        return fields().b("laserBullet");
    }

    default boolean hitUnder(){
        return fields().b("hitUnder");
    }

    default boolean sticky(){
        return fields().b("sticky");
    }

    default float stickyExtraLifetime(){
        return fields().f("stickyExtraLifetime");
    }

    default float homingPower(){
        return fields().f("homingPower");
    }

    default float homingDelay(){
        return fields().f("homingDelay");
    }

    default float homingRange(){
        return fields().f("homingRange");
    }

    default float followAimSpeed(){
        return fields().f("followAimSpeed");
    }

    default float weaveMag(){
        return fields().f("weaveMag");
    }

    default float weaveScale(){
        return fields().f("weaveScale");
    }

    default boolean weaveRandom(){
        return fields().b("weaveRandom");
    }

    default float rotateSpeed(){
        return fields().f("rotateSpeed");
    }

    default boolean circleShooter(){
        return fields().b("circleShooter");
    }

    default float circleShooterRadius(){
        return fields().f("circleShooterRadius");
    }

    default float circleShooterRadiusSmooth(){
        return fields().f("circleShooterRadiusSmooth");
    }

    default float circleShooterRotateSpeed(){
        return fields().f("circleShooterRotateSpeed");
    }

    default float trailChance(){
        return fields().f("trailChance");
    }

    default float trailInterval(){
        return fields().f("trailInterval");
    }

    default float trailSpread(){
        return fields().f("trailSpread");
    }

    default float trailMinVelocity(){
        return fields().f("trailMinVelocity");
    }

    default int intervalBullets(){
        return fields().i("intervalBullets");
    }

    default float intervalDelay(){
        return fields().f("intervalDelay");
    }

    default float bulletInterval(){
        return fields().f("bulletInterval");
    }

    default float intervalRandomSpread(){
        return fields().f("intervalRandomSpread");
    }

    default float intervalAngle(){
        return fields().f("intervalAngle");
    }

    default float intervalSpread(){
        return fields().f("intervalSpread");
    }

    default float hitSoundPitchRange(){
        return fields().f("hitSoundPitchRange");
    }

    default float healPercent(){
        return fields().f("healPercent");
    }

    default float healAmount(){
        return fields().f("healAmount");
    }

    default float lifesteal(){
        return fields().f("lifesteal");
    }

    default boolean makeFire(){
        return fields().b("makeFire");
    }

    default boolean fragOnHit(){
        return fields().b("fragOnHit");
    }

    default boolean delayFrags(){
        return fields().b("delayFrags");
    }

    default float fragOffsetMin(){
        return fields().f("fragOffsetMin");
    }

    default float fragOffsetMax(){
        return fields().f("fragOffsetMax");
    }

    default float fragRandomSpread(){
        return fields().f("fragRandomSpread");
    }

    default float fragAngle(){
        return fields().f("fragAngle");
    }

    default float fragSpread(){
        return fields().f("fragSpread");
    }

    default float fragVelocityMin(){
        return fields().f("fragVelocityMin");
    }

    default float fragVelocityMax(){
        return fields().f("fragVelocityMax");
    }

    default float fragLifeMin(){
        return fields().f("fragLifeMin");
    }

    default float fragLifeMax(){
        return fields().f("fragLifeMax");
    }

    default boolean fragOnAbsorb(){
        return fields().b("fragOnAbsorb");
    }

    default boolean fragOnDespawn(){
        return fields().b("fragOnDespawn");
    }

    default int puddles(){
        return fields().i("puddles");
    }

    default float puddleRange(){
        return fields().f("puddleRange");
    }

    default float puddleAmount(){
        return fields().f("puddleAmount");
    }

    default float incendChance(){
        return fields().f("incendChance");
    }

    default float incendSpread(){
        return fields().f("incendSpread");
    }

    default int incendAmount(){
        return fields().i("incendAmount");
    }

    default float despawnUnitChance(){
        return fields().f("despawnUnitChance");
    }

    default int despawnUnitCount(){
        return fields().i("despawnUnitCount");
    }

    default float despawnUnitRadius(){
        return fields().f("despawnUnitRadius");
    }

    default boolean faceOutwards(){
        return fields().b("faceOutwards");
    }

    default float suppressionRange(){
        return fields().f("suppressionRange");
    }

    default float suppressionDuration(){
        return fields().f("suppressionDuration");
    }

    default float suppressionEffectChance(){
        return fields().f("suppressionEffectChance");
    }

    default boolean splashDamagePierce(){
        return fields().b("splashDamagePierce");
    }

    default boolean scaledSplashDamage(){
        return fields().b("scaledSplashDamage");
    }

    default float armorMultiplier(){
        return fields().f("armorMultiplier");
    }

    default float blockArmorMultiplier(){
        return fields().f("blockArmorMultiplier");
    }

    default float statusChance(){
        return fields().f("statusChance");
    }

    default int lightning(){
        return fields().i("lightning");
    }

    default float lightningDamage(){
        return fields().f("lightningDamage");
    }

    default float lightningCone(){
        return fields().f("lightningCone");
    }

    default float lightningAngle(){
        return fields().f("lightningAngle");
    }

    default int lightningLength(){
        return fields().i("lightningLength");
    }

    default int lightningLengthRand(){
        return fields().i("lightningLengthRand");
    }

    default boolean despawnHit(){
        return fields().b("despawnHit");
    }

    default float maxDamageFraction(){
        return fields().f("maxDamageFraction");
    }

    default boolean impact(){
        return fields().b("impact");
    }

    default float shieldDamageMultiplier(){
        return fields().f("shieldDamageMultiplier");
    }

    default float recoil(){
        return fields().f("recoil");
    }

    default float inaccuracy(){
        return fields().f("inaccuracy");
    }

    default boolean scaleLife(){
        return fields().b("scaleLife");
    }

    default boolean reflectable(){
        return fields().b("reflectable");
    }

    default boolean absorbable(){
        return fields().b("absorbable");
    }

    default boolean hittable(){
        return fields().b("hittable");
    }

    default boolean laserAbsorb(){
        return fields().b("laserAbsorb");
    }

    default boolean underwater(){
        return fields().b("underwater");
    }

    default float optimalLifeFract(){
        return fields().f("optimalLifeFract");
    }

    default float rangeChange(){
        return fields().f("rangeChange");
    }

    /** {@code ArtilleryBulletType.trailMult}: scales its trail timer. */
    default float trailMult(){
        return fields().f("trailMult");
    }

    /** {@code FlakBulletType.explodeRange}: how near an enemy sets it off. */
    default float explodeRange(){
        return fields().f("explodeRange");
    }

    default float explodeDelay(){
        return fields().f("explodeDelay");
    }

    default float flakDelay(){
        return fields().f("flakDelay");
    }

    default float flakInterval(){
        return fields().f("flakInterval");
    }

    /** {@code FireBulletType.velMin}, {@code velMax}: the range of its starting speed. */
    default float velMin(){
        return fields().f("velMin");
    }

    default float velMax(){
        return fields().f("velMax");
    }

    /** {@code FireBulletType.fireTrailChance}: its chance per tick to set its tile alight. */
    default float fireTrailChance(){
        return fields().f("fireTrailChance");
    }

    /** {@code FireBulletType.fireEffectChance}, {@code fireEffectChance2}: client effects, whose chances still draw. */
    default float fireEffectChance(){
        return fields().f("fireEffectChance");
    }

    default float fireEffectChance2(){
        return fields().f("fireEffectChance2");
    }

    /** {@code LiquidBulletType.puddleSize}. */
    default float puddleSize(){
        return fields().f("puddleSize");
    }

    /** {@code LiquidBulletType.boilTime}. */
    default float boilTime(){
        return fields().f("boilTime");
    }

    /** The beam's {@code length} (laser, continuous, sap, rail and shrapnel kinds). */
    default float length(){
        return fields().f("length");
    }

    /** {@code largeHit} (laser and continuous kinds): the beam also hits the buildings beside the ones in its path. */
    default boolean largeHit(){
        return fields().b("largeHit");
    }

    /** {@code ContinuousBulletType.continuous}: false makes it a single-shot line. */
    default boolean continuous(){
        return fields().b("continuous");
    }

    /** {@code damageInterval} (continuous and point-laser kinds): ticks between hits. */
    default float damageInterval(){
        return fields().f("damageInterval");
    }

    /** {@code ContinuousBulletType.timescaleDamage}: a building owner's time scale multiplies its damage. */
    default boolean timescaleDamage(){
        return fields().b("timescaleDamage");
    }

    /** {@code ContinuousLaserBulletType.fadeTime}: the beam shrinks over its last ticks. Client-tagged, but it sets the length. */
    default float fadeTime(){
        return fields().f("fadeTime");
    }

    /** {@code ContinuousFlameBulletType.lengthInterp}, as the pack names it ({@code interp:slope}). */
    default String lengthInterp(){
        return fields().s("lengthInterp");
    }

    /** {@code SapBulletType.lengthRand}. */
    default float lengthRand(){
        return fields().f("lengthRand");
    }

    /** {@code SapBulletType.sapStrength}: the share of its damage that heals its owner. */
    default float sapStrength(){
        return fields().f("sapStrength");
    }

    /** {@code ShrapnelBulletType.hitLarge}. */
    default boolean hitLarge(){
        return fields().b("hitLarge");
    }

    /** {@code LaserBulletType.lightningSpacing}: lightning along the beam every so many units, if positive. Client-tagged, but it deals damage. */
    default float lightningSpacing(){
        return fields().f("lightningSpacing");
    }

    /** {@code LaserBulletType.lightningDelay}: ticks between the beam's lightning pairs. */
    default float lightningDelay(){
        return fields().f("lightningDelay");
    }

    /** {@code LaserBulletType.lightningAngleRand}. */
    default float lightningAngleRand(){
        return fields().f("lightningAngleRand");
    }

    /** {@code PointLaserBulletType.beamEffectInterval}: its effect timer, which still runs on a server. */
    default float beamEffectInterval(){
        return fields().f("beamEffectInterval");
    }

    /** {@code EmpBulletType.radius}. */
    default float radius(){
        return fields().f("radius");
    }

    /** {@code EmpBulletType.timeIncrease}: the overdrive it gives allied powered blocks. */
    default float timeIncrease(){
        return fields().f("timeIncrease");
    }

    /** {@code EmpBulletType.timeDuration}. */
    default float timeDuration(){
        return fields().f("timeDuration");
    }

    /** {@code EmpBulletType.powerDamageScl}. */
    default float powerDamageScl(){
        return fields().f("powerDamageScl");
    }

    /** {@code EmpBulletType.powerSclDecrease}: the slowdown it gives enemy power producers. */
    default float powerSclDecrease(){
        return fields().f("powerSclDecrease");
    }

    /** {@code EmpBulletType.hitUnits}. */
    default boolean hitUnits(){
        return fields().b("hitUnits");
    }

    /** {@code EmpBulletType.unitDamageScl}. */
    default float unitDamageScl(){
        return fields().f("unitDamageScl");
    }

    /** The unit it spawns instead of a bullet ({@code spawnUnit}), or null. */
    UnitDef spawnUnit();

    /** The unit it leaves when it despawns or hits ({@code despawnUnit}), or null. */
    UnitDef despawnUnit();

    /** The bullet it fires every {@code bulletInterval} ticks ({@code intervalBullet}), or null. */
    BulletDef intervalBullet();

    /** The bullet its lightning is made of; {@code BulletType.init} picks one by what it collides with. */
    BulletDef lightningType();

    /** The bullets it creates when it is created ({@code spawnBullets}), in order. */
    java.util.List<BulletDef> spawnBullets();

    /** The liquid its puddles are made of, or null. */
    LiquidDef puddleLiquid();

    /** A {@code liquid-bullet-type}'s liquid, or null for other kinds. */
    LiquidDef liquid();

    /** The status it applies on hit, or the {@code none} status. */
    StatusDef status();

    /** The bullets it splits into, or null. */
    BulletDef fragBullet();
}
