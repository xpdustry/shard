package com.xpdustry.shard.common.math;

import org.junit.jupiter.api.*;

import java.lang.reflect.*;
import java.util.*;

import static com.xpdustry.shard.common.Differential.*;
import static org.junit.jupiter.api.Assertions.*;

/** Every {@link Interp} curve against {@code arc.math.Interp}'s, bit for bit. */
class InterpTest{
    @Test
    void everyNamedCurveMatchesArc() throws ReflectiveOperationException{
        int checked = 0;
        for(Field field : Interp.class.getFields()){
            if(!Interp.class.isAssignableFrom(field.getType())) continue;
            Field theirs = arc.math.Interp.class.getField(field.getName());
            Interp ours = (Interp)field.get(null);
            arc.math.Interp arc = (arc.math.Interp)theirs.get(null);
            compare(field.getName(), ours, arc);
            checked++;
        }
        assertEquals(arc.math.Interp.class.getFields().length, checked, "Arc has curves Shard lacks");
    }

    @Test
    void parameterizedCurvesMatchArc() throws ReflectiveOperationException{
        int checked = 0;
        for(Class<?> nested : Interp.class.getClasses()){
            if(!Interp.class.isAssignableFrom(nested) || Modifier.isAbstract(nested.getModifiers())) continue;
            Class<?> arcNested = Class.forName("arc.math.Interp$" + nested.getSimpleName());
            for(Constructor<?> constructor : nested.getConstructors()){
                Class<?>[] types = constructor.getParameterTypes();
                if(types.length == 0 || !primitives(types)) continue;
                Constructor<?> arcConstructor = arcNested.getConstructor(types);
                var random = new SplittableRandom(constructor.toString().hashCode());
                for(int sample = 0; sample < 20; sample++){
                    Object[] args = new Object[types.length];
                    for(int a = 0; a < args.length; a++){
                        args[a] = types[a] == int.class ? (Object)(1 + random.nextInt(8)) : (Object)(float)random.nextDouble(0.5, 10);
                    }
                    Object ours, theirs;
                    try{
                        theirs = arcConstructor.newInstance(args);
                    }catch(InvocationTargetException e){
                        var refused = assertThrows(InvocationTargetException.class, () -> constructor.newInstance(args));
                        assertEquals(e.getCause().getClass(), refused.getCause().getClass(), nested.getSimpleName() + Arrays.toString(args));
                        continue;
                    }
                    ours = constructor.newInstance(args);
                    compare(nested.getSimpleName() + Arrays.toString(args), (Interp)ours, (arc.math.Interp)theirs);
                    checked++;
                }
            }
        }
        assertTrue(checked > 100, "only " + checked + " curves compared");
    }

    static void compare(String name, Interp ours, arc.math.Interp arc){
        var random = new SplittableRandom(name.hashCode());
        for(int i = 0; i < 100_000; i++){
            float a = i < FLOAT_EDGES.length ? FLOAT_EDGES[i] : i % 2 == 0 ? (float)random.nextDouble() : (float)random.nextDouble(-2, 3);
            assertBits(arc.apply(a), ours.apply(a), name + "(" + a + ")");
        }
    }
}
