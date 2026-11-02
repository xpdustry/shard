package com.xpdustry.shard.sim.combat;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.ai.team.*;
import com.xpdustry.shard.sim.blocks.defense.ExplosionShields;
import com.xpdustry.shard.sim.blocks.power.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code Damage} basics (Damage.java): area damage to units and buildings, area status, the dynamic
 * explosion of a death or destruction, fires from incendiary hits, and heal suppression. Teams are ids, with
 * {@link Targets#ANY_TEAM} for vanilla's {@code null} (hurts every team).
 *
 * <p>Units are found through the {@link SpatialIndex}'s team grids, in their cell order (vanilla's is its quadtrees'):
 * the same units are hit, and only the order of their deaths' events can differ when several die to one blast.
 * Building damage from a blast lands a tick later, in {@code TIME}, as vanilla's {@code Time.run(0f, …)} makes it, in
 * vanilla's own order ({@link TileDamage}).
 *
 * <p>Not ported: the lasers' line and point damage ({@code collideLine}, {@code collidePoint}, {@code collideLaser},
 * {@code linecast}, WP61). An explosion wave first asks the enemy force projectors to absorb it
 * ({@code ExplosionShields}, W3.6).
 */
public final class Damage{
    /** {@code Vars.minArmorDamage}. */
    public static final float MIN_ARMOR_DAMAGE = 0.1f;

    private Damage(){
    }

    /** {@code Damage.applyArmor}: armor subtracts, down to a tenth of the damage. */
    public static float applyArmor(float damage, float armor){
        return Math.max(damage - armor, MIN_ARMOR_DAMAGE * damage);
    }

    /** {@code calculateDamage}: falls off linearly to 40% at the edge. */
    static float calculateDamage(float dist, float radius, float damage){
        float falloff = 0.4f;
        float scaled = radius <= 0.00001f ? 1f : Mathf.lerp(1f - dist / radius, 1f, falloff);
        return damage * scaled;
    }

    /** {@code damage(team, x, y, radius, damage)}: units and buildings, not complete. */
    public static void damage(World world, int team, float x, float y, float radius, float damage){
        damage(world, team, x, y, radius, damage, false, true, true, false, null, 1f);
    }

    /** {@code damage(team, x, y, radius, damage, air, ground)}. */
    public static void damage(World world, int team, float x, float y, float radius, float damage, boolean air, boolean ground){
        damage(world, team, x, y, radius, damage, false, air, ground, false, null, 1f);
    }

    /** {@code damage(team, x, y, radius, damage, complete, air, ground)}. */
    public static void damage(World world, int team, float x, float y, float radius, float damage, boolean complete, boolean air, boolean ground){
        damage(world, team, x, y, radius, damage, complete, air, ground, false, null, 1f);
    }

    /**
     * {@code damage(team, x, y, radius, damage, complete, air, ground, scaled, source, armorMult)}
     * (Damage.java:509): every enemy unit within the radius takes falling-off damage and a push; buildings take a
     * raycast blast a tick later ({@link #tileDamage}), or, when {@code complete}, full damage on every tile now.
     */
    public static void damage(World world, int team, float x, float y, float radius, float damage, boolean complete, boolean air,
                              boolean ground, boolean scaled, Bullet source, float armorMult){
        UnitVisitor hit = unit -> {
            if(unit.team == team || !Targets.checkTarget(unit, air, ground) || !unit.hittable()
                || !unit.within(x, y, radius + (scaled ? unit.hitSize / 2f : 0f))){
                return;
            }
            float amount = calculateDamage(scaled ? Math.max(0, unit.dst(x, y) - unit.params().hitSize() / 2) : unit.dst(x, y), radius, damage);
            if(armorMult != 1){
                unit.damageArmorMult(amount, armorMult);
            }else{
                unit.damage(amount);
            }
            if(source != null) unit.controller.hit(unit, source.owner());
            // TODO better velocity displacement (vanilla's own)
            var push = new Vec2(unit.x - x, unit.y - y);
            float dst = push.len();
            unit.vel.add(push.setLength((radius > 0f ? 1f - dst / radius : 1f) * 2f / unit.mass()));
        };
        // rect.setSize(radius * 2).setCenter(x, y)
        float size = radius * 2f;
        float rx = x - size / 2f, ry = y - size / 2f;
        SpatialIndex index = SpatialIndex.of(world);
        if(index != null){
            if(team != Targets.ANY_TEAM){
                index.nearbyEnemies(team, rx, ry, size, size, hit);
            }else{
                index.intersect(rx, ry, size, size, hit);
            }
        }
        if(ground){
            float multiplier = source == null ? 1f : source.type().buildingDamageMultiplier;
            if(!complete){
                tileDamage(world, team, Math.round(x / 8f), Math.round(y / 8f), radius / 8f, damage * multiplier, source != null);
            }else{
                completeDamage(world, team, x, y, radius, damage * multiplier);
            }
        }
    }

