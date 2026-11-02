package com.xpdustry.shard.common.math;

import org.junit.jupiter.api.*;

import java.lang.reflect.*;
import java.util.*;

import static com.xpdustry.shard.common.Differential.*;
import static org.junit.jupiter.api.Assertions.*;

/** Every function of {@link Mathf} against {@code arc.math.Mathf} from the vanilla jar, bit for bit. */
class MathfTest{
    /** The functions the simulation leans on hardest get 10^6 inputs each; the rest get 10^5. */
    static final Set<String> KEY = Set.of("sin", "cos", "sinDeg", "cosDeg", "atan2", "angle", "angleExact", "floor",
        "ceil", "round", "mod", "dst", "len", "within", "lerp", "approach", "clamp", "slerp");

    @BeforeAll
    static void deltaIsOne(){
        arc.util.Time.delta = 1f;
    }

    @Test
    void sinTableIsArcs() throws ReflectiveOperationException{
        Field ours = Mathf.class.getDeclaredField("sinTable");
        Field theirs = arc.math.Mathf.class.getDeclaredField("sinTable");
        ours.setAccessible(true);
        theirs.setAccessible(true);
        float[] a = (float[])ours.get(null), b = (float[])theirs.get(null);
        assertEquals(b.length, a.length);
        for(int i = 0; i < a.length; i++){
            assertEquals(Float.floatToIntBits(b[i]), Float.floatToIntBits(a[i]), "sinTable[" + i + "]");
        }
    }

    @Test
    void everyPureFunctionMatchesArc(){
        var missing = new ArrayList<String>();
        int checked = 0;
        for(Method ours : publicStatics(Mathf.class)){
            Class<?>[] types = ours.getParameterTypes();
            if(!primitives(types)) continue;
            Optional<Method> theirs = counterpart(arc.math.Mathf.class, ours.getName(), types);
            if(theirs.isEmpty()){
                missing.add(ours.toString());
                continue;
            }
            compare(ours, theirs.get(), KEY.contains(ours.getName()) ? 1_000_000 : 100_000);
            checked++;
        }
        assertEquals(List.of(), missing, "functions Arc doesn't have");
        assertTrue(checked >= 76, "only " + checked + " functions compared");
    }

    /** Arc's random helpers draw from the static {@code Mathf.rand}; Shard's take the world's {@link Rand}. */
    @Test
    void everyRandomHelperMatchesArcDrawingFromTheSameState(){
        var missing = new ArrayList<String>();
        int checked = 0;
        for(Method ours : publicStatics(Mathf.class)){
            Class<?>[] types = ours.getParameterTypes();
            if(types.length == 0 || types[0] != Rand.class) continue;
            Class<?>[] rest = Arrays.copyOfRange(types, 1, types.length);
            if(!primitives(rest)) continue;
            Optional<Method> theirs = counterpart(arc.math.Mathf.class, ours.getName(), rest);
            if(theirs.isEmpty()){
                missing.add(ours.toString());
                continue;
            }
            var random = new SplittableRandom(ours.toString().hashCode());
            long seed = random.nextLong();
            var rand = new Rand(seed);
            arc.math.Mathf.rand = new arc.math.Rand(seed);
            for(int i = 0; i < 100_000; i++){
                Object[] args = new Object[rest.length];
                for(int a = 0; a < args.length; a++) args[a] = randomArg(rest[a], random, i);
                Object[] withRand = new Object[types.length];
                withRand[0] = rand;
                System.arraycopy(args, 0, withRand, 1, args.length);
                Object expected = call(theirs.get(), null, args);
                Object actual = call(ours, null, withRand);
                assertBits(expected, actual, describe(ours, args));
                assertEquals(arc.math.Mathf.rand.seed0, rand.getState(0), "state after " + describe(ours, args));
                assertEquals(arc.math.Mathf.rand.seed1, rand.getState(1), "state after " + describe(ours, args));
            }
            checked++;
        }
        assertEquals(List.of(), missing, "helpers Arc doesn't have");
        assertTrue(checked > 15, "only " + checked + " helpers compared");
    }

    static void compare(Method ours, Method theirs, int iterations){
        Class<?>[] types = ours.getParameterTypes();
        var random = new SplittableRandom(ours.toString().hashCode());
        for(int i = 0; i < iterations; i++){
            Object[] args = new Object[types.length];
            for(int a = 0; a < args.length; a++) args[a] = randomArg(types[a], random, i);
            assertBits(call(theirs, null, args), call(ours, null, args), describe(ours, args));
        }
    }

    static List<Method> publicStatics(Class<?> type){
        return Arrays.stream(type.getDeclaredMethods())
            .filter(m -> Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers()))
            .sorted(Comparator.comparing(Method::toString))
            .toList();
    }
}
