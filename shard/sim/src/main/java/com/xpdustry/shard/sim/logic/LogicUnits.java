package com.xpdustry.shard.sim.logic;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.ai.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * The world state behind processors' unit statements ({@link UnitStatements}) that outlives a statement:
 * <ul>
 *   <li>{@code LExecutor.unitTimeouts}: when each unit last had items or a payload moved by a processor, which vanilla
 *   keeps in a static map for the process (never pruned, cleared by {@code ResetEvent}) and Shard keeps per world;</li>
 *   <li>each team's unit quadtree ({@link UnitTree}), for {@code radar} and {@code query unit}: the hitboxes the units
 *   had at {@code TEAMS}, where vanilla inserts them, are recorded there in any world that runs logic, and the trees
 *   are built from them on first use in a tick.</li>
 * </ul>
 * After a copy or a restore it also points each {@link LogicAi}'s reused build plan back at the unit queue's object
 * ({@link LogicAi#relink}) at the start of the next tick.
 *
 * <p>{@code ulocate ore} reads {@link OreIndex}, the ore index the miners read.
 *
 * <p>{@link Persistence#AUTHORITATIVE}, in its own section ({@link SectionPart}): the timeouts, which are history. The
 * hitboxes and trees are taken again at each {@code TEAMS} before anything reads them. Its hash also holds the team
 * building lists {@code fetch} reads ({@link Buildings#teamBuildings}), which the building index keeps.
 *
 * <p>Owner: WP69.
 */
@Register
public final class LogicUnits implements SectionPart{
    /** The section format: 2 since its ore index moved to {@link OreIndex}. */
    static final int VERSION = 2;

    @Derived("the world, from init, the copy or the restore")
    private World world;
    /** Unit id to the {@code Time.time} of its last transfer. */
    private final TreeMap<Integer, Float> timeouts = new TreeMap<>();
    @Derived("the hitboxes recorded at TEAMS of tick boxTick; taken again at the next TEAMS")
    private float[][] boxes = new float[TeamUnits.TEAMS][];
    @Derived("with boxes")
    private long boxTick = -1;
    @Derived("built from boxes on first use in treeTick")
    private UnitTree[] trees = new UnitTree[TeamUnits.TEAMS];
    @Derived("with trees")
    private long treeTick = -1;
    @Derived("set by every copy and restore: the logic AIs' plans are relinked at the next tick's start")
    private boolean relink;

    @Override
    public void init(World world){
        this.world = world;
    }

    public static LogicUnits of(World world){
        return world.find(LogicUnits.class);
    }

    // region timeouts

    /** {@code exec.timeoutDone(unit, delay)}: {@code delay} frames passed since the unit's last transfer (or 0). */
    public boolean timeoutDone(Unit unit, float delay){
        return world.timers().time() >= timeouts.getOrDefault(unit.id(), 0f) + delay;
    }

    /** {@code exec.updateTimeout(unit)}. */
    public void updateTimeout(Unit unit){
        timeouts.put(unit.id(), world.timers().time());
    }

    // endregion
    // region unit trees

    /** Records every team's unit hitboxes in its recount order, as {@code updateTeamStats} inserts them. */
    void recordBoxes(){
        TeamUnits units = TeamUnits.of(world);
        if(units == null) return;
        for(int team = 0; team < TeamUnits.TEAMS; team++){
            UnitSeq list = units.units(team);
            if(list.isEmpty()){
                boxes[team] = null;
                continue;
            }
            float[] out = boxes[team] != null && boxes[team].length >= list.size() * 3 ? boxes[team] : new float[list.size() * 3];
            for(int i = 0; i < list.size(); i++){
                Unit unit = list.get(i);
                out[i * 3] = unit.x - unit.hitSize / 2f;
                out[i * 3 + 1] = unit.y - unit.hitSize / 2f;
                out[i * 3 + 2] = unit.hitSize;
            }
            boxes[team] = out;
        }
        boxTick = world.ticks();
    }

    /** {@code team.data().tree()}: the team's unit quadtree as {@code TEAMS} built it this tick. */
    UnitTree tree(int team){
        long now = world.ticks();
        if(treeTick != now){
            Arrays.fill(trees, null);
            treeTick = now;
        }
        if(trees[team] != null) return trees[team];
        TileGrid tiles = world.tiles();
        // World.getQuadBounds: the map and finalWorldBounds on every side
        var tree = new UnitTree(-CellGrid.MARGIN, -CellGrid.MARGIN, tiles.width() * 8f + CellGrid.MARGIN * 2, tiles.height() * 8f + CellGrid.MARGIN * 2);
        TeamUnits units = TeamUnits.of(world);
        UnitSeq list = units == null ? null : units.units(team);
        if(list != null){
            float[] recorded = boxTick == now ? boxes[team] : null;
            for(int i = 0; i < list.size(); i++){
                Unit unit = list.get(i);
                if(recorded != null){
                    tree.insert(unit, recorded[i * 3], recorded[i * 3 + 1], recorded[i * 3 + 2]);
                }else{
                    tree.insert(unit, unit.x - unit.hitSize / 2f, unit.y - unit.hitSize / 2f, unit.hitSize);
                }
            }
        }
        return trees[team] = tree;
    }

    /**
     * {@code team.data().tree().intersect(x, y, width, height, cons)}: {@code team}'s units in the rectangle, in the
     * quadtree's order, as {@link #tree} builds it. The RTS AI's squads read it ({@code RtsAI.assignSquads}).
     */
    public void intersect(int team, float x, float y, float width, float height, java.util.function.Consumer<Unit> out){
        tree(team).intersect(x, y, width, height, out);
    }

    /** Whether {@code team} had units at the last recount ({@code state.teams.present}, for units). */
    boolean hasUnits(int team){
        TeamUnits units = TeamUnits.of(world);
        return units != null && !units.units(team).isEmpty();
    }

    // endregion
    // region state

    @Override
    public WorldPart copy(CopyContext context){
        var out = new LogicUnits();
        out.world = context.world();
        out.timeouts.putAll(timeouts);
        out.relink = true;
        return out;
    }

    @Override
    public void hash(Hasher hasher){
        hasher.i(timeouts.size());
        for(var entry : timeouts.entrySet()) hasher.i(entry.getKey()).f(entry.getValue());
        // fetch's building lists: the building index keeps them, logic alone reads them
        world.buildings().hashTeamLists(hasher);
    }

    @Override
    public String section(){
        return "logic-units";
    }

    @Override
    public void write(World world, Writes out){
        out.i(VERSION);
        out.i(timeouts.size());
        for(var entry : timeouts.entrySet()){
            out.i(entry.getKey());
            out.f(entry.getValue());
        }
    }

    @Override
    public void read(World world, Reads in){
        int version = in.i();
        if(version != VERSION) throw new CheckpointException("logic-units section version " + version + ", expected " + VERSION);
        init(world);
        timeouts.clear();
        for(int i = in.i(); i > 0; i--) timeouts.put(in.i(), in.f());
        relink = true;
    }

    /** {@code updateTeamStats} inserts the units into their trees right after the recount: their hitboxes now. */
    @Register
    public static final class RecordBoxes implements PhaseHandler{
        @Override
        public Phase phase(){
            return Phase.TEAMS;
        }

        @Override
        public int order(){
            // right after TeamStats' recount
            return -99;
        }

        @Override
        public void run(World world){
            LogicUnits units = of(world);
            LogicPart logic = LogicPart.of(world);
            if(units != null && logic != null && logic.running()) units.recordBoxes();
        }
    }

    /** The relink after a copy or a restore, first thing in the next tick. */
    @Register
    public static final class Relink implements PhaseHandler{
        @Override
        public Phase phase(){
            return Phase.COMMANDS;
        }

        @Override
        public int order(){
            return Integer.MIN_VALUE;
        }

        @Override
        public void run(World world){
            LogicUnits units = of(world);
            if(units == null || !units.relink) return;
            units.relink = false;
            ActiveList<Unit> all = world.units();
            for(int i = 0; i < all.size(); i++){
                Unit unit = all.get(i);
                if(unit.controller instanceof LogicAi ai) ai.relink(unit);
            }
        }
    }
}
