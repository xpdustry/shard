package com.xpdustry.shard.sim.ai;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code HugAI} (HugAI.java): walks the flowfield, and once its target is in range with a clear line, runs
 * straight at it and circles it up close.
 */
public class HugAi extends AiController{
    public static final String KIND = "hug-ai";

    public HugAi(World world){
        super(world, KIND);
    }

    /** A blank controller for a checkpoint to fill: no draws. */
    public HugAi(){
    }

    protected HugAi(HugAi source, CopyContext context){
        super(source, context);
    }

    @Override
    public void updateMovement(){
        AiType type = type();
        Rules rules = UnitCalls.rules(unit.world());
        Building core = closestEnemyCore();

        if(core != null && unit.within(core.x(), core.y(), type.range / 1.1f + core.block().size() * TILESIZE / 2f)){
            target = core;
            for(WeaponMount mount : unit.mounts){
                Weapon weapon = Weapon.of(unit, mount);
                if(weapon.controllable && weapon.bullet.collidesGround) mount.target = core;
            }
        }

        boolean move = true;

        if(rules.waves && unit.team == rules.defaultTeam){
            int spawner = closestSpawner();
            if(spawner != -1 && withinTile(spawner, rules.dropZoneRadius + 120f)) move = false;
        }

        // raycast for target
        if(target != null && Targets.within(target, unit.x, unit.y, type.typeRange) && !raycast(unit.tileX(), unit.tileY(), tileX(target), tileY(target), (x, y) -> {
            for(Point2 p : Geometry.d4c){
                if(!unit.canPass(x + p.x(), y + p.y())) return true;
            }
            return false;
        })){
            float tx = Targets.x(target), ty = Targets.y(target);
            if(Targets.within(target, unit.x, unit.y, (unit.hitSize + (target != null ? Targets.hitSize(target) : 1f)) * 0.5f)){
                // circle target
                unit.movePref(new Vec2(tx, ty).sub(unit.x, unit.y).rotate(90f).setLength(unit.speed()));
            }else{
                // move toward target in a straight line
                unit.movePref(new Vec2(tx, ty).sub(unit.x, unit.y).limit(unit.speed()));
            }
        }else if(move){
            pathfind();
        }

        if(unit.params().canBoost() && unit.elevation > 0.001f && !unit.onSolid()){
            unit.elevation = Mathf.approachDelta(unit.elevation, 0f, unit.params().descentSpeed());
        }

        faceTarget();
    }

    @Override
    public UnitController copy(CopyContext context){
        return new HugAi(this, context);
    }
}
