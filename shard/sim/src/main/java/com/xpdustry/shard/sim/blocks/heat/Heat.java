package com.xpdustry.shard.sim.blocks.heat;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.tile.*;

import java.util.*;

/** Vanilla's {@code BuildingComp.calculateHeat} (BuildingComp.java:412–464), the heat a {@link HeatConsumer} takes in. */
public final class Heat{
    private Heat(){
    }

    /** {@code calculateHeat(sideHeat)}: without cycle tracking, as every consumer but a conductor calls it. */
    public static float calculate(Building self, float[] sideHeat){
        return calculate(self, sideHeat, null);
    }

    /**
     * {@code calculateHeat(sideHeat, cameFrom)}: the heat of the same-team {@link HeatBlock}s in {@code self}'s
     * proximity, in proximity order, that face it. A heater or redirector faces it when its rotation points at
     * {@code self}; a router (a conductor with {@code splitHeat}) when it doesn't point away from it, and gives a third
     * of its heat to each of its three outputs. Unrotated heat blocks always count. Each one's heat is spread over its
     * size and scaled by how many tiles of edge the two share. A conductor that already took heat from {@code self}
     * ({@code cameFrom}) gives none back, so cycles don't feed themselves; every facing heater's id (and a conductor's
     * own {@code cameFrom}) goes into {@code cameFrom} when there is one. A facing conductor is brought up to date
     * after its heat is read, so it gives last tick's heat the first time it is read in a tick.
     */
    public static float calculate(Building self, float[] sideHeat, IntSet cameFrom){
        Arrays.fill(sideHeat, 0f);
        if(cameFrom != null) cameFrom.clear();
        float heat = 0f;
        int size = self.block().size();
        for(int i = 0; i < self.proximitySize(); i++){
            Building build = self.proximity(i);
            if(build != null && build.team == self.team && build instanceof HeatBlock heater){
                boolean split = build instanceof HeatConductor cond && cond.splitHeat();
                int relative = self.relativeTo(build);
                // non-routers must face us, routers must face away; next to a redirector, cycles force them to face away anyway
                if(!build.block().rotate() || (!split && (relative + 2) % 4 == build.rotation) || (split && relative != build.rotation)){
                    // if there's a cycle, ignore its heat
                    if(!(build instanceof HeatConductor conductor && conductor.cameFrom.contains(self.id()))){
                        // x/y coordinate difference across the point of contact
                        float diff = Math.min(Math.abs(build.x() - self.x()), Math.abs(build.y() - self.y())) / Footprint.TILE_SIZE;
                        int otherSize = build.block().size();
                        // the number of points this block has contact with
                        int contactPoints = Math.min((int)(size / 2f + otherSize / 2f - diff), Math.min(otherSize, size));
                        // heat is distributed across the building's size
                        float add = heater.heat() / otherSize * contactPoints;
                        // heat routers split heat across 3 surfaces
                        if(split) add /= 3f;
                        sideHeat[Mathf.mod(relative, 4)] += add;
                        heat += add;
                    }
                    // register traversed cycles
                    if(cameFrom != null){
                        cameFrom.add(build.id());
                        if(build instanceof HeatConductor conductor) cameFrom.addAll(conductor.cameFrom);
                    }
                    // vanilla's "massive hack": the conductor updates after its heat was read
                    if(heater instanceof HeatConductor conductor) conductor.updateHeat();
                }
            }
        }
        return heat;
    }
}
