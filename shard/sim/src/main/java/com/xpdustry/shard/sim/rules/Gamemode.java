package com.xpdustry.shard.sim.rules;

import java.util.function.*;

/**
 * Rule presets (vanilla {@code Gamemode}). A map is played in a mode by applying the preset to fresh rules and the
 * map's own rules over it, as vanilla's {@code Map.applyRules} does.
 */
public enum Gamemode{
    SURVIVAL(rules -> {
        rules.waveTimer = true;
        rules.waves = true;
    }),
    SANDBOX(rules -> {
        rules.infiniteResources = true;
        rules.allowEditRules = true;
        rules.waves = true;
        rules.waveTimer = false;
    }),
    ATTACK(rules -> {
        rules.attackMode = true;
        rules.waveTimer = true;
        rules.waveSpacing = 2f * Rules.TICKS_PER_MINUTE;
        // Vanilla quirk: it also sets waveTeam.rules().infiniteResources, but Team.rules() reads the live state's
        // rules, not the preset, and hosting replaces those right after. The preset never carries it.
    }),
    PVP(rules -> {
        rules.pvp = true;
        rules.enemyCoreBuildRadius = 600f;
        rules.buildCostMultiplier = 1f;
        rules.buildSpeedMultiplier = 1f;
        rules.unitBuildSpeedMultiplier = 2f;
        rules.attackMode = true;
    }),
    EDITOR(rules -> {
        rules.infiniteResources = true;
        rules.instantBuild = true;
        rules.editor = true;
        rules.waves = false;
        rules.waveTimer = false;
    });

    private final Consumer<Rules> preset;

    Gamemode(Consumer<Rules> preset){
        this.preset = preset;
    }

    /** Applies this preset to {@code rules} and returns them. */
    public Rules apply(Rules rules){
        preset.accept(rules);
        return rules;
    }

    /** Hidden from mode lists, as vanilla's editor mode. */
    public boolean hidden(){
        return this == EDITOR;
    }

    /**
     * Whether a map with {@code spawns} enemy spawn points and {@code teams} teams that have cores can be played in
     * this mode (vanilla's validators).
     */
    public boolean valid(int spawns, int teams){
        return switch(this){
            case SURVIVAL -> spawns > 0;
            case ATTACK, PVP -> teams > 1;
            case SANDBOX, EDITOR -> true;
        };
    }

    /** The vanilla name: {@code survival}, {@code sandbox}… */
    public String id(){
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
