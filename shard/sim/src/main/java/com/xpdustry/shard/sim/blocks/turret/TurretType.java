package com.xpdustry.shard.sim.blocks.turret;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.schema.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.consume.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * A turret block's parameters: the fields of {@code BaseTurret}, {@code ReloadTurret}, {@code Turret} and the ammo
 * kinds, as the content bake's {@code init()} left them ({@code shootY}, {@code recoilTime}, {@code cooldownTime},
 * {@code newTargetInterval} and {@code trackingRange} filled in, the coolant a booster). Visual-only fields (effects,
 * sounds, drawers, shake) are left out, except the sound pitches whose draws are gameplay.
 */
public final class TurretType{
    // BaseTurret
    public final float range, rotateSpeed, activationTime, coolantMultiplier;
    /** {@code coolant}: the consumer coolant is read from, or null. */
    public final ConsumeLiquidBase coolant;
    // ReloadTurret
    public final float reload;
    // Turret
    public final int timerTarget, maxAmmo, ammoPerShot, recoils;
    public final float targetInterval, newTargetInterval, heatRequirement, maxHeatEfficiency;
    public final boolean consumeAmmoOnce;
    public final float inaccuracy, velocityRnd, extraVelocity, lifeRnd, extraLife, scaleLifetimeOffset, shootCone, shootX, shootY,
        xRand, trackingRange, minRange, minWarmup, warmupMaintainTime, shootWarmupSpeed, recoilTime, cooldownTime,
        soundPitchMin, soundPitchMax;
    public final boolean accurateDelay, moveWhileCharging, reloadWhileCharging, targetAir, targetGround, targetBlocks,
        targetHealing, playerControllable, targetUnderBlocks, alwaysShooting, predictTarget, linearWarmup, rotate;
    public final ShootPattern shoot;
    /** {@code unitSort}, as its content name ({@code sort:closest}). */
    public final String unitSort;
    // the ammo kinds
    /** {@code ammoTypes} of an item or liquid turret by content id (null where not ammo), else empty. */
    public final Ammo[] ammoTypes;
    /** The content ids of {@code ammoTypes}, in the definition's order. */
    public final int[] ammoOrder;
    /** {@code shootType} of a power, laser or continuous turret, else null. */
    public final Ammo shootType;
    /** {@code LaserTurret.firingMoveFract}, {@code shootDuration}. */
    public final float firingMoveFract, shootDuration;
    /** {@code ContinuousTurret.aimChangeSpeed}, {@code scaleDamageEfficiency}; {@code ContinuousLiquidTurret.liquidConsumed}. */
    public final float aimChangeSpeed, liquidConsumed;
    public final boolean scaleDamageEfficiency;
    /** {@code LiquidTurret.extinguish}. */
    public final boolean extinguish;
    /** {@code BaseTurret.fogRadiusMultiplier}: the share of the range its fog radius covers. */
    public final float fogRadiusMultiplier;

