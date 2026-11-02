package com.xpdustry.shard.sim.bullet;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's line and point damage (Damage.java:146–433), which the beam bullets of WP61 deal: {@code collideLine} and
 * {@code collideLaser} hit everything along a line, nearest first, up to a pierce cap; {@code findPierceLength} and
 * {@code findLaserLength} shorten the line at the cap or a laser-absorbing block; {@code collidePoint} hits one point;
 * {@code linecast} finds the first thing on a line; {@code findAbsorber} the first laser-absorbing enemy block.
 *
 * <p>Buildings come from the 4-connected or diagonal tile walks vanilla uses; units from the spatial index's team grids,
 * whose order only matters for units at exactly the same distance.
 */
public final class Lasers{
    private static final float EXPAND = 3f;

    private Lasers(){
    }

    /** {@code vec.trnsExact(angle, length)}: rotated with the exact cosine and sine, as vanilla's lasers do. */
    static Vec2 trnsExact(float angle, float length){
        float radians = angle * Mathf.degreesToRadians;
        float cos = (float)StrictMath.cos(radians), sin = (float)StrictMath.sin(radians);
        return new Vec2(length * cos, length * sin);
    }

    /** {@code World.toTile(coord)}. */
    static int toTile(float coord){
        return Math.round(coord / 8f);
    }

    /** {@code Damage.findAbsorber(team, x1, y1, x2, y2)}: the first laser-absorbing block of another team on the line. */
    public static Building findAbsorber(World world, int team, float x1, float y1, float x2, float y2){
        Building[] found = {null};
        boolean hit = Geometry.raycast(toTile(x1), toTile(y1), toTile(x2), toTile(y2), (x, y) -> {
            Building build = world.buildings().at(x, y);
            found[0] = build;
            return build != null && build.team != team && build.block().baked() && build.block().absorbLasers();
        });
        return hit ? found[0] : null;
    }

    /** {@code Damage.findLength(b, length, laser, pierceCap)}. */
    static float findLength(Bullet b, float length, boolean laser, int pierceCap){
        if(pierceCap > 0){
            length = findPierceLength(b, pierceCap, laser, length);
        }else if(laser){
            length = findLaserLength(b, length);
        }
        return length;
    }

    /** {@code Damage.findLaserLength(b, length)}: up to the first laser-absorbing block of another team, at least 6. */
    static float findLaserLength(Bullet b, float length){
        World world = b.world();
        Vec2 vec = trnsExact(b.rotation(), length);
        int[] furthest = {-1};
        TileGrid tiles = world.tiles();
        boolean found = Geometry.raycast(b.tileX(), b.tileY(), toTile(b.x() + vec.x), toTile(b.y() + vec.y), (x, y) -> {
            if(!tiles.inBounds(x, y)){
                furthest[0] = -1;
                return false;
            }
            furthest[0] = tiles.pos(x, y);
            Building build = world.buildings().at(x, y);
            // furthest.team() is its building's team, derelict without one
            int team = build == null ? 0 : build.team;
            return team != b.team() && build != null && absorbsLasers(build);
        });
        return found && furthest[0] != -1 ? Math.max(6f, b.dst(tiles.x(furthest[0]) * 8f, tiles.y(furthest[0]) * 8f)) : length;
    }

    static boolean absorbsLasers(Building build){
        BlockDef block = build.block();
        return block.baked() && block.absorbLasers();
    }

