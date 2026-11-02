package com.xpdustry.shard.sim.persist;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.lang.invoke.*;
import java.util.*;

/**
 * The private state of foundation classes that the reader sets on blank objects and the foundation layers store: an
 * entity's world and flags, a building's block, tile and sleep state, a unit's type, the update lists' arrays. Tables
 * and parts store themselves ({@code TileGrid.write}, {@code Buildings.write}…); these fields stay private because a
 * public setter would let anyone rebind an entity. Each is a static final, so the JIT treats it like a field access. A
 * renamed field fails this class's initialization, naming the field.
 */
final class Access{
    static final VarHandle ENTITY_WORLD = field(Entity.class, "world", World.class);
    static final VarHandle ENTITY_ADDED = field(Entity.class, "added", boolean.class);
    static final VarHandle ENTITY_ATTACHMENTS = field(Entity.class, "attachments", Object[].class);
    static final MethodHandle KEY_BY_ID;
    static final VarHandle KEYS_BY_ID;

    static final VarHandle BUILDING_BLOCK = field(Building.class, "block", BlockDef.class);
    static final VarHandle BUILDING_TILE = field(Building.class, "tile", int.class);
    static final VarHandle BUILDING_INDEX = field(Building.class, "index", int.class);
    static final VarHandle BUILDING_SLEEPING = field(Building.class, "sleeping", boolean.class);
    static final VarHandle BUILDING_SLEEP_TIME = field(Building.class, "sleepTime", float.class);
    static final VarHandle BUILDING_PROXIMITY = field(Building.class, "proximity", Building[].class);
    static final VarHandle BUILDING_PROXIMITY_SIZE = field(Building.class, "proximitySize", int.class);
    static final VarHandle BUILDING_PAYLOAD_X = field(Building.class, "payloadX", float.class);
    static final VarHandle BUILDING_PAYLOAD_Y = field(Building.class, "payloadY", float.class);

    static final VarHandle UNIT_TYPE = field(Unit.class, "type", UnitDef.class);

    static final VarHandle LIST_ITEMS = field(ActiveList.class, "items", Entity[].class);
    static final VarHandle LIST_SIZE = field(ActiveList.class, "size", int.class);
    static final VarHandle LIST_INDEX = field(ActiveList.class, "index", int.class);

    static{
        try{
            MethodHandles.Lookup keys = MethodHandles.privateLookupIn(AttachmentKey.class, MethodHandles.lookup());
            KEY_BY_ID = keys.findStatic(AttachmentKey.class, "byId", MethodType.methodType(AttachmentKey.class, int.class));
            KEYS_BY_ID = keys.findStaticVarHandle(AttachmentKey.class, "byId", AttachmentKey[].class);
        }catch(ReflectiveOperationException e){
            throw new ExceptionInInitializerError("AttachmentKey.byId is gone: " + e);
        }
    }

    static AttachmentKey<?> key(int id){
        try{
            return (AttachmentKey<?>)KEY_BY_ID.invokeExact(id);
        }catch(Throwable e){
            throw new IllegalStateException("no attachment key with id " + id, e);
        }
    }

    /** Every attachment key created so far, by id. */
    static List<AttachmentKey<?>> keys(){
        return List.of((AttachmentKey<?>[])KEYS_BY_ID.getVolatile());
    }

    private static VarHandle field(Class<?> owner, String name, Class<?> type){
        try{
            return MethodHandles.privateLookupIn(owner, MethodHandles.lookup()).findVarHandle(owner, name, type);
        }catch(ReflectiveOperationException e){
            throw new ExceptionInInitializerError(owner.getName() + "." + name + " (" + type.getSimpleName()
                + ") is gone; update sim.persist.Access and the codec that stores it: " + e);
        }
    }

    private Access(){}
}
