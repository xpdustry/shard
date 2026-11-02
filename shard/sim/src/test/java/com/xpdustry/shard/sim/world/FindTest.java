package com.xpdustry.shard.sim.world;

import com.xpdustry.shard.sim.unit.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link World#find} answers what a scan of {@link World#parts()} does, for every class and interface a vanilla world's
 * parts have (more types than its table first holds, so it grows), for a type no part has, twice over, and in a fork.
 */
class FindTest{
    @Test
    void findAnswersTheFirstPartOfEachType(){
        World world = UnitWorlds.world(40, 40, 1);
        Set<Class<?>> types = types(world);
        // more than the 32 its first table holds at most half full
        assertTrue(types.size() > 32, "only " + types.size() + " types");
        for(int round = 0; round < 2; round++){
            for(Class<?> type : types) assertSame(scan(world, type), world.find(type), type.getName());
            assertNull(world.find(FindTest.class));
        }
        World fork = world.fork();
        for(Class<?> type : types) assertSame(scan(fork, type), fork.find(type), type.getName());
    }

    /** Every class and interface of every part, in a fixed order. */
    static Set<Class<?>> types(World world){
        var types = new LinkedHashSet<Class<?>>();
        for(WorldPart part : world.parts()){
            var queue = new ArrayDeque<Class<?>>(List.of(part.getClass()));
            while(!queue.isEmpty()){
                Class<?> type = queue.poll();
                if(!types.add(type)) continue;
                if(type.getSuperclass() != null) queue.add(type.getSuperclass());
                queue.addAll(List.of(type.getInterfaces()));
            }
        }
        return types;
    }

    static Object scan(World world, Class<?> type){
        for(WorldPart part : world.parts()) if(type.isInstance(part)) return part;
        return null;
    }
}
