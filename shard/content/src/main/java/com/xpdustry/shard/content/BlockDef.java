package com.xpdustry.shard.content;

import com.xpdustry.shard.content.schema.*;

import java.util.*;

/**
 * The typed, immutable definition of a block, as pinned by a {@link ContentEpoch}. The abstract getters are what the
 * building base reads; the defaults read {@link #fields()}, where kinds find their own parameters (capacities,
 * consumers, kind-local fields).
 */
public interface BlockDef extends ContentDef{
    /** The wire id, which is the client's content id. */
    @Override
    int id();

    /** The content name ({@code conveyor}, {@code titanium-conveyor}…). */
    @Override
    String name();

    @Override
    default ContentType type(){
        return ContentType.BLOCK;
    }

    /** The behaviour kind that simulates this block: its vanilla block class ({@code item-turret}). */
    @Override
    KindKey kind();

    /**
     * The kind whose vanilla class declares this block's building class: {@code item-turret} for
     * {@code ItemTurret$ItemTurretBuild}, {@code block} for plain {@code Building}s (and for the environment blocks,
     * which have none). Blocks with the same build kind save and sync the same building state. A hand-built definition
     * has one kind for both.
     */
    default KindKey buildKind(){
        return baked() ? fields().kind().buildKind().kindKey() : kind();
    }

    /** The footprint edge, in tiles. */
    int size();

    /** Vanilla's baked {@code health}. */
    float health();

    /** Vanilla's {@code update}: whether buildings of this block join the update list. */
    boolean update();

    /** Vanilla's {@code noUpdateDisabled}: a disabled building skips {@code updateTile()}. */
    boolean noUpdateDisabled();

    boolean hasItems();

    boolean hasLiquids();

    boolean hasPower();

    default boolean solid(){
        return fields().b("solid");
    }

    /** Vanilla's {@code hasBuilding()}: {@code update || destructible}. */
    default boolean hasBuilding(){
        return update() || destructible();
    }

    /**
     * Whether vanilla's block is a {@code LegacyBlock}: the end of every map load removes or replaces it
     * (World.java:222), and mlog has no constant for it. False for hand-built definitions.
     */
    default boolean legacy(){
        return baked() && isA(KindKey.of("legacy-block"));
    }

    /**
     * Whether creating this block's building also creates a block unit, as every vanilla {@code TurretBuild} does
     * (Turret.java:297): that draws an entity id. False for hand-built definitions.
     */
    default boolean ownsBlockUnit(){
        return baked() && isA(KindKey.of("turret"));
    }

    default boolean rotate(){
        return fields().b("rotate");
    }

    default int itemCapacity(){
        return fields().i("itemCapacity");
    }

    default float liquidCapacity(){
        return fields().f("liquidCapacity");
    }

    /** Ticks to build at speed 1, from the requirements ({@code Block.init}). */
    default float buildTime(){
        return fields().f("buildTime");
    }

    default float armor(){
        return fields().f("armor");
    }

    /** The build cost: item name to amount, in item id order. */
    default Map<String, Integer> requirements(){
        Map<String, Integer> out = new LinkedHashMap<>();
        fields().map("requirements").forEach((item, amount) -> out.put(item, (Integer)amount));
        return out;
    }

    // what the tile layer, placement and pathing read (vanilla Block)

    /** {@code solidifes}: solid to units once built (doors, constructs). */
    default boolean solidifes(){
        return fields().b("solidifes");
    }

    /** {@code destructible}; with {@link #update()} it decides vanilla's {@code hasBuilding()} and {@code synthetic()}. */
    default boolean destructible(){
        return fields().b("destructible");
    }

    default boolean breakable(){
        return fields().b("breakable");
    }

    /** {@code alwaysReplace}: props and boulders that placement clears. */
    default boolean alwaysReplace(){
        return fields().b("alwaysReplace");
    }

    /** {@code teamPassable}: units of the building's team walk through it. */
    default boolean teamPassable(){
        return fields().b("teamPassable");
    }

    /** {@code fillsTile}: false for cliffs and the like, which map-edge darkness skips. */
    default boolean fillsTile(){
        return fields().b("fillsTile");
    }

    default boolean forceDark(){
        return fields().b("forceDark");
    }

