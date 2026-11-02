package com.xpdustry.shard.sim.blocks.power;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * What {@code Damage.damage(x, y, radius, damage)} hits, as data: the buildings {@code Damage.tileDamage}
 * (Damage.java:561) damages and by how much, and the damage a unit takes at a distance ({@code calculateDamage}). The
 * generator gate compares these with vanilla's; applying them is the area damage port's (WP41, {@link
 * GeneratorExplosions#damage}).
 *
 * <p>What applying needs beyond this: vanilla runs the tile pass in {@code Time.run(0f)}, so on the next timer update,
 * and walks its hits in the order of a static Arc {@code IntFloatMap}, a linear-probing table that only grows, so its
 * capacity depends on every explosion before it in the process; Shard keeps that capacity per world
 * ({@code combat.Combat}). {@link #tiles} returns the hits in the order the rays first reached them, which is the order
 * vanilla's {@code put}s add the keys in, so replaying them into such a table gives vanilla's layout.
 */
public final class ExplosionDamage{
    /** One building tile hit: {@code world.build(pos).damage(team, damage)}. */
    public record Hit(int pos, float damage){
    }

    /** {@code damage(team, x, y, radius, damage)}'s building pass: {@code tileDamage} at the tile of (x, y). */
    public static List<Hit> tiles(World world, int team, float x, float y, float radius, float damage){
        return tiles(world, team, toTile(x), toTile(y), radius / Footprint.TILE_SIZE, damage);
    }

    /**
     * The body of {@code tileDamage(team, tx, ty, baseRadius, damage)}: a multiblock at the centre that outlives the hit
     * takes a side's worth at once; otherwise a ray per angle walks out from the centre, each building it meets taking
     * what is left after the health of the ones before, falling off to the edge. A tile hit by several rays keeps the
     * largest. {@code team} is the attacker, never damaged; -1 for none.
     */
    public static List<Hit> tiles(World world, int team, int tx, int ty, float baseRadius, float damage){
        TileGrid grid = world.tiles();
        Buildings buildings = world.buildings();
        int x = Mathf.clamp(tx, -100, grid.width() + 100), y = Mathf.clamp(ty, -100, grid.height() + 100);

        Building in = buildings.at(x, y);
        if(in != null && in.team != team && in.block().size() > 1 && in.health > damage){
            return List.of(new Hit(Point2.pack(x, y), damage * Math.min(in.block().size(), baseRadius * 0.4f)));
        }

        float radius = Math.min(baseRadius, 100), rad2 = radius * radius;
        int rays = Mathf.ceil(radius * 2 * Mathf.pi);
        double spacing = Math.PI * 2.0 / rays;
        var index = new IntIntMap();
        var positions = new IntSeq();
        float[] amounts = new float[16];

        for(int i = 0; i <= rays; i++){
            float dealt = 0f;
            int startX = x, startY = y;
            // vanilla calls Math; StrictMath may differ from it in the last bit, which shows only on an exact integer
            int endX = x + (int)(StrictMath.cos(spacing * i) * radius), endY = y + (int)(StrictMath.sin(spacing * i) * radius);

            int xDist = Math.abs(endX - startX);
            int yDist = -Math.abs(endY - startY);
            int xStep = startX < endX ? 1 : -1;
            int yStep = startY < endY ? 1 : -1;
            int error = xDist + yDist;

            while(startX != endX || startY != endY){
                Building build = buildings.at(startX, startY);
                if(build != null && build.team != team){
                    // damage dealt at circle edge
                    float edgeScale = 0.6f;
                    float mult = (1f - (Mathf.dst2(startX, startY, x, y) / rad2) + edgeScale) / (1f + edgeScale);
                    float next = damage * mult - dealt;
                    int p = Point2.pack(startX, startY);
                    int at = index.get(p, -1);
                    if(at == -1){
                        at = positions.size;
                        index.put(p, at);
                        positions.add(p);
                        if(at == amounts.length) amounts = Arrays.copyOf(amounts, at * 2);
                        amounts[at] = 0f;
                    }
                    // IntFloatMap.get's default is 0, so a hit never registers below 0
                    amounts[at] = Math.max(amounts[at], next);
                    dealt += build.health;

                    if(next - dealt <= 0) break;
                }

                if(2 * error - yDist > xDist - 2 * error){
                    error += yDist;
                    startX += xStep;
                }else{
                    error += xDist;
                    startY += yStep;
                }
            }
        }

        var out = new ArrayList<Hit>(positions.size);
        for(int i = 0; i < positions.size; i++) out.add(new Hit(positions.get(i), amounts[i]));
        return out;
    }

    /** {@code calculateDamage(dist, radius, damage)}: a unit's share of an explosion at {@code dist} world units. */
    public static float unitDamage(float dist, float radius, float damage){
        float falloff = 0.4f;
        float scaled = radius <= 0.00001f ? 1f : Mathf.lerp(1f - dist / radius, 1f, falloff);
        return damage * scaled;
    }

    /** {@code World.toTile(coord)}. */
    private static int toTile(float coord){
        return Math.round(coord / Footprint.TILE_SIZE);
    }

    private ExplosionDamage(){
    }
}
