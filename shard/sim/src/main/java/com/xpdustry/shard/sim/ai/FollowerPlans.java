package com.xpdustry.shard.sim.ai;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * A following builder holds its leader's plan object ({@code unit.plans.addFirst(following.buildPlan())}), and both
 * units' builders and the follower's AI work on it, in whatever order the units update. A fork or a restore copies each
 * unit's plans on its own, so each copied follower stores where its plan was in its leader's queue
 * ({@link BuilderAi#planLink}), and this part hands it that object again once the copy's units are all in place: when
 * the fork is filled ({@link #forked}), or when the restore rebuilds derived parts, after every object is read.
 *
 * <p>{@link Persistence#DERIVED}: it holds nothing; a world without followers finds none in one pass over its units.
 */
@Register
public final class FollowerPlans implements WorldPart{
    @Override
    public WorldPart copy(CopyContext context){
        return new FollowerPlans();
    }

    @Override
    public void forked(World fork){
        relink(fork);
    }

    @Override
    public void hash(Hasher hasher){
    }

    @Override
    public Persistence persistence(){
        return Persistence.DERIVED;
    }

    @Override
    public void rebuild(World world){
        relink(world);
    }

    private static void relink(World world){
        ActiveList<Unit> units = world.units();
        for(int i = 0; i < units.size(); i++){
            UnitController controller = units.get(i).controller;
            if(controller instanceof CommandAi command) controller = command.commandController;
            if(controller instanceof BuilderAi builder) builder.relink();
        }
    }
}
