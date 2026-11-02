package com.xpdustry.shard.content.bake;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.yaml.*;

import java.util.*;

import static com.xpdustry.shard.content.bake.Bake.*;
import static com.xpdustry.shard.content.bake.Values.*;

/** {@code Block.init()} and the block kinds' overrides, with the consumers' {@code apply(Block)}. */
final class BlockInits{
    private BlockInits(){
    }

    /** Kinds whose constructor registers a content config ({@code config(Item.class, …)}), Block.java:1488. */
    private static final List<String> contentConfigs = List.of("landing-pad", "directional-unloader", "duct-router", "sorter",
        "constructor", "payload-router", "payload-source", "item-source", "liquid-source", "unloader", "unit-cargo-unload-point",
        "unit-factory");

    static void register(){
        init("block", BlockInits::block);
        // Block.java:1335; filling an empty shownPlanets from the requirements needs planets, which the pack carries baked
        postInit("block", (bake, b, sup) -> {
            String tag = b.s("databaseTag");
            if(tag == null || tag.isEmpty()) b.set("databaseTag", b.s("category"));
            sup.run();
        });

        init("wall", (bake, b, sup) -> {
            if(b.i("size") == 2 && unset(b, "destroySound")) b.set("destroySound", "sound:blockExplodeWall");
            if(!b.b("flashHit")) b.set("drawDynamic", false);
            b.set("drawCached", true);
            sup.run();
        });
        init("shield-wall", (bake, b, sup) -> {
            sup.run();
            b.set("drawDynamic", true);
        });
        init("base-shield", (bake, b, sup) -> {
            sup.run();
            clipRadius(b, b.f("radius"));
        });
        init("force-projector", (bake, b, sup) -> {
            clipRadius(b, b.f("radius") + b.f("phaseRadiusBoost") + 3f);
            sup.run();
        });
        init("conveyor", (bake, b, sup) -> {
            sup.run();
            if(b.get("junctionReplacement") == null) b.set("junctionReplacement", "junction");
            if(!isBlock(bake, b.s("bridgeReplacement"), "item-bridge", "duct-bridge")) b.set("bridgeReplacement", "bridge-conveyor");
        });
        init("duct", (bake, b, sup) -> {
            sup.run();
            if(!isBlock(bake, b.s("bridgeReplacement"), "duct-bridge", "item-bridge")) b.set("bridgeReplacement", "duct-bridge");
        });
        init("conduit", (bake, b, sup) -> {
            sup.run();
            if(b.get("junctionReplacement") == null) b.set("junctionReplacement", "liquid-junction");
            if(!isBlock(bake, b.s("bridgeReplacement"), "item-bridge")) b.set("bridgeReplacement", "bridge-conduit");
        });
        init("direction-bridge", (bake, b, sup) -> {
            clipRadius(b, (b.i("range") + 0.5f) * tilesize);
            sup.run();
        });
        init("duct-junction", (bake, b, sup) -> {
            b.set("itemCapacity", 4);
            sup.run();
        });
        init("item-bridge", (bake, b, sup) -> {
            sup.run();
            clipRadius(b, (b.i("range") + 0.5f) * tilesize);
        });
        // AirBlock.init doesn't call super.init(): air is never initialized as a block
        init("air-block", (bake, b, sup) -> b.set("decoration", bake.name(b)));
        init("floor", BlockInits::floor);
        init("ore-block", (bake, b, sup) -> {
            sup.run();
            Object drop = b.get("itemDrop");
            if(drop == null) bake.diagnostics.error(Mark.none, bake.name(b) + " must have an item drop");
            else b.set("mapColor", bake.get(ContentType.ITEM, (String)drop).s("color"));
        });
        init("tall-block", (bake, b, sup) -> {
            sup.run();
            b.set("hasShadow", true);
        });
        init("colored-floor", (bake, b, sup) -> sup.run());
        init("colored-wall", (bake, b, sup) -> sup.run());
        init("canvas-block", (bake, b, sup) -> {
            sup.run();
            b.set("bitsPerPixel", Mathf.log2(Mathf.nextPowerOfTwo(b.list("palette").size())));
            b.set("clipSize", Math.max(b.f("clipSize"), b.i("size") * 8 - b.f("padding")));
        });
        init("logic-display", (bake, b, sup) -> {
            sup.run();
            // Draw.scl: 1 on a server; a client sets it from its atlas (Control.java:330) and gets a smaller clip
            b.set("clipSize", Math.max(b.f("clipSize"), b.f("scaleFactor") * 1f * b.i("displaySize")));
        });
        init("payload-conveyor", (bake, b, sup) -> {
            sup.run();
            b.set("clipSize", Math.max(b.f("clipSize"), b.i("size") * tilesize * 2.1f));
        });
        init("payload-loader", (bake, b, sup) -> {
            // basePowerUse is consPower's usage, which only the dynamic consumer's lambda reads
            if(b.b("loadPowerDynamic")){
                Mut power = consPower(b);
                b.set("basePowerUse", power != null ? power.f("usage") : 0f);
                consume(bake, b, bake.create("consume-power-dynamic"));
            }
            sup.run();
        });
        init("payload-mass-driver", (bake, b, sup) -> {
            sup.run();
            clipRadius(b, b.f("range") + 4f);
        });
        init("battery", (bake, b, sup) -> {
            sup.run();
            if(b.get("drawer") == null){
                Mut power = bake.create("draw-power").set("emptyLightColor", b.get("emptyLightColor")).set("fullLightColor", b.get("fullLightColor"));
                Mut top = bake.create("draw-region").set("suffix", "-top");
                b.set("drawer", bake.create("draw-multi").set("drawers", new ArrayList<>(List.of(bake.create("draw-default"), power, top))));
            }
        });
        init("beam-node", (bake, b, sup) -> {
            sup.run();
            clipRadius(b, (b.i("range") + 1) * tilesize);
        });
        init("consume-generator", (bake, b, sup) -> {
            Mut filterItem = find(b, "consume-item-filter"), filterLiquid = find(b, "consume-liquid-filter");
            b.set("filterItem", filterItem);
            b.set("filterLiquid", filterLiquid);
            if(filterItem != null && filterItem.is("consume-item-efficiency")) filterItem.set("itemDurationMultipliers", copyValue(b.get("itemDurationMultipliers")));
            if(b.get("outputLiquid") != null){
                b.set("outputsLiquid", true);
                b.set("hasLiquids", true);
            }
            if(b.b("explodeOnFull") && b.get("outputLiquid") != null && b.get("explosionPuddleLiquid") == null){
                b.set("explosionPuddleLiquid", stackName(b.map("outputLiquid")));
            }
            b.set("emitLight", true);
            b.set("lightRadius", b.f("baseLightRadius") * b.i("size"));
            sup.run();
        });
        init("light-block", (bake, b, sup) -> {
            b.set("lightRadius", b.f("radius") * 2.5f);
            b.set("lightClipSize", Math.max(b.f("lightClipSize"), b.f("lightRadius") * 3f));
            b.set("emitLight", true);
            sup.run();
        });
        init("power-node", (bake, b, sup) -> {
            sup.run();
            b.set("clipSize", Math.max(b.f("clipSize"), b.f("laserRange") * tilesize));
        });
        init("thermal-generator", (bake, b, sup) -> {
            if(b.get("outputLiquid") != null){
                b.set("outputsLiquid", true);
                b.set("hasLiquids", true);
            }
            b.set("emitLight", true);
            sup.run();
            b.set("lightClipSize", Math.max(b.f("lightClipSize"), 45f * b.i("size") * 2f * 2f));
        });
        init("beam-drill", (bake, b, sup) -> {
            clipRadius(b, (b.i("range") + 2) * tilesize);
            sup.run();
            blockedItems(b);
        });
        init("drill", (bake, b, sup) -> {
            sup.run();
            blockedItems(b);
            if(b.f("drillEffectRnd") < 0) b.set("drillEffectRnd", (float)b.i("size"));
        });
        init("generic-crafter", (bake, b, sup) -> {
            if(b.get("outputItems") == null && b.get("outputItem") != null) b.set("outputItems", copyValue(b.get("outputItem")));
            if(b.get("outputLiquids") == null && b.get("outputLiquid") != null) b.set("outputLiquids", copyValue(b.get("outputLiquid")));
            // write back to outputLiquid, as it helps with sensing
            if(b.get("outputLiquid") == null && b.get("outputLiquids") != null && !b.map("outputLiquids").isEmpty()){
                var first = b.map("outputLiquids").entrySet().iterator().next();
                b.set("outputLiquid", new LinkedHashMap<>(Map.of(first.getKey(), first.getValue())));
            }
            b.set("outputsLiquid", b.get("outputLiquids") != null);
            if(b.get("outputItems") != null) b.set("hasItems", true);
            if(b.get("outputLiquids") != null) b.set("hasLiquids", true);
            sup.run();
        });
        init("core-block", (bake, b, sup) -> {
            b.set("lightRadius", 30f + 20f * b.i("size"));
            b.set("fogRadius", Math.max(b.i("fogRadius"), (int)(b.f("lightRadius") / 8f * 3f) + 13));
            b.set("emitLight", true);
            sup.run();
        });
        init("accelerator", (bake, b, sup) -> {
            // capacities (per item) is protected; itemCapacity is their sum
            int capacity = 0;
            Mut launch = bake.get(ContentType.BLOCK, b.s("launchBlock"));
            Map<String, Object> requirements = sortedStacks(bake, launch.map("requirements"));
            for(Object amount : requirements.values()) capacity += (Integer)amount;
            b.set("itemCapacity", capacity);
            consume(bake, b, bake.create("consume-items").set("items", new LinkedHashMap<>(requirements)));
            sup.run();
        });
        init("landing-pad", (bake, b, sup) -> {
            consume(bake, b, bake.create("consume-liquid").set("liquid", b.get("consumeLiquid")).set("amount", b.get("consumeLiquidAmount"))
                .set("update", false));
            sup.run();
        });
        init("unit-factory", (bake, b, sup) -> {
            List<Object> capacities = zeros(bake.count(ContentType.ITEM));
            int itemCapacity = 10;
            for(Mut plan : b.muts("plans")){
                for(var stack : plan.map("requirements").entrySet()){
                    int id = bake.id(ContentType.ITEM, stack.getKey()), amount = (Integer)stack.getValue() * 2;
                    capacities.set(id, Math.max((Integer)capacities.get(id), amount));
                    itemCapacity = Math.max(itemCapacity, amount);
                }
            }
            b.set("capacities", capacities);
            b.set("itemCapacity", itemCapacity);
            for(Mut c : b.muts("consumes")) c.set("multiplier", "hook:unit-factory.multiplier");
            sup.run();
        });
        init("reconstructor", (bake, b, sup) -> {
            List<Object> capacities = zeros(bake.count(ContentType.ITEM));
            int itemCapacity = 10;
            Mut items = find(b, "consume-items");
            if(items != null){
                for(var stack : items.map("items").entrySet()){
                    int id = bake.id(ContentType.ITEM, stack.getKey()), amount = (Integer)stack.getValue() * 2;
                    capacities.set(id, Math.max((Integer)capacities.get(id), amount));
                    itemCapacity = Math.max(itemCapacity, amount);
                }
            }
            b.set("capacities", capacities);
            b.set("itemCapacity", itemCapacity);
            for(Mut c : b.muts("consumes")) c.set("multiplier", "hook:reconstructor.multiplier");
            sup.run();
        });
        init("unit-assembler", (bake, b, sup) -> {
            clipRadius(b, (b.i("areaSize") + 1) * tilesize);
            consume(bake, b, bake.create("consume-payload-dynamic").set("payloads", "hook:unit-assembler.payloads"));
            consume(bake, b, bake.create("consume-item-dynamic").set("items", "hook:unit-assembler.items"));
            consume(bake, b, bake.create("consume-liquids-dynamic").set("liquids", "hook:unit-assembler.liquids"));
            sup.run();
            for(Mut c : b.muts("consumes")) c.set("multiplier", "hook:unit-assembler.multiplier");
            List<Object> capacities = zeros(bake.count(ContentType.ITEM));
            int itemCapacity = 10;
            List<Object> liquidFilter = b.list("liquidFilter");
            for(Mut plan : b.muts("plans")){
                if(plan.get("itemReq") != null){
                    for(var stack : plan.map("itemReq").entrySet()){
                        int id = bake.id(ContentType.ITEM, stack.getKey()), amount = (Integer)stack.getValue() * 2;
                        capacities.set(id, Math.max((Integer)capacities.get(id), amount));
                        itemCapacity = Math.max(itemCapacity, amount);
                    }
                }
                if(plan.get("liquidReq") != null){
                    for(String liquid : plan.map("liquidReq").keySet()){
                        int id = bake.id(ContentType.LIQUID, liquid);
                        if(id < liquidFilter.size()) liquidFilter.set(id, true);
                    }
                }
            }
            b.set("capacities", capacities);
            b.set("itemCapacity", itemCapacity);
        });
        init("repair-turret", (bake, b, sup) -> {
            if(b.b("acceptCoolant")){
                b.set("hasLiquids", true);
                consume(bake, b, bake.create("consume-coolant").set("amount", b.get("coolantUse")).set("optional", true).set("booster", true));
            }
            consume(bake, b, bake.create("consume-power-condition").set("usage", b.get("powerUse")));
            clipRadius(b, b.f("repairRadius") + tilesize);
            sup.run();
        });
    }

