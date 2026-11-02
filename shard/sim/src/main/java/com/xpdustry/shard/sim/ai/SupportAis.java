package com.xpdustry.shard.sim.ai;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * The support AIs (WP70), by the content's names: the miner, the builder (also the {@code assist} command's, which
 * only follows players) and the repairer. Each is made as vanilla's constructor makes it, two timers drawn.
 */
@Register
public final class SupportAis implements AiKinds{
    @Override
    public AiController create(World world, String kind){
        return switch(kind){
            case MinerAi.KIND -> new MinerAi(world);
            case BuilderAi.KIND -> new BuilderAi(world);
            case Controllers.ASSIST_AI -> {
                var ai = new BuilderAi(world);
                ai.onlyAssist = true;
                yield ai;
            }
            case RepairAi.KIND -> new RepairAi(world);
            case CargoAi.KIND -> new CargoAi(world);
            case PrebuildAi.KIND -> new PrebuildAi(world);
            default -> null;
        };
    }
}
