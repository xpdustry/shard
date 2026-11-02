package com.xpdustry.shard.content;

import com.xpdustry.shard.content.schema.*;

/**
 * A unit weapon after {@code UnitType.init}: mirrored weapons are separate entries, as vanilla's list holds them. Not
 * content: it has no id and lives in its unit's {@link UnitDef#weapons()}.
 */
public interface WeaponDef{
    /** The weapon's fields, a {@code weapon} struct or one of its subkinds. */
    Struct fields();

    /** The bullet it fires. */
    BulletDef bullet();

    /** The sprite name, which also tells weapons apart; may be empty. */
    default String name(){
        return fields().s("name");
    }

    default KindKey kind(){
        return fields().kind().kindKey();
    }

    default float x(){
        return fields().f("x");
    }

    default float y(){
        return fields().f("y");
    }

    default float shootX(){
        return fields().f("shootX");
    }

    default float shootY(){
        return fields().f("shootY");
    }

    default float reload(){
        return fields().f("reload");
    }

    /** {@code baseRotation}: the rotation a new mount starts at, relative to the unit. */
    default float baseRotation(){
        return fields().f("baseRotation");
    }

    default boolean mirror(){
        return fields().b("mirror");
    }

    /** Whether it fires in turn with its mirror. */
    default boolean alternate(){
        return fields().b("alternate");
    }

    /** The index of its mirror in the weapon list, or -1. */
    default int otherSide(){
        return fields().i("otherSide");
    }

    default boolean rotate(){
        return fields().b("rotate");
    }

    /** {@code controllable}: a player's aim and trigger drive it ({@code WeaponsComp.controlWeapons}, {@code aim}). */
    default boolean controllable(){
        return fields().b("controllable");
    }

    default float rotateSpeed(){
        return fields().f("rotateSpeed");
    }

    default float shootCone(){
        return fields().f("shootCone");
    }

    default float inaccuracy(){
        return fields().f("inaccuracy");
    }

    default boolean continuous(){
        return fields().b("continuous");
    }

    default float xRand(){
        return fields().f("xRand");
    }

    default float yRand(){
        return fields().f("yRand");
    }

    default boolean flipSprite(){
        return fields().b("flipSprite");
    }

    default float rotationLimit(){
        return fields().f("rotationLimit");
    }

    default boolean alwaysContinuous(){
        return fields().b("alwaysContinuous");
    }

    default float aimChangeSpeed(){
        return fields().f("aimChangeSpeed");
    }

    default boolean aiControllable(){
        return fields().b("aiControllable");
    }

    default boolean alwaysShooting(){
        return fields().b("alwaysShooting");
    }

    default boolean autoTarget(){
        return fields().b("autoTarget");
    }

    default boolean predictTarget(){
        return fields().b("predictTarget");
    }

    default boolean useAttackRange(){
        return fields().b("useAttackRange");
    }

    default float targetInterval(){
        return fields().f("targetInterval");
    }

    default float targetSwitchInterval(){
        return fields().f("targetSwitchInterval");
    }

    default float recoilTime(){
        return fields().f("recoilTime");
    }

    default int recoils(){
        return fields().i("recoils");
    }

    default float cooldownTime(){
        return fields().f("cooldownTime");
    }

    default float velocityRnd(){
        return fields().f("velocityRnd");
    }

    default float extraVelocity(){
        return fields().f("extraVelocity");
    }

    default float lifeRnd(){
        return fields().f("lifeRnd");
    }

    default float extraLife(){
        return fields().f("extraLife");
    }

    default float minWarmup(){
        return fields().f("minWarmup");
    }

    default float shootWarmupSpeed(){
        return fields().f("shootWarmupSpeed");
    }

    default float smoothReloadSpeed(){
        return fields().f("smoothReloadSpeed");
    }

    default boolean linearWarmup(){
        return fields().b("linearWarmup");
    }

    default float soundPitchMin(){
        return fields().f("soundPitchMin");
    }

    default float soundPitchMax(){
        return fields().f("soundPitchMax");
    }

    default boolean ignoreRotation(){
        return fields().b("ignoreRotation");
    }

    default boolean noAttack(){
        return fields().b("noAttack");
    }

    default float minShootVelocity(){
        return fields().f("minShootVelocity");
    }

    default float maxShootVelocity(){
        return fields().f("maxShootVelocity");
    }

    default float shootStatusDuration(){
        return fields().f("shootStatusDuration");
    }

    default boolean shootOnDeath(){
        return fields().b("shootOnDeath");
    }

    /** Whether {@code shootOnDeathEffect} is set, which makes a death shot hide its shoot effects. */
    default boolean hasShootOnDeathEffect(){
        return fields().get("shootOnDeathEffect") != null;
    }

    /** {@code mountType}: the hook naming the mount class ({@code hook:RepairBeamWeapon#0} for heal beams). */
    default String mountType(){
        return fields().s("mountType");
    }

    /** The shoot pattern ({@code shoot}), a {@code shoot-pattern} struct or one of its subkinds; mirrors hold it flipped. */
    default Struct shoot(){
        return fields().struct("shoot");
    }

    /** The status it gives its unit when it shoots ({@code shootStatus}), or the {@code none} status. */
    StatusDef shootStatus();
}