    /**
     * {@code Damage.findPierceLength(b, pierceCap, laser, length)}: the distance to the {@code pierceCap}-th thing on
     * the line (at least 6), or the whole length if there are fewer, and never past a laser-absorbing block.
     */
    static float findPierceLength(Bullet b, int pierceCap, boolean laser, float length){
        World world = b.world();
        BulletType type = b.type();
        Vec2 vec = trnsExact(b.rotation(), length);
        // rect.setPosition(b.x, b.y).setSize(vec.x, vec.y).normalize().grow(3f)
        float rx = b.x(), ry = b.y(), rw = vec.x, rh = vec.y;
        if(rw < 0){
            rx += rw;
            rw = -rw;
        }
        if(rh < 0){
            ry += rh;
            rh = -rh;
        }
        rx -= 1.5f;
        ry -= 1.5f;
        rw += 3f;
        rh += 3f;
        float[] maxDst = {Float.POSITIVE_INFINITY};
        var distances = new FloatList();
        if(type.collidesGround && type.collidesTiles){
            Geometry.raycast(b.tileX(), b.tileY(), toTile(b.x() + vec.x), toTile(b.y() + vec.y), (x, y) -> {
                // add the distance to the list so it can be processed
                Building build = world.buildings().at(x, y);
                if(build != null && build.team != b.team() && BulletCollider.collide(build, b) && BulletUpdate.checkUnderBuild(b, build, x * 8f, y * 8f)){
                    distances.add(b.dst(build.x(), build.y()));
                    if(laser && absorbsLasers(build)){
                        maxDst[0] = Math.min(maxDst[0], b.dst(build.x(), build.y()));
                        return true;
                    }
                }
                return false;
            });
        }
        SpatialIndex index = SpatialIndex.of(world);
        if(index != null){
            float x2 = b.x() + vec.x, y2 = b.y() + vec.y;
            index.nearbyEnemies(b.team(), rx, ry, rw, rh, u -> {
                float hs = u.hitSize;
                if(Targets.checkTarget(u, type.collidesAir, type.collidesGround) && u.hittable()
                    && intersectSegmentRectangle(b.x(), b.y(), x2, y2, u.x - hs / 2f, u.y - hs / 2f, hs, hs)){
                    distances.add(Mathf.dst(u.x, u.y, b.x(), b.y()));
                }
            });
        }
        distances.sort();
        // either the length when not enough things were pierced, or the last pierced object
        return Math.min(distances.size < pierceCap || pierceCap <= 0 ? length : Math.max(6f, distances.get(pierceCap - 1)), maxDst[0]);
    }

    /** {@code Damage.collideLaser(b, length, large, laser, pierceCap)}: the line, its length kept in {@code fdata}. */
    public static float collideLaser(Bullet b, float length, boolean large, boolean laser, int pierceCap){
        float resultLength = findPierceLength(b, pierceCap, laser, length);
        collideLine(b, b.team(), b.x(), b.y(), b.rotation(), resultLength, large, laser, pierceCap);
        b.fdata(resultLength);
        return resultLength;
    }

    /** One thing a line hits, where it hits it. */
    private record Collided(float x, float y, Entity target){
    }

