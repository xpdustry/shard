package com.xpdustry.shard.sim.blocks.crafting;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code Incinerator.IncineratorBuild} (Incinerator.java:29): while powered its {@link #heat} rises to its
 * efficiency, and once past half it takes any item, and any incinerable liquid, and destroys it. Each item draws its
 * burn effect's chance (0.3) from the world's random, each liquid transfer its own (0.02), as vanilla's do.
 *
 * <p>Vanilla saves none of it: a loaded incinerator starts cold.
 */
@Kind("incinerator")
public final class Incinerator extends Building implements Checkpointable{
    public float heat;

    @Override
    public void updateTile(){
        heat = Mathf.approachDelta(heat, efficiency, 0.04f);
    }

    @Override
    public BlockStatus status(double stateTick){
        return !enabled ? BlockStatus.logicDisable : heat > 0.5f ? BlockStatus.active : BlockStatus.noInput;
    }

    @Override
    public void handleItem(Building source, int item){
        // the burn effect's chance
        Mathf.chance(world().rand(), 0.3);
    }

    @Override
    public boolean acceptItem(Building source, int item){
        return heat > 0.5f && enabled;
    }

    @Override
    public void handleLiquid(Building source, int liquid, float amount){
        Mathf.chance(world().rand(), 0.02);
    }

    @Override
    public boolean acceptLiquid(Building source, int liquid){
        return heat > 0.5f && world().content().liquid(liquid).incinerable() && enabled;
    }

    @Override
    public void write(CheckpointOut out){
        out.f("heat", heat);
    }

    @Override
    public void read(CheckpointIn in){
        heat = in.f("heat");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        ((Incinerator)into).heat = heat;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(heat);
    }
}
