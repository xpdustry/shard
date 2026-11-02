package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.sim.ai.CommandAi;

/**
 * Vanilla's {@code UnitType.createController(unit)}: the controller a unit gets from its type, made exactly when
 * vanilla makes one so the AI timers draw from the random in vanilla's order. The type's baked {@code controller} is:
 * <ul>
 *   <li>{@code hook:default-controller}: an AI from {@code aiController} when the type isn't player-controllable or
 *   the team is AI without RTS AI, else a {@link CommandAi};</li>
 *   <li>the core units' lambda ({@code alpha}, {@code beta}, {@code gamma}): a {@code BuilderAI} on AI teams, else a
 *   {@link CommandAi};</li>
 *   <li>{@code builder-ai}, the Erekir core units' lambda ({@code evoke}, {@code incite}, {@code emanate}):
 *   {@code new BuilderAI(true, coreFleeRange)} on every team, {@code coreFleeRange} being 500;</li>
 *   <li>{@code no-ai}: {@link NoAi}, which draws nothing;</li>
 *   <li>any other AI kind: that AI.</li>
 * </ul>
 * {@code hook:default-ai} is {@code GroundAI} for ground types and {@code FlyingAI} for flying ones. Every AI is made
 * through {@link AiKinds}, so a kind's own class runs once its package registers it.
 */
public final class Controllers{
    public static final String NO_AI = "no-ai", BUILDER_AI = "builder-ai";
    /** The {@code assist} command's AI: a {@code BuilderAI} with {@code onlyAssist}, which no content names. */
    public static final String ASSIST_AI = "assist-ai";
    /** Serpulo's core units flee from 400 world units ({@code new BuilderAI(true, 400f)}, UnitTypes.java:2495). */
    static final float CORE_FLEE_RANGE = 400f;
    /** {@code coreFleeRange} (UnitTypes.java:4352), Erekir's core units'. */
    static final float EREKIR_CORE_FLEE_RANGE = 500f;

    private Controllers(){
    }

    /** {@code type.createController(unit)}, for the unit's current type and team. */
    public static UnitController create(Unit unit){
        UnitParams params = unit.params();
        String controller = params.controller();
        if(UnitParams.DEFAULT_CONTROLLER.equals(controller)){
            boolean ai = !params.playerControllable() || (UnitCalls.isAI(unit.world(), unit.team)
                && !UnitCalls.rules(unit.world()).teams.get(unit.team).rtsAi);
            return ai ? ai(unit) : new CommandAi(unit.world());
        }
        if(UnitParams.CORE_UNIT_CONTROLLER.equals(controller)){
            if(!UnitCalls.isAI(unit.world(), unit.team)) return new CommandAi(unit.world());
            // u -> u.team.isAI() ? new BuilderAI(true, 400f) : new CommandAI() (UnitTypes.java:2495)
            return fleeingBuilder(unit, CORE_FLEE_RANGE);
        }
        // u -> new BuilderAI(true, coreFleeRange), on every team (UnitTypes.java:4352-4501): the extractor names this
        // lambda by the AI it makes, and only these three types have it
        if(BUILDER_AI.equals(controller)) return fleeingBuilder(unit, EREKIR_CORE_FLEE_RANGE);
        if(NO_AI.equals(controller)) return new NoAi();
        return AiKinds.make(unit.world(), controller);
    }

    /** {@code new BuilderAI(true, fleeRange)}. */
    private static UnitController fleeingBuilder(Unit unit, float fleeRange){
        UnitController ai = AiKinds.make(unit.world(), BUILDER_AI);
        if(ai instanceof com.xpdustry.shard.sim.ai.BuilderAi builder){
            builder.alwaysFlee = true;
            builder.fleeRange = fleeRange;
        }
        return ai;
    }

    /** {@code aiController.get()}. */
    static UnitController ai(Unit unit){
        UnitParams params = unit.params();
        String kind = params.aiController();
        if(UnitParams.DEFAULT_AI.equals(kind)) kind = params.flying() ? AiController.FLYING : AiController.GROUND;
        return AiKinds.make(unit.world(), kind);
    }
}
