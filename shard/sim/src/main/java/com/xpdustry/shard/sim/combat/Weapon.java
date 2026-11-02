package com.xpdustry.shard.sim.combat;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code Weapon} (Weapon.java): one weapon of a unit type, its parameters read once from the content's
 * {@link WeaponDef} (after {@code UnitType.init}: mirrors are their own entries, flipped), and its {@code update},
 * which aims, reloads, alternates sides and shoots through its {@link ShootPattern}. Each unit holds a
 * {@link WeaponMount} per weapon. This class is the {@code weapon} kind; the kinds with behaviour of their own
 * ({@link PointDefenseWeapon}, {@link RepairBeamWeapon}, {@link BuildWeapon}) are subclasses registered with
 * {@link WeaponKind}. Immutable once loaded.
 *
 * <p>Every random draw vanilla's server makes is kept, sound pitches included; effects, sounds, shakes and drawing are
 * not. Delayed shots are timer tasks ({@link DelayedShot}).
 */
@Registry
public class Weapon{
    private WeaponDef def;
    private int index;
    private boolean supported = true;

    public BulletType bullet;
    public ShootPattern shoot;
    public float x, y, shootX, shootY, xRand, yRand, reload, baseRotation, rotateSpeed, rotationLimit, shootCone, inaccuracy;
    public float aimChangeSpeed, targetInterval, targetSwitchInterval, recoilTime, cooldownTime, velocityRnd, extraVelocity;
    public float lifeRnd, extraLife, minWarmup, shootWarmupSpeed, smoothReloadSpeed, soundPitchMin, soundPitchMax;
    public float minShootVelocity, maxShootVelocity, shootStatusDuration;
    public int otherSide, recoils;
    public boolean mirror, alternate, flipSprite, rotate, continuous, alwaysContinuous, controllable, aiControllable;
    public boolean alwaysShooting, autoTarget, predictTarget, useAttackRange, linearWarmup, ignoreRotation, noAttack;
    public boolean shootOnDeath, hasShootOnDeathEffect;
    public StatusDef shootStatus;

    /** For {@link Armory}, which then {@link #load loads} it. */
    public Weapon(){
    }

    /** Reads the parameters of weapon {@code index} of a unit type from {@code def}. */
    protected void load(WeaponDef def, int index, BulletTypes bullets){
        this.def = def;
        this.index = index;
        bullet = bullets.get(def.bullet());
        shoot = ShootPattern.of(def.shoot());
        x = def.x();
        y = def.y();
        shootX = def.shootX();
        shootY = def.shootY();
        xRand = def.xRand();
        yRand = def.yRand();
        reload = def.reload();
        baseRotation = def.baseRotation();
        rotateSpeed = def.rotateSpeed();
        rotationLimit = def.rotationLimit();
        shootCone = def.shootCone();
        inaccuracy = def.inaccuracy();
        aimChangeSpeed = def.aimChangeSpeed();
        targetInterval = def.targetInterval();
        targetSwitchInterval = def.targetSwitchInterval();
        recoilTime = def.recoilTime();
        cooldownTime = def.cooldownTime();
        velocityRnd = def.velocityRnd();
        extraVelocity = def.extraVelocity();
        lifeRnd = def.lifeRnd();
        extraLife = def.extraLife();
        minWarmup = def.minWarmup();
        shootWarmupSpeed = def.shootWarmupSpeed();
        smoothReloadSpeed = def.smoothReloadSpeed();
        soundPitchMin = def.soundPitchMin();
        soundPitchMax = def.soundPitchMax();
        minShootVelocity = def.minShootVelocity();
        maxShootVelocity = def.maxShootVelocity();
        shootStatusDuration = def.shootStatusDuration();
        otherSide = def.otherSide();
        recoils = def.recoils();
        mirror = def.mirror();
        alternate = def.alternate();
        flipSprite = def.flipSprite();
        rotate = def.rotate();
        // baked: alwaysContinuous implies continuous
        alwaysContinuous = def.alwaysContinuous();
        continuous = def.continuous();
        controllable = def.controllable();
        aiControllable = def.aiControllable();
        alwaysShooting = def.alwaysShooting();
        autoTarget = def.autoTarget();
        predictTarget = def.predictTarget();
        useAttackRange = def.useAttackRange();
        linearWarmup = def.linearWarmup();
        ignoreRotation = def.ignoreRotation();
        noAttack = def.noAttack();
        shootOnDeath = def.shootOnDeath();
        hasShootOnDeathEffect = def.hasShootOnDeathEffect();
        shootStatus = def.shootStatus();
    }

