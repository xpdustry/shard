package com.xpdustry.shard.sim.rules;

import com.xpdustry.shard.sim.objectives.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * How a world's game works (vanilla {@code Rules}): all 118 fields, with vanilla's names, types and defaults, in
 * vanilla's declaration order, which is the order the rules JSON writes them in ({@code compat.v160.rules}).
 *
 * <p>Content is held by wire id. Fields vanilla allows to be null are null here too ({@code sector},
 * {@code ambientMusic}, {@code modeName}…); the javadoc says which. {@link #planetBackground} (campaign only) stays raw
 * Arc JSON.
 *
 * <p>A world's rules live in its {@link WorldRules} part: authoritative, forked and hashed with the world.
 */
public final class Rules implements Checkpointable{
    /** Vanilla's {@code Time.toMinutes}. */
    public static final float TICKS_PER_MINUTE = 60f * 60f;

    /** Allows editing the rules in-game. Essentially a cheat mode toggle. */
    public boolean allowEditRules = false;
    /** Sandbox mode: infinite resources, build range and build speed. */
    public boolean infiniteResources;
    /** Allow building and deconstructing cores anywhere, and the team-changing UI. */
    public boolean coreBuildAndConfig = false;
    /** Team-specific rules. */
    public TeamRules teams = new TeamRules();
    /** Waves come on a timer; otherwise when the play button is pressed. */
    public boolean waveTimer = true;
    /** Waves can be summoned with the play button. */
    public boolean waveSending = true;
    /** Waves spawn at all. */
    public boolean waves;
    /** Air units spawn at spawn points instead of the map edge. */
    public boolean airUseSpawns = false;
    /** Units spawn at enemy cores in attack maps with waves. */
    public boolean wavesSpawnAtCores = true;
    public boolean pvp;
    /** Pause PvP while waiting for players. */
    public boolean pvpAutoPause = true;
    /** Pause is disabled in single player. */
    public boolean pauseDisabled = false;
    /** The wave timer waits until all enemies are dead. */
    public boolean waitEnemies = false;
    public boolean attackMode = false;
    public boolean editor = false;
    /** Derelict blocks can be repaired by clicking them. */
    public boolean derelictRepair = true;
    /** Whether a game over can happen at all. */
    public boolean canGameOver = true;
    /** Cores change teams when destroyed. */
    public boolean coreCapture = false;
    public boolean reactorExplosions = true;
    /** Manual unit control. */
    public boolean possessionAllowed = true;
    public boolean schematicsAllowed = true;
    /** Friendly explosions can set fires and damage blocks. */
    public boolean damageExplosions = true;
    /** Fire (and neoplasm spread). */
    public boolean fire = true;
    /** Wave units target random things instead of only the core and generators. */
    public boolean randomWaveAI = false;
    /** Experimental: blocks update inside units and share power. */
    public boolean unitPayloadUpdate = false;
    /** Payloads are destroyed with the unit carrying them. */
    public boolean unitPayloadsExplode = false;
    /** Cores add to the unit limit. */
    public boolean unitCapVariable = true;
    public boolean hideSpawns = true;
    public float solarMultiplier = 1f;
    public float unitBuildSpeedMultiplier = 1f;
    public float unitCostMultiplier = 1f;
    public float unitDamageMultiplier = 1f;
    public float unitHealthMultiplier = 1f;
    /** Compounds with {@link #unitDamageMultiplier}. */
    public float unitCrashDamageMultiplier = 1f;
    public float unitMineSpeedMultiplier = 1f;
    /** Ticks until unit factories activate (all teams). */
    public float unitFactoryActivationDelay = 0f;
    /** Destroyed blocks leave rebuild ghosts. */
    public boolean ghostBlocks = true;
    public boolean showOtherTeamPings = false;
    public boolean logicUnitControl = true;
    public boolean logicUnitBuild = true;
    public boolean logicUnitDeconstruct = false;
    /** World processors may link to player structures. */
    public boolean worldProcessorPlayerLink = true;
    public boolean allowEditWorldProcessors = false;
    public boolean disableWorldProcessors = false;
    public float blockHealthMultiplier = 1f;
    public float blockDamageMultiplier = 1f;
    public float buildCostMultiplier = 1f;
    public float buildSpeedMultiplier = 1f;
    public float deconstructRefundMultiplier = 0.5f;
    public float objectiveTimerMultiplier = 1f;
    /** No-build radius around enemy cores, in world units. */
    public float enemyCoreBuildRadius = 400f;
    /** No-build zones follow the closest core. */
    public boolean polygonCoreProtection = false;
    /** Blocks cannot be placed near blocks that are near the enemy team. */
    public boolean placeRangeCheck = false;
    /** Dead PvP teams become derelict. */
    public boolean cleanupDeadTeams = true;
    public boolean onlyDepositCore = false;
    public boolean allowCoreUnloaders = true;
    /** Seconds between a player's item deposits. Vanilla enforces it only on the client. */
    public float itemDepositCooldown = 0.5f;
    /** Enemy blocks near a destroyed enemy core are destroyed too. */
    public boolean coreDestroyClear = false;
    public boolean hideBannedBlocks = false;
    public boolean allowEnvironmentDeconstruct = false;
    public boolean instantBuild = false;
    /** {@link #bannedBlocks} is a whitelist. */
    public boolean blockWhitelist = false;
    /** {@link #bannedUnits} is a whitelist. */
    public boolean unitWhitelist = false;
    public float dropZoneRadius = 300f;
    /** Ticks between waves. */
    public float waveSpacing = 2 * TICKS_PER_MINUTE;
    /** Ticks before the first wave; at most 0 means twice {@link #waveSpacing}. */
    public float initialWaveSpacing = 0f;
    /** The wave that wins the game; at most 0 disables it. */
    public int winWave = 0;
    public int unitCap = 0;
    public boolean disableUnitCap;
    public float dragMultiplier = 1f;
    /** {@link Env} flags. */
    public int env = Env.DEFAULT;
    public Attributes attributes = new Attributes();
    /** The sector, as {@code <planet>-<id>}, or null outside the campaign. */
    public String sector;
    /** Ambient music names, or null. */
    public List<String> ambientMusic;
    /** Boss and danger music names, or null. */
    public List<String> darkMusic;
    public boolean alwaysPlayMusic = false;
    public boolean disableMusic = false;
    public float musicVolume = 1f;
    public List<SpawnGroup> spawns = new ArrayList<>();
    /** Items put in cores when the game starts. */
    public List<ItemStack> loadout = new ArrayList<>(List.of(new ItemStack(VanillaIds.COPPER, 100)));
    public List<WeatherEntry> weather = new ArrayList<>();
    /** Placement limits, block id to count. */
    public TreeMap<Integer, Integer> blockLimits = new TreeMap<>();
    public TreeSet<Integer> bannedBlocks = new TreeSet<>();
    public TreeSet<Integer> bannedUnits = new TreeSet<>();
    /** Blocks shown although their build visibility hides them. */
    public TreeSet<Integer> revealedBlocks = new TreeSet<>();
    /** Researched content names (campaign multiplayer only). */
    public TreeSet<String> researched = new TreeSet<>();
    /** The map's objective executor, with each objective's progress (WP74). */
    public MapObjectives objectives = new MapObjectives();
    /** Flags set by objectives, read by world processors. */
    public TreeSet<String> objectiveFlags = new TreeSet<>();
    public boolean fog = false;
    /** With fog, undiscovered areas are black. */
    public boolean staticFog = true;
    public Color staticColor = new Color(0f, 0f, 0f, 1f);
    public Color dynamicColor = new Color(0f, 0f, 0f, 0.5f);
    public boolean lighting = false;
    public Color ambientLight = new Color(0.01f, 0.01f, 0.04f, 0.99f);
    public boolean unitLight = true;
    public int defaultTeam = VanillaIds.SHARDED;
    public int waveTeam = VanillaIds.CRUX;
    public Color cloudColor = new Color(0f, 0f, 0f, 0f);
    /** The custom mode's name, or null. */
    public String modeName;
    /** Shown instead of the wave and core counters, or null. */
    public String mission;
    public boolean coreIncinerates = true;
    public boolean borderDarkness = true;
    public boolean limitMapArea = false;
    public int limitX, limitY, limitWidth = 1, limitHeight = 1;
    public boolean disableOutsideArea = true;
    public TreeMap<String, String> tags = new TreeMap<>();
    /** Null unless set. */
    public String customBackgroundCallback;
    /** Null unless set. */
    public String backgroundTexture;
    public float backgroundSpeed = 27000f;
    public float backgroundScl = 1f;
    public float backgroundOffsetX = 0.1f, backgroundOffsetY = 0.1f;
    /** The background planet's parameters as raw Arc JSON (an object), or null. Campaign only. */
    public String planetBackground;
    /** The planet id, or -1 for vanilla's null: its rules apply; {@code sun} mixes tech. */
    public int planet = VanillaIds.SERPULO;
    /** World processors may use the {@code data} instruction. */
    public boolean allowLogicData = false;

    /** The gamemode these rules fit best (vanilla {@code mode()}). */
    public Gamemode mode(){
        if(pvp) return Gamemode.PVP;
        if(editor) return Gamemode.EDITOR;
        if(attackMode) return Gamemode.ATTACK;
        if(infiniteResources) return Gamemode.SANDBOX;
        return Gamemode.SURVIVAL;
    }

    public boolean hasEnv(int env){
        return (this.env & env) != 0;
    }

    public float buildRadius(int team){
        return !teams.get(team).protectCores ? 0f : enemyCoreBuildRadius + teams.get(team).extraCoreBuildRadius;
    }

    public float unitBuildSpeed(int team){
        return unitBuildSpeedMultiplier * teams.get(team).unitBuildSpeedMultiplier;
    }

    public float unitCost(int team){
        return unitCostMultiplier * teams.get(team).unitCostMultiplier;
    }

    public float unitDamage(int team){
        return unitDamageMultiplier * teams.get(team).unitDamageMultiplier;
    }

    public float unitHealth(int team){
        return Math.max(unitHealthMultiplier * teams.get(team).unitHealthMultiplier, 0.000001f);
    }

    public float unitCrashDamage(int team){
        return unitDamage(team) * unitCrashDamageMultiplier * teams.get(team).unitCrashDamageMultiplier;
    }

    public float unitMineSpeed(int team){
        return unitMineSpeedMultiplier * teams.get(team).unitMineSpeedMultiplier;
    }

    public float blockHealth(int team){
        return blockHealthMultiplier * teams.get(team).blockHealthMultiplier;
    }

    public float blockDamage(int team){
        return blockDamageMultiplier * teams.get(team).blockDamageMultiplier;
    }

    public float buildSpeed(int team){
        return buildSpeedMultiplier * teams.get(team).buildSpeedMultiplier;
    }

    public boolean isBlockBanned(int block){
        return blockWhitelist != bannedBlocks.contains(block);
    }

    public boolean isUnitBanned(int unit){
        return unitWhitelist != bannedUnits.contains(unit);
    }

    public float unitActivationDelay(int team){
        return unitFactoryActivationDelay + teams.get(team).unitFactoryActivationDelay;
    }

    public boolean isInfiniteResources(int team){
        return infiniteResources || teams.get(team).infiniteResources;
    }

    /** Every field, in {@link #copy()}'s order; sorted sets and maps as arrays in their order. */
    @Override
    public void write(CheckpointOut out){
        out.bool("allowEditRules", allowEditRules).bool("infiniteResources", infiniteResources)
            .bool("coreBuildAndConfig", coreBuildAndConfig).obj("teams", teams).bool("waveTimer", waveTimer)
            .bool("waveSending", waveSending).bool("waves", waves).bool("airUseSpawns", airUseSpawns)
            .bool("wavesSpawnAtCores", wavesSpawnAtCores).bool("pvp", pvp).bool("pvpAutoPause", pvpAutoPause)
            .bool("pauseDisabled", pauseDisabled).bool("waitEnemies", waitEnemies).bool("attackMode", attackMode)
            .bool("editor", editor).bool("derelictRepair", derelictRepair).bool("canGameOver", canGameOver)
            .bool("coreCapture", coreCapture).bool("reactorExplosions", reactorExplosions)
            .bool("possessionAllowed", possessionAllowed).bool("schematicsAllowed", schematicsAllowed)
            .bool("damageExplosions", damageExplosions).bool("fire", fire).bool("randomWaveAI", randomWaveAI)
            .bool("unitPayloadUpdate", unitPayloadUpdate).bool("unitPayloadsExplode", unitPayloadsExplode)
            .bool("unitCapVariable", unitCapVariable).bool("hideSpawns", hideSpawns)
            .f("solarMultiplier", solarMultiplier).f("unitBuildSpeedMultiplier", unitBuildSpeedMultiplier)
            .f("unitCostMultiplier", unitCostMultiplier).f("unitDamageMultiplier", unitDamageMultiplier)
            .f("unitHealthMultiplier", unitHealthMultiplier).f("unitCrashDamageMultiplier", unitCrashDamageMultiplier)
            .f("unitMineSpeedMultiplier", unitMineSpeedMultiplier)
            .f("unitFactoryActivationDelay", unitFactoryActivationDelay).bool("ghostBlocks", ghostBlocks)
            .bool("showOtherTeamPings", showOtherTeamPings).bool("logicUnitControl", logicUnitControl)
            .bool("logicUnitBuild", logicUnitBuild).bool("logicUnitDeconstruct", logicUnitDeconstruct)
            .bool("worldProcessorPlayerLink", worldProcessorPlayerLink)
            .bool("allowEditWorldProcessors", allowEditWorldProcessors)
            .bool("disableWorldProcessors", disableWorldProcessors).f("blockHealthMultiplier", blockHealthMultiplier)
            .f("blockDamageMultiplier", blockDamageMultiplier).f("buildCostMultiplier", buildCostMultiplier)
            .f("buildSpeedMultiplier", buildSpeedMultiplier)
            .f("deconstructRefundMultiplier", deconstructRefundMultiplier)
            .f("objectiveTimerMultiplier", objectiveTimerMultiplier).f("enemyCoreBuildRadius", enemyCoreBuildRadius)
            .bool("polygonCoreProtection", polygonCoreProtection).bool("placeRangeCheck", placeRangeCheck)
            .bool("cleanupDeadTeams", cleanupDeadTeams).bool("onlyDepositCore", onlyDepositCore)
            .bool("allowCoreUnloaders", allowCoreUnloaders).f("itemDepositCooldown", itemDepositCooldown)
            .bool("coreDestroyClear", coreDestroyClear).bool("hideBannedBlocks", hideBannedBlocks)
            .bool("allowEnvironmentDeconstruct", allowEnvironmentDeconstruct).bool("instantBuild", instantBuild)
            .bool("blockWhitelist", blockWhitelist).bool("unitWhitelist", unitWhitelist)
            .f("dropZoneRadius", dropZoneRadius).f("waveSpacing", waveSpacing)
            .f("initialWaveSpacing", initialWaveSpacing).i("winWave", winWave).i("unitCap", unitCap)
            .bool("disableUnitCap", disableUnitCap).f("dragMultiplier", dragMultiplier).i("env", env)
            .obj("attributes", attributes).str("sector", sector).strs("ambientMusic", ambientMusic)
            .strs("darkMusic", darkMusic).bool("alwaysPlayMusic", alwaysPlayMusic).bool("disableMusic", disableMusic)
            .f("musicVolume", musicVolume).list("spawns", spawns).list("loadout", loadout).list("weather", weather)
            .ints("blockLimits", intMap(blockLimits)).ints("bannedBlocks", intSet(bannedBlocks))
            .ints("bannedUnits", intSet(bannedUnits)).ints("revealedBlocks", intSet(revealedBlocks))
            .strs("researched", researched).obj("objectives", objectives).strs("objectiveFlags", objectiveFlags)
            .bool("fog", fog).bool("staticFog", staticFog).obj("staticColor", staticColor)
            .obj("dynamicColor", dynamicColor).bool("lighting", lighting).obj("ambientLight", ambientLight)
            .bool("unitLight", unitLight).i("defaultTeam", defaultTeam).i("waveTeam", waveTeam)
            .obj("cloudColor", cloudColor).str("modeName", modeName).str("mission", mission)
            .bool("coreIncinerates", coreIncinerates).bool("borderDarkness", borderDarkness)
            .bool("limitMapArea", limitMapArea).i("limitX", limitX).i("limitY", limitY).i("limitWidth", limitWidth)
            .i("limitHeight", limitHeight).bool("disableOutsideArea", disableOutsideArea)
            .strs("tags", stringMap(tags)).str("customBackgroundCallback", customBackgroundCallback)
            .str("backgroundTexture", backgroundTexture).f("backgroundSpeed", backgroundSpeed)
            .f("backgroundScl", backgroundScl).f("backgroundOffsetX", backgroundOffsetX)
            .f("backgroundOffsetY", backgroundOffsetY).str("planetBackground", planetBackground).i("planet", planet)
            .bool("allowLogicData", allowLogicData);
    }

    @Override
    public void read(CheckpointIn in){
        allowEditRules = in.bool("allowEditRules");
        infiniteResources = in.bool("infiniteResources");
        coreBuildAndConfig = in.bool("coreBuildAndConfig");
        teams = in.obj("teams", TeamRules.class);
        waveTimer = in.bool("waveTimer");
        waveSending = in.bool("waveSending");
        waves = in.bool("waves");
        airUseSpawns = in.bool("airUseSpawns");
        wavesSpawnAtCores = in.bool("wavesSpawnAtCores");
        pvp = in.bool("pvp");
        pvpAutoPause = in.bool("pvpAutoPause");
        pauseDisabled = in.bool("pauseDisabled");
        waitEnemies = in.bool("waitEnemies");
        attackMode = in.bool("attackMode");
        editor = in.bool("editor");
        derelictRepair = in.bool("derelictRepair");
        canGameOver = in.bool("canGameOver");
        coreCapture = in.bool("coreCapture");
        reactorExplosions = in.bool("reactorExplosions");
        possessionAllowed = in.bool("possessionAllowed");
        schematicsAllowed = in.bool("schematicsAllowed");
        damageExplosions = in.bool("damageExplosions");
        fire = in.bool("fire");
        randomWaveAI = in.bool("randomWaveAI");
        unitPayloadUpdate = in.bool("unitPayloadUpdate");
        unitPayloadsExplode = in.bool("unitPayloadsExplode");
        unitCapVariable = in.bool("unitCapVariable");
        hideSpawns = in.bool("hideSpawns");
        solarMultiplier = in.f("solarMultiplier");
        unitBuildSpeedMultiplier = in.f("unitBuildSpeedMultiplier");
        unitCostMultiplier = in.f("unitCostMultiplier");
        unitDamageMultiplier = in.f("unitDamageMultiplier");
        unitHealthMultiplier = in.f("unitHealthMultiplier");
        unitCrashDamageMultiplier = in.f("unitCrashDamageMultiplier");
        unitMineSpeedMultiplier = in.f("unitMineSpeedMultiplier");
        unitFactoryActivationDelay = in.f("unitFactoryActivationDelay");
        ghostBlocks = in.bool("ghostBlocks");
        showOtherTeamPings = in.bool("showOtherTeamPings");
        logicUnitControl = in.bool("logicUnitControl");
        logicUnitBuild = in.bool("logicUnitBuild");
        logicUnitDeconstruct = in.bool("logicUnitDeconstruct");
        worldProcessorPlayerLink = in.bool("worldProcessorPlayerLink");
        allowEditWorldProcessors = in.bool("allowEditWorldProcessors");
        disableWorldProcessors = in.bool("disableWorldProcessors");
        blockHealthMultiplier = in.f("blockHealthMultiplier");
        blockDamageMultiplier = in.f("blockDamageMultiplier");
        buildCostMultiplier = in.f("buildCostMultiplier");
        buildSpeedMultiplier = in.f("buildSpeedMultiplier");
        deconstructRefundMultiplier = in.f("deconstructRefundMultiplier");
        objectiveTimerMultiplier = in.f("objectiveTimerMultiplier");
        enemyCoreBuildRadius = in.f("enemyCoreBuildRadius");
        polygonCoreProtection = in.bool("polygonCoreProtection");
        placeRangeCheck = in.bool("placeRangeCheck");
        cleanupDeadTeams = in.bool("cleanupDeadTeams");
        onlyDepositCore = in.bool("onlyDepositCore");
        allowCoreUnloaders = in.bool("allowCoreUnloaders");
        itemDepositCooldown = in.f("itemDepositCooldown");
        coreDestroyClear = in.bool("coreDestroyClear");
        hideBannedBlocks = in.bool("hideBannedBlocks");
        allowEnvironmentDeconstruct = in.bool("allowEnvironmentDeconstruct");
        instantBuild = in.bool("instantBuild");
        blockWhitelist = in.bool("blockWhitelist");
        unitWhitelist = in.bool("unitWhitelist");
        dropZoneRadius = in.f("dropZoneRadius");
        waveSpacing = in.f("waveSpacing");
        initialWaveSpacing = in.f("initialWaveSpacing");
        winWave = in.i("winWave");
        unitCap = in.i("unitCap");
        disableUnitCap = in.bool("disableUnitCap");
        dragMultiplier = in.f("dragMultiplier");
        env = in.i("env");
        attributes = in.obj("attributes", Attributes.class);
        sector = in.str("sector");
        ambientMusic = mutable(in.strs("ambientMusic"));
        darkMusic = mutable(in.strs("darkMusic"));
        alwaysPlayMusic = in.bool("alwaysPlayMusic");
        disableMusic = in.bool("disableMusic");
        musicVolume = in.f("musicVolume");
        spawns = mutable(in.list("spawns", SpawnGroup.class));
        loadout = mutable(in.list("loadout", ItemStack.class));
        weather = mutable(in.list("weather", WeatherEntry.class));
        blockLimits = intMap(in.ints("blockLimits"));
        bannedBlocks = intSet(in.ints("bannedBlocks"));
        bannedUnits = intSet(in.ints("bannedUnits"));
        revealedBlocks = intSet(in.ints("revealedBlocks"));
        researched = stringSet(in.strs("researched"));
        objectives = in.obj("objectives", MapObjectives.class);
        objectiveFlags = stringSet(in.strs("objectiveFlags"));
        fog = in.bool("fog");
        staticFog = in.bool("staticFog");
        staticColor = in.obj("staticColor", Color.class);
        dynamicColor = in.obj("dynamicColor", Color.class);
        lighting = in.bool("lighting");
        ambientLight = in.obj("ambientLight", Color.class);
        unitLight = in.bool("unitLight");
        defaultTeam = in.i("defaultTeam");
        waveTeam = in.i("waveTeam");
        cloudColor = in.obj("cloudColor", Color.class);
        modeName = in.str("modeName");
        mission = in.str("mission");
        coreIncinerates = in.bool("coreIncinerates");
        borderDarkness = in.bool("borderDarkness");
        limitMapArea = in.bool("limitMapArea");
        limitX = in.i("limitX");
        limitY = in.i("limitY");
        limitWidth = in.i("limitWidth");
        limitHeight = in.i("limitHeight");
        disableOutsideArea = in.bool("disableOutsideArea");
        tags = stringMap(in.strs("tags"));
        customBackgroundCallback = in.str("customBackgroundCallback");
        backgroundTexture = in.str("backgroundTexture");
        backgroundSpeed = in.f("backgroundSpeed");
        backgroundScl = in.f("backgroundScl");
        backgroundOffsetX = in.f("backgroundOffsetX");
        backgroundOffsetY = in.f("backgroundOffsetY");
        planetBackground = in.str("planetBackground");
        planet = in.i("planet");
        allowLogicData = in.bool("allowLogicData");
    }


    /** A stored list as a mutable one, null kept. */
    private static <T> List<T> mutable(List<T> stored){
        return stored == null ? null : new ArrayList<>(stored);
    }

    /** A sorted map of ints as its keys and values, interleaved. */
    private static int[] intMap(TreeMap<Integer, Integer> map){
        if(map == null) return null;
        var out = new int[map.size() * 2];
        int at = 0;
        for(Map.Entry<Integer, Integer> entry : map.entrySet()){
            out[at++] = entry.getKey();
            out[at++] = entry.getValue();
        }
        return out;
    }

    private static TreeMap<Integer, Integer> intMap(int[] stored){
        if(stored == null) return null;
        if(stored.length % 2 != 0) throw new CheckpointException("a rules map has a key without a value");
        var out = new TreeMap<Integer, Integer>();
        for(int i = 0; i < stored.length; i += 2) out.put(stored[i], stored[i + 1]);
        return out;
    }

    private static int[] intSet(TreeSet<Integer> set){
        return set == null ? null : set.stream().mapToInt(Integer::intValue).toArray();
    }

    private static TreeSet<Integer> intSet(int[] stored){
        if(stored == null) return null;
        var out = new TreeSet<Integer>();
        for(int value : stored) out.add(value);
        return out;
    }

    private static TreeSet<String> stringSet(List<String> stored){
        return stored == null ? null : new TreeSet<>(stored);
    }

    /** A sorted map of strings as its keys and values, interleaved. */
    private static List<String> stringMap(TreeMap<String, String> map){
        if(map == null) return null;
        var out = new ArrayList<String>(map.size() * 2);
        map.forEach((key, value) -> {
            out.add(key);
            out.add(value);
        });
        return out;
    }

    private static TreeMap<String, String> stringMap(List<String> stored){
        if(stored == null) return null;
        if(stored.size() % 2 != 0) throw new CheckpointException("a rules map has a key without a value");
        var out = new TreeMap<String, String>();
        for(int i = 0; i < stored.size(); i += 2) out.put(stored.get(i), stored.get(i + 1));
        return out;
    }

    /** A deep copy. */
    public Rules copy(){
        var out = new Rules();
        out.allowEditRules = allowEditRules;
        out.infiniteResources = infiniteResources;
        out.coreBuildAndConfig = coreBuildAndConfig;
        out.teams = teams.copy();
        out.waveTimer = waveTimer;
        out.waveSending = waveSending;
        out.waves = waves;
        out.airUseSpawns = airUseSpawns;
        out.wavesSpawnAtCores = wavesSpawnAtCores;
        out.pvp = pvp;
        out.pvpAutoPause = pvpAutoPause;
        out.pauseDisabled = pauseDisabled;
        out.waitEnemies = waitEnemies;
        out.attackMode = attackMode;
        out.editor = editor;
        out.derelictRepair = derelictRepair;
        out.canGameOver = canGameOver;
        out.coreCapture = coreCapture;
        out.reactorExplosions = reactorExplosions;
        out.possessionAllowed = possessionAllowed;
        out.schematicsAllowed = schematicsAllowed;
        out.damageExplosions = damageExplosions;
        out.fire = fire;
        out.randomWaveAI = randomWaveAI;
        out.unitPayloadUpdate = unitPayloadUpdate;
        out.unitPayloadsExplode = unitPayloadsExplode;
        out.unitCapVariable = unitCapVariable;
        out.hideSpawns = hideSpawns;
        out.solarMultiplier = solarMultiplier;
        out.unitBuildSpeedMultiplier = unitBuildSpeedMultiplier;
        out.unitCostMultiplier = unitCostMultiplier;
        out.unitDamageMultiplier = unitDamageMultiplier;
        out.unitHealthMultiplier = unitHealthMultiplier;
        out.unitCrashDamageMultiplier = unitCrashDamageMultiplier;
        out.unitMineSpeedMultiplier = unitMineSpeedMultiplier;
        out.unitFactoryActivationDelay = unitFactoryActivationDelay;
        out.ghostBlocks = ghostBlocks;
        out.showOtherTeamPings = showOtherTeamPings;
        out.logicUnitControl = logicUnitControl;
        out.logicUnitBuild = logicUnitBuild;
        out.logicUnitDeconstruct = logicUnitDeconstruct;
        out.worldProcessorPlayerLink = worldProcessorPlayerLink;
        out.allowEditWorldProcessors = allowEditWorldProcessors;
        out.disableWorldProcessors = disableWorldProcessors;
        out.blockHealthMultiplier = blockHealthMultiplier;
        out.blockDamageMultiplier = blockDamageMultiplier;
        out.buildCostMultiplier = buildCostMultiplier;
        out.buildSpeedMultiplier = buildSpeedMultiplier;
        out.deconstructRefundMultiplier = deconstructRefundMultiplier;
        out.objectiveTimerMultiplier = objectiveTimerMultiplier;
        out.enemyCoreBuildRadius = enemyCoreBuildRadius;
        out.polygonCoreProtection = polygonCoreProtection;
        out.placeRangeCheck = placeRangeCheck;
        out.cleanupDeadTeams = cleanupDeadTeams;
        out.onlyDepositCore = onlyDepositCore;
        out.allowCoreUnloaders = allowCoreUnloaders;
        out.itemDepositCooldown = itemDepositCooldown;
        out.coreDestroyClear = coreDestroyClear;
        out.hideBannedBlocks = hideBannedBlocks;
        out.allowEnvironmentDeconstruct = allowEnvironmentDeconstruct;
        out.instantBuild = instantBuild;
        out.blockWhitelist = blockWhitelist;
        out.unitWhitelist = unitWhitelist;
        out.dropZoneRadius = dropZoneRadius;
        out.waveSpacing = waveSpacing;
        out.initialWaveSpacing = initialWaveSpacing;
        out.winWave = winWave;
        out.unitCap = unitCap;
        out.disableUnitCap = disableUnitCap;
        out.dragMultiplier = dragMultiplier;
        out.env = env;
        out.attributes = attributes.copy();
        out.sector = sector;
        out.ambientMusic = ambientMusic == null ? null : new ArrayList<>(ambientMusic);
        out.darkMusic = darkMusic == null ? null : new ArrayList<>(darkMusic);
        out.alwaysPlayMusic = alwaysPlayMusic;
        out.disableMusic = disableMusic;
        out.musicVolume = musicVolume;
        out.spawns = new ArrayList<>(spawns.size());
        for(SpawnGroup group : spawns) out.spawns.add(group.copy());
        out.loadout = new ArrayList<>(loadout);
        out.weather = new ArrayList<>(weather.size());
        for(WeatherEntry entry : weather) out.weather.add(entry.copy());
        out.blockLimits = new TreeMap<>(blockLimits);
        out.bannedBlocks = new TreeSet<>(bannedBlocks);
        out.bannedUnits = new TreeSet<>(bannedUnits);
        out.revealedBlocks = new TreeSet<>(revealedBlocks);
        out.researched = new TreeSet<>(researched);
        out.objectives = objectives.copy();
        out.objectiveFlags = new TreeSet<>(objectiveFlags);
        out.fog = fog;
        out.staticFog = staticFog;
        out.staticColor = staticColor;
        out.dynamicColor = dynamicColor;
        out.lighting = lighting;
        out.ambientLight = ambientLight;
        out.unitLight = unitLight;
        out.defaultTeam = defaultTeam;
        out.waveTeam = waveTeam;
        out.cloudColor = cloudColor;
        out.modeName = modeName;
        out.mission = mission;
        out.coreIncinerates = coreIncinerates;
        out.borderDarkness = borderDarkness;
        out.limitMapArea = limitMapArea;
        out.limitX = limitX;
        out.limitY = limitY;
        out.limitWidth = limitWidth;
        out.limitHeight = limitHeight;
        out.disableOutsideArea = disableOutsideArea;
        out.tags = new TreeMap<>(tags);
        out.customBackgroundCallback = customBackgroundCallback;
        out.backgroundTexture = backgroundTexture;
        out.backgroundSpeed = backgroundSpeed;
        out.backgroundScl = backgroundScl;
        out.backgroundOffsetX = backgroundOffsetX;
        out.backgroundOffsetY = backgroundOffsetY;
        out.planetBackground = planetBackground;
        out.planet = planet;
        out.allowLogicData = allowLogicData;
        return out;
    }

    /** Feeds every field, in declaration order. */
    public void hash(Hasher h){
        h.bool(allowEditRules).bool(infiniteResources).bool(coreBuildAndConfig);
        teams.hash(h);
        h.bool(waveTimer).bool(waveSending).bool(waves).bool(airUseSpawns).bool(wavesSpawnAtCores).bool(pvp)
            .bool(pvpAutoPause).bool(pauseDisabled).bool(waitEnemies).bool(attackMode).bool(editor).bool(derelictRepair)
            .bool(canGameOver).bool(coreCapture).bool(reactorExplosions).bool(possessionAllowed).bool(schematicsAllowed)
            .bool(damageExplosions).bool(fire).bool(randomWaveAI).bool(unitPayloadUpdate).bool(unitPayloadsExplode)
            .bool(unitCapVariable).bool(hideSpawns);
        h.f(solarMultiplier).f(unitBuildSpeedMultiplier).f(unitCostMultiplier).f(unitDamageMultiplier)
            .f(unitHealthMultiplier).f(unitCrashDamageMultiplier).f(unitMineSpeedMultiplier).f(unitFactoryActivationDelay);
        h.bool(ghostBlocks).bool(showOtherTeamPings).bool(logicUnitControl).bool(logicUnitBuild)
            .bool(logicUnitDeconstruct).bool(worldProcessorPlayerLink).bool(allowEditWorldProcessors)
            .bool(disableWorldProcessors);
        h.f(blockHealthMultiplier).f(blockDamageMultiplier).f(buildCostMultiplier).f(buildSpeedMultiplier)
            .f(deconstructRefundMultiplier).f(objectiveTimerMultiplier).f(enemyCoreBuildRadius);
        h.bool(polygonCoreProtection).bool(placeRangeCheck).bool(cleanupDeadTeams).bool(onlyDepositCore)
            .bool(allowCoreUnloaders).f(itemDepositCooldown).bool(coreDestroyClear).bool(hideBannedBlocks)
            .bool(allowEnvironmentDeconstruct).bool(instantBuild).bool(blockWhitelist).bool(unitWhitelist);
        h.f(dropZoneRadius).f(waveSpacing).f(initialWaveSpacing).i(winWave).i(unitCap).bool(disableUnitCap)
            .f(dragMultiplier).i(env);
        attributes.hash(h);
        str(h, sector);
        strings(h, ambientMusic);
        strings(h, darkMusic);
        h.bool(alwaysPlayMusic).bool(disableMusic).f(musicVolume);
        h.i(spawns.size());
        for(SpawnGroup group : spawns) group.hash(h);
        h.i(loadout.size());
        for(ItemStack stack : loadout) h.i(stack.item()).i(stack.amount());
        h.i(weather.size());
        for(WeatherEntry entry : weather) entry.hash(h);
        h.i(blockLimits.size());
        blockLimits.forEach((block, limit) -> h.i(block).i(limit));
        ids(h, bannedBlocks);
        ids(h, bannedUnits);
        ids(h, revealedBlocks);
        strings(h, researched);
        objectives.hash(h);
        strings(h, objectiveFlags);
        h.bool(fog).bool(staticFog).i(staticColor.rgba()).i(dynamicColor.rgba()).bool(lighting).i(ambientLight.rgba())
            .bool(unitLight).i(defaultTeam).i(waveTeam).i(cloudColor.rgba());
        str(h, modeName);
        str(h, mission);
        h.bool(coreIncinerates).bool(borderDarkness).bool(limitMapArea).i(limitX).i(limitY).i(limitWidth)
            .i(limitHeight).bool(disableOutsideArea);
        h.i(tags.size());
        tags.forEach((key, value) -> {
            h.str(key);
            str(h, value); //vanilla's StringMap allows null values
        });
        str(h, customBackgroundCallback);
        str(h, backgroundTexture);
        h.f(backgroundSpeed).f(backgroundScl).f(backgroundOffsetX).f(backgroundOffsetY);
        str(h, planetBackground);
        h.i(planet).bool(allowLogicData);
    }

    private static void str(Hasher h, String value){
        h.bool(value != null);
        if(value != null) h.str(value);
    }

    private static void strings(Hasher h, Collection<String> values){
        h.i(values == null ? -1 : values.size());
        if(values != null) for(String value : values) h.str(value);
    }

    private static void ids(Hasher h, Collection<Integer> values){
        h.i(values.size());
        for(int value : values) h.i(value);
    }
}
