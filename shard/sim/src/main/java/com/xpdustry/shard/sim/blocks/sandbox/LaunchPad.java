package com.xpdustry.shard.sim.blocks.sandbox;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code LaunchPad.LaunchPadBuild} (LaunchPad.java), the launch pad and the advanced launch pad, as a
 * server hosts them off the campaign: it takes items up to its capacity (one kind unless {@code acceptMultipleItems}),
 * counts {@code launchTime} while it has power, and once full sends them up in a {@link LaunchPayload}, which takes
 * them nowhere. Choosing a destination is the campaign's.
 */
@Kind("launch-pad")
public final class LaunchPad extends Building implements Checkpointable{
    public float launchCounter;
    @Derived("the block's launchTime, read on first use")
    private float launchTime = Float.NaN;
    @Derived("the block's acceptMultipleItems, read on first use")
    private int acceptMultipleItems = -1;

    @Override
    public boolean shouldConsume(){
        return launchCounter < launchTime();
    }

    @Override
    public double sense(LAccess sensor){
        if(sensor == LAccess.progress) return Mathf.clamp(launchCounter / launchTime());
        return super.sense(sensor);
    }

    @Override
    public boolean acceptItem(Building source, int item){
        int total = items.total();
        return total < info().itemCapacity && (acceptMultipleItems() || total == 0 || items.first() == item);
    }

    @Override
    public void updateTile(){
        // increment launchCounter then launch when full and base conditions are met
        if((launchCounter += edelta()) >= launchTime() && items.total() >= info().itemCapacity){
            consume();
            World world = world();
            // launchSound's pitch
            Mathf.range(world.rand(), block().baked() ? block().fields().f("launchSoundPitchRand") : 0.1f);
            LaunchPayload entity = LaunchPayload.create(world);
            var stacks = new IntSeq();
            items.each((item, amount) -> stacks.add(item, amount));
            entity.stacks = stacks.toArray();
            entity.x = x();
            entity.y = y();
            entity.lifetime = 120f;
            entity.team = team;
            entity.add();
            items.clear();
            launchCounter = 0f;
        }
    }

    private float launchTime(){
        if(Float.isNaN(launchTime)) launchTime = block().baked() ? block().fields().f("launchTime") : 1f;
        return launchTime;
    }

    private boolean acceptMultipleItems(){
        if(acceptMultipleItems == -1) acceptMultipleItems = block().baked() && block().fields().b("acceptMultipleItems") ? 1 : 0;
        return acceptMultipleItems == 1;
    }

    @Override
    public void write(CheckpointOut out){
        out.f("launchCounter", launchCounter);
    }

    @Override
    public void read(CheckpointIn in){
        launchCounter = in.f("launchCounter");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (LaunchPad)into;
        out.launchCounter = launchCounter;
        out.launchTime = launchTime;
        out.acceptMultipleItems = acceptMultipleItems;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.f(launchCounter);
    }
}