    /**
     * {@code Damage.collideLine(hitter, team, x, y, angle, length, large, laser, pierceCap)} (Damage.java:256): every
     * building on the 4-connected walk (and, if {@code large} or the line crosses them, its four neighbours) and every
     * enemy unit whose grown hitbox the line enters, nearest first, until the bullet's damage or the pierce cap runs out.
     */
    public static void collideLine(Bullet hitter, int team, float x, float y, float angle, float length, boolean large, boolean laser,
                                   int pierceCap){
        World world = hitter.world();
        BulletType type = hitter.type();
        length = findLength(hitter, length, laser, pierceCap);
        hitter.fdata(length);
        var collidedBlocks = new IntSet();
        Vec2 vec = trnsExact(angle, length);
        var collided = new ArrayList<Collided>();
        TileGrid tiles = world.tiles();
        Buildings buildings = world.buildings();
        if(type.collidesGround && type.collidesTiles){
            float x2 = x + vec.x, y2 = y + vec.y;
            raycastEachNoDiagonal(toTile(x), toTile(y), toTile(x2), toTile(y2), (cx, cy) -> {
                Building tile = buildings.at(cx, cy);
                boolean collide = tile != null && BulletCollider.collide(tile, hitter) && BulletUpdate.checkUnderBuild(hitter, tile, cx * 8f, cy * 8f)
                    && ((tile.team != team && BulletCollider.collide(tile, hitter)) || type.testCollision(hitter, tile)) && collidedBlocks.add(tile.id());
                if(collide){
                    collided.add(new Collided(cx * 8f, cy * 8f, tile));
                    for(Point2 p : Geometry.d4){
                        int ox = p.x() + cx, oy = p.y() + cy;
                        if(!tiles.inBounds(ox, oy)) continue;
                        // other.getBounds(): the tile's square
                        if(large || intersectSegmentRectangle(x, y, x2, y2, ox * 8f - 4f, oy * 8f - 4f, 8f, 8f)){
                            Building build = buildings.at(ox, oy);
                            if(build != null && BulletUpdate.checkUnderBuild(hitter, build, cx * 8f, cy * 8f) && collidedBlocks.add(build.id())){
                                // vanilla quirk kept: (p.x + cx * tilesize), not (p.x + cx) * tilesize
                                collided.add(new Collided(p.x() + cx * 8f, (p.y() + cy) * 8f, build));
                            }
                        }
                    }
                }
                return false;
            });
        }
        // rect.setPosition(x, y).setSize(vec.x, vec.y).normalize().grow(expand * 2f)
        float rx = x, ry = y, rw = vec.x, rh = vec.y;
        if(rw < 0){
            rx += rw;
            rw = -rw;
        }
        if(rh < 0){
            ry += rh;
            rh = -rh;
        }
        rx -= EXPAND;
        ry -= EXPAND;
        rw += EXPAND * 2f;
        rh += EXPAND * 2f;
        float x2 = vec.x + x, y2 = vec.y + y;
        SpatialIndex index = SpatialIndex.of(world);
        if(index != null){
            index.nearbyEnemies(team, rx, ry, rw, rh, u -> {
                if(Targets.checkTarget(u, type.collidesAir, type.collidesGround) && u.hittable()){
                    // u.hitbox(hitrect); hitrect.grow(expand * 2)
                    float hs = u.hitSize;
                    Vec2 at = BulletUpdate.raycastRect(x, y, x2, y2, u.x - hs / 2f - EXPAND, u.y - hs / 2f - EXPAND, hs + EXPAND * 2f, hs + EXPAND * 2f);
                    if(at != null) collided.add(new Collided(at.x, at.y, u));
                }
            });
        }
        int[] collideCount = {0};
        float hx = hitter.x(), hy = hitter.y();
        // Seq.sort(Floatf): a stable sort by squared distance
        collided.sort((a, c) -> Float.compare(Mathf.dst2(hx, hy, a.x(), a.y()), Mathf.dst2(hx, hy, c.x(), c.y())));
        for(Collided c : collided){
            if(hitter.damage() > 0 && (pierceCap <= 0 || collideCount[0] < pierceCap)){
                if(c.target() instanceof Unit u){
                    // u.collision(hitter, x, y) only tells its controller (WP54)
                    BulletUpdate.collision(hitter, u, c.x(), c.y());
                    collideCount[0]++;
                }else if(c.target() instanceof Building tile){
                    float health = tile.health;
                    if(tile.team != team && BulletCollider.collide(tile, hitter)){
                        BulletCollider.collision(tile, hitter);
                        type.hit(hitter, c.x(), c.y());
                        collideCount[0]++;
                    }
                    // try to heal the tile
                    if(type.testCollision(hitter, tile)){
                        type.hitTile(hitter, tile, c.x(), c.y(), health, false);
                    }
                }
            }
        }
    }

    /** {@code Damage.collidePoint(hitter, team, effect, x, y)}: the building and the enemy units at one point. */
    public static void collidePoint(Bullet hitter, int team, float x, float y){
        World world = hitter.world();
        BulletType type = hitter.type();
        if(type.collidesGround){
            Building build = world.buildings().at(toTile(x), toTile(y));
            if(build != null && hitter.damage() > 0){
                float health = build.health;
                if(build.team != team && BulletCollider.collide(build, hitter)){
                    BulletCollider.collision(build, hitter);
                    type.hit(hitter, x, y);
                }
                // try to heal the tile
                if(type.testCollision(hitter, build)){
                    type.hitTile(hitter, build, x, y, health, false);
                }
            }
        }
        SpatialIndex index = SpatialIndex.of(world);
        if(index == null) return;
        // rect.setCentered(x, y, 1f)
        index.nearbyEnemies(team, x - 0.5f, y - 0.5f, 1f, 1f, u -> {
            if(Targets.checkTarget(u, type.collidesAir, type.collidesGround) && u.hittable()){
                BulletUpdate.collision(hitter, u, x, y);
            }
        });
    }

