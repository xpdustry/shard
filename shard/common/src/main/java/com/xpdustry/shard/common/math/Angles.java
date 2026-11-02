package com.xpdustry.shard.common.math;

import com.xpdustry.shard.common.geom.*;

/**
 * Arc's {@code arc.math.Angles}, bit for bit. Angles are in degrees. Left out: {@code mouseAngle} (client input) and
 * the seeded particle helpers ({@code randLenVectors}, effects only).
 */
public final class Angles{
    public static float forwardDistance(float angle1, float angle2){
        return Math.abs(angle1 - angle2);
    }

    public static float backwardDistance(float angle1, float angle2){
        return 360 - Math.abs(angle1 - angle2);
    }

    public static boolean within(float a, float b, float margin){
        return angleDist(a, b) <= margin;
    }

    public static float angleDist(float a, float b){
        a = Mathf.mod(a, 360f);
        b = Mathf.mod(b, 360f);
        return Math.min((a - b) < 0 ? a - b + 360 : a - b, (b - a) < 0 ? b - a + 360 : b - a);
    }

    public static boolean near(float a, float b, float range){
        return angleDist(a, b) < range;
    }

    public static float clampRange(float angle, float dest, float range){
        float dst = angleDist(angle, dest);
        return dst <= range ? angle : moveToward(angle, dest, dst - range);
    }

    /** Quirk kept: the result is not wrapped to [0, 360) when it steps past 0 or 360. */
    public static float moveToward(float angle, float to, float speed){
        if(Math.abs(angleDist(angle, to)) < speed) return to;
        angle = Mathf.mod(angle, 360f);
        to = Mathf.mod(to, 360f);

        if(angle > to == backwardDistance(angle, to) > forwardDistance(angle, to)){
            angle -= speed;
        }else{
            angle += speed;
        }

        return angle;
    }

    public static float angle(float x, float y){
        return angle(0, 0, x, y);
    }

    public static float angle(float x, float y, float x2, float y2){
        float ang = Mathf.atan2(x2 - x, y2 - y) * Mathf.radDeg;
        if(ang < 0) ang += 360f;
        return ang;
    }

    public static float angleRad(float x, float y, float x2, float y2){
        return Mathf.atan2(x2 - x, y2 - y);
    }

    public static float trnsx(float angle, float len){
        return len * Mathf.cosDeg(angle);
    }

    public static float trnsy(float angle, float len){
        return len * Mathf.sinDeg(angle);
    }

    /**
     * x of (x, y) rotated by {@code angle}: Arc's {@code new Vec2(x, y).rotate(angle).x}, written out as
     * {@link Vec2#rotate} computes it, without the vector.
     */
    public static float trnsx(float angle, float x, float y){
        float radians = angle * Mathf.degreesToRadians;
        return x * Mathf.cos(radians) - y * Mathf.sin(radians);
    }

    /** y of (x, y) rotated by {@code angle}: Arc's {@code new Vec2(x, y).rotate(angle).y}, written out the same way. */
    public static float trnsy(float angle, float x, float y){
        float radians = angle * Mathf.degreesToRadians;
        return x * Mathf.sin(radians) + y * Mathf.cos(radians);
    }

    private Angles(){}
}
