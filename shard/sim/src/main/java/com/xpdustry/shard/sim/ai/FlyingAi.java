package com.xpdustry.shard.sim.ai;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code FlyingAI} (FlyingAI.java): flies at its target, circling through it or hovering in range, and on
 * the default team in wave mode holds off near the closest spawn. Targets: an enemy core in range, else its type's
 * {@code targetFlags} in order (or, with {@code randomWaveAI}, a few random building flags seeded by the wave), else
 * the closest enemy core anywhere.
 */
public class FlyingAi extends AiController{
    /** {@code randomTargets}. */
    static final BlockFlag[] RANDOM_TARGETS = {BlockFlag.core, BlockFlag.storage, BlockFlag.generator, BlockFlag.launchPad,
        BlockFlag.factory, BlockFlag.repair, BlockFlag.battery, BlockFlag.reactor, BlockFlag.drill};

    public FlyingAi(World world){
        this(world, AiController.FLYING);
    }

    protected FlyingAi(World world, String kind){
        super(world, kind);
    }

    /** A blank controller for a checkpoint to fill: no draws. */
    public FlyingAi(){
    }

    protected FlyingAi(FlyingAi source, CopyContext context){
        super(source, context);
    }

    @Override
    public void updateMovement(){
        unloadPayloads();
        AiType type = type();
        Rules rules = UnitCalls.rules(unit.world());

        if(target != null && type.hasWeapons){
            if(type.circleTarget){
                circleAttack(type.circleTargetRadius);
            }else{
                moveTo(Targets.x(target), Targets.y(target), type.typeRange * 0.8f);
                unit.lookAt(Targets.x(target), Targets.y(target));
            }
        }

        if(target == null && rules.waves && unit.team == rules.defaultTeam){
            int spawner = closestSpawner();
            int width = unit.world().tiles().width();
            if(spawner != -1) moveTo(spawner % width * TILESIZE, spawner / width * TILESIZE, rules.dropZoneRadius + 130f);
        }
    }

    @Override
    public Entity findTarget(float x, float y, float range, boolean air, boolean ground){
        Entity result = findMainTarget(x, y, range, air, ground);
        // if the main target is in range, use it, otherwise target whatever is closest
        return checkTarget(result, x, y, range) ? target(x, y, range, air, ground) : result;
    }

    @Override
    public Entity findMainTarget(float x, float y, float range, boolean air, boolean ground){
        Building core = targetFlag(x, y, BlockFlag.core, true);

        if(core != null && Mathf.within(x, y, core.x(), core.y(), range)) return core;

        Rules rules = UnitCalls.rules(unit.world());
        if(rules.randomWaveAI){
            WaveState waves = WaveState.of(unit.world());
            // when there are no waves, it's just random based on the unit
            var rand = new Rand(0);
            rand.setSeed(unit.type().id() + (rules.waves ? (waves == null ? 0 : waves.wave) : unit.id()));
            // try a few random flags first
            for(int attempt = 0; attempt < 5; attempt++){
                Building result = targetFlagActive(x, y, RANDOM_TARGETS[rand.random(RANDOM_TARGETS.length - 1)], true);
                if(result != null) return result;
            }
            // try the closest target
            Entity result = target(x, y, range, air, ground);
            if(result != null) return result;
        }else{
            for(BlockFlag flag : type().targetFlags){
                if(flag == null){
                    Entity result = target(x, y, range, air, ground);
                    if(result != null) return result;
                }else if(ground){
                    Entity result = targetFlagActive(x, y, flag, true);
                    if(result != null) return result;
                }
            }
        }

        return core;
    }

    @Override
    public UnitController copy(CopyContext context){
        return new FlyingAi(this, context);
    }
}
