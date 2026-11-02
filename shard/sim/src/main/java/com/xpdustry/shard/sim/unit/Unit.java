package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.sim.ai.CommandAi;
import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.objectives.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.unit.ability.*;
import com.xpdustry.shard.sim.world.*;

/**
 * A unit: one class for every unit type (ARCHITECTURE.md §3 "Objects"). What vanilla spreads over 38 generated entity
 * classes is composed here: a {@link MoveShape} for the movement component, {@link WeaponMount}s, {@link UnitAbility}
 * state, {@link Statuses}, the item stack, the {@link PlanQueue}, a {@link PayloadSlot} for {@code PayloadComp}
 * classes, the tether of {@code BuildingTetherComp} and the timer of {@code TimedKillComp}, and a
 * {@link UnitController}. Which entity class layout a unit uses on the wire is {@code :compat}'s choice, from its
 * {@link UnitParams}.
 *
 * <p>Fields are grouped by the vanilla component that declares them. They hold everything vanilla syncs or saves
 * ({@code data/vanilla-v160.7/entities.json}) and the server state behind it; draw-only state (trails, alphas, hit
 * flashes) is left out. Every field is {@link Persistence#AUTHORITATIVE} except those marked {@link Derived}, which
 * {@link #restoreType()} rebuilds from the type.
 *
 * <p>Lifecycle, as vanilla ({@code UnitType.create}/{@code spawn}, the generated {@code add()}/{@code remove()}):
 * <ol>
 *   <li>{@link #create} draws the entity id, then the random field initializers, applies the type (which makes the
 *   type's controller, {@link Controllers}), sets a {@link CommandAi}'s default command and runs each ability's
 *   {@code created};</li>
 *   <li>{@link #add()} joins {@code Groups.unit} (the world's {@link World#units()}) and {@code Groups.sync}
 *   ({@link EntityGroups}), records the last position, counts the unit for its team ({@link TeamUnits}), which may
 *   kill it over the cap, and registers it with {@link UnitPhysics};</li>
 *   <li>{@link #update()} runs the merged {@code update()} of the class's components ({@link UnitComponent});</li>
 *   <li>{@link #kill()}, {@link #destroy()}, {@link #despawn()} and the posted cap and environment deaths end it, each
 *   publishing its {@link UnitEvent} where vanilla sends its call ({@link UnitCalls});</li>
 *   <li>{@link #remove()} leaves the groups, uncounts the unit and tells its controller. Physics drops it at its next
 *   step.</li>
 * </ol>
 * Nothing on a server draws, so {@code Groups.draw} is not kept.
 *
 * <p>Owners: WP12 (ids, groups, order, fields), WP21 (unit core: {@code UnitComp.update}, damage, death), WP44
 * (movement shapes), with weapons, abilities, statuses, plans and payloads ported by their own packages.
 */
public final class Unit extends Entity{
    static final ActiveList.Slot<Unit> UPDATE_SLOT = new ActiveList.Slot<>(){
        @Override
        public int get(Unit unit){
            return unit.updateIndex;
        }

        @Override
        public void set(Unit unit, int position){
            unit.updateIndex = position;
        }
    };

    private UnitDef type;
    @Derived("restoreType(): the world's UnitParams of the type")
    private UnitParams params = UnitParams.DEFAULTS;
    private int updateIndex = -1;

    // PosComp, RotComp, VelComp
    public float x, y, rotation;
    public Vec2 vel = new Vec2();
    /** Set from the type, then each update from the floor and rules ({@code UnitComp.update}); stored. */
    public float drag;

    // HitboxComp
    public float lastX, lastY, deltaX, deltaY;
    @Derived("restoreType(): type.hitSize")
    public float hitSize;

    // PhysicsComp: set while UnitPhysics holds a body for this unit
    public boolean hasPhysicsRef;

    // TeamComp, HealthComp, ShieldComp
    public int team;
    public float health;
    @Derived("restoreType(): type.health")
    public float maxHealth = 1f;
    public boolean dead;
    public float shield;
    @Derived("restoreType(): type.armor")
    public float armor;

    // UnitComp
    public float elevation;
    public double flag;
    public boolean spawnedByCore;
    public UnitController controller;
    public UnitAbility[] abilities = {};
    /** The unit type docked in this one (content id), or -1. */
    public int dockedType = -1;
    /** Suicide AI: whether it has a target. */
    public boolean hasTarget;
    public boolean wasPlayer, wasHealed, wasFlying;
    public float resupplyTime, drownTime;
    /** Distance walked since the last splash; a splash draws a sound pitch from the random. */
    public float splashTimer;
    /** The liquid floor drowning this unit (content id), or -1. */
    public int lastDrownFloor = -1;
    public int lastFogPos;

