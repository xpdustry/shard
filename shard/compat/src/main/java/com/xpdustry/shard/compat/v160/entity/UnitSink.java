package com.xpdustry.shard.compat.v160.entity;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.ContentType;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.*;

import java.util.*;

/**
 * Reads a unit's save body ({@code write()}) into a new Shard {@link Unit}: vanilla's generated {@code read()} on a
 * fresh entity of the class, then {@code afterRead()}. The sink collects what the layout decodes, in any order, and
 * {@link #unit} builds the unit, which is not added. Vanilla's semantics, kept:
 * <ul>
 *   <li>Mount aims are dropped: the fresh entity has no mounts while they are read, and {@code afterRead}'s
 *   {@code setType} makes new ones from the type. Ability data is kept for every index the body and the type share
 *   ({@code readAbilities} makes placeholders holding it, whose data {@code setType} copies into the type's fresh
 *   abilities); the rest hold none. Every other ability state starts fresh.</li>
 *   <li>The controller is the type's, except a {@code CommandAI}, which keeps its orders ({@link WireControllers}).</li>
 *   <li>A mined tile or tethering building outside the map, and plans outside it, read as none.</li>
 *   <li>Carried units are read the same way, recursively.</li>
 * </ul>
 * Deviation: the unit is created after its body is read, so its entity id and its constructor's random draws come
 * after the carried units' rather than before. A carried building is made off the map and reads its base and body with
 * its kind's codec ({@code PayloadBodies}), as {@code Payload.read} does.
 */
