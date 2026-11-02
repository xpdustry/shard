package com.xpdustry.shard.sim.fire;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.puddle.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.weather.*;
import com.xpdustry.shard.sim.world.*;

/**
 * One fire (vanilla's pooled {@code Fire} entity, {@code FireComp} with {@code TimedComp}): it burns on a tile for its
 * {@link #lifetime}, longer on a flammable building, spreads to a neighbour and throws fireballs while the tile's
 * items, liquids or puddle burn, and every 40 ticks damages the building and the ground units on its tile. It is in
 * {@code Groups.all} (its update, from {@code MISC}) and {@code Groups.sync}; {@link Fires} maps tiles to fires.
 *
 * <p>Vanilla draws four values for a fire whenever its object is built or reset ({@code animation}, the damage,
 * fireball and spread timers), so a pooled fire starts with the timers its object was reset with ({@link HazardPool}).
 * The animation and warmup only draw: nothing on a server reads them.
 *
 * <p>Every field is {@link Persistence#AUTHORITATIVE}. x and y are {@code PosComp}'s, set once.
 */
public final class Fire extends Entity implements Checkpointable{
    /** {@code Fires.baseLifetime}. */
    public static final float BASE_LIFETIME = 1000f;
    static final float SPREAD_DELAY = 22f, FIREBALL_DELAY = 40f, DAMAGE_DELAY = 40f, TILE_DAMAGE = 1.8f, UNIT_DAMAGE = 3f;
    /** {@code FireComp.frames}: the animation's frame count, whose draw ({@code Mathf.random(frames - 1)}) is kept. */
    static final int FRAMES = 40;
    private static final int WATER = Attributes.id("water");

    /** The tile it burns on, as {@code y * width + x}, or -1 for vanilla's {@code null}. */
    public int tile = -1;
    public float lifetime, time, x, y;
    public float puddleFlammability, damageTimer, spreadTimer, fireballTimer;
    /**
     * Which pooled {@code Fire} object it is ({@link HazardPool}): unlike the id, the number stays with the object through
     * the pool, so code that keeps a fire across ticks (a liquid turret's target) keeps this.
     */
    public int object = -1;

    /** A blank fire for a checkpoint, a fork or a save to fill. */
    public Fire(){
    }

    /**
     * {@code Fire.create()}: {@code Pools.obtain}, a free object with the timers it was reset with, or a new one that
     * draws them around its id, in its constructor's order. Not added.
     */
    public static Fire obtain(World world){
        var fire = new Fire();
        HazardPool pool = HazardPool.of(world);
        if(world.ids().free(Pooled.FIRE) > 0 && pool.freeFires() > 0){
            fire.created(world, Pooled.FIRE);
            pool.popFire(fire);
        }else{
            // the constructor: animation, damageTimer, fireballTimer, then id, then spreadTimer
            Rand rand = world.rand();
            Mathf.random(rand, FRAMES - 1);
            fire.damageTimer = Mathf.random(rand, DAMAGE_DELAY);
            fire.fireballTimer = Mathf.random(rand, FIREBALL_DELAY);
            fire.created(world, Pooled.FIRE);
            fire.spreadTimer = Mathf.random(rand, SPREAD_DELAY);
            fire.object = pool.newFireObject();
        }
        return fire;
    }

    /** {@code fin()}. */
    public float fin(){
        return time / lifetime;
    }

    /** The generated {@code add()}: {@code Groups.all}, then {@code sync}. */
    @Override
    public void add(){
        if(isAdded()) return;
        EntityGroups groups = EntityGroups.of(world());
        if(groups != null){
            groups.addAll(this);
            groups.addSync(this);
        }
        added(true);
        Fires.of(world()).added(this);
    }

    /**
     * The generated {@code remove()}: leaves the groups, clears its tile's fire ({@code Fires.remove(tile)}, whichever
     * fire that is), then {@code Groups.queueFree}.
     */
    @Override
    public void remove(){
        if(!isAdded()) return;
        EntityGroups groups = EntityGroups.of(world());
        if(groups != null){
            groups.removeAll(this);
            groups.removeSync(this);
        }
        added(false);
        Fires fires = Fires.of(world());
        fires.set(world(), tile, null);
        fires.removed(this);
        world().ids().queueFree(Pooled.FIRE);
        HazardPool.of(world()).queueReset(this);
    }

