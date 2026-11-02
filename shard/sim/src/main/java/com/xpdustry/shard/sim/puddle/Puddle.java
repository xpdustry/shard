package com.xpdustry.shard.sim.puddle;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.fire.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;

/**
 * One puddle (vanilla's pooled {@code Puddle} entity, {@code PuddleComp}): a liquid on a tile that evaporates by its
 * viscosity, overflows to its four neighbours above two thirds full, wets the ground units on it, sets a building on it
 * alight when hot, and runs its liquid's own update (neoplasm, {@link CellLiquid}). It is in {@code Groups.all} (its
 * update, from {@code MISC}) and {@code Groups.sync}; {@link Puddles} maps tiles to puddles.
 *
 * <p>Vanilla quirk kept: a puddle whose tile maps to another puddle leaves {@code Groups.all} without being freed, and
 * stays in {@code Groups.sync}, so snapshots keep sending it.
 *
 * <p>Every field is {@link Persistence#AUTHORITATIVE}. x and y are {@code PosComp}'s, set once. The effect and ripple
 * times are drawn when the object is built or reset ({@link HazardPool}), but nothing on a server reads them.
 */
public final class Puddle extends Entity implements Checkpointable{
    /** {@code Puddles.maxLiquid}. */
    public static final float MAX_LIQUID = 70f;

    /** The tile it lies on, as {@code y * width + x}, or -1 for vanilla's {@code null}. */
    public int tile = -1;
    /** The liquid's content id, or -1 for none. */
    public int liquid = -1;
    public float amount, accepting, updateTime, x, y;

    /** A blank puddle for a checkpoint, a fork or a save to fill. */
    public Puddle(){
    }

    /** {@code Puddle.create()}: a free object, or a new one that draws its effect and ripple times around its id. Not added. */
    public static Puddle obtain(World world){
        var puddle = new Puddle();
        if(world.ids().free(Pooled.PUDDLE) > 0){
            puddle.created(world, Pooled.PUDDLE);
        }else{
            // the constructor: effectTime, id, lastRipple
            Mathf.random(world.rand(), 50f);
            puddle.created(world, Pooled.PUDDLE);
            Mathf.random(world.rand(), 40f);
        }
        return puddle;
    }

    public LiquidDef liquidDef(){
        return liquid < 0 ? null : world().content().liquid(liquid);
    }

    /** {@code getFlammability()}. */
    public float flammability(){
        return liquidDef().flammability() * amount;
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
    }

    /** The generated {@code remove()}: leaves the groups, clears its tile's puddle, then {@code Groups.queueFree}. */
    @Override
    public void remove(){
        if(!isAdded()) return;
        EntityGroups groups = EntityGroups.of(world());
        if(groups != null){
            groups.removeAll(this);
            groups.removeSync(this);
        }
        added(false);
        Puddles.of(world()).set(world(), tile, null);
        world().ids().queueFree(Pooled.PUDDLE);
        HazardPool.of(world()).queueReset(Pooled.PUDDLE);
    }

    /** The generated {@code Puddle.update()}: {@code PuddleComp.update} (PuddleComp.java:47). */
    @Override
    public void update(){
        World world = world();
        LiquidDef liquid = liquidDef();
        if(liquid == null || tile == -1){
            remove();
            return;
        }
        float addSpeed = accepting > 0 ? 3f : 0f;
        amount -= (1f - liquid.viscosity()) / (5f + addSpeed);
        amount += accepting;
        amount = Math.min(amount, MAX_LIQUID);
        accepting = 0f;
        TileGrid tiles = world.tiles();
        if(amount >= MAX_LIQUID / 1.5f){
            float deposited = Math.min((amount - MAX_LIQUID / 1.5f) / 4f, 0.3f);
            int targets = 0;
            for(Point2 point : Geometry.d4){
                int ox = tiles.x(tile) + point.x(), oy = tiles.y(tile) + point.y();
                if(!tiles.inBounds(ox, oy)) continue;
                int other = tiles.pos(ox, oy);
                if(tiles.table().isAir(tiles.block(other)) || liquid.moveThroughBlocks()){
                    targets++;
                    Puddles.deposit(world, other, tile, this.liquid, deposited, false, false);
                }
            }
            amount -= deposited * targets;
        }
        if(liquid.capPuddles()) amount = Mathf.clamp(amount, 0, MAX_LIQUID);
        if(amount <= 0f){
            remove();
            return;
        }
        if(Puddles.get(world, tile) != this && isAdded()){
            // force removal without pool free
            EntityGroups groups = EntityGroups.of(world);
            if(groups != null) groups.removeAll(this);
            added(false);
            return;
        }
        // effects-only code, which still wets units and starts fires
        if(amount >= MAX_LIQUID / 2f && updateTime <= 0f){
            wet(world, liquid);
            if(liquid.temperature() > 0.7f && world.buildings().at(tile) != null && Mathf.chance(world.rand(), 0.5)){
                Fires.create(world, tile);
            }
            updateTime = 40f;
            // tile.build.puddleOn(this): no block overrides it
        }
        updateTime -= 1f;
        CellLiquid.update(this, liquid);
    }

    /** {@code Units.nearby(rect, unitCons)}: the liquid's status on grounded, non-hovering units under the puddle. */
    private void wet(World world, LiquidDef liquid){
        SpatialIndex index = SpatialIndex.of(world);
        StatusDef effect = liquid.effect();
        if(index == null || effect == null) return;
        // rect.setSize(size).setCenter(x, y)
        float size = Mathf.clamp(amount / (MAX_LIQUID / 1.5f)) * 10f;
        float rx = x - size / 2f, ry = y - size / 2f;
        index.intersect(rx, ry, size, size, unit -> {
            if(!unit.isGrounded() || unit.type().hovering()) return;
            float hs = unit.hitSize, ux = unit.x - hs / 2f, uy = unit.y - hs / 2f;
            // rect.overlaps(unit.hitbox)
            if(rx < ux + hs && rx + size > ux && ry < uy + hs && ry + size > uy){
                unit.statuses.apply(unit, effect.id(), 60 * 2);
            }
        });
    }

    @Override
    public Copyable blank(){
        return new Puddle();
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (Puddle)into;
        out.tile = tile;
        out.liquid = liquid;
        out.amount = amount;
        out.accepting = accepting;
        out.updateTime = updateTime;
        out.x = x;
        out.y = y;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(tile).i(liquid).f(amount).f(accepting).f(updateTime).f(x).f(y);
    }

    @Override
    public void write(CheckpointOut out){
        out.i("tile", tile).i("liquid", liquid).f("amount", amount).f("accepting", accepting).f("updateTime", updateTime)
            .f("x", x).f("y", y);
    }

    @Override
    public void read(CheckpointIn in){
        tile = in.i("tile");
        liquid = in.i("liquid");
        amount = in.f("amount");
        accepting = in.f("accepting");
        updateTime = in.f("updateTime");
        x = in.f("x");
        y = in.f("y");
    }

    @Override
    public String toString(){
        return "Puddle#" + id() + "@" + tile;
    }
}
