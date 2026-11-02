package com.xpdustry.shard.sim.blocks.turret;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.blocks.heat.*;
import com.xpdustry.shard.sim.build.PlaceEnded;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.consume.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.fire.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code BaseTurretBuild}, {@code ReloadTurretBuild} and {@code TurretBuild} (Turret.java): the shared turret.
 * Every {@code targetInterval} frames it looks for the best enemy ({@link #findTarget}), leads it
 * ({@link #targetPosition}), turns toward it at {@code rotateSpeed}, and once within {@code shootCone} fires its ammo
 * through its {@link ShootPattern}, a pattern's later shots on the world's timers as vanilla's {@code Time.run}.
 * Reload advances with efficiency and coolant. The ammo kinds are subclasses: {@link ItemTurret}, {@link PowerTurret},
 * {@link LiquidTurret}, {@link LaserTurret} and the {@link ContinuousTurretBuild continuous} ones.
 *
 * <p>Every draw vanilla makes is made here in its order, though the effects and sounds they feed are not: the coolant
 * effect's spread, the charge and shoot sounds' pitch.
 *
 * <p>Control: a processor's {@code control shoot}/{@code shootp} ({@link #control}), and a player through the block
 * unit ({@link #blockUnit()}, {@code ControlBlock}). A turret with a {@code heatRequirement} (afflict, malign) sums the
 * heat facing it every update ({@link Heat#calculate}) into {@link #heatReq}, which scales its efficiency up to
 * {@code maxHeatEfficiency}; without any it can't consume. Under fog its radius is its range with its ammo's
 * {@code rangeChange} ({@link #fogRadius()}), refreshed when that changes. Not ported: drawing, sounds and recoil offsets.
 */
public abstract class TurretBuild extends Building implements Checkpointable, PlaceEnded{
    /** {@code logicControlCooldown}: frames a processor's aim holds before the turret's own targeting resumes. */
    public static final float LOGIC_CONTROL_COOLDOWN = 60 * 2;
    /** What constructing a block unit draws from the generator ({@code UnitType.create}), after its entity id. */
    private static final int BLOCK_UNIT_DRAWS = 3;

    // BaseTurretBuild
    /**
     * {@code rotation}: where the turret points, in degrees. Vanilla's field shadows the building's 0-3
     * {@link #rotation}, which {@link #updateTile} keeps in step.
     */
    public float turretRotation = 90f;
    public float activationTimer;
    // ReloadTurretBuild
    public float reloadCounter;
    // TurretBuild
    /** {@code ammo}: the entries' item ids, the last one in use ({@code ammo.peek()}). */
    public int[] ammoIds = new int[0];
    /** {@code ammo}: the entries' amounts, as {@link #ammoIds}. */
    public int[] ammoAmounts = new int[0];
    public int ammoSize;
    public int totalAmmo;
    public float curRecoil, heat, logicControlTime = -1f;
    /** {@code curRecoils}: null until the first update of a turret with {@code recoils}. */
    public float[] curRecoils;
    public float shootWarmup, charge, warmupHold;
    public int totalShots, barrelCounter;
    public boolean logicShooting;
    /** {@code lastRangeChange}: the ammo's {@code rangeChange} the fog last drew, under {@code rules.fog}. */
    public float lastRangeChange;
    /** {@code target} when it is a unit or a building, else null. */
    public Entity target;
    /**
     * {@code target} when it is a fire a liquid turret puts out: the fire's pooled object ({@code Fire.object}), else
     * -1. Vanilla keeps the {@code Fire} object, so once the fire goes out the turret aims where it burnt, then at
     * (0, 0) when the pool resets it, then at the next fire to take the object, until it looks for a new target.
     */
    public int targetFire = -1;
    /** {@code targetPos}. */
    public float targetX, targetY;
    public boolean wasShooting, isShooting;
    public int queuedBullets;
    public float heatReq;
    /** {@code sideHeat}: the heat {@link #heatReq} took in through each side, as {@link Heat#calculate} fills it. */
    public float[] sideHeat = new float[4];
    /** The entity id the block unit took at construction ({@code UnitTypes.block.create(team)}). */
    public int blockUnitId = -1;
    /** The block unit, made at construction as vanilla makes it ({@link #constructed()}). */
    public Unit blockUnit;

    @Derived("the block's turret parameters, read on first use")
    private TurretType type;

    /** The block's turret parameters. */
    public final TurretType type(){
        if(type == null) type = new TurretType(world(), block());
        return type;
    }

    /**
     * {@code unit = UnitTypes.block.create(team)} (Turret.java:297): the block unit's id and draws, then the unit, made
     * here as vanilla makes it, so reading it later changes nothing. A save's or a checkpoint's turret is constructed
     * the same way, or restores the unit it held.
     */
    @Override
    protected void constructed(){
        World world = world();
        blockUnitId = world.ids().next();
        for(int i = 0; i < BLOCK_UNIT_DRAWS; i++) world.rand().nextLong();
        blockUnit = BlockUnits.create(this);
    }

    // ---------------------------------------------------------------- ammo

    /** {@code peekAmmo()}: the ammo the next shot fires, or null. */
    public Ammo peekAmmo(){
        return ammoSize == 0 ? null : type().ammo(ammoIds[ammoSize - 1]);
    }

    /** {@code useAmmo()}: takes one shot's ammo and returns its type. */
    public Ammo useAmmo(){
        if(cheating()) return peekAmmo();
        TurretType t = type();
        int top = ammoSize - 1;
        Ammo entry = t.ammo(ammoIds[top]);
        ammoAmounts[top] -= t.ammoPerShot;
        if(ammoAmounts[top] <= 0) ammoSize--;
        totalAmmo -= t.ammoPerShot;
        totalAmmo = Math.max(totalAmmo, 0);
        return entry;
    }

    /** {@code hasAmmo()}: an entry with a shot's worth at the top, moved there if another has one; and consumers met. */
    public boolean hasAmmo(){
        int perShot = type().ammoPerShot;
        // skip first entry if it has less than the required amount of ammo
        if(ammoSize >= 2 && ammoAmounts[ammoSize - 1] < perShot){
            for(int i = 0; i < ammoSize; i++){
                if(ammoAmounts[i] >= perShot){
                    swapAmmo(ammoSize - 1, i);
                    break;
                }
            }
        }
        // used for "side-ammo" like gas in some turrets
        if(!canConsume()) return false;
        return ammoSize > 0 && (ammoAmounts[ammoSize - 1] >= perShot || cheating());
    }

    /** {@code ammo.swap(a, b)}. */
    protected final void swapAmmo(int a, int b){
        int id = ammoIds[a], amount = ammoAmounts[a];
        ammoIds[a] = ammoIds[b];
        ammoAmounts[a] = ammoAmounts[b];
        ammoIds[b] = id;
        ammoAmounts[b] = amount;
    }

    /** {@code ammo.add(entry)}. */
    protected final void addAmmo(int id, int amount){
        if(ammoSize == ammoIds.length){
            ammoIds = Arrays.copyOf(ammoIds, Math.max(4, ammoSize * 2));
            ammoAmounts = Arrays.copyOf(ammoAmounts, ammoIds.length);
        }
        ammoIds[ammoSize] = id;
        ammoAmounts[ammoSize++] = amount;
    }

    // ---------------------------------------------------------------- ranges

    /** {@code fogRadius()} (Turret.java:426): {@code hasAmmo()} may move a loaded entry to the top, as in vanilla. */
    @Override
    public float fogRadius(){
        TurretType t = type();
        return (t.range + (hasAmmo() ? peekAmmo().rangeChange() : 0f)) / 8f * t.fogRadiusMultiplier;
    }

    @Override
    public float range(){
        Ammo ammo = peekAmmo();
        return ammo != null ? type().range + ammo.rangeChange() : type().range;
    }

    /**
     * {@code estimateDps()} (Turret.java:335), which the RTS AI weighs targets by: {@code hasAmmo()} may move a loaded
     * entry to the top, as in vanilla.
     */
    public float estimateDps(){
        if(!hasAmmo()) return 0f;
        Ammo ammo = peekAmmo();
        return type().shoot.shots / type().reload * 60f * (ammo == null ? 0f : ammo.type().estimateDPS()) * potentialEfficiency * timeScale;
    }

    public float minRange(){
        Ammo ammo = peekAmmo();
        return ammo != null ? type().minRange + ammo.minRangeChange() : type().minRange;
    }

    public float trackingRange(){
        return range() + type().trackingRange - type().range;
    }

    // ---------------------------------------------------------------- state

    @Override
    public boolean shouldConsume(){
        return isShooting || reloadCounter < type().reload;
    }

    @Override
    public BlockStatus status(double stateTick){
        if(enabled && !hasAmmo()) return BlockStatus.noInput;
        // BaseTurretBuild
        return activationTimer <= 0 ? super.status(stateTick) : BlockStatus.inactive;
    }

    @Override
    public boolean canConsume(){
        if(type().heatRequirement > 0 && heatReq <= 0f) return false;
        return super.canConsume();
    }

    @Override
    public void updateEfficiencyMultiplier(){
        super.updateEfficiencyMultiplier();
        TurretType t = type();
        if(t.heatRequirement > 0){
            efficiency *= Math.min(Math.max(heatReq / t.heatRequirement, cheating() ? 1f : 0f), t.maxHeatEfficiency);
        }
    }

    @Override
    public void placed(){
        super.placed();
        activationTimer = type().activationTime;
    }

    /**
     * {@code Turret.placeEnded(tile, builder, rotation, config)}: a rotating turret that construction finishes faces
     * its building's rotation ({@code rotdeg()}; the plan's {@code rotation} argument is unused, as in vanilla).
     * Construction calls it after {@link #placed()}, as {@code ConstructBlock.constructFinish} calls
     * {@code block.placeEnded}.
     */
    @Override
    public void placeEnded(Unit builder, int rotation, Object config){
        if(type().rotate) turretRotation = this.rotation * 90;
    }

    public float progress(){
        return Mathf.clamp(reloadCounter / type().reload);
    }

    public boolean isShooting(){
        return isShooting;
    }

    /** {@code controlled()}: a player aims this turret through its block unit. */
    public boolean controlled(){
        return blockUnit != null && blockUnit.isPlayer();
    }

    public boolean logicControlled(){
        return logicControlTime > 0;
    }

    public boolean isActive(){
        return (hasTarget() || wasShooting) && enabled && activationTimer <= 0;
    }

    public boolean charging(){
        return queuedBullets > 0 && type().shoot.firstShotDelay > 0;
    }

    public boolean shouldTurn(){
        return type().moveWhileCharging || !charging();
    }

    /** {@code target != null}. */
    public final boolean hasTarget(){
        return target != null || targetFire != -1;
    }

    /** {@code target = null}. */
    public final void clearTarget(){
        target = null;
        targetFire = -1;
    }

    // ---------------------------------------------------------------- the update

    @Override
    public void updateTile(){
        TurretType t = type();
        if(!validateTarget()) clearTarget();
        Unit unit = blockUnit;
        isShooting = t.alwaysShooting || (unit != null && unit.isPlayer() ? unit.isShooting : logicControlled() ? logicShooting : hasTarget());

        float warmupTarget = (isShooting && canConsume()) || charging() ? 1f : 0f;
        if(warmupTarget > 0 && !controlled()) warmupHold = 1f;
        if(warmupHold > 0f){
            warmupHold -= 1f / t.warmupMaintainTime;
            warmupTarget = 1f;
        }
        if(t.linearWarmup){
            shootWarmup = Mathf.approachDelta(shootWarmup, warmupTarget, t.shootWarmupSpeed * (warmupTarget > 0 ? efficiency : 1f));
        }else{
            shootWarmup = Mathf.lerpDelta(shootWarmup, warmupTarget, t.shootWarmupSpeed * (warmupTarget > 0 ? efficiency : 1f));
        }
        wasShooting = false;

        curRecoil = Mathf.approachDelta(curRecoil, 0, 1 / t.recoilTime);
        if(t.recoils > 0){
            if(curRecoils == null) curRecoils = new float[t.recoils];
            for(int i = 0; i < t.recoils; i++) curRecoils[i] = Mathf.approachDelta(curRecoils[i], 0, 1 / t.recoilTime);
        }
        heat = Mathf.approachDelta(heat, 0, 1 / t.cooldownTime);
        charge = charging() ? Mathf.approachDelta(charge, 1, 1 / t.shoot.firstShotDelay) : 0;

        if(unit != null) syncBlockUnit(unit);
        if(logicControlTime > 0) logicControlTime -= 1f;
        // Turret.java:544
        if(t.heatRequirement > 0) heatReq = Heat.calculate(this, sideHeat);
        // sync underlying rotation; 0-3 rotation is a shadowed field
        if(t.rotate) rotation = Mathf.mod(Mathf.round(turretRotation / 90f), 4);

        handleReload();

        // Turret.java:556
        if(com.xpdustry.shard.sim.unit.UnitCalls.rules(world()).fog){
            float newRange = hasAmmo() ? peekAmmo().rangeChange() : 0f;
            if(newRange != lastRangeChange){
                lastRangeChange = newRange;
                com.xpdustry.shard.sim.fog.FogControl.forceUpdate(this);
            }
        }

        if(activationTimer > 0){
            activationTimer -= 1f;
            return;
        }
        if(hasAmmo()){
            if(Float.isNaN(reloadCounter)) reloadCounter = 0;
            if(timer(t.timerTarget, hasTarget() ? t.newTargetInterval : t.targetInterval)) findTarget();
            if(validateTarget()){
                boolean canShoot;
                if(controlled()){
                    // player behavior
                    targetX = unit.aimX;
                    targetY = unit.aimY;
                    canShoot = unit.isShooting;
                }else if(logicControlled()){
                    canShoot = logicShooting;
                }else{
                    // default AI behavior
                    targetPosition();
                    if(Float.isNaN(turretRotation)) turretRotation = 0;
                    if(target != null){
                        canShoot = within(Targets.x(target), Targets.y(target), range() + Targets.hitSize(target) / 1.9f);
                    }else{
                        Vec2 fire = targetFirePosition();
                        canShoot = within(fire.x, fire.y, range());
                    }
                }
                if(!controlled() && unit != null){
                    unit.aimX = targetX;
                    unit.aimY = targetY;
                }
                float targetRot = Angles.angle(x(), y(), targetX, targetY);
                if(shouldTurn()) turnToTarget(targetRot);
                if(!t.alwaysShooting && Angles.angleDist(turretRotation, targetRot) < t.shootCone && canShoot){
                    wasShooting = true;
                    updateShooting();
                }
            }else{
                clearTarget();
            }
            if(t.alwaysShooting){
                wasShooting = true;
                updateShooting();
            }
        }
    }

    /** {@code validateTarget()}: a live enemy (or a damaged ally, healing), a fire, or a player's or processor's aim. */
    protected boolean validateTarget(){
        if(targetFire != -1) return true;
        return !Targets.invalidateTarget(target, canHeal() ? 0 : team, x(), y(), Float.MAX_VALUE) || controlled() || logicControlled();
    }

    protected boolean canHeal(){
        if(!type().targetHealing || !hasAmmo()) return false;
        Ammo ammo = peekAmmo();
        return ammo.type().collidesTeam && ammo.type().heals();
    }

    /** {@code findEnemy(range)}: the best unit by the ammo's or the turret's sort, else a building, as the turret targets. */
    protected Entity findEnemy(float range){
        TurretType t = type();
        Ammo ammo = peekAmmo();
        UnitSort sort = Sorts.of(world(), !Sorts.CLOSEST.equals(ammo.unitSort()) ? ammo.unitSort() : t.unitSort);
        if(t.targetAir && !t.targetGround){
            SpatialIndex index = SpatialIndex.of(world());
            return index == null ? null : index.bestEnemy(team, x(), y(), range, e -> !e.dead && !e.isGrounded(), sort);
        }
        boolean buildings = t.targetGround && t.targetBlocks && ammo.targetBlocks(), missiles = ammo.targetMissiles();
        return Targets.bestTarget(world(), team, x(), y(), range,
            e -> !e.dead && (e.isGrounded() || t.targetAir) && (!e.isGrounded() || t.targetGround) && (missiles || !e.params().timedKill()),
            b -> buildings && (t.targetUnderBlocks || !b.block().underBullets()), sort);
    }

    protected void findTarget(){
        float trackRange = trackingRange(), range = range();
        clearTarget();
        target = findEnemy(range);
        // find another target within the tracking range, but only if there's nothing else (always prioritize standard target)
        if(!Mathf.equal(trackRange, range) && target == null) target = findEnemy(trackRange);
        if(target == null && canHeal()){
            target = Targets.findAllyTile(world(), team, x(), y(), range, b -> b.damaged() && b != this);
        }
    }

    /** {@code targetPosition(target)}. */
    protected void targetPosition(){
        if(target != null) targetPosition(target, Targets.x(target), Targets.y(target));
        else if(targetFire != -1){
            Vec2 at = targetFirePosition();
            targetPosition(null, at.x, at.y);
        }
    }

    /**
     * {@code targetPosition(pos)}: where to aim to meet {@code pos} (at {@code (px, py)}; a unit, a building, or with
     * {@code pos} null a fire), leading a unit by the ammo's speed.
     */
    public void targetPosition(Entity pos, float px, float py){
        if(!hasAmmo()) return;
        TurretType t = type();
        BulletType bullet = peekAmmo().type();
        float ox = 0f, oy = 0f, dx = 0f, dy = 0f;
        if(pos instanceof Unit unit){
            dx = unit.deltaX;
            dy = unit.deltaY;
            // when delay is accurate, assume unit has moved by chargeTime already
            if(t.accurateDelay && !t.moveWhileCharging){
                ox = dx * t.shoot.firstShotDelay;
                oy = dy * t.shoot.firstShotDelay;
            }
        }
        if(t.predictTarget && bullet.speed >= 0.01f){
            Vec2 at = Predict.intercept(x(), y(), px + ox, py + oy, dx, dy, bullet.speed);
            targetX = at.x;
            targetY = at.y;
        }else{
            targetX = px;
            targetY = py;
        }
        if(targetX == 0 && targetY == 0){
            targetX = px;
            targetY = py;
        }
    }

    protected void turnToTarget(float targetRot){
        turretRotation = Angles.moveToward(turretRotation, targetRot, type().rotateSpeed * delta() * potentialEfficiency);
    }

    /** Where the fire target's object stands now ({@link Fires#objectPosition}). */
    public final Vec2 targetFirePosition(){
        Vec2 at = new Vec2();
        Fires.objectPosition(world(), targetFire, at);
        return at;
    }

    // ---------------------------------------------------------------- reload and cooling

    protected void handleReload(){
        TurretType t = type();
        // turret always reloads regardless of whether it's targeting something
        if((t.reloadWhileCharging || !charging()) && reloadCounter < t.reload){
            updateReload();
            updateCooling();
        }
    }

    protected void updateReload(){
        reloadCounter += delta() * ammoReloadMultiplier() * baseReloadSpeed();
    }

    /** {@code ReloadTurretBuild.updateCooling}: coolant speeds the reload by its heat capacity. */
    protected void updateCooling(){
        TurretType t = type();
        ConsumeLiquidBase coolant = t.coolant;
        if(coolant != null && coolant.efficiency(this) > 0 && efficiency > 0){
            float capacity;
            if(coolant instanceof ConsumeLiquidFilter filter){
                capacity = world().content().liquid(filter.consumed(this)).heatCapacity();
            }else{
                capacity = coolant.consumes(liquids.current()) ? world().content().liquid(liquids.current()).heatCapacity() : 0.4f;
            }
            float amount = coolant.amount * coolant.efficiency(this);
            coolant.update(this);
            reloadCounter += amount * edelta() * capacity * t.coolantMultiplier * ammoReloadMultiplier();
            Rand rand = world().rand();
            if(Mathf.chance(rand, 0.06 * amount)){
                // coolEffect.at(x + Mathf.range(size * tilesize / 2f), y + Mathf.range(size * tilesize / 2f))
                float half = block().size() * Footprint.TILE_SIZE / 2f;
                Mathf.range(rand, half);
                Mathf.range(rand, half);
            }
        }
    }

    protected float ammoReloadMultiplier(){
        return hasAmmo() ? peekAmmo().reloadMultiplier() : 1f;
    }

    protected float baseReloadSpeed(){
        return efficiency;
    }

    // ---------------------------------------------------------------- shooting

    protected void updateShooting(){
        TurretType t = type();
        if(reloadCounter >= t.reload && !charging() && shootWarmup >= t.minWarmup){
            shoot(peekAmmo());
            reloadCounter %= t.reload;
        }
    }

    /** {@code shoot(type)}: the pattern's bullets, the later ones queued on the world's timers, then the ammo. */
    protected void shoot(Ammo ammo){
        TurretType t = type();
        World world = world();
        if(t.shoot.firstShotDelay > 0){
            // chargeSound.at(bulletX, bulletY, Mathf.random(soundPitchMin, soundPitchMax))
            Mathf.random(world.rand(), t.soundPitchMin, t.soundPitchMax);
        }
        ShootPattern pattern = ammo.shootPattern() != null ? ammo.shootPattern() : t.shoot;
        BulletType bullet = ammo.type();
        pattern.shoot(world.rand(), barrelCounter, (xOffset, yOffset, angle, delay, mover) -> {
            queuedBullets++;
            int barrel = barrelCounter;
            if(delay > 0f){
                world.timers().run(delay, new DelayedBullet(this, bullet.id(), xOffset, yOffset, angle, mover, barrel));
            }else{
                bullet(bullet, xOffset, yOffset, angle, mover);
            }
        }, () -> barrelCounter++);
        if(t.consumeAmmoOnce) useAmmo();
    }

    /** {@code bullet(type, xOffset, yOffset, angleOffset, mover)} (Turret.java:785): one bullet leaves the barrel. */
    protected void bullet(BulletType type, float xOffset, float yOffset, float angleOffset, Mover mover){
        queuedBullets--;
        TurretType t = type();
        if(dead || (!t.consumeAmmoOnce && !hasAmmo())) return;
        World world = world();
        Rand rand = world.rand();
        float xSpread = Mathf.range(rand, t.xRand);
        float bulletX = x() + Angles.trnsx(turretRotation - 90, t.shootX + xOffset + xSpread, t.shootY + yOffset);
        float bulletY = y() + Angles.trnsy(turretRotation - 90, t.shootX + xOffset + xSpread, t.shootY + yOffset);
        float shootAngle = turretRotation + angleOffset + Mathf.range(rand, t.inaccuracy + type.inaccuracy);
        float baseLife = (1f - t.lifeRnd) + Mathf.random(rand, t.lifeRnd) + t.extraLife;
        float lifeScl = type.scaleLife
            ? Mathf.clamp((baseLife + t.scaleLifetimeOffset) * Mathf.dst(bulletX, bulletY, targetX, targetY) / type.range,
                minRange() / type.range, range() / type.range)
            : baseLife;
        float velocityScl = (1f - t.velocityRnd) + Mathf.random(rand, t.velocityRnd) + t.extraVelocity;
        Bullet created = type.create(world, this, this, team, bulletX, bulletY, shootAngle, -1f, velocityScl, lifeScl, mover,
            targetX, targetY, null);
        handleBullet(created, xOffset, yOffset, shootAngle - turretRotation);
        // (type.shootSound or shootSound).at(bulletX, bulletY, Mathf.random(soundPitchMin, soundPitchMax), volume)
        Mathf.random(rand, t.soundPitchMin, t.soundPitchMax);
        curRecoil = 1f;
        if(t.recoils > 0) curRecoils[barrelCounter % t.recoils] = 1f;
        heat = 1f;
        totalShots++;
        if(!t.consumeAmmoOnce) useAmmo();
    }

    /** {@code handleBullet}: what a kind does with a bullet it just fired; nothing for most. */
    protected void handleBullet(Bullet bullet, float offsetX, float offsetY, float angleOffset){
    }

    /**
     * A pattern's later shot ({@code Time.run(delay, …)} in {@code shoot}): fires from the barrel it was queued for.
     * Vanilla's closure holds the bullet type; this holds its content id.
     */
    public record DelayedBullet(TurretBuild turret, int bullet, float xOffset, float yOffset, float angle, Mover mover, int barrel)
        implements Timers.Task{
        @Override
        public void run(World world){
            // hack: make sure the barrel is the same as what it was when the bullet was queued to fire
            int prev = turret.barrelCounter;
            turret.barrelCounter = barrel;
            turret.bullet(world.bullets().types(world).get(bullet), xOffset, yOffset, angle, mover);
            turret.barrelCounter = prev;
        }
    }

    // ---------------------------------------------------------------- block unit

    /**
     * The block unit a player controls this turret through ({@code unit()}, Turret.java:440): made at construction with
     * the entity id the turret drew then, never added to the world; this syncs its stats, position and team with the
     * turret's, as vanilla's does. A read that must change nothing uses the field ({@link BlockUnits#at}).
     */
    public Unit blockUnit(){
        if(blockUnit == null) blockUnit = BlockUnits.create(this);
        syncBlockUnit(blockUnit);
        return blockUnit;
    }

    /** {@code unit.tile(this); unit.rotation(rotation); unit.team(team)}; {@code dead()} is the turret's. */
    private void syncBlockUnit(Unit unit){
        BlockUnits.tile(unit, this);
        unit.dead = dead;
        unit.rotation = turretRotation;
        unit.team = team;
    }

    // ---------------------------------------------------------------- logic

    @Override
    public void control(LAccess type, double p1, double p2, double p3, double p4){
        if(type == LAccess.shoot && !(blockUnit != null && blockUnit.isPlayer())){
            targetX = (float)p1 * Footprint.TILE_SIZE;
            targetY = (float)p2 * Footprint.TILE_SIZE;
            logicControlTime = LOGIC_CONTROL_COOLDOWN;
            logicShooting = !Mathf.zero(p3);
        }
        super.control(type, p1, p2, p3, p4);
    }

    @Override
    public void control(LAccess type, Object p1, double p2, double p3, double p4){
        if(type == LAccess.shootp && !(blockUnit != null && blockUnit.isPlayer())){
            logicControlTime = LOGIC_CONTROL_COOLDOWN;
            logicShooting = !Mathf.zero(p2);
            if(p1 instanceof Unit || p1 instanceof Building) targetPosition((Entity)p1, Targets.x((Entity)p1), Targets.y((Entity)p1));
        }
        super.control(type, p1, p2, p3, p4);
    }

    @Override
    public double sense(LAccess sensor){
        return switch(sensor){
            case ammo -> totalAmmo;
            case ammoCapacity -> type().maxAmmo;
            case rotation -> turretRotation;
            case shootX -> targetX / Footprint.TILE_SIZE;
            case shootY -> targetY / Footprint.TILE_SIZE;
            case shooting -> isShooting ? 1 : 0;
            case progress -> progress();
            // BuildingComp: ControlBlock c && c.isControlled() ? ctrlPlayer : 0
            case controlled -> controlled() ? BlockUnits.CTRL_PLAYER : 0;
            default -> super.sense(sensor);
        };
    }

    @Override
    public Object senseObject(LAccess sensor){
        if(sensor == LAccess.controller) return controlled() ? blockUnit.player() : this;
        return super.senseObject(sensor);
    }

    // ---------------------------------------------------------------- state

    @Override
    public void write(CheckpointOut out){
        out.f("turretRotation", turretRotation).f("activationTimer", activationTimer).f("reloadCounter", reloadCounter)
            .ints("ammoIds", ammoIds, ammoSize).ints("ammoAmounts", ammoAmounts, ammoSize).i("ammoSize", ammoSize).i("totalAmmo", totalAmmo)
            .f("curRecoil", curRecoil).f("heat", heat).f("logicControlTime", logicControlTime)
            .floats("curRecoils", curRecoils)
            .f("shootWarmup", shootWarmup).f("charge", charge).f("warmupHold", warmupHold).i("totalShots", totalShots)
            .i("barrelCounter", barrelCounter).bool("logicShooting", logicShooting).f("lastRangeChange", lastRangeChange).ref("target", target).i("targetFire", targetFire)
            .f("targetX", targetX).f("targetY", targetY).bool("wasShooting", wasShooting).bool("isShooting", isShooting)
            .i("queuedBullets", queuedBullets).f("heatReq", heatReq).floats("sideHeat", sideHeat).i("blockUnitId", blockUnitId).ref("blockUnit", blockUnit);
    }

    @Override
    public void read(CheckpointIn in){
        turretRotation = in.f("turretRotation");
        activationTimer = in.f("activationTimer");
        reloadCounter = in.f("reloadCounter");
        ammoIds = in.ints("ammoIds");
        ammoAmounts = in.ints("ammoAmounts");
        ammoSize = in.i("ammoSize");
        if(ammoIds.length != ammoSize || ammoAmounts.length != ammoSize) throw new CheckpointException("turret ammo entries differ in length");
        totalAmmo = in.i("totalAmmo");
        curRecoil = in.f("curRecoil");
        heat = in.f("heat");
        logicControlTime = in.f("logicControlTime");
        curRecoils = in.floats("curRecoils");
        shootWarmup = in.f("shootWarmup");
        charge = in.f("charge");
        warmupHold = in.f("warmupHold");
        totalShots = in.i("totalShots");
        barrelCounter = in.i("barrelCounter");
        logicShooting = in.bool("logicShooting");
        lastRangeChange = in.f("lastRangeChange");
        target = in.ref("target", Entity.class);
        targetFire = in.i("targetFire");
        targetX = in.f("targetX");
        targetY = in.f("targetY");
        wasShooting = in.bool("wasShooting");
        isShooting = in.bool("isShooting");
        queuedBullets = in.i("queuedBullets");
        heatReq = in.f("heatReq");
        in.floats("sideHeat", sideHeat);
        blockUnitId = in.i("blockUnitId");
        blockUnit = in.ref("blockUnit", Unit.class);
    }

    /** The block unit's stats are the turret's, which the unit's own restore took from its type. */
    @Override
    public void restored(){
        if(blockUnit != null) BlockUnits.stats(blockUnit, this);
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (TurretBuild)into;
        out.turretRotation = turretRotation;
        out.activationTimer = activationTimer;
        out.reloadCounter = reloadCounter;
        out.ammoIds = ammoIds.clone();
        out.ammoAmounts = ammoAmounts.clone();
        out.ammoSize = ammoSize;
        out.totalAmmo = totalAmmo;
        out.curRecoil = curRecoil;
        out.heat = heat;
        out.logicControlTime = logicControlTime;
        out.curRecoils = curRecoils == null ? null : curRecoils.clone();
        out.shootWarmup = shootWarmup;
        out.charge = charge;
        out.warmupHold = warmupHold;
        out.totalShots = totalShots;
        out.barrelCounter = barrelCounter;
        out.logicShooting = logicShooting;
        out.lastRangeChange = lastRangeChange;
        out.target = context.map(target);
        out.targetFire = targetFire;
        out.targetX = targetX;
        out.targetY = targetY;
        out.wasShooting = wasShooting;
        out.isShooting = isShooting;
        out.queuedBullets = queuedBullets;
        out.heatReq = heatReq;
        out.sideHeat = sideHeat.clone();
        out.blockUnitId = blockUnitId;
        out.blockUnit = context.map(blockUnit);
        out.type = type;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(turretRotation).f(activationTimer).f(reloadCounter).i(ammoSize);
        for(int i = 0; i < ammoSize; i++) hasher.i(ammoIds[i]).i(ammoAmounts[i]);
        hasher.i(totalAmmo).f(curRecoil).f(heat).f(logicControlTime).bool(curRecoils != null);
        if(curRecoils != null) hasher.floats(curRecoils, 0, curRecoils.length);
        hasher.f(shootWarmup).f(charge).f(warmupHold).i(totalShots).i(barrelCounter).bool(logicShooting).f(lastRangeChange)
            .i(target == null ? -1 : target.id()).i(targetFire).f(targetX).f(targetY).bool(wasShooting).bool(isShooting)
            .i(queuedBullets).f(heatReq).floats(sideHeat).i(blockUnitId).i(blockUnit == null ? -1 : blockUnit.id());
        // no world list holds the block unit, so its state is hashed here, possessed or not: the aim and shooting flag
        // the turret fires by, and its controller (review 40)
        if(blockUnit != null) blockUnit.hashState(hasher);
    }
}
