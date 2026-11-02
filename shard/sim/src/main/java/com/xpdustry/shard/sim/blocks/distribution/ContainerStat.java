package com.xpdustry.shard.sim.blocks.distribution;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code Unloader.ContainerStat}: what an unloader knows of one neighbour it can take from or give to. The
 * objects are pooled ({@link ContainerStats}); {@code reset} clears only {@link #building}, so a reused one keeps
 * the {@link #lastUsed} of its previous neighbour, which vanilla's unloader fairness then reads.
 */
public final class ContainerStat implements Checkpointable{
    public Building building;
    public float loadFactor;
    public boolean canLoad;
    public boolean canUnload;
    /** {@code !(building instanceof StorageBuild)}, cached. */
    public boolean notStorage;
    public int lastUsed;

    ContainerStat copy(CopyContext context){
        var out = new ContainerStat();
        out.building = building == null ? null : context.map(building);
        out.loadFactor = loadFactor;
        out.canLoad = canLoad;
        out.canUnload = canUnload;
        out.notStorage = notStorage;
        out.lastUsed = lastUsed;
        return out;
    }

    void hash(Hasher hasher){
        hasher.i(building == null ? -1 : building.id()).f(loadFactor).bool(canLoad).bool(canUnload).bool(notStorage).i(lastUsed);
    }

    @Override
    public void write(CheckpointOut out){
        out.ref("building", building).f("loadFactor", loadFactor).bool("canLoad", canLoad).bool("canUnload", canUnload)
            .bool("notStorage", notStorage).i("lastUsed", lastUsed);
    }

    @Override
    public void read(CheckpointIn in){
        building = in.ref("building", Building.class);
        loadFactor = in.f("loadFactor");
        canLoad = in.bool("canLoad");
        canUnload = in.bool("canUnload");
        notStorage = in.bool("notStorage");
        lastUsed = in.i("lastUsed");
    }
}
