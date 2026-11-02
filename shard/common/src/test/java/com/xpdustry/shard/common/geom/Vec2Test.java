package com.xpdustry.shard.common.geom;

import com.xpdustry.shard.common.math.*;
import org.junit.jupiter.api.*;

import java.lang.reflect.*;
import java.util.*;

import static com.xpdustry.shard.common.Differential.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Every method of {@link Vec2} against {@code arc.math.geom.Vec2}: same receiver, same arguments, then the result
 * and the receiver's x and y must be bit-identical. Vec2 arguments map to Arc Vec2s with the same coordinates.
 */
class Vec2Test{
    @BeforeAll
    static void deltaIsOne(){
        arc.util.Time.delta = 1f;
    }

    @Test
    void everyMethodMatchesArc(){
        var missing = new ArrayList<String>();
        int checked = 0;
        for(Method ours : Vec2.class.getDeclaredMethods()){
            if(!Modifier.isPublic(ours.getModifiers()) || Modifier.isStatic(ours.getModifiers())) continue;
            if(Set.of("equals", "hashCode", "toString").contains(ours.getName())) continue;
            Class<?>[] types = ours.getParameterTypes();
            // Rand and Interp arguments have their own tests below.
            if(Arrays.asList(types).contains(Rand.class) || Arrays.asList(types).contains(Interp.class)) continue;
            Optional<Method> theirs = arcMethod(ours);
            if(theirs.isEmpty()){
                missing.add(ours.toString());
                continue;
            }
            compare(ours, theirs.get());
            checked++;
        }
        assertEquals(List.of(), missing, "methods Arc doesn't have");
        assertTrue(checked > 50, "only " + checked + " methods compared");
    }

    @Test
    void randomDirectionDrawsLikeArc(){
        var random = new SplittableRandom(5);
        for(int i = 0; i < 10_000; i++){
            long seed = random.nextLong();
            arc.math.Mathf.rand = new arc.math.Rand(seed);
            var ours = new Vec2().setToRandomDirection(new Rand(seed));
            var theirs = new arc.math.geom.Vec2().setToRandomDirection();
            assertBits(theirs.x, ours.x, "x");
            assertBits(theirs.y, ours.y, "y");
        }
    }

    @Test
    void interpolateMatchesArc(){
        var random = new SplittableRandom(6);
        for(int i = 0; i < 100_000; i++){
            float x = randomFloat(random, i), y = randomFloat(random, i + 1), tx = randomFloat(random, i + 2);
            float ty = randomFloat(random, i + 3), alpha = (float)random.nextDouble(-0.5, 1.5);
            var ours = new Vec2(x, y).interpolate(new Vec2(tx, ty), alpha, Interp.smooth);
            var theirs = new arc.math.geom.Vec2(x, y).interpolate(new arc.math.geom.Vec2(tx, ty), alpha, arc.math.Interp.smooth);
            assertBits(theirs.x, ours.x, "x");
            assertBits(theirs.y, ours.y, "y");
        }
    }

    static Optional<Method> arcMethod(Method ours){
        outer:
        for(Method candidate : arc.math.geom.Vec2.class.getMethods()){
            if(!candidate.getName().equals(ours.getName()) || candidate.getParameterCount() != ours.getParameterCount()) continue;
            Class<?>[] a = ours.getParameterTypes(), b = candidate.getParameterTypes();
            for(int i = 0; i < a.length; i++){
                boolean same = a[i].isPrimitive() ? a[i] == b[i] : a[i] == Vec2.class && b[i].isAssignableFrom(arc.math.geom.Vec2.class);
                if(!same) continue outer;
            }
            return Optional.of(candidate);
        }
        return Optional.empty();
    }

    static void compare(Method ours, Method theirs){
        Class<?>[] types = ours.getParameterTypes();
        var random = new SplittableRandom(ours.toString().hashCode());
        for(int i = 0; i < 100_000; i++){
            float x = randomFloat(random, i), y = randomFloat(random, (i * 7) % 50);
            Object[] args = new Object[types.length], arcArgs = new Object[types.length];
            for(int a = 0; a < types.length; a++){
                if(types[a] == Vec2.class){
                    float vx = randomFloat(random, i + a), vy = randomFloat(random, i + a + 1);
                    args[a] = new Vec2(vx, vy);
                    arcArgs[a] = new arc.math.geom.Vec2(vx, vy);
                }else{
                    args[a] = arcArgs[a] = types[a] == int.class ? (Object)random.nextInt(-3, 4) : randomArg(types[a], random, i);
                }
            }
            var mine = new Vec2(x, y);
            var arc = new arc.math.geom.Vec2(x, y);
            Object expected = call(theirs, arc, arcArgs), actual = call(ours, mine, args);
            String what = "(" + x + ", " + y + ")." + describe(ours, args);
            if(expected instanceof arc.math.geom.Vec2 e && actual instanceof Vec2 v){
                assertBits(e.x, v.x, what + " result x");
                assertBits(e.y, v.y, what + " result y");
            }else{
                assertBits(expected, actual, what);
            }
            assertBits(arc.x, mine.x, what + " x");
            assertBits(arc.y, mine.y, what + " y");
        }
    }
}
