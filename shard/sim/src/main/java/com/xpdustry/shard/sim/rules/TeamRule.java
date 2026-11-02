package com.xpdustry.shard.sim.rules;

import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * One team's rules (vanilla {@code Rules.TeamRule}): its 24 fields, with vanilla's names and defaults, in vanilla's
 * declaration order (the order the rules JSON writes them in).
 */
public final class TeamRule implements Checkpointable{
    /** Whether, when AI is enabled, ships should be spawned from the core. */
    public boolean aiCoreSpawn = true;
    /** Whether the core no-build radius applies to this team; unprotected teams are ignored by the team assigner. */
    public boolean protectCores = true;
    /** Whether {@code placeRangeCheck} applies to this team. */
    public boolean checkPlacement = true;
    /** Blocks need no power or resources. */
    public boolean cheat;
    /** The core is always filled to capacity with all items. */
    public boolean fillItems;
    /** Resources are not consumed when building. */
    public boolean infiniteResources;
    /** Experimental pre-built base AI. */
    public boolean prebuildAi;
    /** AI that builds random schematics. */
    public boolean buildAi;
    /** Tier of the builder AI, in [0, 1]. */
    public float buildAiTier = 1f;
    /** "RTS" unit AI. */
    public boolean rtsAi;
    /** Minimum size of attack squads. */
    public int rtsMinSquad = 4;
    /** Maximum size of attack squads. */
    public int rtsMaxSquad = 50;
    /** Minimum advantage a squad needs to attack. */
    public float rtsMinWeight = 1.2f;
    /** Ticks until unit factories activate. */
    public float unitFactoryActivationDelay = 0f;
    public float unitBuildSpeedMultiplier = 1f;
    public float unitDamageMultiplier = 1f;
    public float unitCrashDamageMultiplier = 1f;
    public float unitMineSpeedMultiplier = 1f;
    public float unitCostMultiplier = 1f;
    public float unitHealthMultiplier = 1f;
    public float blockHealthMultiplier = 1f;
    public float blockDamageMultiplier = 1f;
    public float buildSpeedMultiplier = 1f;
    /** Extra spacing added to the no-build zone around the core. */
    public float extraCoreBuildRadius = 0f;

    /** The no-argument defaults, which is also what vanilla's JSON reader starts each entry from. */
    public TeamRule(){
    }

    /** What {@link TeamRules#get} creates for a team without an entry: derelict is unprotected. */
    public static TeamRule of(int team){
        var rule = new TeamRule();
        if(team == VanillaIds.DERELICT){
            rule.protectCores = false;
            rule.checkPlacement = false;
        }
        return rule;
    }

    public TeamRule copy(){
        var out = new TeamRule();
        out.aiCoreSpawn = aiCoreSpawn;
        out.protectCores = protectCores;
        out.checkPlacement = checkPlacement;
        out.cheat = cheat;
        out.fillItems = fillItems;
        out.infiniteResources = infiniteResources;
        out.prebuildAi = prebuildAi;
        out.buildAi = buildAi;
        out.buildAiTier = buildAiTier;
        out.rtsAi = rtsAi;
        out.rtsMinSquad = rtsMinSquad;
        out.rtsMaxSquad = rtsMaxSquad;
        out.rtsMinWeight = rtsMinWeight;
        out.unitFactoryActivationDelay = unitFactoryActivationDelay;
        out.unitBuildSpeedMultiplier = unitBuildSpeedMultiplier;
        out.unitDamageMultiplier = unitDamageMultiplier;
        out.unitCrashDamageMultiplier = unitCrashDamageMultiplier;
        out.unitMineSpeedMultiplier = unitMineSpeedMultiplier;
        out.unitCostMultiplier = unitCostMultiplier;
        out.unitHealthMultiplier = unitHealthMultiplier;
        out.blockHealthMultiplier = blockHealthMultiplier;
        out.blockDamageMultiplier = blockDamageMultiplier;
        out.buildSpeedMultiplier = buildSpeedMultiplier;
        out.extraCoreBuildRadius = extraCoreBuildRadius;
        return out;
    }

