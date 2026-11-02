package com.xpdustry.shard.sim.fog;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code FogControl} (FogControl.java), cosmetic fog for buildings (ARCHITECTURE §8; real fog,
 * {@code docs/architecture/fog.md}, stays off): per team, the tiles it has discovered ({@code staticData}, which never
 * fogs again) and the tiles it sees now ({@code read}), drawn as vanilla's midpoint circles around every vision source.
 *
 * <p>Sources: units with a {@code fogRadius} above 0 (radius {@code (int)fogRadius}, drawn one tile wider in the
 * dynamic fog) and buildings flagged {@code hasFogRadius}, radius {@code Mathf.round(fogRadius())}
 * ({@link Building#fogRadius()}: a turret's range with its ammo's {@code rangeChange}, a radar's growing radius). Teams
 * that are only AI ({@code isOnlyAI}) get no fog; teams that are AI ({@code isAI}) see everything.
 *
 * <p><b>Cadence, made deterministic.</b> Vanilla draws on two threads: the static thread whenever {@code update}
 * queued events, the dynamic one when a team's sources changed (a unit crossed a tile, a source building came or
 * went, a turret's ammo changed its range, a radar grew half a tile, a unit died) and at least 40 ms of wall clock
 * passed since that team's last redraw ({@code dynamicUpdateInterval}). Shard draws both synchronously at the end of
 * {@link #update}, inside {@code TEAMS} (vanilla's threads wake there and race the rest of the frame), and counts the
 * 40 ms as {@value #DYNAMIC_TICKS} ticks of a 60 Hz server (50 ms: the first frame past 40). The oracle pins vanilla to
 * the same schedule (W4.3).
 *
 * <p>Vanilla quirks kept: a team whose every source has a radius of 0 or less (or that has none left) isn't cleared,
 * so it keeps seeing what it last saw; {@code FogEvent} packs x, y and the radius into 16 unsigned bits, so a unit left
 * of or below the map draws nothing; a large unit ({@code hitSize > 16}) is in fog unless one of the eight points half
 * its hit size around it is seen, its centre not counted; {@code forceUpdate} and the unit death handler would throw
 * before the first update when only dynamic fog is on (Shard does nothing instead).
 *
 * <p>{@link Persistence#AUTHORITATIVE}: every team's grids, flags and last redraw tick, and the static events queued
 * since the last update (placed buildings, turret and radar refreshes), which the next update draws.
 */
@Register
public final class FogControl implements WorldPart, Checkpointable, TileWatcher{
    /** The static fog's custom chunk name ({@code SaveVersion.addCustomChunk}). */
    public static final String CHUNK = "static-fog-data";
    /** {@code dynamicUpdateInterval} (40 ms) in ticks of a 60 Hz server: the first frame boundary past it. */
    public static final int DYNAMIC_TICKS = 3;
    static final int TEAMS = 256;
    /** {@code Geometry.d8}. */
    private static final int[] D8X = {1, 1, 0, -1, -1, -1, 0, 1}, D8Y = {0, 1, 1, 1, 0, -1, -1, -1};

    /** Vanilla's static {@code ww} and {@code wh}: the world's, or the last chunk read's. */
    private int width, height;
    /** {@code fog != null}. */
    private boolean exists;
    /** {@code fog}, by team id; null entries are teams without data. */
    private final FogData[] fog = new FogData[TEAMS];
    private boolean loadedStatic, justLoaded;
    /** {@code staticEvents}: queued since the last draw. */
    private long[] staticEvents = new long[16];
    private int staticCount;

    @Derived("scratch, empty between updates")
    private long[] dynamicQueue = new long[16], unitQueue = new long[16];
    @Derived("scratch, empty between updates")
    private int dynamicCount, unitCount;
    @Derived("scratch, cleared before each draw")
    private final boolean[] cleared = new boolean[TEAMS];
    @Derived("the content's unit fog radii, on first use")
    private float[] unitRadii;

    public FogControl(){
    }

    @Override
    public void init(World world){
        width = world.tiles().width();
        height = world.tiles().height();
    }

    /** The world's fog, or null in a world built without it. */
    public static FogControl of(World world){
        return world.find(FogControl.class);
    }

    // ---------------------------------------------------------------- queries

    /** {@code isDiscovered(team, x, y)}: always without static fog, for AI teams and with fog off. */
    public boolean isDiscovered(Rules rules, World world, int team, int x, int y){
        if(!rules.staticFog || !rules.fog || UnitCalls.isAI(world, team)) return true;
        FogData data = data(team);
        if(data == null) return false;
        if(x < 0 || y < 0 || x >= width || y >= height) return false;
        return get(data.staticData, x + y * width);
    }

    /** {@code isVisible(team, x, y)}, in world units. */
    public boolean isVisible(Rules rules, World world, int team, float x, float y){
        return isVisibleTile(rules, world, team, Math.round(x / 8f), Math.round(y / 8f));
    }

    /** {@code isVisibleTile(team, x, y)}: clamped to the map; everything with fog off and for AI teams. */
    public boolean isVisibleTile(Rules rules, World world, int team, int x, int y){
        if(!rules.fog || UnitCalls.isAI(world, team)) return true;
        FogData data = data(team);
        if(data == null) return false;
        return get(data.read, Mathf.clamp(x, 0, width - 1) + Mathf.clamp(y, 0, height - 1) * width);
    }

    /** {@code fogControl.isVisibleTile(team, x, y)} of {@code world}'s fog: visible in a world without the part. */
    public static boolean visibleTile(World world, int team, int x, int y){
        Rules rules = world.buildings().rules();
        if(!rules.fog) return true;
        FogControl fog = of(world);
        return fog == null || fog.isVisibleTile(rules, world, team, x, y);
    }

    /** {@code fogControl.isDiscovered(team, x, y)} of {@code world}'s fog: discovered in a world without the part. */
    public static boolean discoveredTile(World world, int team, int x, int y){
        Rules rules = world.buildings().rules();
        if(!rules.fog || !rules.staticFog) return true;
        FogControl fog = of(world);
        return fog == null || fog.isDiscovered(rules, world, team, x, y);
    }

    /** {@code getDiscovered(team) != null}: whether the team has fog data. */
    public boolean hasData(int team){
        return data(team) != null;
    }

    /** {@code team}'s visible tiles now, bit {@code x + y * width} of the words, or null without data. A copy. */
    public long[] visible(int team){
        FogData data = data(team);
        return data == null ? null : data.read.clone();
    }

    /** {@code team}'s discovered tiles, as {@link #visible}. A copy. */
    public long[] discovered(int team){
        FogData data = data(team);
        return data == null ? null : data.staticData.clone();
    }

    /** The fog's width ({@code ww}): the world's, or the last chunk read's. */
    public int width(){
        return width;
    }

    public int height(){
        return height;
    }

    private FogData data(int team){
        return exists ? fog[team & 0xff] : null;
    }

    // ---------------------------------------------------------------- entity checks

    /**
     * {@code UnitComp.inFogTo(viewer)} (UnitComp.java:232): never to its own team or with fog off; a unit up to 16
     * world units wide by its centre, a larger one by the eight points half its hit size around it, the centre not
     * counted (vanilla quirk).
     */
    public static boolean inFogTo(Unit unit, int viewer){
        World world = unit.world();
        Rules rules = world.buildings().rules();
        if(unit.team == viewer || !rules.fog) return false;
        FogControl fog = of(world);
        if(fog == null) return false;
        if(unit.hitSize <= 16f) return !fog.isVisible(rules, world, viewer, unit.x, unit.y);
        float trns = unit.hitSize / 2f;
        for(int i = 0; i < 8; i++){
            if(fog.isVisible(rules, world, viewer, unit.x + D8X[i] * trns, unit.y + D8Y[i] * trns)) return false;
        }
        return true;
    }

    /**
     * {@code BuildingComp.isDiscovered(viewer)} (BuildingComp.java:315), the static fog part: a building up to 2 tiles
     * wide by its origin, a larger one by its origin or the four corners {@code size / 2} away. True without static fog.
     */
    public static boolean isDiscovered(Building building, int viewer){
        World world = building.world();
        Rules rules = world.buildings().rules();
        if(!rules.staticFog || !rules.fog) return true;
        FogControl fog = of(world);
        if(fog == null) return true;
        int x = building.tileX(), y = building.tileY(), size = building.block().size();
        if(size <= 2) return fog.isDiscovered(rules, world, viewer, x, y);
        int s = size / 2;
        return fog.isDiscovered(rules, world, viewer, x, y) || fog.isDiscovered(rules, world, viewer, x - s, y - s)
            || fog.isDiscovered(rules, world, viewer, x - s, y + s) || fog.isDiscovered(rules, world, viewer, x + s, y + s)
            || fog.isDiscovered(rules, world, viewer, x + s, y - s);
    }

    // ---------------------------------------------------------------- events

    /** {@code WorldLoadEvent} (FogControl.java:46): everything starts over; with static fog every source is discovered. */
    @Override
    public void loaded(World world){
        stop();
        loadedStatic = false;
        justLoaded = true;
        width = world.tiles().width();
        height = world.tiles().height();
        Rules rules = world.buildings().rules();
        if(rules.fog && rules.staticFog){
            pushStaticBlocks(world, rules);
            updateStatic();
            loadedStatic = true;
        }
    }

    /** {@code stop()}: no data, nothing queued. */
    private void stop(){
        exists = false;
        Arrays.fill(fog, null);
        staticCount = 0;
    }

    /** {@code pushStaticBlocks}: a circle around every updating building with a fog radius, making its team's data. */
    private void pushStaticBlocks(World world, Rules rules){
        exists = true;
        var builds = world.builds();
        for(int i = 0; i < builds.size(); i++){
            Building build = builds.get(i);
            if(!build.info().hasFlag(BlockFlag.hasFogRadius)) continue;
            if(fog[build.team] == null) fog[build.team] = new FogData(build.team, width, height);
            pushEvent(rules, event(build.tileX(), build.tileY(), Mathf.round(build.fogRadius()), build.team));
        }
    }

    private void pushEvent(Rules rules, long event){
        if(!rules.staticFog) return;
        if(staticCount == staticEvents.length) staticEvents = Arrays.copyOf(staticEvents, staticCount * 2);
        staticEvents[staticCount++] = event;
    }

    /**
     * {@code TilePreChangeEvent} (FogControl.java:81): a source building going away redraws its team. Every tile of a
     * multiblock fires it; it only sets a flag.
     */
    @Override
    public void tileChanging(World world, int pos){
        Rules rules = world.buildings().rules();
        if(!rules.fog) return;
        Building build = world.buildings().at(pos);
        if(build == null || isOnlyAI(world, build.team) || !build.info().hasFlag(BlockFlag.hasFogRadius)) return;
        FogData data = data(world.tiles().team(pos));
        if(data != null) data.dynamicUpdated = true;
    }

    /** {@code TileChangeEvent} (FogControl.java:64): a source building placed on its origin redraws and discovers. */
    @Override
    public void tileChanged(World world, int pos){
        Rules rules = world.buildings().rules();
        if(!rules.fog) return;
        Building build = world.buildings().at(pos);
        if(build == null || build.tile() != pos || isOnlyAI(world, build.team) || !build.info().hasFlag(BlockFlag.hasFogRadius)) return;
        FogData data = data(world.tiles().team(pos));
        if(data != null) data.dynamicUpdated = true;
        if(rules.staticFog) pushEvent(rules, event(build.tileX(), build.tileY(), Mathf.round(build.fogRadius()), build.team));
    }

    /** {@code UnitDestroyEvent} (FogControl.java:91): the unit's team redraws. */
    public static void unitDestroyed(World world, int team){
        FogControl fog = of(world);
        if(fog == null || !world.buildings().rules().fog) return;
        FogData data = fog.data(team);
        if(data != null) data.dynamicUpdated = true;
    }

    /**
     * {@code forceUpdate(team, build)} (FogControl.java:177): a building's radius changed (a turret's ammo, a radar
     * growing): its team redraws, and with static fog its circle is discovered.
     */
    public static void forceUpdate(Building build){
        World world = build.world();
        Rules rules = world.buildings().rules();
        FogControl fog = of(world);
        if(fog == null || !rules.fog) return;
        FogData data = fog.data(build.team);
        if(data == null) return;
        data.dynamicUpdated = true;
        if(rules.staticFog) fog.pushEvent(rules, event(build.tileX(), build.tileY(), Mathf.round(build.fogRadius()), build.team));
    }

    @Override
    public void buildingChanged(int pos){
    }

    // ---------------------------------------------------------------- update

    /** {@code fogControl.update()}, from {@code Logic.update} right after the team stats, while the rules have fog. */
    public void update(World world){
        Rules rules = world.buildings().rules();
        exists = true;
        if(rules.staticFog && !loadedStatic){
            pushStaticBlocks(world, rules);
            updateStatic();
            loadedStatic = true;
        }
        dynamicCount = 0;
        long tick = world.ticks();
        Teams teams = Teams.of(world);
        TeamUnits units = TeamUnits.of(world);
        for(int team = 0; team < TEAMS; team++){
            if(!present(world, teams, units, rules, team) || isOnlyAI(world, team)) continue;
            unitCount = 0;
            FogData data = fog[team];
            if(data == null) data = fog[team] = new FogData(team, width, height);
            UnitSeq list = units == null ? null : units.units(team);
            for(int i = 0; list != null && i < list.size(); i++){
                Unit unit = list.get(i);
                int tx = unit.tileX(), ty = unit.tileY(), pos = tx + ty * width;
                float radius = unitRadius(world, unit.type());
                if(radius <= 0f) continue;
                long event = event(tx, ty, (int)radius, team);
                unitQueue = add(unitQueue, unitCount++, event);
                if(unit.lastFogPos != pos){
                    pushEvent(rules, event);
                    unit.lastFogPos = pos;
                    data.dynamicUpdated = true;
                }
            }
            if(data.dynamicUpdated && tick - data.lastDynamicTick >= DYNAMIC_TICKS){
                data.dynamicUpdated = false;
                data.lastDynamicTick = tick;
                Buildings buildings = world.buildings();
                int flagged = buildings.flaggedCount(team, BlockFlag.hasFogRadius);
                for(int i = 0; i < flagged; i++){
                    Building build = buildings.flaggedAt(team, BlockFlag.hasFogRadius, i);
                    dynamicQueue = add(dynamicQueue, dynamicCount++, event(build.tileX(), build.tileY(), Mathf.round(build.fogRadius()), build.team));
                }
                for(int i = 0; i < unitCount; i++) dynamicQueue = add(dynamicQueue, dynamicCount++, unitQueue[i]);
            }
        }
        unitCount = 0;
        if(dynamicCount > 0){
            updateDynamic();
            justLoaded = false;
        }
        dynamicCount = 0;
        if(rules.staticFog && staticCount > 0) updateStatic();
    }

    /** {@code state.teams.present}: a team with buildings, units, a core, or the wave team while waves are on. */
    private static boolean present(World world, Teams teams, TeamUnits units, Rules rules, int team){
        if(world.buildings().teamBuildingCount(team) > 0) return true;
        if(units != null && units.units(team).size() > 0) return true;
        if(team == rules.waveTeam && rules.waves) return true;
        return teams != null && !teams.cores(team).isEmpty();
    }

    /** {@code Team.isOnlyAI()}: AI ({@code isAI}) with no player on the team. */
    static boolean isOnlyAI(World world, int team){
        if(!UnitCalls.isAI(world, team)) return false;
        Players players = Players.of(world);
        if(players == null) return true;
        for(int i = 0; i < players.list().size(); i++) if(players.list().get(i).team == team) return false;
        return true;
    }

    /** {@code unit.type.fogRadius}: the type's, or a fresh {@code UnitType}'s default for a hand-built type. */
    private float unitRadius(World world, UnitDef type){
        if(unitRadii == null){
            ContentEpoch content = world.content();
            unitRadii = new float[content.units()];
            for(int i = 0; i < unitRadii.length; i++){
                UnitDef def = content.unit(i);
                unitRadii[i] = def.baked() ? def.fields().f("fogRadius") : Math.max(58f * 3f, def.hitSize() * 2f) / 8f;
            }
        }
        return unitRadii[type.id()];
    }

    /** {@code updateStatic()}: every queued circle onto its team's discovered tiles. */
    private void updateStatic(){
        for(int i = 0; i < staticCount; i++){
            long event = staticEvents[i];
            FogData data = fog[team(event)];
            if(data != null) circle(data.staticData, x(event), y(event), radius(event));
        }
        staticCount = 0;
    }

    /** {@code updateDynamic(cleared)}: each team with an event of radius above 0 is redrawn from scratch, one wider. */
    private void updateDynamic(){
        Arrays.fill(cleared, false);
        for(int i = 0; i < dynamicCount; i++){
            long event = dynamicQueue[i];
            int rad = radius(event), team = team(event);
            if(rad <= 0) continue;
            FogData data = fog[team];
            if(data == null) continue;
            if(!cleared[team]){
                cleared[team] = true;
                Arrays.fill(data.write, 0L);
            }
            // radius is always +1 to keep up with visuals
            circle(data.write, x(event), y(event), rad + 1);
        }
        for(int team = 0; team < TEAMS; team++){
            if(!cleared[team]) continue;
            FogData data = fog[team];
            long[] temp = data.read;
            data.read = data.write;
            data.write = temp;
        }
    }

    // ---------------------------------------------------------------- the save chunk

    /** {@code shouldWrite()}: the rules have static fog and data exists. */
    public boolean shouldWrite(Rules rules){
        return rules.fog && rules.staticFog && exists;
    }

    /**
     * {@code write(stream)} (FogControl.java:440): the number of teams with data, the world's size, then each team's
     * discovered tiles in runs of at most 127, the top bit set for discovered.
     */
    public byte[] writeChunk(int worldWidth, int worldHeight){
        var out = new Writes();
        int used = 0;
        for(int team = 0; team < TEAMS; team++) if(fog[team] != null) used++;
        out.b(used);
        out.s(worldWidth);
        out.s(worldHeight);
        int size = width * height;
        for(int team = 0; team < TEAMS; team++){
            FogData data = fog[team];
            if(data == null) continue;
            out.b(team);
            int pos = 0;
            while(pos < size){
                int consecutives = 0;
                boolean cur = get(data.staticData, pos);
                while(consecutives < 127 && pos < size){
                    if(cur != get(data.staticData, pos)) break;
                    consecutives++;
                    pos++;
                }
                out.b((cur ? 0x80 : 0) | consecutives);
            }
        }
        return out.toByteArray();
    }

    /**
     * {@code read(stream)} (FogControl.java:476): the chunk's size becomes the fog's; each team in it gets new data,
     * its discovered tiles set. Others keep theirs.
     */
    public void readChunk(byte[] chunk){
        exists = true;
        var in = new Reads(chunk);
        int teams = in.ub();
        int w = in.s(), h = in.s();
        int length = w * h;
        width = w;
        height = h;
        for(int i = 0; i < teams; i++){
            int team = in.ub();
            FogData data = fog[team] = new FogData(team, w, h);
            int pos = 0;
            while(pos < length){
                int run = in.ub();
                int consec = run & 0x7f;
                if((run & 0x80) != 0) set(data.staticData, pos, pos + consec);
                pos += consec;
            }
        }
    }

    // ---------------------------------------------------------------- circles

    /** {@code circle}: the midpoint circle of {@code radius} around a tile, filled by rows. */
    void circle(long[] arr, int x, int y, int radius){
        int f = 1 - radius;
        int ddFx = 1, ddFy = -2 * radius;
        int px = 0, py = radius;
        hline(arr, x, x, y + radius);
        hline(arr, x, x, y - radius);
        hline(arr, x - radius, x + radius, y);
        while(px < py){
            if(f >= 0){
                py--;
                ddFy += 2;
                f += ddFy;
            }
            px++;
            ddFx += 2;
            f += ddFx;
            hline(arr, x - px, x + px, y + py);
            hline(arr, x - px, x + px, y - py);
            hline(arr, x - py, x + py, y + px);
            hline(arr, x - py, x + py, y - px);
        }
    }

    private void hline(long[] arr, int x1, int x2, int y){
        if(y < 0 || y >= height) return;
        if(x1 > x2){
            int tmp = x1;
            x1 = x2;
            x2 = tmp;
        }
        if(x1 >= width) return;
        if(x2 < 0) return;
        if(x1 < 0) x1 = 0;
        if(x2 >= width) x2 = width - 1;
        x2++;
        int off = y * width;
        set(arr, off + x1, off + x2);
    }

    // ---------------------------------------------------------------- FogEvent, Bits

    /** {@code FogEvent.get(x, y, radius, team)}: 16 unsigned bits each for x, y and radius, 8 for the team. */
    static long event(int x, int y, int radius, int team){
        return (x & 0xFFFFL) | (y & 0xFFFFL) << 16 | (radius & 0xFFFFL) << 32 | (team & 0xFFL) << 48;
    }

    static int x(long event){
        return (int)(event & 0xFFFF);
    }

    static int y(long event){
        return (int)(event >>> 16 & 0xFFFF);
    }

    static int radius(long event){
        return (int)(event >>> 32 & 0xFFFF);
    }

    static int team(long event){
        return (int)(event >>> 48 & 0xFF);
    }

    private static long[] add(long[] array, int index, long value){
        if(index == array.length) array = Arrays.copyOf(array, index * 2);
        array[index] = value;
        return array;
    }

    static boolean get(long[] bits, int index){
        int word = index >>> 6;
        return word < bits.length && (bits[word] & 1L << index) != 0;
    }

    /** {@code Bits.set(from, to)}: from inclusive, to exclusive. */
    static void set(long[] bits, int from, int to){
        for(int i = from; i < to; ){
            int word = i >>> 6;
            int end = Math.min(to, (word + 1) << 6);
            long mask = (end - i == 64 ? -1L : ((1L << (end - i)) - 1)) << (i & 63);
            if(word < bits.length) bits[word] |= mask;
            i = end;
        }
    }

    /** One team's grids: {@code FogData}. Stored by {@link FogControl}'s checkpoint, its team with it. */
    public static final class FogData implements Checkpointable{
        /** The team, which vanilla knows by the data's index. */
        int team;
        /** Tiles seen now. */
        long[] read;
        @Derived("the redraw's scratch buffer, swapped with read and cleared before every draw")
        long[] write;
        /** Tiles discovered. */
        long[] staticData;
        /** {@code lastDynamicMs}, as the tick of the last redraw. */
        long lastDynamicTick = Long.MIN_VALUE / 2;
        boolean dynamicUpdated = true;

        /** For restores. */
        public FogData(){
        }

        FogData(int team, int width, int height){
            int words = (width * height + 63) >>> 6;
            this.team = team;
            read = new long[words];
            write = new long[words];
            staticData = new long[words];
        }

        FogData copy(){
            var out = new FogData();
            out.team = team;
            out.read = read.clone();
            out.write = new long[read.length];
            out.staticData = staticData.clone();
            out.lastDynamicTick = lastDynamicTick;
            out.dynamicUpdated = dynamicUpdated;
            return out;
        }

        @Override
        public void write(CheckpointOut out){
            out.i("team", team).longs("read", read).longs("staticData", staticData).l("lastDynamicTick", lastDynamicTick)
                .bool("dynamicUpdated", dynamicUpdated);
        }

        @Override
        public void read(CheckpointIn in){
            team = in.i("team");
            read = in.longs("read");
            staticData = in.longs("staticData");
            lastDynamicTick = in.l("lastDynamicTick");
            dynamicUpdated = in.bool("dynamicUpdated");
            if(read == null || staticData == null || read.length != staticData.length || team < 0 || team >= TEAMS){
                throw new CheckpointException("the fog of team " + team + " is corrupt");
            }
            write = new long[read.length];
        }
    }

    // ---------------------------------------------------------------- world part

    @Override
    public WorldPart copy(CopyContext context){
        var out = new FogControl();
        out.width = width;
        out.height = height;
        out.exists = exists;
        for(int team = 0; team < TEAMS; team++) if(fog[team] != null) out.fog[team] = fog[team].copy();
        out.loadedStatic = loadedStatic;
        out.justLoaded = justLoaded;
        out.staticEvents = Arrays.copyOf(staticEvents, Math.max(16, staticCount));
        out.staticCount = staticCount;
        out.unitRadii = unitRadii;
        return out;
    }

    @Override
    public void hash(Hasher hasher){
        hasher.i(width).i(height).bool(exists).bool(loadedStatic).bool(justLoaded).i(staticCount);
        for(int i = 0; i < staticCount; i++) hasher.l(staticEvents[i]);
        for(int team = 0; team < TEAMS; team++){
            FogData data = fog[team];
            if(data == null) continue;
            hasher.i(team).l(data.lastDynamicTick).bool(data.dynamicUpdated);
            for(long word : data.read) hasher.l(word);
            for(long word : data.staticData) hasher.l(word);
        }
    }

    @Override
    public void write(CheckpointOut out){
        var teams = new ArrayList<FogData>();
        for(FogData data : fog) if(data != null) teams.add(data);
        out.i("width", width).i("height", height).bool("exists", exists).list("fog", teams).bool("loadedStatic", loadedStatic)
            .bool("justLoaded", justLoaded).longs("staticEvents", staticEvents, staticCount).i("staticCount", staticCount);
    }

    @Override
    public void read(CheckpointIn in){
        width = in.i("width");
        height = in.i("height");
        exists = in.bool("exists");
        List<FogData> teams = in.list("fog", FogData.class);
        if(teams == null) throw new CheckpointException("the fog's teams are missing");
        Arrays.fill(fog, null);
        for(FogData data : teams) fog[data.team] = data;
        loadedStatic = in.bool("loadedStatic");
        justLoaded = in.bool("justLoaded");
        long[] events = in.longs("staticEvents");
        staticCount = in.i("staticCount");
        if(events == null || events.length != staticCount) throw new CheckpointException("the fog's static events are corrupt");
        staticEvents = Arrays.copyOf(events, Math.max(16, staticCount));
    }
}
