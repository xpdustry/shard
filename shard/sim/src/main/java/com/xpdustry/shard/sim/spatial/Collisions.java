package com.xpdustry.shard.sim.spatial;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.unit.*;

/**
 * Unit movement against solid tiles: a port of {@code EntityCollisions.move} and {@code moveDelta}, float for float.
 * The unit moves in segments of at most one world unit, first along x, then along y; after each segment its tile
 * hitbox ({@code hitboxTile}: at most 7.8 wide) is pushed out of every solid tile it overlaps, along the axis of least
 * penetration ({@code Geometry.overlap}). Vanilla's {@code Rect} scratch fields are locals here.
 */
public final class Collisions{
    /** {@code EntityCollisions.seg}. */
    private static final float SEGMENT = 1f;
    /** {@code EntityCollisions.maxDelta}. */
    private static final float MAX_DELTA = 1000f;
    private static final int TILE = 8;

    /** {@code EntityCollisions.move(entity, dx, dy, solidCheck)}. */
    public static void move(Unit unit, float deltax, float deltay, TileSolidity tiles, Solidity kind){
        if((Math.abs(deltax) < 0.0001f && Math.abs(deltay) < 0.0001f) || deltax != deltax || deltay != deltay) return;

        deltax = Mathf.clamp(deltax, -MAX_DELTA, MAX_DELTA);
        deltay = Mathf.clamp(deltay, -MAX_DELTA, MAX_DELTA);

        int r = Math.max(Math.round(tileSize(unit) / TILE), 1);

        // vanilla quirk: one segment always runs per axis, even a zero one, which still resolves overlaps
        boolean movedx = false;
        while(Math.abs(deltax) > 0 || !movedx){
            movedx = true;
            moveDelta(unit, Math.min(Math.abs(deltax), SEGMENT) * Mathf.sign(deltax), 0, r, tiles, kind);
            if(Math.abs(deltax) >= SEGMENT){
                deltax -= SEGMENT * Mathf.sign(deltax);
            }else{
                deltax = 0f;
            }
        }

        boolean movedy = false;
        while(Math.abs(deltay) > 0 || !movedy){
            movedy = true;
            moveDelta(unit, 0, Math.min(Math.abs(deltay), SEGMENT) * Mathf.sign(deltay), r, tiles, kind);
            if(Math.abs(deltay) >= SEGMENT){
                deltay -= SEGMENT * Mathf.sign(deltay);
            }else{
                deltay = 0f;
            }
        }
    }

    /** {@code EntityCollisions.moveDelta}: one segment, then the overlap pushes, then {@code trns} by the result. */
    static void moveDelta(Unit unit, float deltax, float deltay, int r, TileSolidity tiles, Solidity kind){
        float size = tileSize(unit);
        // r1 and r2 are both hitboxTile; r1 moves
        float r2x = unit.x - size / 2f, r2y = unit.y - size / 2f;
        float r1x = r2x + deltax, r1y = r2y + deltay;

        int tilex = Math.round((r1x + size / 2) / TILE), tiley = Math.round((r1y + size / 2) / TILE);

        for(int dx = -r; dx <= r; dx++){
            for(int dy = -r; dy <= r; dy++){
                int wx = dx + tilex, wy = dy + tiley;
                if(!tiles.solid(kind, wx, wy)) continue;
                // tmp.setSize(tilesize).setCenter(wx * tilesize, wy * tilesize)
                float tx = wx * TILE - TILE / 2f, ty = wy * TILE - TILE / 2f;
                if(!(tx < r1x + size && tx + TILE > r1x && ty < r1y + size && ty + TILE > r1y)) continue;

                // Geometry.overlap(r1, tmp, x): the correction moves r1 out along the axis of least penetration
                float nx = (r1x + size / 2) - (tx + TILE / 2f), ny = (r1y + size / 2) - (ty + TILE / 2f);
                float xoverlap = size / 2 + TILE / 2f - Math.abs(nx);
                if(!(Math.abs(xoverlap) > 0)) continue;
                float yoverlap = size / 2 + TILE / 2f - Math.abs(ny);
                if(!(Math.abs(yoverlap) > 0)) continue;
                if(Math.abs(xoverlap) < Math.abs(yoverlap)){
                    float m = Math.max(xoverlap, 0f);
                    r1x += -(m * (nx < 0 ? 1 : -1));
                }else{
                    float m = Math.max(yoverlap, 0f);
                    r1y += -(m * (ny < 0 ? 1 : -1));
                }
            }
        }

        // PosComp.trns
        unit.x = unit.x + (r1x - r2x);
        unit.y = unit.y + (r1y - r2y);
    }

    /**
     * {@code EntityCollisions.overlapsTile(rect, solidCheck)}: whether a tile that is solid for {@code kind} overlaps
     * the box ({@code x}, {@code y}, {@code width}, {@code height}), among the 3×3 tiles around its centre. Vanilla's
     * radius reads the box of the last {@code moveDelta}, which is never wider than a tile, so it is always 1.
     */
    public static boolean overlapsTile(float x, float y, float width, float height, TileSolidity tiles, Solidity kind){
        // rect.getCenter: assumes tiles are centered
        int tilex = Math.round((x + width / 2f) / TILE), tiley = Math.round((y + height / 2f) / TILE);
        for(int dx = -1; dx <= 1; dx++){
            for(int dy = -1; dy <= 1; dy++){
                int wx = dx + tilex, wy = dy + tiley;
                if(!tiles.solid(kind, wx, wy)) continue;
                // r2.setCentered(wx * tilesize, wy * tilesize, tilesize).overlaps(rect)
                float tx = wx * TILE - TILE / 2f, ty = wy * TILE - TILE / 2f;
                if(tx < x + width && tx + TILE > x && ty < y + height && ty + TILE > y) return true;
            }
        }
        return false;
    }

    /** {@code HitboxComp.hitboxTile}'s size: tile hitboxes are never bigger than a tile. */
    static float tileSize(Unit unit){
        return Math.min(unit.hitSize * 0.66f, 7.8f);
    }

    private Collisions(){}
}
