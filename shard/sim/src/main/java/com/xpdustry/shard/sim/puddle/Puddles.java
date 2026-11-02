package com.xpdustry.shard.sim.puddle;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.fire.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.weather.*;
import com.xpdustry.shard.sim.world.*;

/**
 * A world's puddles (vanilla's {@code Puddles} and {@code Tiles.puddles}): which puddle lies on each tile, and
 * {@link #deposit}, which pours liquid on a tile: it boils off in a hot environment, flies off as a blob in space,
 * reacts with a liquid floor or a puddle of another liquid (fires, steam, neoplasm), or fills the tile's puddle.
 * Tiles are {@code y * width + x}, -1 for vanilla's {@code null}; liquids are content ids.
 *
 * <p>The tile map is {@link Persistence#AUTHORITATIVE}: a puddle can lose its tile to another and live on unmapped
 * ({@link Puddle}), so the map can't be rebuilt from the puddles.
 */
@Register
public final class Puddles implements WorldPart, Checkpointable{
    private static final int HEAT = Attributes.id("heat");

    /** By tile; null until the first puddle, then sized to the map. */
    private Puddle[] tiles;

    public Puddles(){
    }

    private Puddles(Puddles source, CopyContext context){
        if(source.tiles != null){
            tiles = new Puddle[source.tiles.length];
            for(int i = 0; i < tiles.length; i++){
                if(source.tiles[i] != null) tiles[i] = context.map(source.tiles[i]);
            }
        }
    }

    /** The world's puddles. */
    public static Puddles of(World world){
        Puddles puddles = world.find(Puddles.class);
        if(puddles == null) throw new IllegalStateException("this world has no " + Puddles.class.getName() + " part");
        return puddles;
    }

    /** {@code Puddles.get(tile)}: the puddle mapped to {@code tile}, or null. */
    public static Puddle get(World world, int tile){
        return of(world).get(tile);
    }

    public Puddle get(int tile){
        return tiles == null || tile < 0 || tile >= tiles.length ? null : tiles[tile];
    }

    /** {@code Puddles.hasLiquid(tile, liquid)}: at least half a unit of {@code liquid} lies there. */
    public static boolean hasLiquid(World world, int tile, int liquid){
        Puddle p = get(world, tile);
        return p != null && p.liquid == liquid && p.amount >= 0.5f;
    }

    /** {@code Puddles.register(puddle)}: maps its tile to it, as reading a save or a snapshot does. */
    public void register(World world, Puddle puddle){
        set(world, puddle.tile, puddle);
    }

    /** Sets or, with null, clears ({@code Puddles.remove(tile)}) the puddle of {@code tile}. */
    void set(World world, int tile, Puddle puddle){
        if(tile < 0) return;
        if(tiles == null){
            if(puddle == null) return;
            tiles = new Puddle[world.tiles().size()];
        }
        tiles[tile] = puddle;
    }

    /** {@code Puddles.deposit(tile, liquid, amount)}. */
    public static void deposit(World world, int tile, int liquid, float amount){
        deposit(world, tile, tile, liquid, amount, true, false);
    }

    /**
     * {@code Puddles.deposit(tile, source, liquid, amount, initial, cap)} (Puddles.java:31): pours {@code amount} of
     * {@code liquid} on {@code tile}, from {@code source} (where it flowed from; the same tile for a spill).
     * {@code cap} keeps a reaction from overfilling a puddle of another liquid. {@code initial} only picks ripple
     * effects.
     */
    public static void deposit(World world, int tile, int source, int liquid, float amount, boolean initial, boolean cap){
        if(tile < 0) return;
        TileGrid grid = world.tiles();
        ContentEpoch content = world.content();
        LiquidDef def = content.liquid(liquid);
        Rand rand = world.rand();
        float ax = (grid.x(tile) * 8f + grid.x(source) * 8f) / 2f, ay = (grid.y(tile) * 8f + grid.y(source) * 8f) / 2f;
        if(willBoil(world, def)){
            // liquid.vaporEffect.at(ax, ay, gasColor)
            Mathf.chanceDelta(rand, 0.16f);
            return;
        }
        if(UnitCalls.rules(world).hasEnv(Env.SPACE)){
            if(Mathf.chanceDelta(rand, 0.11f) && tile != source){
                spaceLiquid(world, tile, source, ax, ay);
            }
            return;
        }
        BlockDef floor = content.block(grid.floor(tile));
        LiquidDef drop = floor instanceof FloorDef f && f.isLiquid() ? f.liquidDrop() : null;
        if(drop != null && !canStayOn(def, drop)){
            reactPuddle(world, drop, def, amount, tile, ax, ay);
            // ripple effect
            return;
        }
        if(floor.solid()) return;
        Puddles puddles = of(world);
        Puddle p = puddles.get(tile);
        if(p == null || p.liquid == -1){
            Puddle puddle = Puddle.obtain(world);
            puddle.tile = tile;
            puddle.liquid = liquid;
            puddle.amount = Math.min(amount, Puddle.MAX_LIQUID);
            puddle.x = ax;
            puddle.y = ay;
            puddles.register(world, puddle);
            puddle.add();
        }else if(p.liquid == liquid){
            p.accepting = Math.max(amount, p.accepting);
            // ripple effect
        }else{
            float added = reactPuddle(world, p.liquidDef(), def, amount, p.tile, (p.x + grid.x(source) * 8f) / 2f, (p.y + grid.y(source) * 8f) / 2f);
            if(cap) added = Mathf.clamp(Puddle.MAX_LIQUID - p.amount, 0f, added);
            p.amount += added;
        }
    }

