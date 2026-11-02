package com.xpdustry.shard.sim.combat;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code Lightning} (Lightning.java): a chain of damage bullets that jumps to the furthest enemy within
 * reach, or wanders, and stops at an insulated enemy block. Its wander comes from a {@code Rand} seeded with
 * {@code lastSeed++}, a counter vanilla keeps for the whole JVM and Shard keeps per world ({@link Combat}); the jitter
 * of each segment draws from the world's random, as vanilla's {@code Mathf.range} does.
 */
public final class Lightning{
    /** {@code Team.derelict}, which lightning from explosions belongs to. */
    public static final int DERELICT = 0;
    private static final int MAX_CHAIN = 8;
    private static final float HIT_RANGE = 30f;

    private Lightning(){
    }

    /** {@code Lightning.create(bullet, color, damage, x, y, angle, length)}: the bullet's lightning type, team and filters. */
    public static void create(Bullet bullet, float damage, float x, float y, float targetAngle, int length){
        World world = bullet.world();
        BulletType type = bullet.type();
        BulletType hitCreate = type.lightningType == null ? damageLightning(world) : type.lightningType;
        internal(world, bullet, hitCreate, Combat.of(world).nextLightningSeed(), bullet.team(), damage, x, y, targetAngle, length);
    }

    /**
     * {@code Lightning.create(team, color, damage, x, y, angle, length)} or, with {@code hitCreate}, the overload taking
     * a bullet type; null is {@code Bullets.damageLightning}.
     */
    public static void create(World world, int team, BulletType hitCreate, float damage, float x, float y, float targetAngle, int length){
        internal(world, null, hitCreate == null ? damageLightning(world) : hitCreate, Combat.of(world).nextLightningSeed(), team, damage,
            x, y, targetAngle, length);
    }

    private static BulletType damageLightning(World world){
        BulletDef def = world.content().bullet("damage-lightning");
        return world.bullets().types(world).get(def);
    }

    private static void internal(World world, Bullet hitter, BulletType hitCreate, int seed, int team, float damage, float x, float y,
                                 float rotation, int length){
        // random.setSeed(seed)
        var random = new Rand(seed);
        var hit = new IntSet();
        var lines = new ArrayList<Vec2>();
        Rand rand = world.rand();
        SpatialIndex index = SpatialIndex.of(world);
        for(int i = 0; i < length / 2; i++){
            hitCreate.create(world, null, team, x, y, rotation, damage * (hitter == null ? 1f : hitter.damageMultiplier()), 1f, 1f);
            lines.add(new Vec2(x + Mathf.range(rand, 3f), y + Mathf.range(rand, 3f)));
            if(lines.size() > 1){
                Vec2 from = lines.get(lines.size() - 2);
                Vec2 to = lines.get(lines.size() - 1);
                boolean[] blocked = {false};
                Geometry.raycast(Math.round(from.x / 8f), Math.round(from.y / 8f), Math.round(to.x / 8f), Math.round(to.y / 8f), (wx, wy) -> {
                    Building build = world.buildings().at(wx, wy);
                    if(build != null && build.block().baked() && build.block().insulated() && build.team != team){
                        blocked[0] = true;
                        // snap it instead of removing
                        to.set(wx * 8f, wy * 8f);
                        return true;
                    }
                    return false;
                });
                if(blocked[0]) break;
            }
            var entities = new ArrayList<Unit>();
            if(hit.size < MAX_CHAIN && index != null){
                float cx = x, cy = y;
                // rect.setSize(hitRange).setCenter(x, y)
                index.nearbyEnemies(team, cx - HIT_RANGE / 2f, cy - HIT_RANGE / 2f, HIT_RANGE, HIT_RANGE, u -> {
                    if(!hit.contains(u.id()) && (hitter == null || Targets.checkTarget(u, hitter.type().collidesAir, hitter.type().collidesGround))){
                        entities.add(u);
                    }
                });
            }
            if(hitter != null && hitter.type().pierceCap > 0 && hit.size >= hitter.type().pierceCap) break;
            Unit furthest = null;
            float furthestDst = 0f;
            for(Unit unit : entities){
                float dst = unit.dst2(x, y);
                if(furthest == null || dst > furthestDst){
                    furthest = unit;
                    furthestDst = dst;
                }
            }
            if(furthest != null && furthest.within(x, y, HIT_RANGE * 2f)){
                hit.add(furthest.id());
                x = furthest.x;
                y = furthest.y;
            }else{
                rotation += random.range(20f);
                x += Angles.trnsx(rotation, HIT_RANGE / 2f);
                y += Angles.trnsy(rotation, HIT_RANGE / 2f);
            }
        }
    }
}
