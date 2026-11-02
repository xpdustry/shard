package com.xpdustry.shard.sim.blocks.payload;

import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.unit.*;

/**
 * Vanilla's {@code PayloadVoid.PayloadVoidBuild} (PayloadVoid.java:38): takes any payload, any unit that enters it too,
 * pulls it to its centre without turning it, and there destroys it while it has any efficiency. Its explosion effect
 * and sound draw nothing on a server. It saves only the payload block's body.
 */
@Kind("payload-void")
public final class PayloadVoid extends PayloadBuild<Payload>{
    @Override
    public boolean acceptUnitPayload(Unit unit){
        return true;
    }

    @Override
    public void updateTile(){
        super.updateTile();
        if(moveInPayload(false) && efficiency > 0){
            payload = null;
        }
    }
}
