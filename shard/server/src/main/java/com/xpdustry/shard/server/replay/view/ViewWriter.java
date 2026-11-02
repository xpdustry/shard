package com.xpdustry.shard.server.replay.view;

import com.xpdustry.shard.server.replay.view.ViewSource.*;

import java.nio.charset.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/**
 * Encodes a {@link ViewSource} as view stream segments (format 2, {@code docs/replay/FORMAT.md}): each segment is a keyframe
 * followed by frames of differences, made of typed, length-delimited sections, and compressed on its own. Content is
 * numbered by the source's fixed tables, so a segment depends only on the world at its keyframe and what happened
 * after it: a replay service writes the same bytes again from a checkpoint ({@link #ViewWriter(ViewSource, Options,
 * Baseline)}).
 *
 * <p>Call {@link #sample()} after every tick; it records a frame every {@link Options#frameEvery()} ticks and starts a
 * segment every {@link Options#keyframeEvery()} ticks, counted from the first sample. Closed segments wait in
 * {@link #take()}. Positions are quantized to 1/8 world unit, angles to 1/65536 of a turn, fractions to 1/255. Units
 * and bullets are coded as the residual from a constant-velocity, constant-turn prediction.
 */
public final class ViewWriter{
    public static final int FORMAT = 2;
    static final byte[] MAGIC = "SVIEW".getBytes(StandardCharsets.US_ASCII);
    static final int KEYFRAME = 'K', FRAME = 'F';
    /** Section types, in stream order. */
    static final int ENV = 1, BUILDINGS = 2, UNITS = 3, BULLETS = 4, EVENTS = 5, STATUS = 6, PLAYERS = 7, CHAT = 8, PLANS = 9, FIRES = 10,
        PUDDLES = 11, REBUILD = 12;
    /** Unit update fields. */
    static final int U_POS = 1, U_ROT = 2, U_HEALTH = 4, U_ELEVATION = 8, U_FLAGS = 16, U_TYPE = 32, U_AIM = 64;
    /** Building update fields. */
    static final int B_HEALTH = 1, B_ROTATION = 2, B_FLAGS = 4, B_AIM = 8, B_PROGRESS = 16;
    /** Player update fields. */
    static final int P_TEAM = 1, P_UNIT = 2, P_NAME = 4, P_COLOR = 8, P_FLAGS = 16;
    /** A unit flag that only exists in the stream: the player's id follows the flags. */
    static final int PLAYER = 128;
    /** At most this many ticks between status records while nothing else in the status changes. */
    static final int STATUS_EVERY = 60;
    /** The event kinds, by index: the header's {@code effects}. Append only. */
    public static final List<String> EFFECTS = List.of("unit-death", "block-destroy", "wave", "game-over");
    /** Plans kept per unit; vanilla's queue is unbounded, the stream isn't. */
    static final int MAX_PLANS = 512;
    /** Chat lines and names are cut to this many UTF-8 bytes. */
    static final int MAX_TEXT = 1024;

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

    /**
     * What a stream's segments are written against: its first tick, and its terrain then (as {@link ViewSource#blocks()}
     * indices). A writer made with it continues the stream at a later keyframe.
     */
    public record Baseline(long start, int[] floor, int[] overlay, int[] env){
    }

    /**
     * A closed segment. Compressing and hashing it are left to the caller, off the world's thread.
     *
     * @param tick its keyframe's tick, absolute
     * @param frames its records, keyframe included
     * @param raw its bytes before compression
     */
    public record Segment(long tick, int frames, byte[] raw){
        /** SHA-256 of {@link #raw}, in hex: the header's index carries it. */
        public String hash(){
            return ViewWriter.hash(raw);
        }

        /** {@link #raw}, deflated as stored. */
        public byte[] deflated(int level){
            return deflate(raw, level);
        }
    }

    private final ViewSource source;
    private final Options options;
    private final int width, height, tiles;

