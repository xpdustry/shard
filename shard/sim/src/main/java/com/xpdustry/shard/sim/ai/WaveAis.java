package com.xpdustry.shard.sim.ai;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/** The AI kinds this package ports, by the content's names. */
@Register
public final class WaveAis implements AiKinds{
    @Override
    public AiController create(World world, String kind){
        return switch(kind){
            case AiController.GROUND -> new GroundAi(world);
            case AiController.FLYING -> new FlyingAi(world);
            case AiController.MISSILE -> new MissileAi(world);
            case SuicideAi.KIND -> new SuicideAi(world);
            case HugAi.KIND -> new HugAi(world);
            case FlyingFollowAi.KIND -> new FlyingFollowAi(world);
            case DefenderAi.KIND -> new DefenderAi(world);
            case BoostAi.KIND -> new BoostAi(world);
            default -> null;
        };
    }
}
