package com.xpdustry.shard.sim.build;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.environment.*;
import com.xpdustry.shard.sim.tile.*;

import java.util.*;

/**
 * What placement and construction read from a block's definition (vanilla {@code Block}'s fields and the overrides of
 * {@code canPlaceOn}, {@code canReplace}, {@code canBreak}), read once per block and world. A hand-built definition
 * (test content) has no baked fields and gets a fresh {@code Block}'s values: placeable, free, built in 20 ticks.
 */
public final class PlaceInfo{
    /** Which vanilla {@code canPlaceOn} override the block's class has. */
    public enum CanPlaceOn{
        ANY, DRILL, BEAM_DRILL, PUMP, SOLID_PUMP, THERMAL_GENERATOR, ATTRIBUTE_CRAFTER, WALL_CRAFTER, OVERLAY_FLOOR, REMOVE_ORE,
        REMOVE_WALL, CORE, UNIT_CARGO_LOADER,
        /** {@code UnitAssembler}: its area overlaps no other assembler's. */
        UNIT_ASSEMBLER,
        /** {@code UnitAssemblerModule}: it links to an assembler. */
        UNIT_ASSEMBLER_MODULE
    }

    /** Which vanilla {@code canBreak} override the block's class has. */
    public enum CanBreak{
        ANY, CORE, LOGIC
    }

    /** {@code BuildVisibility}: which rule makes the block visible. */
    public enum Visibility{
        HIDDEN, SHOWN, DEBUG_ONLY, EDITOR_ONLY, CORE_ZONE_ONLY, WORLD_PROCESSOR_ONLY, SANDBOX_ONLY, CAMPAIGN_ONLY,
        LEGACY_LAUNCH_PAD_ONLY, NOT_LEGACY_LAUNCH_PAD_ONLY, LIGHTING_ONLY, FOG_ONLY
    }

    /** {@code BlockGroup}s whose members replace each other whatever their class ({@code anyReplace}). */
    private static final Set<String> ANY_REPLACE = new TreeSet<>(List.of("walls", "projectors", "turrets", "transportation",
        "liquids", "logic", "payloads", "heat"));
    private static final String[] PLANETS = {"sun", "erekir", "gier", "notva", "tantros", "serpulo", "verilus"};

    public final BlockDef block;
    public final int id, size;
    /** {@code offset}: the footprint centre's distance from the origin tile's, in world units. */
    public final float offset;
    /** {@code requirements}, in item id order: ids and amounts. */
    public final int[] items, amounts;
    /** {@code buildTime}, the block's own cost multiplier included. */
    public final float buildTime;
    public final boolean rotate, lockRotation, quickRotate, instantBuild, instantDeconstruct, rebuildable, replaceable;
    public final boolean alwaysReplace, privileged, solid, solidifes, floating, placeableLiquid, requiresWater;
    public final boolean ignoreBuildDarkness, allowDerelictRepair, synthetic, breakable, isFloor, isOverlay, core;
    public final float deconstructThreshold, placeOverlapRange, health;
    /** {@code group}, or null for {@code none}. */
    public final String group;
    public final boolean anyReplace;
    public final Visibility visibility;
    /** {@code shownPlanets} as planet ids; empty when shown everywhere. */
    public final int[] shownPlanets;
    public final CanPlaceOn canPlaceOn;
    public final CanBreak canBreak;
    /** Drills: {@code tier}, {@code blockedItems} (item ids, null for none), the beam drill's {@code range}. */
    public final int tier, range;
    public final int[] blockedItems;
    /** Attribute blocks: the attribute id (-1 for none), {@code baseEfficiency} and {@code minEfficiency}. */
    public final int attribute;
    public final float baseEfficiency, minEfficiency;
    /** Floors: {@code supportsOverlay}, {@code allowCorePlacement}; overlays: {@code wallOre}. */
    public final boolean supportsOverlay, allowCorePlacement, wallOre;
    /** Cores: {@code requiresCoreZone}. */
    public final boolean requiresCoreZone;
    /** A cargo loader's {@code unitType}, or -1. */
    public final int unitType;

