package com.xpdustry.shard.testkit;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Names and ids for one Shard world: blocks, unit types, items and liquids from its content epoch, teams from
 * {@link Teams}. Adapters use it to turn config names into ids and ids into the names projections hold.
 */
public final class ShardNames{
    private final World world;
    private final List<String> items, liquids;
    private final TreeMap<String, BlockDef> blocks = new TreeMap<>();
    private final TreeMap<String, UnitDef> units = new TreeMap<>();

    ShardNames(World world){
        this.world = world;
        ContentEpoch content = world.content();
        items = List.copyOf(content.names(ContentType.ITEM));
        liquids = List.copyOf(content.names(ContentType.LIQUID));
        for(int id = 0; id < content.blocks(); id++) blocks.putIfAbsent(content.block(id).name(), content.block(id));
        for(int id = 0; id < content.units(); id++) units.putIfAbsent(content.unit(id).name(), content.unit(id));
    }

    public World world(){
        return world;
    }

    public BlockDef block(String name){
        BlockDef block = blocks.get(name);
        if(block == null) throw new IllegalArgumentException("no block '" + name + "' in content " + world.content().hash());
        return block;
    }

    public UnitDef unit(String name){
        UnitDef unit = units.get(name);
        if(unit == null) throw new IllegalArgumentException("no unit type '" + name + "' in content " + world.content().hash());
        return unit;
    }

    public int item(String name){
        return id(items, name, "item");
    }

    public int liquid(String name){
        return id(liquids, name, "liquid");
    }

    public String itemName(int id){
        return name(items, id, "item");
    }

    public String liquidName(int id){
        return name(liquids, id, "liquid");
    }

    /** Item names for an array of item ids; negative ids are empty slots (null). */
    public List<String> itemNames(int[] ids){
        var out = new ArrayList<String>(ids.length);
        for(int id : ids) out.add(id < 0 ? null : itemName(id));
        return out;
    }

    public int team(String name){
        return Teams.id(name);
    }

    public int x(Building building){
        return world.tiles().x(building.tile());
    }

    public int y(Building building){
        return world.tiles().y(building.tile());
    }

    /** The building whose footprint covers (x, y). */
    public Building building(int x, int y){
        if(!world.tiles().inBounds(x, y)) throw new IllegalArgumentException("(" + x + ", " + y + ") is outside the world");
        Building building = world.buildings().at(world.tiles().pos(x, y));
        if(building == null) throw new IllegalArgumentException("no building at (" + x + ", " + y + ")");
        return building;
    }

    private String name(List<String> names, int id, String type){
        return id >= 0 && id < names.size() ? names.get(id) : type + "#" + id;
    }

    private int id(List<String> names, String name, String type){
        int id = names.indexOf(name);
        if(id < 0) throw new IllegalArgumentException("no " + type + " '" + name + "' in content " + world.content().hash());
        return id;
    }

    /**
     * The projection form of a Shard object: buildings as {@code "@x,y"}, units as {@code "#id"}, definitions by
     * name; anything else {@link Values#UNSUPPORTED}.
     */
    public Object ref(Object value){
        return switch(value){
            case Building b -> Values.building(x(b), y(b));
            case Unit u -> Values.unit(u.id());
            case BlockDef b -> b.name();
            case UnitDef u -> u.name();
            default -> Values.UNSUPPORTED;
        };
    }
}
