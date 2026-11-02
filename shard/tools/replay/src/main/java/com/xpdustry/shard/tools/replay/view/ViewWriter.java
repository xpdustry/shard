package com.xpdustry.shard.tools.replay.view;

import com.xpdustry.shard.tools.replay.view.ViewSource.*;
import tools.jackson.databind.json.*;

import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;

/**
 * Encodes a {@link ViewSource} as a view stream (format 1, {@code docs/replay/FORMAT.md}): a JSON header with the name
 * tables and the segment index, the terrain once, then segments that each start with a keyframe and continue with
 * frames of differences. Each segment is compressed on its own, so a viewer seeks by inflating one segment.
 *
 * <p>Call {@link #sample()} after every tick; it records a frame every {@link Options#frameEvery()} ticks and a
 * keyframe every {@link Options#keyframeEvery()} ticks. Positions are quantized to 1/8 world unit, angles to
 * 1/65536 of a turn, fractions to 1/255. Moving things are coded as the residual from a constant-velocity prediction,
 * of position and of rotation, so a unit or bullet in straight flight or a steady turn costs one byte a frame.
 */
public final class ViewWriter{
    public static final int FORMAT = 1;
    static final byte[] MAGIC = "SVIEW".getBytes(StandardCharsets.US_ASCII);
    static final int KEYFRAME = 'K', FRAME = 'F';
    /** Frame sections, in stream order. */
    static final int ENV = 1, BUILDS = 2, UNITS = 4, BULLETS = 8, EVENTS = 16, STATUS = 32;
    /** Unit update fields. */
    static final int U_POS = 1, U_ROT = 2, U_HEALTH = 4, U_ELEVATION = 8, U_FLAGS = 16, U_TYPE = 32;
    /** Building update fields. */
    static final int B_HEALTH = 1, B_ROTATION = 2, B_FLAGS = 4, B_AIM = 8, B_PROGRESS = 16;
    /** Bullet update fields. */
    static final int P_POS = 1, P_ROT = 2;
    /** A unit flag that only exists in the stream: a player name index follows the flags. */
    static final int PLAYER = 128;
    /** At most this many ticks between status records while nothing else in the status changes. */
    static final int STATUS_EVERY = 60;

    /**
     * @param frameEvery ticks between frames (3: 20 frames a second)
     * @param keyframeEvery ticks between keyframes, which are the seek points
     * @param level the deflate level
     */
    public record Options(int frameEvery, int keyframeEvery, int level){
        public static final Options DEFAULT = new Options(3, 600, 6);

        public Options{
            if(frameEvery < 1 || keyframeEvery < frameEvery || keyframeEvery % frameEvery != 0){
                throw new IllegalArgumentException("keyframeEvery must be a multiple of frameEvery: " + keyframeEvery + ", " + frameEvery);
            }
        }
    }

    /** Bytes by section before compression, and the compressed total. */
    public static final class Stats{
        final TreeMap<String, Long> raw = new TreeMap<>();
        long compressed, frames, keyframes, unitSamples, bulletSamples, buildings;
        int maxUnits, maxBullets;

        void add(String section, int bytes){
            raw.merge(section, (long)bytes, Long::sum);
        }

        public SortedMap<String, Long> raw(){
            return Collections.unmodifiableSortedMap(raw);
        }

        public long rawTotal(){
            return raw.values().stream().mapToLong(Long::longValue).sum();
        }

        public long compressed(){
            return compressed;
        }

        public long frames(){
            return frames;
        }

        public String summary(long ticks){
            double hours = ticks / 60.0 / 3600.0;
            var out = new StringBuilder();
            out.append(String.format("frames %d (%d keyframes), avg units %.1f (max %d), avg bullets %.1f (max %d), buildings at end %d%n",
                frames, keyframes, unitSamples / (double)Math.max(1, frames), maxUnits, bulletSamples / (double)Math.max(1, frames), maxBullets, buildings));
            raw.forEach((name, bytes) -> out.append(String.format("  %-10s %,12d B raw  %,10.0f KB/h%n", name, bytes, bytes / 1024.0 / hours)));
            out.append(String.format("  %-10s %,12d B raw  %,10.0f KB/h%n", "total", rawTotal(), rawTotal() / 1024.0 / hours));
            out.append(String.format("  %-10s %,12d B      %,10.0f KB/h (%.1f MB/h)%n", "file", compressed, compressed / 1024.0 / hours, compressed / 1048576.0 / hours));
            return out.toString();
        }
    }

