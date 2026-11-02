package com.xpdustry.shard.common.math;

/**
 * Arc's {@code arc.math.Mathf} (Arc 7445105c), bit for bit: the 14-bit sine table, the polynomial {@code atan2}, the
 * {@code BIG_ENOUGH} floor and round, and the rest of what blocks and units call. {@code MathfTest} checks every
 * method against Arc's own class.
 *
 * <p>Differences from Arc, all deliberate:
 * <ul>
 * <li>Random helpers take the world's {@link Rand} instead of the global {@code Mathf.rand}. They keep Mathf's own
 *     formulas, which differ from {@link Rand}'s methods of the same name ({@code Mathf.chance} draws a float,
 *     {@code Rand.chance} a double), so a port keeps the same draws.</li>
 * <li>{@code *Delta} helpers assume Shard's fixed Δ = 1, as vanilla's server frame does; {@code Time.delta * x} is
 *     then exactly {@code x}.</li>
 * <li>Transcendental functions use {@link StrictMath}, so results don't depend on JIT
 *     intrinsics. After the cast to float they match Arc's {@code Math} calls on every input the tests try.</li>
 * <li>Nothing reads a clock: {@code absin(scl, mag)} and {@code sin(scl, mag)} over {@code Time.time} are left out.</li>
 * </ul>
 */
public final class Mathf{
    public static final int[] signs = {-1, 1};
    public static final int[] zeroOne = {0, 1};
    public static final boolean[] booleans = {true, false};
    public static final float FLOAT_ROUNDING_ERROR = 0.000001f; // 32 bits
    public static final float PI = 3.1415927f, pi = PI, halfPi = PI / 2;
    public static final float PI2 = PI * 2;
    public static final float E = 2.7182818f;
    public static final float sqrt2 = Mathf.sqrt(2f);
    public static final float sqrt3 = Mathf.sqrt(3f);
    /** multiply by this to convert from radians to degrees */
    public static final float radiansToDegrees = 180f / PI;
    public static final float radDeg = radiansToDegrees;
    /** multiply by this to convert from degrees to radians */
    public static final float degreesToRadians = PI / 180;
    public static final float degRad = degreesToRadians;
    public static final double doubleDegRad = 0.017453292519943295;
    public static final double doubleRadDeg = 57.29577951308232;

    private static final int sinBits = 14; // 16KB. Adjust for accuracy.
    private static final int sinMask = ~(-1 << sinBits);
    private static final int sinCount = sinMask + 1;
    private static final float[] sinTable = new float[sinCount];
    private static final float radFull = PI * 2;
    private static final float degFull = 360;
    private static final float radToIndex = sinCount / radFull;
    private static final float degToIndex = sinCount / degFull;
    private static final int BIG_ENOUGH_INT = 16 * 1024;
    private static final double BIG_ENOUGH_FLOOR = BIG_ENOUGH_INT;
    private static final double CEIL = 0.9999999;
    private static final double BIG_ENOUGH_ROUND = BIG_ENOUGH_INT + 0.5f;

    static{
        for(int i = 0; i < sinCount; i++)
            sinTable[i] = (float)StrictMath.sin((i + 0.5f) / sinCount * radFull);
        for(int i = 0; i < 360; i += 90)
            sinTable[(int)(i * degToIndex) & sinMask] = (float)StrictMath.sin(i * degreesToRadians);

        sinTable[0] = 0f;
        sinTable[(int)(90 * degToIndex) & sinMask] = 1f;
        sinTable[(int)(180 * degToIndex) & sinMask] = 0f;
        sinTable[(int)(270 * degToIndex) & sinMask] = -1f;
    }

    /** Returns the sine in radians from a lookup table. */
    public static float sin(float radians){
        return sinTable[(int)(radians * radToIndex) & sinMask];
    }

    /** Returns the cosine in radians from a lookup table. */
    public static float cos(float radians){
        return sinTable[(int)((radians + PI / 2) * radToIndex) & sinMask];
    }

    /** Returns the sine in degrees from a lookup table. */
    public static float sinDeg(float degrees){
        return sinTable[(int)(degrees * degToIndex) & sinMask];
    }

