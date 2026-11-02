package com.xpdustry.shard.common.math;

import org.junit.jupiter.api.*;

import java.lang.reflect.*;
import java.util.*;

import static com.xpdustry.shard.common.Differential.*;
import static org.junit.jupiter.api.Assertions.*;

/** Every function of {@link Angles} against {@code arc.math.Angles}, 10^6 inputs each, bit for bit. */
class AnglesTest{
    @Test
    void everyFunctionMatchesArc(){
        var missing = new ArrayList<String>();
        int checked = 0;
        for(Method ours : MathfTest.publicStatics(Angles.class)){
            Class<?>[] types = ours.getParameterTypes();
            assertTrue(primitives(types), ours + " takes objects");
            Optional<Method> theirs = counterpart(arc.math.Angles.class, ours.getName(), types);
            if(theirs.isEmpty()){
                missing.add(ours.toString());
                continue;
            }
            MathfTest.compare(ours, theirs.get(), 1_000_000);
            checked++;
        }
        assertEquals(List.of(), missing, "functions Arc doesn't have");
        assertTrue(checked >= 12, "only " + checked + " functions compared");
    }
}
