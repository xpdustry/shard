package com.xpdustry.shard.sim.unit.ability;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code SpawnDeathAbility} (SpawnDeathAbility.java): on death, {@code amount + random(randAmount)} units of
 * its type are spawned around the unit, each a random distance up to {@code spread} away in a random direction, facing
 * outwards (or the unit's way, give or take 5 degrees). Latum carries it: five renales. The count draws even when
 * {@code randAmount} is 0. The new units are added at once; they reach clients through the next snapshot.
 */
@AbilityKind("spawn-death-ability")
public final class SpawnDeathAbility extends Ability{
    private UnitDef unit;
    private int amount, randAmount;
    private float spread;
    private boolean faceOutwards;

    @Override
    protected void load(AbilityDef def, int index, ContentEpoch content){
        super.load(def, index, content);
        unit = def.unit(content);
        amount = def.count();
        randAmount = def.randAmount();
        spread = def.spread();
        faceOutwards = def.faceOutwards();
    }

    @Override
    public void death(Unit dying, UnitAbility state){
        World world = dying.world();
        Rand rand = world.rand();
        int spawned = amount + Mathf.random(rand, randAmount);
        for(int i = 0; i < spawned; i++){
            // Tmp.v1.rnd(Mathf.random(spread)): the length, then the direction
            float length = Mathf.random(rand, spread);
            Vec2 offset = new Vec2().setToRandomDirection(rand).scl(length);
            Unit u = Unit.spawn(world, unit, dying.team, dying.x + offset.x, dying.y + offset.y, 0f);
            u.rotation = faceOutwards ? offset.angle() : dying.rotation + Mathf.range(rand, 5f);
        }
    }
}
