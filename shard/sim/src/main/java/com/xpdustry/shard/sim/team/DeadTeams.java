package com.xpdustry.shard.sim.team;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.blocks.power.*;
import com.xpdustry.shard.sim.blocks.storage.*;
import com.xpdustry.shard.sim.build.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's clean-up of a PvP team that lost its last core: {@code Logic}'s {@code CoreChangeEvent} listener
 * (Logic.java:180-185) and {@code TeamData.destroyToDerelict} (Teams.java:331).
 *
 * <p>A destroyed core fires {@code CoreChangeEvent} at the end of {@code onDestroyed}; the listener posts its check to
 * the next frame ({@code Core.app.post}), where it runs with the frame's posted tasks, before the update: here, early in
 * {@link Phase#TEAMS}, after {@link PostedDestroys}. If {@code cleanupDeadTeams} and {@code pvp} hold, the core is no
 * longer added, isn't derelict, and its team has no core left, the team is destroyed to derelict:
 * <ul>
 *   <li>its rebuild plans are dropped;</li>
 *   <li>each of its buildings in its {@code buildingTree}'s order ({@link BuildingTree#getObjects}), privileged ones
 *   skipped: a core is killed; any other is scheduled: a unit it carries is dumped and killed
 *   ({@code Call.destroyPayload}), one in five is killed after {@code Mathf.random(0, 360)} ticks, and it is planned for
 *   rebuilding by its team ({@code addPlan(false, true)}, vanilla quirk: into the plans just dropped);</li>
 *   <li>the scheduled buildings move to derelict, 1,000 per {@code Call.setTeams} ({@link #setTeams});</li>
 *   <li>each of its units is killed after {@code Mathf.random(0, 300)} ticks, if it is still on the team then.</li>
 * </ul>
 *
 * <p>Vanilla fires {@code CoreChangeEvent} from {@code created} and {@code changeTeam} too, but the check needs the
 * core no longer added, which a created or recoloured core is by the next frame, so only destructions are posted
 * ({@link Core#onDestroyed}).
 */
@Register
public final class DeadTeams implements PhaseHandler{
    @Override
    public Phase phase(){
        return Phase.TEAMS;
    }

    /** After {@link PostedDestroys} (-300), before the team stats. */
    @Override
    public int order(){
        return -290;
    }

    /** {@code Logic.update} does this before its {@code isPaused} check. */
    @Override
    public boolean runsWhilePaused(){
        return true;
    }

    @Override
    public void run(World world){
        Teams teams = Teams.of(world);
        if(teams == null || teams.coreChanges.isEmpty()) return;
        var posted = new ArrayList<>(teams.coreChanges);
        teams.coreChanges.clear();
        Rules rules = UnitCalls.rules(world);
        for(Core core : posted){
            if(rules.cleanupDeadTeams && rules.pvp && !core.isAdded() && core.team != Team.DERELICT && teams.cores(core.team).isEmpty()){
                destroyToDerelict(world, core.team);
            }
        }
    }

    /** {@code TeamData.destroyToDerelict()} (Teams.java:331). */
    public static void destroyToDerelict(World world, int team){
        Buildings buildings = world.buildings();
        var builds = new ArrayList<Building>();
        BuildingTree tree = buildings.tree(team);
        if(tree != null) tree.getObjects(builds::add);
        Teams teams = Teams.of(world);
        if(teams != null) teams.get(team).plans.clear();
        var derelict = new IntSeq();
        for(Building b : builds){
            if(world.tiles().table().privileged(b.block().id())) continue;
            if(b instanceof Core) buildings.kill(b);
            else scheduleDerelict(world, b, derelict);
        }
        finishScheduleDerelict(world, derelict);
        // kill all units randomly
        TeamUnits counts = TeamUnits.of(world);
        if(counts == null) return;
        UnitSeq units = counts.units(team);
        for(int i = 0; i < units.size(); i++){
            world.timers().run(Mathf.random(world.rand(), 0f, 60f * 5f), new KillUnit(units.get(i), team));
        }
    }

    /** {@code scheduleDerelict(build)} (Teams.java:399). */
    private static void scheduleDerelict(World world, Building build, IntSeq derelict){
        // queue block to be handled later, avoid packet spam
        derelict.add(build.pos());
        if(build.getPayload() instanceof Payload.UnitPayload) destroyPayload(build);
        Rand rand = world.rand();
        if(Mathf.chance(rand, 0.2)) world.timers().run(Mathf.random(rand, 0f, 60f * 6f), new KillBuilding(build));
        // don't bother checking previous for performance reasons
        RebuildPlans.addPlan(build, false, true);
    }

    /** {@code finishScheduleDerelict()}: {@code derelictBuffer.chunked(1000, values -> Call.setTeams(values, derelict))}. */
    private static void finishScheduleDerelict(World world, IntSeq derelict){
        for(int start = 0; start < derelict.size; start += 1000){
            int[] chunk = Arrays.copyOfRange(derelict.items, start, Math.min(derelict.size, start + 1000));
            setTeams(world, chunk, Team.DERELICT);
        }
    }

    /**
     * {@code Call.destroyPayload(build)} (Teams.java:506): a unit the building carries is dumped, if it can be, then
     * killed; the Call goes out after.
     */
    static void destroyPayload(Building build){
        if(build.getPayload() instanceof Payload.UnitPayload && build.takePayload() instanceof Payload.UnitPayload payload){
            payload.dump();
            payload.unit().killed();
        }
        World world = build.world();
        world.publish(new TeamEvent.DestroyPayload(world.ticks(), build.pos()));
    }

    /**
     * {@code Call.setTeams(positions, team)} (Tile.java:861), run here then sent: each building at a position changes
     * team without its power update, the graphs it and its laser links were in marked stale; then every linked building
     * outside the list, then every listed one, cuts its links to other teams' buildings in a stale graph and reflows.
     * Vanilla visits the linked buildings in an {@code ObjectSet}'s identity-hash order; Shard visits them in the order
     * they were found (proximity-order exception, ARCHITECTURE.md §1).
     */
    public static void setTeams(World world, int[] positions, int team){
        Buildings buildings = world.buildings();
        var stale = Collections.newSetFromMap(new IdentityHashMap<PowerGraph, Boolean>());
        var linked = new LinkedHashSet<Building>();
        for(int pos : positions){
            Building build = at(world, pos);
            if(build == null) continue;
            PowerModule power = build.power;
            if(power != null){
                stale.add(power.graph);
                for(int i = 0; i < power.links.size; i++){
                    Building other = at(world, power.links.items[i]);
                    if(other != null && other.power != null){
                        linked.add(other);
                        stale.add(other.power.graph);
                    }
                }
            }
            buildings.changeTeam(build, team, buildings.cause(), false);
        }
        for(Building external : linked) reflowPower(world, team, external, stale);
        // update power graphs in a second pass
        for(int pos : positions) reflowPower(world, team, at(world, pos), stale);
        world.publish(new TeamEvent.SetTeams(world.ticks(), positions, team));
    }

    /** {@code Tile.reflowPower(team, build)} (Tile.java:897). */
    private static void reflowPower(World world, int team, Building build, Set<PowerGraph> stale){
        if(build == null || build.power == null || !stale.contains(build.power.graph)) return;
        IntSeq links = build.power.links;
        for(int i = 0; i < links.size; i++){
            Building other = at(world, links.items[i]);
            // only reflow links that were connected to the old power graph; ones that have a new one were already covered
            if(other != null && other.team != team && other.power != null && stale.contains(other.power.graph)){
                links.removeIndex(i);
                other.power.links.removeValue(build.pos());
                PowerGraph.create(world).reflow(other);
                i--;
            }
        }
        PowerGraph.create(world).reflow(build);
        PowerGraph.updatePowerGraph(build);
    }

    /** {@code world.build(pos)} for a {@code Point2.pack} position. */
    private static Building at(World world, int pos){
        int x = Point2.x(pos), y = Point2.y(pos);
        return world.tiles().inBounds(x, y) ? world.buildings().at(x, y) : null;
    }

    /** {@code Time.run(delay, build::kill)}: {@code Call.buildDestroyed(build)}. */
    public record KillBuilding(Building building) implements Timers.Task{
        @Override
        public void run(World world){
            world.buildings().kill(building);
        }
    }

    /** {@code Time.run(delay, () -> { if(u.team == team) u.kill(); })}: ensure the unit hasn't switched teams. */
    public record KillUnit(Unit unit, int team) implements Timers.Task{
        @Override
        public void run(World world){
            if(unit.team == team) unit.kill();
        }
    }
}