    /** {@code privileged}: world processors and their kin. */
    default boolean privileged(){
        return fields().b("privileged");
    }

    /** Vanilla's {@code supportsEnv(env)}: whether the block works in a world with the {@code Env} flags {@code env}. */
    default boolean supportsEnv(int env){
        int enabled = fields().i("envEnabled"), disabled = fields().i("envDisabled"), required = fields().i("envRequired");
        return (enabled & env) != 0 && (disabled & env) == 0 && (required == 0 || (required & env) == required);
    }

    /** {@code isStatic()}: drawn in the static wall layer ({@code cacheLayer == CacheLayer.walls}). */
    default boolean isStatic(){
        return "cache:walls".equals(fields().s("cacheLayer"));
    }

    /** {@code saveData}: the tile's data bytes go into saves. */
    default boolean saveData(){
        return fields().b("saveData");
    }

    /** {@code placeableOn}: false for floors nothing can be built on (space, empty). */
    default boolean placeableOn(){
        return fields().b("placeableOn");
    }

    /** {@code floating}: placeable on deep liquid. */
    default boolean floating(){
        return fields().b("floating");
    }

    default boolean placeableLiquid(){
        return fields().b("placeableLiquid");
    }

    /** {@code playerUnmineable}: its ore drops only to drills and units, not to players. */
    default boolean playerUnmineable(){
        return fields().b("playerUnmineable");
    }

    /** {@code forceTeam}'s team id, or -1: buildings of this block always belong to that team. */
    default int forceTeam(){
        Object team = fields().get("forceTeam");
        return team == null ? -1 : (Integer)team;
    }

    /** {@code itemDrop}: the item mined from this block, or null. */
    default ItemDef itemDrop(){
        throw new UnsupportedOperationException("block " + name() + " has no baked fields");
    }

    /** {@code attributes}: attribute name ({@code heat}, {@code oil}…) to value, only the ones set. */
    default Map<String, Float> attributes(){
        Map<String, Float> out = new LinkedHashMap<>();
        fields().map("attributes").forEach((attribute, value) -> out.put(attribute, (Float)value));
        return out;
    }

    /** {@code fogRadius}: the tiles around its building that its team sees in fog, or -1 for none. */
    default int fogRadius(){
        return fields().i("fogRadius");
    }

    /** {@code flags}: the {@code BlockFlag}s that index this block ({@code core}, {@code turret}…), in enum order. */
    default List<String> flags(){
        List<String> out = new ArrayList<>();
        for(Object flag : fields().list("flags")) out.add((String)flag);
        return out;
    }

    /** {@code targetable}: units and turrets may target its buildings. */
    default boolean targetable(){
        return fields().b("targetable");
    }

    /** {@code priority}: the {@code TargetPriority} targeting prefers, whatever the distance. */
    default float priority(){
        return fields().f("priority");
    }

    // what buildings read: consumers, configuration, power and caps (vanilla Block)

    /**
     * {@code consumeBuilder}: what the block consumes, one {@code consume} struct per consumer in vanilla's order
     * ({@code consume-items}, {@code consume-power}…). {@code Block.init} turns them into {@code consumers}.
     */
    default List<Struct> consumers(){
        return fields().structs("consumes");
    }

    /** {@code hasConsumers}: whether {@link #consumers()} is non-empty once vanilla inits the block. */
    default boolean hasConsumers(){
        return fields().b("hasConsumers");
    }

    /** {@code configurable}: players may configure its buildings. */
    default boolean configurable(){
        return fields().b("configurable");
    }

    /** {@code saveConfig}: a new building of this block takes the last config players gave one ({@code lastConfig}). */
    default boolean saveConfig(){
        return fields().b("saveConfig");
    }

    /** {@code copyConfig}: copying a building into a schematic or plan keeps its config. */
    default boolean copyConfig(){
        return fields().b("copyConfig");
    }

    /** {@code logicConfigurable}: processors may configure its buildings ({@code control config}). */
    default boolean logicConfigurable(){
        return fields().b("logicConfigurable");
    }

    /** {@code consumesPower}: draws from a power graph. */
    default boolean consumesPower(){
        return fields().b("consumesPower");
    }

    /** {@code outputsPower}: feeds a power graph. */
    default boolean outputsPower(){
        return fields().b("outputsPower");
    }

