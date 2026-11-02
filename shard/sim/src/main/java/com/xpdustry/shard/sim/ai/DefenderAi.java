package com.xpdustry.shard.sim.ai;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code DefenderAI} (DefenderAI.java), for support units that guard others (oct, quad's kin): follows the
 * toughest nearby unit of its team, else its own core, else, on the wave team, the enemy core.
 */
public class DefenderAi extends AiController{
    public static final String KIND = "defender-ai";

    public DefenderAi(World world){
        super(world, KIND);
    }

    /** A blank controller for a checkpoint to fill: no draws. */
    public DefenderAi(){
    }

    protected DefenderAi(DefenderAi source, CopyContext context){
        super(source, context);
    }

    @Override
    public void updateMovement(){
        unloadPayloads();
        if(target != null){
            moveTo(Targets.x(target), Targets.y(target), Targets.hitSize(target) / 2f * 1.1f + unit.hitSize / 2f + 15f, 50f);
            unit.lookAt(Targets.x(target), Targets.y(target));
        }
    }

    @Override
    public void updateTargeting(){
        if(retarget()) target = findTarget(unit.x, unit.y, type().range, true, true);
    }

    @Override
    public Entity findTarget(float x, float y, float range, boolean air, boolean ground){
        // sort by max health and closer target
        SpatialIndex index = SpatialIndex.of(unit.world());
        Unit self = unit;
        Unit result = index == null ? null : index.closest(unit.team, x, y, Math.max(range, 400f),
            u -> !u.dead && u.type() != self.type() && u.targetable(self.team) && u.params().playerControllable(),
            (u, tx, ty) -> -u.maxHealth + Mathf.dst2(u.x, u.y, tx, ty) / 6400f);
        if(result != null) return result;

        // return core if found
        Entity core = closestCore();
        if(core != null) return core;

        // for enemies, target the enemy core
        Rules rules = UnitCalls.rules(unit.world());
        if(rules.waves && unit.team == rules.waveTeam) return closestEnemyCore();

        return null;
    }

    @Override
    public UnitController copy(CopyContext context){
        return new DefenderAi(this, context);
    }
}