    // WeaponsComp
    public WeaponMount[] mounts = {};
    public boolean isRotate, isShooting;
    public float aimX, aimY;

    // StatusComp
    public Statuses statuses = new Statuses();

    // ItemsComp: vanilla's empty ItemStack holds 0 copper
    public int stackItem, stackAmount;

    // BuilderComp
    public PlanQueue plans = new PlanQueue();
    public boolean updateBuilding = true;
    public float buildCounter;

    // MinerComp: the tile being mined, as Point2.pack(x, y), or -1
    public int mineTile = -1;
    public float mineTimer;

    /** The movement component. */
    public MoveShape shape;
    /** {@code PayloadComp}, or null for classes without it. */
    public PayloadSlot payloads;
    /** {@code BuildingTetherComp}: the building this unit belongs to, or null. Check {@code isValid()}. */
    public Building tether;
    /**
     * {@code BlockUnitComp.tile}: the turret a block unit belongs to, or null for every other unit. A block unit is never
     * added; it is valid while its turret is (WP60).
     */
    public Building blockTile;
    /** {@code TimedKillComp}: frames lived and the lifetime, for missiles. */
    public float time, lifetime;

    public Unit(){
    }

    /**
     * Vanilla's {@code UnitType.create(team)}: a new unit of {@code type} with a fresh entity id, not yet added. The
     * caller positions it and calls {@link #add()}, or uses {@link #spawn}.
     */
    public static Unit create(World world, UnitDef type, int team){
        UnitParams params = world.unitTable().params(type);
        var unit = new Unit();
        unit.created(world);
        unit.shape = MoveShape.of(params.locomotion());
        // the generated constructors' field initializers draw from Mathf.rand, CrawlComp's before UnitComp's
        Rand rand = world.rand();
        if(unit.shape instanceof MoveShape.Crawl crawl) crawl.crawlTime = rand.random(100f);
        unit.resupplyTime = rand.random(10f);
        if(params.payloads()) unit.payloads = new PayloadSlot();
        unit.team = team;
        unit.setType(type);
        // the controller's init() already picked the default command; create sets it again
        if(unit.controller instanceof CommandAi ai && params.defaultCommand() != null){
            int command = world.content().unitCommands().indexOf(params.defaultCommand());
            if(command != -1) ai.command = command;
        }
        Abilities.created(unit);
        unit.elevation = params.flying() ? 1f : 0f;
        unit.heal();
        if(params.timedKill()) unit.lifetime = params.lifetime();
        return unit;
    }

    /**
     * A unit whose construction draws were made earlier, under {@code id}: a turret's block unit (WP60), which vanilla
     * makes when the turret is constructed. It takes {@code controller} before its type, so nothing draws; it is not
     * added.
     */
    public static Unit createDrawn(World world, UnitDef type, int team, int id, UnitController controller){
        UnitParams params = world.unitTable().params(type);
        var unit = new Unit();
        unit.created(world, id);
        unit.shape = MoveShape.of(params.locomotion());
        if(params.payloads()) unit.payloads = new PayloadSlot();
        unit.team = team;
        unit.controller(controller);
        unit.setType(type);
        unit.elevation = params.flying() ? 1f : 0f;
        unit.heal();
        return unit;
    }

    /** Vanilla's {@code UnitType.spawn(team, x, y, rotation)}: {@link #create}, place, {@link #add()}. */
    public static Unit spawn(World world, UnitDef type, int team, float x, float y, float rotation){
        Unit unit = create(world, type, team);
        unit.rotation = rotation;
        unit.set(x, y);
        unit.add();
        return unit;
    }

    /** The slot accessor for {@link World#units()}. */
    public static ActiveList.Slot<Unit> updateSlot(){
        return UPDATE_SLOT;
    }

    public UnitDef type(){
        return type;
    }

    /** The type's parameters, as of the last {@link #setType} or {@link #restoreType()}. */
    public UnitParams params(){
        return params;
    }