    private final ViewSource source;
    private final Options options;
    private final int width, height, tiles;
    private final Names blocks = new Names(), units = new Names(), effects = new Names(), players = new Names();
    private final Map<BulletStyle, Integer> styles = new LinkedHashMap<>();
    private int[] blockNames = new int[0];

    private final short[] floorIds, overlayIds, envIds;
    private int[] terrainFloor, terrainOverlay, terrainEnv;
    private final int[] floor, overlay, env;
    private TreeMap<Integer, Build> builds = new TreeMap<>();
    private TreeMap<Integer, Mover> unitState = new TreeMap<>(), bulletState = new TreeMap<>();
    private int[] status;
    private long statusTick;
    private final List<ViewEvent> events = new ArrayList<>();

    private final Bytes segment = new Bytes(1 << 16);
    private final List<Segment> segments = new ArrayList<>();
    private byte[] terrain, header;
    private long start = -1, last, segmentStart;
    private int segmentFrames;
    private final Stats stats = new Stats();
    /** Set when this writer continues another's stream from a later keyframe: it writes segments only. */
    private final Continuation from;

    /**
     * What a writer needs to continue a stream from a later keyframe (a replay service regenerating one segment):
     * the name tables and terrain of the whole stream, and its start tick. Segments written with it decode under
     * the original header; with a deterministic sim they equal the original's byte for byte.
     */
    public record Continuation(List<String> blocks, List<String> units, List<String> effects, List<String> players,
                               List<BulletStyle> styles, int[] floor, int[] overlay, int[] env, long start){
    }

    private record Segment(long tick, int frames, byte[] bytes){
    }

    /** A building as last encoded. */
    private record Build(int block, int team, int rotation, int health, int flags, int aim, int target, int progress){
    }

    /** A unit or bullet as last encoded: quantized position, its last step, and the rest. */
    private static final class Mover{
        int x, y, vx, vy, rotation, spin, health, elevation, flags, player, type, team, age;
    }

    public ViewWriter(ViewSource source, Options options){
        this(source, options, null);
    }

    /** A writer that continues {@code from}'s stream at the source's current tick, which must be a keyframe tick. */
    public ViewWriter(ViewSource source, Options options, Continuation from){
        this.source = source;
        this.options = options;
        this.from = from;
        width = source.width();
        height = source.height();
        tiles = width * height;
        floorIds = new short[tiles];
        overlayIds = new short[tiles];
        envIds = new short[tiles];
        floor = new int[tiles];
        overlay = new int[tiles];
        env = new int[tiles];
        blocks.id("air");
        if(from != null){
            from.blocks().forEach(blocks::id);
            from.units().forEach(units::id);
            from.effects().forEach(effects::id);
            from.players().forEach(players::id);
            for(BulletStyle style : from.styles()) styles.putIfAbsent(style, styles.size());
            terrainFloor = from.floor().clone();
            terrainOverlay = from.overlay().clone();
            terrainEnv = from.env().clone();
        }
    }

    /** The continuation of this stream, once it is {@link #finish()}ed. */
    public Continuation continuation(){
        if(header == null) throw new IllegalStateException("finish() first");
        return new Continuation(blocks.list(), units.list(), effects.list(), players.list(), List.copyOf(styles.keySet()),
            terrainFloor, terrainOverlay, terrainEnv, start);
    }

    /** The header JSON, once finished. */
    public byte[] header(){
        return header;
    }

    /** The terrain chunk, deflated, once recording started. */
    public byte[] terrain(){
        return terrain;
    }

    /** The deflated segments, once finished. */
    public List<byte[]> segments(){
        return segments.stream().map(Segment::bytes).toList();
    }

    public Stats stats(){
        return stats;
    }

    /** Ticks recorded so far. */
    public long ticks(){
        return start < 0 ? 0 : last - start;
    }

