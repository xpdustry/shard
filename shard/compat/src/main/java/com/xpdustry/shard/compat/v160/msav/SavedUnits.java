package com.xpdustry.shard.compat.v160.msav;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.compat.v160.blocks.payload.*;
import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.ContentType;
import com.xpdustry.shard.sim.ai.*;
import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.weather.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.*;

import java.lang.reflect.*;
import java.util.*;

/**
 * Brings a map's saved units to life, as vanilla's {@code readWorldEntities} does: each unit chunk becomes a Shard
 * {@link Unit} with the saved id, which the loader then adds. {@link UnitSink} reads the body, of any revision and in
 * the file's ids, which gives vanilla's {@code read()}/{@code afterRead()} semantics; it is decoded on its own first
 * for what the draws depend on (the saved controller, carried payloads).
 *
 * <p>Entity ids and draws are vanilla's, values included ({@code SaveVersion.java:495}): the constructor's entity id and
 * generator values ({@code resupplyTime}, a crawler's {@code crawlTime}), the saved id, then {@code read()}, which reads
 * the controller ({@code TypeIO.readController} makes an AI for every saved controller but a player's or a formation's),
 * then each carried unit ({@code Payload.read}: constructed, so the next ids, and read, its own {@code afterRead()}
 * included), then {@code afterRead()}: {@code setType} makes the type's controller if there is none yet, and the type's
 * is made again unless the controller keeps its state ({@code CommandAI}, {@code LogicAI}). {@link UnitSink} creates
 * the units in that order but makes each type's controller once, so the generator is set back and {@link #replay}
 * draws vanilla's sequence again. A unit that stays bytes has its draws counted by {@link ReadDraws}: one carrying a
 * building does; one of a layout or type the epoch lacks is also {@link #unknown()}.
 */
public final class SavedUnits{
    private final World world;
    private final ContentHeader.IdMapping ids;
    private final WireContext source;
    private final BuildingCodecs codecs;
    private String unknown;
    /** The saved class of each carried unit that came to life, in the file's class ids, by entity id. */
    private final SortedMap<Integer, Integer> carriedClasses = new TreeMap<>();

    SavedUnits(World world, BuildingCodecs codecs, ContentHeader.IdMapping ids){
        this.world = world;
        this.ids = ids;
        this.source = new FileWireContext(world, codecs, ids);
        this.codecs = codecs;
    }

    /** The class each carried unit that came to life was saved with, in the file's class ids, by entity id. */
    SortedMap<Integer, Integer> carriedClasses(){
        return carriedClasses;
    }

    /** The first unit whose draws differ from vanilla's, or null. */
    String unknown(){
        return unknown;
    }

    /**
     * The weather state saved in {@code body}, as {@code readWorldEntities} brings it to life: {@code WeatherState.create}
     * (from an empty pool: a fresh id, then the wind's draw), then the saved fields, added; or null when the world has no
     * weather or the epoch lacks the weather, and then nothing was drawn. The caller gives it its saved id
     * ({@link #renumber}).
     */
    WeatherState weather(byte[] body){
        if(Weathers.of(world) == null) return null;
        WeatherStateLayout.State saved;
        try{
            saved = (WeatherStateLayout.State)EntityClasses.readSave(new Reads(body), EntityClasses.WEATHER_STATE, source);
        }catch(WireException | IndexOutOfBoundsException e){
            note("WeatherState stays bytes: " + e.getMessage());
            return null;
        }
        int weather = saved.weather < 0 ? -1 : ids.toCurrent(ContentType.WEATHER, saved.weather);
        if(weather < 0 || !ids.known(ContentType.WEATHER, saved.weather)){
            note("a WeatherState of weather " + saved.weather + ", which the epoch lacks, stays bytes");
            return null;
        }
        WeatherState state = WeatherState.create(world, weather, saved.intensity, saved.life);
        // read(): the saved fields over what the constructor and create set
        state.intensity = saved.intensity;
        state.life = saved.life;
        state.opacity = saved.opacity;
        state.effectTimer = saved.effectTimer;
        state.windVector.set(saved.windVector.x(), saved.windVector.y());
        state.x = saved.x;
        state.y = saved.y;
        return state;
    }

