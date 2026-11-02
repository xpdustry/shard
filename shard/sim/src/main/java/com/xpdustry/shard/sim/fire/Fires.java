package com.xpdustry.shard.sim.fire;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.puddle.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * A world's fires (vanilla's {@code Fires} and {@code Tiles.fires}): which fire burns on each tile, and the calls that
 * start, cool and find them. Tiles are {@code y * width + x}, and -1 is vanilla's {@code null} tile, which does
 * nothing. Its {@link Hazards} view is how combat reaches fires and puddles.
 *
 * <p>The tile map is {@link Persistence#AUTHORITATIVE}: vanilla clears a tile's entry when any fire that names the tile
 * is removed, and a save may hold two fires on one tile, so the map can't be rebuilt from the fires. The fires by object
 * ({@link #objectPosition}) are derived from the added fires.
 */
@Register
public final class Fires implements WorldPart, Checkpointable, Hazards{
    /** By tile; null until the first fire, then sized to the map. */
    private Fire[] tiles;
    /**
     * By object number ({@link Fire#object}): the added fire that is that object, or null. Fires keep it as they are added
     * and removed; null until first asked after a fork or a load.
     */
    @Derived("rebuilt from the added fires when first asked after a fork or a load")
    private Fire[] objects;

    public Fires(){
    }

    private Fires(Fires source, CopyContext context){
        if(source.tiles != null){
            tiles = new Fire[source.tiles.length];
            for(int i = 0; i < tiles.length; i++){
                if(source.tiles[i] != null) tiles[i] = context.map(source.tiles[i]);
            }
        }
    }

    /** The world's fires. */
    public static Fires of(World world){
        Fires fires = world.find(Fires.class);
        if(fires == null) throw new IllegalStateException("this world has no " + Fires.class.getName() + " part");
        return fires;
    }

    /**
     * {@code Fires.create(tile)} (Fires.java:17): starts a fire on {@code tile}, or gives the one there its full
     * lifetime back. Nothing happens on tile -1, with fire off in the rules or without oxygen in the environment.
     */
    public static void create(World world, int tile){
        Rules rules = UnitCalls.rules(world);
        if(tile < 0 || !rules.fire || !rules.hasEnv(Env.OXYGEN)) return;
        Fires fires = of(world);
        Fire fire = fires.get(tile);
        if(fire == null){
            fire = Fire.obtain(world);
            TileGrid grid = world.tiles();
            fire.tile = tile;
            fire.lifetime = Fire.BASE_LIFETIME;
            fire.x = grid.x(tile) * 8f;
            fire.y = grid.y(tile) * 8f;
            fire.add();
            fires.set(world, tile, fire);
        }else{
            fire.lifetime = Fire.BASE_LIFETIME;
            fire.time = 0f;
        }
    }

    /** {@code Fires.extinguish(tile, intensity)}: shortens the fire on {@code tile}, if any, by {@code intensity} ticks. */
    public static void extinguish(World world, int tile, float intensity){
        if(tile < 0) return;
        Fire fire = of(world).get(tile);
        // Fx.steam.at(fire)
        if(fire != null) fire.time += intensity;
    }

    /** {@code Fires.has(x, y)}: an added fire that hasn't burnt out stands on exactly (x, y). */
    public static boolean has(World world, int x, int y){
        TileGrid grid = world.tiles();
        if(!grid.inBounds(x, y)) return false;
        Fire fire = of(world).get(grid.pos(x, y));
        return fire != null && fire.isAdded() && fire.fin() < 1f && fire.tile != -1 && grid.x(fire.tile) == x && grid.y(fire.tile) == y;
    }

    /** {@code Fires.get(x, y)}: the fire mapped to (x, y), or null, also outside the map. */
    public static Fire get(World world, int x, int y){
        TileGrid grid = world.tiles();
        return grid.inBounds(x, y) ? of(world).get(grid.pos(x, y)) : null;
    }

    /**
     * Where pooled {@code Fire} object {@code object} ({@link Fire#object}) stands, as vanilla code that keeps the object
     * (a liquid turret's target) reads its x and y: the added fire using it; else, if removed and not yet reset, where it
     * burnt; else (0, 0), as the pool's {@code reset()} leaves it. The added fires are looked up by object (review 40:
     * every turret scanning every entity each tick cost turrets times entities).
     */
    public static void objectPosition(World world, int object, Vec2 out){
        Fire fire = of(world).added(world, object);
        if(fire != null){
            out.x = fire.x;
            out.y = fire.y;
        }else if(!HazardPool.of(world).removedPosition(object, out)){
            out.x = 0f;
            out.y = 0f;
        }
    }

    /** The added fire that is object {@code object}, or null; the first in {@code Groups.all} order, were there two. */
    private Fire added(World world, int object){
        if(objects == null){
            objects = new Fire[16];
            ActiveList<Entity> all = EntityGroups.of(world).all();
            for(int i = all.size() - 1; i >= 0; i--) if(all.get(i) instanceof Fire fire) put(fire);
        }
        Fire fire = object >= 0 && object < objects.length ? objects[object] : null;
        return fire != null && fire.isAdded() ? fire : null;
    }

    /** {@code fire} was added: it is its object now. */
    void added(Fire fire){
        if(objects != null) put(fire);
    }

    /** {@code fire} was removed: its object is no added fire until one takes it. */
    void removed(Fire fire){
        if(objects != null && fire.object >= 0 && fire.object < objects.length && objects[fire.object] == fire) objects[fire.object] = null;
    }

    private void put(Fire fire){
        if(fire.object < 0) return;
        if(fire.object >= objects.length) objects = Arrays.copyOf(objects, Math.max(fire.object + 1, objects.length * 2));
        objects[fire.object] = fire;
    }

    /** {@code Fires.get(tile)}: the fire mapped to {@code tile}, or null. */
    public Fire get(int tile){
        return tiles == null || tile < 0 || tile >= tiles.length ? null : tiles[tile];
    }

    /** {@code Fires.register(fire)}: maps its tile to it, as reading a save or a snapshot does. */
    public void register(World world, Fire fire){
        if(fire.tile != -1) set(world, fire.tile, fire);
    }

    /** Sets or, with null, clears ({@code Fires.remove(tile)}) the fire of {@code tile}. */
    void set(World world, int tile, Fire fire){
        if(tile < 0) return;
        if(tiles == null){
            if(fire == null) return;
            tiles = new Fire[world.tiles().size()];
        }
        tiles[tile] = fire;
    }

    /**
     * {@code Tile.getFlammability()} (Tile.java:142): an empty tile burns as its floor's liquid; a building as its items
     * and liquids, each weighted by its capacity.
     */
    public static float flammability(World world, int tile){
        TileGrid grid = world.tiles();
        ContentEpoch content = world.content();
        if(grid.table().isAir(grid.block(tile))){
            if(content.block(grid.floor(tile)) instanceof FloorDef floor && floor.liquidDrop() != null) return floor.liquidDrop().flammability();
            return 0;
        }
        Building build = world.buildings().at(tile);
        if(build == null) return 0;
        BlockDef block = build.block();
        float result = 0f;
        if(block.hasItems() && build.items != null && build.items.total() > 0){
            result += build.items.sum((item, amount) -> content.item(item).flammability() * amount) / Math.max(block.itemCapacity(), 1)
                * Mathf.clamp(block.itemCapacity() / 2.4f, 1f, 3f);
        }
        if(block.hasLiquids() && build.liquids != null){
            result += build.liquids.sum((liquid, amount) -> content.liquid(liquid).flammability() * amount / 1.6f) / Math.max(block.liquidCapacity(), 1)
                * Mathf.clamp(block.liquidCapacity() / 30f, 1f, 2f);
        }
        return result;
    }

    // ---------------------------------------------------------------- Hazards

    /** The seam bound to {@code world}: fires here, puddles in {@link Puddles}. */
    @Override
    public Hazards in(World world){
        return new Seam(world);
    }

    /** Combat's view of a world's fires and puddles. */
    record Seam(World world) implements Hazards{
        @Override
        public void fire(int tile){
            create(world, tile);
        }

        @Override
        public boolean hasFire(int x, int y){
            return has(world, x, y);
        }

        @Override
        public void extinguish(int tile, float intensity){
            Fires.extinguish(world, tile, intensity);
        }

        @Override
        public void puddle(int tile, int source, int liquid, float amount, boolean initial, boolean cap){
            Puddles.deposit(world, tile, source, liquid, amount, initial, cap);
        }
    }

    // ---------------------------------------------------------------- state

    @Override
    public WorldPart copy(CopyContext context){
        return new Fires(this, context);
    }

    @Override
    public void hash(Hasher hasher){
        int count = 0;
        if(tiles != null){
            for(Fire fire : tiles) if(fire != null) count++;
        }
        hasher.i(count);
        if(tiles == null) return;
        for(int tile = 0; tile < tiles.length; tile++){
            Fire fire = tiles[tile];
            if(fire == null) continue;
            hasher.i(tile);
            fire.hashState(hasher);
        }
    }

    /** The whole map, by tile (empty before the first fire); mostly nulls, which cost little once compressed. */
    @Override
    public void write(CheckpointOut out){
        Fire[] all = tiles == null ? new Fire[0] : tiles;
        out.refs("tiles", all, all.length);
    }

    @Override
    public void read(CheckpointIn in){
        Fire[] all = in.refs("tiles", Fire.class);
        if(all == null) throw new CheckpointException("the fire map is missing");
        tiles = all.length == 0 ? null : all;
        objects = null;
    }
}