    void unsupported(){
        supported = false;
    }

    public WeaponDef def(){
        return def;
    }

    /** Its index in the unit type's weapon list, which is its mount's. */
    public int index(){
        return index;
    }

    /** Whether this class ports the weapon's kind, rather than {@code weapon}'s behaviour standing in for it. */
    public boolean supported(){
        return supported;
    }

    /** {@code range()}: the bullet's. */
    public float range(){
        return bullet == null ? 0f : bullet.range;
    }

    /** The mount's weapon, cached on the mount. */
    public static Weapon of(Unit unit, WeaponMount mount){
        Weapon weapon = mount.kind;
        if(weapon == null) mount.kind = weapon = Arms.of(unit).weapon(mount.weapon);
        return weapon;
    }

    /** The mount's X and Y: where the weapon sits on its unit. */
    final float mountX(Unit unit){
        return unit.x + Angles.trnsx(unit.rotation - 90, x, y);
    }

    final float mountY(Unit unit){
        return unit.y + Angles.trnsy(unit.rotation - 90, x, y);
    }

    /** {@code update(unit, mount)} (Weapon.java:305): one tick of the mount. */
    public void update(Unit unit, WeaponMount mount){
        World world = unit.world();
        boolean can = Weapons.canShoot(unit);
        float reloadMultiplier = unit.statuses.reloadMultiplier;
        float lastReload = mount.reload;
        mount.reload = Math.max(mount.reload - reloadMultiplier, 0);
        mount.recoil = Mathf.approachDelta(mount.recoil, 0, reloadMultiplier / recoilTime);
        if(recoils > 0){
            if(mount.recoils == null) mount.recoils = new float[recoils];
            for(int i = 0; i < recoils; i++){
                mount.recoils[i] = Mathf.approachDelta(mount.recoils[i], 0, reloadMultiplier / recoilTime);
            }
        }
        mount.smoothReload = Mathf.lerpDelta(mount.smoothReload, mount.reload / reload, smoothReloadSpeed);
        mount.charge = mount.charging && shoot.firstShotDelay > 0 ? Mathf.approachDelta(mount.charge, 1, 1 / shoot.firstShotDelay) : 0;

        Bullet held = continuous && mount.bullet != -1 ? held(world, mount) : null;
        float warmupTarget = (can && mount.shoot) || (continuous && mount.bullet != -1) || mount.charging ? 1f : 0f;
        if(linearWarmup){
            mount.warmup = Mathf.approachDelta(mount.warmup, warmupTarget, shootWarmupSpeed);
        }else{
            mount.warmup = Mathf.lerpDelta(mount.warmup, warmupTarget, shootWarmupSpeed);
        }

        float mountX = mountX(unit), mountY = mountY(unit);

        // find a new target
        if(!controllable && autoTarget){
            updateTarget(unit, mount, mountX, mountY, can);
        }

        // rotate if applicable
        if(rotate && (mount.rotate || mount.shoot) && can){
            float axisX = unit.x + Angles.trnsx(unit.rotation - 90, x, y), axisY = unit.y + Angles.trnsy(unit.rotation - 90, x, y);
            mount.targetRotation = Angles.angle(axisX, axisY, mount.aimX, mount.aimY) - unit.rotation;
            mount.rotation = Angles.moveToward(mount.rotation, mount.targetRotation, rotateSpeed);
            if(rotationLimit < 360){
                float dst = Angles.angleDist(mount.rotation, baseRotation);
                if(dst > rotationLimit / 2f){
                    mount.rotation = Angles.moveToward(mount.rotation, baseRotation, dst - rotationLimit / 2f);
                }
            }
        }else if(!rotate){
            mount.rotation = baseRotation;
            mount.targetRotation = unit.angleTo(mount.aimX, mount.aimY);
        }

        float weaponRotation = unit.rotation - 90 + (rotate ? mount.rotation : baseRotation);
        float bulletX = mountX + Angles.trnsx(weaponRotation, this.shootX, this.shootY);
        float bulletY = mountY + Angles.trnsy(weaponRotation, this.shootX, this.shootY);
        float shootAngle = bulletRotation(unit, mount, bulletX, bulletY);

        if(alwaysShooting) mount.shoot = true;

        // update continuous state
        if(continuous && mount.bullet != -1){
            if(held == null || !held.isAdded() || held.time() >= held.lifetime() || held.type().id() != bullet.id()){
                mount.bullet = -1;
            }else{
                held.rotation(weaponRotation + 90);
                held.set(bulletX, bulletY);
                mount.reload = reload;
                mount.recoil = 1f;
                var push = new Vec2().trns(held.rotation() + 180f, held.type().recoil);
                unit.vel.add(push.x, push.y);
                // target length of laser
                float shootLength = Math.min(Mathf.dst(bulletX, bulletY, mount.aimX, mount.aimY), range());
                // current length of laser
                float curLength = Mathf.dst(bulletX, bulletY, held.aimX(), held.aimY());
                // resulting length of the bullet (smoothed)
                float resultLength = Mathf.approachDelta(curLength, shootLength, aimChangeSpeed);
                // actual aim end point based on length
                var end = new Vec2().trns(shootAngle, mount.lastLength = resultLength).add(bulletX, bulletY);
                held.aim(end.x, end.y);
                if(alwaysContinuous && mount.shoot){
                    held.time(held.lifetime() * held.type().optimalLifeFract * mount.warmup);
                    held.keepAlive(true);
                    StatusApply.of(world).apply(unit, shootStatus, shootStatusDuration);
                }
            }
        }else{
            // heat decreases when not firing
            mount.heat = Math.max(mount.heat - reloadMultiplier / cooldownTime, 0);
        }

        // flip weapon shoot side for alternating weapons
        boolean wasFlipped = mount.side;
        if(otherSide >= 0 && alternate && mount.side == flipSprite && otherSide < unit.mounts.length && mount.reload <= reload / 2f
            && lastReload > reload / 2f){
            unit.mounts[otherSide].side = !unit.mounts[otherSide].side;
            mount.side = !mount.side;
        }

        float velLen = unit.isRemote() ? unit.vel.len() : Mathf.len(unit.deltaX, unit.deltaY);

        // shoot if applicable
        if(mount.shoot // must be shooting
            && can // must be able to shoot
            && !(bullet.killShooter && mount.totalShots > 0) // a bullet that kills its shooter fires once
            && (!alternate || wasFlipped == flipSprite)
            && mount.warmup >= minWarmup // must be warmed up
            && velLen >= minShootVelocity && (maxShootVelocity == -1 || velLen <= maxShootVelocity) // velocity requirements
            && (mount.reload <= 0.0001f || (alwaysContinuous && mount.bullet == -1)) // reloaded, or always-continuous
            && (alwaysShooting || Angles.within(rotate ? mount.rotation : unit.rotation + baseRotation, mount.targetRotation, shootCone))){
            shoot(unit, mount, bulletX, bulletY, shootAngle);
            mount.reload = reload;
        }
    }