    /** Gives an added {@code weather}, the last synced entity, its saved {@code id}, keeping the sync group's index. */
    void renumber(WeatherState weather, int id){
        EntityGroups groups = EntityGroups.of(world);
        if(groups != null) groups.removeSync(weather);
        weather.id(id);
        if(groups != null) groups.addSync(weather);
    }

    /** Whether {@code classId} is a unit class this reader turns into units. */
    static boolean unit(int classId){
        EntityClasses.EntityClass type = EntityClasses.get(classId);
        return type != null && type.unit() && type.revision() >= 0;
    }

    /**
     * The unit saved in {@code body} (its revision first) with {@code id} (-1: the file saves none), created with a
     * fresh entity id and not added, which the caller gives it after {@code checkNextId}; or null when it can't be
     * read, and then nothing was drawn.
     */
    Unit read(int classId, int id, byte[] body){
        EntityClasses.EntityClass type = EntityClasses.get(classId);
        WireEntity state;
        try{
            state = decode(new Reads(body), classId);
            known(state);
        }catch(WireException | IndexOutOfBoundsException | UnsupportedOperationException e){
            note(type.name() + " stays bytes: " + e.getMessage());
            return null;
        }
        // a carried building Shard can't build stays bytes with its carrier, whose draws ReadDraws counts
        if(carriesUnbuildable(state)) return null;
        Rand rand = world.rand();
        long seed0 = rand.getState(0), seed1 = rand.getState(1);
        // the carried units' ids come after checkNextId(id), which follows the carrier's own: one below it now keeps
        // the counter where vanilla's is after both, and the caller sets the saved id
        if(id >= 0) world.ids().checkNextId(id - 1);
        Unit unit;
        try{
            unit = UnitSink.readSave(world, new Reads(body), classId, source, ids);
        }catch(WireException | UnsupportedOperationException e){
            rand.setState(seed0, seed1);
            note(type.name() + " stays bytes: " + e.getMessage());
            return null;
        }
        rand.setState(seed0, seed1);
        // ids from the one after the carrier's on, which the carried units drew while created
        int[] next = {unit.id() + 1};
        replay(unit, state, next);
        world.ids().checkNextId(next[0] - 1);
        return unit;
    }

    /**
     * A unit {@code Payload.read} reads inside a building's body (a payload conveyor's), from {@code in}: constructed
     * with the next entity id, then read with its {@code afterRead()}, drawing what vanilla's do, as {@link #read} does
     * for a saved unit. Content ids are in {@code ids}' header ids.
     * @throws WireException for a body it can't read, before anything is drawn
     */
    public static Unit payload(World world, Reads in, int classId, BuildingCodecs codecs, ContentHeader.IdMapping ids){
        var units = new SavedUnits(world, codecs, ids);
        WireEntity state = units.decode(in.fork(), classId);
        units.known(state);
        Rand rand = world.rand();
        long seed0 = rand.getState(0), seed1 = rand.getState(1);
        Unit unit = UnitSink.readSave(world, in, classId, units.source, ids);
        rand.setState(seed0, seed1);
        int[] next = {unit.id() + 1};
        units.replay(unit, state, next);
        world.ids().checkNextId(next[0] - 1);
        return unit;
    }

    /**
     * Draws again what vanilla's constructor, {@code read()} and {@code afterRead()} of {@code unit} draw, read from
     * {@code state}, its carried units' in between; sets the values drawn, and the carried units' entity ids, counting
     * from {@code next[0]}.
     */
    private void replay(Unit unit, WireEntity state, int[] next){
        Rand rand = world.rand();
        if(unit.shape instanceof MoveShape.Crawl crawl) crawl.crawlTime = rand.random(100f);
        unit.resupplyTime = rand.random(10f);
        var saved = (Codecs.Controller)field(state, "controller");
        readController(unit, saved);
        // LogicAI's posTarget is an entity, PosTeam.create(), which takes an id (LogicAI.java:39)
        if(saved instanceof Codecs.LogicControl){
            if(unit.controller instanceof LogicAi ai) ai.posId = next[0];
            next[0]++;
        }
        if(hasField(state, "payloads") && field(state, "payloads") instanceof List<?> payloads){
            for(int i = 0; i < payloads.size(); i++){
                if(payloads.get(i) instanceof Codecs.UnitPayload carried && unit.payloads.get(i) instanceof Payload.UnitPayload(Unit passenger)){
                    passenger.id(next[0]++);
                    carriedClasses.put(passenger.id(), carried.classId());
                    replay(passenger, carried.unit(), next);
                }else if(payloads.get(i) instanceof Codecs.BuildPayload carried && unit.payloads.get(i) instanceof Payload.BuildPayload(var building)){
                    replay(building, carried, next);
                }
            }
        }
        afterRead(unit, saved);
    }

