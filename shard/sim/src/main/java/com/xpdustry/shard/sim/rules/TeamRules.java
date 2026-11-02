package com.xpdustry.shard.sim.rules;

import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * The per-team rules (vanilla {@code Rules.TeamRules}): one optional {@link TeamRule} per team id.
 *
 * <p>Vanilla quirk kept: {@link #get(int)} creates the entry it was asked for, so reading a team's rules adds it to
 * the rules JSON. {@link #peek(int)} reads without creating.
 */
public final class TeamRules implements Checkpointable{
    private final TeamRule[] values = new TeamRule[VanillaIds.TEAMS];

    /** The team's rules, created with {@link TeamRule#of(int)} on first use, as vanilla does. */
    public TeamRule get(int team){
        TeamRule rule = values[team];
        return rule == null ? (values[team] = TeamRule.of(team)) : rule;
    }

    /** The team's entry, or null when it has none. */
    public TeamRule peek(int team){
        return values[team];
    }

    /** What {@link #get} would return, without creating an entry. */
    public TeamRule effective(int team){
        TeamRule rule = values[team];
        return rule == null ? TeamRule.of(team) : rule;
    }

    /** Sets or (with null) clears the team's entry. */
    public void set(int team, TeamRule rule){
        values[team] = rule;
    }

    public TeamRules copy(){
        var out = new TeamRules();
        for(int i = 0; i < values.length; i++){
            if(values[i] != null) out.values[i] = values[i].copy();
        }
        return out;
    }

    public void hash(Hasher hasher){
        for(int i = 0; i < values.length; i++){
            hasher.bool(values[i] != null);
            if(values[i] != null) values[i].hash(hasher);
        }
    }

    /** One team's rule, for the teams that have one: the rest stay null, as they were. */
    private record Row(int team, TeamRule rule){
    }

    @Override
    public void write(CheckpointOut out){
        var rows = new ArrayList<Row>();
        for(int team = 0; team < values.length; team++){
            if(values[team] != null) rows.add(new Row(team, values[team]));
        }
        out.list("values", rows);
    }

    @Override
    public void read(CheckpointIn in){
        for(Row row : in.list("values", Row.class)){
            if(row.team() < 0 || row.team() >= values.length || row.rule() == null) throw new CheckpointException("a team rule is corrupt");
            values[row.team()] = row.rule();
        }
    }
}
