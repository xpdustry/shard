package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.registry.*;

/**
 * {@code SyncComp.update}, in every unit class (between {@code StatusComp} and {@code UnitComp}): a server interpolates
 * a player's unit toward what its client last reported ({@link RemoteSync}), after {@code VelComp} moved it by its
 * velocity, so the report wins. Other units are the server's own and stay as they moved. A player who takes a unit
 * over snaps its interpolation to where it is ({@link RemoteSync#snap}), so it stays there, whatever its velocity,
 * until the first snapshot.
 */
@Register
public final class SyncComponent implements UnitComponent{
    @Override
    public String name(){
        return "SyncComp";
    }

    @Override
    public boolean applies(UnitParams params){
        return true;
    }

    @Override
    public void update(Unit unit){
        // (net.client() && !isLocal()) || isRemote(): on a server, a player's unit
        if(!unit.isRemote()) return;
        RemoteSync sync = unit.attachment(RemoteSync.KEY);
        if(sync != null) sync.interpolate(unit);
    }
}
