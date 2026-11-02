package com.xpdustry.shard.sim.building;

import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/** Logs vanilla's placement callbacks as "x,y:callback", and can be told to be solid like a closed door. */
@Kind("test-probe")
public final class Probe extends Building{
    /** The callbacks of every probe, in call order. Tests clear it. */
    public static final List<String> LOG = new ArrayList<>();

    public boolean closed;

    private void log(String callback){
        LOG.add(world().tiles().x(tile()) + "," + world().tiles().y(tile()) + ":" + callback);
    }

    @Override
    public void onProximityUpdate(){
        super.onProximityUpdate();
        log("update");
    }

    @Override
    public void onProximityAdded(){
        log("added");
    }

    @Override
    public void onProximityRemoved(){
        log("removed");
    }

    @Override
    public void onRemoved(){
        log("onRemoved");
    }

    @Override
    public boolean checkSolid(){
        return closed;
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        ((Probe)into).closed = closed;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.bool(closed);
    }
}