    TurretType(World world, BlockDef block){
        Struct f = block.fields();
        BulletTypes bullets = world.bullets().types(world);
        ContentEpoch content = world.content();
        range = f.f("range");
        rotateSpeed = f.f("rotateSpeed");
        activationTime = f.f("activationTime");
        coolantMultiplier = f.f("coolantMultiplier");
        coolant = coolant(world, block);
        reload = f.f("reload");
        timerTarget = f.i("timerTarget");
        maxAmmo = f.i("maxAmmo");
        ammoPerShot = f.i("ammoPerShot");
        recoils = f.i("recoils");
        targetInterval = f.f("targetInterval");
        newTargetInterval = f.f("newTargetInterval");
        heatRequirement = f.f("heatRequirement");
        maxHeatEfficiency = f.f("maxHeatEfficiency");
        consumeAmmoOnce = f.b("consumeAmmoOnce");
        inaccuracy = f.f("inaccuracy");
        velocityRnd = f.f("velocityRnd");
        extraVelocity = f.f("extraVelocity");
        lifeRnd = f.f("lifeRnd");
        extraLife = f.f("extraLife");
        scaleLifetimeOffset = f.f("scaleLifetimeOffset");
        shootCone = f.f("shootCone");
        shootX = f.f("shootX");
        shootY = f.f("shootY");
        xRand = f.f("xRand");
        trackingRange = f.f("trackingRange");
        minRange = f.f("minRange");
        minWarmup = f.f("minWarmup");
        warmupMaintainTime = f.f("warmupMaintainTime");
        shootWarmupSpeed = f.f("shootWarmupSpeed");
        recoilTime = f.f("recoilTime");
        cooldownTime = f.f("cooldownTime");
        soundPitchMin = f.f("soundPitchMin");
        soundPitchMax = f.f("soundPitchMax");
        accurateDelay = f.b("accurateDelay");
        moveWhileCharging = f.b("moveWhileCharging");
        reloadWhileCharging = f.b("reloadWhileCharging");
        targetAir = f.b("targetAir");
        targetGround = f.b("targetGround");
        targetBlocks = f.b("targetBlocks");
        targetHealing = f.b("targetHealing");
        playerControllable = f.b("playerControllable");
        targetUnderBlocks = f.b("targetUnderBlocks");
        alwaysShooting = f.b("alwaysShooting");
        predictTarget = f.b("predictTarget");
        linearWarmup = f.b("linearWarmup");
        rotate = f.b("rotate");
        shoot = ShootPattern.of(f.struct("shoot"));
        unitSort = f.s("unitSort");

        boolean items = block.isA(KindKey.of("item-turret"));
        boolean liquids = block.isA(KindKey.of("liquid-turret")) || block.isA(KindKey.of("continuous-liquid-turret"));
        if(items || liquids){
            Map<String, Object> map = f.map("ammoTypes");
            ammoTypes = new Ammo[items ? content.items() : content.liquids()];
            ammoOrder = new int[map.size()];
            int i = 0;
            for(var entry : map.entrySet()){
                int id = items ? content.item(entry.getKey()).id() : content.liquid(entry.getKey()).id();
                ammoTypes[id] = Ammo.of(bullets.get(content.bullet((String)entry.getValue())));
                ammoOrder[i++] = id;
            }
        }else{
            ammoTypes = new Ammo[0];
            ammoOrder = new int[0];
        }
        shootType = f.has("shootType") && f.s("shootType") != null ? Ammo.of(bullets.get(content.bullet(f.s("shootType")))) : null;
        firingMoveFract = f.has("firingMoveFract") ? f.f("firingMoveFract") : 0f;
        shootDuration = f.has("shootDuration") ? f.f("shootDuration") : 0f;
        fogRadiusMultiplier = f.has("fogRadiusMultiplier") ? f.f("fogRadiusMultiplier") : 1f;
        aimChangeSpeed = f.has("aimChangeSpeed") ? f.f("aimChangeSpeed") : Float.POSITIVE_INFINITY;
        scaleDamageEfficiency = f.has("scaleDamageEfficiency") && f.b("scaleDamageEfficiency");
        liquidConsumed = f.has("liquidConsumed") ? f.f("liquidConsumed") : 0f;
        extinguish = f.has("extinguish") && f.b("extinguish");
    }

    /**
     * {@code coolant}: the consumer the bake made the coolant ({@code ConsumeCoolant}, or a laser turret's first liquid
     * consumer), found in the block's consumers by its place in the definition's list.
     */
    private static ConsumeLiquidBase coolant(World world, BlockDef block){
        Struct wanted = block.fields().struct("coolant");
        if(wanted == null) return null;
        List<Struct> consumes = block.fields().structs("consumes");
        Consume[] all = world.buildings().info(block).consumers.all();
        for(int i = 0; i < consumes.size() && i < all.length; i++){
            if(consumes.get(i).equals(wanted) && all[i] instanceof ConsumeLiquidBase liquid) return liquid;
        }
        return null;
    }

    /** The ammo of {@code id} (an item or liquid), or null. */
    public Ammo ammo(int id){
        return id >= 0 && id < ammoTypes.length ? ammoTypes[id] : null;
    }
}
