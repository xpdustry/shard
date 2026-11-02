package com.xpdustry.shard.compat.v160.world;

import java.util.*;

/**
 * Everything a world stream holds, frozen at one tick boundary ({@link WorldProjection#capture}), so
 * {@link WorldStreamWriter} can encode it on any thread. Content ids are in {@code header}'s id space, except the
 * player's, which precede the header.
 *
 * @param patches the data patch section ({@link WorldStreamWriter#patches})
 * @param rules the rules JSON
 * @param markers {@code MapMarkers} UBJSON
 */
public record WorldStreamSource(byte[] patches, String rules, String locales, SequencedMap<String, String> tags, int wave, float wavetime,
                                double tick, long seed0, long seed1, PlayerState player, ContentHeader header, MapImage map,
                                List<MapState.EntityMappingEntry> entityMapping, List<MapState.TeamPlans> plans,
                                List<MapState.EntityChunk> entities, byte[] markers, List<MapState.CustomChunk> customChunks){
}
