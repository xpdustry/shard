package com.xpdustry.shard.sim.world;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.unit.*;

import java.lang.reflect.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compares a world object with its fork copy, field by field, through every superclass. Primitives and arrays must be
 * equal; references to other {@link Copyable}s must point to their copies (never to the source object); owned
 * objects are compared recursively and must be distinct instances; content definitions and what is read from them
 * ({@link UnitParams}) are shared. A kind that
 * forgets a field in {@code copyState} fails here.
 */
final class DeepCompare{
    private final World source, fork;
    private final IdentityHashMap<Object, Object> seen = new IdentityHashMap<>();

    DeepCompare(World source, World fork){
        this.source = source;
        this.fork = fork;
    }

    /** {@code copy} must be the fork's copy of {@code original}. */
    void copyable(Object original, Object copy, String path){
        if(original == null){
            assertNull(copy, path);
            return;
        }
        assertNotNull(copy, path);
        assertNotSame(original, copy, path + " is shared with the source");
        assertEquals(original.getClass(), copy.getClass(), path);
        Object previous = seen.putIfAbsent(original, copy);
        if(previous != null){
            assertSame(previous, copy, path + " maps to two copies");
            return;
        }
        fields(original, copy, path);
    }

    private void fields(Object original, Object copy, String path){
        for(Class<?> type = original.getClass(); type != Object.class; type = type.getSuperclass()){
            for(Field field : type.getDeclaredFields()){
                if(Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                try{
                    value(field.get(original), field.get(copy), path + "." + field.getName());
                }catch(IllegalAccessException e){
                    throw new AssertionError(e);
                }
            }
        }
    }

    private void value(Object a, Object b, String path){
        if(a == null || b == null){
            assertEquals(a, b, path);
        }else if(a == source){
            assertSame(fork, b, path + " must be the fork");
        }else if(a instanceof Copyable){
            copyable(a, b, path);
        }else if(a instanceof BlockDef || a instanceof UnitDef || a instanceof UnitParams || a instanceof ContentEpoch
            || a instanceof String || a instanceof Number || a instanceof Boolean || a instanceof Character || a instanceof Enum<?>){
            assertEquals(a, b, path);
        }else if(a.getClass().isArray()){
            int length = Array.getLength(a);
            if(length > 0) assertNotSame(a, b, path + " array is shared");
            assertEquals(length, Array.getLength(b), path + " length");
            for(int i = 0; i < length; i++) value(Array.get(a, i), Array.get(b, i), path + "[" + i + "]");
        }else{
            assertNotSame(a, b, path + " is shared with the source");
            assertEquals(a.getClass(), b.getClass(), path);
            if(seen.putIfAbsent(a, b) == null) fields(a, b, path);
        }
    }
}
