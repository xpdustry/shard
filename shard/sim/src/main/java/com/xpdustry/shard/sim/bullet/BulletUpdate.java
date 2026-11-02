package com.xpdustry.shard.sim.bullet;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.registry.*;
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
 * {@code Groups.bullet.update()} then {@code Groups.bullet.collide()}, the end of {@code Logic.updateEntities}
 * (Logic.java:495). The update is the generated {@code Bullet.update()}: {@code BulletComp.update} (movement, the
 * mover, acceleration, the type's update, the tile raycast, the pierce cap, keep-alive), then {@code TimedComp.update}
 * (age, and removal at the end of life). The collision pass is {@code EntityCollisions.updateCollision}: each bullet
 * sweeps the box from its last position to its current one against the enemy teams' unit grids, and stops at the
 * first hit that removes it.
 *
 * <p>Both passes keep {@code EntityGroup}'s cursor: a bullet removed during its turn hands its row to the last bullet,
 * which is visited next, and bullets made during the pass (frags) are visited in the same pass.
 *
 * <p>Candidate units come as vanilla's do ({@code BulletComp.getCollisions}): per enemy team, in id order, that team's
 * quadtree in its own {@code intersect} order ({@code SpatialIndex.nearbyEnemies}). When one bullet overlaps two units
 * on the same tick, a non-piercing bullet hits the first found, so the order decides which ({@code BulletOrderOracleTest}).
 *
 * <p>Worlds whose content has no bullets (toy content) have no bullet behaviour; their tests move the table
 * themselves.
 */
@Register
public final class BulletUpdate implements PhaseHandler{
    static final KindKey STATIC_WALL = KindKey.of("static-wall");

    @Override
    public Phase phase(){
        return Phase.BULLETS;
    }

    @Override
    public void run(World world){
        Bullets bullets = world.bullets();
        if(bullets.types(world).size() == 0) return;
        // one handle moved along each pass, and one candidate buffer: nothing per bullet
        var handle = new Bullet(world, bullets);
        bullets.each(row -> update(handle.rebind(row)));
        var candidates = new Candidates();
        bullets.each(row -> collide(handle.rebind(row), candidates));
    }

    /**
     * The units {@link #collide} found, reused across a pass. A call appends its own candidates after any an enclosing
     * call still holds and drops them when it returns, so a collision that nests another sees its own slice.
     */
    static final class Candidates implements UnitVisitor{
        Unit[] units = new Unit[16];
        int size;

        @Override
        public void visit(Unit unit){
            if(size == units.length) units = Arrays.copyOf(units, size * 2);
            units[size++] = unit;
        }
    }

    /** The generated {@code Bullet.update()}: {@code BulletComp.update}, then {@code TimedComp.update}. */
    static void update(Bullet b){
        BulletType type = b.type();
        Bullets t = b.table();
        int r = b.row();
        // for one frame, bullets do not move: bullet.update() runs right after the weapon made it
        if(!t.justSpawned[r]){
            t.x[r] += t.velX[r];
            t.y[r] += t.velY[r];
            float scale = Math.max(1f - type.drag, 0);
            t.velX[r] *= scale;
            t.velY[r] *= scale;
        }
        t.justSpawned[r] = false;
        Mover mover = t.mover[r];
        if(mover != null) mover.move(b);
        if(type.accel != 0){
            Vec2 vel = b.vel();
            b.vel(vel.setLength(vel.len() + type.accel));
        }
        type.update(b);
        Entity sticky = b.stickyTarget();
        if(sticky != null){
            // only stick to things that still exist in the world
            if(Targets.isValid(sticky)){
                t = b.table();
                r = b.row();
                float rotate = sticky instanceof Unit unit ? unit.rotation - t.stickyRotation[r] : 0f;
                Vec2 at = new Vec2(t.stickyX[r], t.stickyY[r]).rotate(rotate).add(Targets.x(sticky), Targets.y(sticky));
                b.set(at.x, at.y);
                t.rotation[r] = rotate + t.stickyRotationOffset[r];
                b.vel(b.vel().setAngle(t.rotation[r]));
            }
        }else if(type.collidesTiles && type.collides && type.collidesGround){
            tileRaycast(b, Math.round(b.lastX() / 8f), Math.round(b.lastY() / 8f), b.tileX(), b.tileY());
        }
        if(type.removeAfterPierce && type.pierceCap != -1 && b.collidedCount() >= type.pierceCap){
            b.hit(true);
            b.remove();
        }
        t = b.table();
        r = b.row();
        if(t.keepAlive[r]){
            t.time[r] -= 1f;
            t.keepAlive[r] = false;
        }
        // TimedComp.update
        t.time[r] = Math.min(t.time[r] + 1f, t.lifetime[r]);
        if(t.time[r] >= t.lifetime[r]) b.remove();
    }

    /** {@code checkUnderBuild}: whether a bullet may hit {@code build}, which may sit under bullets. */
    static boolean checkUnderBuild(Bullet b, Building build, float x, float y){
        BlockDef block = build.block();
        if(!(block.baked() && block.underBullets())) return true;
        int aim = b.aimTile();
        Building aimed = aim < 0 ? null : b.world().buildings().at(aim);
        BulletType type = b.type();
        return aimed == build
            // bullet type allows hitting under bullets
            || type.hitUnder
            // same team has no 'under build' mechanics
            || build.team == b.team()
            // a piercing bullet overshot the aim tile, it's fine to hit things now
            || (type.pierce && aim >= 0 && Mathf.dst(x, y, b.originX(), b.originY())
                > Mathf.dst(b.world().tiles().x(aim) * 8f, b.world().tiles().y(aim) * 8f, b.originX(), b.originY()) + 2f)
            // there was nothing to aim at
            || (b.aimX() == -1f && b.aimY() == -1f);
    }

    /** {@code tileRaycast} (BulletComp.java:229): the diagonal Bresenham walk from the last tile to this one. */
    static void tileRaycast(Bullet b, int x1, int y1, int x2, int y2){
        World world = b.world();
        BulletType type = b.type();
        TileGrid tiles = world.tiles();
        int x = x1, dx = Math.abs(x2 - x), sx = x < x2 ? 1 : -1;
        int y = y1, dy = Math.abs(y2 - y), sy = y < y2 ? 1 : -1;
        int e2, err = dx - dy;
        int ww = tiles.width(), wh = tiles.height();
        while(x >= 0 && y >= 0 && x < ww && y < wh){
            Building build = world.buildings().at(x, y);
            if(type.collideFloor || type.collideTerrain){
                int pos = tiles.pos(x, y);
                ContentEpoch content = world.content();
                BlockDef floor = content.block(tiles.floor(pos)), block = content.block(tiles.block(pos));
                boolean surface = floor instanceof FloorDef f && !f.isLiquid() && !f.solid();
                if(type.collideFloor && (surface || !tiles.table().isAir(tiles.block(pos)))
                    || type.collideTerrain && block.baked() && block.isA(STATIC_WALL)){
                    // vanilla removes before it marks the hit, so the bullet despawns
                    b.remove();
                    b.hit(true);
                    return;
                }
            }
            if(build != null && b.isAdded() && checkUnderBuild(b, build, x * 8f, y * 8f) && BulletCollider.collide(build, b)
                && type.testCollision(b, build) && !build.dead && (type.collidesTeam || build.team != b.team())
                && !(type.pierceBuilding && b.hasCollided(build.id()))){
                if(type.sticky){
                    if(build.team != b.team()){
                        // stick to the edge of the block
                        Vec2 hit = raycastRect(b.lastX(), b.lastY(), x, y, x * 8f - 4f, y * 8f - 4f, 8f, 8f);
                        if(hit != null) b.set(hit.x, hit.y);
                        stickTo(b, build, 0f);
                        return;
                    }
                }else{
                    boolean remove = false, doRemove = false;
                    float health = build.health;
                    if(build.team != b.team()) remove = BulletCollider.collision(build, b);
                    if(remove || type.collidesTeam){
                        if(Mathf.dst2(b.lastX(), b.lastY(), x * 8f, y * 8f) < Mathf.dst2(b.lastX(), b.lastY(), b.x(), b.y())){
                            b.set(x * 8f, y * 8f);
                        }
                        if(!type.pierceBuilding){
                            b.hit(true);
                            doRemove = true;
                        }else{
                            b.addCollided(build.id());
                        }
                    }
                    type.hitTile(b, build, x * 8f, y * 8f, health, true);
                    if(doRemove) b.remove();
                    if(type.laserBullet && build.block().baked() && build.block().absorbLasers()){
                        b.hit(true);
                        b.remove();
                        return;
                    }
                    // stop raycasting when building is hit
                    if(type.pierceBuilding) return;
                }
            }
            if(x == x2 && y == y2) break;
            e2 = 2 * err;
            if(e2 > -dy){
                err -= dy;
                x += sx;
            }
            if(e2 < dx){
                err += dx;
                y += sy;
            }
        }
    }

    /**
     * Arc's {@code Geometry.raycastRect(startx, starty, endx, endy, rect)} for the rectangle {@code (rx, ry, rw, rh)}:
     * where the segment enters it, or null. Vanilla passes the tile's coordinates as the end, not its world position.
     */
    static Vec2 raycastRect(float startx, float starty, float endx, float endy, float rx, float ry, float rw, float rh){
        float x = rx + rw / 2, y = ry + rh / 2, halfx = rw / 2f, halfy = rh / 2f;
        float deltax = endx - startx, deltay = endy - starty;
        float scaleX = 1.0f / deltax;
        float scaleY = 1.0f / deltay;
        int signX = Mathf.sign(scaleX);
        int signY = Mathf.sign(scaleY);
        float nearTimeX = (x - signX * (halfx) - startx) * scaleX;
        float nearTimeY = (y - signY * (halfy) - starty) * scaleY;
        float farTimeX = (x + signX * (halfx) - startx) * scaleX;
        float farTimeY = (y + signY * (halfy) - starty) * scaleY;
        if(nearTimeX > farTimeY || nearTimeY > farTimeX) return null;
        float nearTime = Math.max(nearTimeX, nearTimeY);
        float farTime = Math.min(farTimeX, farTimeY);
        if(nearTime >= 1 || farTime <= 0) return null;
        float htime = Mathf.clamp(nearTime);
        return new Vec2(startx + htime * deltax, starty + htime * deltay);
    }

    /** {@code stickTo(other)}: the bullet rides on {@code other} from now on and hits nothing. */
    static void stickTo(Bullet b, Entity other, float otherRotation){
        Bullets t = b.table();
        int r = b.row();
        BulletType type = b.type();
        t.lifetime[r] += type.stickyExtraLifetime;
        // sticky bullets don't actually hit anything
        t.stickyX[r] = t.x[r] - Targets.x(other);
        t.stickyY[r] = t.y[r] - Targets.y(other);
        t.stickyTarget[r] = other;
        t.stickyRotationOffset[r] = t.rotation[r];
        t.stickyRotation[r] = other instanceof Unit unit ? unit.rotation : otherRotation;
    }

    /**
     * {@code EntityCollisions.updateCollision(bullet)} (EntityCollisions.java:244): the units of other teams whose
     * hitbox overlaps the bullet's swept box, then the swept test against each, until the bullet is gone.
     */
    static void collide(Bullet b, Candidates candidates){
        World world = b.world();
        SpatialIndex index = SpatialIndex.of(world);
        if(index == null) return;
        float size = b.hitSize(), half = size / 2f;
        float x = b.x(), y = b.y();
        // r1: the hitbox at the last position; r2: the hitbox now, merged with r1
        float r1x = x - half + (b.lastX() - x), r1y = y - half + (b.lastY() - y);
        float r2x = x - half, r2y = y - half;
        float minX = Math.min(r2x, r1x), maxX = Math.max(r2x + size, r1x + size);
        float minY = Math.min(r2y, r1y), maxY = Math.max(r2y + size, r1y + size);
        float sweepX = minX, sweepW = maxX - minX, sweepY = minY, sweepH = maxY - minY;
        int start = candidates.size;
        // collect, then collide: the hits below change the units and the grids
        index.nearbyEnemies(b.team(), sweepX, sweepY, sweepW, sweepH, candidates);
        int end = candidates.size;
        try{
            for(int i = start; i < end; i++){
                // a nested collision may have grown the array
                Unit unit = candidates.units[i];
                float us = unit.hitSize, ul = unit.x - us / 2f, ub = unit.y - us / 2f;
                if(sweepX < ul + us && sweepX + sweepW > ul && sweepY < ub + us && sweepY + sweepH > ub){
                    checkCollide(b, unit);
                    // break out of loop when this object hits something
                    if(!b.isAdded()) return;
                }
            }
        }finally{
            Arrays.fill(candidates.units, start, candidates.size, null);
            candidates.size = start;
        }
    }

    /** {@code checkCollide(bullet, unit)}: both boxes at their last positions, swept by their movement. */
    static void checkCollide(Bullet b, Unit unit){
        float as = b.hitSize(), bs = unit.hitSize;
        float ax = b.x(), ay = b.y(), alx = b.lastX(), aly = b.lastY();
        float r1x = ax - as / 2f + (alx - ax), r1y = ay - as / 2f + (aly - ay);
        float r2x = unit.x - bs / 2f + (unit.lastX - unit.x), r2y = unit.y - bs / 2f + (unit.lastY - unit.y);
        float vax = ax - alx, vay = ay - aly;
        float vbx = unit.x - unit.lastX, vby = unit.y - unit.lastY;
        if(collides(b, unit) && unit.hittable()){
            var at = new Vec2(ax, ay);
            boolean overlap = r1x < r2x + bs && r1x + as > r2x && r1y < r2y + bs && r1y + as > r2y;
            if(overlap || sweep(r1x, r1y, as, as, vax, vay, r2x, r2y, bs, bs, vbx, vby, at)){
                collision(b, unit, at.x, at.y);
                // the unit's side, UnitComp.collision: its controller hears of the hit
                unit.controller.hit(unit, b.owner());
            }
        }
    }

    /** {@code BulletComp.collides(other)} for a unit. */
    static boolean collides(Bullet b, Unit unit){
        BulletType type = b.type();
        return type.collides && unit.team != b.team() && Targets.checkTarget(unit, type.collidesAir, type.collidesGround)
            && !(type.pierce && b.hasCollided(unit.id())) && b.stickyTarget() == null;
    }

    /** {@code BulletComp.collision(other, x, y)}: hit, remove or remember, then the hit on the unit. */
    static void collision(Bullet b, Unit unit, float x, float y){
        BulletType type = b.type();
        if(type.sticky){
            if(b.stickyTarget() == null){
                // tunnel into the target a bit for better visuals
                b.set(x + b.velX(), y + b.velY());
                stickTo(b, unit, 0f);
            }
            return;
        }
        type.hit(b, x, y);
        // must be last
        if(!type.pierce){
            b.hit(true);
            b.remove();
        }else{
            b.addCollided(unit.id());
        }
        type.hitEntity(b, unit, unit.health);
    }

    /** {@code EntityCollisions.collide}: the swept AABB test; on a hit, {@code out} is where the first box's centre is. */
    static boolean sweep(float x1, float y1, float w1, float h1, float vx1, float vy1, float x2, float y2, float w2, float h2,
                         float vx2, float vy2, Vec2 out){
        float px = vx1, py = vy1;
        vx1 -= vx2;
        vy1 -= vy2;
        float xInvEntry, yInvEntry, xInvExit, yInvExit;
        if(vx1 > 0.0f){
            xInvEntry = x2 - (x1 + w1);
            xInvExit = (x2 + w2) - x1;
        }else{
            xInvEntry = (x2 + w2) - x1;
            xInvExit = x2 - (x1 + w1);
        }
        if(vy1 > 0.0f){
            yInvEntry = y2 - (y1 + h1);
            yInvExit = (y2 + h2) - y1;
        }else{
            yInvEntry = (y2 + h2) - y1;
            yInvExit = y2 - (y1 + h1);
        }
        float xEntry = xInvEntry / vx1;
        float xExit = xInvExit / vx1;
        float yEntry = yInvEntry / vy1;
        float yExit = yInvExit / vy1;
        float entryTime = Math.max(xEntry, yEntry);
        float exitTime = Math.min(xExit, yExit);
        if(entryTime > exitTime || xExit < 0.0f || yExit < 0.0f || xEntry > 1.0f || yEntry > 1.0f){
            return false;
        }
        out.set(x1 + w1 / 2f + px * entryTime, y1 + h1 / 2f + py * entryTime);
        return true;
    }
}
