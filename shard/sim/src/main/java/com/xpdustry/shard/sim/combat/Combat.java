package com.xpdustry.shard.sim.combat;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.defense.ExplosionShields;
import com.xpdustry.shard.sim.blocks.power.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Combat's world state and its answers to the unit and building seams. The state is two statics vanilla keeps for the
 * whole JVM and Shard keeps per world: {@code Lightning.lastSeed}, and the size of {@code Damage.damages}, the
 * {@code IntFloatMap} {@code tileDamage} fills and clears but never shrinks, whose table size decides the order it
 * damages buildings in.
 *
 * <p>As {@link UnitDamage} it is {@code UnitComp.destroy}'s explosion and crash, and {@code LegsComp}'s leg splash;
 * as {@link UnitHooks}, the death weapons between them and {@code WeaponsComp.remove} (the abilities' update and
 * death are the hooks' default bodies, {@code sim.unit.ability}); as {@link BuildingEffects}, a destroyed building's explosion, liquid splash and destroy bullet;
 * as {@link GeneratorExplosions}, a generator's area damage, ignitions and fireballs. Every fire and puddle these
 * start goes to {@link Hazards}, whose owner is another work package; so do a building's leaks and a generator's
 * spill ({@link BuildingEffects#puddle}).
 *
 * <p>{@link Persistence#AUTHORITATIVE}: the seed and the table size; the map is empty between ticks.
 */
@Register
public final class Combat implements WorldPart, Checkpointable, UnitDamage, UnitHooks, BuildingEffects, GeneratorExplosions{
    /** {@code new IntFloatMap()}'s table: 51 entries at a load factor of 0.8. */
    static final int DAMAGES_TABLE = 64;
    private static final float LOAD_FACTOR = 0.8f;

    private int lightningSeed;
    /** The table size of vanilla's {@code damages} map, which only grows. */
    private int damagesTable = DAMAGES_TABLE;
    @Derived("empty between ticks; a new map of damagesTable entries")
    private IntIntMap damages;
    @Derived("empty between ticks: counts damages' entries while tileDamage fills it")
    private int damagesSize;
    @Derived("new Armory(world.bullets().types(world)) on first use")
    private Armory armory;

    public Combat(){
        damages = newDamages(DAMAGES_TABLE);
    }

    private Combat(Combat source){
        lightningSeed = source.lightningSeed;
        damagesTable = source.damagesTable;
        damages = newDamages(damagesTable);
        armory = source.armory;
    }

    private static IntIntMap newDamages(int table){
        return new IntIntMap((int)(table * LOAD_FACTOR), LOAD_FACTOR);
    }

    /** The world's combat part. */
    public static Combat of(World world){
        Combat combat = world.find(Combat.class);
        if(combat == null) throw new IllegalStateException("this world has no " + Combat.class.getName() + " part");
        return combat;
    }

    /** The world's weapons, built over its bullet types on first use. */
    Armory armory(World world){
        Armory armory = this.armory;
        BulletTypes bullets = world.bullets().types(world);
        if(armory == null || armory.bullets() != bullets) this.armory = armory = new Armory(bullets);
        return armory;
    }

    /** {@code lastSeed++}. */
    int nextLightningSeed(){
        return lightningSeed++;
    }

    /** {@code tileDamage}'s map, keys {@code Point2.pack(x, y)}, values float bits. */
    IntIntMap damages(){
        return damages;
    }

    /** {@code damages.put(key, value)}, following the table's growth as Arc's {@code put} grows it. */
    void putDamage(int key, float value){
        boolean added = !damages.containsKey(key);
        damages.put(key, Float.floatToRawIntBits(value));
        if(!added) return;
        // the zero key is kept apart and never grows the table
        if(key == 0){
            damagesSize++;
        }else if(++damagesSize >= (int)(damagesTable * LOAD_FACTOR)){
            damagesTable <<= 1;
        }
    }

    /** {@code damages.clear()}: empty, at the size it grew to. */
    void clearDamages(){
        damages.clear();
        damagesSize = 0;
    }

    // ---------------------------------------------------------------- UnitDamage

    /**
     * {@code Damage.dynamicExplosion(x, y, flammability, explosiveness, power, radius, damage, item().flammability > 1,
     * team, …)} (UnitComp.java:892).
     */
    @Override
    public void deathExplosion(Unit unit, float flammability, float explosiveness, float power, float radius){
        World world = unit.world();
        Rules rules = UnitCalls.rules(world);
        // an empty stack's item only decides fires its zero flammability never starts
        ItemDef item = unit.stackAmount > 0 ? world.content().item(unit.stackItem) : null;
        boolean fire = item != null && item.baked() && item.flammability() > 1;
        Damage.dynamicExplosion(world, unit.x, unit.y, flammability, explosiveness, power, radius,
            rules.damageExplosions && rules.unitCrashDamage(unit.team) > 0, fire, unit.team);
    }

    // ---------------------------------------------------------------- UnitHooks

    /** The weapons that shoot on death (UnitComp.java:911). */
    @Override
    public void destroyed(Unit unit){
        Weapons.shootOnDeath(unit);
    }

    /** {@code WeaponsComp.remove}: the unit's continuous bullets end. */
    @Override
    public void removed(Unit unit){
        Weapons.removed(unit);
    }

    /** The crash of a flying wreck: complete ground damage, scaled by its size (UnitComp.java:924). */
    @Override
    public void crash(Unit unit){
        Rules rules = UnitCalls.rules(unit.world());
        float crashDamage = Mathf.pow(unit.hitSize, 0.75f) * unit.type().crashDamageMultiplier() * 2.5f * rules.unitCrashDamage(unit.team);
        // an enemy force projector over the wreck absorbs the crash
        if(!ExplosionShields.absorbed(unit.world(), unit.team, unit.x, unit.y, crashDamage)){
            Damage.damage(unit.world(), unit.team, unit.x, unit.y, Mathf.pow(unit.hitSize, 0.94f) * 1.25f, crashDamage, true, false, true);
        }
    }

    /**
     * {@code Damage.damage(team, x, y, legSplashRange, legSplashDamage * unitDamage, false, true)}; the deconstruction
     * of a {@code unitMoveBreakable} block there is construction's (WP30).
     */
    @Override
    public void legSplash(Unit unit, float x, float y){
        Rules rules = UnitCalls.rules(unit.world());
        UnitParams.LegParams legs = unit.params().legs();
        Damage.damage(unit.world(), unit.team, x, y, legs.splashRange(), legs.splashDamage() * rules.unitDamage(unit.team), false, true);
    }

    // ---------------------------------------------------------------- BuildingEffects

    /** {@code Damage.dynamicExplosion(x, y, …, damage, explosionFx, baseShake)}: fire on, no team spared (BuildingComp.java:1525). */
    @Override
    public void explode(Building building, float flammability, float explosiveness, float power, float radius, boolean damage){
        Damage.dynamicExplosion(building.world(), building.x(), building.y(), flammability, explosiveness, power, radius, damage, true,
            Targets.ANY_TEAM);
    }

    /**
     * {@code splashLiquid(liquid, amount)} (BuildingComp.java:1467): a puddle every half tick, each at a random tile
     * under the building, drawn when it lands.
     */
    @Override
    public void splashLiquid(Building building, int liquid, float amount){
        float splash = Mathf.clamp(amount / 4f, 0f, 10f);
        for(int i = 0; i < Mathf.clamp(amount / 5, 0, 30); i++){
            building.world().timers().run(i / 2f, new Splash(building.x(), building.y(), building.block().size(), liquid, splash));
        }
    }

    /** One of {@code splashLiquid}'s puddles: {@code Puddles.deposit(world.tileWorld(x + range, y + range), liquid, splash)}. */
    public record Splash(float x, float y, int size, int liquid, float splash) implements Timers.Task{
        @Override
        public void run(World world){
            Rand rand = world.rand();
            // vanilla's range is an int (block.size * tilesize / 2), so it calls Mathf.range(int): whole pixels from -4 to 4
            int range = size * Footprint.TILE_SIZE / 2;
            int tile = tileWorld(world, x + Mathf.range(rand, range), y + Mathf.range(rand, range));
            if(tile != -1) Hazards.of(world).puddle(tile, liquid, splash);
        }
    }

    /** {@code Puddles.deposit(tile, source, liquid, amount, initial, cap)}: a leak or a generator's spill. */
    @Override
    public void puddle(World world, int tile, int source, int liquid, float amount, boolean initial, boolean cap){
        Hazards.of(world).puddle(tile, source, liquid, amount, initial, cap);
    }

    /** {@code afterDestroyed()}: the block's destroy bullet, derelict unless it keeps the team, at a seeded angle. */
    @Override
    public void afterDestroyed(Building building){
        BlockDef block = building.block();
        if(!block.baked()) return;
        String name = block.destroyBullet();
        if(name == null) return;
        World world = building.world();
        BulletType type = world.bullets().types(world).get(world.content().bullet(name));
        int team = block.destroyBulletSameTeam() ? building.team : Lightning.DERELICT;
        type.create(world, building, team, building.x(), building.y(), Mathf.randomSeed(building.id(), 360f));
    }

    // ---------------------------------------------------------------- GeneratorExplosions

    /** {@code Damage.damage(x, y, radius, damage)}: no team spared; the building pass lands on the next timer update. */
    @Override
    public void damage(Building generator, float x, float y, float radius, float damage){
        Damage.damage(generator.world(), Targets.ANY_TEAM, x, y, radius, damage);
    }

    @Override
    public void fire(Building generator, int tile, float delay){
        generator.world().timers().run(delay, new Ignition(tile));
    }

    /** {@code Time.run(dst / explosionSpeed, () -> Fires.create(t))} (PowerGenerator.java:131). */
    public record Ignition(int tile) implements Timers.Task{
        @Override
        public void run(World world){
            Hazards.of(world).fire(tile);
        }
    }

    @Override
    public void fireball(Building generator, float x, float y, float angle, float velocityScl){
        World world = generator.world();
        BulletDef fireball = world.content().bullet("fireball");
        if(fireball == null) return;
        world.bullets().types(world).get(fireball).createNet(world, Lightning.DERELICT, x, y, angle, -1f, velocityScl, 1f);
    }

    /** {@code world.tileWorld(x, y)} as a tile position, or -1 outside the map. */
    private static int tileWorld(World world, float x, float y){
        int tx = Math.round(x / Footprint.TILE_SIZE), ty = Math.round(y / Footprint.TILE_SIZE);
        return world.tiles().inBounds(tx, ty) ? world.tiles().pos(tx, ty) : -1;
    }

    // ---------------------------------------------------------------- part

    @Override
    public WorldPart copy(CopyContext context){
        return new Combat(this);
    }

    @Override
    public void hash(Hasher hasher){
        hasher.i(lightningSeed).i(damagesTable);
    }

    @Override
    public void write(CheckpointOut out){
        out.i("lightningSeed", lightningSeed).i("damagesTable", damagesTable);
    }

    @Override
    public void read(CheckpointIn in){
        lightningSeed = in.i("lightningSeed");
        damagesTable = in.i("damagesTable");
        if(damagesTable < DAMAGES_TABLE || Integer.bitCount(damagesTable) != 1) throw new CheckpointException("bad damages table size " + damagesTable);
        damages = newDamages(damagesTable);
        damagesSize = 0;
    }
}
