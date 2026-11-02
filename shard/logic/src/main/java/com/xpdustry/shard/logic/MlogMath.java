package com.xpdustry.shard.logic;

import com.xpdustry.shard.common.math.*;

/**
 * The math mlog results depend on that {@code :common} does not have, bit for bit (Arc 7445105c): {@code LogicOp}'s
 * double functions, {@code Simplex.raw2d}, {@code Strings.parseDouble}/{@code parseLong}/{@code parseInt} and
 * {@code Color}'s double packing. {@code angle}, {@code angleDiff} and {@code len} use {@link Angles} and {@link Mathf}.
 *
 * <p>Vanilla calls {@code Math.sin}, {@code Math.log}, {@code Math.pow}… Shard calls {@link StrictMath}, so results
 * don't depend on the JVM's intrinsics (the determinism rule in docs/development.md). They differ: on JDK 25 x86-64 with the
 * libm intrinsics on, 2–8% of {@code sin}, {@code cos}, {@code tan}, {@code log}, {@code log10} and {@code pow}
 * results are one ulp off fdlibm. With {@code -XX:-UseLibmIntrinsic} they agree, which is how the oracle tests run
 * vanilla.
 */
public final class MlogMath{
    static final double doubleDegRad = Mathf.doubleDegRad, doubleRadDeg = Mathf.doubleRadDeg;

    private MlogMath(){
    }

    /** {@code LogicOp.angle}: {@code Angles.angle((float)x, (float)y)}. */
    static double angle(double x, double y){
        return Angles.angle((float)x, (float)y);
    }

    /** {@code LogicOp.angleDiff}. */
    static double angleDiff(double x, double y){
        return Angles.angleDist((float)x, (float)y);
    }

    /** {@code LogicOp.len}. */
    static double len(double x, double y){
        return Mathf.dst((float)x, (float)y);
    }

    //region arc.util.noise.Simplex.raw2d