    /** Returns the cosine in degrees from a lookup table. */
    public static float cosDeg(float degrees){
        return sinTable[(int)((degrees + 90) * degToIndex) & sinMask];
    }

    public static float absin(float in, float scl, float mag){
        return (sin(in, scl * 2f, mag) + mag) / 2f;
    }

    public static float tan(float radians, float scl, float mag){
        return (sin(radians / scl)) / (cos(radians / scl)) * mag;
    }

    public static float sin(float radians, float scl, float mag){
        return sin(radians / scl) * mag;
    }

    public static float cos(float radians, float scl, float mag){
        return cos(radians / scl) * mag;
    }

    /** The angle of (x, y) in degrees, [0, 360), from the polynomial {@link #atan2(float, float)}. */
    public static float angle(float x, float y){
        float result = atan2(x, y) * radDeg;
        if(result < 0) result += 360f;
        return result;
    }

    /** As {@link #angle(float, float)} but with the exact {@code atan2}. */
    public static float angleExact(float x, float y){
        float result = (float)StrictMath.atan2(y, x) * radDeg;
        if(result < 0) result += 360f;
        return result;
    }

    /** Wraps the given angle in radians to [-PI, PI]. */
    public static float wrapAngleAroundZero(float a){
        if(a >= 0){
            float rotation = a % Mathf.PI2;
            if(rotation > Mathf.PI) rotation -= Mathf.PI2;
            return rotation;
        }else{
            float rotation = -a % Mathf.PI2;
            if(rotation > Mathf.PI) rotation -= Mathf.PI2;
            return -rotation;
        }
    }

    /** Arc's atan for {@link #atan2}: finite inputs only, computed in double. */
    private static float atn(final double i){
        final double n = Math.abs(i);
        final double c = (n - 1.0) / (n + 1.0);
        final double c2 = c * c, c3 = c * c2, c5 = c3 * c2, c7 = c5 * c2, c9 = c7 * c2, c11 = c9 * c2;
        return (float)Math.copySign((Math.PI * 0.25)
        + (0.99997726 * c - 0.33262347 * c3 + 0.19354346 * c5 - 0.11643287 * c7 + 0.05265332 * c9 - 0.0117212 * c11), i);
    }

    /**
     * Arc's approximate atan2 in radians, [-PI, PI]. Quirk kept: the arguments are {@code (x, y)}, the reverse of
     * {@link Math#atan2(double, double)}. Returns 0 for (0, 0) and NaN if either argument is NaN.
     */
    public static float atan2(float x, final float y){
        float n = y / x;
        if(n != n){
            n = (y == x ? 1f : -1f); // if both y and x are infinite, n would be NaN
        }else if(n - n != n - n){
            x = 0f; // if n is infinite, y is infinitely larger than x.
        }

        if(x > 0){
            return atn(n);
        }else if(x < 0){
            return y >= 0 ? atn(n) + PI : atn(n) - PI;
        }else if(y > 0){
            return x + halfPi;
        }else if(y < 0){
            return x - halfPi;
        }else{
            return x + y; // returns 0 for 0,0 or NaN if either y or x is NaN
        }
    }

    public static int digits(int n){
        return n < 100000 ? n < 100 ? n < 10 ? 1 : 2 : n < 1000 ? 3 : n < 10000 ? 4 : 5 : n < 10000000 ? n < 1000000 ? 6 : 7 : n < 100000000 ? 8 : n < 1000000000 ? 9 : 10;
    }

    public static float sqrt(float x){
        return (float)Math.sqrt(x);
    }

    public static float sqr(float x){
        return x * x;
    }

    public static float map(float value, float froma, float toa, float fromb, float tob){
        return fromb + (value - froma) * (tob - fromb) / (toa - froma);
    }

    /** Map value from [0, 1]. */
    public static float map(float value, float from, float to){
        return map(value, 0, 1, from, to);
    }

    /** Returns -1 if f < 0, 1 otherwise. */
    public static int sign(float f){
        return (f < 0 ? -1 : 1);
    }

    /** Returns 1 if true, -1 if false. */
    public static int sign(boolean b){
        return b ? 1 : -1;
    }

