package com.xpdustry.shard.compat.v160.msav;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.world.*;

import java.io.*;
import java.util.*;
import java.util.function.*;
import java.util.zip.*;

/**
 * Writes v13 msav files, as vanilla v160.7's {@code SaveIO.write} does: the {@code MSAV} header and version, then the
 * meta, patches, content, map, entities, markers and custom regions, zlib-deflated. The world part comes from the same
 * frozen projection as a world stream; only the meta differs.
 */
public final class MsavWriter{
    private static final KindKey CORE = KindKey.of("core-block");

    /** Vanilla's {@code Version.build}. */
    public static final int BUILD = 160;

    private MsavWriter(){}

    /** The file as vanilla writes it: deflated. */
    public static byte[] write(SequencedMap<String, String> meta, WorldStreamSource world){
        var bytes = new ByteArrayOutputStream();
        try(var out = new DeflaterOutputStream(bytes)){
            out.write(writeRaw(meta, world));
        }catch(IOException e){
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** The inflated file. */
    public static byte[] writeRaw(SequencedMap<String, String> meta, WorldStreamSource world){
        var out = new Writes(1 << 16);
        out.b(new byte[]{'M', 'S', 'A', 'V'});
        out.i(MsavReader.LATEST);
        region(out, r -> {
            r.s(meta.size());
            meta.forEach((key, value) -> {
                r.str(key);
                r.str(value);
            });
        });
        region(out, r -> r.b(world.patches()));
        region(out, r -> world.header().write(r));
        region(out, r -> world.map().write(r));
        region(out, r -> {
            r.s(world.entityMapping().size());
            for(MapState.EntityMappingEntry entry : world.entityMapping()){
                r.s(entry.id());
                r.str(entry.name());
            }
            r.i(world.plans().size());
            for(MapState.TeamPlans team : world.plans()){
                r.i(team.team());
                r.i(team.plans().size());
                for(MapState.TeamPlan plan : team.plans()){
                    r.s(plan.x());
                    r.s(plan.y());
                    r.s(plan.rotation());
                    r.s(plan.block());
                    r.b(plan.config());
                }
            }
            r.i(world.entities().size());
            for(MapState.EntityChunk entity : world.entities()){
                r.i(1 + 4 + entity.body().length);
                r.b(entity.classId());
                r.i(entity.id());
                r.b(entity.body());
            }
        });
        region(out, r -> r.b(world.markers()));
        region(out, r -> {
            r.i(world.customChunks().size());
            for(MapState.CustomChunk chunk : world.customChunks()){
                r.str(chunk.name());
                r.i(chunk.bytes().length);
                r.b(chunk.bytes());
            }
        });
        return out.toByteArray();
    }

    /**
     * Vanilla's {@code writeMeta} on a dedicated server: the map's tags, overwritten by the save's own keys.
     *
     * @param noCores {@code state.rules.defaultTeam.cores().isEmpty()} ({@link #noCores})
     * @param saved the save time in epoch milliseconds, which vanilla takes from the clock
     */
    public static SequencedMap<String, String> meta(WorldStreamSource world, SequencedMap<String, String> tags, int defaultTeam,
                                                    boolean noCores, long saved){
        return meta(world, tags, defaultTeam, noCores, saved, tags.getOrDefault("stats", "{}"));
    }

    /** {@link #meta}, with the game's {@code state.stats} JSON ({@code ObjectivesJson.writeStats}). */
    public static SequencedMap<String, String> meta(WorldStreamSource world, SequencedMap<String, String> tags, int defaultTeam,
                                                    boolean noCores, long saved, String stats){
        var meta = new LinkedHashMap<>(tags);
        meta.put("saved", Long.toString(saved));
        meta.put("playtime", "0");
        meta.put("build", Integer.toString(BUILD));
        meta.put("mapname", tags.getOrDefault("name", "unknown"));
        meta.put("wave", Integer.toString(world.wave()));
        meta.put("tick", Double.toString(world.tick()));
        meta.put("wavetime", Float.toString(world.wavetime()));
        meta.put("stats", stats);
        meta.put("rules", world.rules());
        meta.put("sectorPreset", "");
        meta.put("locales", world.locales());
        meta.put("mods", "[]");
        meta.put("controlGroups", "null");
        meta.put("width", Integer.toString(world.map().width()));
        meta.put("height", Integer.toString(world.map().height()));
        meta.put("viewpos", "(0.0,0.0)");
        meta.put("controlledType", "null");
        meta.put("nocores", Boolean.toString(noCores));
        meta.put("playerteam", Integer.toString(defaultTeam));
        meta.put("hasExternalAssets", "false");
        return meta;
    }

    /** Whether {@code team} has no core: no building of a core block is that team's ({@code TeamData.cores}). */
    public static boolean noCores(World world, int team){
        boolean[] none = {true};
        world.buildings().each(building -> {
            if(building.team == team && building.block().isA(CORE)) none[0] = false;
        });
        return none[0];
    }

    /** {@code writeRegion}: the region's length, then its bytes. */
    private static void region(Writes out, Consumer<Writes> body){
        var region = new Writes();
        body.accept(region);
        out.i(region.size());
        out.b(region.buffer(), 0, region.size());
    }
}