    /** {@code damageUnits(team, x, y, size, damage, predicate, acceptor)}: hittable units overlapping the square. */
    public static void damageUnits(World world, int team, float x, float y, float size, float damage, UnitFilter predicate,
                                   UnitVisitor acceptor){
        float side = size * 2f, rx = x - side / 2f, ry = y - side / 2f;
        UnitVisitor hit = unit -> {
            if(!predicate.test(unit) || !unit.hittable()) return;
            float hs = unit.hitSize, left = unit.x - hs / 2f, bottom = unit.y - hs / 2f;
            if(!(left < rx + side && left + hs > rx && bottom < ry + side && bottom + hs > ry)) return;
            unit.damage(damage);
            acceptor.visit(unit);
        };
        SpatialIndex index = SpatialIndex.of(world);
        if(index == null) return;
        if(team != Targets.ANY_TEAM) index.nearbyEnemies(team, rx, ry, side, side, hit);
        else index.intersect(rx, ry, side, side, hit);
    }

    /**
     * {@code status(team, x, y, radius, effect, duration, air, ground, chance)}: every enemy unit within the radius
     * gets the status, if the chance holds.
     */
    public static void status(World world, int team, float x, float y, float radius, StatusDef effect, float duration, boolean air,
                              boolean ground, float chance){
        StatusApply statuses = StatusApply.of(world);
        UnitVisitor apply = unit -> {
            if(unit.team == team || !Targets.checkTarget(unit, air, ground) || !unit.hittable() || !unit.within(x, y, radius)) return;
            if(Mathf.chance(world.rand(), chance)) statuses.apply(unit, effect, duration);
        };
        float size = radius * 2f;
        SpatialIndex index = SpatialIndex.of(world);
        if(index == null) return;
        if(team != Targets.ANY_TEAM) index.nearbyEnemies(team, x - size / 2f, y - size / 2f, size, size, apply);
        else index.intersect(x - size / 2f, y - size / 2f, size, size, apply);
    }

    /**
     * {@code tileDamage(team, x, y, baseRadius, damage, source)} (Damage.java:561): queued with {@code Time.run(0f)}, so
     * it lands at the next {@code TIME} (or later in this one, when a timer task causes it).
     */
    public static void tileDamage(World world, int team, int x, int y, float baseRadius, float damage, boolean bullet){
        world.timers().run(0f, new TileDamage(team, x, y, baseRadius, damage, bullet));
    }

    /**
     * The body of {@code tileDamage}: a building of another team covering the centre takes a side's worth if it
     * survives; otherwise each building the rays reach takes the most any ray dealt it ({@link ExplosionDamage#tiles}),
     * in the order of vanilla's static {@code IntFloatMap}. The hits come in the order the rays first reached them,
     * which is the order vanilla's {@code put}s added the keys, so the map's layout is vanilla's. {@code bullet} only says
     * whether vanilla fired a bullet damage event, which the RTS AI hears ({@link TeamAis#buildDamaged}).
     */
    public record TileDamage(int team, int tx, int ty, float baseRadius, float damage, boolean bullet) implements Timers.Task{
        @Override
        public void run(World world){
            Buildings buildings = world.buildings();
            TileGrid tiles = world.tiles();
            int x = Mathf.clamp(tx, -100, tiles.width() + 100), y = Mathf.clamp(ty, -100, tiles.height() + 100);
            Building in = buildings.at(x, y);
            // spawned inside a multiblock: otherwise the building would absorb everything in one cell
            if(in != null && in.team != team && in.block().size() > 1 && in.health > damage){
                // deal the damage of an entire side, to be equivalent with maximum 'standard' damage
                in.damage(team, damage * Math.min(in.block().size(), baseRadius * 0.4f));
                // with a source bullet, damage(source, team, d) fires BuildDamageEvent (BuildingComp.java:1787)
                if(bullet) TeamAis.buildDamaged(in);
                return;
            }
            Combat combat = Combat.of(world);
            for(ExplosionDamage.Hit hit : ExplosionDamage.tiles(world, team, tx, ty, baseRadius, damage)){
                combat.putDamage(hit.pos(), hit.damage());
            }
            // apply damage, in the map's iteration order
            combat.damages().each((key, value) -> {
                Building build = buildings.at(Point2.x(key), Point2.y(key));
                if(build != null){
                    build.damage(team, Float.intBitsToFloat(value));
                    if(bullet) TeamAis.buildDamaged(build);
                }
            });
            combat.clearDamages();
        }
    }

    /** {@code completeDamage}: every tile of another team within the radius damages its building, once per tile. */
    static void completeDamage(World world, int team, float x, float y, float radius, float damage){
        int trad = (int)(radius / 8f);
        Buildings buildings = world.buildings();
        int cx = Math.round(x / 8f), cy = Math.round(y / 8f);
        for(int dx = -trad; dx <= trad; dx++){
            for(int dy = -trad; dy <= trad; dy++){
                if(!world.tiles().inBounds(cx + dx, cy + dy)) continue;
                Building build = buildings.at(cx + dx, cy + dy);
                if(build != null && (team == Targets.ANY_TEAM || team != build.team) && dx * dx + dy * dy <= trad * trad){
                    build.damage(team, damage);
                }
            }
        }
    }