    /** Converts a boolean to an integer: 1 if true, 0 if false. */
    public static int num(boolean b){
        return b ? 1 : 0;
    }

    public static float pow(float a, float b){
        return (float)StrictMath.pow(a, b);
    }

    public static int pow(int a, int b){
        return (int)Math.ceil(StrictMath.pow(a, b));
    }

    // Random helpers with Mathf's formulas, over an explicit Rand (Arc uses the global Mathf.rand).

    public static float range(Rand rand, float range){
        return random(rand, -range, range);
    }

    public static int range(Rand rand, int range){
        return random(rand, -range, range);
    }

    public static float range(Rand rand, float min, float max){
        if(chance(rand, 0.5)){
            return random(rand, min, max);
        }else{
            return -random(rand, min, max);
        }
    }

    /** {@code chanceDelta} at Δ = 1. */
    public static boolean chanceDelta(Rand rand, double d){
        return rand.nextFloat() < d * 1f;
    }

    public static boolean chance(Rand rand, double d){
        return d >= 1f || rand.nextFloat() < d;
    }

    /** Returns a random number between 0 (inclusive) and the specified value (inclusive). */
    public static int random(Rand rand, int range){
        return rand.nextInt(range + 1);
    }

    /** Returns a random number between start (inclusive) and end (inclusive). */
    public static int random(Rand rand, int start, int end){
        return start + rand.nextInt(end - start + 1);
    }

    /** Returns a random number between 0 (inclusive) and the specified value (inclusive). */
    public static long random(Rand rand, long range){
        return (long)(rand.nextDouble() * range);
    }

    /** Returns a random number between start (inclusive) and end (inclusive). */
    public static long random(Rand rand, long start, long end){
        return start + (long)(rand.nextDouble() * (end - start));
    }

    public static boolean randomBoolean(Rand rand){
        return rand.nextBoolean();
    }

    /** Returns true if a random value between 0 and 1 is less than the specified value. */
    public static boolean randomBoolean(Rand rand, float chance){
        return random(rand) < chance;
    }

    /** Returns random number between 0.0 (inclusive) and 1.0 (exclusive). */
    public static float random(Rand rand){
        return rand.nextFloat();
    }

    /** Returns a random number between 0 (inclusive) and the specified value (exclusive). */
    public static float random(Rand rand, float range){
        return rand.nextFloat() * range;
    }

    /** Returns a random number between start (inclusive) and end (exclusive). */
    public static float random(Rand rand, float start, float end){
        return start + rand.nextFloat() * (end - start);
    }

    /** Returns -1 or 1, randomly. */
    public static int randomSign(Rand rand){
        return 1 | (rand.nextInt() >> 31);
    }

    /** Inclusive. Quirk kept: a power-of-two {@code max} draws one extra int first. */
    public static int randomSeed(long seed, int min, int max){
        Rand seedr = new Rand(seed);
        if(isPowerOfTwo(max)){
            seedr.nextInt();
        }
        return seedr.nextInt(max - min + 1) + min;
    }

    /** Inclusive. */
    public static float randomSeed(long seed, float min, float max){
        Rand seedr = new Rand(seed);
        return (min + seedr.nextFloat() * (max - min));
    }

    public static float randomSeed(long seed){
        return new Rand(seed * 99999).nextFloat();
    }

    public static float randomSeed(long seed, float max){
        return new Rand(seed * 99999).nextFloat() * max;
    }

    public static float randomSeedRange(long seed, float range){
        return range * (new Rand(seed * 99999).nextFloat() - 0.5f) * 2f;
    }

    /** A triangular random number in (-1, 1). */
    public static float randomTriangular(Rand rand){
        return rand.nextFloat() - rand.nextFloat();
    }

    /** A triangular random number in (-max, max). */
    public static float randomTriangular(Rand rand, float max){
        return (rand.nextFloat() - rand.nextFloat()) * max;
    }

    public static float randomTriangular(Rand rand, float min, float max){
        return randomTriangular(rand, min, max, (min + max) * 0.5f);
    }

