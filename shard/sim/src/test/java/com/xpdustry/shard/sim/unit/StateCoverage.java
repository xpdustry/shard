package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.world.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that state is covered by copies and hashes, by reflection over every field:
 * <ul>
 *   <li>{@link #assertForked} compares an object with its fork copy as {@code world/DeepCompare} does: primitives and
 *   arrays equal, references to world objects mapped to their copies, owned objects distinct and equal;</li>
 *   <li>{@link #assertHashed} changes every primitive reachable through owned objects, one at a time, and checks that
 *   the hash notices.</li>
 * </ul>
 */
public final class StateCoverage{
    private final World source, fork;
    private final IdentityHashMap<Object, Object> seen = new IdentityHashMap<>();

    private StateCoverage(World source, World fork){
        this.source = source;
        this.fork = fork;
    }

    /** {@code copy} is {@code original}'s copy in {@code fork}, field by field. */
    public static void assertForked(World source, World fork, Object original, Object copy, String path){
        new StateCoverage(source, fork).compare(original, copy, path);
    }

    private void compare(Object original, Object copy, String path){
        assertNotNull(copy, path);
        assertNotSame(original, copy, path + " is shared with the source");
        assertEquals(original.getClass(), copy.getClass(), path);
        Object previous = seen.putIfAbsent(original, copy);
        if(previous != null){
            assertSame(previous, copy, path + " maps to two copies");
            return;
        }
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
        }else if(shared(a)){
            assertEquals(a, b, path);
        }else if(a.getClass().isArray()){
            int length = Array.getLength(a);
            if(length > 0) assertNotSame(a, b, path + " array is shared");
            assertEquals(length, Array.getLength(b), path + " length");
            for(int i = 0; i < length; i++) value(Array.get(a, i), Array.get(b, i), path + "[" + i + "]");
        }else{
            compare(a, b, path);
        }
    }

    /** Content and the immutable views of it that forks share: unit params, bullet types, weapons. */
    private static boolean shared(Object value){
        return value instanceof BlockDef || value instanceof UnitDef || value instanceof UnitParams || value instanceof ContentEpoch
            || value instanceof BulletTypes || value instanceof BulletType || value instanceof Armory || value instanceof Weapon
            || value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Character || value instanceof Enum<?>;
    }

    /**
     * Changes each primitive field reachable from {@code root} through owned objects and arrays (not through world
     * objects, content or {@code skip}ped fields), one at a time, and asserts that {@code hash} changes each time. The
     * field is restored afterwards. Fields named in {@code skip} ({@code Class.field}, or a field name alone) are
     * derived scratch that no hash should cover.
     *
     * @return how many fields were checked
     */
    public static int assertHashed(Object root, LongSupplier hash, Set<String> skip){
        return assertHashed(root, hash, skip, Set.of());
    }

    /** As {@link #assertHashed(Object, LongSupplier, Set)}, also walking into the {@code Copyable}s of class {@code into}. */
    public static int assertHashed(Object root, LongSupplier hash, Set<String> skip, Set<Class<?>> into){
        long base = hash.getAsLong();
        var visited = Collections.newSetFromMap(new IdentityHashMap<>());
        var missed = new ArrayList<String>();
        int[] checked = {0};
        var walk = new Walk(hash, base, skip, into, visited, missed, checked);
        walk.walk(root, root.getClass().getSimpleName());
        assertEquals(List.of(), missed, "fields the hash does not cover");
        return checked[0];
    }

    private record Walk(LongSupplier hash, long base, Set<String> skip, Set<Class<?>> into, Set<Object> visited,
                        List<String> missed, int[] checked){
        void walk(Object object, String path){
            if(!visited.add(object)) return;
            for(Class<?> type = object.getClass(); type != Object.class && type != null; type = type.getSuperclass()){
                for(Field field : type.getDeclaredFields()){
                    if(Modifier.isStatic(field.getModifiers())) continue;
                    if(skip.contains(field.getName()) || skip.contains(type.getSimpleName() + "." + field.getName())) continue;
                    field.setAccessible(true);
                    String at = path + "." + field.getName();
                    try{
                        if(field.getType().isPrimitive()){
                            Object old = field.get(object);
                            field.set(object, changed(old));
                            boolean noticed = noticed();
                            field.set(object, old);
                            assertEquals(base, hash.getAsLong(), at + " did not restore");
                            if(!noticed) missed.add(at);
                            checked[0]++;
                        }else{
                            Object value = field.get(object);
                            if(value != null && owned(value)) visit(value, at);
                        }
                    }catch(IllegalAccessException e){
                        throw new AssertionError(at, e);
                    }
                }
            }
        }

        /** An owned object or array: arrays of primitives change their first element, other arrays visit each element. */
        void visit(Object value, String path){
            if(!value.getClass().isArray()){
                walk(value, path);
                return;
            }
            int length = Array.getLength(value);
            if(value.getClass().getComponentType().isPrimitive()){
                if(length == 0) return;
                Object old = Array.get(value, 0);
                Array.set(value, 0, changed(old));
                boolean noticed = noticed();
                Array.set(value, 0, old);
                if(!noticed) missed.add(path + "[0]");
                checked[0]++;
                return;
            }
            for(int i = 0; i < length; i++){
                Object element = Array.get(value, i);
                if(element != null && owned(element)) visit(element, path + "[" + i + "]");
            }
        }

        /** Whether the hash changed; a hash that throws (a size past the filled part) noticed too. */
        boolean noticed(){
            try{
                return hash.getAsLong() != base;
            }catch(RuntimeException e){
                return true;
            }
        }

        /** Owned state worth walking: not world objects, content, the world, boxed values or JDK internals. */
        boolean owned(Object value){
            if(into.contains(value.getClass())) return true;
            if(value instanceof Copyable || value instanceof World || shared(value) || value.getClass().isRecord()) return false;
            if(value instanceof Rand) return true;
            String name = value.getClass().getName();
            return value.getClass().isArray() || name.startsWith("com.xpdustry.shard.");
        }
    }

    private static Object changed(Object value){
        return switch(value){
            case Integer i -> i + 1;
            case Float f -> f + 1.5f;
            case Boolean b -> !b;
            case Double d -> d + 1.5;
            case Long l -> l + 1;
            case Short s -> (short)(s + 1);
            case Byte b -> (byte)(b + 1);
            case Character c -> (char)(c + 1);
            default -> throw new IllegalArgumentException(String.valueOf(value));
        };
    }

    private StateCoverage(){
        this(null, null);
    }
}