    private PlaceInfo(BlockDef block, ContentEpoch content){
        this.block = block;
        id = block.id();
        size = block.size();
        offset = Footprint.worldOffset(size);
        boolean baked = block.baked();
        if(baked){
            Map<String, Integer> requirements = block.requirements();
            items = new int[requirements.size()];
            amounts = new int[requirements.size()];
            int i = 0;
            for(Map.Entry<String, Integer> entry : requirements.entrySet()){
                items[i] = content.id(ContentType.ITEM, entry.getKey());
                amounts[i++] = entry.getValue();
            }
            sortById(items, amounts);
        }else{
            items = amounts = new int[0];
        }
        buildTime = baked ? block.buildTime() : 20f;
        rotate = baked && block.rotate();
        lockRotation = b(block, "lockRotation", true);
        quickRotate = b(block, "quickRotate", true);
        instantBuild = b(block, "instantBuild", false);
        instantDeconstruct = b(block, "instantDeconstruct", false);
        rebuildable = b(block, "rebuildable", true);
        replaceable = b(block, "replaceable", true);
        alwaysReplace = baked && block.alwaysReplace();
        privileged = baked && block.privileged();
        solid = baked && block.solid();
        solidifes = baked && block.solidifes();
        floating = baked && block.floating();
        placeableLiquid = baked && block.placeableLiquid();
        requiresWater = b(block, "requiresWater", false);
        ignoreBuildDarkness = b(block, "ignoreBuildDarkness", false);
        allowDerelictRepair = b(block, "allowDerelictRepair", true);
        synthetic = block.update() || (baked ? block.destructible() : id != 0);
        breakable = baked && block.breakable();
        isFloor = block instanceof FloorDef;
        isOverlay = block instanceof FloorDef floor && floor.isOverlay();
        core = baked && block.isA(KindKey.of("core-block"));
        deconstructThreshold = f(block, "deconstructThreshold", 0f);
        placeOverlapRange = f(block, "placeOverlapRange", 50f);
        health = block.health();
        String group = baked ? block.group() : null;
        this.group = group == null || group.equals("none") ? null : group;
        anyReplace = this.group != null && ANY_REPLACE.contains(this.group);
        visibility = baked ? visibility(block.fields().s("buildVisibility")) : Visibility.SHOWN;
        shownPlanets = baked ? planets(block.fields().list("shownPlanets")) : new int[0];
        canPlaceOn = baked ? canPlaceOn(block) : CanPlaceOn.ANY;
        canBreak = !baked ? CanBreak.ANY : core ? CanBreak.CORE : logic(block) ? CanBreak.LOGIC : CanBreak.ANY;
        tier = has(block, "tier") ? block.fields().i("tier") : 0;
        range = has(block, "range") && canPlaceOn == CanPlaceOn.BEAM_DRILL ? block.fields().i("range") : 0;
        blockedItems = blockedItems(block, content);
        attribute = has(block, "attribute") && block.fields().s("attribute") != null ? Attributes.id(block.fields().s("attribute")) : -1;
        baseEfficiency = f(block, "baseEfficiency", 1f);
        minEfficiency = f(block, "minEfficiency", 0f);
        supportsOverlay = b(block, "supportsOverlay", false);
        allowCorePlacement = b(block, "allowCorePlacement", false);
        wallOre = block instanceof FloorDef floor && floor.wallOre();
        requiresCoreZone = b(block, "requiresCoreZone", false);
        unitType = canPlaceOn == CanPlaceOn.UNIT_CARGO_LOADER ? content.id(ContentType.UNIT, block.fields().s("unitType")) : -1;
    }

    /** The placement data of {@code block}. */
    public static PlaceInfo of(BlockDef block, ContentEpoch content){
        return new PlaceInfo(block, content);
    }

    /** {@code planRotation(rotation)}: a block that doesn't rotate keeps rotation 0, unless it unlocks it. */
    public int planRotation(int rotation){
        return !rotate && lockRotation ? 0 : rotation;
    }

    /** {@code isMultiblock()}. */
    public boolean multiblock(){
        return size > 1;
    }

    /** {@code isOnPlanet(planet)}: a planet id, or -1 for none. */
    public boolean onPlanet(int planet){
        if(planet < 0 || planet == 0 || shownPlanets.length == 0) return true;
        for(int shown : shownPlanets) if(shown == planet) return true;
        return false;
    }

    private static Visibility visibility(String name){
        if(name == null) return Visibility.HIDDEN;
        String plain = name.startsWith("visibility:") ? name.substring("visibility:".length()) : name;
        return switch(plain){
            case "hidden" -> Visibility.HIDDEN;
            case "shown" -> Visibility.SHOWN;
            case "debugOnly" -> Visibility.DEBUG_ONLY;
            case "editorOnly" -> Visibility.EDITOR_ONLY;
            case "coreZoneOnly" -> Visibility.CORE_ZONE_ONLY;
            case "worldProcessorOnly" -> Visibility.WORLD_PROCESSOR_ONLY;
            case "sandboxOnly" -> Visibility.SANDBOX_ONLY;
            case "campaignOnly" -> Visibility.CAMPAIGN_ONLY;
            case "legacyLaunchPadOnly" -> Visibility.LEGACY_LAUNCH_PAD_ONLY;
            case "notLegacyLaunchPadOnly" -> Visibility.NOT_LEGACY_LAUNCH_PAD_ONLY;
            case "lightingOnly" -> Visibility.LIGHTING_ONLY;
            case "fogOnly" -> Visibility.FOG_ONLY;
            default -> throw new IllegalStateException("unknown build visibility " + name);
        };
    }