    public static float randomTriangular(Rand rand, float min, float max, float mode){
        float u = rand.nextFloat();
        float d = max - min;
        if(u <= (mode - min) / d) return min + (float)Math.sqrt(u * d * (mode - min));
        return max - (float)Math.sqrt((1 - u) * d * (max - mode));
    }

    /** Returns the next power of two. Returns the specified value if the value is already a power of two. */
    public static int nextPowerOfTwo(int value){
        if(value == 0) return 1;
        value--;
        value |= value >> 1;
        value |= value >> 2;
        value |= value >> 4;
        value |= value >> 8;
        value |= value >> 16;
        return value + 1;
    }

    public static boolean isPowerOfTwo(int value){
        return value != 0 && (value & value - 1) == 0;
    }

    public static int clamp(int value, int min, int max){
        return Math.max(Math.min(value, max), min);
    }

    public static long clamp(long value, long min, long max){
        return Math.max(Math.min(value, max), min);
    }

    public static float clamp(float value, float min, float max){
        return Math.max(Math.min(value, max), min);
    }

    /** Clamps to [0, 1]. */
    public static float clamp(float value){
        return Math.max(Math.min(value, 1f), 0f);
    }

    public static double clamp(double value, double min, double max){
        return Math.max(Math.min(value, max), min);
    }

    public static float maxZero(float val){
        return Math.max(val, 0);
    }

    /** Approaches a value at linear speed. */
    public static float approach(float from, float to, float speed){
        return from + Mathf.clamp(to - from, -speed, speed);
    }

    /** {@code approachDelta} at Δ = 1. */
    public static float approachDelta(float from, float to, float speed){
        return approach(from, to, 1f * speed);
    }

    /** Linearly interpolates between fromValue to toValue on progress position. */
    public static float lerp(float fromValue, float toValue, float progress){
        return fromValue + (toValue - fromValue) * progress;
    }

    /** {@code lerpDelta} at Δ = 1. */
    public static float lerpDelta(float fromValue, float toValue, float progress){
        return lerp(fromValue, toValue, clamp(progress * 1f));
    }

    /** Interpolates between two angles in radians along the shorter way; returns [0, PI2). */
    public static float slerpRad(float fromRadians, float toRadians, float progress){
        float delta = ((toRadians - fromRadians + PI2 + PI) % PI2) - PI;
        return (fromRadians + delta * progress + PI2) % PI2;
    }

    /** Interpolates between two angles in degrees along the shorter way; returns [0, 360). */
    public static float slerp(float fromDegrees, float toDegrees, float progress){
        float delta = ((toDegrees - fromDegrees + 360 + 180) % 360) - 180;
        return (fromDegrees + delta * progress + 360) % 360;
    }

    /** {@code slerpDelta} at Δ = 1. */
    public static float slerpDelta(float fromDegrees, float toDegrees, float progress){
        return slerp(fromDegrees, toDegrees, clamp(progress * 1f));
    }

    /** Floor that is only correct for floats from -(2^14) to (Float.MAX_VALUE - 2^14). */
    public static int floor(float value){
        return (int)(value + BIG_ENOUGH_FLOOR) - BIG_ENOUGH_INT;
    }

    /** Floor for positive floats: a cast. */
    public static int floorPositive(float value){
        return (int)value;
    }

    /** Ceil that is only correct for floats from -(2^14) to (Float.MAX_VALUE - 2^14). */
    public static int ceil(float value){
        return BIG_ENOUGH_INT - (int)(BIG_ENOUGH_FLOOR - value);
    }

    /** Ceil for positive floats. */
    public static int ceilPositive(float value){
        return (int)(value + CEIL);
    }

    /** Round that is only correct for floats from -(2^14) to (Float.MAX_VALUE - 2^14). */
    public static int round(float value){
        return (int)(value + BIG_ENOUGH_ROUND) - BIG_ENOUGH_INT;
    }

    public static int round(int value, int step){
        return (value / step) * step;
    }

    public static float round(float value, float step){
        return (int)(value / step) * step;
    }

    public static int round(float value, int step){
        return (int)(value / step) * step;
    }

    /** Round for positive floats. */
    public static int roundPositive(float value){
        return (int)(value + 0.5f);
    }

    public static boolean zero(float value){
        return Math.abs(value) <= FLOAT_ROUNDING_ERROR;
    }

