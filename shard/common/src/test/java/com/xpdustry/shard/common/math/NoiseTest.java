package com.xpdustry.shard.common.math;

import org.junit.jupiter.api.*;

import java.lang.management.*;
import java.lang.reflect.*;
import java.util.*;

import static com.xpdustry.shard.common.Differential.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Simplex} and {@link Ridged} against Arc's {@code arc.util.noise} classes, bit for bit: a seeded grid of the
 * inputs map generation filters pass (tile coordinates plus 10, {@code 1 / scale}, fractional octaves), then raw
 * functions over wide doubles, where Ridged's range folding and the floors of negative coordinates kick in.
 */
class NoiseTest{
    /** Seeds the filters see: {@code Mathf.random(999999999)} and its {@code + 1}, plus the int edges. */
    static int[] seeds(SplittableRandom random){
        int[] seeds = new int[INT_EDGES.length + 40];
        for(int i = 0; i < seeds.length; i++) seeds[i] = i < INT_EDGES.length ? INT_EDGES[i] : random.nextInt(1_000_000_001);
        return seeds;
    }

    @Test
    void everyPublicFunctionHasAPort(){
        var missing = new ArrayList<String>();
        for(Class<?>[] pair : new Class<?>[][]{{Simplex.class, arc.util.noise.Simplex.class}, {Ridged.class, arc.util.noise.Ridged.class}}){
            for(Method theirs : pair[1].getDeclaredMethods()){
                if(!Modifier.isPublic(theirs.getModifiers())) continue;
                if(counterpart(pair[0], theirs.getName(), theirs.getParameterTypes()).isEmpty()) missing.add(theirs.toString());
            }
        }
        assertEquals(List.of(), missing);
    }

    @Test
    void simplexMatchesArcOnAFilterGrid(){
        var random = new SplittableRandom(1607);
        float[] scales = {1f, 7.5f, 23.47619f, 40f, 155.5f, 500f};
        float[] octaves = {1f, 2f, 2.5f, 3f, 10f};
        float[] persistences = {0f, 0.3f, 0.5f, 1f};
        int compared = 0;
        for(int seed : seeds(random)){
            float scl = scales[random.nextInt(scales.length)], oct = octaves[random.nextInt(octaves.length)];
            float persistence = persistences[random.nextInt(persistences.length)];
            for(int x = -3; x < 600; x += 1 + random.nextInt(9)){
                for(int y = -3; y < 600; y += 1 + random.nextInt(9)){
                    // GenerateFilter.noise: x + 10, y + tilt * x + 10
                    float fy = y + x * (random.nextInt(4) == 0 ? (float)random.nextDouble(-4, 4) : 0f);
                    assertBits(arc.util.noise.Simplex.noise2d(seed, oct, persistence, 1f / scl, x + 10, fy + 10),
                        Simplex.noise2d(seed, oct, persistence, 1f / scl, x + 10, fy + 10),
                        "noise2d(" + seed + ", " + oct + ", " + persistence + ", 1/" + scl + ", " + (x + 10) + ", " + (fy + 10) + ")");
                    compared++;
                }
            }
        }
        assertTrue(compared > 100_000, "only " + compared);
    }

    @Test
    void ridgedMatchesArcOnAFilterGrid(){
        var random = new SplittableRandom(1013);
        float[] scales = {1f, 12f, 40f, 140f, 500f};
        double[] falloffs = {0, 0.3, 0.5, 1};
        int compared = 0;
        for(int seed : seeds(random)){
            float scl = scales[random.nextInt(scales.length)];
            int octaves = 1 + random.nextInt(10);
            double falloff = falloffs[random.nextInt(falloffs.length)];
            for(int x = -3; x < 600; x += 1 + random.nextInt(9)){
                for(int y = -3; y < 600; y += 1 + random.nextInt(9)){
                    // GenerateFilter.rnoise: seed + 1, the int coordinates
                    String what = "(" + (seed + 1) + ", " + x + ", " + y + ", " + octaves + ", " + falloff + ", 1/" + scl + ")";
                    assertBits(arc.util.noise.Ridged.noise2d(seed + 1, x, y, octaves, falloff, 1f / scl),
                        Ridged.noise2d(seed + 1, x, y, octaves, falloff, 1f / scl), "noise2d" + what);
                    assertBits(arc.util.noise.Ridged.noise2d(seed + 1, x, y, 1f / scl), Ridged.noise2d(seed + 1, x, y, 1f / scl), "noise2d" + what);
                    compared += 2;
                }
            }
        }
        assertTrue(compared > 100_000, "only " + compared);
    }

