package com.xpdustry.shard.server.intent.action;

import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * The deny rules admins and plugins set on a world: what vanilla's server owners write as action filters, as data. A
 * rule denies the action types in its mask, for one team or every team and one session or every session, and tells
 * the player its message. {@link RuleFilter} enforces them after vanilla's rate limit.
 *
 * <p>Rules change only through {@link Put} and {@link Remove}, admin or plugin commands that go through the world's
 * inbox and log like everything else (ARCHITECTURE.md §4), so a checkpoint keeps them and a replay sees them change
 * at the same tick. {@link Persistence#AUTHORITATIVE}; not registered: hosted worlds run it.
 */
public final class ActionRules implements WorldPart, Checkpointable{
    /** Rules by name, so a put replaces and a listing is ordered. */
    private final TreeMap<String, Rule> rules = new TreeMap<>();

    public ActionRules(){
    }

    /** The world's rules, or null if it doesn't run them. */
    public static ActionRules of(World world){
        return world.find(ActionRules.class);
    }

    /** Every rule, by name. */
    public SortedMap<String, Rule> rules(){
        return Collections.unmodifiableSortedMap(rules);
    }

    /** The first rule, by name, that denies {@code type} to a player of {@code team} on {@code session}, or null. */
    public Rule denying(ActionType type, int team, long session){
        for(Rule rule : rules.values()){
            if(rule.denies(type, team, session)) return rule;
        }
        return null;
    }

    /**
     * One deny rule.
     *
     * @param mask the {@link ActionType#bit()}s it denies
     * @param team the team it applies to, or -1 for every team
     * @param session the session it applies to, or -1 for every session
     * @param message what the player is told, or empty for nothing
     */
    public record Rule(String name, int mask, int team, long session, String message){
        public Rule{
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(message, "message");
            if(name.isEmpty()) throw new IllegalArgumentException("a rule needs a name");
        }

        /** A rule denying {@code types} to everyone. */
        public static Rule deny(String name, String message, ActionType... types){
            int mask = 0;
            for(ActionType type : types) mask |= type.bit();
            return new Rule(name, mask, -1, -1, message);
        }

        public boolean denies(ActionType type, int team, long session){
            return (mask & type.bit()) != 0 && (this.team == -1 || this.team == team) && (this.session == -1 || this.session == session);
        }

        /** The types it denies, in vanilla's order. */
        public List<ActionType> types(){
            var out = new ArrayList<ActionType>();
            for(ActionType type : ActionType.values()) if((mask & type.bit()) != 0) out.add(type);
            return out;
        }
    }

    /** Adds {@code rule}, replacing the rule of the same name. */
    public record Put(Rule rule) implements Command.Admin, Command.Script{
        @Override
        public Verdict apply(World world, Cause cause){
            ActionRules rules = of(world);
            if(rules == null) return Verdict.rejected("this world has no action rules");
            rules.rules.put(rule.name(), rule);
            return Verdict.ADMITTED;
        }
    }

    /** Removes the rule named {@code name}. */
    public record Remove(String name) implements Command.Admin, Command.Script{
        @Override
        public Verdict apply(World world, Cause cause){
            ActionRules rules = of(world);
            if(rules == null) return Verdict.rejected("this world has no action rules");
            return rules.rules.remove(name) != null ? Verdict.ADMITTED : Verdict.rejected("no rule " + name);
        }
    }

    @Override
    public WorldPart copy(CopyContext context){
        var out = new ActionRules();
        out.rules.putAll(rules);
        return out;
    }

    @Override
    public void hash(Hasher hasher){
        hasher.i(rules.size());
        for(Rule rule : rules.values()) hasher.str(rule.name()).i(rule.mask()).i(rule.team()).l(rule.session()).str(rule.message());
    }

    @Override
    public void write(CheckpointOut out){
        out.list("rules", List.copyOf(rules.values()));
    }

    @Override
    public void read(CheckpointIn in){
        rules.clear();
        for(Rule rule : in.list("rules", Rule.class)) rules.put(rule.name(), rule);
    }
}