    /** {@code connectedPower}: joins the graph of the power buildings next to it. */
    default boolean connectedPower(){
        return fields().b("connectedPower");
    }

    /** {@code conductivePower}: passes power on to the buildings next to it (battery diodes, plastanium walls…). */
    default boolean conductivePower(){
        return fields().b("conductivePower");
    }

    /** {@code acceptsItems}: other buildings may offer it items. */
    default boolean acceptsItems(){
        return fields().b("acceptsItems");
    }

    /** {@code separateItemCapacity}: {@link #itemCapacity()} holds for each item, not for the total. */
    default boolean separateItemCapacity(){
        return fields().b("separateItemCapacity");
    }

    /** {@code instantTransfer}: items pass through without a transfer delay (sorters, overflow gates…). */
    default boolean instantTransfer(){
        return fields().b("instantTransfer");
    }

    default boolean outputsLiquid(){
        return fields().b("outputsLiquid");
    }

    default boolean acceptsPayload(){
        return fields().b("acceptsPayload");
    }

    default boolean outputsPayload(){
        return fields().b("outputsPayload");
    }

    /** {@code acceptsUnitPayloads}: units may enter it as payloads (the payload blocks, unit factories). */
    default boolean acceptsUnitPayloads(){
        return fields().b("acceptsUnitPayloads");
    }

    /** {@code allowedInPayloads}: payload units and blocks may carry its buildings ({@code canPickup()}'s default). */
    default boolean allowedInPayloads(){
        return fields().b("allowedInPayloads");
    }

    /** {@code updateInUnits}: its buildings update while a unit carries them, if the rules allow it. */
    default boolean updateInUnits(){
        return fields().b("updateInUnits");
    }

    /** {@code alwaysUpdateInUnits}: its buildings update in units whatever {@code rules.unitPayloadUpdate} says. */
    default boolean alwaysUpdateInUnits(){
        return fields().b("alwaysUpdateInUnits");
    }

    /** {@code outputFacing}: a rotating block outputs payloads only through its front ({@code PayloadBlock.blends}). */
    default boolean outputFacing(){
        return fields().b("outputFacing");
    }

    /** {@code buildVisibility}, as the pack names it ({@code "visibility:shown"}, {@code "visibility:hidden"}...). */
    default String buildVisibility(){
        return fields().s("buildVisibility");
    }

    /** {@code crushFragile}: tanks that crush fragile blocks destroy enemy buildings of this block next to them. */
    default boolean crushFragile(){
        return fields().b("crushFragile");
    }

    /** {@code crushDamageMultiplier}: scales the crush damage tanks deal to buildings of this block. */
    default float crushDamageMultiplier(){
        return fields().f("crushDamageMultiplier");
    }

    /** {@code unitMoveBreakable}: a tank or a splashing leg breaks it by moving over it (boulders, bushes). */
    default boolean unitMoveBreakable(){
        return fields().b("unitMoveBreakable");
    }

    /** {@code unitCapModifier}: what each building of this block adds to its team's unit cap (cores). */
    default int unitCapModifier(){
        return fields().i("unitCapModifier");
    }

    /** {@code group}: the {@code BlockGroup} ({@code transportation}, {@code power}…), which decides what it connects to. */
    default String group(){
        return fields().s("group");
    }

    /** {@code sync}: its buildings are sent in state snapshots. */
    default boolean sync(){
        return fields().b("sync");
    }

    /** {@code underBullets}: bullets pass over it unless aimed at it ({@code BulletComp.checkUnderBuild}). */
    default boolean underBullets(){
        return fields().b("underBullets");
    }

    /** {@code absorbLasers}: lasers stop at it. */
    default boolean absorbLasers(){
        return fields().b("absorbLasers");
    }

    /** {@code insulated}: lightning stops at it. */
    default boolean insulated(){
        return fields().b("insulated");
    }

    /** {@code destroyBullet}: the name of the bullet it fires when destroyed ({@link ContentEpoch#bullet(String)}), or null. */
    default String destroyBullet(){
        return fields().s("destroyBullet");
    }

    /** {@code destroyBulletSameTeam}: its destroy bullet keeps its team instead of turning derelict. */
    default boolean destroyBulletSameTeam(){
        return fields().b("destroyBulletSameTeam");
    }
}
