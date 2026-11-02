package com.xpdustry.shard.server.replay.view;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Decodes format 2 segments ({@code docs/replay/FORMAT.md}) into the state after each record: the reference decoder that
 * {@link ViewWriter} is tested against and the browser's {@code stream.ts} is compared with. It checks what
 * {@code FORMAT.md}'s "Limits" ask: lengths and counts against the bytes left, indices against the header's tables,
 * tiles inside the map, inflation capped. Sections of a type it doesn't know are skipped.
 */
public final class ViewReader{
    /** Inflating a segment or the terrain stops here. */
    public static final int MAX_INFLATED = 64 << 20;

    private final ViewHeader header;
    private final int tiles;

    public ViewReader(ViewHeader header){
        if(header.width() < 1 || header.height() < 1 || (long)header.width() * header.height() > 4096L * 4096L){
            throw new IllegalArgumentException("a map of " + header.width() + "×" + header.height());
        }
        this.header = header;
        this.tiles = header.width() * header.height();
    }

    /** The world after a record. Maps are by tile or id, ascending. */
    public static final class State{
        public long tick;
        public boolean keyframe;
        public final TreeMap<Integer, int[]> env = new TreeMap<>();
        /** block, team, rotation, health, flags, aim, target+1, progress */
        public final TreeMap<Integer, int[]> buildings = new TreeMap<>();
        /** type, team, x, y, rotation, health, elevation, flags, player, aim; then vx, vy, spin */
        public final TreeMap<Integer, int[]> units = new TreeMap<>();
        /** type, team, x, y, rotation, age at its record; then vx, vy, spin, the tick it was sent */
        public final TreeMap<Integer, long[]> bullets = new TreeMap<>();
        public final TreeMap<Integer, Player> players = new TreeMap<>();
        /** per unit: tile, block+1, rotation, progress, four ints a plan */
        public final TreeMap<Integer, int[]> plans = new TreeMap<>();
        public final TreeMap<Integer, Integer> fires = new TreeMap<>();
        /** liquid, amount */
        public final TreeMap<Integer, int[]> puddles = new TreeMap<>();
        /** team, block, rotation */
        public final TreeMap<Integer, int[]> rebuild = new TreeMap<>();
        public int[] status = {0, 0, 0};
        /** kind, team, x, y, rotation, content+1, size×4, age */
        public final List<int[]> events = new ArrayList<>();
        public final List<Chat> chat = new ArrayList<>();
    }

    public record Player(String name, int color, int team, int flags, int unit){
    }

    public record Chat(int player, String text, int age){
    }