public final class UnitSink implements UnitEntityLayout.Sink, MechUnitLayout.Sink, PayloadUnitLayout.Sink,
    BuildingTetherPayloadUnitLayout.Sink, TimedKillUnitLayout.Sink, TargetDummyUnitLayout.Sink{
    private final World world;
    private final WireContext context;
    private final ContentHeader.IdMapping ids;
    private float[] abilities = {};
    private float aimX, aimY, elevation, health, rotation, shield, x, y, baseRotation, lifetime, time;
    private Codecs.Controller controller = Codecs.OtherControl.GENERIC;
    private double flag;
    // a fresh entity's values, which fields an old revision lacks keep (updateBuilding: BuilderComp.java)
    private boolean isShooting, spawnedByCore, updateBuilding = true;
    private int mineTile = Codecs.NONE, team, building = Codecs.NONE, type = -1;
    private List<Codecs.Plan> plans = List.of();
    private List<Codecs.StatusEntry> statuses = List.of();
    private List<Codecs.Payload> payloads = List.of();
    private Codecs.ItemStack stack = new Codecs.ItemStack(0, 0);
    private Codecs.Vec2 vel = Codecs.Vec2.ZERO;
    private boolean lifetimeRead;

    private UnitSink(World world, WireContext context, ContentHeader.IdMapping ids){
        this.world = world;
        this.context = context;
        this.ids = ids;
    }

    /**
     * Vanilla's {@code read()} of class {@code classId}, from its revision on, into a new unit of {@code world}, then
     * {@code afterRead()}. {@code context} must describe {@code world}. Any revision vanilla reads is read
     * ({@code SaveVersion.java:495}); fields an old one lacks keep a fresh entity's values.
     */
    public static Unit readSave(World world, Reads read, int classId, WireContext context){
        return readSave(world, read, classId, context, null);
    }

    /**
     * {@link #readSave(World, Reads, int, WireContext)} of a body in another id space, an old save's: {@code ids} maps
     * the content ids of its header to {@code world}'s (vanilla's temporary mapper), and {@code context} answers in the
     * header's ids. The type, the stack's item, statuses, plans' blocks and a {@code CommandAI}'s command and stances are
     * moved, carried units' too; plan configs keep the header's ids.
     * @throws WireException for content {@code world} lacks, before the unit is created
     */
    public static Unit readSave(World world, Reads read, int classId, WireContext context, ContentHeader.IdMapping ids){
        EntityClasses.EntityClass type = EntityClasses.get(classId);
        if(type == null || !type.unit()) throw new WireException("entity class " + classId + " is not a unit class");
        int revision = read.s();
        var sink = new UnitSink(world, context, ids);
        switch(type.layout()){
            case "UnitEntity" -> UnitEntityLayout.readSave(read, sink, classId, revision, context);
            case "MechUnit" -> MechUnitLayout.readSave(read, sink, classId, revision, context);
            case "PayloadUnit" -> PayloadUnitLayout.readSave(read, sink, classId, revision, context);
            case "BuildingTetherPayloadUnit" -> BuildingTetherPayloadUnitLayout.readSave(read, sink, classId, revision, context);
            case "TimedKillUnit" -> TimedKillUnitLayout.readSave(read, sink, classId, revision, context);
            case "TargetDummyUnit" -> TargetDummyUnitLayout.readSave(read, sink, classId, revision, context);
            default -> throw new WireException(type.name() + " has no unit layout");
        }
        return sink.unit();
    }

    /** The unit read: created with its type and team, then given the rest. */
    Unit unit(){
        if(ids != null) remap();
        if(type < 0 || type >= world.content().units()) throw new WireException("unknown unit type " + type);
        Unit unit = Unit.create(world, world.content().unit(type), team);
        // readAbilities' placeholders hand their data to setType's fresh copies where both have the index; the type's
        // other abilities were never created(), so they hold none
        for(int i = 0; i < unit.abilities.length; i++) unit.abilities[i].data = i < abilities.length ? abilities[i] : 0f;
        unit.aimX = aimX;
        unit.aimY = aimY;
        unit.elevation = elevation;
        unit.flag = flag;
        unit.health = health;
        unit.isShooting = isShooting;
        unit.mineTile = mineTile != Codecs.NONE && context.tileExists(mineTile) ? mineTile : -1;
        var ids = WirePlans.ContentIds.of(world.content());
        for(Codecs.Plan plan : plans) if(plan != null) unit.plans.addLast(WirePlans.plan(plan, ids));
        unit.rotation = rotation;
        unit.shield = shield;
        unit.spawnedByCore = spawnedByCore;
        unit.stackItem = stack.item();
        unit.stackAmount = stack.amount();
        unit.statuses.clear();
        for(Codecs.StatusEntry entry : statuses){
            var status = new StatusEntry(entry.effect(), entry.time());
            status.damageMultiplier = entry.damageMultiplier();
            status.healthMultiplier = entry.healthMultiplier();
            status.speedMultiplier = entry.speedMultiplier();
            status.reloadMultiplier = entry.reloadMultiplier();
            status.buildSpeedMultiplier = entry.buildSpeedMultiplier();
            status.dragMultiplier = entry.dragMultiplier();
            status.armorOverride = entry.armorOverride();
            unit.statuses.add(status);
        }
        unit.updateBuilding = updateBuilding;
        unit.vel.set(vel.x(), vel.y());
        unit.x = x;
        unit.y = y;
        if(unit.shape instanceof MoveShape.Mech mech) mech.baseRotation = baseRotation;
        if(unit.payloads != null){
            for(Codecs.Payload payload : payloads) unit.payloads.add(payload(payload));
        }
        if(building != Codecs.NONE) unit.tether = WireControllers.building(world, building);
        if(lifetimeRead){
            unit.lifetime = lifetime;
            unit.time = time;
        }
        UnitController kept = WireControllers.afterRead(controller, unit.controller, world);
        if(kept != unit.controller) unit.controller(kept);
        return unit;
    }

    /** Moves the content ids read from the header's to the world's ({@link #readSave(World, Reads, int, WireContext, ContentHeader.IdMapping)}). */
    private void remap(){
        type = (short)current(ContentType.UNIT, type);
        stack = new Codecs.ItemStack(current(ContentType.ITEM, stack.item()), stack.amount());
        var moved = new ArrayList<Codecs.StatusEntry>();
        for(Codecs.StatusEntry e : statuses){
            moved.add(new Codecs.StatusEntry(current(ContentType.STATUS, e.effect()), e.time(), e.damageMultiplier(), e.healthMultiplier(),
                e.speedMultiplier(), e.reloadMultiplier(), e.buildSpeedMultiplier(), e.dragMultiplier(), e.armorOverride()));
        }
        statuses = moved;
        var plans = new ArrayList<Codecs.Plan>();
        for(Codecs.Plan p : this.plans){
            plans.add(p == null || p.breaking() ? p : new Codecs.Plan(false, p.pos(), current(ContentType.BLOCK, p.block()), p.rotation(), p.config()));
        }
        this.plans = plans;
        if(controller instanceof Codecs.CommandControl command){
            var stances = new ArrayList<Integer>();
            for(int stance : command.stances()) stances.add(byName(com.xpdustry.shard.wire.v160_7.ContentType.unitStance, stance, world.content().unitStances()));
            controller = new Codecs.CommandControl(command.type(), command.target(), command.attack(),
                command.command() < 0 ? command.command() : byName(com.xpdustry.shard.wire.v160_7.ContentType.unitCommand, command.command(), world.content().unitCommands()),
                command.queue(), List.copyOf(stances));
        }
    }

    private int current(ContentType type, int id){
        if(id < 0) return id;
        int current = ids.toCurrent(type, id);
        if(current < 0) throw new WireException(type + " " + id + " is not in the epoch");
        return current;
    }

    /** A command or stance id under the header's mapper: by name when the header lists the type, else as it is. */
    private int byName(com.xpdustry.shard.wire.v160_7.ContentType type, int id, List<String> current){
        List<String> names = ids.header().names(type.ordinal());
        if(names.isEmpty()) return id;
        return id >= 0 && id < names.size() ? current.indexOf(names.get(id)) : -1;
    }

    private Payload payload(Codecs.Payload payload){
        return switch(payload){
            case Codecs.UnitPayload(int classId, WireEntity state) -> {
                // the layout decoded the carried unit, at any revision, into its own holder: read it again through a sink
                var bytes = new Writes();
                EntityClasses.writeSave(bytes, classId, state, context);
                yield new Payload.UnitPayload(readSave(world, new Reads(bytes.toByteArray()), classId, context, ids));
            }
            case Codecs.BuildPayload build -> com.xpdustry.shard.compat.v160.blocks.payload.PayloadBodies.building(world, build.block(),
                build.version(), build.body(), ids != null ? ids : ContentHeader.of(world.content()).mapping(world.content()));
        };
    }

    @Override
    public void abilities(float[] value){
        abilities = value;
    }

    @Override
    public void aimX(float value){
        aimX = value;
    }

    @Override
    public void aimY(float value){
        aimY = value;
    }

    @Override
    public void controller(Codecs.Controller value){
        controller = value;
    }

    @Override
    public void elevation(float value){
        elevation = value;
    }

    @Override
    public void flag(double value){
        flag = value;
    }

    @Override
    public void health(float value){
        health = value;
    }

    @Override
    public void isShooting(boolean value){
        isShooting = value;
    }

    @Override
    public void mineTile(int value){
        mineTile = value;
    }

    @Override
    public void mounts(List<Codecs.Mount> value){
        // dropped: see the class comment
    }

    @Override
    public void plans(List<Codecs.Plan> value){
        plans = value == null ? List.of() : value;
    }

    @Override
    public void rotation(float value){
        rotation = value;
    }

    @Override
    public void shield(float value){
        shield = value;
    }

    @Override
    public void spawnedByCore(boolean value){
        spawnedByCore = value;
    }

    @Override
    public void stack(Codecs.ItemStack value){
        stack = value;
    }

    @Override
    public void statuses(List<Codecs.StatusEntry> value){
        statuses = value;
    }

    @Override
    public void team(int value){
        team = value;
    }

    @Override
    public void type(short value){
        type = value;
    }

    @Override
    public void updateBuilding(boolean value){
        updateBuilding = value;
    }

    @Override
    public void vel(Codecs.Vec2 value){
        vel = value;
    }

    @Override
    public void x(float value){
        x = value;
    }

    @Override
    public void y(float value){
        y = value;
    }

    @Override
    public void baseRotation(float value){
        baseRotation = value;
    }

    @Override
    public void payloads(List<Codecs.Payload> value){
        payloads = value;
    }

    @Override
    public void building(int value){
        building = value;
    }

    @Override
    public void lifetime(float value){
        lifetime = value;
        lifetimeRead = true;
    }

    @Override
    public void time(float value){
        time = value;
    }
}