    /**
     * The generated {@code Fire.update()}: {@code FireComp.update} (FireComp.java:39), then {@code TimedComp.update}.
     * {@code FireComp}'s early returns only skip to the end of its own part, so the fire ages once more after its
     * removal.
     */
    @Override
    public void update(){
        World world = world();
        // animation and warmup: drawing only
        float speedMultiplier = 1f + Math.max(water(world) * 10f, 0);
        time = Mathf.clamp(time + speedMultiplier, 0, lifetime);
        if(!(time >= lifetime) && tile != -1 && !Float.isNaN(lifetime)){
            burn(world);
        }else{
            remove();
        }
        // TimedComp.update
        time = Math.min(time + 1f, lifetime);
        if(time >= lifetime) remove();
    }

    /** {@code state.envAttrs.get(Attribute.water)}: rain's share puts fires out faster. */
    private static float water(World world){
        Weathers weathers = Weathers.of(world);
        return weathers == null ? UnitCalls.rules(world).attributes.get(WATER) : weathers.envAttrs().get(WATER);
    }

    private void burn(World world){
        Rand rand = world.rand();
        Building entity = world.buildings().at(tile);
        boolean damage = entity != null;
        float flammability = Fires.flammability(world, tile) + puddleFlammability;
        if(!damage && flammability <= 0) time += 8f;
        if(damage) lifetime += Mathf.clamp(flammability / 8f, 0f, 0.6f);
        if(flammability > 1f && (spreadTimer += Mathf.clamp(flammability / 5f, 0.3f, 2f)) >= SPREAD_DELAY){
            spreadTimer = 0f;
            Point2 p = Geometry.d4.get(Mathf.random(rand, 3));
            TileGrid tiles = world.tiles();
            int tx = tiles.x(tile) + p.x(), ty = tiles.y(tile) + p.y();
            Fires.create(world, tiles.inBounds(tx, ty) ? tiles.pos(tx, ty) : -1);
        }
        if(flammability > 0 && (fireballTimer += Mathf.clamp(flammability / 10f, 0f, 0.5f)) >= FIREBALL_DELAY){
            fireballTimer = 0f;
            BulletDef fireball = world.content().bullet("fireball");
            float angle = Mathf.random(rand, 360f);
            if(fireball != null) world.bullets().types(world).get(fireball).createNet(world, Lightning.DERELICT, x, y, angle, -1f, 1f, 1f);
        }
        // damage nearby units and the building
        if((damageTimer += 1f) >= DAMAGE_DELAY){
            damageTimer = 0f;
            Puddle p = Puddles.get(world, tile);
            puddleFlammability = p != null ? p.flammability() / 3f : 0;
            if(damage) entity.damage(TILE_DAMAGE);
            int burning = StatusTable.of(world).id("burning");
            TileGrid tiles = world.tiles();
            Damage.damageUnits(world, Targets.ANY_TEAM, tiles.x(tile) * 8f, tiles.y(tile) * 8f, 8f, UNIT_DAMAGE,
                unit -> !unit.isFlying() && !Statuses.isImmune(unit, burning),
                unit -> unit.statuses.apply(unit, burning, 60 * 5));
        }
    }

    @Override
    public Copyable blank(){
        return new Fire();
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (Fire)into;
        out.tile = tile;
        out.lifetime = lifetime;
        out.time = time;
        out.x = x;
        out.y = y;
        out.puddleFlammability = puddleFlammability;
        out.damageTimer = damageTimer;
        out.spreadTimer = spreadTimer;
        out.fireballTimer = fireballTimer;
        out.object = object;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(tile).f(lifetime).f(time).f(x).f(y).f(puddleFlammability).f(damageTimer).f(spreadTimer).f(fireballTimer).i(object);
    }

    @Override
    public void write(CheckpointOut out){
        out.i("tile", tile).f("lifetime", lifetime).f("time", time).f("x", x).f("y", y).f("puddleFlammability", puddleFlammability)
            .f("damageTimer", damageTimer).f("spreadTimer", spreadTimer).f("fireballTimer", fireballTimer).i("object", object);
    }

    @Override
    public void read(CheckpointIn in){
        tile = in.i("tile");
        lifetime = in.f("lifetime");
        time = in.f("time");
        x = in.f("x");
        y = in.f("y");
        puddleFlammability = in.f("puddleFlammability");
        damageTimer = in.f("damageTimer");
        spreadTimer = in.f("spreadTimer");
        fireballTimer = in.f("fireballTimer");
        object = in.i("object");
    }

    @Override
    public String toString(){
        return "Fire#" + id() + "@" + tile;
    }
}