    /**
     * Vanilla's {@code setType}: copies the type's health, drag, armor and hit size, and makes one mount per weapon and
     * one ability state per ability ({@link Abilities#create()}) when the counts differ. A unit without a controller gets its type's
     * ({@link Controllers}), whose timers draw from the world random as vanilla's do; a restore sets the saved
     * controller first.
     */
    public void setType(UnitDef type){
        this.type = type;
        restoreType();
        drag = params.drag();
        if(mounts.length != params.weaponCount()){
            mounts = new WeaponMount[params.weaponCount()];
            for(int i = 0; i < mounts.length; i++) mounts[i] = new WeaponMount(i, params.weaponBaseRotation(i));
        }
        if(abilities.length != params.abilities()){
            UnitAbility[] old = abilities;
            abilities = Abilities.of(this).create();
            for(int i = 0; i < abilities.length && i < old.length; i++) abilities[i].data = old[i].data;
        }
        if(controller == null) controller(Controllers.create(this));
    }

    /**
     * The {@link Derived} part of {@link #setType}: the type's parameters, health, armor and hit size. A restore calls
     * it once the unit's fields, controller included, are read: the mounts, abilities and controller are stored, so
     * nothing is made and nothing draws from the random.
     */
    public void restoreType(){
        params = world().unitTable().params(type);
        maxHealth = type.health();
        armor = params.armor();
        hitSize = params.hitSize();
    }

    // region controller

    /** Vanilla's {@code controller(next)}: binds {@code next}, which may run its {@code init()}. */
    public void controller(UnitController next){
        controller = next;
        next.bound(this);
    }

    /** Vanilla's {@code resetController()}: the type's controller, made anew. */
    public void resetController(){
        controller(Controllers.create(this));
    }

    /** Vanilla's {@code isPlayer()}. */
    public boolean isPlayer(){
        return controller instanceof PlayerControl;
    }

    /** The controlling player, or null. */
    public Player player(){
        return controller instanceof PlayerControl control ? control.player : null;
    }

    /** Vanilla's {@code isRemote()} on a server: every player is remote. */
    public boolean isRemote(){
        return isPlayer();
    }

    /** Vanilla's {@code isAI()}. */
    public boolean isAI(){
        return controller instanceof AiController;
    }

    /** Vanilla's {@code isCommandable()}. */
    public boolean isCommandable(){
        return controller instanceof CommandAi;
    }

    // endregion

    // region health (HealthComp, ShieldComp, UnitComp)

    /** Vanilla's {@code heal()}: alive at full health. */
    public void heal(){
        dead = false;
        health = maxHealth;
    }

    /** {@code HealthComp.heal(amount)} then {@code UnitComp.heal(amount)}: clamped, and marked healed. */
    public void heal(float amount){
        health += amount;
        clampHealth();
        if(health < maxHealth && amount > 0) wasHealed = true;
    }

    /** Heals by a fraction of the maximum health. */
    public void healFract(float amount){
        heal(amount * maxHealth);
    }

    /** {@code clampHealth()}: never above the maximum, never NaN. */
    public void clampHealth(){
        health = Math.min(health, maxHealth);
        if(Float.isNaN(health)) health = 0f;
    }

    public boolean damaged(){
        return health < maxHealth - 0.001f;
    }

    public float healthf(){
        return health / maxHealth;
    }

    /** {@code ShieldComp.damage}: armor (or the status override) first, then the status and team health scaling. */
    public void damage(float amount){
        float armorValue = statuses.armorOverride >= 0f ? statuses.armorOverride : armor;
        rawDamage(applyArmor(amount, armorValue) / statuses.healthMultiplier / UnitCalls.rules(world()).unitHealth(team));
    }

    /**
     * {@code damage(amount, withEffect)}: the hit flash isn't simulated, so the same as {@link #damage(float)}. A block
     * unit's replaces it with its turret's {@code damage(amount, withEffect)} (BlockUnitComp.java:57), which only flashes
     * besides {@code damage(amount)}; its other damage methods hurt the unit alone, as vanilla's do.
     */
    public void damage(float amount, boolean withEffect){
        if(blockTile != null){
            blockTile.damage(amount);
            return;
        }
        damage(amount);
    }

    /** {@code ShieldComp.damagePierce}: ignores armor. */
    public void damagePierce(float amount){
        rawDamage(amount / statuses.healthMultiplier / UnitCalls.rules(world()).unitHealth(team));
    }

    /** {@code ShieldComp.damageArmorMult}. */
    public void damageArmorMult(float amount, float armorMult){
        float armorValue = statuses.armorOverride >= 0f ? statuses.armorOverride * armorMult : armor * armorMult;
        rawDamage(applyArmor(amount, armorValue) / statuses.healthMultiplier / UnitCalls.rules(world()).unitHealth(team));
    }