    public static boolean zero(double value){
        return Math.abs(value) <= FLOAT_ROUNDING_ERROR;
    }

    public static boolean zero(float value, float tolerance){
        return Math.abs(value) <= tolerance;
    }

    public static boolean equal(float a, float b){
        return Math.abs(a - b) <= FLOAT_ROUNDING_ERROR;
    }

    public static boolean equal(float a, float b, float tolerance){
        return Math.abs(a - b) <= tolerance;
    }

    /** @return the logarithm of value with base a */
    public static float log(float a, float value){
        return (float)(StrictMath.log(value) / StrictMath.log(a));
    }

    /** @return the logarithm of value with base 2 */
    public static float log2(float value){
        return log(2, value);
    }

    /** @return base-2 logarithm of the specified integer */
    public static int log2(int value){
        return value == 0 ? 0 : 31 - Integer.numberOfLeadingZeros(value);
    }

    /** Mod function that works properly for negative numbers. */
    public static float mod(float f, float n){
        return ((f % n) + n) % n;
    }

    /** Mod function that works properly for negative numbers. */
    public static int mod(int x, int n){
        return ((x % n) + n) % n;
    }

    /** @return a sampled value based on position in an array of float values. */
    public static float sample(float[] values, float time){
        time = Mathf.clamp(time);
        float pos = time * (values.length - 1);
        int cur = Math.min((int)(time * (values.length - 1)), values.length - 1);
        int next = Math.min(cur + 1, values.length - 1);
        float mod = (pos - cur);
        return lerp(values[cur], values[next], mod);
    }

    /** @return the input 0-1 value scaled to 0-1-0. */
    public static float slope(float fin){
        return 1f - Math.abs(fin - 0.5f) * 2f;
    }

    /** Converts a 0-1 value to 0-1 when it is in [offset, 1]. */
    public static float curve(float f, float offset){
        if(f < offset){
            return 0f;
        }else{
            return (f - offset) / (1f - offset);
        }
    }

    /** Converts a 0-1 value to 0-1 when it is in [from, to]. */
    public static float curve(float f, float from, float to){
        if(f < from){
            return 0f;
        }else if(f > to){
            return 1f;
        }else{
            return (f - from) / (to - from);
        }
    }

    public static float curveMargin(float f, float margin){
        return curveMargin(f, margin, margin);
    }

    public static float curveMargin(float f, float marginLeft, float marginRight){
        if(f < marginLeft) return f / marginLeft * 0.5f;
        if(f > 1f - marginRight) return (f - 1f + marginRight) / marginRight * 0.5f + 0.5f;
        return 0.5f;
    }

    public static float len(float x, float y){
        return (float)Math.sqrt(x * x + y * y);
    }

    public static float len2(float x, float y){
        return x * x + y * y;
    }

    public static float dot(float x1, float y1, float x2, float y2){
        return x1 * x2 + y1 * y2;
    }

    public static float dst(float x1, float y1){
        return (float)Math.sqrt(x1 * x1 + y1 * y1);
    }

    public static float dst2(float x1, float y1){
        return (x1 * x1 + y1 * y1);
    }

    public static float dst(float x1, float y1, float x2, float y2){
        final float xd = x2 - x1;
        final float yd = y2 - y1;
        return (float)Math.sqrt(xd * xd + yd * yd);
    }

    public static float dst2(float x1, float y1, float x2, float y2){
        final float xd = x2 - x1;
        final float yd = y2 - y1;
        return xd * xd + yd * yd;
    }

    /** Manhattan distance. */
    public static float dstm(float x1, float y1, float x2, float y2){
        return Math.abs(x1 - x2) + Math.abs(y1 - y2);
    }

    /** @return whether dst(x1, y1, x2, y2) < dst */
    public static boolean within(float x1, float y1, float x2, float y2, float dst){
        return dst2(x1, y1, x2, y2) < dst * dst;
    }

    /** @return whether dst(x, y, 0, 0) < dst */
    public static boolean within(float x1, float y1, float dst){
        return (x1 * x1 + y1 * y1) < dst * dst;
    }

    private Mathf(){}
}
