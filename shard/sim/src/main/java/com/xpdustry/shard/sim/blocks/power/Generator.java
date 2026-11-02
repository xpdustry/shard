package com.xpdustry.shard.sim.blocks.power;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.weather.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code PowerGenerator.GeneratorBuild} (PowerGenerator.java), the base of the generator kinds: it produces
 * {@code powerProduction * productionEfficiency} per tick while enabled, which its graph sums, and saves
 * {@link #productionEfficiency} and {@link #generateTime}. A kind sets {@code productionEfficiency} in its
 * {@code updateTile}.
 *
 * <p>Destroyed with {@code rules.reactorExplosions}, a generator whose {@link #warmup()} reached
 * {@code explosionMinWarmup} explodes after the base's item explosion ({@link #onExplosion()}): area damage, fires
 * ignited by chance and fireballs go through {@link GeneratorExplosions}; props in the radius break and puddles
 * spill here. Effects, sounds, shake and scorch marks are the client's.
 */
public abstract class Generator extends Building implements Checkpointable{
    public float generateTime;
    /** The efficiency of the producer: 1 is 100%. */
    public float productionEfficiency;
    @Derived("the block's generator parameters, read on first use")
    private Params params;

    /** {@code PowerGenerator}'s fields a server reads. */
    record Params(float powerProduction, int explosionRadius, int explosionDamage, int explosionPuddles,
        float explosionPuddleRange, float explosionPuddleAmount, int explosionPuddleLiquid, float explosionMinWarmup,
        boolean explosionBreaksProps, float explosionIgnitionChance, boolean explosionScaleIgnitionChance,
        float explosionSpeed, int explosionFireballs){
        static Params of(BlockDef block, ContentEpoch content){
            if(!block.baked()) return new Params(0f, 12, 0, 10, Footprint.TILE_SIZE * 2f, 100f, -1, 0f, true, 0f, true, 0.4f, 0);
            var fields = block.fields();
            String puddle = fields.s("explosionPuddleLiquid");
            return new Params(fields.f("powerProduction"), fields.i("explosionRadius"), fields.i("explosionDamage"),
                fields.i("explosionPuddles"), fields.f("explosionPuddleRange"), fields.f("explosionPuddleAmount"),
                puddle == null ? -1 : content.id(ContentType.LIQUID, puddle), fields.f("explosionMinWarmup"),
                fields.b("explosionBreaksProps"), fields.f("explosionIgnitionChance"), fields.b("explosionScaleIgnitionChance"),
                fields.f("explosionSpeed"), fields.i("explosionFireballs"));
        }
    }

    final Params params(){
        if(params == null) params = Params.of(block(), world().content());
        return params;
    }

    @Override
    public float getPowerProduction(){
        return enabled ? params().powerProduction() * productionEfficiency : 0f;
    }

    /**
     * {@code attribute.env()}: {@code state.envAttrs}, the rules' attributes plus the weather's, as of this tick's
     * {@code ENV} phase; the rules' alone in a world without weather.
     */
    protected final float env(int attribute){
        Weathers weathers = Weathers.of(world());
        return weathers != null ? weathers.envAttrs().get(attribute) : rules().attributes.get(attribute);
    }

    /** {@code warmup()}: how far the generator is running, which decides whether it explodes. */
    public float warmup(){
        return enabled ? productionEfficiency : 0f;
    }

    @Override
    public void onDestroyed(){
        super.onDestroyed();
        if(rules().reactorExplosions) createExplosion();
    }

    public boolean shouldExplode(){
        return warmup() >= params().explosionMinWarmup();
    }

    public final void createExplosion(){
        if(shouldExplode()) onExplosion();
    }

    /** {@code onExplosion()} (PowerGenerator.java:118), with vanilla's draws in its order. */
    public void onExplosion(){
        Params params = params();
        World world = world();
        Rand rand = world.rand();
        GeneratorExplosions explosions = GeneratorExplosions.of(world);
        if(params.explosionDamage() > 0){
            explosions.damage(this, x(), y(), params.explosionRadius() * Footprint.TILE_SIZE, params.explosionDamage());
        }
        if(params.explosionIgnitionChance() > 0 || params.explosionBreaksProps()){
            TileGrid tiles = world.tiles();
            int tx = tileX(), ty = tileY(), radius = params.explosionRadius();
            Geometry.circle(tx, ty, radius, (cx, cy) -> {
                boolean inside = tiles.inBounds(cx, cy);
                float dst = Mathf.dst(tx, ty, cx, cy);
                // create fires: the chance draws even outside the map
                if(params.explosionIgnitionChance() > 0 && Mathf.chance(rand, params.explosionIgnitionChance()
                    * (params.explosionScaleIgnitionChance() ? 1 - Mathf.sqrt(dst / radius) : 1))){
                    if(inside) explosions.fire(this, tiles.pos(cx, cy), dst / params.explosionSpeed());
                }
                // break boulders and the like (ConstructBlock.deconstructFinish: the tile becomes air)
                if(params.explosionBreaksProps() && inside && breakable(world.content().block(tiles.block(tiles.pos(cx, cy))))){
                    world.buildings().setBlock(cx, cy, world.content().block(0), 0, 0, world.buildings().cause());
                }
            });
        }
        if(params.explosionFireballs() > 0){
            int amount = Mathf.random(rand, 1, params.explosionFireballs());
            for(int i = 0; i < amount; i++){
                float angle = Mathf.random(rand, 360f), velocity = Mathf.random(rand, 0.5f, 1f);
                explosions.fireball(this, x(), y(), angle, velocity);
            }
        }
        if(params.explosionPuddleLiquid() != -1){
            BuildingEffects effects = BuildingEffects.of(world);
            TileGrid tiles = world.tiles();
            for(int i = 0; i < params.explosionPuddles(); i++){
                float angle = Mathf.random(rand, 360f), length = Mathf.random(rand, params.explosionPuddleRange());
                var offset = new Vec2().trns(angle, length);
                // world.tileWorld: World.toTile rounds
                int px = Math.round((x() + offset.x) / Footprint.TILE_SIZE), py = Math.round((y() + offset.y) / Footprint.TILE_SIZE);
                if(tiles.inBounds(px, py)){
                    int pos = tiles.pos(px, py);
                    effects.puddle(world, pos, pos, params.explosionPuddleLiquid(), params.explosionPuddleAmount(), true, false);
                }
            }
        }
    }

    /** {@code unitMoveBreakable}: a prop (boulders) that units and explosions break. */
    private static boolean breakable(BlockDef block){
        return block.baked() && block.fields().b("unitMoveBreakable");
    }

    @Override
    public void write(CheckpointOut out){
        out.f("generateTime", generateTime).f("productionEfficiency", productionEfficiency);
    }

    @Override
    public void read(CheckpointIn in){
        generateTime = in.f("generateTime");
        productionEfficiency = in.f("productionEfficiency");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (Generator)into;
        out.generateTime = generateTime;
        out.productionEfficiency = productionEfficiency;
        out.params = params;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(generateTime).f(productionEfficiency);
    }
}