    /** {@code damageContinuous}: per tick, at Δ = 1. */
    public void damageContinuous(float amount){
        damage(amount);
    }

    /** {@code damageContinuousPierce}. */
    public void damageContinuousPierce(float amount){
        damagePierce(amount);
    }

    /** {@code Damage.applyArmor}: armor subtracts, down to {@code minArmorDamage}, a tenth of the damage. */
    public static float applyArmor(float damage, float armor){
        return Math.max(damage - armor, 0.1f * damage);
    }

    /**
     * {@code ShieldComp.rawDamage}: the shield absorbs first, then a killable type loses health and dies at zero. A
     * target dummy ({@code TargetDummyComp}) takes nothing: its building counts the hit ({@link DummyBuilding}).
     */
    public void rawDamage(float amount){
        if(params.dummy()){
            if(tether instanceof DummyBuilding dummy) dummy.dummyHit(amount);
            return;
        }
        if(Float.isNaN(health)) health = 0f;
        float shieldDamage = Math.min(Math.max(shield, 0), amount);
        shield -= shieldDamage;
        amount -= shieldDamage;
        if(amount > 0 && params.killable()){
            health -= amount;
            if(health <= 0 && !dead) kill();
        }
    }

    /** Vanilla's {@code killable()}. */
    public boolean killable(){
        return params.killable();
    }

    /**
     * Vanilla's {@code hittable()}: unhittable types still are while carrying payloads, if so configured; a submerged
     * shape never is ({@link MoveShape#submerged}).
     */
    public boolean hittable(){
        return !shape.submerged(this) && (params.hittable() || (params.vulnerableWithPayloads() && payloads != null && !payloads.isEmpty()));
    }

    /** {@code UnitComp.kill}: a living, killable unit dies through {@code Call.unitDeath}. */
    public void kill(){
        if(dead || !killable()) return;
        UnitCalls.death(this);
    }

    /**
     * {@code HealthComp.killed}, {@code UnitComp.killed}: dead at zero health or below. A flying type that leaves a
     * wreck falls first ({@code UnitComp.update}), any other is destroyed at once.
     */
    public void killed(){
        // BlockUnitComp.killed merges first: a block unit kills its turret (BlockUnitComp.java:52)
        if(blockTile != null) blockTile.kill();
        // isLocal() is always false on a server
        wasPlayer = false;
        health = Math.min(health, 0);
        dead = true;
        if(!params.flying() || !params.createWreck()) destroy();
    }

    /**
     * {@code UnitComp.destroy}: the death explosion from the carried items unless spawned by a core, the
     * {@linkplain UnitHooks#destroyed destroyed hook} (the weapons that shoot on death), a flying wreck's crash damage,
     * the abilities' {@code death} ({@link UnitHooks#abilitiesDied}), then removal. {@code type.killed} does nothing: no native
     * type overrides it.
     */
    public void destroy(){
        // PayloadComp.destroy merges first, whether or not the unit is added
        if(payloads != null) Payloads.destroyed(this);
        if(!isAdded() || !killable()) return;
        shape.destroyed(this);
        if(!spawnedByCore){
            // an empty stack contributes nothing whatever its item
            var item = stackAmount > 0 ? world().content().item(stackItem) : null;
            float explosiveness = 2f + (item == null ? 0f : item.explosiveness() * stackAmount * 1.53f);
            float flammability = item == null ? 0f : item.flammability() * stackAmount / 1.9f;
            float power = item == null ? 0f : item.charge() * Mathf.pow(stackAmount, 1.11f) * 160f;
            UnitDamage.of(world()).deathExplosion(this, flammability, explosiveness, power, (bounds() + params.legLength() / 1.7f) / 2f);
        }
        // UnitDestroyEvent (UnitComp.java:905): the game stats objectives read
        ObjectiveState.unitDestroyed(world(), team);
        // and FogControl's: the team's fog is redrawn
        com.xpdustry.shard.sim.fog.FogControl.unitDestroyed(world(), team);
        UnitHooks.of(world()).destroyed(this);
        if(params.flying() && !spawnedByCore && params.createWreck() && UnitCalls.rules(world()).unitCrashDamage(team) > 0){
            UnitDamage.of(world()).crash(this);
        }
        UnitHooks.of(world()).abilitiesDied(this);
        remove();
    }

