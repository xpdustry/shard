package com.xpdustry.shard.compat.v160.world;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.content.patch.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Vanilla's {@code NetworkIO.writeWorld}, section by section: data patches, rules, map locales, map tags, game state
 * (wave, countdown, tick, logic rand), the player, the content header, the map, the entity mapping, team plans,
 * world entities, markers and custom chunks.
 *
 * <p>The data patch section holds the world's content patch from {@linkplain Patchgen patchgen}, which is empty for
 * vanilla content: clients apply it before they read the rest.
 */
public final class WorldStreamWriter{
    /** Vanilla's {@code DataPatcher.patchFormatVersion}. */
    public static final int PATCH_FORMAT_VERSION = PatchSection.formatVersion;
    /** {@code MapMarkers} with no markers: an empty UBJSON object. */
    public static final byte[] NO_MARKERS = {'{', '}'};

    private WorldStreamWriter(){}

    /** The stream's raw bytes, as {@code writeWorld} writes them before compression. */
    public static byte[] write(WorldStreamSource source){
        var out = new Writes(1 << 16);
        write(source, out);
        return out.toByteArray();
    }

    /** The stream as {@code NetServer.sendWorldData} sends it: zlib-deflated. */
    public static byte[] deflate(byte[] raw){
        var bytes = new ByteArrayOutputStream(raw.length / 4 + 64);
        try(var deflater = new DeflaterOutputStream(bytes)){
            deflater.write(raw);
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /**
     * The data patch section of a world of {@code epoch} ({@code writeDataPatches}): {@link Patchgen}'s patch from
     * {@link Epoch#vanilla()}, what v160.7 clients have, to {@code epoch}. The msav patch region holds the same bytes.
     */
    public static byte[] patches(Epoch epoch){
        var bytes = new ByteArrayOutputStream();
        try(var out = new DataOutputStream(bytes)){
            PatchSection.write(out, List.of(Patchgen.of(Epoch.vanilla(), epoch)));
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    public static void write(WorldStreamSource source, Writes out){
        out.b(source.patches());

        out.str(source.rules());
        out.str(source.locales());
        out.s(source.tags().size());
        source.tags().forEach((key, value) -> {
            out.str(key);
            out.str(value);
        });

        out.i(source.wave());
        out.f(source.wavetime());
        out.d(source.tick());
        out.l(source.seed0());
        out.l(source.seed1());

        source.player().write(out);

        source.header().write(out);
        source.map().write(out);
        writeEntityMapping(source, out);
        writeTeamPlans(source, out);
        writeEntities(source, out);
        out.b(source.markers());
        writeCustomChunks(source, out);
    }

    static void writeEntityMapping(WorldStreamSource source, Writes out){
        out.s(source.entityMapping().size());
        for(MapState.EntityMappingEntry entry : source.entityMapping()){
            out.s(entry.id());
            out.str(entry.name());
        }
    }

    /** {@code writeTeamBlocks}: teams, each with its plans; configs are raw {@code TypeIO} objects. */
    static void writeTeamPlans(WorldStreamSource source, Writes out){
        out.i(source.plans().size());
        for(MapState.TeamPlans team : source.plans()){
            out.i(team.team());
            out.i(team.plans().size());
            for(MapState.TeamPlan plan : team.plans()){
                out.s(plan.x());
                out.s(plan.y());
                out.s(plan.rotation());
                out.s(plan.block());
                out.b(plan.config());
            }
        }
    }

    /** {@code writeWorldEntities}: a count, then one int-length chunk per entity: class id, entity id, {@code write}. */
    static void writeEntities(WorldStreamSource source, Writes out){
        out.i(source.entities().size());
        for(MapState.EntityChunk entity : source.entities()){
            out.i(1 + 4 + entity.body().length);
            out.b(entity.classId());
            out.i(entity.id());
            out.b(entity.body());
        }
    }

    /** {@code writeCustomChunks(stream, true)}: chunks a client should get. */
    static void writeCustomChunks(WorldStreamSource source, Writes out){
        out.i(source.customChunks().size());
        for(MapState.CustomChunk chunk : source.customChunks()){
            out.str(chunk.name());
            out.i(chunk.bytes().length);
            out.b(chunk.bytes());
        }
    }
}