    /** The continuous weapon's bullet: what the mount's pooled object holds now, or null. */
    static Bullet held(World world, WeaponMount mount){
        return Bullet.ofObject(world, mount.bullet);
    }

    /** The {@code !controllable && autoTarget} block: retargets on its timer, drops a bad target, aims and shoots. */
    void updateTarget(Unit unit, WeaponMount mount, float mountX, float mountY, boolean can){
        if((mount.retarget -= 1f) <= 0f){
            mount.target = findTarget(unit, mountX, mountY, bullet.range, bullet.collidesAir, bullet.collidesGround);
            mount.retarget = mount.target == null ? targetInterval : targetSwitchInterval;
        }
        if(mount.target != null && checkTarget(unit, mount.target, mountX, mountY, bullet.range)){
            mount.target = null;
        }
        boolean shoot = false;
        if(mount.target != null){
            Entity target = mount.target;
            shoot = Targets.within(target, mountX, mountY, bullet.range + Math.abs(shootY) + Targets.hitSize(target) / 2f) && can;
            if(predictTarget){
                Vec2 to = Predict.intercept(unit, target, bullet);
                mount.aimX = to.x;
                mount.aimY = to.y;
            }else{
                mount.aimX = Targets.x(target);
                mount.aimY = Targets.y(target);
            }
        }
        mount.shoot = mount.rotate = shoot;
        // shooting state is not affected, as these cannot be controlled
    }

