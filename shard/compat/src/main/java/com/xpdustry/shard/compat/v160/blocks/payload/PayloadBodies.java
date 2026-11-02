package com.xpdustry.shard.compat.v160.blocks.payload;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.ContentType;
import com.xpdustry.shard.sim.blocks.payload.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.*;

/**
 * Vanilla's {@code Payload.write} and {@code Payload.read} (Payload.java:84-118), for the payload blocks' codecs: a
 * presence flag, then a block payload (type 1, the block, the building's version, its {@code writeAll}) or a unit
 * payload (type 0, the unit's class, its {@code write()}). And {@code PayloadBlockBuild}'s body, which every
 * {@link PayloadBuild} starts with: {@code payVector}, {@code payRotation}, the payload.
 *
 * <p>A read builds what vanilla's does, with its draws: a block payload is {@code new BuildPayload(block,
 * Team.derelict)} ({@link Buildings#createPayload}: its id, field initializers and power graph updater), then the
 * building reads its base, team included, and its kind's body; a unit payload is read as a saved unit is
 * ({@link SavedUnits#payload}: its constructor's, {@code read()}'s and {@code afterRead()}'s draws). A carried
 * building whose kind has no codec has no known length, so it can't be read.
 */
public final class PayloadBodies{
    private static final BuildingCodecs CODECS = BuildingCodecs.builtIn();

    private PayloadBodies(){
    }

    /** {@code Payload.write(payload, write)}, content ids in {@code ids}' stream ids. */
    public static void write(World world, Payload payload, Writes out, ContentHeader.IdMapping ids){
        if(payload == null){
            out.bool(false);
            return;
        }
        out.bool(true);
        switch(payload){
            case Payload.BuildPayload build -> {
                out.b(Payload.BLOCK);
                out.s(ids.requireStream(ContentType.BLOCK, build.block().id()));
                // the version byte, then writeAll
                out.b(CODECS.chunk(build.building(), world.content(), ids));
            }
            case Payload.UnitPayload carried -> {
                Unit unit = carried.unit();
                var projector = new EntityProjector(world.content(), CODECS);
                int classId = projector.classOf(unit);
                out.b(Payload.UNIT);
                out.b(classId);
                EntityClasses.writeSave(out, classId, projector.unit(unit), WorldWireContext.capture(world));
            }
        }
    }

    /** {@code Payload.read(read)}: the payload, or null; content ids in {@code ids}' header ids. */
    public static Payload read(World world, Reads in, ContentHeader.IdMapping ids){
        if(!in.bool()) return null;
        int type = in.b();
        if(type == Payload.BLOCK){
            int id = ids.toCurrent(ContentType.BLOCK, in.s());
            if(id < 0) throw new WireException("a block payload of a block the epoch lacks");
            BlockDef block = world.content().block(id);
            Building building = world.buildings().createPayload(block, VanillaIds.DERELICT);
            int version = in.b();
            readAll(building, in, version, ids);
            return new Payload.BuildPayload(building);
        }
        if(type == Payload.UNIT){
            int classId = in.b();
            if(EntityClasses.get(classId) == null) throw new WireException("No type with ID " + classId + " found.");
            return new Payload.UnitPayload(SavedUnits.payload(world, in, classId, CODECS, ids));
        }
        throw new IllegalArgumentException("Unknown payload type: " + type);
    }

    /**
     * A block payload a unit carries, decoded by {@code :wire} into its block ({@code ids}' header id), version and
     * {@code writeAll} bytes: built and read as {@link #read} does one.
     */
    public static Payload.BuildPayload building(World world, int block, int version, byte[] body, ContentHeader.IdMapping ids){
        int id = ids.toCurrent(ContentType.BLOCK, block);
        if(id < 0) throw new WireException("a block payload of a block the epoch lacks");
        Building building = world.buildings().createPayload(world.content().block(id), VanillaIds.DERELICT);
        readAll(building, new Reads(body), version, ids);
        return new Payload.BuildPayload(building);
    }

    /** {@code build.readAll(read, version)} for a building off the map: its base, then its kind's body. */
    static void readAll(Building building, Reads in, int version, ContentHeader.IdMapping ids){
        World world = building.world();
        BuildingBase.Header header = BuildingBase.header(in);
        building.team = header.team();
        BuildingBase.read(building, header, in, ids, pos -> world.buildings().at(Point2.x(pos), Point2.y(pos)));
        KindKey kind = world.kinds().key(building.block());
        BuildingCodec codec = CODECS.get(kind);
        if(codec == null || !codec.reads(version)){
            throw new WireException("a block payload of " + building.block().name() + " (kind " + kind + ", revision " + version + "), whose length only a codec knows");
        }
        codec.read(building, in, version, ids);
    }

    /**
     * Reads past a payload on a {@link Tape}, for a payload block carried as a payload ({@link BuildingCodecs#readBody}):
     * measured in the tape's {@link Tape#context()}, which knows the carried block's or unit's layout. A tape without
     * one can only skip an empty payload.
     */
    public static void skip(Tape in){
        if(in.context() != null){
            in.measure(ahead -> in.nested(EntityClasses.readPayload(ahead, in.context())));
            return;
        }
        if(!in.bool()) return;
        throw new UnsupportedOperationException("a payload inside a carried payload block (type " + in.b() + ")");
    }

    /**
     * The payloads {@code building} holds, in the order its codec reads them: a payload conveyor's item, a payload
     * block's payload, then a deconstructor's {@code deconstructing}. Empty ones are left out.
     */
    public static java.util.List<Payload> held(Building building){
        var out = new java.util.ArrayList<Payload>(2);
        switch(building){
            case PayloadConveyorBuild conveyor -> {
                if(conveyor.item != null) out.add(conveyor.item);
            }
            case PayloadBuild<?> block -> {
                if(block.payload != null) out.add(block.payload);
                if(block instanceof PayloadDeconstructor d && d.deconstructing != null) out.add(d.deconstructing);
            }
            default -> {}
        }
        return out;
    }

    /** A saved content id ({@code content.unit(id)}, {@code content.block(id)}): -1 for none and for one the epoch lacks. */
    public static int content(ContentType type, int saved, ContentHeader.IdMapping ids){
        return saved < 0 ? -1 : ids.toCurrent(type, saved);
    }

    // PayloadBlockBuild (PayloadBlock.java:276-292)

    /** {@code PayloadBlockBuild.write}: the offset, the rotation, the payload. */
    public static void write(PayloadBuild<?> building, Writes out, ContentHeader.IdMapping ids){
        out.f(building.payVector.x);
        out.f(building.payVector.y);
        out.f(building.payRotation);
        write(building.world(), building.payload, out, ids);
    }

    /** {@code PayloadBlockBuild.read}. */
    @SuppressWarnings("unchecked")
    public static <T extends Payload> void read(PayloadBuild<T> building, Reads in, ContentHeader.IdMapping ids){
        building.payVector.set(in.f(), in.f());
        building.payRotation = in.f();
        building.payload = (T)read(building.world(), in, ids);
    }

    /** Reads past a {@code PayloadBlockBuild} body ({@link #skip(Tape)}). */
    public static void skipBlockBody(Tape in){
        in.f();
        in.f();
        in.f();
        skip(in);
    }
}
