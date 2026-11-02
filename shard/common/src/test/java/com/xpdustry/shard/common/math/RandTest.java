package com.xpdustry.shard.common.math;

import org.junit.jupiter.api.*;

import java.lang.reflect.*;
import java.util.*;

import static com.xpdustry.shard.common.Differential.*;
import static org.junit.jupiter.api.Assertions.*;

/** {@link Rand} against {@code arc.math.Rand}: random call sequences from the same seeds draw the same values. */
class RandTest{
    @Test
    void seedingMatchesArc(){
        var random = new SplittableRandom(1);
        for(int i = 0; i < 100_000; i++){
            long seed = randomLong(random, i);
            var ours = new Rand(seed);
            var theirs = new arc.math.Rand(seed);
            assertEquals(theirs.seed0, ours.getState(0), "seed0 of " + seed);
            assertEquals(theirs.seed1, ours.getState(1), "seed1 of " + seed);
            ours.setSeed(~seed);
            theirs.setSeed(~seed);
            assertEquals(theirs.seed0, ours.getState(0));
            assertEquals(theirs.seed1, ours.getState(1));
        }
    }

    @Test
    void everyMethodDrawsTheSameSequence(){
        var methods = new ArrayList<Method[]>();
        for(Method ours : Rand.class.getMethods()){
            if(ours.getDeclaringClass() != Rand.class || Modifier.isStatic(ours.getModifiers())) continue;
            if(!primitives(ours.getParameterTypes()) || ours.getName().startsWith("set") || ours.getName().equals("copy")) continue;
            // getState is checked after every call; next(bits) is protected in Arc and reached through the others;
            // toString is Shard's own.
            if(Set.of("getState", "next", "toString").contains(ours.getName())) continue;
            Method theirs = counterpart(arc.math.Rand.class, ours.getName(), ours.getParameterTypes())
                .orElseThrow(() -> new AssertionError("Arc's Rand has no " + ours));
            methods.add(new Method[]{ours, theirs});
        }
        methods.sort(Comparator.comparing(m -> m[0].toString()));
        assertEquals(14, methods.size(), "drawing methods compared");
        var random = new SplittableRandom(2);
        for(int run = 0; run < 100; run++){
            long seed = random.nextLong();
            var ours = new Rand(seed);
            var theirs = new arc.math.Rand(seed);
            for(int i = 0; i < 10_000; i++){
                Method[] pair = methods.get(random.nextInt(methods.size()));
                Class<?>[] types = pair[0].getParameterTypes();
                Object[] args = new Object[types.length];
                for(int a = 0; a < args.length; a++) args[a] = randomArg(types[a], random, i);
                assertBits(call(pair[1], theirs, args), call(pair[0], ours, args), describe(pair[0], args));
                assertEquals(theirs.seed0, ours.getState(0), "after " + describe(pair[0], args));
                assertEquals(theirs.seed1, ours.getState(1), "after " + describe(pair[0], args));
            }
        }
    }

    @Test
    void nextBytesMatchesArc(){
        var random = new SplittableRandom(3);
        for(int i = 0; i < 1000; i++){
            long seed = random.nextLong();
            byte[] a = new byte[random.nextInt(0, 40)], b = new byte[a.length];
            new Rand(seed).nextBytes(a);
            new arc.math.Rand(seed).nextBytes(b);
            assertArrayEquals(b, a);
        }
    }

    @Test
    void stateRoundTripsAndCopiesAreIndependent(){
        var rand = new Rand(42);
        rand.nextLong();
        var copy = rand.copy();
        assertEquals(rand.nextLong(), copy.nextLong());
        rand.nextLong();
        assertNotEquals(rand.getState(0), copy.getState(0));
        var restored = new Rand(0);
        restored.setState(copy.getState(0), copy.getState(1));
        assertEquals(copy.nextLong(), restored.nextLong());
    }
}