    public void hash(Hasher hasher){
        hasher.bool(aiCoreSpawn).bool(protectCores).bool(checkPlacement).bool(cheat).bool(fillItems)
            .bool(infiniteResources).bool(prebuildAi).bool(buildAi).f(buildAiTier).bool(rtsAi).i(rtsMinSquad)
            .i(rtsMaxSquad).f(rtsMinWeight).f(unitFactoryActivationDelay).f(unitBuildSpeedMultiplier)
            .f(unitDamageMultiplier).f(unitCrashDamageMultiplier).f(unitMineSpeedMultiplier).f(unitCostMultiplier)
            .f(unitHealthMultiplier).f(blockHealthMultiplier).f(blockDamageMultiplier).f(buildSpeedMultiplier)
            .f(extraCoreBuildRadius);
    }

    @Override
    public void write(CheckpointOut out){
        out.bool("aiCoreSpawn", aiCoreSpawn).bool("protectCores", protectCores).bool("checkPlacement", checkPlacement)
            .bool("cheat", cheat).bool("fillItems", fillItems).bool("infiniteResources", infiniteResources)
            .bool("prebuildAi", prebuildAi).bool("buildAi", buildAi).f("buildAiTier", buildAiTier).bool("rtsAi", rtsAi)
            .i("rtsMinSquad", rtsMinSquad).i("rtsMaxSquad", rtsMaxSquad).f("rtsMinWeight", rtsMinWeight)
            .f("unitFactoryActivationDelay", unitFactoryActivationDelay).f("unitBuildSpeedMultiplier", unitBuildSpeedMultiplier)
            .f("unitDamageMultiplier", unitDamageMultiplier).f("unitCrashDamageMultiplier", unitCrashDamageMultiplier)
            .f("unitMineSpeedMultiplier", unitMineSpeedMultiplier).f("unitCostMultiplier", unitCostMultiplier)
            .f("unitHealthMultiplier", unitHealthMultiplier).f("blockHealthMultiplier", blockHealthMultiplier)
            .f("blockDamageMultiplier", blockDamageMultiplier).f("buildSpeedMultiplier", buildSpeedMultiplier)
            .f("extraCoreBuildRadius", extraCoreBuildRadius);
    }

    @Override
    public void read(CheckpointIn in){
        aiCoreSpawn = in.bool("aiCoreSpawn");
        protectCores = in.bool("protectCores");
        checkPlacement = in.bool("checkPlacement");
        cheat = in.bool("cheat");
        fillItems = in.bool("fillItems");
        infiniteResources = in.bool("infiniteResources");
        prebuildAi = in.bool("prebuildAi");
        buildAi = in.bool("buildAi");
        buildAiTier = in.f("buildAiTier");
        rtsAi = in.bool("rtsAi");
        rtsMinSquad = in.i("rtsMinSquad");
        rtsMaxSquad = in.i("rtsMaxSquad");
        rtsMinWeight = in.f("rtsMinWeight");
        unitFactoryActivationDelay = in.f("unitFactoryActivationDelay");
        unitBuildSpeedMultiplier = in.f("unitBuildSpeedMultiplier");
        unitDamageMultiplier = in.f("unitDamageMultiplier");
        unitCrashDamageMultiplier = in.f("unitCrashDamageMultiplier");
        unitMineSpeedMultiplier = in.f("unitMineSpeedMultiplier");
        unitCostMultiplier = in.f("unitCostMultiplier");
        unitHealthMultiplier = in.f("unitHealthMultiplier");
        blockHealthMultiplier = in.f("blockHealthMultiplier");
        blockDamageMultiplier = in.f("blockDamageMultiplier");
        buildSpeedMultiplier = in.f("buildSpeedMultiplier");
        extraCoreBuildRadius = in.f("extraCoreBuildRadius");
    }
}