    /** Records a frame if one is due; call it after every tick. Returns whether it did. */
    public boolean sample(){
        long tick = source.tick();
        events.addAll(source.events());
        if(start < 0 && from != null){
            start = from.start();
            readTerrain();
            keyframe(tick);
            return true;
        }
        if(start < 0){
            start = tick;
            readTerrain();
            terrainFloor = floor.clone();
            terrainOverlay = overlay.clone();
            terrainEnv = env.clone();
            terrain = deflate(terrainChunk());
            stats.add("terrain", terrain.length);
            keyframe(tick);
            return true;
        }
        if(tick - last < options.frameEvery()) return false;
        if(tick - segmentStart >= options.keyframeEvery()){
            // the old segment ends with a frame at this tick, which carries its events; the new one starts with a
            // keyframe of the same tick, without events, so a segment can be regenerated from its checkpoint alone
            frame(tick);
            closeSegment();
            keyframe(tick);
        }else{
            frame(tick);
        }
        return true;
    }

    /** The segments a continuing writer wrote ({@link #ViewWriter(ViewSource, Options, Continuation)}). */
    public List<byte[]> finishSegments(){
        if(start < 0) throw new IllegalStateException("nothing was recorded");
        closeSegment();
        return segments();
    }

    /** The whole stream. Recording may not continue afterwards. */
    public byte[] finish(){
        if(start < 0) throw new IllegalStateException("nothing was recorded");
        if(from != null) throw new IllegalStateException("a continuing writer writes segments only: use finishSegments()");
        closeSegment();
        var mapper = JsonMapper.builder().build();
        var header = new LinkedHashMap<String, Object>();
        header.put("format", FORMAT);
        header.put("producer", source.producer());
        header.put("game", source.game());
        header.put("content", source.content());
        header.put("map", source.map());
        header.put("width", width);
        header.put("height", height);
        header.put("tickRate", 60);
        header.put("frameEvery", options.frameEvery());
        header.put("keyframeEvery", options.keyframeEvery());
        header.put("start", start);
        header.put("ticks", last - start);
        header.put("frames", stats.frames);
        var teams = new ArrayList<Map<String, Object>>();
        for(TeamView team : source.teams()) teams.add(ordered("id", team.id(), "name", team.name(), "color", String.format("%08x", team.color())));
        header.put("teams", teams);
        header.put("blocks", blocks.list());
        header.put("units", units.list());
        header.put("effects", effects.list());
        header.put("players", players.list());
        var bullets = new ArrayList<Map<String, Object>>();
        for(BulletStyle style : styles.keySet()){
            var map = new LinkedHashMap<String, Object>();
            map.put("kind", style.kind());
            map.put("sprite", style.sprite());
            map.put("width", style.width());
            map.put("height", style.height());
            map.put("front", String.format("%08x", style.front()));
            map.put("back", String.format("%08x", style.back()));
            map.put("shrinkY", style.shrinkY());
            map.put("lifetime", style.lifetime());
            map.put("trailLength", style.trailLength());
            map.put("trailWidth", style.trailWidth());
            map.put("trailColor", String.format("%08x", style.trailColor()));
            map.put("hitSize", style.hitSize());
            bullets.add(map);
        }
        header.put("bullets", bullets);
        header.put("terrain", terrain.length);
        var index = new ArrayList<Map<String, Object>>();
        long offset = 0;
        for(Segment segment : segments){
            index.add(ordered("offset", offset, "length", segment.bytes.length, "tick", segment.tick - start, "frames", segment.frames));
            offset += 4 + segment.bytes.length;
        }
        header.put("segments", index);
        byte[] json = mapper.writeValueAsBytes(header);
        this.header = json;

        var out = new Bytes(json.length + terrain.length + (int)offset + 32);
        out.bytes(MAGIC).u8(FORMAT).u32(json.length).bytes(json).u32(terrain.length).bytes(terrain);
        for(Segment segment : segments) out.u32(segment.bytes.length).bytes(segment.bytes);
        stats.add("header", json.length);
        stats.compressed = out.size();
        return out.toArray();
    }

