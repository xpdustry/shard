package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.registry.*;

/**
 * {@code MechComp.update}, for mech classes ({@code MechUnit}, between {@code ItemsComp} and {@code MinerComp}): after a
 * controlled move, or always for a player's unit ({@code isRemote()}), the legs turn toward the last movement and the
 * walk advances by it. The step shake, dust and sound are client-only ({@code !headless}), so nothing is drawn.
 */
@Register
public final class MechComponent implements UnitComponent{
    @Override
    public String name(){
        return "MechComp";
    }

    @Override
    public boolean applies(UnitParams params){
        return params.locomotion() == Locomotion.MECH;
    }

    @Override
    public void update(Unit unit){
        var mech = (MoveShape.Mech)unit.shape;
        UnitParams params = unit.params();
        // net.client() is false on a server
        if(mech.walked || unit.isRemote()){
            float len = Mathf.len(unit.deltaX, unit.deltaY);
            // baseRotateSpeed * clamp(len / speed / Time.delta) * Time.delta, at Δ = 1
            mech.baseRotation = Angles.moveToward(mech.baseRotation, Mathf.angle(unit.deltaX, unit.deltaY),
                params.move().baseRotateSpeed() * Mathf.clamp(len / params.speed()));
            mech.walkTime += len;
            mech.walked = false;
        }
        mech.walkExtension = MoveShape.Mech.walkExtendScaled(mech.walkTime, params.move().mechStride()) % 1f;
    }
}
