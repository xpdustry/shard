package com.xpdustry.shard.sim.combat;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.unit.*;

/**
 * Vanilla's {@code Predict} (Predict.java): where to aim a bullet of speed {@code v} so it meets a moving target.
 * Only units move ({@code Hitboxc}); a building target is aimed at directly.
 */
public final class Predict{
    private Predict(){
    }

    /**
     * {@code intercept(srcx, srcy, dstx, dsty, dstvx, dstvy, v)}: the target's position after the smallest positive
     * meeting time, or its position now. Vanilla's {@code quad(a, b, c)} is inlined, its roots kept in locals instead
     * of an array, in the same operations and order. Its linear case sets its vector but returns null, so a linear
     * equation never has a solution; kept.
     */
    public static Vec2 intercept(float srcx, float srcy, float dstx, float dsty, float dstvx, float dstvy, float v){
        float tx = dstx - srcx, ty = dsty - srcy;
        // quadratic equation components
        float a = dstvx * dstvx + dstvy * dstvy - v * v;
        float b = 2 * (dstvx * tx + dstvy * ty);
        float c = tx * tx + ty * ty;
        // quad(a, b, c): whether it has roots, and the roots t0 and t1
        boolean roots = false;
        float t0 = 0, t1 = 0;
        if(Math.abs(a) < 1e-6){
            if(Math.abs(b) < 1e-6) roots = Math.abs(c) < 1e-6;
        }else{
            float disc = b * b - 4 * a * c;
            if(disc >= 0){
                disc = Mathf.sqrt(disc);
                a = 2 * a;
                t0 = (-b - disc) / a;
                t1 = (-b + disc) / a;
                roots = true;
            }
        }
        var sol = new Vec2(dstx, dsty);
        if(roots){
            float t = Math.min(t0, t1);
            if(t < 0) t = Math.max(t0, t1);
            if(t > 0) sol.set(dstx + dstvx * t, dsty + dstvy * t);
        }
        return sol;
    }

    /** {@code intercept(src, dst, bullet)}: the bullet's speed, less the shooter's motion if it keeps velocity. */
    public static Vec2 intercept(Unit src, Entity dst, BulletType bullet){
        return intercept(src, dst, 0f, 0f, bullet.keepVelocity, bullet.speed);
    }

    /** {@code intercept(src, dst, offsetx, offsety, useSrcVelocity, v)}. */
    public static Vec2 intercept(Unit src, Entity dst, float offsetx, float offsety, boolean useSrcVelocity, float v){
        float ddx = 0, ddy = 0;
        if(dst instanceof Unit target){
            ddx += target.deltaX;
            ddy += target.deltaY;
        }
        if(useSrcVelocity){
            ddx -= src.deltaX;
            ddy -= src.deltaY;
        }
        return intercept(src.x, src.y, Targets.x(dst) + offsetx, Targets.y(dst) + offsety, ddx, ddy, v);
    }
}
