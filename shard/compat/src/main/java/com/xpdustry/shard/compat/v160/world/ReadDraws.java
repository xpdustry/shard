package com.xpdustry.shard.compat.v160.world;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.ContentType;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.*;

import java.util.*;

/**
 * What vanilla draws while it reads bytes Shard keeps as they are: the units and buildings a payload's read
 * constructs, in a passthrough payload block or conveyor and in a unit of the entities region. Vanilla constructs them
 * as it reads, so the loader calls this right after each building's or entity's read, keeping vanilla's order.
 * Measured per block and per unit class against vanilla ({@code GenerationFiltersOracleTest},
 * {@code BuildingBaseOracleTest}):
 * <ul>
 *   <li>a unit: its entity id, {@code resupplyTime} (UnitComp.java:66) and a crawler's {@code crawlTime}, two values for
 *       each AI controller its read makes (AIController.java:40): the one {@code TypeIO.readController} makes, then
 *       the one {@code UnitComp.afterRead} makes unless the controller keeps its state; then what its payloads draw;</li>
 *   <li>a building: {@link Building#drawConstruction}, then a throwaway power module's graph updater id when its base
 *       lists power its block lacks (BuildingComp.java:248). Its body's read draws nothing more unless it holds
 *       payloads itself.</li>
 * </ul>
 * Payloads in the entities region draw entity ids only ({@link #entity}), but those of a saved unit that stays bytes
 * ({@link #savedUnit}), which draw their values too. What it can't tell (a building inside a payload unit, a block payload of a
 * payload kind, a unit controlled by a player or a formation, a unit layout {@code :wire} doesn't decode) draws
 * nothing and is kept as {@link #unknown()}: ids and the generator drift from vanilla's after it.
 */
public final class ReadDraws{
    private static final KindKey PAYLOAD_BLOCK = KindKey.of("payload-block");
    private static final KindKey PAYLOAD_CONVEYOR = KindKey.of("payload-conveyor");
    /** Where {@code Payload.write} starts in a payload block's body: after its vector and rotation floats. */
    private static final int PAYLOAD_BLOCK_OFFSET = 12;
    /** ... and in a payload conveyor's: after its progress and rotation floats. */
    private static final int PAYLOAD_CONVEYOR_OFFSET = 8;
    /** The {@code AIController} constructor's draws. */
    private static final int CONTROLLER = 2;

    private final World world;
    private final ContentHeader.IdMapping ids;
    private final LoadContext context;
    private String unknown;

    /** Draws into {@code world}, for bytes whose content ids are {@code ids}' header's. */
    public ReadDraws(World world, BuildingCodecs codecs, ContentHeader.IdMapping ids){
        this.world = world;
        this.ids = ids;
        this.context = new LoadContext(world, codecs, ids);
    }

    /** The first building or entity whose draws are not known, or null. */
    public String unknown(){
        return unknown;
    }

    /** After a passthrough building's {@code body} (its chunk after the base) is read: its payload's draws. */
    public void building(Building building, byte[] body){
        BlockDef block = building.block();
        if(!block.baked()) return;
        int offset = block.isA(PAYLOAD_BLOCK) ? PAYLOAD_BLOCK_OFFSET : block.isA(PAYLOAD_CONVEYOR) ? PAYLOAD_CONVEYOR_OFFSET : -1;
        if(offset < 0) return;
        var steps = new ArrayList<Step>();
        try{
            var read = new Reads(body);
            read.skip(offset);
            topPayload(read, steps);
        }catch(WireException | Unknown | IndexOutOfBoundsException e){
            note(block.name() + " at (" + building.tileX() + ", " + building.tileY() + "): " + e.getMessage());
            return;
        }
        apply(steps, true);
    }

    /**
     * After an entity chunk of class {@code classId} is read: what the payloads of a payload unit draw. The entity's own
     * construction is the loader's.
     */
    public void entity(int classId, byte[] body){
        EntityClasses.EntityClass type = EntityClasses.get(classId);
        if(type == null || !(type.layout().equals("PayloadUnit") || type.layout().equals("BuildingTetherPayloadUnit"))) return;
        var steps = new ArrayList<Step>();
        try{
            List<Codecs.Payload> payloads = switch(EntityClasses.readSave(new Reads(body), classId, context)){
                case PayloadUnitLayout.Source u -> u.payloads();
                case BuildingTetherPayloadUnitLayout.Source u -> u.payloads();
                default -> List.of();
            };
            for(Codecs.Payload payload : payloads) payload(payload, steps);
        }catch(WireException | Unknown | IndexOutOfBoundsException e){
            note("entity " + type.name() + ": " + e.getMessage());
            return;
        }
        apply(steps, false);
    }