    /**
     * {@code Payload.read} of a block payload, again: {@code new BuildPayload(block, Team.derelict)} (the building's id,
     * its field initializers, its graph's updater), then its body's own payloads in read order (a payload conveyor
     * carrying a unit, a deconstructor taking a building apart), each as vanilla reads it.
     */
    private void replay(Building building, Codecs.BuildPayload saved, int[] next){
        building.id(next[0]++);
        if(world.buildings().info(building.block()).drawsField) world.rand().nextLong();
        if(building.power != null) building.power.graph.id(next[0]++);
        List<Payload> held = PayloadBodies.held(building);
        List<Codecs.Payload> nested = nested(saved);
        if(held.size() != nested.size()){
            throw new IllegalStateException(building.block().name() + " holds " + held.size() + " payloads, its bytes " + nested.size());
        }
        for(int i = 0; i < held.size(); i++){
            Payload ours = held.get(i);
            if(nested.get(i) instanceof Codecs.UnitPayload carried && ours instanceof Payload.UnitPayload(Unit passenger)){
                passenger.id(next[0]++);
                carriedClasses.put(passenger.id(), carried.classId());
                replay(passenger, carried.unit(), next);
            }else if(nested.get(i) instanceof Codecs.BuildPayload carried && ours instanceof Payload.BuildPayload(var inner)){
                replay(inner, carried, next);
            }else{
                throw new IllegalStateException(building.block().name() + "'s payload " + i + " isn't what its bytes hold");
            }
        }
    }

    /** The payloads a carried building's bytes hold, decoded, in read order; empty ones left out. */
    private List<Codecs.Payload> nested(Codecs.BuildPayload saved){
        var out = new ArrayList<Codecs.Payload>();
        BlockDef block = world.content().block(Math.max(ids.toCurrent(ContentType.BLOCK, saved.block()), 0));
        codecs.readBody(new Reads(saved.body()), block, world.kinds().key(block), saved.version(), source, value -> {
            if(value instanceof Codecs.Payload payload) out.add(payload);
        });
        return out;
    }

    /**
     * {@code readController}'s draws for {@code saved} (null: the revision saves none), on top of the controller
     * {@link UnitSink} left: a {@code CommandAI} with the saved orders, or the type's.
     */
    private void readController(Unit unit, Codecs.Controller saved){
        switch(saved){
            // readController's CommandAI keeps its state: its timers are the first drawn
            case Codecs.CommandControl command -> {
                var read = new CommandAi(world);
                if(unit.controller instanceof CommandAi ai) System.arraycopy(read.timers, 0, ai.timers, 0, ai.timers.length);
            }
            // readController's LogicAI keeps its state: its timers are the first drawn
            case Codecs.LogicControl logic -> {
                var read = new AiController(world, LogicAi.KIND);
                if(unit.controller instanceof LogicAi ai) System.arraycopy(read.timers, 0, ai.timers, 0, ai.timers.length);
            }
            // no player is found while a map loads
            case null -> {}
            case Codecs.PlayerControl _, Codecs.FormationControl _ -> {}
            // GroundAI (or AssemblerAI)
            default -> new AiController(world, AiController.GROUND);
        }
    }

