package com.xpdustry.shard.common.geom;

import com.xpdustry.shard.common.math.*;

/**
 * A minimal mutable 2D vector: the part of Arc's {@code arc.math.geom.Vec2} that gameplay uses, bit for bit. Rotation
 * goes through {@link Mathf}'s sine table, as in Arc. A world never stores Vec2 objects in its tables; kernels use
 * them as scratch values.
 */
public final class Vec2{
    public float x;
    public float y;

    public Vec2(){
    }

    public Vec2(float x, float y){
        this.x = x;
        this.y = y;
    }

    public Vec2(Vec2 v){
        set(v);
    }

    public Vec2 cpy(){
        return new Vec2(this);
    }

    /** Sets this to (amount, 0) rotated by {@code angle} degrees. */
    public Vec2 trns(float angle, float amount){
        return set(amount, 0).rotate(angle);
    }

    public Vec2 trns(float angle, float x, float y){
        return set(x, y).rotate(angle);
    }

    public Vec2 snap(){
        return set((int)x, (int)y);
    }

    public float len(){
        return (float)Math.sqrt(x * x + y * y);
    }

    public float len2(){
        return x * x + y * y;
    }

    public Vec2 set(Vec2 v){
        x = v.x;
        y = v.y;
        return this;
    }

    public Vec2 set(float x, float y){
        this.x = x;
        this.y = y;
        return this;
    }

    public Vec2 setZero(){
        this.x = 0;
        this.y = 0;
        return this;
    }

    public Vec2 sub(Vec2 v){
        x -= v.x;
        y -= v.y;
        return this;
    }

    public Vec2 sub(float x, float y){
        this.x -= x;
        this.y -= y;
        return this;
    }

    public Vec2 add(Vec2 v){
        x += v.x;
        y += v.y;
        return this;
    }

    public Vec2 add(Vec2 vec, float scl){
        this.x += vec.x * scl;
        this.y += vec.y * scl;
        return this;
    }

    public Vec2 add(float x, float y){
        this.x += x;
        this.y += y;
        return this;
    }

    public Vec2 nor(){
        float len = len();
        if(len != 0){
            x /= len;
            y /= len;
        }
        return this;
    }

    public float dot(Vec2 v){
        return x * v.x + y * v.y;
    }

    public float dot(float ox, float oy){
        return x * ox + y * oy;
    }

    public float crs(Vec2 v){
        return this.x * v.y - this.y * v.x;
    }

    public float crs(float x, float y){
        return this.x * y - this.y * x;
    }

    public Vec2 scl(float scalar){
        x *= scalar;
        y *= scalar;
        return this;
    }

    public Vec2 scl(float x, float y){
        this.x *= x;
        this.y *= y;
        return this;
    }

    public Vec2 inv(){
        return scl(-1f);
    }

    public Vec2 mulAdd(Vec2 vec, float scalar){
        this.x += vec.x * scalar;
        this.y += vec.y * scalar;
        return this;
    }

    public float dst(Vec2 v){
        final float x_d = v.x - x;
        final float y_d = v.y - y;
        return (float)Math.sqrt(x_d * x_d + y_d * y_d);
    }

    public float dst(float x, float y){
        final float x_d = x - this.x;
        final float y_d = y - this.y;
        return (float)Math.sqrt(x_d * x_d + y_d * y_d);
    }

    public float dst2(Vec2 v){
        final float x_d = v.x - x;
        final float y_d = v.y - y;
        return x_d * x_d + y_d * y_d;
    }

    public float dst2(float x, float y){
        final float xd = x - this.x;
        final float yd = y - this.y;
        return xd * xd + yd * yd;
    }

    public boolean within(float x, float y, float dst){
        return dst2(x, y) < dst * dst;
    }

    public Vec2 limit(float limit){
        return limit2(limit * limit);
    }

    public Vec2 limit2(float limit2){
        float len2 = len2();
        if(len2 > limit2){
            return scl((float)Math.sqrt(limit2 / len2));
        }
        return this;
    }

    public Vec2 clampLength(float min, float max){
        float len2 = len2();
        if(len2 >= max * max){
            return limit(max);
        }else if(len2 <= min * min){
            return setLength(min);
        }
        return this;
    }

    public Vec2 clamp(float min, float max){
        final float len2 = len2();
        if(len2 == 0f) return this;
        float max2 = max * max;
        if(len2 > max2) return scl((float)Math.sqrt(max2 / len2));
        float min2 = min * min;
        if(len2 < min2) return scl((float)Math.sqrt(min2 / len2));
        return this;
    }

    public Vec2 clamp(float minx, float miny, float maxx, float maxy){
        x = Mathf.clamp(x, minx, maxx);
        y = Mathf.clamp(y, miny, maxy);
        return this;
    }

