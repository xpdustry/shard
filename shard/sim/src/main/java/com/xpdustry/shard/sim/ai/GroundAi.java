package com.xpdustry.shard.sim.ai;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.spatial.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code GroundAI} (GroundAI.java): walks the team's enemy-core flowfield until the core is in half its
 * range, shoots what comes in range (the core when it is close), and, when it hasn't moved {@code 1.5} tiles in twice
 * the time that should take, steps around the units ahead ({@link com.xpdustry.shard.sim.path.AvoidanceProcess}) and
 * tells the ground AIs around it to do the same.
 */
public class GroundAi extends AiController{
    /** {@code stuckRange}. */
    static final float STUCK_RANGE = TILESIZE * 1.5f;
    /** {@code Vars.unitCollisionRadiusScale}. */
    static final float RADIUS_SCALE = 0.6f;

    public float stuckTime;
    public float stuckX = -999f, stuckY = -999f;

    public GroundAi(World world){
        this(world, AiController.GROUND);
    }

    protected GroundAi(World world, String kind){
        super(world, kind);
    }

    /** A blank controller for a checkpoint to fill: no draws. */
    public GroundAi(){
    }

    protected GroundAi(GroundAi source, CopyContext context){
        super(source, context);
        stuckTime = source.stuckTime;
        stuckX = source.stuckX;
        stuckY = source.stuckY;
    }

    @Override
    public void updateMovement(){
        Rules rules = UnitCalls.rules(unit.world());
        AiType type = type();
        // if it hasn't moved the stuck range in twice the time it should have taken, it's stuck
        float stuckThreshold = Math.max(1f, STUCK_RANGE * 2f / unit.params().speed());

        Building core = closestEnemyCore();
        boolean moved = false;

        if(core != null && unit.within(core.x(), core.y(), type.range / 1.3f + core.block().size() * TILESIZE / 2f)){
            target = core;
            for(WeaponMount mount : unit.mounts){
                Weapon weapon = Weapon.of(unit, mount);
                if(weapon.controllable && weapon.bullet.collidesGround) mount.target = core;
            }
        }

        if(core == null || !unit.within(core.x(), core.y(), type.typeRange * 0.5f)){
            boolean move = true;

            if(rules.waves && unit.team == rules.defaultTeam){
                int spawner = closestSpawner();
                if(spawner != -1 && withinTile(spawner, rules.dropZoneRadius + 120f)) move = false;
                if(spawner == -1 && core == null) move = false;
            }

            // no reason to move if there's nothing there
            if(core == null && (!rules.waves || closestSpawner() == -1)) move = false;

            moved = move;

            if(move) pathfind(true, stuckTime >= stuckThreshold);
        }

        if(unit.params().canBoost() && unit.elevation > 0.001f && !unit.onSolid()){
            unit.elevation = Mathf.approachDelta(unit.elevation, 0f, unit.params().descentSpeed());
        }

        faceTarget();

        if(moved){
            if(unit.within(stuckX, stuckY, STUCK_RANGE)){
                stuckTime += 1f;
                if(stuckTime - 1f < stuckThreshold && stuckTime >= stuckThreshold){
                    float radius = unit.hitSize * RADIUS_SCALE * 2f;
                    SpatialIndex index = SpatialIndex.of(unit.world());
                    if(index != null){
                        Unit self = unit;
                        index.nearby(unit.team, unit.x, unit.y, radius, other -> {
                            if(other != self && other.controller instanceof GroundAi ai && other.within(self.x, self.y, radius + other.hitSize * RADIUS_SCALE)){
                                ai.stuckX = other.x;
                                ai.stuckY = other.y;
                                ai.stuckTime = Math.max(1f, STUCK_RANGE * 2f / other.params().speed()) + 1f;
                            }
                        });
                    }
                }
            }else{
                stuckX = unit.x;
                stuckY = unit.y;
                stuckTime = 0f;
            }
        }else{
            stuckTime = 0f;
        }
    }

    @Override
    public UnitController copy(CopyContext context){
        return new GroundAi(this, context);
    }

    @Override
    public void hash(Hasher hasher){
        super.hash(hasher);
        hasher.f(stuckTime).f(stuckX).f(stuckY);
    }

    @Override
    public void write(CheckpointOut out){
        super.write(out);
        out.f("stuckTime", stuckTime).f("stuckX", stuckX).f("stuckY", stuckY);
    }

    @Override
    public void read(CheckpointIn in){
        super.read(in);
        stuckTime = in.f("stuckTime");
        stuckX = in.f("stuckX");
        stuckY = in.f("stuckY");
    }
}
