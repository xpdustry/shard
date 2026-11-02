package com.xpdustry.shard.tools.extract;

import mindustry.entities.part.*;
import mindustry.world.draw.*;

import java.util.*;

/**
 * Who reads a field (ARCHITECTURE.md §5, note 20 §1.4). Vanilla clients simulate buildings, bullets and units
 * themselves, so most data is {@code shared}: the client must have the same value or its prediction drifts.
 * {@code client} fields only change how things look or read in the UI; {@code server} fields are read only by code
 * that runs on the server (unit AI).
 *
 * <p>The rule is a reviewed heuristic: by type (effects, sounds, colors, draw parts), then by name, then the explicit
 * lists below, which win.
 */
final class Tags{
    enum Tag{
        server, shared, client
    }

    /** {@code Class.field} → tag, overriding the heuristic. */
    static final Map<String, Tag> overrides = new HashMap<>();

    static{
        for(String f : List.of("UnitType.aiController", "UnitType.controller", "UnitType.targetFlags", "UnitType.playerTargetFlags",
            "UnitType.circleTarget", "UnitType.defaultCommand", "UnitType.aiControllable")){
            overrides.put(f, Tag.server);
        }
        for(String f : List.of(
            "UnlockableContent.credit", "UnlockableContent.alwaysUnlocked", "UnlockableContent.inlineDescription",
            "UnlockableContent.hideDetails", "UnlockableContent.hideDatabase", "UnlockableContent.generateIcons",
            "UnlockableContent.selectionSize", "UnlockableContent.fullOverride", "UnlockableContent.allDatabaseTabs",
            "UnlockableContent.shownPlanets", "UnlockableContent.databaseTabs", "UnlockableContent.databaseCategory",
            "UnlockableContent.databaseTag",
            "Block.researchCost", "Block.researchCostMultiplier", "Block.researchCostMultipliers", "Block.inEditor",
            "Block.mapColor", "Block.hasColor", "Block.squareSprite", "Block.variants", "Block.selectionRows",
            "Block.selectionColumns", "Block.schematicPriority", "Block.albedo", "Block.placePitchChange",
            "Block.breakPitchChange", "Block.useColor", "Block.drawCracks", "Block.createRubble", "Block.generateIcons",
            "Block.clipSize", "Block.hideDetails",
            "UnitType.researchCostMultiplier", "UnitType.database", "UnitType.mapColor", "UnitType.clipSize",
            "Item.color", "Liquid.color", "Liquid.gasColor", "Liquid.barColor", "Liquid.lightColor", "StatusEffect.color")){
            overrides.put(f, Tag.client);
        }
    }

    private static final String[] visualWords = {
        "color", "sound", "effect", "shake", "light", "draw", "region", "sprite", "icon", "outline", "shadow", "trail",
        "recoil", "engine", "glow", "parts", "layer", "visual", "fade", "smoke", "heat", "ambient", "decal", "wreck",
        "blend", "pitch", "volume", "particle", "flare", "glare", "cell", "clip", "emit", "flash", "sway", "squish",
        "bob", "wave", "ripple", "footstep", "legsplash", "trns"
    };

    static Tag of(Kinds.Prop prop){
        String id = prop.field.getDeclaringClass().getSimpleName() + "." + prop.field.getName();
        Tag override = overrides.get(id);
        if(override != null) return override;
        if(visualType(prop.type)) return Tag.client;
        Class<?> owner = prop.owner.type;
        if(DrawBlock.class.isAssignableFrom(owner) || DrawPart.class.isAssignableFrom(owner)) return Tag.client;
        if(mindustry.entities.Effect.class.isAssignableFrom(owner)) return Tag.client;
        String name = prop.name.toLowerCase(Locale.ROOT);
        for(String word : visualWords){
            if(name.contains(word)) return Tag.client;
        }
        return Tag.shared;
    }

    private static boolean visualType(FieldType type){
        return switch(type){
            case FieldType.Named n -> !n.prefix().equals("cost") && !n.prefix().equals("sort") && !n.prefix().equals("visibility");
            case FieldType.Scalar s -> s.name().equals("color");
            case FieldType.Obj o -> DrawBlock.class.isAssignableFrom(o.base()) || DrawPart.class.isAssignableFrom(o.base());
            case FieldType.ListType l -> visualType(l.element());
            default -> false;
        };
    }
}