    public Vec2 setLength(float len){
        return setLength2(len * len);
    }

    public Vec2 setLength2(float len2){
        float oldLen2 = len2();
        return (oldLen2 == 0 || oldLen2 == len2) ? this : scl((float)Math.sqrt(len2 / oldLen2));
    }

    /** Degrees in [0, 360), from the polynomial {@link Mathf#atan2(float, float)}. */
    public float angle(){
        float angle = Mathf.atan2(x, y) * Mathf.radiansToDegrees;
        if(angle < 0) angle += 360;
        return angle;
    }

    /** The exact angle in radians, [-PI, PI]. */
    public float angleRad(){
        return (float)StrictMath.atan2(y, x);
    }

    public Vec2 setAngle(float degrees){
        return setAngleRad(degrees * Mathf.degreesToRadians);
    }

    public Vec2 setAngleRad(float radians){
        this.set(len(), 0f);
        this.rotateRad(radians);
        return this;
    }

    public Vec2 rotateTo(float angle, float speed){
        return setAngle(Angles.moveToward(angle(), angle, speed));
    }

    public Vec2 rotate(float degrees){
        return rotateRad(degrees * Mathf.degreesToRadians);
    }

    public Vec2 rotateAround(Vec2 reference, float degrees){
        return this.sub(reference).rotate(degrees).add(reference);
    }

    public Vec2 rotateRad(float radians){
        float cos = Mathf.cos(radians);
        float sin = Mathf.sin(radians);

        float newX = this.x * cos - this.y * sin;
        float newY = this.x * sin + this.y * cos;

        this.x = newX;
        this.y = newY;

        return this;
    }

    public Vec2 rotate90(int dir){
        float x = this.x;
        if(dir >= 0){
            this.x = -y;
            y = x;
        }else{
            this.x = y;
            y = -x;
        }
        return this;
    }

    public Vec2 approach(Vec2 target, float alpha){
        float dx = x - target.x, dy = y - target.y;
        float alpha2 = alpha * alpha;
        float len2 = Mathf.len2(dx, dy);

        if(len2 > alpha2){
            float scl = Mathf.sqrt(alpha2 / len2);
            dx *= scl;
            dy *= scl;

            return sub(dx, dy);
        }else{
            return set(target);
        }
    }

    /** {@code approachDelta} at Δ = 1. */
    public Vec2 approachDelta(Vec2 target, float alpha){
        return approach(target, 1f * alpha);
    }

    public Vec2 lerp(Vec2 target, float alpha){
        final float invAlpha = 1.0f - alpha;
        this.x = (x * invAlpha) + (target.x * alpha);
        this.y = (y * invAlpha) + (target.y * alpha);
        return this;
    }

    public Vec2 lerp(float tx, float ty, float alpha){
        final float invAlpha = 1.0f - alpha;
        this.x = (x * invAlpha) + (tx * alpha);
        this.y = (y * invAlpha) + (ty * alpha);
        return this;
    }

    /** {@code lerpDelta} at Δ = 1. */
    public Vec2 lerpDelta(float tx, float ty, float alpha){
        alpha = Mathf.clamp(alpha * 1f);
        final float invAlpha = 1.0f - alpha;
        this.x = (x * invAlpha) + (tx * alpha);
        this.y = (y * invAlpha) + (ty * alpha);
        return this;
    }

    public Vec2 lerpPast(Vec2 target, float alpha){
        x = (x) + ((target.x - x) * alpha);
        y = (y) + ((target.y - y) * alpha);
        return this;
    }

    public Vec2 interpolate(Vec2 target, float alpha, Interp interpolation){
        return lerp(target, interpolation.apply(alpha));
    }

    public Vec2 setToRandomDirection(Rand rand){
        float theta = rand.random(0f, Mathf.PI2);
        return this.set(Mathf.cos(theta), Mathf.sin(theta));
    }

    public boolean isZero(){
        return x == 0 && y == 0;
    }

    public boolean epsilonEquals(float x, float y, float epsilon){
        if(Math.abs(x - this.x) > epsilon) return false;
        return !(Math.abs(y - this.y) > epsilon);
    }

    @Override
    public boolean equals(Object obj){
        return obj instanceof Vec2 other
            && Float.floatToIntBits(x) == Float.floatToIntBits(other.x)
            && Float.floatToIntBits(y) == Float.floatToIntBits(other.y);
    }

    @Override
    public int hashCode(){
        return 31 * (31 + Float.floatToIntBits(x)) + Float.floatToIntBits(y);
    }

    @Override
    public String toString(){
        return "(" + x + "," + y + ")";
    }
}
