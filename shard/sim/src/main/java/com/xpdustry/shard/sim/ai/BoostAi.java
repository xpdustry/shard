package com.xpdustry.shard.sim.ai;

import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code BoostAI} (BoostAI.java), for RTS-controlled units only: the command AI's default behaviour while
 * boosting, landing on the {@code move} command once the attack target is in range.
 */
public class BoostAi extends AiController{
    public static final String KIND = "boost-ai";

    public BoostAi(World world){
        super(world, KIND);
    }

    /** A blank controller for a checkpoint to fill: no draws. */
    public BoostAi(){
    }

    protected BoostAi(BoostAi source, CopyContext context){
        super(source, context);
    }

    @Override
    public void updateUnit(Unit unit){
        this.unit = unit;
        if(unit.controller instanceof CommandAi ai){
            ai.defaultBehavior();
            unit.updateBoosting(true);
            // auto land when near target
            if(ai.attackTarget != null && Targets.within(ai.attackTarget, unit.x, unit.y, type().range)){
                ai.command(unit, unit.world().content().unitCommands().indexOf("move"));
            }
        }else{
            unit.updateBoosting(true);
        }
    }

    @Override
    public UnitController copy(CopyContext context){
        return new BoostAi(this, context);
    }
}
