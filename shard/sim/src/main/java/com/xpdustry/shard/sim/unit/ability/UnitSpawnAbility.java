package com.xpdustry.shard.sim.unit.ability;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.objectives.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code UnitSpawnAbility} (UnitSpawnAbility.java): every {@code spawnTime} ticks, scaled by the team's unit
 * build speed, a unit of its type is made at its offset, facing the carrier's way, if the team may make one; it is
 * added at once and announced with {@code Call.unitSpawn} ({@link CombatEvent.UnitSpawned}). The timer keeps counting
 * while the team is at its cap. No vanilla unit carries it.
 */
@AbilityKind("unit-spawn-ability")
public final class UnitSpawnAbility extends Ability{
    private UnitDef unit;
    private float spawnTime, spawnX, spawnY;

    @Override
    protected void load(AbilityDef def, int index, ContentEpoch content){
        super.load(def, index, content);
        unit = def.unit(content);
        spawnTime = def.spawnTime();
        spawnX = def.spawnX();
        spawnY = def.spawnY();
    }

    @Override
    public UnitAbility create(){
        return new Timed();
    }

    @Override
    public void update(Unit carrier, UnitAbility ability){
        var state = (Timed)ability;
        World world = carrier.world();
        // Time.delta * state.rules.unitBuildSpeed(team), at Δ = 1
        state.timer += UnitCalls.rules(world).unitBuildSpeed(carrier.team);
        if(state.timer >= spawnTime && Units.canCreate(world, carrier.team, unit)){
            float x = carrier.x + Angles.trnsx(carrier.rotation, spawnY, -spawnX), y = carrier.y + Angles.trnsy(carrier.rotation, spawnY, -spawnX);
            Unit u = Unit.create(world, unit, carrier.team);
            u.set(x, y);
            u.rotation = carrier.rotation;
            // UnitCreateEvent: the game stats count the default team's
            ObjectiveState.unitCreated(world, u.team);
            u.add();
            world.publish(new CombatEvent.UnitSpawned(u.id()));
            state.timer = 0f;
        }
    }
}
