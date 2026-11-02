package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla-epoch worlds for unit tests. Vanilla's default rules cap units at 0 without cores, so a unit spawned on an
 * empty map dies at once ({@code Units.getCap}); tests about anything else turn the cap off, as the oracle tests set
 * {@code state.rules.disableUnitCap}.
 */
public final class UnitWorlds{
    private UnitWorlds(){
    }

    /** {@link VanillaWorlds#world(int, int, long)} with no unit cap. */
    public static World world(int width, int height, long seed){
        return uncapped(VanillaWorlds.world(width, height, seed));
    }

    /** {@link VanillaWorlds#world(int, int, long, Systems)} with no unit cap, if it has rules. */
    public static World world(int width, int height, long seed, Systems systems){
        return uncapped(VanillaWorlds.world(width, height, seed, systems));
    }

    /** Turns off {@code world}'s unit cap, if it has rules. */
    public static World uncapped(World world){
        for(WorldPart part : world.parts()){
            if(part instanceof WorldRules rules) rules.rules.disableUnitCap = true;
        }
        return world;
    }

    /** Caps every team but the wave team at {@code cap} units per type, whatever its buildings. */
    public static void cap(World world, int cap){
        Rules rules = world.part(WorldRules.class).rules;
        rules.disableUnitCap = false;
        rules.unitCapVariable = false;
        rules.unitCap = cap;
    }
}