    private static int[] planets(List<Object> names){
        if(names == null) return new int[0];
        int[] out = new int[names.size()];
        for(int i = 0; i < out.length; i++){
            Object name = names.get(i);
            out[i] = name instanceof Number number ? number.intValue() : planet(String.valueOf(name));
        }
        return out;
    }

    private static int planet(String name){
        String plain = name.startsWith("planet:") ? name.substring("planet:".length()) : name;
        for(int i = 0; i < PLANETS.length; i++) if(PLANETS[i].equals(plain)) return i;
        throw new IllegalStateException("unknown planet " + name);
    }

    /** The override the block's class has, most derived first, as Java's dispatch would find it. */
    private static CanPlaceOn canPlaceOn(BlockDef block){
        if(block.isA(KindKey.of("unit-assembler"))) return CanPlaceOn.UNIT_ASSEMBLER;
        if(block.isA(KindKey.of("unit-assembler-module"))) return CanPlaceOn.UNIT_ASSEMBLER_MODULE;
        if(block.isA(KindKey.of("unit-cargo-loader"))) return CanPlaceOn.UNIT_CARGO_LOADER;
        if(block.isA(KindKey.of("core-block"))) return CanPlaceOn.CORE;
        if(block.isA(KindKey.of("remove-ore"))) return CanPlaceOn.REMOVE_ORE;
        if(block.isA(KindKey.of("remove-wall"))) return CanPlaceOn.REMOVE_WALL;
        if(block.isA(KindKey.of("overlay-floor"))) return CanPlaceOn.OVERLAY_FLOOR;
        if(block.isA(KindKey.of("solid-pump"))) return CanPlaceOn.SOLID_PUMP;
        if(block.isA(KindKey.of("pump"))) return CanPlaceOn.PUMP;
        if(block.isA(KindKey.of("drill"))) return CanPlaceOn.DRILL;
        if(block.isA(KindKey.of("beam-drill"))) return CanPlaceOn.BEAM_DRILL;
        if(block.isA(KindKey.of("thermal-generator"))) return CanPlaceOn.THERMAL_GENERATOR;
        if(block.isA(KindKey.of("attribute-crafter"))) return CanPlaceOn.ATTRIBUTE_CRAFTER;
        if(block.isA(KindKey.of("wall-crafter"))) return CanPlaceOn.WALL_CRAFTER;
        return CanPlaceOn.ANY;
    }

    /** {@code LogicBlock}, {@code MemoryBlock}, {@code SwitchBlock} and {@code MessageBlock}: {@code canBreak} is {@code accessible()}. */
    private static boolean logic(BlockDef block){
        return block.isA(KindKey.of("logic-block")) || block.isA(KindKey.of("memory-block")) || block.isA(KindKey.of("switch-block"))
            || block.isA(KindKey.of("message-block"));
    }

    /** {@code blockedItems}, or a list of the one {@code blockedItem} (Drill.java:93), or null. */
    private static int[] blockedItems(BlockDef block, ContentEpoch content){
        if(!has(block, "blockedItems")) return null;
        List<Object> list = block.fields().list("blockedItems");
        if(list == null){
            String one = has(block, "blockedItem") ? block.fields().s("blockedItem") : null;
            return one == null ? null : new int[]{content.id(ContentType.ITEM, one)};
        }
        int[] out = new int[list.size()];
        for(int i = 0; i < out.length; i++) out[i] = content.id(ContentType.ITEM, String.valueOf(list.get(i)));
        return out;
    }

    /** {@code Arrays.sort(requirements, comparingInt(i -> i.item.id))} (Block.java:1240), stable. */
    private static void sortById(int[] ids, int[] amounts){
        for(int i = 1; i < ids.length; i++){
            for(int j = i; j > 0 && ids[j - 1] > ids[j]; j--){
                int id = ids[j];
                ids[j] = ids[j - 1];
                ids[j - 1] = id;
                int amount = amounts[j];
                amounts[j] = amounts[j - 1];
                amounts[j - 1] = amount;
            }
        }
    }

    private static boolean has(BlockDef block, String field){
        return block.baked() && block.fields().has(field);
    }

    private static boolean b(BlockDef block, String field, boolean fallback){
        return has(block, field) ? block.fields().b(field) : fallback;
    }

    private static float f(BlockDef block, String field, float fallback){
        return has(block, field) ? block.fields().f(field) : fallback;
    }
}
