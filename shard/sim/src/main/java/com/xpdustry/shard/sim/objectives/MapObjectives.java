package com.xpdustry.shard.sim.objectives;

import com.xpdustry.shard.sim.logic.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code MapObjectives} (MapObjectives.java:37), the map's objective executor, which the rules hold
 * ({@link Rules#objectives}): every objective, flattened, in the order the JSON lists them.
 *
 * <p>Each tick ({@link ObjectiveUpdate}) every qualified objective updates in order; one that is met completes at once
 * ({@link #complete}), so an objective whose last parent completes earlier in the pass runs in the same pass.
 */
public final class MapObjectives implements Checkpointable{
    /** {@code LExecutor.runLogicScript(code)}'s instruction limit. */
    public static final int SCRIPT_INSTRUCTIONS = 100_000;

    public List<MapObjective> all = new ArrayList<>(4);

    public MapObjectives(){
    }

    public MapObjectives(List<MapObjective> all){
        this.all.addAll(all);
    }

    /** {@code update()}: on a server, each objective that is met completes as {@code Call.completeObjective(index)}. */
    public void update(World world, Rules rules){
        for(int i = 0; i < all.size(); i++){
            MapObjective objective = all.get(i);
            if(objective.qualified(all) && objective.update(world, rules)) complete(world, rules, i);
        }
    }

    /**
     * {@code Call.completeObjective(index)}: the handler runs first, on the server ({@code called = Loc.server}), so the
     * Calls the completion script makes go out before this one.
     */
    public void complete(World world, Rules rules, int index){
        MapObjective objective = get(index);
        if(objective != null) done(world, rules, objective);
        world.publish(new ObjectiveEvent.Completed(world.ticks(), index));
    }

    /** {@code done()}: the flags change, the objective is completed, and its completion script runs. */
    static void done(World world, Rules rules, MapObjective objective){
        rules.objectiveFlags.removeAll(objective.flagsRemoved);
        rules.objectiveFlags.addAll(objective.flagsAdded);
        objective.completed = true;
        String code = objective.completionLogicCode;
        if(code != null && !code.isEmpty()){
            LogicPart.require(world).runScript(code, SCRIPT_INSTRUCTIONS, false, null);
        }
    }

    /** {@code get(index)}, or null out of range. */
    public MapObjective get(int index){
        return index < 0 || index >= all.size() ? null : all.get(index);
    }

    /** {@code any()}: whether any objective is qualified. */
    public boolean any(){
        for(MapObjective objective : all){
            if(objective.qualified(all)) return true;
        }
        return false;
    }

    public void clear(){
        all.clear();
    }

    public MapObjectives copy(){
        var out = new MapObjectives();
        for(MapObjective objective : all) out.all.add(objective.copy());
        return out;
    }

    public void hash(Hasher hasher){
        // none hashes as the raw "[]" the rules held before objectives were typed, so such rules hash as they did
        if(all.isEmpty()){
            hasher.str("[]");
            return;
        }
        hasher.i(all.size());
        for(MapObjective objective : all) objective.hash(hasher);
    }

    @Override
    public void write(CheckpointOut out){
        out.list("all", all);
    }

    @Override
    public void read(CheckpointIn in){
        all = new ArrayList<>(in.list("all", MapObjective.class));
    }
}