    private final short[] floorIds, overlayIds, envIds;
    private int[] terrainFloor, terrainOverlay, terrainEnv;
    private final int[] floor, overlay, env;
    private TreeMap<Integer, Build> builds = new TreeMap<>();
    private TreeMap<Integer, Mover> unitState = new TreeMap<>(), bulletState = new TreeMap<>();
    private TreeMap<Integer, PlayerView> players = new TreeMap<>();
    private TreeMap<Integer, List<Planned>> plans = new TreeMap<>();
    private TreeMap<Integer, Integer> fires = new TreeMap<>();
    private TreeMap<Integer, int[]> puddles = new TreeMap<>();
    private TreeMap<Integer, List<Integer>> rebuild = new TreeMap<>();
    private int[] status;
    private long statusTick;
    private final List<ViewEvent> events = new ArrayList<>();
    private final List<ChatView> chat = new ArrayList<>();

    private final Bytes segment = new Bytes(1 << 16);
    private final List<Segment> closed = new ArrayList<>();
    private byte[] terrain;
    private long start = -1, last, segmentStart;
    private int segmentFrames;
    private long frames;
    private final Baseline from;

    /** A writer of a new stream, which starts at the first {@link #sample()}. */
    public ViewWriter(ViewSource source, Options options){
        this(source, options, null);
    }

    /** A writer that continues {@code from}'s stream at the source's current tick, which must be one of its keyframe ticks. */
    public ViewWriter(ViewSource source, Options options, Baseline from){
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
        if(from != null){
            if(from.floor().length != tiles) throw new IllegalArgumentException("the baseline is for another map size");
            terrainFloor = from.floor().clone();
            terrainOverlay = from.overlay().clone();
            terrainEnv = from.env().clone();
        }
    }

    /** The terrain chunk, deflated, once recording started. */
    public byte[] terrain(){
        if(terrain == null && terrainFloor != null) terrain = deflate(terrainChunk(terrainFloor, terrainOverlay, terrainEnv), options.level());
        return terrain;
    }

    /** What this stream's segments are written against, once recording started. */
    public Baseline baseline(){
        if(start < 0) throw new IllegalStateException("nothing was recorded");
        return new Baseline(start, terrainFloor.clone(), terrainOverlay.clone(), terrainEnv.clone());
    }

    /** The first tick, or -1 before the first sample. */
    public long start(){
        return start;
    }

    /** The last tick recorded. */
    public long last(){
        return last;
    }

    /** Records written so far, keyframes included. */
    public long frames(){
        return frames;
    }