    /** {@code Call.unitDespawn(unit)}: removed without dying. */
    public void despawn(){
        UnitCalls.despawn(this);
    }

    /** {@code Call.unitSafeDeath(unit)}: removed with death effects only. */
    public void safeDeath(){
        UnitCalls.safeDeath(this);
    }

    /** Vanilla's {@code bounds()}: twice the hit size. */
    public float bounds(){
        return hitSize * 2f;
    }

    // endregion

    // region items (ItemsComp)

    /** {@code item()}: the stack's item id, which an empty stack keeps (its amount is 0). */
    public int item(){
        return stackItem;
    }

    /** {@code hasItem()}. */
    public boolean hasItem(){
        return stackAmount > 0;
    }

    /** {@code itemCapacity()}: the type's. */
    public int itemCapacity(){
        return params.itemCapacity();
    }

    /** {@code acceptsItem(item)}: an empty stack takes anything, a full one or another item nothing. */
    public boolean acceptsItem(int item){
        return !hasItem() || item == stackItem && stackAmount + 1 <= itemCapacity();
    }

    /** {@code addItem(item)}. */
    public void addItem(int item){
        addItem(item, 1);
    }

    /** {@code addItem(item, amount)}: a different item replaces the stack; capped at the capacity. */
    public void addItem(int item, int amount){
        stackAmount = stackItem == item ? stackAmount + amount : amount;
        stackItem = item;
        stackAmount = Mathf.clamp(stackAmount, 0, itemCapacity());
    }

    /** {@code clearItem()}: the amount goes to 0, the item stays. */
    public void clearItem(){
        stackAmount = 0;
    }

    /** {@code maxAccepted(item)}: room for {@code item}, none while carrying another. */
    public int maxAccepted(int item){
        return stackItem != item && stackAmount > 0 ? 0 : itemCapacity() - stackAmount;
    }

    // endregion

    public void set(float x, float y){
        this.x = x;
        this.y = y;
    }

    /**
     * Vanilla's {@code team(team)}: any unit just takes the team, except a block unit, which takes a new one only with
     * its turret and passes it on ({@code BlockUnitComp.team}, BlockUnitComp.java:79): the turret changes team unless it
     * is already on it. A player controlling a turret moves it to the player's team this way every update.
     */
    public void team(int team){
        if(blockTile == null){
            this.team = team;
        }else if(this.team != team){
            this.team = team;
            if(blockTile.team != team) world().buildings().changeTeam(blockTile, team, world().buildings().cause());
        }
    }

    /** Vanilla's {@code isValid}: not dead and still added. */
    public boolean isValid(){
        // BlockUnitComp replaces isValid with its tile's
        if(blockTile != null) return blockTile.isValid();
        return !dead && isAdded();
    }

    /** Vanilla's {@code isGrounded}: elevation below 0.001. */
    public boolean isGrounded(){
        return elevation < 0.001f;
    }

    /** Vanilla's {@code isFlying}: elevation 0.09 or more. Between the two a unit is neither. */
    public boolean isFlying(){
        return elevation >= 0.09f;
    }

    /** Arc's {@code Position.dst2(x, y)}. */
    public float dst2(float x, float y){
        float xd = this.x - x, yd = this.y - y;
        return xd * xd + yd * yd;
    }

    /** Arc's {@code Position.dst(x, y)}. */
    public float dst(float x, float y){
        return Mathf.sqrt(dst2(x, y));
    }

    /** Arc's {@code Position.within(x, y, dst)}: strictly closer than {@code dst}. */
    public boolean within(float x, float y, float dst){
        return Mathf.within(this.x, this.y, x, y, dst);
    }

    /** The type's {@code targetPriority}: targeting prefers higher, whatever the distance. */
    public float targetPriority(){
        return params.targetPriority();
    }

    /** Vanilla's {@code mass()}: the area of the hit circle. */
    public float mass(){
        return hitSize * hitSize * Mathf.pi;
    }

    /**
     * {@code PhysicsComp.impulse(x, y)}: a push of ({@code x}, {@code y}) on the velocity, divided by the mass (bullet
     * knockback, conveyors, wind, a core's spawn).
     */
    public void impulse(float x, float y){
        float mass = mass();
        vel.add(x / mass, y / mass);
    }

    /** {@code PhysicsComp.impulse(v)}. */
    public void impulse(Vec2 v){
        impulse(v.x, v.y);
    }

