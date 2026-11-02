package com.xpdustry.shard.sim.persist;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.world.*;

import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.*;

/**
 * Stores a record by its components, named as declared, and rebuilds it with the canonical constructor. Commands,
 * intents, causes, verdicts and timer tasks are records, so the command log and the timers need no hand-written
 * codecs. Components may be primitives, strings, enums, content definitions, world objects (by reference), primitive
 * arrays, lists, and owned objects of any storable class; anything else fails when the record is first stored.
 */
final class RecordPlan extends Plan{
    private enum Kind{BOOL, BYTE, SHORT, CHAR, INT, LONG, FLOAT, DOUBLE, STRING, ENUM, BLOCK, UNIT_TYPE, REF, LIST,
        BYTES, SHORTS, INTS, LONGS, FLOATS, DOUBLES, OBJECT}

    private final String[] names;
    private final Class<?>[] types;
    private final Kind[] kinds;
    private final MethodHandle[] accessors;
    private final MethodHandle constructor;

    RecordPlan(Class<?> type){
        super(type);
        RecordComponent[] components = type.getRecordComponents();
        names = new String[components.length];
        types = new Class<?>[components.length];
        kinds = new Kind[components.length];
        accessors = new MethodHandle[components.length];
        try{
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(type, MethodHandles.lookup());
            for(int i = 0; i < components.length; i++){
                names[i] = components[i].getName().intern();
                types[i] = components[i].getType();
                kinds[i] = kind(types[i]);
                accessors[i] = lookup.unreflect(components[i].getAccessor()).asType(MethodType.methodType(Object.class, Object.class));
            }
            constructor = lookup.findConstructor(type, MethodType.methodType(void.class, types))
                .asSpreader(Object[].class, components.length).asType(MethodType.methodType(Object.class, Object[].class));
        }catch(ReflectiveOperationException e){
            throw new CheckpointException("cannot reach the components of " + type.getName(), e);
        }
    }

    private static Kind kind(Class<?> type){
        if(type == boolean.class) return Kind.BOOL;
        if(type == byte.class) return Kind.BYTE;
        if(type == short.class) return Kind.SHORT;
        if(type == char.class) return Kind.CHAR;
        if(type == int.class) return Kind.INT;
        if(type == long.class) return Kind.LONG;
        if(type == float.class) return Kind.FLOAT;
        if(type == double.class) return Kind.DOUBLE;
        if(type == String.class) return Kind.STRING;
        if(type.isEnum()) return Kind.ENUM;
        if(BlockDef.class.isAssignableFrom(type)) return Kind.BLOCK;
        if(UnitDef.class.isAssignableFrom(type)) return Kind.UNIT_TYPE;
        if(Copyable.class.isAssignableFrom(type)) return Kind.REF;
        if(type == List.class) return Kind.LIST;
        if(type == byte[].class) return Kind.BYTES;
        if(type == short[].class) return Kind.SHORTS;
        if(type == int[].class) return Kind.INTS;
        if(type == long[].class) return Kind.LONGS;
        if(type == float[].class) return Kind.FLOATS;
        if(type == double[].class) return Kind.DOUBLES;
        return Kind.OBJECT;
    }

    @Override
    boolean owned(){
        return true;
    }

    @Override
    void write(Object value, CheckpointOut out){
        for(int i = 0; i < names.length; i++){
            Object component;
            try{
                component = accessors[i].invokeExact(value);
            }catch(Throwable e){
                throw new CheckpointException("cannot read " + type.getName() + "." + names[i], e);
            }
            String name = names[i];
            switch(kinds[i]){
                case BOOL -> out.bool(name, (Boolean)component);
                case BYTE -> out.b(name, (Byte)component);
                case SHORT -> out.s(name, (Short)component);
                case CHAR -> out.s(name, (Character)component);
                case INT -> out.i(name, (Integer)component);
                case LONG -> out.l(name, (Long)component);
                case FLOAT -> out.f(name, (Float)component);
                case DOUBLE -> out.d(name, (Double)component);
                case STRING -> out.str(name, (String)component);
                case ENUM -> out.e(name, (Enum<?>)component);
                case BLOCK -> out.block(name, (BlockDef)component);
                case UNIT_TYPE -> out.unitType(name, (UnitDef)component);
                case REF -> out.ref(name, (Copyable)component);
                case LIST -> out.list(name, (List<?>)component);
                case BYTES -> out.bytes(name, (byte[])component);
                case SHORTS -> out.shorts(name, (short[])component);
                case INTS -> out.ints(name, (int[])component);
                case LONGS -> out.longs(name, (long[])component);
                case FLOATS -> out.floats(name, (float[])component);
                case DOUBLES -> out.doubles(name, (double[])component);
                case OBJECT -> out.obj(name, component);
            }
        }
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    Object read(CheckpointIn in){
        var values = new Object[names.length];
        for(int i = 0; i < names.length; i++){
            String name = names[i];
            values[i] = switch(kinds[i]){
                case BOOL -> in.bool(name);
                case BYTE -> in.b(name);
                case SHORT -> in.s(name);
                case CHAR -> (char)in.s(name);
                case INT -> in.i(name);
                case LONG -> in.l(name);
                case FLOAT -> in.f(name);
                case DOUBLE -> in.d(name);
                case STRING -> in.str(name);
                case ENUM -> in.e(name, (Class<Enum>)types[i]);
                case BLOCK -> in.block(name);
                case UNIT_TYPE -> in.unitType(name);
                case REF -> in.ref(name, (Class<Copyable>)types[i]);
                case LIST -> in.list(name, Object.class);
                case BYTES -> in.bytes(name);
                case SHORTS -> in.shorts(name);
                case INTS -> in.ints(name);
                case LONGS -> in.longs(name);
                case FLOATS -> in.floats(name);
                case DOUBLES -> in.doubles(name);
                case OBJECT -> in.obj(name, types[i]);
            };
        }
        try{
            return constructor.invokeExact(values);
        }catch(CheckpointException e){
            throw e;
        }catch(Throwable e){
            throw new CheckpointException("the constructor of " + type.getName() + " refused the stored components", e);
        }
    }

    @Override
    void fill(Object value, CheckpointIn in){
        throw new CheckpointException(type.getName() + " is a record: it is read whole");
    }
}