    private static Map<String, Object> ordered(Object... pairs){
        var map = new LinkedHashMap<String, Object>();
        for(int i = 0; i < pairs.length; i += 2) map.put((String)pairs[i], pairs[i + 1]);
        return map;
    }

    // ---- terrain ----

    private int blockName(int id){
        if(id >= blockNames.length) blockNames = Arrays.copyOf(blockNames, Math.max(id + 1, blockNames.length * 2));
        int cached = blockNames[id];
        if(cached == 0) cached = blockNames[id] = blocks.id(source.blockName(id)) + 1;
        return cached - 1;
    }

    private void readTerrain(){
        readTerrain(null);
    }

    /** Reads the terrain into the current layers; writes each changed tile to {@code changes}, if given, and counts them. */
    private int readTerrain(Bytes changes){
        source.terrain(floorIds, overlayIds, envIds);
        int count = 0, previous = 0;
        for(int i = 0; i < tiles; i++){
            int f = blockName(floorIds[i] & 0xFFFF), o = blockName(overlayIds[i] & 0xFFFF), e = blockName(envIds[i] & 0xFFFF);
            if(f != floor[i] || o != overlay[i] || e != env[i]){
                floor[i] = f;
                overlay[i] = o;
                env[i] = e;
                if(changes != null) changes.varint(i - previous).varint(f).varint(o).varint(e);
                previous = i;
                count++;
            }
        }
        return count;
    }

    private byte[] terrainChunk(){
        var out = new Bytes(tiles / 4);
        for(int[] layer : new int[][]{floor, overlay, env}){
            var runs = new Bytes(256);
            int count = 0;
            for(int i = 0; i < tiles; ){
                int j = i + 1;
                while(j < tiles && layer[j] == layer[i]) j++;
                runs.varint(j - i).varint(layer[i]);
                count++;
                i = j;
            }
            out.varint(count).bytes(runs);
        }
        return out.toArray();
    }

    // ---- frames ----

    private void keyframe(long tick){
        segmentStart = tick;
        last = tick;
        segmentFrames = 1;
        stats.frames++;
        stats.keyframes++;
        int before = segment.size();
        segment.u8(KEYFRAME).varint(tick - start);

        readTerrain();
        var envOut = new Bytes(64);
        int envCount = 0, previous = 0;
        for(int i = 0; i < tiles; i++){
            if(floor[i] != terrainFloor[i] || overlay[i] != terrainOverlay[i] || env[i] != terrainEnv[i]){
                envOut.varint(i - previous).varint(floor[i]).varint(overlay[i]).varint(env[i]);
                previous = i;
                envCount++;
            }
        }
        segment.varint(envCount).bytes(envOut);

        builds = readBuilds();
        segment.varint(builds.size());
        previous = 0;
        for(var entry : builds.entrySet()){
            segment.varint(entry.getKey() - previous);
            writeBuild(segment, entry.getValue());
            previous = entry.getKey();
        }

        unitState = readUnits();
        writeMovers(segment, unitState, true);
        bulletState = readBullets();
        writeMovers(segment, bulletState, false);

        status = readStatus();
        statusTick = tick;
        segment.varint(status[0]).varint(status[1]).varint(status[2]);
        stats.add("keyframes", segment.size() - before);
        count();
    }

    private void frame(long tick){
        int dtick = (int)(tick - last);
        last = tick;
        segmentFrames++;
        stats.frames++;
        int mask = 0;
        var envOut = new Bytes(16);
        var buildOut = new Bytes(64);
        var unitOut = new Bytes(256);
        var bulletOut = new Bytes(256);
        var eventOut = new Bytes(16);
        var statusOut = new Bytes(8);

        // terrain changes since the last frame
        var envBody = new Bytes(16);
        int envCount = readTerrain(envBody);
        if(envCount > 0){
            envOut.varint(envCount).bytes(envBody);
            mask |= ENV;
        }

        if(diffBuilds(readBuilds(), buildOut)) mask |= BUILDS;
        if(diffMovers(unitState, readUnits(), unitOut, true)) mask |= UNITS;
        if(diffMovers(bulletState, readBullets(), bulletOut, false)) mask |= BULLETS;
        if(!events.isEmpty()){
            writeEvents(eventOut);
            mask |= EVENTS;
        }
        int[] now = readStatus();
        if(now[0] != status[0] || now[2] != status[2] || tick - statusTick >= STATUS_EVERY){
            status = now;
            statusTick = tick;
            statusOut.varint(now[0]).varint(now[1]).varint(now[2]);
            mask |= STATUS;
        }

        segment.u8(FRAME).varint(dtick).u8(mask);
        stats.add("frame", 3);
        section(envOut, "env");
        section(buildOut, "builds");
        section(unitOut, "units");
        section(bulletOut, "bullets");
        section(eventOut, "events");
        section(statusOut, "status");
        count();
    }