    /**
     * After a saved unit's chunk of class {@code classId} that stays bytes is read (one carrying a building): what its
     * constructor, read and payloads draw, generator values included, as {@code SavedUnits} draws them for a unit that
     * comes to life. Its entity id is the loader's.
     */
    public void savedUnit(int classId, byte[] body){
        EntityClasses.EntityClass type = EntityClasses.get(classId);
        var steps = new ArrayList<Step>();
        try{
            unit(classId, EntityClasses.readSave(new Reads(body), classId, context), steps);
        }catch(WireException | Unknown | IndexOutOfBoundsException e){
            note("entity " + type.name() + ": " + e.getMessage());
            return;
        }
        // the constructor's entity id was drawn with the chunk
        steps.set(0, new Draws(0, construction(type)));
        apply(steps, true);
    }

    private void note(String what){
        if(unknown == null) unknown = what;
    }

    /** A draw: a building constructed, or entity ids and generator values. */
    private sealed interface Step{
    }

    private record Construct(BlockDef block) implements Step{
    }

    private record Draws(int ids, int values) implements Step{
    }

    private void apply(List<Step> steps, boolean values){
        for(Step step : steps){
            switch(step){
                case Construct c -> Building.drawConstruction(world, c.block(), values);
                case Draws d -> {
                    for(int i = 0; i < d.ids(); i++) world.ids().next();
                    if(values) for(int i = 0; i < d.values(); i++) world.rand().nextLong();
                }
            }
        }
    }

    /** {@code Payload.read} at the top of a body, where a block payload's body may run to the end unread. */
    private void topPayload(Reads read, List<Step> steps){
        if(!read.bool()) return;
        int type = read.b();
        if(type == 1){
            block(ids.toCurrent(ContentType.BLOCK, read.s()), read, steps);
        }else if(type == 0){
            topUnit(read.b(), read.b(read.remaining()), steps);
        }else{
            throw new Unknown("payload type " + type);
        }
    }

    /** {@code new BuildPayload(block, team)}, then its base's read from {@code read}, after the version byte. */
    private void block(int id, Reads read, List<Step> steps){
        BlockDef block = world.content().block(Math.max(id, 0));
        if(block.baked() && (block.isA(PAYLOAD_BLOCK) || block.isA(PAYLOAD_CONVEYOR))){
            throw new Unknown("a block payload of " + block.name() + ", whose read reads payloads");
        }
        steps.add(new Construct(block));
        read.b();
        // BuildingComp.readBase: a versioned base (bit 7 of the rotation) lists its modules from version 2
        read.f();
        int rotation = read.b();
        read.b();
        if((rotation & 0x80) != 0){
            int version = read.b();
            if(version >= 1) read.b();
            if(version >= 2 && (read.ub() & 2) != 0 && !block.hasPower()) steps.add(new Draws(1, 0));
        }
    }

    private void payload(Codecs.Payload payload, List<Step> steps){
        switch(payload){
            case null -> {
            }
            case Codecs.BuildPayload build -> {
                var body = new byte[build.body().length + 1];
                body[0] = (byte)build.version();
                System.arraycopy(build.body(), 0, body, 1, build.body().length);
                block(ids.toCurrent(ContentType.BLOCK, build.block()), new Reads(body), steps);
            }
            case Codecs.UnitPayload unit -> unit(unit.classId(), unit.unit(), steps);
        }
    }

    /**
     * A unit payload at the top of a body, {@code rest} being the unit's save from its revision on. {@code :wire} reads
     * only a class's current revision. The two before it, in every class of the unit and mech layouts, start with the
     * abilities, then {@code ammo}, which the current one dropped, then a mech's {@code baseRotation}, then the controller
     * (v160.7's generated {@code read}); nothing after the unit is needed, so that head is enough.
     */
    private void topUnit(int classId, byte[] rest, List<Step> steps){
        EntityClasses.EntityClass type = EntityClasses.get(classId);
        if(type == null || !type.unit()) throw new Unknown("unit payload of class " + classId + ", which is not a unit");
        int revision = rest.length < 2 ? -1 : (short)((rest[0] & 0xff) << 8 | rest[1] & 0xff);
        int behind = type.revision() - revision;
        boolean mech = type.layout().equals("MechUnit");
        if(revision >= 1 && (behind == 1 || behind == 2) && (mech || type.layout().equals("UnitEntity"))){
            var read = new Reads(rest, 2, rest.length - 2);
            Codecs.readAbilities(read);
            read.f();
            // a mech's baseRotation comes before its controller
            if(mech) read.f();
            steps.add(new Draws(1, construction(type)));
            controller(Codecs.readController(read), steps);
            return;
        }
        unit(classId, EntityClasses.readSave(new Reads(rest), classId, context), steps);
    }

