package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * The state of one weapon on one unit (vanilla {@code WeaponMount}), owned by its {@link Unit}. The weapon itself is
 * the type's {@code weapons.get(weapon)}, a {@link Weapon} ({@code sim.combat}), which updates it. {@code shoot},
 * {@code rotate}, {@code aimX} and {@code aimY} are synced ({@code TypeIO.writeMounts}); the rest is server state. The
 * sound loop is client-only. A heal beam's {@code HealBeamMount} fields are here too, unused by other weapons.
 *
 * <p>Every field is {@link Persistence#AUTHORITATIVE} but the cached {@link #kind}.
 */
public final class WeaponMount implements Checkpointable{
    /** The index of the weapon in the unit type's weapon list. Set once, at creation or restore. */
    public int weapon;
    /** Frames until the weapon can fire again; 0 is ready. */
    public float reload;
    /** Rotation relative to the unit. */
    public float rotation;
    public float recoil;
    /** Per-barrel recoil, or null. */
    public float[] recoils;
    public float targetRotation;
    public float heat, warmup, charge, smoothReload;
    public boolean charging;
    /** Where the weapon aims, in world coordinates. */
    public float aimX, aimY;
    public boolean shoot, rotate;
    public boolean allowShootEffects = true;
    /** Alternating-weapon side. */
    public boolean side;
    public int totalShots, barrelCounter;
    /** The last aim length, for point lasers. */
    public float lastLength;
    /**
     * The bullet a continuous weapon keeps alive, as its pooled object ({@code Bullets.object}), or -1: vanilla keeps
     * the object, which the pool may hand to another bullet.
     */
    public int bullet = -1;
    /** The current target of an autonomous weapon: a unit or a building, or null. Check {@code isAdded()}. */
    public Entity target;
    /** A point-defense weapon's target, a bullet, as its pooled object, or -1; {@link #target} is then null. */
    public int targetBullet = -1;
    public float retarget;
    /** {@code HealBeamMount}: the beam's strength, its effect timer and its end ({@code offset} is drawing only). */
    public float strength, effectTimer, lastEndX, lastEndY;
    @Derived("Weapon.of(unit, mount), on first update")
    public Weapon kind;

    /** A blank mount for a checkpoint to fill. */
    public WeaponMount(){
    }

    /** A new mount, as {@code new WeaponMount(weapon)}: its rotation starts at the weapon's base rotation. */
    public WeaponMount(int weapon, float baseRotation){
        this.weapon = weapon;
        this.rotation = baseRotation;
    }

    WeaponMount copy(CopyContext context){
        var out = new WeaponMount(weapon, rotation);
        out.reload = reload;
        out.recoil = recoil;
        out.recoils = recoils == null ? null : recoils.clone();
        out.targetRotation = targetRotation;
        out.heat = heat;
        out.warmup = warmup;
        out.charge = charge;
        out.smoothReload = smoothReload;
        out.charging = charging;
        out.aimX = aimX;
        out.aimY = aimY;
        out.shoot = shoot;
        out.rotate = rotate;
        out.allowShootEffects = allowShootEffects;
        out.side = side;
        out.totalShots = totalShots;
        out.barrelCounter = barrelCounter;
        out.lastLength = lastLength;
        out.bullet = bullet;
        out.target = context.map(target);
        out.targetBullet = targetBullet;
        out.retarget = retarget;
        out.strength = strength;
        out.effectTimer = effectTimer;
        out.lastEndX = lastEndX;
        out.lastEndY = lastEndY;
        out.kind = kind;
        return out;
    }

    @Override
    public void write(CheckpointOut out){
        out.i("weapon", weapon).f("reload", reload).f("rotation", rotation).f("recoil", recoil).floats("recoils", recoils)
            .f("targetRotation", targetRotation).f("heat", heat).f("warmup", warmup).f("charge", charge)
            .f("smoothReload", smoothReload).bool("charging", charging).f("aimX", aimX).f("aimY", aimY)
            .bool("shoot", shoot).bool("rotate", rotate).bool("allowShootEffects", allowShootEffects).bool("side", side)
            .i("totalShots", totalShots).i("barrelCounter", barrelCounter).f("lastLength", lastLength).i("bullet", bullet)
            .ref("target", target).i("targetBullet", targetBullet).f("retarget", retarget)
            .f("strength", strength).f("effectTimer", effectTimer).f("lastEndX", lastEndX).f("lastEndY", lastEndY);
    }

    @Override
    public void read(CheckpointIn in){
        weapon = in.i("weapon");
        reload = in.f("reload");
        rotation = in.f("rotation");
        recoil = in.f("recoil");
        recoils = in.floats("recoils");
        targetRotation = in.f("targetRotation");
        heat = in.f("heat");
        warmup = in.f("warmup");
        charge = in.f("charge");
        smoothReload = in.f("smoothReload");
        charging = in.bool("charging");
        aimX = in.f("aimX");
        aimY = in.f("aimY");
        shoot = in.bool("shoot");
        rotate = in.bool("rotate");
        allowShootEffects = in.bool("allowShootEffects");
        side = in.bool("side");
        totalShots = in.i("totalShots");
        barrelCounter = in.i("barrelCounter");
        lastLength = in.f("lastLength");
        bullet = in.i("bullet");
        target = in.ref("target", Entity.class);
        targetBullet = in.i("targetBullet");
        retarget = in.f("retarget");
        strength = in.f("strength");
        effectTimer = in.f("effectTimer");
        lastEndX = in.f("lastEndX");
        lastEndY = in.f("lastEndY");
        kind = null;
    }

    void hash(Hasher hasher){
        hasher.i(weapon).f(reload).f(rotation).f(recoil).bool(recoils != null);
        if(recoils != null) hasher.floats(recoils);
        hasher.f(targetRotation).f(heat).f(warmup).f(charge).f(smoothReload).bool(charging);
        hasher.f(aimX).f(aimY).bool(shoot).bool(rotate).bool(allowShootEffects).bool(side);
        hasher.i(totalShots).i(barrelCounter).f(lastLength).i(bullet).i(target == null ? -1 : target.id()).i(targetBullet).f(retarget);
        hasher.f(strength).f(effectTimer).f(lastEndX).f(lastEndY);
    }
}
