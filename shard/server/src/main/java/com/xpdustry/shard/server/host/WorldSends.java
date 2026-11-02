package com.xpdustry.shard.server.host;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;

import java.util.*;

/**
 * Vanilla's {@code sendWorldData} ({@code NetServer.java:328}): the world stream a joining player loads. It is
 * captured on the world's tick thread ({@link WorldProjection}) and written and deflated anywhere
 * ({@link #encode}). Every player that joins in one tick shares one capture of the map.
 */
final class WorldSends{
    private final WorldProjection projection;
    private final BuildingCodecs codecs;

    /**
     * Streams worlds of {@code epoch}, or of a map's patch of it ({@link Epoch#serves}): the stream's header and ids
     * are the epoch's, which a patch keeps, and every entity and building is projected with the world's own content,
     * whose hash its passthrough bytes carry.
     */
    WorldSends(Epoch epoch, BuildingCodecs codecs){
        projection = new WorldProjection(epoch, codecs);
        this.codecs = codecs;
    }

    /**
     * One stream per player, sharing the map: on the tick thread, between ticks. With fog each team's players share
     * their own units ({@link WorldProjection#stream}: the units in fog to the team are left out).
     */
    List<WorldStreamSource> capture(World world, MapState state, List<Player> players){
        var out = new ArrayList<WorldStreamSource>(players.size());
        var entities = new EntityProjector(world.content(), codecs);
        WorldStreamSource first = null;
        // by the team whose fog filters the units (-1 without fog)
        var byViewer = new TreeMap<Integer, List<MapState.EntityChunk>>();
        for(Player player : players){
            PlayerState joining = PlayerState.of(entities.player(player));
            int viewer = WorldProjection.viewer(world, joining.team());
            if(first == null){
                first = projection.stream(world, state, joining);
                byViewer.put(viewer, first.entities());
                out.add(first);
            }else{
                List<MapState.EntityChunk> seen = byViewer.computeIfAbsent(viewer,
                    team -> projection.entities(world, state, projection.header(state).mapping(world.content()), team));
                out.add(new WorldStreamSource(first.patches(), first.rules(), first.locales(), first.tags(), first.wave(), first.wavetime(),
                    first.tick(), first.seed0(), first.seed1(), joining, first.header(), first.map(), first.entityMapping(), first.plans(),
                    seen, first.markers(), first.customChunks()));
            }
        }
        return out;
    }

    /**
     * The world as vanilla's {@code SaveIO.save} writes it (ServerControl's {@code save}): every live unit, whatever the
     * fog, deflated v13 msav with the save's meta ({@link MsavWriter#meta}). On the tick thread, between ticks; it
     * changes nothing in the world.
     *
     * @param saved the {@code saved} meta key, in epoch milliseconds
     */
    byte[] save(World world, MapState state, long saved){
        int defaultTeam = world.part(WorldRules.class).rules.defaultTeam;
        WorldStreamSource source = projection.capture(world, state, PlayerState.fresh(-1, "", defaultTeam));
        if(state.assets != null && state.assets.hasExternal()){
            // the external assets embedded, as `save <slot> true` writes them: vanilla's plain save names them by hash,
            // which its asset cache serves on load, and Shard has none, so a save by hash wouldn't load back here
            source = new WorldStreamSource(state.assets.write(true), source.rules(), source.locales(), source.tags(), source.wave(),
                source.wavetime(), source.tick(), source.seed0(), source.seed1(), source.player(), source.header(), source.map(),
                source.entityMapping(), source.plans(), source.entities(), source.markers(), source.customChunks());
        }
        return MsavWriter.write(MsavWriter.meta(source, state.tags, defaultTeam, MsavWriter.noCores(world, defaultTeam), saved), source);
    }

    /** What warming up measured: the capture's {@link Footprint}, and its stream's bytes as written and as deflated. */
    record Warm(Footprint footprint, int raw, int deflated){
    }

    /**
     * Captures and encodes a stream for no one, off the tick threads, before the world first runs: the first join's
     * capture on the tick thread is then warm (cold, it stalled a tick by ~90 ms). It reads the world and changes
     * nothing in it.
     */
    Warm warm(World world, MapState state){
        WorldStreamSource source = projection.capture(world, state, PlayerState.fresh(-1, "", 0));
        byte[] raw = WorldStreamWriter.write(source);
        return new Warm(Footprint.of(source), raw.length, WorldStreamWriter.deflate(raw).length);
    }

    /**
     * What a capture holds until its streams are sent, and what each stream's encode may hold besides, in bytes:
     * bounds taken high from the capture itself, which {@link CaptureBudget} reserves.
     *
     * <p>The capture: the projection's three {@code short} and one {@code int} per tile, its tile data, the building
     * chunks and live entities it encoded, the rules' JSON and the rest, each with the JVM's headers. A stream: the raw
     * stream at its worst (no tile runs, {@code MapImage.write}), which {@link Writes} doubles into and copies out of,
     * then the deflated copy, which its buffer doubles into and copies out of again, the deflater's own state, and the
     * player's part.
     *
     * @param capture bytes the capture holds
     * @param stream bytes one stream's encode may hold at once
     */
    record Footprint(long capture, long stream){
        /** An object's or array's header and alignment, and a map entry with its boxed key and value, taken high. */
        private static final long OBJECT = 32, ENTRY = 96;
        /** The content header and id mapping a capture makes, and the stream's fixed sections, taken high. */
        private static final long FIXED = 64L << 10;
        /** zlib's state at the default level: a 32 KiB window and its hash tables, 256 KiB, plus slack. */
        private static final long DEFLATER = 320L << 10;
        /** A joining player's own part: its {@link PlayerState} and stream record. */
        private static final long PLAYER = 4L << 10;

        static Footprint of(WorldStreamSource source){
            MapImage map = source.map();
            long tiles = (long)map.width() * map.height(), data = map.data().size(), buildings = map.chunks().size(), entities = source.entities().size();
            long chunkBytes = 0, entityBytes = 0, customBytes = 0;
            for(byte[] chunk : map.chunks().values()) chunkBytes += chunk.length;
            for(MapState.EntityChunk entity : source.entities()) entityBytes += entity.body().length;
            for(MapState.CustomChunk custom : source.customChunks()) customBytes += custom.bytes().length;
            long text = source.rules().length();
            for(var tag : source.tags().entrySet()) text += tag.getKey().length() + tag.getValue().length();
            long capture = 10 * tiles + tiles / 8 + 5 * OBJECT
                + data * ENTRY + buildings * (ENTRY + OBJECT) + chunkBytes + entities * (2 * OBJECT) + entityBytes
                + 2 * text + source.tags().size() * ENTRY + source.markers().length + OBJECT
                + 8L * (source.entityMapping().size() + source.plans().size() + source.customChunks().size()) + FIXED;
            long raw = 9 * tiles + 9 * data + chunkBytes + 6 * buildings + entityBytes + 16 * entities
                + 3 * text + source.markers().length + source.patches().length + customBytes + FIXED;
            return new Footprint(capture, 4 * raw + raw / 100 + DEFLATER + PLAYER);
        }
    }

    /** The deflated stream, as {@code NetworkIO.writeWorld} and its deflater make it. Any thread. */
    static byte[] encode(WorldStreamSource source){
        return WorldStreamWriter.deflate(WorldStreamWriter.write(source));
    }
}