    private static final int[][] grad3 = {
    {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0},
    {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
    {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1}
    };

    /** {@code LogicOp.noise}: {@code Simplex.raw2d(0, x, y)}. */
    static double noise(int seed, double x, double y){
        double n0, n1, n2;
        double F2 = 0.5 * (Math.sqrt(3.0) - 1.0);
        double s = (x + y) * F2;
        int i = fastfloor(x + s);
        int j = fastfloor(y + s);
        double G2 = (3.0 - Math.sqrt(3.0)) / 6.0;
        double t = (i + j) * G2;
        double X0 = i - t, Y0 = j - t;
        double x0 = x - X0, y0 = y - Y0;
        int i1, j1;
        if(x0 > y0){
            i1 = 1;
            j1 = 0;
        }else{
            i1 = 0;
            j1 = 1;
        }
        double x1 = x0 - i1 + G2, y1 = y0 - j1 + G2;
        double x2 = x0 - 1.0 + 2.0 * G2, y2 = y0 - 1.0 + 2.0 * G2;
        int ii = i & 255, jj = j & 255;
        int gi0 = perm(seed, ii + perm(seed, jj)) % 12;
        int gi1 = perm(seed, ii + i1 + perm(seed, jj + j1)) % 12;
        int gi2 = perm(seed, ii + 1 + perm(seed, jj + 1)) % 12;
        double t0 = 0.5 - x0 * x0 - y0 * y0;
        if(t0 < 0){
            n0 = 0.0;
        }else{
            t0 *= t0;
            n0 = t0 * t0 * dot(grad3[gi0], x0, y0);
        }
        double t1 = 0.5 - x1 * x1 - y1 * y1;
        if(t1 < 0){
            n1 = 0.0;
        }else{
            t1 *= t1;
            n1 = t1 * t1 * dot(grad3[gi1], x1, y1);
        }
        double t2 = 0.5 - x2 * x2 - y2 * y2;
        if(t2 < 0){
            n2 = 0.0;
        }else{
            t2 *= t2;
            n2 = t2 * t2 * dot(grad3[gi2], x2, y2);
        }
        return 70.0 * (n0 + n1 + n2);
    }

    private static int perm(int seed, int x){
        x = (x & 255) * 0x45d9f3b;
        x = ((x >>> 16) ^ x) * (0x45d9f3b + seed);
        x = (x >>> 16) ^ x;
        return x & 0xff;
    }

    private static int fastfloor(double x){
        return x > 0 ? (int)x : (int)x - 1;
    }

    private static double dot(int[] g, double x, double y){
        return g[0] * x + g[1] * y;
    }

    //endregion
    //region arc.graphics.Color

    /** {@code Color.toDoubleBits(r, g, b, a)}: RGBA8888 in the low 32 bits of the raw double, so a subnormal. */
    public static double packColor(float r, float g, float b, float a){
        int rgba = ((int)(r * 255) << 24) | ((int)(g * 255) << 16) | ((int)(b * 255) << 8) | (int)(a * 255);
        return Double.longBitsToDouble(rgba & 0x00000000_ffffffffL);
    }

    /** {@code Color.toDoubleBits(int r, int g, int b, int a)}, as the assembler packs {@code %rrggbb[aa]}. */
    public static double packColor(int r, int g, int b, int a){
        return packColor(r / 255f, g / 255f, b / 255f, a / 255f);
    }

    /** The RGBA8888 int that {@link #packColor} stores. */
    public static double packColor(int rgba8888){
        return packColor(((rgba8888 & 0xff000000) >>> 24) / 255f, ((rgba8888 & 0x00ff0000) >>> 16) / 255f,
        ((rgba8888 & 0x0000ff00) >>> 8) / 255f, (rgba8888 & 0x000000ff) / 255f);
    }

    //endregion
    //region arc.util.Strings

    /** Arc's {@code Strings.parseDouble}: not {@code Double.parseDouble}, and not correctly rounded. */
    public static double parseDouble(String value, double defaultValue){
        int len = value.length();
        if(len == 0) return defaultValue;
        int sign = 1;
        int start = 0, end = len;
        char last = value.charAt(len - 1), first = value.charAt(0);
        if(last == 'F' || last == 'f' || last == '.') end--;
        if(first == '+') start = 1;
        if(first == '-'){
            start = 1;
            sign = -1;
        }
        if(start >= end) return defaultValue;
        int dot = -1, e = -1, dotCount = 0, eCount = 0;
        for(int i = start; i < end; i++){
            char c = value.charAt(i);
            if(c == '.'){
                dot = i;
                dotCount++;
            }
            if(c == 'e' || c == 'E'){
                e = i;
                eCount++;
            }
        }
        if(dotCount > 1 || eCount > 1) return defaultValue;
        if(dot != -1 && e != -1 && dot > e) return defaultValue;
        int mantissaEnd = (e != -1) ? e : end;
        long exponent = 0;
        if(e != -1){
            if(e + 1 >= end) return defaultValue;
            exponent = parseLong(value, 10, e + 1, end, Long.MIN_VALUE);
            if(exponent == Long.MIN_VALUE) return defaultValue;
        }
        if(dot != -1 && dot < end){
            long whole = start == dot ? 0 : parseLong(value, 10, start, dot, Long.MIN_VALUE);
            if(whole == Long.MIN_VALUE || whole < 0) return defaultValue;
            int decDigits = mantissaEnd - (dot + 1);
            if(decDigits == 0) return whole * StrictMath.pow(10, exponent) * sign;
            int used = Math.min(decDigits, 18);
            long dec = parseLong(value, 10, dot + 1, dot + 1 + used, Long.MIN_VALUE);
            if(dec < 0) return defaultValue;
            for(int i = dot + 1 + used; i < mantissaEnd; i++){
                char c = value.charAt(i);
                if(c < '0' || c > '9') return defaultValue;
            }
            double pow = StrictMath.pow(10, used);
            long p = (long)pow;
            double mantissa;
            if(whole <= (Long.MAX_VALUE - dec) / p){
                mantissa = (whole * p + dec) / pow;
            }else{
                mantissa = whole + dec / pow;
            }
            return mantissa * StrictMath.pow(10, exponent) * sign;
        }
        if(e != -1){
            long whole = parseLong(value, 10, start, e, Long.MIN_VALUE);
            if(whole == Long.MIN_VALUE) return defaultValue;
            return whole * StrictMath.pow(10, exponent) * sign;
        }
        long out = parseLong(value, 10, start, end, Long.MIN_VALUE);
        return out == Long.MIN_VALUE ? defaultValue : out * sign;
    }

    /** Arc's {@code Strings.parseLong(s, radix, start, end, default)}. */
    public static long parseLong(String s, int radix, int start, int end, long defaultValue){
        boolean negative = false;
        int i = start, len = end - start;
        long limit = -9223372036854775807L;
        if(len <= 0) return defaultValue;
        char firstChar = s.charAt(i);
        if(firstChar < '0'){
            if(firstChar == '-'){
                negative = true;
                limit = -9223372036854775808L;
            }else if(firstChar != '+'){
                return defaultValue;
            }
            if(len == 1) return defaultValue;
            ++i;
        }
        long multmin = limit / radix;
        long result;
        int digit;
        for(result = 0L; i < end; result -= digit){
            digit = Character.digit(s.charAt(i++), radix);
            if(digit < 0 || result < multmin) return defaultValue;
            result *= radix;
            if(result < limit + (long)digit) return defaultValue;
        }
        return negative ? result : -result;
    }

    /** Arc's {@code Strings.parseInt(s, radix, default, start, end)}. */
    public static int parseInt(String s, int radix, int defaultValue, int start, int end){
        long v = parseLong(s, radix, start, end, Long.MIN_VALUE);
        if(v == Long.MIN_VALUE || v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) return defaultValue;
        return (int)v;
    }

    /** Arc's {@code Strings.canParseInt}: quirk kept, {@code "-2147483648"} is not parseable (it is the sentinel). */
    public static boolean canParseInt(String s){
        return parseInt(s, 10, Integer.MIN_VALUE, 0, s.length()) != Integer.MIN_VALUE;
    }

    //endregion
}