    /** {@code liquid.willBoil()}: the environment's heat reaches its boiling point. */
    static boolean willBoil(World world, LiquidDef liquid){
        Weathers weathers = Weathers.of(world);
        float heat = weathers == null ? UnitCalls.rules(world).attributes.get(HEAT) : weathers.envAttrs().get(HEAT);
        return heat >= liquid.boilPoint();
    }

    /** {@code canStayOn(liquid, other)}: oil floats on water, neoplasm on most liquids. */
    static boolean canStayOn(LiquidDef liquid, LiquidDef other){
        return liquid.canStayOn().contains(other.name());
    }

    /**
     * {@code Bullets.spaceLiquid.create(null, source.team(), ax, ay, source.angleTo(tile) + Mathf.range(50f), -1f,
     * Mathf.random(0f, 0.2f), Mathf.random(0.6f, 1f), liquid)}: in space, a leak flies off as a blob.
     */
    private static void spaceLiquid(World world, int tile, int source, float ax, float ay){
        Rand rand = world.rand();
        TileGrid grid = world.tiles();
        float angle = Mathf.angle(grid.x(tile) * 8f - grid.x(source) * 8f, grid.y(tile) * 8f - grid.y(source) * 8f) + Mathf.range(rand, 50f);
        float velocity = Mathf.random(rand, 0f, 0.2f), life = Mathf.random(rand, 0.6f, 1f);
        BulletDef def = world.content().bullet("space-liquid");
        Building build = world.buildings().at(source);
        int team = build == null ? Lightning.DERELICT : build.team;
        if(def != null) world.bullets().types(world).get(def).create(world, null, team, ax, ay, angle, -1f, velocity, life);
    }

    /** {@code reactPuddle(dest, liquid, amount, tile, x, y)} (Puddles.java:115): what pouring {@code liquid} onto {@code dest} adds. */
    private static float reactPuddle(World world, LiquidDef dest, LiquidDef liquid, float amount, int tile, float x, float y){
        if(dest == null) return 0f;
        Rand rand = world.rand();
        if((dest.flammability() > 0.3f && liquid.temperature() > 0.7f) || (liquid.flammability() > 0.3f && dest.temperature() > 0.7f)){
            // flammable liquid + hot liquid
            Fires.create(world, tile);
            if(Mathf.chance(rand, 0.006 * amount)){
                BulletDef fireball = world.content().bullet("fireball");
                float angle = Mathf.random(rand, 360f);
                if(fireball != null) world.bullets().types(world).get(fireball).createNet(world, Lightning.DERELICT, x, y, angle, -1f, 1f, 1f);
            }
        }else if(dest.temperature() > 0.7f && liquid.temperature() < 0.55f){
            // cold liquid poured onto a hot puddle: Fx.steam
            Mathf.chance(rand, 0.5f * amount);
            return -0.1f * amount;
        }else if(liquid.temperature() > 0.7f && dest.temperature() < 0.55f){
            // hot liquid poured onto a cold puddle: Fx.steam
            Mathf.chance(rand, 0.8f * amount);
            return -0.7f * amount;
        }
        return CellLiquid.react(dest, liquid, amount);
    }

    // ---------------------------------------------------------------- state

    @Override
    public WorldPart copy(CopyContext context){
        return new Puddles(this, context);
    }

    @Override
    public void hash(Hasher hasher){
        int count = 0;
        if(tiles != null){
            for(Puddle puddle : tiles) if(puddle != null) count++;
        }
        hasher.i(count);
        if(tiles == null) return;
        for(int tile = 0; tile < tiles.length; tile++){
            Puddle puddle = tiles[tile];
            if(puddle == null) continue;
            hasher.i(tile);
            puddle.hashState(hasher);
        }
    }

    /** The whole map, by tile (empty before the first puddle); mostly nulls, which cost little once compressed. */
    @Override
    public void write(CheckpointOut out){
        Puddle[] all = tiles == null ? new Puddle[0] : tiles;
        out.refs("tiles", all, all.length);
    }

    @Override
    public void read(CheckpointIn in){
        Puddle[] all = in.refs("tiles", Puddle.class);
        if(all == null) throw new CheckpointException("the puddle map is missing");
        tiles = all.length == 0 ? null : all;
    }
}