    private void section(Bytes bytes, String name){
        segment.bytes(bytes);
        stats.add(name, bytes.size());
    }

    private void count(){
        stats.unitSamples += unitState.size();
        stats.bulletSamples += bulletState.size();
        stats.maxUnits = Math.max(stats.maxUnits, unitState.size());
        stats.maxBullets = Math.max(stats.maxBullets, bulletState.size());
        stats.buildings = builds.size();
    }

    private void closeSegment(){
        if(segment.size() == 0) return;
        segments.add(new Segment(segmentStart, segmentFrames, deflate(segment.toArray())));
        segment.clear();
    }

    private byte[] deflate(byte[] raw){
        var deflater = new Deflater(options.level(), true);
        deflater.setInput(raw);
        deflater.finish();
        var out = new Bytes(raw.length / 4 + 64);
        byte[] buffer = new byte[1 << 16];
        while(!deflater.finished()){
            int n = deflater.deflate(buffer);
            out.bytes(Arrays.copyOf(buffer, n));
        }
        deflater.end();
        return out.toArray();
    }

    // ---- buildings ----

    private TreeMap<Integer, Build> readBuilds(){
        var out = new TreeMap<Integer, Build>();
        source.buildings(view -> {
            boolean aims = (view.flags() & BuildView.AIM) != 0;
            boolean site = (view.flags() & (BuildView.CONSTRUCT | BuildView.DECONSTRUCT)) != 0;
            out.put(view.tile(), new Build(blocks.id(view.block()), view.team() & 0xFF, view.rotation() & 3, q8(view.health()), view.flags() & 0xFF,
                aims ? qrot(view.aim()) : 0, site && view.target() != null ? blocks.id(view.target()) + 1 : 0, site ? q8(view.progress()) : 0));
        });
        return out;
    }

    private static void writeBuild(Bytes out, Build build){
        out.varint(build.block).u8(build.team).u8(build.rotation).u8(build.health).u8(build.flags);
        if((build.flags & BuildView.AIM) != 0) out.u16(build.aim);
        if((build.flags & (BuildView.CONSTRUCT | BuildView.DECONSTRUCT)) != 0) out.varint(build.target).u8(build.progress);
    }

    private boolean diffBuilds(TreeMap<Integer, Build> now, Bytes out){
        var removed = new ArrayList<Integer>();
        var added = new TreeMap<Integer, Build>();
        var updated = new TreeMap<Integer, Build>();
        for(var entry : builds.entrySet()){
            Build next = now.get(entry.getKey());
            Build old = entry.getValue();
            if(next == null || next.block != old.block || next.team != old.team) removed.add(entry.getKey());
        }
        for(var entry : now.entrySet()){
            Build old = builds.get(entry.getKey());
            Build next = entry.getValue();
            if(old == null || next.block != old.block || next.team != old.team) added.put(entry.getKey(), next);
            else if(!next.equals(old)) updated.put(entry.getKey(), next);
        }
        if(removed.isEmpty() && added.isEmpty() && updated.isEmpty()){
            builds = now;
            return false;
        }
        out.varint(removed.size());
        int previous = 0;
        for(int tile : removed){
            out.varint(tile - previous);
            previous = tile;
        }
        out.varint(added.size());
        previous = 0;
        for(var entry : added.entrySet()){
            out.varint(entry.getKey() - previous);
            writeBuild(out, entry.getValue());
            previous = entry.getKey();
        }
        out.varint(updated.size());
        previous = 0;
        for(var entry : updated.entrySet()){
            Build old = builds.get(entry.getKey()), next = entry.getValue();
            int mask = (next.health != old.health ? B_HEALTH : 0) | (next.rotation != old.rotation ? B_ROTATION : 0)
                | (next.flags != old.flags ? B_FLAGS : 0) | ((next.flags & BuildView.AIM) != 0 && next.aim != old.aim ? B_AIM : 0)
                | ((next.flags & (BuildView.CONSTRUCT | BuildView.DECONSTRUCT)) != 0 && (next.target != old.target || next.progress != old.progress || next.flags != old.flags) ? B_PROGRESS : 0);
            out.varint(entry.getKey() - previous).u8(mask);
            if((mask & B_HEALTH) != 0) out.u8(next.health);
            if((mask & B_ROTATION) != 0) out.u8(next.rotation);
            if((mask & B_FLAGS) != 0) out.u8(next.flags);
            if((mask & B_AIM) != 0) out.u16(next.aim);
            if((mask & B_PROGRESS) != 0) out.varint(next.target).u8(next.progress);
            previous = entry.getKey();
        }
        builds = now;
        return true;
    }

