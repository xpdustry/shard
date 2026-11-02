package com.xpdustry.shard.sim.persist;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * The reader's tables while one world is restored: the stored schemas by key, the classes they name, the restored
 * objects by index, and the objects whose {@code restored()} hook runs at the end.
 */
final class Reading{
    final World world;
    final ContentEpoch content;
    private final String[] keys;
    private final Schema.Builder[] schemas;
    private final Class<?>[] types;
    private final TreeMap<String, Integer> named = new TreeMap<>();
    private Object[] objects = new Object[0];
    private final ArrayList<Object> hooked = new ArrayList<>();
    private final ArrayList<Plan> hookedPlans = new ArrayList<>();

    /** For {@code world} (null when reading a log), with the stored schemas. */
    Reading(World world, ContentEpoch content, List<Schema> stored){
        this.world = world;
        this.content = content;
        keys = new String[stored.size()];
        schemas = new Schema.Builder[stored.size()];
        types = new Class<?>[stored.size()];
        for(int i = 0; i < stored.size(); i++){
            keys[i] = stored.get(i).key();
            schemas[i] = new Schema.Builder(stored.get(i));
            if(named.put(keys[i], i) != null) throw new CheckpointException("the schema table lists " + keys[i] + " twice");
        }
    }

    Schema.Builder schema(String key){
        Integer index = named.get(key);
        if(index == null) throw new CheckpointException("the checkpoint has no schema for " + key);
        return schemas[index];
    }

    /** The schema of a section, or null if the checkpoint stored none (an older layout). */
    Schema.Builder section(String name){
        return schema("#" + name);
    }

    /** The class with key index {@code index}. */
    Class<?> type(int index){
        if(index < 0 || index >= keys.length) throw new CheckpointException("class index " + index + " is out of range");
        Class<?> type = types[index];
        if(type == null){
            String key = keys[index];
            if(key.startsWith("@") || key.startsWith("#")) throw new CheckpointException(key + " is not a class");
            try{
                type = Class.forName(key, false, Reading.class.getClassLoader());
            }catch(ClassNotFoundException e){
                throw new CheckpointException("the checkpoint stores a " + key + ", which this build doesn't have", e);
            }
            types[index] = type;
        }
        return type;
    }

    String attachmentKey(int index){
        if(index < 0 || index >= keys.length || !keys[index].startsWith("@")) throw new CheckpointException("bad attachment key index " + index);
        return keys[index].substring(1);
    }

    void objects(Object[] objects){
        this.objects = objects;
    }

    /** While set, references are identities ({@link Capturing#identity}) resolved in {@link #world}, or the bound one. */
    boolean identities;
    /** The world identities resolve in instead of {@link #world}: the one a log is replayed into ({@link #bind}). */
    private World bound;

    /** Resolves identities in {@code world} until the result is closed. */
    Bound bind(World world){
        World before = bound;
        bound = world;
        return () -> bound = before;
    }

    /** {@link #bind}'s scope. */
    interface Bound extends AutoCloseable{
        @Override
        void close();
    }

    <T> T object(int index, Class<T> type){
        if(index == -1) return null;
        if(identities) return identified(index, type);
        if(index < 0 || index >= objects.length || objects[index] == null) throw new CheckpointException("bad object index " + index);
        Object value = objects[index];
        if(!type.isInstance(value)) throw new CheckpointException("object " + index + " is a " + value.getClass().getName() + ", not a " + type.getName());
        return type.cast(value);
    }

    /**
     * The world object an input stored by identity: the building at its tile, or the synced entity with its id, as the
     * world is now. An input is decoded right before the tick that admitted it, so the object it named is there.
     */
    private <T> T identified(int identity, Class<T> type){
        World world = bound != null ? bound : this.world;
        if(world == null) throw new CheckpointException("this input refers to a world object; decode it with the world it is replayed into");
        int key = identity >>> 1;
        Object value;
        if((identity & 1) == 0){
            int width = world.tiles().width();
            Building building = key < world.tiles().size() ? world.buildings().at(key % width, key / width) : null;
            value = building != null && building.tile() == key ? building : null;
            if(value == null) throw new CheckpointException("an input names the building at tile " + key + ", which isn't there at tick " + world.ticks());
        }else{
            EntityGroups groups = world.find(EntityGroups.class);
            value = groups == null ? null : groups.byId(key);
            if(value == null) throw new CheckpointException("an input names entity #" + key + ", which isn't in the world at tick " + world.ticks());
        }
        if(!type.isInstance(value)) throw new CheckpointException("an input's " + type.getSimpleName() + " is a " + value.getClass().getName() + " now");
        return type.cast(value);
    }

    BlockDef block(int id){
        if(id == -1) return null;
        if(id < 0 || id >= content.blocks()) throw new CheckpointException("block id " + id + " is not in content " + content.hash());
        return content.block(id);
    }

    UnitDef unitType(int id){
        if(id == -1) return null;
        if(id < 0 || id >= content.units()) throw new CheckpointException("unit type " + id + " is not in content " + content.hash());
        return content.unit(id);
    }

    /** Remembers an object whose plan has a {@code restored()} hook. */
    void created(Object value, Plan plan){
        if(plan instanceof Plan.EntityPlan entity && hooks(entity.own) || plan instanceof Plan.OwnPlan own && hooks(own.own)){
            hooked.add(value);
            hookedPlans.add(plan);
        }
    }

    private static boolean hooks(Plan.Own own){
        return own instanceof Plan.Self || own instanceof Plan.ByCodec;
    }

    /** Runs every remembered {@code restored()} hook, in the order the objects were read. */
    void runHooks(){
        for(int i = 0; i < hooked.size(); i++) hookedPlans.get(i).restored(hooked.get(i));
    }
}
