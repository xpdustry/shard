package com.xpdustry.shard.sim.ai;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.storage.*;
import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * {@code Logic.update}'s prebuild part of the team loop (Logic.java:573): every active team with the {@code prebuildAi}
 * rule keeps one unit of each core's {@code unitType} flagged with that core's position. A core with none, among the
 * team's units of that type at the last recount ({@link TeamUnits#units(int, UnitDef)}), spawns one over itself,
 * facing 0, and tells clients ({@code Units.notifyUnitSpawn}). Those units are core units, so on an AI team they run
 * a {@code BuilderAI} that hands over to {@link PrebuildAi}. Not in the editor.
 *
 * <p>Vanilla runs it in the same per-team loop as {@code fillItems} ({@link CoreFill}), after it; filling a core and
 * spawning a unit don't read each other, so running all fills first is the same.
 */
@Register
public final class PrebuildSpawns implements PhaseHandler{
    @Override
    public Phase phase(){
        return Phase.WEATHER;
    }

    @Override
    public int order(){
        return 200;
    }

    @Override
    public void run(World world){
        Teams teams = Teams.of(world);
        TeamUnits teamUnits = world.find(TeamUnits.class);
        if(teams == null || teamUnits == null) return;
        Rules rules = UnitCalls.rules(world);
        if(rules.editor) return;
        IntSeq active = teams.activeView(world);
        for(int i = 0; i < active.size; i++){
            int team = active.get(i);
            if(!rules.teams.get(team).prebuildAi) continue;
            List<Core> cores = teams.cores(team);
            for(int c = 0; c < cores.size(); c++){
                Core core = cores.get(c);
                BlockDef block = core.block();
                if(!block.baked()) continue;
                UnitDef type = world.content().unit(block.fields().s("unitType"));
                int pos = Point2.pack(core.tileX(), core.tileY());
                if(!flagged(teamUnits.units(team, type), pos)){
                    Unit unit = Unit.spawn(world, type, team, core.x(), core.y(), 0f);
                    unit.flag = pos;
                    CombatEvent.unitSpawned(unit);
                }
            }
        }
    }

    /** {@code units.contains(u -> u.flag == core.pos())}. */
    private static boolean flagged(UnitSeq units, int pos){
        if(units == null) return false;
        for(int i = 0; i < units.size(); i++) if(units.get(i).flag == pos) return true;
        return false;
    }
}