    // ---- units and bullets ----

    private TreeMap<Integer, Mover> readUnits(){
        var out = new TreeMap<Integer, Mover>();
        source.units(view -> {
            var unit = new Mover();
            unit.type = units.id(view.type());
            unit.team = view.team() & 0xFF;
            unit.x = qpos(view.x());
            unit.y = qpos(view.y());
            unit.rotation = qrot(view.rotation());
            unit.health = q8(view.health());
            unit.elevation = q8(view.elevation());
            unit.flags = view.flags() & 0x7F;
            if(view.player() != null){
                unit.flags |= PLAYER;
                unit.player = players.id(view.player());
            }
            out.put(view.id(), unit);
        });
        return out;
    }

    private TreeMap<Integer, Mover> readBullets(){
        var out = new TreeMap<Integer, Mover>();
        source.bullets(view -> {
            var bullet = new Mover();
            bullet.type = styles.computeIfAbsent(view.style(), style -> styles.size());
            bullet.team = view.team() & 0xFF;
            bullet.x = qpos(view.x());
            bullet.y = qpos(view.y());
            bullet.rotation = qrot(view.rotation());
            bullet.age = Math.clamp(Math.round(view.age()), 0, 255);
            out.put(view.id(), bullet);
        });
        return out;
    }

    private static void writeMover(Bytes out, Mover mover, boolean unit){
        out.varint(mover.type).u8(mover.team).svarint(mover.x).svarint(mover.y).u16(mover.rotation);
        if(unit){
            out.u8(mover.health).u8(mover.elevation).u8(mover.flags);
            if((mover.flags & PLAYER) != 0) out.varint(mover.player);
        }else{
            out.u8(mover.age);
        }
    }

    /** A keyframe's full list: count, then (id gap, record) by ascending id. */
    private static void writeMovers(Bytes out, TreeMap<Integer, Mover> movers, boolean unit){
        out.varint(movers.size());
        int previous = 0;
        for(var entry : movers.entrySet()){
            out.varint(entry.getKey() - previous);
            writeMover(out, entry.getValue(), unit);
            previous = entry.getKey();
        }
    }