    @Test
    void rawFunctionsMatchArcOverWideInputs(){
        var random = new SplittableRandom(7445105);
        for(int i = 0; i < 200_000; i++){
            int seed = randomInt(random, i % 500);
            double x = wide(random, i), y = wide(random, i + 1), z = wide(random, i + 2), w = wide(random, i + 3);
            String what = "(" + seed + ", " + x + ", " + y + ", " + z + ", " + w + ")";
            assertBits(arc.util.noise.Simplex.raw2d(seed, x, y), Simplex.raw2d(seed, x, y), "raw2d" + what);
            assertBits(arc.util.noise.Simplex.raw3d(seed, x, y, z), Simplex.raw3d(seed, x, y, z), "raw3d" + what);
            assertBits(arc.util.noise.Simplex.raw4d(x, y, z, w), Simplex.raw4d(x, y, z, w), "raw4d" + what);
            int octaves = 1 + random.nextInt(4);
            double persistence = random.nextDouble();
            assertBits(arc.util.noise.Simplex.noise3d(seed, octaves, persistence, 0.01, x, y, z),
                Simplex.noise3d(seed, octaves, persistence, 0.01, x, y, z), "noise3d" + what);
            assertBits(arc.util.noise.Simplex.noise4d(octaves, persistence, 0.01, x, y, z, w),
                Simplex.noise4d(octaves, persistence, 0.01, x, y, z, w), "noise4d" + what);
            assertBits(rawTiled(x, y, z, w, 64, 48, 3.5), Simplex.rawTiled(x, y, z, w, 64, 48, 3.5), "rawTiled" + what);
            assertBits(arc.util.noise.Ridged.noise2d(seed, x, y, octaves, persistence, 0.5), Ridged.noise2d(seed, x, y, octaves, persistence, 0.5), "ridged2d" + what);
            assertBits(arc.util.noise.Ridged.noise2d(seed, x, y, octaves, 0.5), Ridged.noise2d(seed, x, y, octaves, 0.5), "ridged2d" + what);
            assertBits(arc.util.noise.Ridged.noise3d(seed, x, y, z, octaves, 0.25f), Ridged.noise3d(seed, x, y, z, octaves, 0.25f), "ridged3d" + what);
            assertBits(arc.util.noise.Ridged.noise3d(seed, x, y, z, 0.25f), Ridged.noise3d(seed, x, y, z, 0.25f), "ridged3d" + what);
        }
    }

    /** Whether this JVM runs without HotSpot's libm intrinsics, as the oracle tests do: then Arc's {@code Math.cos} is {@code StrictMath}'s. */
    static final boolean STRICT_LIBM = ManagementFactory.getRuntimeMXBean().getInputArguments().contains("-XX:-UseLibmIntrinsic");

    /**
     * Arc's {@code rawTiled}, as vanilla computes it under {@code -XX:-UseLibmIntrinsic}: Arc's own when this JVM has
     * the flag, else Arc's {@code raw4d} on the torus coordinates {@code rawTiled} derives with {@link StrictMath}.
     */
    static double rawTiled(double x, double y, double x1, double y1, double w, double h, double scl){
        if(STRICT_LIBM) return arc.util.noise.Simplex.rawTiled(x, y, x1, y1, w, h, scl);
        x /= scl;
        y /= scl;
        w /= scl;
        h /= scl;
        double s = x / w, t = y / h, dx = x1 + w - x1, dy = y1 + h - y1;
        return arc.util.noise.Simplex.raw4d(
            x1 + StrictMath.cos(s * 2 * arc.math.Mathf.PI) * dx / arc.math.Mathf.PI2,
            y1 + StrictMath.cos(t * 2 * arc.math.Mathf.PI) * dy / arc.math.Mathf.PI2,
            x1 + StrictMath.sin(s * 2 * arc.math.Mathf.PI) * dx / arc.math.Mathf.PI2,
            y1 + StrictMath.sin(t * 2 * arc.math.Mathf.PI) * dy / arc.math.Mathf.PI2);
    }

    /** A coordinate: the float edges first, then near zero, map-sized, huge (past Ridged's 2^30 fold) or any double. */
    static double wide(SplittableRandom random, int iteration){
        if(iteration < FLOAT_EDGES.length) return FLOAT_EDGES[iteration];
        return switch(random.nextInt(5)){
            case 0 -> random.nextDouble(-2, 2);
            case 1 -> random.nextDouble(-1000, 1000);
            case 2 -> random.nextDouble(-1e10, 1e10);
            case 3 -> Double.longBitsToDouble(random.nextLong());
            default -> random.nextInt(-600, 600) + 10;
        };
    }
}
