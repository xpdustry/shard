package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code Units} remote calls and the rule lookups the unit core makes ({@code Team.isAI}); the unit cap is
 * {@link Units}'.
 *
 * <p>Each call runs its local handler, then publishes its {@link UnitEvent}: the generated {@code Call.x} runs the
 * handler first on a server and sends the packet after it, so calls made inside a handler are published first.
 */
public final class UnitCalls{
    private static final Rules DEFAULT_RULES = new Rules();

    private UnitCalls(){
    }

    /** The world's rules, or vanilla's defaults in a world built without them. Don't modify the result. */
    public static Rules rules(World world){
        WorldRules rules = world.find(WorldRules.class);
        return rules == null ? DEFAULT_RULES : rules.rules;
    }

    /** Vanilla's {@code state.isCampaign()}. */
    public static boolean isCampaign(Rules rules){
        return rules.sector != null;
    }

    /** Vanilla's {@code Team.isAI()}: waves, attack or the campaign, not the default team, not PvP. */
    public static boolean isAI(World world, int team){
        Rules rules = rules(world);
        return (rules.waves || rules.attackMode || isCampaign(rules)) && team != rules.defaultTeam && !rules.pvp;
    }

    /** {@code Call.unitDeath(unit.id)}: an added unit runs {@code killed()}. */
    static void death(Unit unit){
        if(unit.isAdded()) unit.killed();
        unit.world().publish(new UnitEvent.Death(unit.id()));
    }

    /** {@code Call.unitDestroy(unit.id)}: an added unit is destroyed; the packet goes out either way. */
    static void destroy(Unit unit){
        if(unit.isAdded()) unit.destroy();
        unit.world().publish(new UnitEvent.Destroy(unit.id()));
    }

    /** {@code Call.unitDespawn(unit)}: removed, no death. */
    static void despawn(Unit unit){
        unit.remove();
        unit.world().publish(new UnitEvent.Despawn(unit.id()));
    }

    /** {@code Call.unitSafeDeath(unit)}: removed with death effects only. */
    static void safeDeath(Unit unit){
        unit.remove();
        unit.world().publish(new UnitEvent.SafeDeath(unit.id()));
    }

    /** {@code Call.unitEnvDeath(unit)}: dead now, destroyed by a task posted to the next tick. */
    static void envDeath(Unit unit){
        unit.dead = true;
        post(unit);
        unit.world().publish(new UnitEvent.EnvDeath(unit.id()));
    }

    /** {@code Call.unitCapDeath(unit)}: dead now, destroyed by a task posted to the next tick. */
    static void capDeath(Unit unit){
        unit.dead = true;
        post(unit);
        unit.world().publish(new UnitEvent.CapDeath(unit.id()));
    }

    /** {@code Core.app.post(() -> Call.unitDestroy(unit.id))}, run by {@link PostedDestroys}. */
    private static void post(Unit unit){
        TeamUnits counts = TeamUnits.of(unit.world());
        if(counts != null) counts.post(unit);
    }
}