    /**
     * A frame's section: removed ids, added records, then one update per surviving mover in ascending id order.
     * Returns false, writing nothing, when nothing was removed or added and every update would be empty; a reader
     * then moves every survivor along its prediction. A bullet has no type or team update, so one whose style or
     * team changed (a deflecting wall, Wall.java:199) is removed and added again under the same id.
     */
    private boolean diffMovers(TreeMap<Integer, Mover> old, TreeMap<Integer, Mover> now, Bytes out, boolean unit){
        var removed = new ArrayList<Integer>();
        var added = new TreeMap<Integer, Mover>();
        for(var entry : old.entrySet()){
            Mover next = now.get(entry.getKey()), was = entry.getValue();
            if(next == null) removed.add(entry.getKey());
            else if(!unit && (next.type != was.type || next.team != was.team)){
                removed.add(entry.getKey());
                added.put(entry.getKey(), next);
            }
        }
        for(var entry : now.entrySet()) if(!old.containsKey(entry.getKey())) added.put(entry.getKey(), entry.getValue());

        var updates = new Bytes(old.size() * 3 + 8);
        boolean any = !removed.isEmpty() || !added.isEmpty();
        for(var entry : old.entrySet()){
            Mover next = now.get(entry.getKey());
            if(next == null || added.containsKey(entry.getKey())) continue;
            Mover was = entry.getValue();
            int px = was.x + was.vx, py = was.y + was.vy, pr = (was.rotation + was.spin) & 0xFFFF;
            int mask = 0;
            if(next.x != px || next.y != py) mask |= U_POS;
            if(next.rotation != pr) mask |= U_ROT;
            if(unit){
                if(next.health != was.health) mask |= U_HEALTH;
                if(next.elevation != was.elevation) mask |= U_ELEVATION;
                if(next.flags != was.flags || next.player != was.player) mask |= U_FLAGS;
                if(next.type != was.type || next.team != was.team) mask |= U_TYPE;
            }
            updates.u8(mask);
            if((mask & U_POS) != 0) updates.svarint(next.x - px).svarint(next.y - py);
            if((mask & U_ROT) != 0) updates.svarint((short)(next.rotation - pr));
            if((mask & U_HEALTH) != 0) updates.u8(next.health);
            if((mask & U_ELEVATION) != 0) updates.u8(next.elevation);
            if((mask & U_FLAGS) != 0){
                updates.u8(next.flags);
                if((next.flags & PLAYER) != 0) updates.varint(next.player);
            }
            if((mask & U_TYPE) != 0) updates.varint(next.type).u8(next.team);
            any |= mask != 0;
            next.vx = next.x - was.x;
            next.vy = next.y - was.y;
            next.spin = (short)(next.rotation - was.rotation);
        }
        if(unit) unitState = now;
        else bulletState = now;
        if(!any) return false;

        out.varint(removed.size());
        int previous = 0;
        for(int id : removed){
            out.varint(id - previous);
            previous = id;
        }
        out.varint(added.size());
        previous = 0;
        for(var entry : added.entrySet()){
            out.varint(entry.getKey() - previous);
            writeMover(out, entry.getValue(), unit);
            previous = entry.getKey();
        }
        out.bytes(updates);
        return true;
    }

    // ---- events and status ----

    private void writeEvents(Bytes out){
        out.varint(events.size());
        for(ViewEvent event : events){
            out.varint(effects.id(event.kind())).u8(event.team() & 0xFF).svarint(qpos(event.x())).svarint(qpos(event.y()))
                .u8(qrot(event.rotation()) >>> 8).varint(event.content() == null ? 0 : blocksOrUnits(event) + 1).varint(Math.max(0, Math.round(event.size() * 4f)));
        }
        events.clear();
    }

    /** An event's content: a unit type for unit events, else a block. */
    private int blocksOrUnits(ViewEvent event){
        return event.kind().startsWith("unit") ? units.id(event.content()) : blocks.id(event.content());
    }

    private int[] readStatus(){
        Status now = source.status();
        return new int[]{Math.max(0, now.wave()), Math.max(0, Math.round(now.countdown())), Math.max(0, now.enemies())};
    }

    // ---- quantization ----

    static int qpos(float value){
        return Math.round(value * 8f);
    }

    static int qrot(float degrees){
        return Math.round(degrees * (65536f / 360f)) & 0xFFFF;
    }

    static int q8(float fraction){
        if(!(fraction > 0f)) return 0;
        return Math.clamp(Math.round(fraction * 255f), 1, 255);
    }

    /** Names in first-use order; index = position. */
    static final class Names{
        private final Map<String, Integer> ids = new HashMap<>();
        private final List<String> list = new ArrayList<>();

        int id(String name){
            Integer id = ids.get(name);
            if(id != null) return id;
            ids.put(name, list.size());
            list.add(name);
            return list.size() - 1;
        }

        List<String> list(){
            return List.copyOf(list);
        }
    }
}