    /** {@code createIncend(x, y, range, amount)}: fires at random tiles around (x, y). */
    public static void createIncend(World world, float x, float y, float range, int amount){
        Rand rand = world.rand();
        for(int i = 0; i < amount; i++){
            float cx = x + Mathf.range(rand, range);
            float cy = y + Mathf.range(rand, range);
            int tx = Math.round(cx / 8f), ty = Math.round(cy / 8f);
            if(world.tiles().inBounds(tx, ty)) Hazards.of(world).fire(world.tiles().pos(tx, ty));
        }
    }

    /**
     * {@code applySuppression(team, x, y, range, reload, maxDelay, applyParticleChance, source)}: enemy buildings within
     * range can't be healed for {@code reload + 1} ticks. The particles are client-side and draw nothing on a server.
     */
    public static void applySuppression(World world, int team, float x, float y, float range, float reload, float maxDelay,
                                        float applyParticleChance){
        Buildings buildings = world.buildings();
        for(int other = 0; other < TeamUnits.TEAMS; other++){
            if(other == team) continue;
            buildings.eachBlock(other, x, y, range, build -> build.applyHealSuppression(reload + 1f));
        }
    }

    /**
     * {@code dynamicExplosion(x, y, flammability, explosiveness, power, radius, damage, fire, ignoreTeam)}
     * (Damage.java:93): lightning from power, fireballs from flammability and damage waves from explosiveness, each
     * queued on the timers as vanilla's {@code Time.run} queues it. Shakes and effects are client-side.
     */
    public static void dynamicExplosion(World world, float x, float y, float flammability, float explosiveness, float power,
                                        float radius, boolean damage, boolean fire, int ignoreTeam){
        if(!damage) return;
        Rand rand = world.rand();
        Timers timers = world.timers();
        for(int i = 0; i < Mathf.clamp(power / 700, 0, 8); i++){
            int length = 5 + Mathf.clamp((int)(Mathf.pow(power, 0.98f) / 500), 1, 18);
            timers.run(i * 0.8f + Mathf.random(rand, 4f), new ExplosionLightning(x, y, power, length));
        }
        if(fire){
            for(int i = 0; i < Mathf.clamp(flammability / 4, 0, 30); i++){
                timers.run(i / 2f, new ExplosionFireball(x, y));
            }
        }
        int waves = explosiveness <= 2 ? 0 : Mathf.clamp((int)(explosiveness / 11), 1, 25);
        for(int i = 0; i < waves; i++){
            timers.run(i * 2f, new ExplosionWave(ignoreTeam, x, y, radius, explosiveness, i, waves));
        }
    }

    /** One of a dynamic explosion's lightning strikes, from derelict, at power-scaled damage. */
    public record ExplosionLightning(float x, float y, float power, int length) implements Timers.Task{
        @Override
        public void run(World world){
            Rand rand = world.rand();
            float angle = Mathf.random(rand, 360f);
            Lightning.create(world, Lightning.DERELICT, null, 3 + Mathf.pow(power, 0.35f), x, y, angle, length + Mathf.range(rand, 2));
        }
    }

    /** {@code Call.createBullet(Bullets.fireball, Team.derelict, x, y, Mathf.random(360f), fireball.damage, 1, 1)}. */
    public record ExplosionFireball(float x, float y) implements Timers.Task{
        @Override
        public void run(World world){
            BulletDef fireball = world.content().bullet("fireball");
            if(fireball == null) return;
            BulletType type = world.bullets().types(world).get(fireball);
            type.createNet(world, Lightning.DERELICT, x, y, Mathf.random(world.rand(), 360f), type.damage, 1f, 1f);
        }
    }

    /** One wave of a dynamic explosion: a widening ring of plain area damage, then the smoke effect's two draws. */
    public record ExplosionWave(int ignoreTeam, float x, float y, float radius, float explosiveness, int wave, int waves)
        implements Timers.Task{
        @Override
        public void run(World world){
            // an enemy force projector over the point absorbs the wave
            if(!ExplosionShields.absorbed(world, ignoreTeam, x, y, explosiveness / 2f)){
                damage(world, ignoreTeam, x, y, Mathf.clamp(radius + explosiveness, 0, 50f) * ((wave + 1f) / waves), explosiveness / 2f, false);
            }
            Rand rand = world.rand();
            // Fx.blockExplosionSmoke.at(x + Mathf.range(radius), y + Mathf.range(radius))
            Mathf.range(rand, radius);
            Mathf.range(rand, radius);
        }
    }

    /** {@code damage(team, x, y, radius, damage, complete)}: units and buildings. */
    public static void damage(World world, int team, float x, float y, float radius, float damage, boolean complete){
        damage(world, team, x, y, radius, damage, complete, true, true, false, null, 1f);
    }
}