    // Block.java:1357
    private static void block(Bake bake, Mut b, Runnable sup){
        sup.run();
        int size = b.i("size");

        if(unset(b, "destroySound")){
            b.set("destroySound", size >= 3 ? "sound:blockExplode3" : size >= 2 ? randomSound(bake, "blockExplode2", "blockExplode2Alt")
                : randomSound(bake, "blockExplode1", "blockExplode1Alt"));
        }
        if(unset(b, "placeSound")) b.set("placeSound", size >= 3 ? "sound:blockPlace3" : size >= 2 ? "sound:blockPlace2" : "sound:blockPlace1");
        if(unset(b, "breakSound")) b.set("breakSound", size >= 3 ? "sound:blockBreak3" : size >= 2 ? "sound:blockBreak2" : "sound:blockBreak1");

        if(b.b("customShadow")) b.set("hasShadow", false);
        if(b.b("underBullets")) b.set("priority", -2f); // TargetPriority.under
        if(b.i("fogRadius") > 0) addToSet(bake, b, "flags", "hasFogRadius");
        if(b.b("sync")) addToSet(bake, b, "flags", "synced");

        Map<String, Object> requirements = sortedStacks(bake, b.map("requirements"));
        if(b.i("health") == -1){
            boolean round = false;
            if(b.f("scaledHealth") < 0){
                float scaledHealth = 40f;
                float scaling = 1f;
                for(String item : requirements.keySet()) scaling += bake.get(ContentType.ITEM, item).f("healthScaling");
                scaledHealth *= scaling;
                b.set("scaledHealth", scaledHealth);
                round = true;
            }
            float scaledHealth = b.f("scaledHealth");
            b.set("health", round ? Mathf.round(size * size * scaledHealth, 5) : (int)(size * size * scaledHealth));
        }

        b.set("clipSize", Math.max(b.f("clipSize"), size * tilesize));
        b.set("lightClipSize", Math.max(b.f("lightClipSize"), b.f("clipSize")));

        if(b.b("hasLiquids") && b.b("drawLiquidLight")){
            b.set("emitLight", true);
            b.set("lightClipSize", Math.max(b.f("lightClipSize"), size * 30f * 2f));
        }

        // some blocks don't have hasLiquids, but have liquid consumers/liquid capacity
        if(b.f("liquidCapacity") < 0){
            float consumeAmount = 1f;
            for(Mut c : b.muts("consumes")){
                if(c.is("consume-liquid-base")){
                    consumeAmount = Math.max(consumeAmount, c.f("amount") * 60f);
                }else if(c.is("consume-liquids")){
                    for(Object amount : c.map("liquids").values()) consumeAmount = Math.max(consumeAmount, (Float)amount * 60f);
                }
            }
            b.set("liquidCapacity", (float)Mathf.round(10f * consumeAmount));
        }

        if(b.b("emitLight")) b.set("lightClipSize", Math.max(b.f("lightClipSize"), b.f("lightRadius") * 2f));

        if("transportation".equals(b.s("group")) || "distribution".equals(b.s("category"))) b.set("acceptsItems", true);

        b.set("offset", ((size + 1) % 2) * tilesize / 2f);
        b.set("sizeOffset", -((size - 1) / 2));

        if(!requirements.isEmpty() && b.f("buildTime") < 0){
            float buildTime = 0f;
            for(var stack : requirements.entrySet()){
                buildTime += (Integer)stack.getValue() * bake.get(ContentType.ITEM, stack.getKey()).f("cost");
            }
            b.set("buildTime", buildTime);
        }
        if(b.f("buildTime") < 0) b.set("buildTime", 20f);
        b.set("buildTime", b.f("buildTime") * b.f("buildCostMultiplier"));

        b.set("hasConsumers", !b.list("consumes").isEmpty());
        b.set("itemFilter", falses(bake.count(ContentType.ITEM)));
        b.set("liquidFilter", falses(bake.count(ContentType.LIQUID)));
        for(Mut c : b.muts("consumes")) apply(bake, b, c);

        if(!b.b("logicConfigurable")){
            for(String kind : contentConfigs){
                if(b.is(kind)) b.set("logicConfigurable", true);
            }
        }

        Mut power = consPower(b);
        if(!b.b("outputsPower") && power != null && power.b("buffered")) power.set("buffered", false);

        if("visibility:sandboxOnly".equals(b.s("buildVisibility"))) b.set("hideDetails", false);
    }