    /** Inflates a deflate-raw chunk, at most {@link #MAX_INFLATED} bytes. */
    public static byte[] inflate(byte[] deflated){
        var inflater = new Inflater(true);
        try{
            inflater.setInput(deflated);
            var out = new ByteArrayOutputStream(deflated.length * 4);
            byte[] buffer = new byte[1 << 16];
            while(!inflater.finished()){
                int n = inflater.inflate(buffer);
                if(n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw new IllegalArgumentException("a truncated deflate stream");
                if(out.size() + n > MAX_INFLATED) throw new IllegalArgumentException("a chunk inflates past " + MAX_INFLATED + " bytes");
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }catch(DataFormatException e){
            throw new IllegalArgumentException("bad deflate data: " + e.getMessage(), e);
        }finally{
            inflater.end();
        }
    }

    /**
     * The states after each record of an inflated segment, given the state the previous segment ended in (null for
     * the first segment, or when seeking: a keyframe resets everything).
     */
    public List<State> segment(byte[] raw){
        var in = new Bytes.In(raw);
        var out = new ArrayList<State>();
        State state = null;
        boolean first = true;
        while(in.more()){
            int kind = in.u8();
            if(first && kind != ViewWriter.KEYFRAME) throw new IllegalArgumentException("a segment starts with a keyframe");
            if(!first && kind != ViewWriter.FRAME) throw new IllegalArgumentException("a second keyframe at " + (in.position() - 1));
            State next = new State();
            if(first){
                next.keyframe = true;
                next.tick = tick(in.varint());
            }else{
                long dtick = in.varint();
                if(dtick < 1) throw new IllegalArgumentException("a frame " + dtick + " ticks after the last");
                copy(state, next);
                next.tick = tick(state.tick + dtick);
            }
            int sections = in.count();
            int lastType = 0;
            boolean units = false, bullets = false;
            for(int i = 0; i < sections; i++){
                int type = in.varintInt();
                if(type <= lastType) throw new IllegalArgumentException("section " + type + " after " + lastType);
                lastType = type;
                units |= type == ViewWriter.UNITS;
                bullets |= type == ViewWriter.BULLETS;
                int length = in.count();
                var body = new Bytes.In(in.bytes(length));
                if(first) keyframeSection(type, body, next);
                else frameSection(type, body, state, next);
                if(type <= ViewWriter.REBUILD && body.more()) throw new IllegalArgumentException("section " + type + " has " + body.remaining() + " bytes left");
            }
            if(!first){
                // a missing section: no removals or additions, and every survivor moves to its prediction
                if(!units) for(int[] u : next.units.values()) move(u, 0, null, 2, 4, 10);
                if(!bullets){
                    for(long[] b : next.bullets.values()){
                        int[] m = {0, 0, (int)b[2], (int)b[3], (int)b[4], 0, 0, 0, 0, 0, (int)b[6], (int)b[7], (int)b[8]};
                        move(m, 0, null, 2, 4, 10);
                        b[2] = m[2];
                        b[3] = m[3];
                        b[4] = m[4];
                    }
                }
            }
            out.add(next);
            state = next;
            first = false;
        }
        if(out.isEmpty()) throw new IllegalArgumentException("an empty segment");
        return out;
    }

    private long tick(long tick){
        if(tick < 0 || tick > Integer.MAX_VALUE) throw new IllegalArgumentException("tick " + tick);
        return tick;
    }

    private static void copy(State from, State to){
        from.env.forEach((k, v) -> to.env.put(k, v));
        from.buildings.forEach((k, v) -> to.buildings.put(k, v));
        from.units.forEach((k, v) -> to.units.put(k, v.clone()));
        from.bullets.forEach((k, v) -> to.bullets.put(k, v.clone()));
        to.players.putAll(from.players);
        to.plans.putAll(from.plans);
        to.fires.putAll(from.fires);
        to.puddles.putAll(from.puddles);
        to.rebuild.putAll(from.rebuild);
        to.status = from.status;
    }

    // ---- keyframes ----

    private void keyframeSection(int type, Bytes.In in, State state){
        switch(type){
            case ViewWriter.ENV -> env(in, state);
            case ViewWriter.BUILDINGS -> {
                int n = in.count();
                int tile = 0;
                for(int i = 0; i < n; i++){
                    tile = tile(tile, in.varintInt(), i);
                    state.buildings.put(tile, building(in));
                }
            }
            case ViewWriter.UNITS -> {
                int n = in.count(), id = 0;
                for(int i = 0; i < n; i++){
                    id = gap(id, in.varintInt(), i);
                    state.units.put(id, unit(in));
                }
            }
            case ViewWriter.BULLETS -> {
                int n = in.count(), id = 0;
                for(int i = 0; i < n; i++){
                    id = gap(id, in.varintInt(), i);
                    state.bullets.put(id, bullet(in, state.tick));
                }
            }
            case ViewWriter.STATUS -> state.status = new int[]{in.varintInt(), in.varintInt(), in.varintInt()};
            case ViewWriter.PLAYERS -> {
                int n = in.count(), id = 0;
                for(int i = 0; i < n; i++){
                    id = gap(id, in.varintInt(), i);
                    state.players.put(id, player(in));
                }
            }
            case ViewWriter.PLANS -> plans(in, state);
            case ViewWriter.FIRES -> {
                int n = in.count(), tile = 0;
                for(int i = 0; i < n; i++){
                    tile = tile(tile, in.varintInt(), i);
                    state.fires.put(tile, in.u8());
                }
            }
            case ViewWriter.PUDDLES -> {
                int n = in.count(), tile = 0;
                for(int i = 0; i < n; i++){
                    tile = tile(tile, in.varintInt(), i);
                    state.puddles.put(tile, new int[]{index(in.varintInt(), header.liquids().size(), "liquid"), in.u8()});
                }
            }
            case ViewWriter.REBUILD -> rebuildPlans(in, state, false);
            case ViewWriter.EVENTS, ViewWriter.CHAT -> throw new IllegalArgumentException("a keyframe carries no section " + type);
            default -> {
                // a later format's section: skipped
            }
        }
    }

    // ---- frames ----

    private void frameSection(int type, Bytes.In in, State before, State state){
        switch(type){
            case ViewWriter.ENV -> env(in, state);
            case ViewWriter.BUILDINGS -> {
                for(int tile : removals(in, state.buildings.keySet())) state.buildings.remove(tile);
                int n = in.count(), tile = 0;
                for(int i = 0; i < n; i++){
                    tile = tile(tile, in.varintInt(), i);
                    if(state.buildings.containsKey(tile)) throw new IllegalArgumentException("building " + tile + " added twice");
                    state.buildings.put(tile, building(in));
                }
                n = in.count();
                tile = 0;
                for(int i = 0; i < n; i++){
                    tile = tile(tile, in.varintInt(), i);
                    int[] old = state.buildings.get(tile);
                    if(old == null) throw new IllegalArgumentException("an update of no building at " + tile);
                    int[] b = old.clone();
                    int mask = in.u8();
                    if((mask & ~31) != 0) throw new IllegalArgumentException("building mask " + mask);
                    if((mask & ViewWriter.B_HEALTH) != 0) b[3] = in.u8();
                    if((mask & ViewWriter.B_ROTATION) != 0) b[2] = in.u8();
                    if((mask & ViewWriter.B_FLAGS) != 0) b[4] = in.u8();
                    if((mask & ViewWriter.B_AIM) != 0) b[5] = in.u16();
                    if((mask & ViewWriter.B_PROGRESS) != 0){
                        b[6] = index(in.varintInt(), header.blocks().size() + 1, "block");
                        b[7] = in.u8();
                    }
                    state.buildings.put(tile, b);
                }
            }
            case ViewWriter.UNITS -> movers(in, before, state, true);
            case ViewWriter.BULLETS -> movers(in, before, state, false);
            case ViewWriter.EVENTS -> {
                int n = in.count();
                for(int i = 0; i < n; i++){
                    int kind = index(in.varintInt(), ViewWriter.EFFECTS.size(), "effect");
                    int team = in.u8(), x = in.svarintInt(), y = in.svarintInt(), rotation = in.u8();
                    int content = in.varintInt();
                    boolean unit = ViewWriter.EFFECTS.get(kind).startsWith("unit");
                    if(content > 0) index(content - 1, unit ? header.units().size() : header.blocks().size(), "content");
                    state.events.add(new int[]{kind, team, x, y, rotation, content, in.varintInt(), in.varintInt()});
                }
            }
            case ViewWriter.STATUS -> state.status = new int[]{in.varintInt(), in.varintInt(), in.varintInt()};
            case ViewWriter.PLAYERS -> {
                for(int id : removals(in, state.players.keySet())) state.players.remove(id);
                int n = in.count(), id = 0;
                for(int i = 0; i < n; i++){
                    id = gap(id, in.varintInt(), i);
                    if(state.players.containsKey(id)) throw new IllegalArgumentException("player " + id + " added twice");
                    state.players.put(id, player(in));
                }
                n = in.count();
                id = 0;
                for(int i = 0; i < n; i++){
                    id = gap(id, in.varintInt(), i);
                    Player old = state.players.get(id);
                    if(old == null) throw new IllegalArgumentException("an update of no player " + id);
                    int mask = in.u8();
                    if((mask & ~31) != 0) throw new IllegalArgumentException("player mask " + mask);
                    int team = (mask & ViewWriter.P_TEAM) != 0 ? in.u8() : old.team();
                    int unit = (mask & ViewWriter.P_UNIT) != 0 ? in.varintInt() - 1 : old.unit();
                    String name = (mask & ViewWriter.P_NAME) != 0 ? in.str(ViewWriter.MAX_TEXT) : old.name();
                    int color = (mask & ViewWriter.P_COLOR) != 0 ? in.u32() : old.color();
                    int flags = (mask & ViewWriter.P_FLAGS) != 0 ? in.u8() : old.flags();
                    state.players.put(id, new Player(name, color, team, flags, unit));
                }
            }
            case ViewWriter.CHAT -> {
                int n = in.count();
                for(int i = 0; i < n; i++) state.chat.add(new Chat(in.varintInt() - 1, in.str(ViewWriter.MAX_TEXT), in.varintInt()));
            }
            case ViewWriter.PLANS -> plans(in, state);
            case ViewWriter.FIRES -> {
                for(int tile : removals(in, state.fires.keySet())) state.fires.remove(tile);
                for(int list = 0; list < 2; list++){
                    int n = in.count(), tile = 0;
                    for(int i = 0; i < n; i++){
                        tile = tile(tile, in.varintInt(), i);
                        if((list == 0) == state.fires.containsKey(tile)) throw new IllegalArgumentException("fire " + tile + (list == 0 ? " added twice" : " updated but absent"));
                        state.fires.put(tile, in.u8());
                    }
                }
            }
            case ViewWriter.PUDDLES -> {
                for(int tile : removals(in, state.puddles.keySet())) state.puddles.remove(tile);
                int n = in.count(), tile = 0;
                for(int i = 0; i < n; i++){
                    tile = tile(tile, in.varintInt(), i);
                    if(state.puddles.containsKey(tile)) throw new IllegalArgumentException("puddle " + tile + " added twice");
                    state.puddles.put(tile, new int[]{index(in.varintInt(), header.liquids().size(), "liquid"), in.u8()});
                }
                n = in.count();
                tile = 0;
                for(int i = 0; i < n; i++){
                    tile = tile(tile, in.varintInt(), i);
                    int[] old = state.puddles.get(tile);
                    if(old == null) throw new IllegalArgumentException("an update of no puddle at " + tile);
                    state.puddles.put(tile, new int[]{old[0], in.u8()});
                }
            }
            case ViewWriter.REBUILD -> {
                for(int tile : removals(in, state.rebuild.keySet())) state.rebuild.remove(tile);
                rebuildPlans(in, state, true);
            }
            default -> {
                // a later format's section: skipped
            }
        }
    }

    private void env(Bytes.In in, State state){
        int n = in.count(), tile = 0;
        for(int i = 0; i < n; i++){
            tile = tile(tile, in.varintInt(), i);
            state.env.put(tile, new int[]{block(in), block(in), block(in)});
        }
    }

    /** A list of rebuild plans: count, then (tile gap, u8 team, varint block, u8 rotation). */
    private void rebuildPlans(Bytes.In in, State state, boolean frame){
        int n = in.count(), tile = 0;
        for(int i = 0; i < n; i++){
            tile = tile(tile, in.varintInt(), i);
            if(frame && state.rebuild.containsKey(tile)) throw new IllegalArgumentException("rebuild plan " + tile + " added twice");
            state.rebuild.put(tile, new int[]{in.u8(), block(in), in.u8()});
        }
    }

    private void plans(Bytes.In in, State state){
        int n = in.count(), unit = 0;
        for(int i = 0; i < n; i++){
            unit = gap(unit, in.varintInt(), i);
            int count = in.count();
            if(count > ViewWriter.MAX_PLANS) throw new IllegalArgumentException(count + " plans");
            var plans = new int[count * 4];
            for(int p = 0; p < count; p++){
                plans[p * 4] = index(in.varintInt(), tiles, "tile");
                plans[p * 4 + 1] = index(in.varintInt(), header.blocks().size() + 1, "block");
                plans[p * 4 + 2] = in.u8();
                plans[p * 4 + 3] = in.u8();
            }
            if(count == 0) state.plans.remove(unit);
            else state.plans.put(unit, plans);
        }
    }

    private List<Integer> removals(Bytes.In in, Set<Integer> live){
        int n = in.count(), at = 0;
        var out = new ArrayList<Integer>(n);
        for(int i = 0; i < n; i++){
            at = gap(at, in.varintInt(), i);
            if(!live.contains(at)) throw new IllegalArgumentException("a removal of " + at + ", which isn't there");
            out.add(at);
        }
        return out;
    }

    private void movers(Bytes.In in, State before, State state, boolean unit){
        for(int id : removals(in, unit ? state.units.keySet() : state.bullets.keySet())){
            if(unit) state.units.remove(id);
            else state.bullets.remove(id);
        }
        var added = new TreeSet<Integer>();
        int n = in.count(), id = 0;
        for(int i = 0; i < n; i++){
            id = gap(id, in.varintInt(), i);
            if(unit ? state.units.containsKey(id) : state.bullets.containsKey(id)) throw new IllegalArgumentException("mover " + id + " added twice");
            if(unit) state.units.put(id, unit(in));
            else state.bullets.put(id, bullet(in, state.tick));
            added.add(id);
        }
        // one update per survivor of the previous frame, ascending
        for(int survivor : unit ? before.units.keySet() : before.bullets.keySet()){
            if(added.contains(survivor) || !(unit ? state.units.containsKey(survivor) : state.bullets.containsKey(survivor))) continue;
            int mask = in.u8();
            if(unit){
                if((mask & ~127) != 0) throw new IllegalArgumentException("unit mask " + mask);
                int[] u = state.units.get(survivor);
                move(u, mask, in, 2, 4, 10);
                if((mask & ViewWriter.U_HEALTH) != 0) u[5] = in.u8();
                if((mask & ViewWriter.U_ELEVATION) != 0) u[6] = in.u8();
                if((mask & ViewWriter.U_FLAGS) != 0){
                    u[7] = in.u8();
                    u[8] = (u[7] & ViewWriter.PLAYER) != 0 ? in.varintInt() : -1;
                }
                if((mask & ViewWriter.U_TYPE) != 0){
                    u[0] = index(in.varintInt(), header.units().size(), "unit");
                    u[1] = in.u8();
                }
                if((mask & ViewWriter.U_AIM) != 0) u[9] = in.u16();
            }else{
                if((mask & ~3) != 0) throw new IllegalArgumentException("bullet mask " + mask);
                long[] b = state.bullets.get(survivor);
                int[] m = {0, 0, (int)b[2], (int)b[3], (int)b[4], 0, 0, 0, 0, 0, (int)b[6], (int)b[7], (int)b[8]};
                move(m, mask, in, 2, 4, 10);
                b[2] = m[2];
                b[3] = m[3];
                b[4] = m[4];
                b[6] = m[10];
                b[7] = m[11];
                b[8] = m[12];
            }
        }
    }

    /** Applies a mover's position and rotation update, or its prediction, and keeps its last step. */
    private static void move(int[] m, int mask, Bytes.In in, int pos, int rot, int vel){
        int px = m[pos] + m[vel], py = m[pos + 1] + m[vel + 1], pr = (m[rot] + m[vel + 2]) & 0xFFFF;
        int x = px, y = py, r = pr;
        if((mask & ViewWriter.U_POS) != 0){
            x += in.svarintInt();
            y += in.svarintInt();
        }
        if((mask & ViewWriter.U_ROT) != 0) r = (r + in.svarintInt()) & 0xFFFF;
        m[vel] = x - m[pos];
        m[vel + 1] = y - m[pos + 1];
        m[vel + 2] = (short)(r - m[rot]);
        m[pos] = x;
        m[pos + 1] = y;
        m[rot] = r;
    }

    private int[] building(Bytes.In in){
        int block = block(in), team = in.u8(), rotation = in.u8(), health = in.u8(), flags = in.u8();
        int aim = (flags & ViewSource.BuildView.AIM) != 0 ? in.u16() : 0;
        int target = 0, progress = 0;
        if((flags & (ViewSource.BuildView.CONSTRUCT | ViewSource.BuildView.DECONSTRUCT)) != 0){
            target = index(in.varintInt(), header.blocks().size() + 1, "block");
            progress = in.u8();
        }
        return new int[]{block, team, rotation, health, flags, aim, target, progress};
    }

    private int[] unit(Bytes.In in){
        int type = index(in.varintInt(), header.units().size(), "unit");
        int team = in.u8(), x = in.svarintInt(), y = in.svarintInt(), rotation = in.u16(), health = in.u8(), elevation = in.u8(), flags = in.u8();
        int player = (flags & ViewWriter.PLAYER) != 0 ? in.varintInt() : -1;
        int aim = (flags & ViewSource.UnitView.AIM) != 0 ? in.u16() : 0;
        return new int[]{type, team, x, y, rotation, health, elevation, flags, player, aim, 0, 0, 0};
    }

    private long[] bullet(Bytes.In in, long tick){
        int type = index(in.varintInt(), header.bullets().size(), "bullet");
        int team = in.u8(), x = in.svarintInt(), y = in.svarintInt(), rotation = in.u16();
        long age = in.varint();
        return new long[]{type, team, x, y, rotation, age, 0, 0, 0, tick};
    }

    private Player player(Bytes.In in){
        String name = in.str(ViewWriter.MAX_TEXT);
        int color = in.u32(), team = in.u8(), flags = in.u8(), unit = in.varintInt() - 1;
        return new Player(name, color, team, flags, unit);
    }

    private int block(Bytes.In in){
        return index(in.varintInt(), header.blocks().size(), "block");
    }

    private int tile(int previous, int gap, int i){
        int tile = gap(previous, gap, i);
        if(tile >= tiles) throw new IllegalArgumentException("tile " + tile + " outside a map of " + tiles);
        return tile;
    }

    private static int gap(int previous, int gap, int i){
        if(i > 0 && gap < 1) throw new IllegalArgumentException("a gap of " + gap + " after the first item");
        return Math.addExact(previous, gap);
    }

    private static int index(int value, int size, String what){
        if(value < 0 || value >= size) throw new IllegalArgumentException(what + " " + value + " outside a table of " + size);
        return value;
    }
}