    /**
     * {@code PhysicsComp.impulseNet(v)}: {@link #impulse}, and a remote (player) unit also moves at once, through tile
     * collisions, as its client will (tractor beams).
     */
    public void impulseNet(Vec2 v){
        impulse(v.x, v.y);
        if(isRemote()){
            float mass = mass();
            move(v.x / mass, v.y / mass);
        }
    }

    /** Vanilla's {@code collisionLayer()}: which physics world pushes this unit, as its movement shape says. */
    public int collisionLayer(){
        return shape.collisionLayer(this);
    }

    /**
     * Vanilla's {@code targetable(team)}: untargetable types still are while carrying payloads, if so configured; a
     * submerged shape never is.
     */
    public boolean targetable(int targeter){
        return !shape.submerged(this) && (params.targetable() || (params.vulnerableWithPayloads() && payloads != null && !payloads.isEmpty()));
    }

    /**
     * Vanilla's {@code unloaded()}: the unit just left a unit factory, a payload or a wave spawn point
     * ({@code WaveSpawner}, {@code UnitPayload.dump}, {@code PayloadComp}, {@code unit spawn} in logic). Legs reset.
     */
    public void unloaded(){
        shape.unloaded(this);
    }

    /** Vanilla's {@code inFogTo(team)} (UnitComp.java:232): {@link com.xpdustry.shard.sim.fog.FogControl#inFogTo}. */
    public boolean inFogTo(int viewer){
        return com.xpdustry.shard.sim.fog.FogControl.inFogTo(this, viewer);
    }

    /** Vanilla's {@code updateLastPosition()}: the movement since the last call. */
    public void updateLastPosition(){
        deltaX = x - lastX;
        deltaY = y - lastY;
        lastX = x;
        lastY = y;
    }

    // region movement (VelComp, UnitComp, PosComp)

    /** {@code moveAt(vector, acceleration)}: steers the velocity toward {@code vector} by at most {@code acceleration} of its length. */
    public void moveAt(Vec2 vector, float acceleration){
        shape.beforeMoveAt(this, vector);
        float tx = vector.x - vel.x, ty = vector.y - vel.y;
        var delta = new Vec2(tx, ty).limit(acceleration * vector.len());
        vel.add(delta);
    }

    /** {@code moveAt(vector)}: at the type's acceleration. */
    public void moveAt(Vec2 vector){
        moveAt(vector, params.accel());
    }

    /** {@code approach(vector)}: the velocity approaches {@code vector} by {@code accel * speed()}. */
    public void approach(Vec2 vector){
        shape.beforeApproach(this, vector);
        vel.approachDelta(vector, params.accel() * speed());
    }

    /** {@code rotateMove(vec)}: forward along the rotation, turning toward {@code vec}; a shape may replace it. */
    public void rotateMove(Vec2 vec){
        shape.rotateMove(this, vec);
    }

    /** {@code movePref(movement)}: {@code moveAt} for omni-directional types, else {@code rotateMove}. */
    public void movePref(Vec2 movement){
        if(params.omniMovement()) moveAt(movement);
        else rotateMove(movement);
    }

    /** {@code lookAt(angle)}: turns toward {@code angle} at the type's rotate speed. */
    public void lookAt(float angle){
        rotation = Angles.moveToward(rotation, angle, params.rotateSpeed() * statuses.speedMultiplier);
    }

    /** {@code lookAt(x, y)}. */
    public void lookAt(float x, float y){
        lookAt(angleTo(x, y));
    }

    /** {@code angleTo(x, y)}. */
    public float angleTo(float x, float y){
        return Angles.angle(this.x, this.y, x, y);
    }

    /** {@code speed()}: the type's speed with the strafe penalty, the boost and the floor. */
    public float speed(){
        float strafePenalty = isGrounded() || !isPlayer() ? 1f
            : Mathf.lerp(1f, params.strafePenalty(), Angles.angleDist(vel.angle(), rotation) / 180f);
        float boost = Mathf.lerp(1f, params.canBoost() ? params.boostMultiplier() : 1f, elevation);
        return params.speed() * strafePenalty * boost * floorSpeedMultiplier();
    }

    /** {@code floorSpeedMultiplier()}, as the movement shape has it. */
    public float floorSpeedMultiplier(){
        return shape.floorSpeedMultiplier(this);
    }

    /** {@code updateBoosting(boost)}: a boosting type rises over solid ground or when asked, and lands when it can. */
    public void updateBoosting(boolean boost){
        if(!params.canBoost() || dead) return;
        boolean shouldBoost = boost || onSolid() || (isFlying() && !canLand());
        elevation = Mathf.approachDelta(elevation, Mathf.num(shouldBoost), shouldBoost ? params.riseSpeed() : params.descentSpeed());
    }