    // Floor.java:154
    private static void floor(Bake bake, Mut b, Runnable sup){
        sup.run();
        String name = bake.name(b);
        b.set("blendId", bake.id(ContentType.BLOCK, b.s("blendGroup")));

        if("air".equals(b.s("wall"))){
            String wall = exists(bake, name + "-wall") ? name + "-wall" : exists(bake, name.replace("darksand", "dune") + "-wall")
                ? name.replace("darksand", "dune") + "-wall" : null;
            b.set("wall", wall == null ? "air" : wall);
        }
        if(b.get("wall") == null) b.set("wall", "air");
        if(b.get("decoration") == null) b.set("decoration", exists(bake, name + "-boulder") ? name + "-boulder" : null);
        if(b.b("isLiquid") && "fx:none".equals(b.get("walkEffect"))) b.set("walkEffect", "fx:ripple");
        if(b.b("isLiquid") && "sound:none".equals(b.get("walkSound"))) b.set("walkSound", "sound:stepWater");
    }

    // consumers

    /** {@code Block.consume}: there is only one power consumer, a new one replaces the old. */
    static Mut consume(Bake bake, Mut block, Mut consume){
        if(consume.is("consume-power")) block.muts("consumes").removeIf(c -> c.is("consume-power"));
        block.muts("consumes").add(consume);
        return consume;
    }

