package com.xpdustry.shard.sim.world;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.entity.*;

/**
 * Copies and hashes records component by component, for world state held as records ({@link Timers.Task}s). A
 * component that is a world object maps through the fork's {@link CopyContext} and hashes as its entity id; nested
 * records recurse; other values are immutable by convention and kept. The records' shapes (their components and
 * canonical constructors) come from the registration index ({@link Shaped}), not reflection.
 */
final class Records{
    static Record copy(Record record, CopyContext context){
        Registries.Shape<Record> shape = shape(record);
        if(!shape.holdsObjects()) return record;
        Object[] values = shape.components().apply(record);
        for(int i = 0; i < values.length; i++){
            values[i] = switch(values[i]){
                case Copyable copyable -> context.map(copyable);
                case Record nested -> copy(nested, context);
                case null, default -> values[i];
            };
        }
        return shape.constructor().apply(values);
    }

    static void hash(Object value, Hasher hasher){
        switch(value){
            case null -> hasher.l(0x6E756C6CL);
            case Entity entity -> hasher.i(entity.id());
            case Integer i -> hasher.i(i);
            case Long l -> hasher.l(l);
            case Float f -> hasher.f(f);
            case Double d -> hasher.d(d);
            case Boolean b -> hasher.bool(b);
            case Short s -> hasher.i(s);
            case Byte b -> hasher.i(b);
            case Character c -> hasher.i(c);
            case String s -> hasher.str(s);
            case Enum<?> e -> hasher.str(e.name());
            case Record record -> {
                hasher.str(record.getClass().getName());
                for(Object component : shape(record).components().apply(record)) hash(component, hasher);
            }
            // Anything else has no defined hash; its class at least tells two states apart.
            default -> hasher.str(value.getClass().getName());
        }
    }

    @SuppressWarnings("unchecked") // a shape reads and makes instances of its own class
    private static Registries.Shape<Record> shape(Record record){
        return (Registries.Shape<Record>)(Registries.Shape<?>)Registries.shape(record.getClass());
    }

    private Records(){}
}
