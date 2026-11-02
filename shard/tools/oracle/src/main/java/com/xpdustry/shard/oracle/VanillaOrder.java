package com.xpdustry.shard.oracle;

import arc.struct.*;

import java.lang.reflect.*;

/**
 * Makes vanilla's proximity order deterministic. {@code Building.updateProximity}, {@code removeFromProximity} and
 * {@code Tile.changeBuild} collect neighbours in static {@code ObjectSet}s, and {@code Building} hashes by identity, so
 * vanilla's order differs from one JVM to the next. Swapping those sets for {@code OrderedSet}s keeps vanilla's code and
 * gives it first-seen order, which is the order Shard keeps. {@link Vanilla#boot()} applies it, before any world loads:
 * the fields are {@code static final}, so code compiled earlier may hold the old sets.
 */
final class VanillaOrder{
    private static boolean patched;

    static synchronized void patch(){
        if(patched) return;
        try{
            Class<?> type = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = type.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            replace(type, unsafe, initialized("mindustry.gen.Building").getDeclaredField("tmpTiles"));
            replace(type, unsafe, initialized("mindustry.world.Tile").getDeclaredField("tileSet"));
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
        patched = true;
    }

    /** The class, initialized now, so its static initializer can't overwrite the patch later. */
    private static Class<?> initialized(String name) throws ClassNotFoundException{
        return Class.forName(name, true, VanillaOrder.class.getClassLoader());
    }

    /** {@code unsafe.putObject(staticFieldBase(field), staticFieldOffset(field), new OrderedSet<>())}. */
    private static void replace(Class<?> type, Object unsafe, Field field) throws ReflectiveOperationException{
        Object base = type.getMethod("staticFieldBase", Field.class).invoke(unsafe, field);
        long offset = (long)type.getMethod("staticFieldOffset", Field.class).invoke(unsafe, field);
        type.getMethod("putObject", Object.class, long.class, Object.class).invoke(unsafe, base, offset, new OrderedSet<>());
        field.setAccessible(true);
        if(!(field.get(null) instanceof OrderedSet<?>)) throw new IllegalStateException(field + " was not replaced");
    }

    private VanillaOrder(){}
}
