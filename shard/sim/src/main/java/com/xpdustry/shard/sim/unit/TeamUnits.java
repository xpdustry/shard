package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * The per-team unit index of vanilla's {@code TeamData}: unit counts by type for unit caps ({@code unitCount},
 * {@code typeCounts}), and the team's units in {@code Groups.unit} order ({@code units}, {@code unitsByType}).
 *
 * <p>Two clocks, as in vanilla. The counts move on every {@link Unit#add()} and {@link Unit#remove()}
 * ({@code TeamData.updateCount}) and are recounted from scratch at {@code TEAMS} ({@code Teams.updateTeamStats}),
 * with units carried as payloads counted too. The lists change only at that recount, so between two recounts they miss
 * new units and still hold removed ones.
 *
 * <p>Unit caps: {@link Units#getCap} reads the rules and {@link #capModifier}, vanilla's {@code TeamData.unitCap}: the
 * sum of {@code unitCapModifier} over the team's buildings, which vanilla's {@code BlockIndexer} keeps as buildings
 * come and go and Shard sums when a unit is added. A unit added over its cap dies: it is marked dead at once and
 * destroyed at the start of the next tick ({@code Call.unitCapDeath} posts {@code Call.unitDestroy}). Environment
 * deaths post the same way; both wait in {@link #doomed()} for {@link PostedDestroys}.
 *
 * <p>{@link Persistence#AUTHORITATIVE}: the counts, lists and posted destroys are stale by design between recounts, so
 * a restore must not rebuild them.
 */
@Register
public final class TeamUnits implements WorldPart, Checkpointable{
    /** Vanilla's {@code Team.all}. */
    public static final int TEAMS = 256;

    private final int[] unitCount;
    /** Per team, by unit type id; null until the team counts a unit, as vanilla's lazy array. */
    private final int[][] typeCounts;
    private final UnitSeq[] units;
    /** Per team, by unit type id; both levels null until needed. */
    private final UnitSeq[][] byType;
    /** Units whose {@code Call.unitDestroy} is posted to the next {@link PostedDestroys}. */
    private final UnitSeq doomed;
    /** Units that became a block's payload, whose new entity id is posted to the next {@link PostedDestroys}. */
    private final UnitSeq renumbered;
    /** {@code unitCapModifier} by block id, read from the content on first use. */
    @Derived("the content's unitCapModifier per block, on first use")
    private int[] capModifiers;
    /** The content's unit type count; stored, since a restored part gets no {@code init}. */
    private int typeIds;
    /**
     * The teams whose counts or lists may be non-zero ({@link #touched}, in no particular order): the recount clears
     * these alone rather than all {@value #TEAMS}.
     */
    @Derived("every team after a restore; then the teams counted since the last recount")
    private final boolean[] touched = new boolean[TEAMS];
    @Derived("with touched")
    private final int[] touchedTeams = new int[TEAMS];
    @Derived("with touched")
    private int touchedCount;

    public TeamUnits(){
        unitCount = new int[TEAMS];
        typeCounts = new int[TEAMS][];
        units = new UnitSeq[TEAMS];
        for(int team = 0; team < TEAMS; team++) units[team] = new UnitSeq();
        byType = new UnitSeq[TEAMS][];
        doomed = new UnitSeq();
        renumbered = new UnitSeq();
    }

    /** Marks {@code team} as one the next recount clears. */
    private void touch(int team){
        if(touched[team]) return;
        touched[team] = true;
        touchedTeams[touchedCount++] = team;
    }

    private TeamUnits(TeamUnits source, CopyContext context){
        unitCount = source.unitCount.clone();
        typeCounts = new int[TEAMS][];
        units = new UnitSeq[TEAMS];
        byType = new UnitSeq[TEAMS][];
        for(int team = 0; team < TEAMS; team++){
            if(source.typeCounts[team] != null) typeCounts[team] = source.typeCounts[team].clone();
            units[team] = source.units[team].copy(context);
            UnitSeq[] types = source.byType[team];
            if(types != null){
                byType[team] = new UnitSeq[types.length];
                for(int i = 0; i < types.length; i++) if(types[i] != null) byType[team][i] = types[i].copy(context);
            }
        }
        doomed = source.doomed.copy(context);
        renumbered = source.renumbered.copy(context);
        capModifiers = source.capModifiers;
        typeIds = source.typeIds;
        System.arraycopy(source.touched, 0, touched, 0, TEAMS);
        System.arraycopy(source.touchedTeams, 0, touchedTeams, 0, TEAMS);
        touchedCount = source.touchedCount;
    }

    @Override
    public void init(World world){
        typeIds = world.content().units();
    }

    /** The world's team index, or null in a world built without it. */
    public static TeamUnits of(World world){
        return world.find(TeamUnits.class);
    }

    /** Vanilla's {@code TeamData.unitCount}: how many units {@code team} has, payloads included. */
    public int count(int team){
        return unitCount[team];
    }

    /** Vanilla's {@code TeamData.countType}. */
    public int count(int team, UnitDef type){
        int[] counts = typeCounts[team];
        return counts == null || counts.length <= type.id() ? 0 : counts[type.id()];
    }

    /** Vanilla's {@code TeamData.unitCap}: the {@code unitCapModifier} sum of {@code team}'s buildings in {@code world}. */
    public int capModifier(World world, int team){
        if(capModifiers == null){
            var content = world.content();
            int[] modifiers = new int[content.blocks()];
            for(int id = 0; id < modifiers.length; id++){
                var block = content.block(id);
                modifiers[id] = block.baked() ? block.fields().i("unitCapModifier") : 0;
            }
            capModifiers = modifiers;
        }
        int[] sum = {0};
        world.buildings().each(building -> {
            if(building.team == team) sum[0] += capModifiers[building.block().id()];
        });
        return sum[0];
    }

    /** Vanilla's {@code TeamData.units}: the team's units at the last recount, in {@code Groups.unit} order. */
    public UnitSeq units(int team){
        return units[team];
    }

    /** Vanilla's {@code TeamData.unitsByType[type.id]}: the team's units of {@code type} at the last recount, or null. */
    public UnitSeq units(int team, UnitDef type){
        UnitSeq[] types = byType[team];
        return types == null || types.length <= type.id() ? null : types[type.id()];
    }

    /** Vanilla's {@code TeamData.updateCount}: never below zero. */
    public void updateCount(int team, UnitDef type, int amount){
        if(type == null) return;
        touch(team);
        unitCount[team] = Math.max(amount + unitCount[team], 0);
        int[] counts = typeCounts[team];
        if(counts == null || counts.length <= type.id()){
            counts = typeCounts[team] = counts == null ? new int[typeIds] : Arrays.copyOf(counts, typeIds);
        }
        counts[type.id()] = Math.max(amount + counts[type.id()], 0);
    }

    /** {@code UnitComp.add}: counts {@code unit}, and kills it if that puts its type over the team's cap. */
    void added(Unit unit){
        updateCount(unit.team, unit.type(), 1);
        World world = unit.world();
        if(unit.params().useUnitCap() && count(unit.team, unit.type()) > Units.getCap(world, unit.team)
            && !unit.spawnedByCore && !unit.dead && !UnitCalls.rules(world).editor){
            UnitCalls.capDeath(unit);
            updateCount(unit.team, unit.type(), -1);
        }
    }

    /** Posts {@code Call.unitDestroy(unit.id)} to the next tick. */
    void post(Unit unit){
        doomed.add(unit);
    }

    /** Vanilla's {@code Teams.updateTeamStats}, the unit part: recounts and refills every team from {@code units}. */
    void recount(ActiveList<Unit> all){
        // only a touched team has counts or units to clear; the rest are zero and empty already
        for(int i = 0; i < touchedCount; i++){
            int team = touchedTeams[i];
            touched[team] = false;
            unitCount[team] = 0;
            if(typeCounts[team] != null) Arrays.fill(typeCounts[team], 0);
            units[team].clear();
            UnitSeq[] types = byType[team];
            if(types != null) for(UnitSeq list : types) if(list != null) list.clear();
        }
        touchedCount = 0;
        for(int i = 0; i < all.size(); i++){
            Unit unit = all.get(i);
            if(unit.type() == null) continue;
            int team = unit.team;
            touch(team);
            units[team].add(unit);
            int type = unit.type().id();
            if(byType[team] == null || byType[team].length <= type) byType[team] = new UnitSeq[typeIds];
            if(byType[team][type] == null) byType[team][type] = new UnitSeq();
            byType[team][type].add(unit);
            count(unit);
        }
    }

    /** {@code Teams.count}: the unit and, recursively, the units it carries, each for its own team. */
    private void count(Unit unit){
        updateCount(unit.team, unit.type(), 1);
        PayloadSlot payloads = unit.payloads;
        if(payloads == null) return;
        for(int i = 0; i < payloads.size(); i++){
            if(payloads.get(i) instanceof Payload.UnitPayload carried) count(carried.unit());
        }
    }

    /**
     * The posted {@code Call.unitDestroy} of each doomed unit, in posting order: destroyed if still added
     * ({@code Groups.unit.getByID}), published either way. Destroys posted meanwhile wait for the next tick, as tasks
     * posted while vanilla runs its posted tasks do.
     */
    void destroyDoomed(){
        if(doomed.isEmpty()) return;
        var posted = new Unit[doomed.size()];
        for(int i = 0; i < posted.length; i++) posted[i] = doomed.get(i);
        doomed.clear();
        // vanilla quirk: remove() uncounts the unit a second time; the recount right after repairs it
        for(Unit unit : posted) UnitCalls.destroy(unit);
    }

    /** Posts {@code unit.id = EntityGroup.nextId()} to the next tick ({@code BuildingComp.handleUnitPayload}). */
    void postNewId(Unit unit){
        renumbered.add(unit);
    }

    /**
     * The posted new id of each unit that became a block's payload, in posting order: it draws the next entity id, so
     * that the Calls sent right after it entered still name its old one (BuildingComp.java:714). Ids posted meanwhile
     * wait for the next tick.
     */
    void renumberPosted(World world){
        if(renumbered.isEmpty()) return;
        var posted = new Unit[renumbered.size()];
        for(int i = 0; i < posted.length; i++) posted[i] = renumbered.get(i);
        renumbered.clear();
        for(Unit unit : posted) unit.id(world.ids().next());
    }

    /** How many units wait for their posted destroy. */
    public int doomed(){
        return doomed.size();
    }

    @Override
    public WorldPart copy(CopyContext context){
        return new TeamUnits(this, context);
    }

    /** One team's lazily made arrays, for the teams that have them: the rest stay null, as they were. */
    private record TeamCounts(int team, int[] counts){
    }

    private record TeamList(int team, UnitSeq units){
    }

    private record TeamTypes(int team, List<UnitSeq> types){
    }

    @Override
    public void write(CheckpointOut out){
        var counts = new ArrayList<TeamCounts>();
        var lists = new ArrayList<TeamList>();
        var types = new ArrayList<TeamTypes>();
        for(int team = 0; team < TEAMS; team++){
            if(typeCounts[team] != null) counts.add(new TeamCounts(team, typeCounts[team]));
            if(!units[team].isEmpty()) lists.add(new TeamList(team, units[team]));
            if(byType[team] != null) types.add(new TeamTypes(team, Arrays.asList(byType[team])));
        }
        out.ints("unitCount", unitCount).list("typeCounts", counts).list("units", lists).list("byType", types)
            .obj("doomed", doomed).obj("renumbered", renumbered).i("typeIds", typeIds);
    }

    @Override
    public void read(CheckpointIn in){
        in.ints("unitCount", unitCount);
        for(TeamCounts row : in.list("typeCounts", TeamCounts.class)) typeCounts[team(row.team())] = row.counts();
        for(TeamList row : in.list("units", TeamList.class)) units[team(row.team())] = row.units();
        for(TeamTypes row : in.list("byType", TeamTypes.class)) byType[team(row.team())] = row.types().toArray(UnitSeq[]::new);
        UnitSeq stored = in.obj("doomed", UnitSeq.class);
        for(int i = 0; i < stored.size(); i++) doomed.add(stored.get(i));
        UnitSeq posted = in.obj("renumbered", UnitSeq.class);
        for(int i = 0; i < posted.size(); i++) renumbered.add(posted.get(i));
        typeIds = in.i("typeIds");
        for(int team = 0; team < TEAMS; team++) touch(team);
    }

    private static int team(int team){
        if(team < 0 || team >= TEAMS) throw new CheckpointException("team " + team + " is out of range");
        return team;
    }

    @Override
    public void hash(Hasher hasher){
        for(int team = 0; team < TEAMS; team++){
            int[] counts = typeCounts[team];
            if(unitCount[team] == 0 && counts == null && units[team].isEmpty()) continue;
            hasher.i(team).i(unitCount[team]).bool(counts != null);
            if(counts != null) hasher.ints(counts);
            units[team].hash(hasher);
        }
        doomed.hash(hasher);
        // only while a new id waits, so worlds where no unit entered a block hash as before
        if(!renumbered.isEmpty()) renumbered.hash(hasher);
    }
}
