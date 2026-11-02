package com.xpdustry.shard.tools.replay.view;

import tools.jackson.databind.*;
import tools.jackson.databind.json.*;

import java.util.*;
import java.util.function.*;
import java.util.zip.*;

import static com.xpdustry.shard.tools.replay.view.ViewWriter.*;

/**
 * The reference decoder of the view stream: what {@link ViewWriter} writes, read back into the quantized state a
 * viewer draws. The browser viewer's decoder follows it; the round-trip tests hold both to the writer.
 *
 * <p>It reads untrusted files. Every length, count, index and gap is checked before use, inflating stops at
 * {@link #MAX_INFLATED}, and segments are inflated only when played (FORMAT.md, "Limits").
 */
public final class ViewReader{
    /** The largest map a reader accepts, in tiles, and the most bytes one terrain or segment may inflate to. */
    public static final int MAX_TILES = 4096 * 4096, MAX_INFLATED = 64 << 20;

    public final JsonNode header;
    public final int width, height;
    public final List<String> blocks, units, effects, players;
    private final int styles;
    private final int[] terrainFloor, terrainOverlay, terrainEnv;
    private final List<byte[]> segments = new ArrayList<>();

    /** A building as decoded; fractions and angles stay quantized (1/255, 1/65536 of a turn). */
    public record Build(int block, int team, int rotation, int health, int flags, int aim, int target, int progress){
    }

    /** A unit (type = unit name index) or bullet (type = bullet style index), quantized; x and y in 1/8 world units. */
    public static final class Mover{
        public int type, team, x, y, vx, vy, rotation, spin, health, elevation, flags, player, age;

        Mover copy(){
            var out = new Mover();
            out.type = type;
            out.team = team;
            out.x = x;
            out.y = y;
            out.vx = vx;
            out.vy = vy;
            out.rotation = rotation;
            out.spin = spin;
            out.health = health;
            out.elevation = elevation;
            out.flags = flags;
            out.player = player;
            out.age = age;
            return out;
        }
    }

    public record Event(int kind, int team, int x, int y, int rotation, int content, int size){
    }

    /** The world at one frame. */
    public static final class State{
        public long tick;
        public int[] floor, overlay, env;
        public TreeMap<Integer, Build> builds = new TreeMap<>();
        public TreeMap<Integer, Mover> units = new TreeMap<>(), bullets = new TreeMap<>();
        public List<Event> events = new ArrayList<>();
        public int wave, countdown, enemies;
    }

    public ViewReader(byte[] file){
        var in = new Bytes.In(file);
        byte[] magic = in.bytes(MAGIC.length);
        if(!Arrays.equals(magic, MAGIC)) throw new IllegalArgumentException("not a view stream");
        int format = in.u8();
        if(format != FORMAT) throw new IllegalArgumentException("view stream format " + format + ", this reader reads " + FORMAT);
        byte[] json = in.bytes(in.length());
        header = JsonMapper.builder().build().readTree(json);
        if(header.path("format").asInt(-1) != format) throw new IllegalArgumentException("the header says format " + header.path("format") + ", the file " + format);
        width = header.path("width").asInt(0);
        height = header.path("height").asInt(0);
        if(width <= 0 || height <= 0 || (long)width * height > MAX_TILES) throw new IllegalArgumentException("a map of " + width + "x" + height);
        blocks = strings("blocks");
        units = strings("units");
        effects = strings("effects");
        players = strings("players");
        styles = header.path("bullets").size();

        var terrain = new Bytes.In(inflate(in.bytes(in.length())));
        int tiles = width * height;
        terrainFloor = runs(terrain, tiles, blocks.size());
        terrainOverlay = runs(terrain, tiles, blocks.size());
        terrainEnv = runs(terrain, tiles, blocks.size());
        if(terrain.more()) throw new IllegalArgumentException("bytes after the terrain");
        while(in.more()) segments.add(in.bytes(in.length()));
        if(segments.size() != header.path("segments").size()) throw new IllegalArgumentException("the index lists " + header.path("segments").size() + " segments, the file has " + segments.size());
    }

