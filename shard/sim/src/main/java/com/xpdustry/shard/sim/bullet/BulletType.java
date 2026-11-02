package com.xpdustry.shard.sim.bullet;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * What a bullet does: vanilla's {@code BulletType} (BulletType.java), its parameters read once from the content's
 * {@link BulletDef} and its hooks as methods. This class is the {@code bullet-type} kind and the base of the others;
 * a kind with behaviour of its own is a subclass registered with {@link BulletKind}, as {@code FlakBulletType} is. A
 * kind with no registered class runs as its nearest registered ancestor and is listed in
 * {@link BulletTypes#unsupported()}.
 *
 * <p>Instances are immutable once {@link #load loaded} and shared by every world of the same content
 * ({@link BulletTypes}); per-bullet state lives in the {@link Bullets} row a {@link Bullet} handle points at. Every
 * random draw vanilla's server makes is kept, sounds' pitches included; effects and sounds themselves are not, and a
 * hit or despawn effect is published as a {@link BulletEvent} instead.
 *
 * <p>Not ported, because no system owns them yet: fires ({@code makeFire}, {@code incendAmount}) and puddles go to the
 * {@link Hazards} seam, statuses to {@link StatusApply}; the {@code data} field (no ported type reads it), trails
 * (client only) and {@code Events} (no listener on a server). Bullet damage to units goes through
 * {@link Unit#damage}, which vanilla's {@code ShieldComp} would have intercepted for force-field abilities (WP62).
 */
@Registry
public class BulletType{
    static final KindKey CONSTRUCT = KindKey.of("construct-block");

    private BulletDef def;
    private ContentEpoch content;
    private boolean supported = true;

    // core
    public float speed, damage, lifetime, drag, hitSize, accel, angleOffset, randomAngleOffset, createChance;
    public boolean ignoreSpawnAngle;
    public float velocityScaleRandMin, velocityScaleRandMax, lifeScaleRandMin, lifeScaleRandMax;
    public float buildingDamageMultiplier, shieldDamageMultiplier, recoil, inaccuracy, range, optimalLifeFract;
    public boolean keepVelocity, scaleKeepVelocity, killShooter, instantDisappear, scaleLife, hittable, reflectable, absorbable;
    public float spawnBulletRandomSpread;
    // collision
    public boolean collides, collidesTiles, collidesGround, collidesAir, collidesTeam, collideFloor, collideTerrain;
    public boolean pierce, pierceBuilding, removeAfterPierce, pierceArmor, laserBullet, hitUnder, sticky, laserAbsorb;
    public int pierceCap, pierceFragCap;
    public float pierceDamageFactor, stickyExtraLifetime;
    // movement
    public float homingPower, homingDelay, homingRange, followAimSpeed, weaveMag, weaveScale, rotateSpeed;
    public boolean weaveRandom, circleShooter;
    public float circleShooterRadius, circleShooterRadiusSmooth, circleShooterRotateSpeed;
    public float trailChance, trailInterval, trailSpread, trailMinVelocity;
    public int intervalBullets;
    public float intervalDelay, bulletInterval, intervalRandomSpread, intervalAngle, intervalSpread;
    // hits
    public float hitSoundPitchRange, healPercent, healAmount, lifesteal, knockback, maxDamageFraction;
    public boolean makeFire, impact, despawnHit;
    public boolean fragOnHit, delayFrags, fragOnAbsorb, fragOnDespawn;
    public int fragBullets;
    public float fragOffsetMin, fragOffsetMax, fragRandomSpread, fragAngle, fragSpread, fragVelocityMin, fragVelocityMax,
        fragLifeMin, fragLifeMax;
    public int puddles, incendAmount, despawnUnitCount;
    public float puddleRange, puddleAmount, incendChance, incendSpread, despawnUnitChance, despawnUnitRadius;
    public boolean faceOutwards;
    public float suppressionRange, suppressionDuration, suppressionEffectChance;
    public float splashDamage, splashDamageRadius, armorMultiplier, blockArmorMultiplier;
    public boolean splashDamagePierce, scaledSplashDamage;
    public float statusDuration, statusChance;
    public int lightning, lightningLength, lightningLengthRand;
    public float lightningDamage, lightningCone, lightningAngle;
    // references
    public StatusDef status;
    public UnitDef spawnUnit, despawnUnit;
    public LiquidDef puddleLiquid;
    public BulletType fragBullet, intervalBullet, lightningType;
    public List<BulletType> spawnBullets = List.of();

    /** For {@link BulletTypes}, which then {@link #load loads} it. */
    public BulletType(){
    }

    /** Reads the parameters from {@code def}, as {@code BulletType.init()} left them (the pack is baked). */
    protected void load(BulletDef def, ContentEpoch content){
        this.def = def;
        this.content = content;
        speed = def.speed();
        damage = def.damage();
        lifetime = def.lifetime();
        drag = def.drag();
        hitSize = def.hitSize();
        accel = def.accel();
        angleOffset = def.angleOffset();
        randomAngleOffset = def.randomAngleOffset();
        createChance = def.createChance();
        ignoreSpawnAngle = def.ignoreSpawnAngle();
        velocityScaleRandMin = def.velocityScaleRandMin();
        velocityScaleRandMax = def.velocityScaleRandMax();
        lifeScaleRandMin = def.lifeScaleRandMin();
        lifeScaleRandMax = def.lifeScaleRandMax();
        buildingDamageMultiplier = def.buildingDamageMultiplier();
        shieldDamageMultiplier = def.shieldDamageMultiplier();
        recoil = def.recoil();
        inaccuracy = def.inaccuracy();
        range = def.range();
        optimalLifeFract = def.optimalLifeFract();
        keepVelocity = def.keepVelocity();
        scaleKeepVelocity = def.scaleKeepVelocity();
        killShooter = def.killShooter();
        instantDisappear = def.instantDisappear();
        scaleLife = def.scaleLife();
        hittable = def.hittable();
        reflectable = def.reflectable();
        absorbable = def.absorbable();
        spawnBulletRandomSpread = def.spawnBulletRandomSpread();
        collides = def.collides();
        collidesTiles = def.collidesTiles();
        collidesGround = def.collidesGround();
        collidesAir = def.collidesAir();
        collidesTeam = def.collidesTeam();
        collideFloor = def.collideFloor();
        collideTerrain = def.collideTerrain();
        pierce = def.pierce();
        pierceBuilding = def.pierceBuilding();
        removeAfterPierce = def.removeAfterPierce();
        pierceArmor = def.pierceArmor();
        laserBullet = def.laserBullet();
        hitUnder = def.hitUnder();
        sticky = def.sticky();
        laserAbsorb = def.laserAbsorb();
        pierceCap = def.pierceCap();
        pierceFragCap = def.pierceFragCap();
        pierceDamageFactor = def.pierceDamageFactor();
        stickyExtraLifetime = def.stickyExtraLifetime();
        homingPower = def.homingPower();
        homingDelay = def.homingDelay();
        homingRange = def.homingRange();
        followAimSpeed = def.followAimSpeed();
        weaveMag = def.weaveMag();
        weaveScale = def.weaveScale();
        weaveRandom = def.weaveRandom();
        rotateSpeed = def.rotateSpeed();
        circleShooter = def.circleShooter();
        circleShooterRadius = def.circleShooterRadius();
        circleShooterRadiusSmooth = def.circleShooterRadiusSmooth();
        circleShooterRotateSpeed = def.circleShooterRotateSpeed();
        trailChance = def.trailChance();
        trailInterval = def.trailInterval();
        trailSpread = def.trailSpread();
        trailMinVelocity = def.trailMinVelocity();
        intervalBullets = def.intervalBullets();
        intervalDelay = def.intervalDelay();
        bulletInterval = def.bulletInterval();
        intervalRandomSpread = def.intervalRandomSpread();
        intervalAngle = def.intervalAngle();
        intervalSpread = def.intervalSpread();
        hitSoundPitchRange = def.hitSoundPitchRange();
        healPercent = def.healPercent();
        healAmount = def.healAmount();
        lifesteal = def.lifesteal();
        knockback = def.knockback();
        maxDamageFraction = def.maxDamageFraction();
        makeFire = def.makeFire();
        impact = def.impact();
        despawnHit = def.despawnHit();
        fragOnHit = def.fragOnHit();
        delayFrags = def.delayFrags();
        fragOnAbsorb = def.fragOnAbsorb();
        fragOnDespawn = def.fragOnDespawn();
        fragBullets = def.fragBullets();
        fragOffsetMin = def.fragOffsetMin();
        fragOffsetMax = def.fragOffsetMax();
        fragRandomSpread = def.fragRandomSpread();
        fragAngle = def.fragAngle();
        fragSpread = def.fragSpread();
        fragVelocityMin = def.fragVelocityMin();
        fragVelocityMax = def.fragVelocityMax();
        fragLifeMin = def.fragLifeMin();
        fragLifeMax = def.fragLifeMax();
        puddles = def.puddles();
        incendAmount = def.incendAmount();
        despawnUnitCount = def.despawnUnitCount();
        puddleRange = def.puddleRange();
        puddleAmount = def.puddleAmount();
        incendChance = def.incendChance();
        incendSpread = def.incendSpread();
        despawnUnitChance = def.despawnUnitChance();
        despawnUnitRadius = def.despawnUnitRadius();
        faceOutwards = def.faceOutwards();
        suppressionRange = def.suppressionRange();
        suppressionDuration = def.suppressionDuration();
        suppressionEffectChance = def.suppressionEffectChance();
        splashDamage = def.splashDamage();
        splashDamageRadius = def.splashDamageRadius();
        armorMultiplier = def.armorMultiplier();
        blockArmorMultiplier = def.blockArmorMultiplier();
        splashDamagePierce = def.splashDamagePierce();
        scaledSplashDamage = def.scaledSplashDamage();
        statusDuration = def.statusDuration();
        statusChance = def.statusChance();
        lightning = def.lightning();
        lightningLength = def.lightningLength();
        lightningLengthRand = def.lightningLengthRand();
        lightningDamage = def.lightningDamage();
        lightningCone = def.lightningCone();
        lightningAngle = def.lightningAngle();
        status = def.status();
        spawnUnit = def.spawnUnit();
        despawnUnit = def.despawnUnit();
        puddleLiquid = def.puddleLiquid();
    }

    /** Resolves the bullets it refers to, once every type of the content is loaded. */
    protected void link(BulletTypes types){
        fragBullet = types.get(def.fragBullet());
        intervalBullet = types.get(def.intervalBullet());
        lightningType = types.get(def.lightningType());
        var spawns = new ArrayList<BulletType>();
        for(BulletDef spawn : def.spawnBullets()) spawns.add(types.get(spawn));
        spawnBullets = List.copyOf(spawns);
    }

    void unsupported(){
        supported = false;
    }

    public BulletDef def(){
        return def;
    }

    public int id(){
        return def.id();
    }

    public ContentEpoch content(){
        return content;
    }

    /**
     * {@code estimateDPS()} (BulletType.java:422) with its overrides (continuous, point-laser, laser, lightning and
     * multi bullets): what the RTS AI weighs a turret by ({@code Turret.estimateDps}). Vanilla caches the base estimate
     * in {@code cachedDps}; it depends only on the content, so it is computed again here.
     */
    public float estimateDPS(){
        return estimateDPS(def, content);
    }

    static float estimateDPS(BulletDef b, ContentEpoch content){
        var f = b.fields();
        if(b.isA(KindKey.of("continuous-bullet-type")) && f.b("continuous") || b.isA(KindKey.of("point-laser-bullet-type"))){
            // assume firing duration is about 100, and that it pierces 3 blocks or units
            return f.f("damage") * 100f / f.f("damageInterval") * 3f;
        }
        if(b.isA(KindKey.of("laser-bullet-type"))) return baseDPS(b, content) * 3f;
        if(b.isA(KindKey.of("lightning-bullet-type"))) return baseDPS(b, content) * Math.max(f.i("lightningLength") / 10f, 1);
        if(b.isA(KindKey.of("multi-bullet-type"))){
            float sum = 0f;
            for(Object other : f.list("bullets")) sum += estimateDPS(content.bullet((String)other), content);
            return sum;
        }
        return baseDPS(b, content);
    }

    /** {@code BulletType.estimateDPS()} itself. */
    private static float baseDPS(BulletDef b, ContentEpoch content){
        var f = b.fields();
        String spawn = f.s("spawnUnit"), despawn = f.s("despawnUnit");
        if(spawn != null) return content.unit(spawn).fields().f("dpsEstimate");
        if(despawn != null) return content.unit(despawn).fields().f("dpsEstimate");
        int pierceCap = f.i("pierceCap");
        float sum = (f.f("damage") + f.f("splashDamage") * 0.75f) * (f.b("pierce") ? pierceCap == -1 ? 2 : Mathf.clamp(pierceCap, 1, 2) : 1f);
        String frag = f.s("fragBullet");
        if(frag != null && !frag.equals(b.name())) sum += estimateDPS(content.bullet(frag), content) * f.i("fragBullets") / 2f;
        for(Object other : f.list("spawnBullets")) sum += estimateDPS(content.bullet((String)other), content);
        return sum;
    }

    /** Whether this class ports the type's kind, rather than an ancestor standing in for it. */
    public boolean supported(){
        return supported;
    }

    /** {@code heals()}. */
    public boolean heals(){
        return healPercent > 0 || healAmount > 0;
    }

    // ---------------------------------------------------------------- creation

    /** {@code create(owner, x, y, angle)}: from a unit or a building, on its team. */
    public Bullet create(World world, Entity owner, int team, float x, float y, float angle){
        return create(world, owner, owner, team, x, y, angle, -1f, 1f, 1f, null, -1f, -1f, null);
    }

    /** {@code create(parent, x, y, angle)}: a bullet a bullet makes, with its owner, shooter and team. */
    public Bullet create(Bullet parent, float x, float y, float angle){
        return create(parent, x, y, angle, 1f, 1f);
    }

    /** {@code create(parent, x, y, angle, velocityScl, lifeScale)}. */
    public Bullet create(Bullet parent, float x, float y, float angle, float velocityScl, float lifeScale){
        return create(parent.world(), parent.owner(), parent.shooter(), parent.team(), x, y, angle, -1f, velocityScl, lifeScale, null, -1f, -1f, null);
    }

    /** {@code create(owner, team, x, y, angle, damage, velocityScl, lifetimeScl, data)}, with no data. */
    public Bullet create(World world, Entity owner, int team, float x, float y, float angle, float damage, float velocityScl, float lifetimeScl){
        return create(world, owner, owner, team, x, y, angle, damage, velocityScl, lifetimeScl, null, -1f, -1f, null);
    }

    /**
     * Vanilla's full {@code create} (BulletType.java:924): a new bullet with a pooled id, its type's {@code init}
     * run, or null when {@code createChance} fails or the type spawns a unit instead. {@code damage < 0} means the
     * type's; {@code aimX} and {@code aimY} are -1 for none, and a building {@code target} is aimed at directly.
     */
    public Bullet create(World world, Entity owner, Entity shooter, int team, float x, float y, float angle, float damage,
                         float velocityScl, float lifetimeScl, Mover mover, float aimX, float aimY, Entity target){
        Rand rand = world.rand();
        angle += angleOffset + Mathf.range(rand, randomAngleOffset);
        if(!Mathf.chance(rand, createChance)) return null;
        if(ignoreSpawnAngle) angle = 0;
        if(spawnUnit != null){
            Unit spawned = Unit.create(world, spawnUnit, team);
            spawned.set(x, y);
            spawned.rotation = angle;
            // immediately at top speed, since it was launched
            if(spawnUnit.missileAccelTime() <= 0f) spawned.vel.trns(angle, spawnUnit.speed());
            // assign unit owner; a turret's block unit as the shooter is WP60's
            if(spawned.controller instanceof com.xpdustry.shard.sim.ai.MissileAi ai && shooter instanceof Unit unit) ai.shooter = unit;
            spawned.add();
            CombatEvent.unitSpawned(spawned);
            // since bullet init is never called, handle killing the shooter here
            if(killShooter) Targets.kill(owner);
            return null;
        }
        Bullets bullets = world.bullets();
        int row = bullets.obtain(id(), team, x, y);
        var bullet = new Bullet(world, bullets, row);
        bullets.owner[row] = owner;
        bullets.shooter[row] = shooter == null ? owner : shooter;
        bullets.time[row] = 0f;
        bullets.originX[row] = x;
        bullets.originY[row] = y;
        if(!(aimX == -1f && aimY == -1f)){
            bullets.aimTile[row] = target instanceof Building building ? building.tile() : tileWorld(world, aimX, aimY);
        }
        bullets.aimX[row] = aimX;
        bullets.aimY[row] = aimY;
        float velocityRandom = velocityScaleRandMin != 1f || velocityScaleRandMax != 1f ? Mathf.random(rand, velocityScaleRandMin, velocityScaleRandMax) : 1f;
        bullet.initVel(angle, speed * velocityScl * velocityRandom);
        float lifeRandom = lifeScaleRandMin != 1f || lifeScaleRandMax != 1f ? Mathf.random(rand, lifeScaleRandMin, lifeScaleRandMax) : 1f;
        bullets.lifetime[row] = lifetime * lifetimeScl * lifeRandom;
        bullets.hitSize[row] = hitSize;
        bullets.mover[row] = mover;
        bullets.damage[row] = (damage < 0 ? this.damage : damage) * damageMultiplier(bullet);
        bullets.buildingDamageMultiplier[row] = buildingDamageMultiplier;
        // add(): BulletComp.add runs init before HitboxComp.add records the (already set) last position
        init(bullet);
        if(keepVelocity && owner instanceof Unit unit){
            Bullets t = bullet.table();
            int r = bullet.row();
            float len = Mathf.len(t.velX[r], t.velY[r]);
            t.velX[r] += unit.vel.x;
            t.velY[r] += unit.vel.y;
            if(scaleKeepVelocity){
                float newLen = Mathf.len(t.velX[r], t.velY[r]);
                // only reduce lifetime, never add
                if(newLen > 0f) t.lifetime[r] *= Math.min(1f, len / newLen);
            }
        }
        return bullet;
    }

    /** {@code world.tileWorld(x, y)} as a tile position, or -1 outside the map. */
    static int tileWorld(World world, float x, float y){
        int tx = Math.round(x / 8f), ty = Math.round(y / 8f);
        return world.tiles().inBounds(tx, ty) ? world.tiles().pos(tx, ty) : -1;
    }

    /**
     * {@code createNet}: {@code Call.createBullet}, which runs vanilla's handler here ({@code create} with no owner)
     * and then sends the packet, so the event follows the bullet.
     */
    public Bullet createNet(World world, int team, float x, float y, float angle, float damage, float velocityScl, float lifetimeScl){
        Bullet bullet = create(world, null, null, team, x, y, angle, damage, velocityScl, lifetimeScl, null, -1f, -1f, null);
        world.publish(new BulletEvent.Created(id(), team, x, y, angle, damage, velocityScl, lifetimeScl));
        return bullet;
    }

    // ---------------------------------------------------------------- hooks

    /** {@code init(b)}, from {@code add()}: kill the shooter, disappear at once, make the spawn bullets. */
    public void init(Bullet b){
        if(killShooter) Targets.kill(b.owner());
        if(instantDisappear) b.time(lifetime + 1f);
        for(BulletType spawn : spawnBullets){
            spawn.create(b, b.x(), b.y(), b.rotation() + Mathf.range(b.world().rand(), spawnBulletRandomSpread));
        }
    }

    /** {@code update(b)}: homing, weaving, trail effects, interval bullets. Trails are client-side. */
    public void update(Bullet b){
        updateHoming(b);
        updateWeaving(b);
        updateTrailEffects(b);
        updateBulletInterval(b);
    }

    public void updateBulletInterval(Bullet b){
        if(intervalBullet != null && b.time() >= intervalDelay && b.timer(2, bulletInterval)){
            float angle = b.rotation();
            Rand rand = b.world().rand();
            for(int i = 0; i < intervalBullets; i++){
                intervalBullet.create(b, b.x(), b.y(), angle + Mathf.range(rand, intervalRandomSpread) + intervalAngle + ((i - (intervalBullets - 1f) / 2f) * intervalSpread));
            }
        }
    }

    public void updateHoming(Bullet b){
        World world = b.world();
        if(homingPower > 0.0001f && b.time() >= homingDelay){
            float realAimX = b.aimX() < 0 ? b.x() : b.aimX();
            float realAimY = b.aimY() < 0 ? b.y() : b.aimY();
            Entity target;
            Building aimed = b.aimTile() < 0 ? null : world.buildings().at(b.aimTile());
            SpatialIndex index = SpatialIndex.of(world);
            BuildingTargets buildings = BuildingTargets.of(world);
            if(aimed != null && aimed.team != b.team() && collidesGround && !b.hasCollided(aimed.id())){
                target = aimed;
            }else if(index == null){
                target = null;
            }else if(heals()){
                // Units.closestTarget(null, …, sourceTeam = b.team, …): every team's units, homing on allies
                target = index.closestTarget(Targets.ANY_TEAM, realAimX, realAimY, homingRange,
                    e -> Targets.checkTarget(e, collidesAir, collidesGround) && e.team != b.team() && !b.hasCollided(e.id()),
                    t -> collidesGround && (t.team != b.team() || t.damaged()) && !b.hasCollided(t.id()), b.team(), buildings);
            }else{
                target = index.closestTarget(b.team(), realAimX, realAimY, homingRange,
                    e -> e != null && Targets.checkTarget(e, collidesAir, collidesGround) && !b.hasCollided(e.id()),
                    t -> t != null && collidesGround && !b.hasCollided(t.id()), -1, buildings);
            }
            if(target != null){
                float angle = Angles.moveToward(b.rotation(), b.angleTo(Targets.x(target), Targets.y(target)), homingPower * 50f);
                b.vel(b.vel().setAngle(angle));
            }
        }
        if(followAimSpeed > 0f && b.shooter() instanceof Unit u){
            float angle = b.angleTo(u.aimX, u.aimY);
            Vec2 vel = b.vel();
            b.vel(vel.setAngle(Angles.moveToward(vel.angle(), angle, followAimSpeed)));
        }
    }

    public void updateWeaving(Bullet b){
        if(weaveMag != 0){
            // the angle is computed in doubles, then Vec2.rotateRadExact turns by it with Math.cos and Math.sin
            float sign = weaveRandom ? (Mathf.randomSeed(b.id(), 0, 1) == 1 ? -1 : 1) : 1f;
            float radians = (float)StrictMath.sin((b.time() + Math.PI * weaveScale / 2f) / weaveScale) * weaveMag * sign * Mathf.degRad;
            Vec2 vel = b.vel();
            float cos = (float)StrictMath.cos(radians), sin = (float)StrictMath.sin(radians);
            b.vel(vel.x * cos - vel.y * sin, vel.x * sin + vel.y * cos);
        }
        if(rotateSpeed != 0){
            b.vel(b.vel().rotate(rotateSpeed));
        }
        if(circleShooter && b.owner() instanceof Unit h && h.isValid()){
            Vec2 to = new Vec2(h.x, h.y).sub(b.x(), b.y());
            to.rotate(90f * Mathf.lerp(0f, 1f, 1f - Mathf.clamp((to.len() - circleShooterRadius) / circleShooterRadiusSmooth)));
            b.vel(b.vel().add(to.limit(speed * circleShooterRotateSpeed)).limit(speed));
        }
    }

    /** The trail effects' draws: a chance per tick, then a spread; the effects themselves are client-side. */
    public void updateTrailEffects(Bullet b){
        Bullets t = b.table();
        int r = b.row();
        float vx = t.velX[r], vy = t.velY[r];
        boolean canSpawn = trailMinVelocity <= 0f || vx * vx + vy * vy >= trailMinVelocity * trailMinVelocity;
        Rand rand = b.world().rand();
        if(trailChance > 0 && canSpawn){
            if(Mathf.chanceDelta(rand, trailChance)) trailSpread(rand);
        }
        if(trailInterval > 0f && canSpawn){
            if(b.timer(0, trailInterval)) trailSpread(rand);
        }
    }

    private void trailSpread(Rand rand){
        // Tmp.v1.rnd(Mathf.random(trailSpread)): a length, then a direction
        if(trailSpread > 0){
            float length = Mathf.random(rand, trailSpread);
            new Vec2().setToRandomDirection(rand).scl(length);
        }
    }

    /** {@code testCollision(b, tile)}: a healing bullet passes over healthy allies. */
    public boolean testCollision(Bullet b, Building tile){
        return !heals() || tile.team != b.team() || tile.healthf() < 1f;
    }

    /** {@code hitTile}: {@code direct} is false for an indirect hit on a building already damaged. */
    public void hitTile(Bullet b, Building build, float x, float y, float initialHealth, boolean direct){
        World world = b.world();
        if(makeFire && build.team != b.team()) Hazards.of(world).fire(build.tile());
        if(heals() && build.team == b.team() && !build.block().isA(CONSTRUCT)){
            // healEffect.at; healSound.at(build, 1f + Mathf.range(0.1f), volume)
            build.heal(healPercent / 100f * build.maxHealth + healAmount);
            Mathf.range(world.rand(), 0.1f);
            hit(b);
        }else if(build.team != b.team() && direct){
            hit(b);
            if(lifesteal > 0f) Targets.heal(b.owner(), Math.max(Math.min(build.health, damage), 0) * lifesteal);
        }
        handlePierce(b, initialHealth, x, y);
    }

    /** {@code hitEntity(b, entity, health)} for a unit, the only entity a bullet collides with. */
    public void hitEntity(Bullet b, Unit unit, float health){
        float damage = b.damage();
        float shield = Math.max(unit.shield, 0f);
        if(maxDamageFraction > 0){
            float cap = unit.maxHealth * maxDamageFraction + shield;
            damage = Math.min(damage, cap);
            // cap health to effective health for handlePierce to handle it properly
            health = Math.min(health, cap);
        }else{
            health += shield;
        }
        if(lifesteal > 0f) Targets.heal(b.owner(), Math.max(Math.min(unit.health, damage), 0) * lifesteal);
        if(pierceArmor){
            unit.damagePierce(damage);
        }else if(armorMultiplier != 1){
            unit.damageArmorMult(damage, armorMultiplier);
        }else{
            unit.damage(damage);
        }
        float knockbackMultiplier = Targets.knockbackMultiplier(unit);
        if(knockbackMultiplier > 0f){
            Vec2 push = new Vec2(unit.x, unit.y).sub(b.x(), b.y()).nor().scl(knockback * 80f * knockbackMultiplier);
            if(impact) push.setAngle(b.rotation() + (knockback < 0 ? 180f : 0f));
            unit.impulse(push.x, push.y);
        }
        if(Mathf.chance(b.world().rand(), statusChance)) StatusApply.of(b.world()).apply(unit, status, statusDuration);
        handlePierce(b, health, unit.x, unit.y);
    }

    /** {@code handlePierce}: each pierce takes some damage off; at none left, the bullet is used up. */
    public void handlePierce(Bullet b, float initialHealth, float x, float y){
        float sub = Mathf.zero(pierceDamageFactor) ? 0f : Math.max(initialHealth * pierceDamageFactor, 0);
        // subtract health from each consecutive pierce
        b.damage(b.damage() - (Float.isNaN(sub) ? b.damage() : Math.min(b.damage(), sub)));
        if(removeAfterPierce && b.damage() <= 0){
            b.hit(true);
            b.remove();
        }
    }

    /** {@code damageMultiplier(b)}: a unit owner's status multiplier and team rules, a building owner's team rules. */
    public float damageMultiplier(Bullet b){
        Entity owner = b.owner();
        if(owner instanceof Unit u) return u.statuses.damageMultiplier * UnitCalls.rules(b.world()).unitDamage(b.team());
        if(owner instanceof Building) return UnitCalls.rules(b.world()).blockDamage(b.team());
        return 1f;
    }

    public void hit(Bullet b){
        hit(b, b.x(), b.y(), true);
    }

    public void hit(Bullet b, float x, float y){
        hit(b, x, y, true);
    }

    /**
     * {@code hit(b, x, y, createFrags)} (BulletType.java:562): the hit effect, frags, puddles, fires, units,
     * suppression, splash damage, then lightning.
     */
    public void hit(Bullet b, float x, float y, boolean createFrags){
        World world = b.world();
        Rand rand = world.rand();
        world.publish(new BulletEvent.Hit(id(), b.team(), x, y, b.rotation()));
        // hitSound.at(x, y, hitSoundPitch + Mathf.range(hitSoundPitchRange), hitSoundVolume)
        Mathf.range(rand, hitSoundPitchRange);
        if(createFrags && fragOnHit){
            if(delayFrags && fragBullet != null && fragBullet.delayFrags){
                world.timers().run(0f, new DelayedFrags(id(), b.owner(), b.shooter(), b.team(), b.rotation(), b.absorbed(), b.frags(), x, y));
            }else{
                createFrags(b, x, y);
            }
        }
        createPuddles(b, x, y);
        createIncend(b, x, y);
        createUnits(b, x, y);
        if(suppressionRange > 0){
            Damage.applySuppression(world, b.team(), b.x(), b.y(), suppressionRange, suppressionDuration, 0f, suppressionEffectChance);
        }
        createSplashDamage(b, x, y);
        for(int i = 0; i < lightning; i++){
            float angle = b.rotation() + Mathf.range(rand, lightningCone / 2) + lightningAngle;
            Lightning.create(b, lightningDamage < 0 ? damage : lightningDamage, b.x(), b.y(), angle, lightningLength + Mathf.random(rand, lightningLengthRand));
        }
    }

    public void createIncend(Bullet b, float x, float y){
        if(incendChance > 0 && Mathf.chance(b.world().rand(), incendChance)){
            Damage.createIncend(b.world(), x, y, incendSpread, incendAmount);
        }
    }

    public void createPuddles(Bullet b, float x, float y){
        if(puddleLiquid != null && puddles > 0){
            World world = b.world();
            for(int i = 0; i < puddles; i++){
                int tile = tileWorld(world, x + Mathf.range(world.rand(), puddleRange), y + Mathf.range(world.rand(), puddleRange));
                Hazards.of(world).puddle(tile, puddleLiquid.id(), puddleAmount);
            }
        }
    }

    /** {@code createSplashDamage}: splash damage and status, splash healing and fires, unless absorbed. */
    public void createSplashDamage(Bullet b, float x, float y){
        if(splashDamageRadius > 0 && !b.absorbed()){
            World world = b.world();
            Damage.damage(world, b.team(), x, y, splashDamageRadius, splashDamage * b.damageMultiplier(), splashDamagePierce,
                collidesAir, collidesGround, scaledSplashDamage, b, armorMultiplier);
            if(status != null && !status.name().equals("none")){
                Damage.status(world, b.team(), x, y, splashDamageRadius, status, statusDuration, collidesAir, collidesGround, statusChance);
            }
            if(heals()){
                // indexer.eachBlock(b.team, x, y, radius, Building::damaged, heal)
                world.buildings().eachBlock(b.team(), x, y, splashDamageRadius, other -> {
                    if(other.damaged()) other.heal(healPercent / 100f * other.maxHealth + healAmount);
                });
            }
            if(makeFire){
                Targets.eachBlock(world, x, y, splashDamageRadius, other -> other.team != b.team(), other -> Hazards.of(world).fire(other.tile()));
            }
        }
    }

    /** {@code createFrags}: {@code fragBullets} of its frag bullet around (x, y), once per pierce up to the cap. */
    public void createFrags(Bullet b, float x, float y){
        if(fragBullet != null && (fragOnAbsorb || !b.absorbed()) && (pierceFragCap < 0 || b.frags() < pierceFragCap)){
            Rand rand = b.world().rand();
            for(int i = 0; i < fragBullets; i++){
                float len = Mathf.random(rand, fragOffsetMin, fragOffsetMax);
                float a = b.rotation() + Mathf.range(rand, fragRandomSpread / 2) + fragAngle + fragSpread * i - (fragBullets - 1) * fragSpread / 2f;
                float velocity = Mathf.random(rand, fragVelocityMin, fragVelocityMax);
                fragBullet.create(b, x + Angles.trnsx(a, len), y + Angles.trnsy(a, len), a, velocity, Mathf.random(rand, fragLifeMin, fragLifeMax));
            }
            b.frags(b.frags() + 1);
        }
    }

    /** {@code createUnits}: the despawn unit, if the chance holds. */
    public void createUnits(Bullet b, float x, float y){
        World world = b.world();
        if(despawnUnit != null && Mathf.chance(world.rand(), despawnUnitChance)){
            for(int i = 0; i < despawnUnitCount; i++){
                float length = Mathf.random(world.rand(), despawnUnitRadius);
                Vec2 offset = new Vec2().setToRandomDirection(world.rand()).scl(length);
                Unit unit = Unit.create(world, despawnUnit, b.team());
                unit.set(x + offset.x, y + offset.y);
                unit.add();
                unit.rotation = faceOutwards ? offset.angle() : b.rotation();
                CombatEvent.unitSpawned(unit);
            }
        }
    }

    /** {@code despawned(b)}: at the end of its life or when removed from outside, a hit if {@code despawnHit}. */
    public void despawned(Bullet b){
        if(despawnHit){
            hit(b, b.x(), b.y(), false);
        }else{
            createUnits(b, b.x(), b.y());
        }
        World world = b.world();
        world.publish(new BulletEvent.Despawned(id(), b.team(), b.x(), b.y(), b.rotation()));
        // despawnSound.at(b, 1f + Mathf.range(hitSoundPitchRange))
        Mathf.range(world.rand(), hitSoundPitchRange);
    }

    /** {@code removed(b)}: frags for a bullet that never split, when it splits on despawn. */
    public void removed(Bullet b){
        if(b.frags() == 0 && fragOnDespawn && fragBullet != null) createFrags(b, b.x(), b.y());
    }

    /** {@code buildingDamage(b)}. */
    public float buildingDamage(Bullet b){
        return b.damage() * b.table().buildingDamageMultiplier[b.row()];
    }

    @Override
    public String toString(){
        return def == null ? "BulletType(unloaded)" : "BulletType(" + def.name() + ")";
    }

    /**
     * {@code Time.run(0f, () -> createFrags(b, x, y))} for delayed frags. Vanilla's closure holds the pooled bullet,
     * which is freed by then; this holds what {@code createFrags} reads of it, as it was at the hit.
     */
    public record DelayedFrags(int type, Entity owner, Entity shooter, int team, float rotation, boolean absorbed, int frags,
                               float x, float y) implements Timers.Task{
        @Override
        public void run(World world){
            BulletType parent = world.bullets().types(world).get(type);
            if(parent.fragBullet == null || !(parent.fragOnAbsorb || !absorbed) || !(parent.pierceFragCap < 0 || frags < parent.pierceFragCap)) return;
            Rand rand = world.rand();
            for(int i = 0; i < parent.fragBullets; i++){
                float len = Mathf.random(rand, parent.fragOffsetMin, parent.fragOffsetMax);
                float a = rotation + Mathf.range(rand, parent.fragRandomSpread / 2) + parent.fragAngle + parent.fragSpread * i
                    - (parent.fragBullets - 1) * parent.fragSpread / 2f;
                float velocity = Mathf.random(rand, parent.fragVelocityMin, parent.fragVelocityMax);
                parent.fragBullet.create(world, owner, shooter, team, x + Angles.trnsx(a, len), y + Angles.trnsy(a, len), a, -1f, velocity,
                    Mathf.random(rand, parent.fragLifeMin, parent.fragLifeMax), null, -1f, -1f, null);
            }
        }
    }
}