    /** {@code afterRead}'s draws: those of the type's controller, made where the controller read is replaced. */
    private static void afterRead(Unit unit, Codecs.Controller saved){
        switch(saved){
            case Codecs.CommandControl _, Codecs.LogicControl _ -> {}
            // setType makes the type's controller, which afterRead replaces unless it keeps its state
            case null -> setType(unit);
            case Codecs.PlayerControl _, Codecs.FormationControl _ -> setType(unit);
            default -> unit.resetController();
        }
    }

    private static void setType(Unit unit){
        unit.resetController();
        if(!(unit.controller instanceof CommandAi)) unit.resetController();
    }

    private void note(String what){
        if(unknown == null) unknown = what;
    }

    /**
     * {@code read()} of {@code classId} from its revision on, in the file's ids, into its layout's state, whose controller
     * stays null when the revision saves none (old revisions keep a field's value when they lack it).
     */
    private WireEntity decode(Reads read, int classId){
        int revision = read.s();
        String layout = EntityClasses.get(classId).layout();
        try{
            Class<?> type = Class.forName("com.xpdustry.shard.wire.v160_7." + layout + "Layout");
            Object state = Class.forName(type.getName() + "$State").getConstructor().newInstance();
            set(state, "controller", null);
            Class<?> sink = Class.forName(type.getName() + "$Sink");
            type.getMethod("readSave", Reads.class, sink, int.class, int.class, WireContext.class).invoke(null, read, state, classId, revision, source);
            return (WireEntity)state;
        }catch(InvocationTargetException e){
            if(e.getCause() instanceof RuntimeException cause) throw cause;
            throw new IllegalStateException(e.getCause());
        }catch(ClassNotFoundException e){
            throw new WireException(layout + " has no save layout");
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(e);
        }
    }

    // content ids

    /**
     * Throws for a type, item or status in {@code state} or its carried units that the epoch lacks, as
     * {@link UnitSink} would after creating the unit: checked first so that nothing is drawn.
     */
    private void known(WireEntity state){
        current(ContentType.UNIT, (Short)field(state, "type"));
        if(field(state, "stack") instanceof Codecs.ItemStack stack) current(ContentType.ITEM, stack.item());
        if(field(state, "statuses") instanceof List<?> statuses){
            for(Object entry : statuses) current(ContentType.STATUS, ((Codecs.StatusEntry)entry).effect());
        }
        if(field(state, "plans") instanceof List<?> plans){
            for(Object entry : plans) if(entry instanceof Codecs.Plan p && !p.breaking()) current(ContentType.BLOCK, p.block());
        }
        if(hasField(state, "payloads") && field(state, "payloads") instanceof List<?> payloads){
            for(Object payload : payloads) if(payload instanceof Codecs.UnitPayload unit) known(unit.unit());
        }
    }

    private void current(ContentType type, int id){
        if(id >= 0 && ids.toCurrent(type, id) < 0) throw new WireException(type + " " + id + " is not in the epoch");
    }

    /**
     * Whether a carried building can't be built here: its kind has no codec, or it makes a block unit (whose id the
     * replay doesn't count); or one of the payloads it holds itself can't.
     */
    private boolean carriesUnbuildable(WireEntity state){
        if(!hasField(state, "payloads") || !(field(state, "payloads") instanceof List<?> payloads)) return false;
        for(Object payload : payloads) if(unbuildable(payload)) return true;
        return false;
    }

    private boolean unbuildable(Object payload){
        if(payload instanceof Codecs.BuildPayload build){
            int id = ids.toCurrent(ContentType.BLOCK, build.block());
            if(id < 0) return true;
            var block = world.content().block(id);
            var kind = world.kinds().key(block);
            if(codecs.get(kind) == null || com.xpdustry.shard.sim.building.Building.ownsBlockUnit(block)) return true;
            for(Codecs.Payload inner : nested(build)) if(unbuildable(inner)) return true;
        }
        return payload instanceof Codecs.UnitPayload unit && carriesUnbuildable(unit.unit());
    }

    private static boolean hasField(Object state, String name){
        try{
            state.getClass().getField(name);
            return true;
        }catch(NoSuchFieldException e){
            return false;
        }
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
            Field field = state.getClass().getField(name);
            field.set(state, value);
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException(state.getClass().getName() + "." + name, e);
        }
    }
}
