package com.xpdustry.shard.compat.v160.world;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.content.ContentType;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.*;

import java.util.*;

/**
 * Writes live entities (units, fires, puddles, weather states) as a stream's entity chunks: {@link EntityProjector}'s save body, under
 * a given class id, in the stream's content ids. The projector writes the epoch's ids; when the stream's header names
 * content otherwise (a save's id space, {@link ContentHeader#extend extended}), the body is read back and its content
 * ids moved: a unit's type, stack item, statuses, plans' blocks, {@code CommandAI} command and stances, and carried
 * units', recursively; a puddle's liquid; a weather state's weather.
 */
final class StreamEntities{
    private final World world;
    private final ContentHeader.IdMapping ids;
    private final EntityProjector projector;
    private final WireContext current, stream;
    private final boolean identity;

    StreamEntities(World world, BuildingCodecs codecs, ContentHeader.IdMapping ids, Map<Integer, Integer> savedClasses){
        this.world = world;
        this.ids = ids;
        this.projector = new EntityProjector(world.content(), codecs, savedClasses);
        this.current = WorldWireContext.capture(world);
        this.stream = new FileWireContext(world, codecs, ids);
        this.identity = identity(world, ids);
    }

    /** The class {@code unit} writes as ({@link EntityProjector#classOf}). */
    int classOf(com.xpdustry.shard.sim.unit.Unit unit){
        return projector.classOf(unit);
    }

    /** {@code entity}'s chunk under {@code classId}: its id and save body. */
    MapState.EntityChunk chunk(Entity entity, int classId){
        var bytes = new Writes();
        EntityClasses.writeSave(bytes, classId, projector.project(entity), current);
        if(!identity){
            WireEntity state = EntityClasses.readSave(new Reads(bytes.toByteArray()), classId, current);
            toStream(state);
            bytes = new Writes();
            EntityClasses.writeSave(bytes, classId, state, stream);
        }
        return new MapState.EntityChunk(classId, entity.id(), bytes.toByteArray());
    }

    private static boolean identity(World world, ContentHeader.IdMapping ids){
        for(ContentType type : ContentType.values()){
            for(int id = 0; id < world.content().count(type); id++) if(ids.toStream(type, id) != id) return false;
        }
        for(var type : List.of(com.xpdustry.shard.wire.v160_7.ContentType.unitCommand, com.xpdustry.shard.wire.v160_7.ContentType.unitStance)){
            List<String> names = ids.header().names(type.ordinal());
            List<String> current = type == com.xpdustry.shard.wire.v160_7.ContentType.unitCommand ? world.content().unitCommands() : world.content().unitStances();
            if(!names.isEmpty() && !names.equals(current.subList(0, Math.min(current.size(), names.size())))) return false;
        }
        return true;
    }

    /** Moves {@code state}'s content ids from the epoch's to the stream's. Every unit layout's state has these fields. */
    private void toStream(WireEntity state){
        if(state instanceof WeatherStateLayout.State weather){
            weather.weather = (short)stream(ContentType.WEATHER, weather.weather);
            return;
        }
        if(state instanceof PuddleLayout.State puddle){
            puddle.liquid = (short)stream(ContentType.LIQUID, puddle.liquid);
            return;
        }
        if(state instanceof FireLayout.State) return;
        if(state instanceof LaunchPayloadLayout.State payload){
            payload.stacks.replaceAll(stack -> new Codecs.ItemStack(stream(ContentType.ITEM, stack.item()), stack.amount()));
            return;
        }
        set(state, "type", (short)stream(ContentType.UNIT, (Short)field(state, "type")));
        if(field(state, "stack") instanceof Codecs.ItemStack stack){
            set(state, "stack", new Codecs.ItemStack(stream(ContentType.ITEM, stack.item()), stack.amount()));
        }
        if(field(state, "statuses") instanceof List<?> statuses){
            var out = new ArrayList<Codecs.StatusEntry>();
            for(Object entry : statuses){
                var e = (Codecs.StatusEntry)entry;
                out.add(new Codecs.StatusEntry(stream(ContentType.STATUS, e.effect()), e.time(), e.damageMultiplier(), e.healthMultiplier(),
                    e.speedMultiplier(), e.reloadMultiplier(), e.buildSpeedMultiplier(), e.dragMultiplier(), e.armorOverride()));
            }
            set(state, "statuses", out);
        }
        if(field(state, "plans") instanceof List<?> plans){
            var out = new ArrayList<Codecs.Plan>();
            for(Object entry : plans){
                var p = (Codecs.Plan)entry;
                out.add(p == null || p.breaking() ? p : new Codecs.Plan(false, p.pos(), stream(ContentType.BLOCK, p.block()), p.rotation(), p.config()));
            }
            set(state, "plans", out);
        }
        if(field(state, "controller") instanceof Codecs.CommandControl command){
            var stances = new ArrayList<Integer>();
            for(int stance : command.stances()){
                stances.add(byName(com.xpdustry.shard.wire.v160_7.ContentType.unitStance, stance, world.content().unitStances()));
            }
            set(state, "controller", new Codecs.CommandControl(command.type(), command.target(), command.attack(),
                command.command() < 0 ? command.command()
                    : byName(com.xpdustry.shard.wire.v160_7.ContentType.unitCommand, command.command(), world.content().unitCommands()),
                command.queue(), List.copyOf(stances)));
        }
        if(field(state, "payloads") instanceof List<?> payloads){
            for(Object payload : payloads) if(payload instanceof Codecs.UnitPayload unit) toStream(unit.unit());
        }
    }

    private int stream(ContentType type, int id){
        if(id < 0) return id;
        int stream = ids.toStream(type, id);
        if(stream < 0) throw new IllegalStateException(type + " " + id + " has no id in the stream's header");
        return stream;
    }

    /** A command or stance's id in the header: by name when it lists the type, else as it is. */
    private int byName(com.xpdustry.shard.wire.v160_7.ContentType type, int id, List<String> current){
        List<String> names = ids.header().names(type.ordinal());
        if(names.isEmpty() || id < 0 || id >= current.size()) return id;
        int index = names.indexOf(current.get(id));
        return index < 0 ? id : index;
    }

    private static Object field(Object state, String name){
        try{
            return state.getClass().getField(name).get(state);
        }catch(NoSuchFieldException e){
            return null;
        }catch(IllegalAccessException e){
            throw new IllegalStateException(e);
        }
    }

    private static void set(Object state, String name, Object value){
        try{
            state.getClass().getField(name).set(state, value);
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(state.getClass().getName() + "." + name, e);
        }
    }
}