    /** {@code findConsumer(c -> c instanceof kind)}. */
    static Mut find(Mut block, String kind){
        for(Mut c : block.muts("consumes")) if(c.is(kind)) return c;
        return null;
    }

    /** {@code consPower}: the power consumer, if any. */
    static Mut consPower(Mut block){
        return find(block, "consume-power");
    }

    /** {@code Consume.apply(Block)} for each consumer kind. */
    private static void apply(Bake bake, Mut b, Mut c){
        if(c.is("consume-item-explode")) return;
        if(c.is("consume-item-dynamic")){
            b.set("hasItems", true);
            b.set("acceptsItems", true);
        }else if(c.is("consume-item-filter")){
            b.set("hasItems", true);
            b.set("acceptsItems", true);
            List<Object> filter = b.list("itemFilter");
            List<Mut> items = bake.all(ContentType.ITEM);
            for(int i = 0; i < items.size(); i++){
                if(Hooks.itemFilter(bake, b, c, items.get(i)) && i < filter.size()) filter.set(i, true);
            }
        }else if(c.is("consume-items")){
            b.set("hasItems", true);
            b.set("acceptsItems", true);
            List<Object> filter = b.list("itemFilter");
            for(String item : c.map("items").keySet()){
                int id = bake.id(ContentType.ITEM, item);
                if(id < filter.size()) filter.set(id, true);
            }
        }else if(c.is("consume-liquid")){
            b.set("hasLiquids", true);
            int id = bake.id(ContentType.LIQUID, c.s("liquid"));
            if(id < b.list("liquidFilter").size()) b.list("liquidFilter").set(id, true);
        }else if(c.is("consume-liquid-filter")){
            b.set("hasLiquids", true);
            List<Object> filter = b.list("liquidFilter");
            List<Mut> liquids = bake.all(ContentType.LIQUID);
            for(int i = 0; i < liquids.size(); i++){
                if(Hooks.liquidFilter(bake, b, c, liquids.get(i)) && i < filter.size()) filter.set(i, true);
            }
        }else if(c.is("consume-liquid-base") || c.is("consume-liquids-dynamic")){
            b.set("hasLiquids", true);
        }else if(c.is("consume-liquids")){
            b.set("hasLiquids", true);
            List<Object> filter = b.list("liquidFilter");
            for(String liquid : c.map("liquids").keySet()){
                int id = bake.id(ContentType.LIQUID, liquid);
                if(id < filter.size()) filter.set(id, true);
            }
        }else if(c.is("consume-power")){
            b.set("hasPower", true);
        }
    }

    // helpers

    private static void clipRadius(Mut b, float radius){
        b.set("clipSize", Math.max(b.f("clipSize"), b.i("size") * tilesize + radius * 2f));
    }

    private static void blockedItems(Mut b){
        if(b.get("blockedItems") == null && b.get("blockedItem") != null) b.set("blockedItems", new ArrayList<>(List.of(b.get("blockedItem"))));
    }

    private static boolean isBlock(Bake bake, String name, String... kinds){
        if(name == null) return false;
        Mut block = bake.get(ContentType.BLOCK, name);
        if(block == null) return false;
        for(String kind : kinds) if(block.is(kind)) return true;
        return false;
    }

    private static boolean exists(Bake bake, String name){
        return bake.get(ContentType.BLOCK, name) != null;
    }

    private static String stackName(Map<String, Object> stack){
        return stack.keySet().iterator().next();
    }
}
