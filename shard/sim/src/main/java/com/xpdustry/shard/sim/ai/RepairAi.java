package com.xpdustry.shard.sim.ai;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.build.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code RepairAI} (RepairAI.java), for mega and the {@code repair} command: every 15 frames it picks the
 * damaged building of its team closest to it ({@code Units.findDamagedTile}, never a construction site), moves within
 * two thirds of its range and shoots it with its healing weapons; with nothing to repair it fights as any AI does, and
 * after three idle seconds near an enemy it flies home.
 */
public class RepairAi extends AiController{
    public static final String KIND = "repair-ai";
    public static final float RETREAT_DST = 160f, FLEE_RANGE = 310f, RETREAT_DELAY = 60f * 3f;

    /** {@code avoid}: the enemy it flees from, or null. */
    public Entity avoid;
    public float retreatTimer;
    /** {@code damagedTarget}: the building it repairs, or null. */
    public Building damagedTarget;

    public RepairAi(World world){
        super(world, KIND);
    }

    /** A blank controller for a checkpoint to fill: no draws. */
    public RepairAi(){
    }

    protected RepairAi(RepairAi source, CopyContext context){
        super(source, context);
        avoid = context.map(source.avoid);
        retreatTimer = source.retreatTimer;
        damagedTarget = context.map(source.damagedTarget);
    }

    @Override
    public void updateMovement(){
        AiType type = type();
        if(target instanceof Building){
            boolean shoot = false;

            if(Targets.within(target, unit.x, unit.y, type.typeRange)){
                Weapons.aim(unit, Targets.x(target), Targets.y(target));
                shoot = true;
            }

            Weapons.controlWeapons(unit, shoot);
        }else if(target == null){
            Weapons.controlWeapons(unit, false);
        }

        boolean hold = hasStance(unit.world().content().unitStances().indexOf("holdposition"));

        if(target instanceof Building b && b.team == unit.team){
            if(!hold){
                if(type.circleTarget){
                    circleAttack(type.circleTargetRadius);
                }else if(!Targets.within(target, unit.x, unit.y, type.typeRange * 0.65f)){
                    moveTo(b.x(), b.y(), type.typeRange * 0.65f);
                }
            }

            if(!type.circleTarget){
                unit.lookAt(b.x(), b.y());
            }
        }

        // not repairing
        if(!(target instanceof Building) && !hold){
            if(timerGet(TIMER_TARGET4, 40)){
                avoid = target(unit.x, unit.y, FLEE_RANGE, true, true);
            }

            if((retreatTimer += 1f) >= RETREAT_DELAY){
                // fly away from enemy when not doing anything
                if(avoid != null){
                    Building core = closestCore();
                    if(core != null && !unit.within(core.x(), core.y(), RETREAT_DST)){
                        moveTo(core.x(), core.y(), RETREAT_DST);
                    }
                }
            }
        }else{
            retreatTimer = 0f;
        }
    }

    @Override
    public void updateTargeting(){
        if(timerGet(TIMER_TARGET, 15)){
            damagedTarget = findDamagedTile();
            if(damagedTarget instanceof ConstructBuild) damagedTarget = null;
        }

        if(damagedTarget == null){
            super.updateTargeting();
        }else{
            target = damagedTarget;
        }
    }

    /** {@code Units.findDamagedTile(team, x, y)}: the closest of the team's damaged buildings, the first of equals. */
    private Building findDamagedTile(){
        List<Building> damaged = unit.world().buildings().damaged(unit.team);
        Building closest = null;
        float cdist = 0f;
        for(Building building : damaged){
            float dst = Mathf.dst2(building.x(), building.y(), unit.x, unit.y);
            if(closest == null || dst < cdist){
                closest = building;
                cdist = dst;
            }
        }
        return closest;
    }

    @Override
    public UnitController copy(CopyContext context){
        return new RepairAi(this, context);
    }

    @Override
    public void hash(Hasher hasher){
        super.hash(hasher);
        hasher.i(avoid == null ? -1 : avoid.id()).f(retreatTimer).i(damagedTarget == null ? -1 : damagedTarget.id());
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.ref("avoid", avoid).f("retreatTimer", retreatTimer).ref("damagedTarget", damagedTarget);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        avoid = in.ref("avoid", Entity.class);
        retreatTimer = in.f("retreatTimer");
        damagedTarget = in.ref("damagedTarget", Building.class);
    }
}