    /** {@code findTarget}: the closest enemy unit it can hit, else building, within range plus its barrel. */
    protected Entity findTarget(Unit unit, float x, float y, float range, boolean air, boolean ground){
        SpatialIndex index = SpatialIndex.of(unit.world());
        if(index == null) return null;
        boolean underBlocks = Arms.of(unit).targetUnderBlocks();
        return index.closestTarget(unit.team, x, y, range + Math.abs(shootY), u -> Targets.checkTarget(u, air, ground),
            t -> ground && (underBlocks || !(t.block().baked() && t.block().underBullets())), -1, BuildingTargets.of(unit.world()));
    }

    /** {@code checkTarget}: true when the target should be dropped. */
    protected boolean checkTarget(Unit unit, Entity target, float x, float y, float range){
        return Targets.invalidateTarget(target, unit.team, x, y, range + Math.abs(shootY));
    }

    /** {@code bulletRotation}: the weapon's own rotation, or the aim corrected for the unit's facing. */
    protected float bulletRotation(Unit unit, WeaponMount mount, float bulletX, float bulletY){
        return rotate ? unit.rotation + mount.rotation
            : Angles.angle(bulletX, bulletY, mount.aimX, mount.aimY) + (unit.rotation - unit.angleTo(mount.aimX, mount.aimY)) + baseRotation;
    }

    /** {@code shoot(unit, mount, x, y, rotation)}: the shoot status, a charge, then the pattern's bullets. */
    protected void shoot(Unit unit, WeaponMount mount, float shootX, float shootY, float rotation){
        World world = unit.world();
        StatusApply.of(world).apply(unit, shootStatus, shootStatusDuration);
        if(shoot.firstShotDelay > 0){
            mount.charging = true;
            // chargeSound.at(shootX, shootY, Mathf.random(soundPitchMin, soundPitchMax))
            Mathf.random(world.rand(), soundPitchMin, soundPitchMax);
        }
        shoot.shoot(world.rand(), mount.barrelCounter, (xOffset, yOffset, angle, delay, mover) -> {
            // this is incremented immediately, as it is used for total bullet creation amount detection
            mount.totalShots++;
            int barrel = mount.barrelCounter;
            if(delay > 0f){
                world.timers().run(delay, new DelayedShot(unit, mount.weapon, xOffset, yOffset, angle, mover, barrel));
            }else{
                bullet(unit, mount, xOffset, yOffset, angle, mover);
            }
        }, () -> mount.barrelCounter++);
    }