    /** {@code velAddNet(vx, vy)}: a remote (player) unit also moves at once, as the client predicted. */
    public void velAddNet(float vx, float vy){
        vel.add(vx, vy);
        if(isRemote()){
            x += vx;
            y += vy;
        }
    }

    /** {@code moving()}. */
    public boolean moving(){
        return !(vel.len2() < 0.01f);
    }

    /** {@code World.toTile(x)}. */
    public int tileX(){
        return Math.round(x / 8f);
    }

    /** {@code World.toTile(y)}. */
    public int tileY(){
        return Math.round(y / 8f);
    }

    /** {@code tileOn()}: the tile position under the unit, or -1 outside the map. */
    public int tileOn(){
        int tx = tileX(), ty = tileY();
        return world().tiles().inBounds(tx, ty) ? world().tiles().pos(tx, ty) : -1;
    }

    /** {@code floorOn()}: the floor id under the unit, air (0) on a block or outside the map. */
    public int floorOn(){
        int pos = tileOn();
        return pos == -1 ? 0 : world().tiles().floorOn(pos);
    }

    /** {@code onSolid()}, as the movement shape has it. */
    public boolean onSolid(){
        return shape.onSolid(this);
    }

    /** {@code canPass(tileX, tileY)}: the shape's solidity, if any, lets the unit stand there. */
    public boolean canPass(int tx, int ty){
        Solidity solidity = shape.solidity(this);
        return solidity == null || !TileSolidity.of(world()).solid(solidity, tx, ty);
    }

    /** {@code canPassOn()}. */
    public boolean canPassOn(){
        return canPass(tileX(), tileY());
    }

    /** {@code physicSize()}. */
    public float physicSize(){
        return hitSize * 0.7f;
    }

    /**
     * {@code canLand()}: not on solid ground and no other grounded unit under the landing square. A target dummy
     * always may.
     */
    public boolean canLand(){
        if(params.dummy()) return true;
        if(onSolid()) return false;
        float size = physicSize();
        SpatialIndex index = SpatialIndex.of(world());
        return index == null || !index.any(x - size / 2f, y - size / 2f, size, size, other -> other != this && other.isGrounded());
    }

    /** {@code canDrown()}. */
    public boolean canDrown(){
        return isGrounded() && params.canDrown();
    }

    // endregion

    /**
     * Vanilla's {@code VelComp.move}: moves by ({@code dx}, {@code dy}), through tile collisions when the movement shape
     * has a {@link MoveShape#solidity(Unit) solidity}, else by plain addition.
     */
    public void move(float dx, float dy){
        Solidity solidity = shape.solidity(this);
        if(solidity != null){
            Collisions.move(this, dx, dy, TileSolidity.of(world()), solidity);
        }else{
            x += dx;
            y += dy;
        }
    }

    /** One tick of this unit, from the {@code UNITS} phase: its class's components' merged {@code update()}. */
    @Override
    public void update(){
        for(UnitComponent component : params.components()) component.update(this);
    }

    /**
     * Vanilla's generated {@code add()}: joins {@code Groups.unit}, then runs the components' {@code add} bodies in
     * its order: {@code HitboxComp} (last position), {@code LegsComp} or {@code CrawlComp} (legs, segment rotation),
     * {@code UnitComp} (team count, cap check, physics).
     */
    @Override
    public void add(){
        if(isAdded()) return;
        world().units().add(this);
        EntityGroups groups = EntityGroups.of(world());
        if(groups != null) groups.addSync(this);
        added(true);
        updateLastPosition();
        // LegsComp.add and CrawlComp.add, where the generated add() runs them; resetting legs draws from the random
        shape.added(this);
        TeamUnits counts = TeamUnits.of(world());
        if(counts != null) counts.added(this);
        UnitPhysics physics = UnitPhysics.of(world());
        if(physics != null) physics.add(this);
    }