    private List<String> strings(String field){
        var out = new ArrayList<String>();
        header.path(field).forEach(node -> out.add(node.asString()));
        return List.copyOf(out);
    }

    public int segments(){
        return segments.size();
    }

    /** Plays segment {@code index} from its keyframe; {@code frame} sees the state after each frame (the same object). */
    public void play(int index, Consumer<State> frame){
        var in = new Bytes.In(inflate(segments.get(index)));
        var state = new State();
        if(!in.more() || in.u8() != KEYFRAME) throw new IllegalArgumentException("segment " + index + " does not start with a keyframe");
        keyframe(in, state);
        frame.accept(state);
        while(in.more()){
            int tag = in.u8();
            if(tag != FRAME) throw new IllegalArgumentException("frame tag " + tag + " at byte " + (in.position() - 1) + " of segment " + index);
            frame(in, state);
            frame.accept(state);
        }
    }

    /** Plays every segment in order. */
    public void playAll(Consumer<State> frame){
        for(int i = 0; i < segments.size(); i++) play(i, frame);
    }

    private void keyframe(Bytes.In in, State state){
        state.tick = in.varint();
        if(state.tick < 0 || state.tick > Integer.MAX_VALUE) throw new IllegalArgumentException("keyframe tick " + Long.toUnsignedString(state.tick));
        state.floor = terrainFloor.clone();
        state.overlay = terrainOverlay.clone();
        state.env = terrainEnv.clone();
        tiles(in, state);
        state.builds.clear();
        int count = in.count();
        var tile = new Gaps(width * height);
        for(int i = 0; i < count; i++) state.builds.put(tile.next(in), build(in));
        state.units = movers(in, true);
        state.bullets = movers(in, false);
        state.events = new ArrayList<>();
        status(in, state);
    }

    private void frame(Bytes.In in, State state){
        long dtick = in.varint();
        if(dtick <= 0 || dtick > Integer.MAX_VALUE - state.tick) throw new IllegalArgumentException("a frame " + Long.toUnsignedString(dtick) + " ticks on");
        state.tick += dtick;
        int mask = in.u8();
        if((mask & ~(ENV | BUILDS | UNITS | BULLETS | EVENTS | STATUS)) != 0) throw new IllegalArgumentException("unknown frame sections " + mask);
        if((mask & ENV) != 0) tiles(in, state);
        if((mask & BUILDS) != 0) builds(in, state);
        state.units = diff(in, state.units, (mask & UNITS) != 0, true);
        state.bullets = diff(in, state.bullets, (mask & BULLETS) != 0, false);
        state.events = new ArrayList<>();
        if((mask & EVENTS) != 0) events(in, state);
        if((mask & STATUS) != 0) status(in, state);
    }

    private static void status(Bytes.In in, State state){
        state.wave = in.varintInt();
        state.countdown = in.varintInt();
        state.enemies = in.varintInt();
    }

    /** Ascending gaps from 0: the first may be 0, every later one at least 1, and the sum stays below {@code limit}. */
    private static final class Gaps{
        final long limit;
        long at = -1;

        Gaps(long limit){
            this.limit = limit;
        }

        int next(Bytes.In in){
            long gap = in.varint();
            if(gap < 0 || (at >= 0 && gap == 0) || gap > limit - Math.max(at, 0)) throw new IllegalArgumentException("a gap of " + Long.toUnsignedString(gap) + " after " + at);
            at = Math.max(at, 0) + gap;
            if(at >= limit) throw new IllegalArgumentException("an index of " + at + ", limit " + limit);
            return (int)at;
        }
    }

    private static int index(Bytes.In in, int size, String what){
        long value = in.varint();
        if(value < 0 || value >= size) throw new IllegalArgumentException(what + " " + Long.toUnsignedString(value) + " outside a table of " + size);
        return (int)value;
    }

    private void tiles(Bytes.In in, State state){
        int count = in.count();
        var tile = new Gaps(width * height);
        for(int i = 0; i < count; i++){
            int at = tile.next(in);
            state.floor[at] = index(in, blocks.size(), "block");
            state.overlay[at] = index(in, blocks.size(), "block");
            state.env[at] = index(in, blocks.size(), "block");
        }
    }

