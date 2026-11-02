package com.xpdustry.shard.sim.objectives;

import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code GameStats} ({@code state.stats}), which the save's meta carries: what the objectives read
 * ({@link #placedBlockCount}, {@link #coreItemCount}, {@link #enemyUnitsDestroyed}) and the game-over screen's counts.
 * Counts by content are by id; vanilla keys them by object in identity-hash order, which only its JSON shows.
 *
 * <p>Kept by Shard's construction, units, unit factories and cores (see {@link ObjectiveState}); {@link #wavesLasted} is
 * set only by a campaign's or a client's game over.
 */
public final class GameStats implements Checkpointable{
    /** Enemy (not the default team's) units destroyed. */
    public int enemyUnitsDestroyed;
    public int wavesLasted;
    /** The default team's buildings fully built. */
    public int buildingsBuilt;
    public int buildingsDeconstructed;
    public int buildingsDestroyed;
    public int unitsCreated;
    /** Blocks the default team finished building, by block id: build-count objectives read it. */
    public TreeMap<Integer, Integer> placedBlockCount = new TreeMap<>();
    /** Other teams' blocks destroyed, by block id. */
    public TreeMap<Integer, Integer> destroyedBlockCount = new TreeMap<>();
    /** Items that entered the default team's cores through blocks, by item id: core-item objectives read it. */
    public TreeMap<Integer, Integer> coreItemCount = new TreeMap<>();

    public GameStats copy(){
        var out = new GameStats();
        out.enemyUnitsDestroyed = enemyUnitsDestroyed;
        out.wavesLasted = wavesLasted;
        out.buildingsBuilt = buildingsBuilt;
        out.buildingsDeconstructed = buildingsDeconstructed;
        out.buildingsDestroyed = buildingsDestroyed;
        out.unitsCreated = unitsCreated;
        out.placedBlockCount = new TreeMap<>(placedBlockCount);
        out.destroyedBlockCount = new TreeMap<>(destroyedBlockCount);
        out.coreItemCount = new TreeMap<>(coreItemCount);
        return out;
    }

    /** {@code ObjectIntMap.increment(key)}. */
    static void increment(TreeMap<Integer, Integer> counts, int key){
        counts.merge(key, 1, Integer::sum);
    }

    public void hash(Hasher hasher){
        hasher.i(enemyUnitsDestroyed).i(wavesLasted).i(buildingsBuilt).i(buildingsDeconstructed).i(buildingsDestroyed).i(unitsCreated);
        hash(hasher, placedBlockCount);
        hash(hasher, destroyedBlockCount);
        hash(hasher, coreItemCount);
    }

    private static void hash(Hasher hasher, TreeMap<Integer, Integer> counts){
        hasher.i(counts.size());
        counts.forEach((key, count) -> hasher.i(key).i(count));
    }

    @Override
    public void write(CheckpointOut out){
        out.i("enemyUnitsDestroyed", enemyUnitsDestroyed).i("wavesLasted", wavesLasted).i("buildingsBuilt", buildingsBuilt)
            .i("buildingsDeconstructed", buildingsDeconstructed).i("buildingsDestroyed", buildingsDestroyed).i("unitsCreated", unitsCreated)
            .ints("placedBlockCount", pairs(placedBlockCount)).ints("destroyedBlockCount", pairs(destroyedBlockCount))
            .ints("coreItemCount", pairs(coreItemCount));
    }

    @Override
    public void read(CheckpointIn in){
        enemyUnitsDestroyed = in.i("enemyUnitsDestroyed");
        wavesLasted = in.i("wavesLasted");
        buildingsBuilt = in.i("buildingsBuilt");
        buildingsDeconstructed = in.i("buildingsDeconstructed");
        buildingsDestroyed = in.i("buildingsDestroyed");
        unitsCreated = in.i("unitsCreated");
        placedBlockCount = counts(in.ints("placedBlockCount"));
        destroyedBlockCount = counts(in.ints("destroyedBlockCount"));
        coreItemCount = counts(in.ints("coreItemCount"));
    }

    private static int[] pairs(TreeMap<Integer, Integer> counts){
        int[] out = new int[counts.size() * 2];
        int i = 0;
        for(var entry : counts.entrySet()){
            out[i++] = entry.getKey();
            out[i++] = entry.getValue();
        }
        return out;
    }

    private static TreeMap<Integer, Integer> counts(int[] pairs){
        var out = new TreeMap<Integer, Integer>();
        for(int i = 0; i < pairs.length; i += 2) out.put(pairs[i], pairs[i + 1]);
        return out;
    }
}