    /**
     * Vanilla's generated {@code remove()}: leaves {@code Groups.unit}, then {@code PayloadComp.remove} (what it carried
     * goes with it, {@link Payloads}), then {@code UnitComp.remove}: uncounts the unit and tells the controller; then the
     * {@linkplain UnitHooks#removed removed hook} ({@code WeaponsComp.remove}).
     */
    @Override
    public void remove(){
        if(!isAdded()) return;
        world().units().remove(this);
        EntityGroups groups = EntityGroups.of(world());
        if(groups != null) groups.removeSync(this);
        added(false);
        if(payloads != null) Payloads.removed(this);
        TeamUnits counts = TeamUnits.of(world());
        if(counts != null) counts.updateCount(team, type, -1);
        if(controller != null) controller.removed(this);
        UnitHooks.of(world()).removed(this);
    }

    @Override
    public Copyable blank(){
        return new Unit();
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (Unit)into;
        out.type = type;
        out.params = params;
        out.updateIndex = updateIndex;
        out.x = x;
        out.y = y;
        out.rotation = rotation;
        out.vel = new Vec2(vel);
        out.drag = drag;
        out.lastX = lastX;
        out.lastY = lastY;
        out.deltaX = deltaX;
        out.deltaY = deltaY;
        out.hitSize = hitSize;
        out.hasPhysicsRef = hasPhysicsRef;
        out.team = team;
        out.health = health;
        out.maxHealth = maxHealth;
        out.dead = dead;
        out.shield = shield;
        out.armor = armor;
        out.elevation = elevation;
        out.flag = flag;
        out.spawnedByCore = spawnedByCore;
        out.controller = controller == null ? null : controller.copy(context);
        out.abilities = new UnitAbility[abilities.length];
        for(int i = 0; i < abilities.length; i++) out.abilities[i] = abilities[i].copy();
        out.dockedType = dockedType;
        out.hasTarget = hasTarget;
        out.wasPlayer = wasPlayer;
        out.wasHealed = wasHealed;
        out.wasFlying = wasFlying;
        out.resupplyTime = resupplyTime;
        out.drownTime = drownTime;
        out.splashTimer = splashTimer;
        out.lastDrownFloor = lastDrownFloor;
        out.lastFogPos = lastFogPos;
        out.mounts = new WeaponMount[mounts.length];
        for(int i = 0; i < mounts.length; i++) out.mounts[i] = mounts[i].copy(context);
        out.isRotate = isRotate;
        out.isShooting = isShooting;
        out.aimX = aimX;
        out.aimY = aimY;
        out.statuses = statuses.copy();
        out.stackItem = stackItem;
        out.stackAmount = stackAmount;
        out.plans = plans.copy();
        out.updateBuilding = updateBuilding;
        out.buildCounter = buildCounter;
        out.mineTile = mineTile;
        out.mineTimer = mineTimer;
        out.shape = shape.copy();
        out.payloads = payloads == null ? null : payloads.copy(context);
        out.tether = context.map(tether);
        out.blockTile = context.map(blockTile);
        out.time = time;
        out.lifetime = lifetime;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(type.id()).i(updateIndex).f(x).f(y).f(rotation).f(vel.x).f(vel.y).f(drag);
        hasher.f(lastX).f(lastY).f(deltaX).f(deltaY).f(hitSize).bool(hasPhysicsRef);
        hasher.i(team).f(health).f(maxHealth).bool(dead).f(shield).f(armor);
        hasher.f(elevation).d(flag).bool(spawnedByCore);
        hasher.bool(controller != null);
        if(controller != null) controller.hash(hasher);
        hasher.i(abilities.length);
        for(UnitAbility ability : abilities) ability.hash(hasher);
        hasher.i(dockedType).bool(hasTarget).bool(wasPlayer).bool(wasHealed).bool(wasFlying);
        hasher.f(resupplyTime).f(drownTime).f(splashTimer).i(lastDrownFloor).i(lastFogPos);
        hasher.i(mounts.length);
        for(WeaponMount mount : mounts) mount.hash(hasher);
        hasher.bool(isRotate).bool(isShooting).f(aimX).f(aimY);
        statuses.hash(hasher);
        hasher.i(stackItem).i(stackAmount);
        plans.hash(hasher);
        hasher.bool(updateBuilding).f(buildCounter).i(mineTile).f(mineTimer);
        hasher.i(shape.locomotion().ordinal());
        shape.hash(hasher);
        hasher.bool(payloads != null);
        if(payloads != null) payloads.hash(hasher);
        hasher.i(tether == null ? -1 : tether.id()).f(time).f(lifetime);
        // only block units, which no world held before WP60, hash their turret
        if(blockTile != null) hasher.i(blockTile.id());
    }

    @Override
    public String toString(){
        return "Unit#" + id() + ":" + (type == null ? "unbound" : type.name());
    }
}
