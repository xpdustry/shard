package com.xpdustry.shard.sim.path;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code AvoidanceProcess} (AvoidanceProcess.java): a grid over the map where every ground unit of an AI
 * team without RTS AI stamps a disc of {@code Integer.MAX_VALUE - id} (the largest wins), so a stuck wave unit can
 * step around tiles that lower-id units cover ({@link PathService#next(int, int, int[], int)}). It is lazy, as
 * vanilla's: nothing happens until the first {@link #getAvoidance()}, which returns null that once.
 *
 * <p>Timing, as vanilla's {@code asyncCore}: {@code begin()} runs at the start of the frame, before
 * {@code Logic.update}, and collects the requests from each team's unit list as of the last recount; {@code process()}
 * stamps them into the buffer that isn't being read, on a thread vanilla joins at the end of the frame. Shard runs
 * both in {@code TEAMS} after physics begins and before the recount ({@link Begin}); the frame's reads see the
 * buffer stamped one frame earlier either way. Vanilla quirks kept: a buffer is cleared only when the previous
 * process had requests, so a buffer can hold stamps from two processes back; and the stamp radius is
 * {@code clamp(1, ceil(rad), 20)} with the arguments in that order, which never caps it at 20.
 *
 * <p>{@link Persistence#AUTHORITATIVE}: the flags, which buffer is read, and both buffers (stored sparse), since they
 * hold positions of earlier frames.
 */
@Register
public final class AvoidanceProcess implements WorldPart, Checkpointable{
    /** {@code Vars.unitCollisionRadiusScale}. */
    static final float RADIUS_SCALE = 0.6f;
    static final float TILESIZE = 8f;

    private int width, height;
    private int[] buffer1, buffer2;
    private boolean swap, modified, active;
    /** Which buffer {@code avoidance} is: 0 none, 1 or 2. */
    private int reading;
    @Derived("cleared at each begin and consumed by its process, in the same tick")
    private final IntSeq requests = new IntSeq();
    /**
     * Each buffer's non-zero cells, listed as they became non-zero: a clear zeroes these instead of the whole map,
     * which left the buffer as {@code Arrays.fill} does at a fraction of the cost (a default map's buffer is 90,000
     * cells and a tick stamps a few hundred; W2.21).
     */
    @Derived("buffer1's non-zero cells, listed again after a restore")
    private IntSeq stamped1 = new IntSeq();
    @Derived("buffer2's non-zero cells, listed again after a restore")
    private IntSeq stamped2 = new IntSeq();

    public AvoidanceProcess(){
    }

    @Override
    public void init(World world){
        width = world.tiles().width();
        height = world.tiles().height();
    }

    /**
     * {@code getAvoidance()}: the grid this frame reads, {@code y * width + x}, or null. The first call activates the
     * process and returns null.
     */
    public int[] getAvoidance(){
        if(!active){
            // lazily initialize and begin processing after this first request
            buffer1 = new int[width * height];
            buffer2 = new int[width * height];
            stamped1.clear();
            stamped2.clear();
            active = true;
        }
        return avoidance();
    }

    /** The grid this frame reads, without activating anything; null while inactive. */
    public int[] avoidance(){
        return reading == 1 ? buffer1 : reading == 2 ? buffer2 : null;
    }

    /** Whether the first request came. */
    public boolean active(){
        return active;
    }

    /** {@code begin()}: the requests of every AI team's ground units, and the buffer this frame reads. */
    void begin(World world){
        if(!active) return;
        requests.clear();
        reading = !swap ? 1 : 2;
        TeamUnits teams = TeamUnits.of(world);
        if(teams == null) return;
        Rules rules = UnitCalls.rules(world);
        for(int team = 0; team < TeamUnits.TEAMS; team++){
            UnitSeq units = teams.units(team);
            // only do avoidance if it's relevant to the team
            if(units == null || units.isEmpty() || !UnitCalls.isAI(world, team) || rules.teams.get(team).rtsAi) continue;
            for(int i = 0; i < units.size(); i++){
                Unit unit = units.get(i);
                if(unit.collisionLayer() != UnitPhysics.LAYER_GROUND) continue;
                // scaling is oversized 2x because units need to avoid things that are at their origin tile
                float scaling = 2f;
                requests.add(Point2.pack(unit.tileX(), unit.tileY()));
                requests.add(Float.floatToRawIntBits(unit.hitSize * RADIUS_SCALE / TILESIZE * scaling));
                requests.add(unit.id());
            }
        }
    }

    /** {@code process()}: stamps the requests into the buffer nobody reads this frame, then swaps. */
    void process(){
        if(!active) return;
        int[] buffer = swap ? buffer1 : buffer2;
        IntSeq stamped = swap ? stamped1 : stamped2;
        swap = !swap;
        if(buffer == null) return;
        // technically wrong (vanilla's comment), and kept: a buffer is cleared only after a process with requests
        if(modified){
            for(int i = 0; i < stamped.size; i++) buffer[stamped.items[i]] = 0;
            stamped.clear();
        }
        modified = requests.size > 0;

        int total = requests.size;
        int[] items = requests.items;
        for(int i = 0; i < total; i += 3){
            int point = items[i], id = items[i + 2];
            int rx = Point2.x(point), ry = Point2.y(point);
            float rad = Float.intBitsToFloat(items[i + 1]);
            float rad2 = rad * rad;
            // vanilla quirk: clamp(value = 1, min = ceil(rad), max = 20) is max(1, ceil(rad))
            int r = Mathf.clamp(1, Mathf.ceil(rad), 20);
            for(int dx = -r; dx <= r; dx++){
                for(int dy = -r; dy <= r; dy++){
                    int x = dx + rx, y = dy + ry;
                    if(x >= 0 && y >= 0 && x < width && y < height && (dx * dx + dy * dy) <= rad2){
                        int cell = x + y * width;
                        if(buffer[cell] == 0) stamped.add(cell);
                        buffer[cell] = Math.max(buffer[cell], Integer.MAX_VALUE - id);
                    }
                }
            }
        }
    }

    /** {@code asyncCore.begin()}'s avoidance, then its process: after physics begins, before the team recount. */
    @Register
    public static final class Begin implements PhaseHandler{
        @Override
        public Phase phase(){
            return Phase.TEAMS;
        }

        @Override
        public int order(){
            return -150;
        }

        @Override
        public void run(World world){
            AvoidanceProcess process = world.find(AvoidanceProcess.class);
            if(process == null) return;
            process.begin(world);
            process.process();
        }
    }

    // world part

    @Override
    public WorldPart copy(CopyContext context){
        var out = new AvoidanceProcess();
        out.width = width;
        out.height = height;
        out.buffer1 = buffer1 == null ? null : buffer1.clone();
        out.buffer2 = buffer2 == null ? null : buffer2.clone();
        out.stamped1 = stamped1.copy();
        out.stamped2 = stamped2.copy();
        out.swap = swap;
        out.modified = modified;
        out.active = active;
        out.reading = reading;
        return out;
    }

    @Override
    public void hash(Hasher hasher){
        hasher.bool(active).bool(swap).bool(modified).i(reading);
        if(!active) return;
        hashSparse(hasher, buffer1);
        hashSparse(hasher, buffer2);
    }

    private static void hashSparse(Hasher hasher, int[] buffer){
        int count = 0;
        for(int i = 0; i < buffer.length; i++){
            if(buffer[i] == 0) continue;
            hasher.i(i).i(buffer[i]);
            count++;
        }
        hasher.i(count);
    }

    @Override
    public void write(CheckpointOut out){
        out.i("width", width).i("height", height).bool("swap", swap).bool("modified", modified).bool("active", active).i("reading", reading)
            .ints("buffer1", sparse(buffer1)).ints("buffer2", sparse(buffer2));
    }

    @Override
    public void read(CheckpointIn in){
        width = in.i("width");
        height = in.i("height");
        swap = in.bool("swap");
        modified = in.bool("modified");
        active = in.bool("active");
        reading = in.i("reading");
        buffer1 = dense(in.ints("buffer1"));
        buffer2 = dense(in.ints("buffer2"));
        stamped1 = stamped(buffer1);
        stamped2 = stamped(buffer2);
    }

    /** {@code buffer}'s non-zero cells. */
    private static IntSeq stamped(int[] buffer){
        var out = new IntSeq();
        if(buffer == null) return out;
        for(int i = 0; i < buffer.length; i++) if(buffer[i] != 0) out.add(i);
        return out;
    }

    /** A buffer as (index, value) pairs of its non-zero cells, or null for none. */
    private static int[] sparse(int[] buffer){
        if(buffer == null) return null;
        var out = new IntSeq();
        for(int i = 0; i < buffer.length; i++){
            if(buffer[i] != 0){
                out.add(i);
                out.add(buffer[i]);
            }
        }
        return out.toArray();
    }

    private int[] dense(int[] pairs){
        if(pairs == null) return null;
        int[] buffer = new int[width * height];
        for(int i = 0; i < pairs.length; i += 2){
            if(pairs[i] < 0 || pairs[i] >= buffer.length) throw new CheckpointException("an avoidance cell is outside the map");
            buffer[pairs[i]] = pairs[i + 1];
        }
        return buffer;
    }
}
