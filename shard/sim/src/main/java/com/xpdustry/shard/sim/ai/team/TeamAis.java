package com.xpdustry.shard.sim.ai.team;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.common.struct.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * Vanilla's {@code TeamData.buildAi} and {@code TeamData.rtsAi}: each team's {@link BaseBuilderAi} and {@link RtsAi},
 * made on the first frame their rule is on and kept after (vanilla never drops them, so a rule turned off and on
 * again resumes the same AI). {@link Loop} runs them in {@code Logic.update}'s team loop. A new world, and so a loaded
 * save, starts with none, as vanilla's {@code logic.reset()} gives a fresh {@code Teams}.
 *
 * <p>The base registry ({@link BaseRegistry}) is derived from the content, made when a base builder first needs it.
 *
 * <p>Owner: WP82.
 */
@Register
public final class TeamAis implements WorldPart, Checkpointable{
    private final BaseBuilderAi[] builders = new BaseBuilderAi[TeamUnits.TEAMS];
    private final RtsAi[] rts = new RtsAi[TeamUnits.TEAMS];
    @Derived("the content's base parts, read on first use")
    private BaseRegistry bases;

    /** The world's team AIs, or null in a world built without them. */
    public static TeamAis of(World world){
        return world.find(TeamAis.class);
    }

    /** {@code team.data().buildAi}, or null. */
    public BaseBuilderAi builder(int team){
        return builders[team];
    }

    /** {@code team.data().rtsAi}, or null. */
    public RtsAi rts(int team){
        return rts[team];
    }

    /** {@code Vars.bases}, for this world's content. */
    public BaseRegistry bases(World world){
        if(bases == null) bases = BaseRegistry.of(world.content());
        return bases;
    }

    /**
     * {@code RtsAI}'s {@code BuildDamageEvent} listener (RtsAI.java:47): a bullet damaged {@code build} (a hit, or
     * splash from a bullet), so its team's RTS AI, if the team has the rule and the AI, will consider defending it.
     */
    public static void buildDamaged(Building build){
        World world = build.world();
        TeamAis ais = of(world);
        if(ais == null || !UnitCalls.rules(world).teams.get(build.team).rtsAi) return;
        RtsAi ai = ais.rts[build.team];
        if(ai != null) ai.buildDamaged(build);
    }

    /**
     * {@code Logic.update}'s team loop, its AI part (Logic.java:562): for each active team, the base builder AI with
     * {@code buildAi} (not in PvP), then the RTS AI with {@code rtsAi}, each made on first use. Not in the editor.
     *
     * <p>Vanilla runs the core fill ({@link CoreFill}), these and the prebuild spawns team by team; Shard runs each
     * part for every team in turn. Only the prebuild spawns could tell, on a map where one team prebuilds and a later
     * one runs these AIs.
     */
    @Register
    public static final class Loop implements PhaseHandler{
        @Override
        public Phase phase(){
            return Phase.WEATHER;
        }

        @Override
        public int order(){
            return 150;
        }

        @Override
        public void run(World world){
            TeamAis ais = of(world);
            Teams teams = Teams.of(world);
            if(ais == null || teams == null) return;
            Rules rules = UnitCalls.rules(world);
            if(rules.editor) return;
            IntSeq active = teams.activeView(world);
            for(int i = 0; i < active.size; i++){
                int team = active.get(i);
                TeamRule rule = rules.teams.get(team);
                // does not work on PvP so built-in attack maps can have it on by default without issues
                if(rule.buildAi && !rules.pvp){
                    if(ais.builders[team] == null) ais.builders[team] = new BaseBuilderAi(team);
                    ais.builders[team].update(world, ais.bases(world));
                }
                if(rule.rtsAi){
                    if(ais.rts[team] == null) ais.rts[team] = new RtsAi(world, team);
                    ais.rts[team].update(world);
                }
            }
        }
    }

    // ---------------------------------------------------------------- state

    @Override
    public WorldPart copy(CopyContext context){
        var out = new TeamAis();
        for(int t = 0; t < TeamUnits.TEAMS; t++){
            if(builders[t] != null) out.builders[t] = builders[t].copy();
            if(rts[t] != null) out.rts[t] = rts[t].copy(context);
        }
        out.bases = bases;
        return out;
    }

    @Override
    public void hash(Hasher hasher){
        for(int t = 0; t < TeamUnits.TEAMS; t++){
            if(builders[t] != null) builders[t].hash(hasher.i(1));
            if(rts[t] != null) rts[t].hash(hasher.i(2));
        }
    }

    @Override
    public void write(CheckpointOut out){
        var builders = new ArrayList<BaseBuilderAi>();
        var rts = new ArrayList<RtsAi>();
        for(int t = 0; t < TeamUnits.TEAMS; t++){
            if(this.builders[t] != null) builders.add(this.builders[t]);
            if(this.rts[t] != null) rts.add(this.rts[t]);
        }
        out.list("builders", builders).list("rts", rts);
    }

    @Override
    public void read(CheckpointIn in){
        List<BaseBuilderAi> builders = in.list("builders", BaseBuilderAi.class);
        List<RtsAi> rts = in.list("rts", RtsAi.class);
        if(builders == null || rts == null || builders.contains(null) || rts.contains(null)){
            throw new CheckpointException("the team AIs are missing");
        }
        Arrays.fill(this.builders, null);
        Arrays.fill(this.rts, null);
        for(BaseBuilderAi ai : builders){
            if(ai.team < 0 || ai.team >= TeamUnits.TEAMS || this.builders[ai.team] != null) throw new CheckpointException("a base builder AI for team " + ai.team);
            this.builders[ai.team] = ai;
        }
        for(RtsAi ai : rts){
            if(ai.team < 0 || ai.team >= TeamUnits.TEAMS || this.rts[ai.team] != null) throw new CheckpointException("an RTS AI for team " + ai.team);
            this.rts[ai.team] = ai;
        }
    }
}
