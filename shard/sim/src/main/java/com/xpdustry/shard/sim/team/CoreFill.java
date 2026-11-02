package com.xpdustry.shard.sim.team;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.storage.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * {@code Logic.update}'s team loop after the weather (Logic.java:552): every active team with the {@code fillItems}
 * rule has its first core filled to what it accepts of each item shown on the rules' planet. The loop reads
 * {@link Teams#getActive}'s live list ({@link Teams#activeView}), so it also drops teams that stopped being active,
 * every tick, as vanilla's does. The base builder, RTS and prebuild AIs of the same loop come with the AI (WP54).
 */
@Register
public final class CoreFill implements PhaseHandler{
    @Override
    public Phase phase(){
        return Phase.WEATHER;
    }

    @Override
    public int order(){
        return 100;
    }

    @Override
    public void run(World world){
        Teams teams = Teams.of(world);
        if(teams == null) return;
        Rules rules = UnitCalls.rules(world);
        IntSeq active = teams.activeView(world);
        for(int i = 0; i < active.size; i++){
            int team = active.get(i);
            Core core = teams.core(team);
            if(!rules.teams.get(team).fillItems || core == null) continue;
            ContentEpoch content = world.content();
            String planet = planet(world, rules);
            for(int item = 0; item < content.items(); item++){
                if(shown(content.item(item), planet)) core.items.set(item, core.getMaximumAccepted(item));
            }
        }
    }

    /** {@code state.getPlanet()}'s name, or null for none: the sector's planet in the campaign, else the rules'. */
    private static String planet(World world, Rules rules){
        String name = rules.sector != null ? rules.sector.substring(0, Math.max(0, rules.sector.lastIndexOf('-'))) : null;
        if(name != null) return name;
        List<String> planets = world.content().planets();
        return rules.planet < 0 || rules.planet >= planets.size() ? null : planets.get(rules.planet);
    }

    /** {@code item.isOnPlanet(planet) && !item.isHidden()}: not hidden, and shown on the planet (any, for none or the sun). */
    static boolean shown(ItemDef item, String planet){
        if(!item.baked()) return true;
        if(item.fields().b("hidden")) return false;
        List<Object> shown = item.fields().has("shownPlanets") ? item.fields().list("shownPlanets") : List.of();
        return planet == null || planet.equals("sun") || shown.isEmpty() || shown.contains(planet);
    }
}