    /**
     * {@code Damage.linecast(hitter, x, y, angle, length)} (Damage.java:369): the first enemy building on the diagonal
     * walk or the enemy unit nearest the start whose grown hitbox the line enters, whichever is nearer; null for none.
     */
    public static Entity linecast(Bullet hitter, float x, float y, float angle, float length){
        World world = hitter.world();
        BulletType type = hitter.type();
        Vec2 vec = new Vec2().trns(angle, length);
        Building[] building = {null};
        if(type.collidesGround){
            Geometry.raycast(toTile(x), toTile(y), toTile(x + vec.x), toTile(y + vec.y), (cx, cy) -> {
                Building tile = world.buildings().at(cx, cy);
                if(tile != null && tile.team != hitter.team()){
                    building[0] = tile;
                    return true;
                }
                return false;
            });
        }
        float rx = x, ry = y, rw = vec.x, rh = vec.y;
        float x2 = vec.x + x, y2 = vec.y + y;
        if(rw < 0){
            rx += rw;
            rw *= -1;
        }
        if(rh < 0){
            ry += rh;
            rh *= -1;
        }
        ry -= EXPAND;
        rx -= EXPAND;
        rw += EXPAND * 2;
        rh += EXPAND * 2;
        Unit[] unit = {null};
        SpatialIndex index = SpatialIndex.of(world);
        if(index != null){
            index.nearbyEnemies(hitter.team(), rx, ry, rw, rh, e -> {
                if((unit[0] != null && Mathf.dst2(e.x, e.y, x, y) > Mathf.dst2(unit[0].x, unit[0].y, x, y))
                    || !Targets.checkTarget(e, type.collidesAir, type.collidesGround) || !e.targetable(hitter.team())) return;
                float hs = e.hitSize;
                float ox = e.x - hs / 2f - EXPAND, oy = e.y - hs / 2f - EXPAND, ow = hs + EXPAND * 2, oh = hs + EXPAND * 2;
                if(BulletUpdate.raycastRect(x, y, x2, y2, ox, oy, ow, oh) != null) unit[0] = e;
            });
        }
        Building tmpBuilding = building[0];
        Unit tmpUnit = unit[0];
        if(tmpBuilding != null && tmpUnit != null){
            if(Mathf.dst2(x, y, tmpBuilding.x(), tmpBuilding.y()) <= Mathf.dst2(x, y, tmpUnit.x, tmpUnit.y)){
                return tmpBuilding;
            }
        }else if(tmpBuilding != null){
            return tmpBuilding;
        }
        return tmpUnit;
    }

    /** {@code World.raycastEachNoDiagonal}: the 4-connected walk, until {@code cons} returns true. */
    static void raycastEachNoDiagonal(int startX, int startY, int endX, int endY, Geometry.Raycaster cons){
        int xDist = Math.abs(endX - startX);
        int yDist = -Math.abs(endY - startY);
        int xStep = startX < endX ? +1 : -1;
        int yStep = startY < endY ? +1 : -1;
        int error = xDist + yDist;
        while(true){
            if(cons.accept(startX, startY) || (startX == endX && startY == endY)) break;
            if(2 * error - yDist > xDist - 2 * error){
                error += yDist;
                startX += xStep;
            }else{
                error += xDist;
                startY += yStep;
            }
        }
    }

    /** Arc's {@code Intersector.intersectSegmentRectangle}: a side crossed, or the start inside. */
    static boolean intersectSegmentRectangle(float startX, float startY, float endX, float endY, float rx, float ry, float rw, float rh){
        float ex = rx + rw, ey = ry + rh;
        return intersectSegments(startX, startY, endX, endY, rx, ry, rx, ey)
            || intersectSegments(startX, startY, endX, endY, rx, ry, ex, ry)
            || intersectSegments(startX, startY, endX, endY, ex, ry, ex, ey)
            || intersectSegments(startX, startY, endX, endY, rx, ey, ex, ey)
            || (rx <= startX && rx + rw >= startX && ry <= startY && ry + rh >= startY);
    }

    /** Arc's {@code Intersector.intersectSegments}, without the intersection point. */
    static boolean intersectSegments(float x1, float y1, float x2, float y2, float x3, float y3, float x4, float y4){
        float d = (y4 - y3) * (x2 - x1) - (x4 - x3) * (y2 - y1);
        if(d == 0) return false;
        float yd = y1 - y3;
        float xd = x1 - x3;
        float ua = ((x4 - x3) * yd - (y4 - y3) * xd) / d;
        if(ua < 0 || ua > 1) return false;
        float ub = ((x2 - x1) * yd - (y2 - y1) * xd) / d;
        return !(ub < 0 || ub > 1);
    }

    /** Arc's {@code FloatSeq}, just what the pierce length needs. */
    private static final class FloatList{
        float[] items = new float[8];
        int size;

        void add(float value){
            if(size == items.length) items = Arrays.copyOf(items, size * 2);
            items[size++] = value;
        }

        float get(int index){
            return items[index];
        }

        void sort(){
            Arrays.sort(items, 0, size);
        }
    }
}
