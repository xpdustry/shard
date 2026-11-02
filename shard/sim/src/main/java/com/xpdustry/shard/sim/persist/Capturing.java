package com.xpdustry.shard.sim.persist;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * The writer's tables while one world is captured: the schema keys (classes, attachment keys, sections) with their
 * schemas, and the object table that turns references into indexes.
 *
 * <p>Object indexes: {@code 0 .. high - 1} are the building registry's slots, {@code high .. high + units - 1} the
 * unit list in order, and every other referenced world object (a removed building, a dead unit, a shared
 * {@code Copyable}) gets the next index when first referenced. So the big groups cost no lookups.
 */
final class Capturing{
    final World world;
    private final boolean recordOnly;
    private final ArrayList<String> keys = new ArrayList<>();
    private final ArrayList<Schema.Builder> schemas = new ArrayList<>();
    private final IdentityHashMap<Class<?>, Integer> classes = new IdentityHashMap<>();
    private final TreeMap<String, Integer> named = new TreeMap<>();

    private final Buildings buildings;
    private final int high;
    private final Entity[] units;
    private final int unitCount;
    private final IdentityHashMap<Object, Integer> extraIndex = new IdentityHashMap<>();
    private final ArrayList<Object> extras = new ArrayList<>();

    /**
     * For {@code world}, or for no world (logs): then references are refused. {@code recordOnly} only records schemas
     * (the coverage check): references are written as null.
     */
    Capturing(World world, boolean recordOnly){
        this.world = world;
        this.recordOnly = recordOnly;
        if(world == null){
            buildings = null;
            high = 0;
            units = new Entity[0];
            unitCount = 0;
        }else{
            buildings = world.buildings();
            high = buildings.indexLimit();
            units = (Entity[])Access.LIST_ITEMS.get(world.units());
            unitCount = world.units().size();
        }
    }

    int classIndex(Class<?> type){
        Integer index = classes.get(type);
        if(index == null){
            index = key(type.getName());
            classes.put(type, index);
        }
        return index;
    }

    Schema.Builder schema(Class<?> type){
        return schemas.get(classIndex(type));
    }

    int attachmentIndex(String key){
        return key("@" + key);
    }

    Schema.Builder attachmentSchema(String key){
        return schemas.get(attachmentIndex(key));
    }

    /** The schema of a section's own fields. */
    Schema.Builder section(String name){
        return schemas.get(key("#" + name));
    }

    private int key(String key){
        Integer index = named.get(key);
        if(index == null){
            index = keys.size();
            keys.add(key);
            schemas.add(new Schema.Builder(key));
            named.put(key, index);
        }
        return index;
    }

    /** Every schema recorded so far, by key index. */
    List<Schema> schemas(){
        return schemas.stream().map(Schema.Builder::build).toList();
    }

    /**
     * While set, references are written by {@linkplain #identity identity} instead of object index: inputs (a log's
     * entries, an inbox's pending inputs) are made outside the world they're replayed into, so they find their objects
     * again there, at the tick they're admitted.
     */
    boolean identities;

    /** For an input log: no world, references by identity. */
    static Capturing identities(){
        var capturing = new Capturing(null, false);
        capturing.identities = true;
        return capturing;
    }

    /**
     * A world object as an input stores it, independent of the world's object table: a building by its tile
     * ({@code tile << 1}), any other entity by its entity id ({@code id << 1 | 1}). {@link Reading#identified} finds it
     * again. Other world objects (graphs, modules) have no identity outside one world.
     */
    static int identity(Copyable value){
        return switch(value){
            case Building building -> building.tile() << 1;
            case Entity entity when entity.id() >= 0 -> entity.id() << 1 | 1;
            default -> throw new CheckpointException("an input may refer to buildings and entities only, not " + value);
        };
    }

    int indexOf(Copyable value){
        if(value == null || recordOnly) return -1;
        if(identities) return identity(value);
        if(world == null) throw new CheckpointException("a reference to " + value + " can't be stored outside a world checkpoint");
        if(value instanceof Building building){
            int index = building.index();
            if(index >= 0 && index < high && buildings.get(index) == building) return index;
        }else if(value instanceof Unit unit){
            int position = Unit.updateSlot().get(unit);
            if(position >= 0 && position < unitCount && units[position] == unit) return high + position;
        }
        Integer known = extraIndex.get(value);
        if(known != null) return known;
        if(value instanceof Entity entity && entity.world() != world){
            throw new CheckpointException("a reference leaves the world: " + value + " belongs to another world");
        }
        int index = high + unitCount + extras.size();
        extras.add(value);
        extraIndex.put(value, index);
        return index;
    }

    /** The registry's slot count: objects {@code 0 .. high - 1}. */
    int high(){
        return high;
    }

    int unitCount(){
        return unitCount;
    }

    /** The objects referenced outside the registry and the unit list, in index order; grows while they're written. */
    int extraCount(){
        return extras.size();
    }

    Object extra(int i){
        return extras.get(i);
    }

    int objectCount(){
        return high + unitCount + extras.size();
    }

    Object object(int index){
        if(index < high) return buildings.get(index);
        if(index < high + unitCount) return units[index - high];
        return extras.get(index - high - unitCount);
    }
}