    private Build build(Bytes.In in){
        int block = index(in, blocks.size(), "block"), team = in.u8(), rotation = in.u8(), health = in.u8(), flags = in.u8();
        if(rotation > 3) throw new IllegalArgumentException("building rotation " + rotation);
        int aim = (flags & ViewSource.BuildView.AIM) != 0 ? in.u16() : 0;
        int target = 0, progress = 0;
        if((flags & (ViewSource.BuildView.CONSTRUCT | ViewSource.BuildView.DECONSTRUCT)) != 0){
            target = index(in, blocks.size() + 1, "construction target");
            progress = in.u8();
        }
        return new Build(block, team, rotation, health, flags, aim, target, progress);
    }

    private void builds(Bytes.In in, State state){
        int tiles = width * height;
        int removed = in.count();
        var tile = new Gaps(tiles);
        for(int i = 0; i < removed; i++){
            int at = tile.next(in);
            if(state.builds.remove(at) == null) throw new IllegalArgumentException("a removal at tile " + at + ", which has no building");
        }
        int added = in.count();
        tile = new Gaps(tiles);
        for(int i = 0; i < added; i++){
            int at = tile.next(in);
            if(state.builds.containsKey(at)) throw new IllegalArgumentException("an addition at tile " + at + ", which has a building");
            state.builds.put(at, build(in));
        }
        int updated = in.count();
        tile = new Gaps(tiles);
        for(int i = 0; i < updated; i++){
            int at = tile.next(in);
            Build old = state.builds.get(at);
            if(old == null) throw new IllegalArgumentException("an update for tile " + at + ", which has no building");
            int mask = in.u8();
            if((mask & ~(B_HEALTH | B_ROTATION | B_FLAGS | B_AIM | B_PROGRESS)) != 0) throw new IllegalArgumentException("unknown building fields " + mask);
            int health = (mask & B_HEALTH) != 0 ? in.u8() : old.health();
            int rotation = (mask & B_ROTATION) != 0 ? in.u8() : old.rotation();
            if(rotation > 3) throw new IllegalArgumentException("building rotation " + rotation);
            int flags = (mask & B_FLAGS) != 0 ? in.u8() : old.flags();
            int aim = (mask & B_AIM) != 0 ? in.u16() : old.aim();
            int target = old.target(), progress = old.progress();
            if((mask & B_PROGRESS) != 0){
                target = index(in, blocks.size() + 1, "construction target");
                progress = in.u8();
            }
            state.builds.put(at, new Build(old.block(), old.team(), rotation, health, flags, aim, target, progress));
        }
    }

    private Mover mover(Bytes.In in, boolean unit){
        var out = new Mover();
        out.type = unit ? index(in, units.size(), "unit type") : index(in, styles, "bullet style");
        out.team = in.u8();
        out.x = in.svarintInt();
        out.y = in.svarintInt();
        out.rotation = in.u16();
        if(unit){
            out.health = in.u8();
            out.elevation = in.u8();
            out.flags = in.u8();
            if((out.flags & PLAYER) != 0) out.player = index(in, players.size(), "player");
        }else{
            out.age = in.u8();
        }
        return out;
    }

    private TreeMap<Integer, Mover> movers(Bytes.In in, boolean unit){
        var out = new TreeMap<Integer, Mover>();
        int count = in.count();
        var id = new Gaps(1L << 31);
        for(int i = 0; i < count; i++) out.put(id.next(in), mover(in, unit));
        return out;
    }

