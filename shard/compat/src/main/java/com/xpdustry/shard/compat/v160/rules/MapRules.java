package com.xpdustry.shard.compat.v160.rules;

import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.waves.*;

import java.util.*;

/** How vanilla turns a map's {@code rules} tag into the rules a game is played with ({@code Map.applyRules}). */
public final class MapRules{
    private final RulesJson json;
    private final List<SpawnGroup> defaultWaves;

    public MapRules(RulesJson json){
        this.json = json;
        this.defaultWaves = List.copyOf(Waves.defaults(json.content()));
    }

    /** {@code Map.applyRules(mode)}: the mode's preset, the map's rules over it, then {@link #rules(Rules, String)}'s fixups. */
    public Rules apply(String mapRules, Gamemode mode){
        return rules(mode.apply(new Rules()), mapRules);
    }

    /**
     * {@code Map.rules(base)}: {@code mapRules} read over {@code base}; a serpulo map with the scorching env plays on
     * erekir, a null planet becomes serpulo, and a map without spawn groups gets the default waves. Vanilla quirk: if
     * the JSON cannot be read, the result is plain {@code new Rules()}, without the mode.
     */
    public Rules rules(Rules base, String mapRules){
        try{
            Rules result = json.read(base, mapRules == null ? "{}" : mapRules);
            if(result.planet == VanillaIds.SERPULO && result.hasEnv(Env.SCORCHING)) result.planet = VanillaIds.EREKIR;
            if(result.planet < 0) result.planet = VanillaIds.SERPULO;
            if(result.spawns.isEmpty()) result.spawns = defaultWaves();
            return result;
        }catch(RuntimeException e){
            return new Rules();
        }
    }

    /** A copy of vanilla's default waves ({@code Waves.get()}). */
    public List<SpawnGroup> defaultWaves(){
        var out = new ArrayList<SpawnGroup>(defaultWaves.size());
        for(SpawnGroup group : defaultWaves) out.add(group.copy());
        return out;
    }
}
