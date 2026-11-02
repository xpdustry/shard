package com.xpdustry.shard.common;

import java.lang.reflect.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Helpers for comparing a port with Arc's class from the vanilla jar: argument generators that hit the edge cases
 * first and then wander over every scale, and comparisons that are exact to the bit (NaN equals NaN, {@code -0f}
 * differs from {@code 0f}).
 */
public final class Differential{
    public static final float[] FLOAT_EDGES = {
        0f, -0f, 1f, -1f, 0.5f, -0.5f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MIN_VALUE,
        -Float.MIN_VALUE, Float.MIN_NORMAL, Float.MAX_VALUE, -Float.MAX_VALUE, 90f, 180f, 270f, 360f, 720f, -90f, -360f,
        -720f, 1e7f, -1e7f, 16777216f, -16777216f, 3.4e38f, (float)Math.PI, (float)-Math.PI, (float)(Math.PI * 2),
        0.1f, 1e-7f, 16384f, 16384.5f, -16384.5f, 0.99999994f, 1.0000001f, 2.5f, -2.5f, 3.5f, -3.5f
    };
    public static final int[] INT_EDGES = {
        0, 1, -1, 2, -2, 3, 4, 7, 8, 15, 16, 31, 32, 63, 64, 100, 255, 256, 360, 1023, 1024, 65535, 65536,
        Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE - 1, Integer.MIN_VALUE + 1, 1 << 30, (1 << 30) + 1
    };
    public static final long[] LONG_EDGES = {0L, 1L, -1L, 2L, 42L, Long.MAX_VALUE, Long.MIN_VALUE, 1L << 32, -(1L << 32)};

    private Differential(){}

    /** A float: the edges first, then uniform over several scales, then any bit pattern. */
    public static float randomFloat(SplittableRandom random, int iteration){
        if(iteration < FLOAT_EDGES.length) return FLOAT_EDGES[iteration];
        return switch(random.nextInt(6)){
            case 0 -> (float)random.nextDouble(-1, 1);
            case 1 -> (float)random.nextDouble(-400, 400);
            case 2 -> (float)random.nextDouble(-1e6, 1e6);
            case 3 -> Float.intBitsToFloat(random.nextInt());
            case 4 -> (float)random.nextInt(-720, 721);
            default -> (float)random.nextDouble(0, 1);
        };
    }

    public static int randomInt(SplittableRandom random, int iteration){
        if(iteration < INT_EDGES.length) return INT_EDGES[iteration];
        return switch(random.nextInt(4)){
            case 0 -> random.nextInt(-100, 101);
            case 1 -> random.nextInt(-100_000, 100_001);
            case 2 -> random.nextInt();
            default -> random.nextInt(0, 2000);
        };
    }

    public static long randomLong(SplittableRandom random, int iteration){
        if(iteration < LONG_EDGES.length) return LONG_EDGES[iteration];
        return random.nextBoolean() ? random.nextLong() : random.nextLong(-1_000_000, 1_000_000);
    }

    public static Object randomArg(Class<?> type, SplittableRandom random, int iteration){
        if(type == float.class) return randomFloat(random, iteration);
        if(type == int.class) return randomInt(random, iteration);
        if(type == long.class) return randomLong(random, iteration);
        if(type == double.class) return iteration < FLOAT_EDGES.length ? (double)FLOAT_EDGES[iteration] : random.nextDouble(-1e6, 1e6);
        if(type == boolean.class) return random.nextBoolean();
        if(type == short.class) return (short)random.nextInt();
        if(type == byte.class) return (byte)random.nextInt();
        throw new IllegalArgumentException("no generator for " + type);
    }

    public static boolean primitives(Class<?>[] types){
        for(Class<?> type : types){
            if(!type.isPrimitive()) return false;
        }
        return true;
    }

    /** The result of a call, or the class of what it threw. */
    public static Object call(Method method, Object target, Object... args){
        try{
            return method.invoke(target, args);
        }catch(InvocationTargetException e){
            return new Thrown(e.getCause().getClass());
        }catch(IllegalAccessException e){
            throw new AssertionError(e);
        }
    }

    public record Thrown(Class<?> type){
    }

    /** Asserts bit equality of two boxed results. */
    public static void assertBits(Object expected, Object actual, String what){
        if(expected instanceof Float e && actual instanceof Float a){
            if(Float.floatToIntBits(e) != Float.floatToIntBits(a)){
                fail(what + ": expected " + e + " (" + Integer.toHexString(Float.floatToIntBits(e)) + ") but was " + a
                    + " (" + Integer.toHexString(Float.floatToIntBits(a)) + ")");
            }
        }else if(expected instanceof Double e && actual instanceof Double a){
            if(Double.doubleToLongBits(e) != Double.doubleToLongBits(a)) fail(what + ": expected " + e + " but was " + a);
        }else{
            assertEquals(expected, actual, what);
        }
    }

    /** The same method on Arc's class: same name, the given parameter types. */
    public static Optional<Method> counterpart(Class<?> arc, String name, Class<?>... types){
        try{
            return Optional.of(arc.getMethod(name, types));
        }catch(NoSuchMethodException e){
            return Optional.empty();
        }
    }

    public static String describe(Method method, Object[] args){
        return method.getName() + Arrays.toString(args);
    }
}