    private TreeMap<Integer, Mover> diff(Bytes.In in, TreeMap<Integer, Mover> old, boolean present, boolean unit){
        var removed = new HashSet<Integer>();
        var added = new TreeMap<Integer, Mover>();
        if(present){
            int count = in.count();
            var id = new Gaps(1L << 31);
            for(int i = 0; i < count; i++){
                int at = id.next(in);
                if(!old.containsKey(at)) throw new IllegalArgumentException("a removal of mover " + at + ", which does not exist");
                removed.add(at);
            }
            count = in.count();
            id = new Gaps(1L << 31);
            for(int i = 0; i < count; i++){
                int at = id.next(in);
                // the same id may be removed and added in one frame (a bullet changing team), never added over a survivor
                if(old.containsKey(at) && !removed.contains(at)) throw new IllegalArgumentException("an addition of mover " + at + ", which survives");
                added.put(at, mover(in, unit));
            }
        }
        int known = unit ? U_POS | U_ROT | U_HEALTH | U_ELEVATION | U_FLAGS | U_TYPE : U_POS | U_ROT;
        var now = new TreeMap<Integer, Mover>();
        for(var entry : old.entrySet()){
            if(removed.contains(entry.getKey())) continue;
            Mover was = entry.getValue(), next = was.copy();
            int mask = present ? in.u8() : 0;
            if((mask & ~known) != 0) throw new IllegalArgumentException("unknown mover fields " + mask);
            next.x = Math.addExact(was.x, was.vx);
            next.y = Math.addExact(was.y, was.vy);
            if((mask & U_POS) != 0){
                next.x = Math.addExact(next.x, in.svarintInt());
                next.y = Math.addExact(next.y, in.svarintInt());
            }
            next.rotation = (was.rotation + was.spin) & 0xFFFF;
            if((mask & U_ROT) != 0) next.rotation = (next.rotation + in.svarintInt()) & 0xFFFF;
            if(unit){
                if((mask & U_HEALTH) != 0) next.health = in.u8();
                if((mask & U_ELEVATION) != 0) next.elevation = in.u8();
                if((mask & U_FLAGS) != 0){
                    next.flags = in.u8();
                    next.player = (next.flags & PLAYER) != 0 ? index(in, players.size(), "player") : 0;
                }
                if((mask & U_TYPE) != 0){
                    next.type = index(in, units.size(), "unit type");
                    next.team = in.u8();
                }
            }
            next.vx = Math.subtractExact(next.x, was.x);
            next.vy = Math.subtractExact(next.y, was.y);
            next.spin = (short)(next.rotation - was.rotation);
            now.put(entry.getKey(), next);
        }
        now.putAll(added);
        return now;
    }

    private void events(Bytes.In in, State state){
        state.events = new ArrayList<>();
        int count = in.count();
        for(int i = 0; i < count; i++){
            int kind = index(in, effects.size(), "event kind"), team = in.u8(), x = in.svarintInt(), y = in.svarintInt(), rotation = in.u8();
            int content = index(in, (effects.get(kind).startsWith("unit") ? units.size() : blocks.size()) + 1, "event content");
            state.events.add(new Event(kind, team, x, y, rotation, content, in.varintInt()));
        }
    }

    private static int[] runs(Bytes.In in, int tiles, int names){
        int[] out = new int[tiles];
        int count = in.count(), at = 0;
        for(int i = 0; i < count; i++){
            long length = in.varint(), value = in.varint();
            if(length <= 0 || length > tiles - at || value < 0 || value >= names) throw new IllegalArgumentException("a terrain run of " + length + " at tile " + at);
            Arrays.fill(out, at, at + (int)length, (int)value);
            at += (int)length;
        }
        if(at != tiles) throw new IllegalArgumentException("terrain runs cover " + at + " of " + tiles + " tiles");
        return out;
    }

    /** Raw deflate, stopping at {@link #MAX_INFLATED} bytes. */
    private static byte[] inflate(byte[] raw){
        var inflater = new Inflater(true);
        inflater.setInput(raw);
        var out = new Bytes(Math.min(raw.length * 4, MAX_INFLATED));
        byte[] buffer = new byte[1 << 16];
        long total = 0;
        try{
            while(!inflater.finished()){
                int n = inflater.inflate(buffer);
                if(n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw new IllegalArgumentException("a truncated deflate stream");
                total += n;
                if(total > MAX_INFLATED) throw new IllegalArgumentException("a deflate stream inflating past " + MAX_INFLATED + " bytes");
                out.bytes(Arrays.copyOf(buffer, n));
            }
            if(inflater.getRemaining() > 0) throw new IllegalArgumentException("bytes after a deflate stream");
        }catch(DataFormatException e){
            throw new IllegalArgumentException("a damaged deflate stream: " + e.getMessage(), e);
        }finally{
            inflater.end();
        }
        return out.toArray();
    }
}