    /** Records a frame if one is due; call it after every tick. Returns whether it did. */
    public boolean sample(){
        long tick = source.tick();
        events.addAll(source.events());
        chat.addAll(source.chat());
        if(start < 0){
            if(from != null){
                start = from.start();
                if(tick < start || (tick - start) % options.keyframeEvery() != 0){
                    throw new IllegalStateException("tick " + tick + " is not a keyframe of a stream that starts at " + start);
                }
                readTerrain();
            }else{
                start = tick;
                readTerrain();
                terrainFloor = floor.clone();
                terrainOverlay = overlay.clone();
                terrainEnv = env.clone();
            }
            // what happened before the stream (or before this segment) belongs to the previous one
            events.clear();
            chat.clear();
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

    /** The segments closed since the last call, in order. */
    public List<Segment> take(){
        var out = List.copyOf(closed);
        closed.clear();
        return out;
    }

    /** Ends the stream: closes the open segment and returns every segment not taken yet. */
    public List<Segment> finish(){
        if(start < 0) throw new IllegalStateException("nothing was recorded");
        closeSegment();
        return take();
    }

    // ---- terrain ----

    private void readTerrain(){
        readTerrain(null);
    }

    /** Reads the terrain into the current layers; writes each changed tile to {@code changes}, if given, and counts them. */
    private int readTerrain(Bytes changes){
        source.terrain(floorIds, overlayIds, envIds);
        int count = 0, previous = 0;
        for(int i = 0; i < tiles; i++){
            int f = floorIds[i] & 0xFFFF, o = overlayIds[i] & 0xFFFF, e = envIds[i] & 0xFFFF;
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

    static byte[] terrainChunk(int[] floor, int[] overlay, int[] env){
        var out = new Bytes(floor.length / 4);
        for(int[] layer : new int[][]{floor, overlay, env}){
            var runs = new Bytes(256);
            int count = 0;
            for(int i = 0; i < layer.length; ){
                int j = i + 1;
                while(j < layer.length && layer[j] == layer[i]) j++;
                runs.varint(j - i).varint(layer[i]);
                count++;
                i = j;
            }
            out.varint(count).bytes(runs);
        }
        return out.toArray();
    }

    // ---- records ----

    private void keyframe(long tick){
        segmentStart = tick;
        last = tick;
        segmentFrames = 1;
        frames++;
        segment.u8(KEYFRAME).varint(tick - start);
        var sections = new Sections();

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
        if(envCount > 0) sections.add(ENV, new Bytes(envOut.size() + 4).varint(envCount).bytes(envOut));

        builds = readBuilds();
        if(!builds.isEmpty()){
            var out = new Bytes(builds.size() * 8).varint(builds.size());
            previous = 0;
            for(var entry : builds.entrySet()){
                out.varint(entry.getKey() - previous);
                writeBuild(out, entry.getValue());
                previous = entry.getKey();
            }
            sections.add(BUILDINGS, out);
        }

        unitState = readUnits();
        if(!unitState.isEmpty()) sections.add(UNITS, writeMovers(unitState, true));
        bulletState = readBullets();
        if(!bulletState.isEmpty()) sections.add(BULLETS, writeMovers(bulletState, false));

        status = readStatus();
        statusTick = tick;
        sections.add(STATUS, new Bytes(8).varint(status[0]).varint(status[1]).varint(status[2]));

        players = readPlayers();
        if(!players.isEmpty()){
            var out = new Bytes(players.size() * 24).varint(players.size());
            previous = 0;
            for(var entry : players.entrySet()){
                out.varint(entry.getKey() - previous);
                writePlayer(out, entry.getValue());
                previous = entry.getKey();
            }
            sections.add(PLAYERS, out);
        }

        plans = readPlans();
        if(!plans.isEmpty()) sections.add(PLANS, writePlans(plans));

        fires = readFires();
        if(!fires.isEmpty()){
            var out = new Bytes(fires.size() * 3).varint(fires.size());
            previous = 0;
            for(var entry : fires.entrySet()){
                out.varint(entry.getKey() - previous).u8(entry.getValue());
                previous = entry.getKey();
            }
            sections.add(FIRES, out);
        }

        puddles = readPuddles();
        if(!puddles.isEmpty()){
            var out = new Bytes(puddles.size() * 4).varint(puddles.size());
            previous = 0;
            for(var entry : puddles.entrySet()){
                out.varint(entry.getKey() - previous).varint(entry.getValue()[0]).u8(entry.getValue()[1]);
                previous = entry.getKey();
            }
            sections.add(PUDDLES, out);
        }

        rebuild = readRebuild();
        if(!rebuild.isEmpty()){
            var out = new Bytes(rebuild.size() * 5).varint(rebuild.size());
            previous = 0;
            for(var entry : rebuild.entrySet()){
                out.varint(entry.getKey() - previous);
                writeRebuild(out, entry.getValue());
                previous = entry.getKey();
            }
            sections.add(REBUILD, out);
        }
        sections.writeTo(segment);
    }

    private void frame(long tick){
        int dtick = (int)(tick - last);
        last = tick;
        segmentFrames++;
        frames++;
        var sections = new Sections();

        var envBody = new Bytes(16);
        int envCount = readTerrain(envBody);
        if(envCount > 0) sections.add(ENV, new Bytes(envBody.size() + 4).varint(envCount).bytes(envBody));

        var buildOut = new Bytes(64);
        if(diffBuilds(readBuilds(), buildOut)) sections.add(BUILDINGS, buildOut);
        var unitOut = new Bytes(256);
        if(diffMovers(unitState, readUnits(), unitOut, true)) sections.add(UNITS, unitOut);
        var bulletOut = new Bytes(256);
        if(diffMovers(bulletState, readBullets(), bulletOut, false)) sections.add(BULLETS, bulletOut);
        if(!events.isEmpty()) sections.add(EVENTS, writeEvents(tick));

        int[] now = readStatus();
        if(now[0] != status[0] || now[2] != status[2] || tick - statusTick >= STATUS_EVERY){
            status = now;
            statusTick = tick;
            sections.add(STATUS, new Bytes(8).varint(now[0]).varint(now[1]).varint(now[2]));
        }

        var playerOut = new Bytes(16);
        if(diffPlayers(readPlayers(), playerOut)) sections.add(PLAYERS, playerOut);
        if(!chat.isEmpty()) sections.add(CHAT, writeChat(tick));
        var planOut = new Bytes(16);
        if(diffPlans(readPlans(), planOut)) sections.add(PLANS, planOut);
        var fireOut = new Bytes(16);
        if(diffFires(readFires(), fireOut)) sections.add(FIRES, fireOut);
        var puddleOut = new Bytes(16);
        if(diffPuddles(readPuddles(), puddleOut)) sections.add(PUDDLES, puddleOut);
        var rebuildOut = new Bytes(16);
        if(diffRebuild(readRebuild(), rebuildOut)) sections.add(REBUILD, rebuildOut);

        segment.u8(FRAME).varint(dtick);
        sections.writeTo(segment);
    }

    /** A record's sections: {@code varint count}, then each as {@code varint type, varint length, body}, by type. */
    private static final class Sections{
        private final TreeMap<Integer, Bytes> bodies = new TreeMap<>();

        void add(int type, Bytes body){
            bodies.put(type, body);
        }

        void writeTo(Bytes out){
            out.varint(bodies.size());
            bodies.forEach((type, body) -> out.varint(type).varint(body.size()).bytes(body));
        }
    }

    private void closeSegment(){
        if(segment.size() == 0) return;
        byte[] raw = segment.toArray();
        closed.add(new Segment(segmentStart, segmentFrames, raw));
        segment.clear();
    }

    /** SHA-256 of {@code bytes}, in lower-case hex. */
    public static String hash(byte[] bytes){
        try{
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }catch(NoSuchAlgorithmException e){
            throw new IllegalStateException(e);
        }
    }

    public static byte[] deflate(byte[] raw, int level){
        var deflater = new Deflater(level, true);
        try{
            deflater.setInput(raw);
            deflater.finish();
            var out = new Bytes(raw.length / 4 + 64);
            byte[] buffer = new byte[1 << 16];
            while(!deflater.finished()){
                int n = deflater.deflate(buffer);
                out.bytes(Arrays.copyOf(buffer, n));
            }
            return out.toArray();
        }finally{
            deflater.end();
        }
    }

    // ---- buildings ----

    /** A building as last encoded. */
    private record Build(int block, int team, int rotation, int health, int flags, int aim, int target, int progress){
    }

    private TreeMap<Integer, Build> readBuilds(){
        var out = new TreeMap<Integer, Build>();
        source.buildings(view -> {
            boolean aims = (view.flags() & BuildView.AIM) != 0;
            boolean site = (view.flags() & (BuildView.CONSTRUCT | BuildView.DECONSTRUCT)) != 0;
            out.put(view.tile(), new Build(view.block(), view.team() & 0xFF, view.rotation() & 3, q8(view.health()), view.flags() & 0xFF,
                aims ? qrot(view.aim()) : 0, site ? view.target() + 1 : 0, site ? q8(view.progress()) : 0));
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
        TreeMap<Integer, Build> was = builds;
        builds = now;
        if(removed.isEmpty() && added.isEmpty() && updated.isEmpty()) return false;
        gaps(out, removed);
        out.varint(added.size());
        int previous = 0;
        for(var entry : added.entrySet()){
            out.varint(entry.getKey() - previous);
            writeBuild(out, entry.getValue());
            previous = entry.getKey();
        }
        out.varint(updated.size());
        previous = 0;
        for(var entry : updated.entrySet()){
            Build old = was.get(entry.getKey()), next = entry.getValue();
            int mask = (next.health != old.health ? B_HEALTH : 0) | (next.rotation != old.rotation ? B_ROTATION : 0)
                | (next.flags != old.flags ? B_FLAGS : 0) | ((next.flags & BuildView.AIM) != 0 && (next.aim != old.aim || next.flags != old.flags) ? B_AIM : 0)
                | ((next.flags & (BuildView.CONSTRUCT | BuildView.DECONSTRUCT)) != 0 && (next.target != old.target || next.progress != old.progress || next.flags != old.flags) ? B_PROGRESS : 0);
            out.varint(entry.getKey() - previous).u8(mask);
            if((mask & B_HEALTH) != 0) out.u8(next.health);
            if((mask & B_ROTATION) != 0) out.u8(next.rotation);
            if((mask & B_FLAGS) != 0) out.u8(next.flags);
            if((mask & B_AIM) != 0) out.u16(next.aim);
            if((mask & B_PROGRESS) != 0) out.varint(next.target).u8(next.progress);
            previous = entry.getKey();
        }
        return true;
    }

    // ---- units and bullets ----

    /** A unit or bullet as last encoded: quantized position, its last step, and the rest. */
    private static final class Mover{
        int x, y, vx, vy, rotation, spin, health, elevation, flags, player, type, team, age, aim;
    }

    private TreeMap<Integer, Mover> readUnits(){
        var out = new TreeMap<Integer, Mover>();
        source.units(view -> {
            var unit = new Mover();
            unit.type = view.type();
            unit.team = view.team() & 0xFF;
            unit.x = qpos(view.x());
            unit.y = qpos(view.y());
            unit.rotation = qrot(view.rotation());
            unit.health = q8(view.health());
            unit.elevation = q8(view.elevation());
            unit.flags = view.flags() & 0x7F;
            if(view.player() >= 0){
                unit.flags |= PLAYER;
                unit.player = view.player();
            }
            if((unit.flags & UnitView.AIM) != 0) unit.aim = qrot(view.aim());
            out.put(view.id(), unit);
        });
        return out;
    }

    private TreeMap<Integer, Mover> readBullets(){
        var out = new TreeMap<Integer, Mover>();
        source.bullets(view -> {
            var bullet = new Mover();
            bullet.type = view.type();
            bullet.team = view.team() & 0xFF;
            bullet.x = qpos(view.x());
            bullet.y = qpos(view.y());
            bullet.rotation = qrot(view.rotation());
            bullet.age = Math.max(0, view.age());
            out.put(view.id(), bullet);
        });
        return out;
    }

    private static void writeMover(Bytes out, Mover mover, boolean unit){
        out.varint(mover.type).u8(mover.team).svarint(mover.x).svarint(mover.y).u16(mover.rotation);
        if(unit){
            out.u8(mover.health).u8(mover.elevation).u8(mover.flags);
            if((mover.flags & PLAYER) != 0) out.varint(mover.player);
            if((mover.flags & UnitView.AIM) != 0) out.u16(mover.aim);
        }else{
            out.varint(mover.age);
        }
    }

    /** A keyframe's list: count, then (id gap, record) by ascending id. */
    private static Bytes writeMovers(TreeMap<Integer, Mover> movers, boolean unit){
        var out = new Bytes(movers.size() * 12).varint(movers.size());
        int previous = 0;
        for(var entry : movers.entrySet()){
            out.varint(entry.getKey() - previous);
            writeMover(out, entry.getValue(), unit);
            previous = entry.getKey();
        }
        return out;
    }

    /**
     * A frame's section: removed ids, added records, then one update per surviving mover in ascending id order.
     * Returns false, writing nothing, when nothing was removed or added and every update would be empty; a reader
     * then moves every survivor along its prediction. A bullet has no type or team update, so one whose type or team
     * changed (a deflecting wall, Wall.java:199) is removed and added again under the same id.
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
                if((next.flags & UnitView.AIM) != 0 && (next.aim != was.aim || (was.flags & UnitView.AIM) == 0)) mask |= U_AIM;
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
            if((mask & U_AIM) != 0) updates.u16(next.aim);
            any |= mask != 0;
            next.vx = next.x - was.x;
            next.vy = next.y - was.y;
            next.spin = (short)(next.rotation - was.rotation);
        }
        if(unit) unitState = now;
        else bulletState = now;
        if(!any) return false;

        gaps(out, removed);
        out.varint(added.size());
        int previous = 0;
        for(var entry : added.entrySet()){
            out.varint(entry.getKey() - previous);
            writeMover(out, entry.getValue(), unit);
            previous = entry.getKey();
        }
        out.bytes(updates);
        return true;
    }

    // ---- players and chat ----

    private TreeMap<Integer, PlayerView> readPlayers(){
        var out = new TreeMap<Integer, PlayerView>();
        source.players(view -> out.put(view.id(), new PlayerView(view.id(), cut(view.name()), view.color(), view.team() & 0xFF, view.admin(), view.unit())));
        return out;
    }

    private static void writePlayer(Bytes out, PlayerView player){
        out.str(player.name()).u32(player.color()).u8(player.team()).u8(player.admin() ? 1 : 0).varint(player.unit() + 1);
    }

    private boolean diffPlayers(TreeMap<Integer, PlayerView> now, Bytes out){
        var removed = new ArrayList<Integer>();
        for(int id : players.keySet()) if(!now.containsKey(id)) removed.add(id);
        var added = new TreeMap<Integer, PlayerView>();
        var updated = new TreeMap<Integer, PlayerView>();
        for(var entry : now.entrySet()){
            PlayerView old = players.get(entry.getKey());
            if(old == null) added.put(entry.getKey(), entry.getValue());
            else if(!old.equals(entry.getValue())) updated.put(entry.getKey(), entry.getValue());
        }
        TreeMap<Integer, PlayerView> was = players;
        players = now;
        if(removed.isEmpty() && added.isEmpty() && updated.isEmpty()) return false;
        gaps(out, removed);
        out.varint(added.size());
        int previous = 0;
        for(var entry : added.entrySet()){
            out.varint(entry.getKey() - previous);
            writePlayer(out, entry.getValue());
            previous = entry.getKey();
        }
        out.varint(updated.size());
        previous = 0;
        for(var entry : updated.entrySet()){
            PlayerView old = was.get(entry.getKey()), next = entry.getValue();
            int mask = (next.team() != old.team() ? P_TEAM : 0) | (next.unit() != old.unit() ? P_UNIT : 0) | (!next.name().equals(old.name()) ? P_NAME : 0)
                | (next.color() != old.color() ? P_COLOR : 0) | (next.admin() != old.admin() ? P_FLAGS : 0);
            out.varint(entry.getKey() - previous).u8(mask);
            if((mask & P_TEAM) != 0) out.u8(next.team());
            if((mask & P_UNIT) != 0) out.varint(next.unit() + 1);
            if((mask & P_NAME) != 0) out.str(next.name());
            if((mask & P_COLOR) != 0) out.u32(next.color());
            if((mask & P_FLAGS) != 0) out.u8(next.admin() ? 1 : 0);
            previous = entry.getKey();
        }
        return true;
    }

    private Bytes writeChat(long tick){
        var out = new Bytes(chat.size() * 32).varint(chat.size());
        for(ChatView line : chat) out.varint(line.player() + 1).str(cut(line.text())).varint(Math.max(0, tick - line.tick()));
        chat.clear();
        return out;
    }

    /** {@code text} cut to at most {@link #MAX_TEXT} UTF-8 bytes, on a character boundary. */
    static String cut(String text){
        if(text == null) return "";
        if(text.length() * 3 <= MAX_TEXT) return text;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if(bytes.length <= MAX_TEXT) return text;
        int end = text.length();
        while(text.substring(0, end).getBytes(StandardCharsets.UTF_8).length > MAX_TEXT) end = Character.isLowSurrogate(text.charAt(end - 1)) && end > 1 ? end - 2 : end - 1;
        return text.substring(0, end);
    }

    // ---- plans ----

    /** A plan as encoded: the progress quantized. */
    private record Planned(int tile, int block, int rotation, int progress){
    }

    private TreeMap<Integer, List<Planned>> readPlans(){
        var out = new TreeMap<Integer, List<Planned>>();
        source.plans(view -> {
            if(view.plans().isEmpty()) return;
            var list = new ArrayList<Planned>(Math.min(view.plans().size(), MAX_PLANS));
            for(Plan plan : view.plans()){
                if(list.size() == MAX_PLANS) break;
                list.add(new Planned(plan.tile(), plan.block(), plan.rotation() & 3, q8(plan.progress())));
            }
            out.put(view.unit(), list);
        });
        return out;
    }

    private static void writePlanList(Bytes out, List<Planned> list){
        out.varint(list.size());
        for(Planned plan : list) out.varint(plan.tile()).varint(plan.block() + 1).u8(plan.rotation()).u8(plan.progress());
    }

    private static Bytes writePlans(TreeMap<Integer, List<Planned>> plans){
        var out = new Bytes(plans.size() * 16).varint(plans.size());
        int previous = 0;
        for(var entry : plans.entrySet()){
            out.varint(entry.getKey() - previous);
            writePlanList(out, entry.getValue());
            previous = entry.getKey();
        }
        return out;
    }

    /** The units whose queue changed, each with its whole new queue (empty when it was cleared). */
    private boolean diffPlans(TreeMap<Integer, List<Planned>> now, Bytes out){
        var changed = new TreeMap<Integer, List<Planned>>();
        for(var entry : plans.entrySet()) if(!now.containsKey(entry.getKey())) changed.put(entry.getKey(), List.of());
        for(var entry : now.entrySet()) if(!entry.getValue().equals(plans.get(entry.getKey()))) changed.put(entry.getKey(), entry.getValue());
        plans = now;
        if(changed.isEmpty()) return false;
        out.bytes(writePlans(changed));
        return true;
    }

    // ---- fires and puddles ----

    private TreeMap<Integer, Integer> readFires(){
        var out = new TreeMap<Integer, Integer>();
        source.fires(view -> out.put(view.tile(), q8(view.life())));
        return out;
    }

    private boolean diffFires(TreeMap<Integer, Integer> now, Bytes out){
        var removed = new ArrayList<Integer>();
        for(int tile : fires.keySet()) if(!now.containsKey(tile)) removed.add(tile);
        var added = new TreeMap<Integer, Integer>();
        var updated = new TreeMap<Integer, Integer>();
        for(var entry : now.entrySet()){
            Integer old = fires.get(entry.getKey());
            if(old == null) added.put(entry.getKey(), entry.getValue());
            else if(!old.equals(entry.getValue())) updated.put(entry.getKey(), entry.getValue());
        }
        fires = now;
        if(removed.isEmpty() && added.isEmpty() && updated.isEmpty()) return false;
        gaps(out, removed);
        for(TreeMap<Integer, Integer> list : List.of(added, updated)){
            out.varint(list.size());
            int previous = 0;
            for(var entry : list.entrySet()){
                out.varint(entry.getKey() - previous).u8(entry.getValue());
                previous = entry.getKey();
            }
        }
        return true;
    }

    private TreeMap<Integer, int[]> readPuddles(){
        var out = new TreeMap<Integer, int[]>();
        source.puddles(view -> out.put(view.tile(), new int[]{view.liquid(), q8(view.amount())}));
        return out;
    }

    private boolean diffPuddles(TreeMap<Integer, int[]> now, Bytes out){
        var removed = new ArrayList<Integer>();
        var added = new TreeMap<Integer, int[]>();
        var updated = new TreeMap<Integer, int[]>();
        for(var entry : puddles.entrySet()){
            int[] next = now.get(entry.getKey());
            if(next == null || next[0] != entry.getValue()[0]) removed.add(entry.getKey());
        }
        for(var entry : now.entrySet()){
            int[] old = puddles.get(entry.getKey());
            if(old == null || old[0] != entry.getValue()[0]) added.put(entry.getKey(), entry.getValue());
            else if(old[1] != entry.getValue()[1]) updated.put(entry.getKey(), entry.getValue());
        }
        puddles = now;
        if(removed.isEmpty() && added.isEmpty() && updated.isEmpty()) return false;
        gaps(out, removed);
        out.varint(added.size());
        int previous = 0;
        for(var entry : added.entrySet()){
            out.varint(entry.getKey() - previous).varint(entry.getValue()[0]).u8(entry.getValue()[1]);
            previous = entry.getKey();
        }
        out.varint(updated.size());
        previous = 0;
        for(var entry : updated.entrySet()){
            out.varint(entry.getKey() - previous).u8(entry.getValue()[1]);
            previous = entry.getKey();
        }
        return true;
    }

    // ---- rebuild plans ----

    /** Each tile's plan: team, block, rotation. */
    private TreeMap<Integer, List<Integer>> readRebuild(){
        var out = new TreeMap<Integer, List<Integer>>();
        source.rebuild(view -> out.put(view.tile(), List.of(view.team() & 0xFF, view.block(), view.rotation() & 3)));
        return out;
    }

    private static void writeRebuild(Bytes out, List<Integer> plan){
        out.u8(plan.get(0)).varint(plan.get(1)).u8(plan.get(2));
    }

    /** Removed tiles, then added plans; a plan that changed on its tile is removed and added again. */
    private boolean diffRebuild(TreeMap<Integer, List<Integer>> now, Bytes out){
        var removed = new ArrayList<Integer>();
        for(var entry : rebuild.entrySet()) if(!entry.getValue().equals(now.get(entry.getKey()))) removed.add(entry.getKey());
        var added = new TreeMap<Integer, List<Integer>>();
        for(var entry : now.entrySet()) if(!entry.getValue().equals(rebuild.get(entry.getKey()))) added.put(entry.getKey(), entry.getValue());
        rebuild = now;
        if(removed.isEmpty() && added.isEmpty()) return false;
        gaps(out, removed);
        out.varint(added.size());
        int previous = 0;
        for(var entry : added.entrySet()){
            out.varint(entry.getKey() - previous);
            writeRebuild(out, entry.getValue());
            previous = entry.getKey();
        }
        return true;
    }

    // ---- events and status ----

    private Bytes writeEvents(long tick){
        var out = new Bytes(events.size() * 12).varint(events.size());
        for(ViewEvent event : events){
            int kind = EFFECTS.indexOf(event.kind());
            if(kind < 0) throw new IllegalArgumentException("unknown event kind " + event.kind());
            out.varint(kind).u8(event.team() & 0xFF).svarint(qpos(event.x())).svarint(qpos(event.y())).u8(qrot(event.rotation()) >>> 8)
                .varint(event.content() + 1).varint(Math.max(0, Math.round(event.size() * 4f))).varint(Math.max(0, tick - event.tick()));
        }
        events.clear();
        return out;
    }

    private int[] readStatus(){
        Status now = source.status();
        return new int[]{Math.max(0, now.wave()), Math.max(0, Math.round(now.countdown())), Math.max(0, now.enemies())};
    }

    /** A gap list of {@code values}, ascending: count, then differences from 0. */
    private static void gaps(Bytes out, List<Integer> values){
        out.varint(values.size());
        int previous = 0;
        for(int value : values){
            out.varint(value - previous);
            previous = value;
        }
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
}