    /** {@code bullet(unit, mount, xOffset, yOffset, angleOffset, mover)} (Weapon.java:490): one bullet leaves. */
    protected void bullet(Unit unit, WeaponMount mount, float xOffset, float yOffset, float angleOffset, Mover mover){
        if(!unit.isAdded()) return;
        World world = unit.world();
        Rand rand = world.rand();
        mount.charging = false;
        float xSpread = Mathf.range(rand, xRand);
        float ySpread = Mathf.range(rand, yRand);
        float weaponRotation = unit.rotation - 90 + (rotate ? mount.rotation : baseRotation);
        float mountX = mountX(unit), mountY = mountY(unit);
        float bulletX = mountX + Angles.trnsx(weaponRotation, this.shootX + xOffset + xSpread, this.shootY + yOffset + ySpread);
        float bulletY = mountY + Angles.trnsy(weaponRotation, this.shootX + xOffset + xSpread, this.shootY + yOffset + ySpread);
        float shootAngle = bulletRotation(unit, mount, bulletX, bulletY) + angleOffset;
        float baseLife = (1f - lifeRnd) + Mathf.random(rand, lifeRnd) + extraLife;
        float lifeScl = bullet.scaleLife ? baseLife * Mathf.clamp(Mathf.dst(bulletX, bulletY, mount.aimX, mount.aimY) / bullet.range) : baseLife;
        float angle = shootAngle + Mathf.range(rand, inaccuracy + bullet.inaccuracy);
        float velocityScl = (1f - velocityRnd) + Mathf.random(rand, velocityRnd) + extraVelocity;
        // pass the missile's shooter down to its bullets
        Bullet created = bullet.create(world, unit, com.xpdustry.shard.sim.ai.MissileAi.shooterOf(unit), unit.team, bulletX, bulletY, angle, -1f, velocityScl, lifeScl, mover, mount.aimX,
            mount.aimY, mount.target);
        mount.bullet = created == null ? -1 : created.object();
        handleBullet(unit, mount, created);
        // shootSound.at or initialShootSound.at(bulletX, bulletY, Mathf.random(soundPitchMin, soundPitchMax), volume)
        Mathf.random(rand, soundPitchMin, soundPitchMax);
        var push = new Vec2().trns(shootAngle + 180f, bullet.recoil);
        unit.vel.add(push.x, push.y);
        mount.recoil = 1f;
        if(recoils > 0) mount.recoils[mount.barrelCounter % recoils] = 1f;
        mount.heat = 1f;
    }

    /** {@code handleBullet}: a continuous weapon's bullet aims at the length it last had. */
    protected void handleBullet(Unit unit, WeaponMount mount, Bullet bullet){
        if(continuous && bullet != null){
            float weaponRotation = unit.rotation - 90 + (rotate ? mount.rotation : baseRotation);
            float bulletX = mountX(unit) + Angles.trnsx(weaponRotation, this.shootX, this.shootY);
            float bulletY = mountY(unit) + Angles.trnsy(weaponRotation, this.shootX, this.shootY);
            // make sure the length updates to the last set value
            var end = new Vec2().trns(bulletRotation(unit, mount, bulletX, bulletY), shootY + mount.lastLength).add(bulletX, bulletY);
            bullet.aim(end.x, end.y);
        }
    }

    @Override
    public String toString(){
        return def == null ? "Weapon(unloaded)" : "Weapon(" + def.name() + ")";
    }

    /**
     * The delayed shot of a pattern ({@code Time.run(delay, …)} in {@code shoot}): fires from the barrel it was
     * queued for. Vanilla's closure holds the unit and its mount object; this holds the unit and the mount's index.
     */
    public record DelayedShot(Unit unit, int mount, float xOffset, float yOffset, float angle, Mover mover, int barrel)
        implements Timers.Task{
        @Override
        public void run(World world){
            if(mount >= unit.mounts.length) return;
            WeaponMount at = unit.mounts[mount];
            Weapon weapon = Weapon.of(unit, at);
            // hack: make sure the barrel is the same as what it was when the bullet was queued to fire
            int prev = at.barrelCounter;
            at.barrelCounter = barrel;
            weapon.bullet(unit, at, xOffset, yOffset, angle, mover);
            at.barrelCounter = prev;
        }
    }
}