    /** The generator values constructing a unit of {@code type} draws: {@code resupplyTime}, and a crawler's {@code crawlTime}. */
    private static int construction(EntityClasses.EntityClass type){
        return type.name().equals("CrawlUnit") ? 2 : 1;
    }

    private void unit(int classId, WireEntity unit, List<Step> steps){
        EntityClasses.EntityClass type = EntityClasses.get(classId);
        steps.add(new Draws(1, construction(type)));
        Codecs.Controller controller = switch(unit){
            case UnitEntityLayout.Source u -> u.controller();
            case MechUnitLayout.Source u -> u.controller();
            case PayloadUnitLayout.Source u -> {
                for(Codecs.Payload carried : u.payloads()) payload(carried, steps);
                yield u.controller();
            }
            case BuildingTetherPayloadUnitLayout.Source u -> {
                for(Codecs.Payload carried : u.payloads()) payload(carried, steps);
                yield u.controller();
            }
            case TimedKillUnitLayout.Source u -> u.controller();
            case TargetDummyUnitLayout.Source u -> u.controller();
            default -> throw new Unknown(type.name() + " has no unit layout");
        };
        controller(controller, steps);
    }

    /** The AI controllers a unit's read makes for {@code controller}, two values each. */
    private static void controller(Codecs.Controller controller, List<Step> steps){
        int controllers = switch(controller){
            // CommandAI and LogicAI keep their state through afterRead
            case Codecs.CommandControl c -> 1;
            case Codecs.LogicControl c -> 1;
            case Codecs.AssemblerControl c -> 2;
            case Codecs.OtherControl c -> 2;
            // no player exists while a map loads, so setType makes the type's controller, which afterRead may replace
            case Codecs.PlayerControl c -> throw new Unknown("a unit controlled by a player");
            case Codecs.FormationControl c -> throw new Unknown("a unit controlled by a formation");
        };
        steps.add(new Draws(0, controllers * CONTROLLER));
    }

    private static final class Unknown extends RuntimeException{
        Unknown(String message){
            super(message, null, false, false);
        }
    }

    /**
     * The facts {@code :wire}'s save layouts need about the loading world's content, for ids of {@code ids}' header.
     * A building body inside a unit's payload is read by its kind's codec, or is unknown.
     */
    private record LoadContext(World world, BuildingCodecs codecs, ContentHeader.IdMapping ids) implements WireContext{
        @Override
        public boolean statusDynamic(int status){
            int id = ids.toCurrent(ContentType.STATUS, status);
            return id >= 0 && id < world.content().count(ContentType.STATUS) && world.content().status(id).dynamic();
        }

        @Override
        public boolean contentExists(int type, int id){
            //under the header's mapper, vanilla's getByID gives null only for a negative id; an unknown one is id 0
            for(ContentType t : ContentType.values()){
                if(t.vanillaOrdinal() == type) return ids.toCurrent(t, id) >= 0;
            }
            return true;
        }

        @Override
        public boolean blockRotates(int block){
            return blockExists(block) && world.content().block(ids.toCurrent(ContentType.BLOCK, block)).fields().b("rotate");
        }

        @Override
        public boolean blockExists(int block){
            int id = ids.toCurrent(ContentType.BLOCK, block);
            return id >= 0 && id < world.content().blocks();
        }

        @Override
        public boolean unlockable(int type, int id){
            // the unlockable types Shard defines (UnlockableContent); a bullet or weather is not one
            for(ContentType t : List.of(ContentType.ITEM, ContentType.BLOCK, ContentType.LIQUID, ContentType.STATUS, ContentType.UNIT)){
                if(t.vanillaOrdinal() == type) return id >= 0 && id < world.content().count(t);
            }
            return false;
        }

        @Override
        public boolean tileExists(int pos){
            int x = pos >>> 16, y = pos & 0xFFFF;
            return x < world.tiles().width() && y < world.tiles().height();
        }

        @Override
        public int syncedClass(int id){
            return -1;
        }

        @Override
        public byte[] readBuildingBody(Reads read, int block, int version){
            BlockDef def = world.content().block(Math.max(ids.toCurrent(ContentType.BLOCK, block), 0));
            KindKey kind = world.kinds().key(def);
            if(codecs.get(kind) == null) throw new Unknown("a block payload of " + def.name() + " inside a unit, whose length only a codec knows");
            return codecs.readBody(read, def, kind, version, this);
        }
    }
}
