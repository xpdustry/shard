package com.xpdustry.shard.compat.v160.msav;

import com.xpdustry.shard.compat.v160.blocks.environment.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.compat.v160.world.MapState.*;

import java.util.*;

/**
 * An msav file as read (vanilla {@code SaveIO}/{@code SaveVersion}), nothing decoded that Shard would have to
 * understand: content ids stay in {@link #header}'s id space and building, entity and plan bytes stay opaque.
 *
 * @param patches the raw data patch region (v11 and later), or null
 * @param entities saved entities; ids are -1 where the version stored none (v4, v5)
 * @param legacyEntities entities in the v1–3 format, which vanilla skips
 * @param markers the raw markers region (v8 and later), or null
 */
public record SaveFile(int version, SequencedMap<String, String> meta, byte[] patches, ContentHeader header, SaveMap map,
                       List<EntityMappingEntry> entityMapping, List<TeamPlans> plans, List<EntityChunk> entities,
                       int legacyEntities, byte[] markers, List<CustomChunk> customChunks){

    /** Whether the file carries data patches or embedded assets: any, of any version. */
    public boolean hasPatches(){
        if(patches == null || patches.length == 0) return false;
        var in = new com.xpdustry.shard.common.io.Reads(patches);
        return switch(version){
            case 11 -> in.ub() > 0;
            case 12 -> {
                in.i();
                int patchCount = in.i();
                if(patchCount > 0) yield true;
                yield in.i() > 0;
            }
            default -> {
                in.i();
                yield in.i() > 0;
            }
        };
    }

    /**
     * The map region: tiles as stored (expanded from runs) and buildings at their centres.
     *
     * @param floors floor ids as stored, before vanilla's air-floor fix
     * @param data tile data in the 7-byte format (bit 4 of the tile flags), packed ({@link TileDataCodec#pack})
     * @param oldData the single data byte of the v4–9 format (bit 2), on tiles without buildings
     * @param buildings by centre position
     * @param parts tiles that are covered by a building but are not its centre
     */
    public record SaveMap(int width, int height, short[] floors, short[] overlays, short[] blocks,
                          SortedMap<Integer, Long> data, SortedMap<Integer, Byte> oldData,
                          SortedMap<Integer, BuildingChunk> buildings, BitSet parts){
    }

    /**
     * A building chunk: {@code body} is {@code writeAll} for the given {@code revision}. {@link MsavReader} reads v1–3
     * chunks into this layout too.
     */
    public record BuildingChunk(int revision, byte[] body){
    }
}
