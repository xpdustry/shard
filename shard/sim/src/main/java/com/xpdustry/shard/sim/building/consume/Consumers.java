package com.xpdustry.shard.sim.building.consume;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.content.schema.*;
import com.xpdustry.shard.sim.building.*;

import java.util.*;

/**
 * A block's consumers, as vanilla's {@code Block.init} groups them (Block.java:1466): every consumer in definition
 * order, the optional and the required ones without the ignored (buffered power), the updated ones, and the power
 * consumer ({@code consPower}). Read once per block and epoch from the definition's baked {@code consumes} list; hand-built
 * definitions have none.
 *
 * <p>Filters are evaluated here, over the epoch's items and liquids, from the consumer kind's own lambda or a block
 * kind's known hook ({@code item-turret.filter} and the liquid turrets'). Dynamic amounts, multipliers and power
 * conditions are hooks the building answers ({@link Building#consumedItems()} and friends). A hook nothing ports is a
 * load error naming it.
 */
public final class Consumers{
    /** No consumers: a block without any, or a hand-built definition. */
    public static final Consumers NONE = new Consumers(List.of());

    private final Consume[] all, optional, nonOptional, update;
    private final ConsumePower power;

    private Consumers(List<Consume> list){
        all = list.toArray(Consume[]::new);
        optional = list.stream().filter(c -> c.optional && !c.ignore()).toArray(Consume[]::new);
        nonOptional = list.stream().filter(c -> !c.optional && !c.ignore()).toArray(Consume[]::new);
        update = list.stream().filter(c -> c.update && !c.ignore()).toArray(Consume[]::new);
        ConsumePower found = null;
        for(Consume c : all){
            if(c instanceof ConsumePower p && found == null) found = p;
        }
        power = found;
    }

    /** Exactly these consumers, in this order, for tests and hand-built content. */
    public static Consumers of(List<Consume> consumers){
        return consumers.isEmpty() ? NONE : new Consumers(consumers);
    }

    /** {@code block}'s consumers, read from its definition with {@code content}'s items and liquids. */
    public static Consumers read(BlockDef block, ContentEpoch content){
        if(!block.baked()) return NONE;
        var list = new ArrayList<Consume>();
        for(Struct c : block.fields().structs("consumes")) list.add(read(block, c, content));
        return of(list);
    }

    /** {@code hasConsumers}. */
    public boolean any(){
        return all.length > 0;
    }

    /** {@code consumers}: every consumer, in definition order. */
    public Consume[] all(){
        return all;
    }

    /** {@code optionalConsumers}. */
    public Consume[] optional(){
        return optional;
    }

    /** {@code nonOptionalConsumers}. */
    public Consume[] nonOptional(){
        return nonOptional;
    }

    /** {@code updateConsumers}. */
    public Consume[] update(){
        return update;
    }

    /** {@code consPower}: the power consumer, or null. */
    public ConsumePower power(){
        return power;
    }

    /** {@code findConsumer(c -> c instanceof type)}: the first consumer of {@code type}, or null. */
    public <T extends Consume> T find(Class<T> type){
        for(Consume c : all){
            if(type.isInstance(c)) return type.cast(c);
        }
        return null;
    }

    // reading

    private static Consume read(BlockDef block, Struct c, ContentEpoch content){
        KindSchema kind = c.kind();
        String key = kind.key();
        // LiquidTurret.init's consumer overrides update() to do nothing: the same as not updating it
        boolean update = c.b("update") && !"hook:liquid-turret.filter".equals(c.has("filter") ? c.s("filter") : null);
        var flags = new Consume.Flags(c.b("optional"), c.b("booster"), update, !isOwnHook(c.s("multiplier")));
        if(kind.isA("consume-item-explode")){
            if(!isOwnHook(c.s("filter"))) throw unknownHook(block, c.s("filter"));
            int items = content.items();
            boolean[] filter = new boolean[items];
            float[] explosiveness = new float[items];
            for(int i = 0; i < items; i++){
                explosiveness[i] = content.item(i).explosiveness();
                filter[i] = explosiveness[i] >= c.f("threshold");
            }
            return new ConsumeItemExplode(flags, filter, explosiveness, c.f("damage"), c.f("threshold"), c.f("baseChance"));
        }
        if(kind.isA("consume-item-filter")){
            int items = content.items();
            boolean[] filter = new boolean[items];
            float[] multipliers = new float[items];
            for(int i = 0; i < items; i++){
                ItemDef item = content.item(i);
                filter[i] = itemFilter(block, c, item);
                multipliers[i] = switch(key){
                    case "consume-item-flammable" -> item.flammability();
                    case "consume-item-radioactive" -> item.radioactivity();
                    case "consume-item-explosive" -> item.explosiveness();
                    case "consume-item-charged" -> item.charge();
                    case "consume-item-list" -> listMultiplier(c, item);
                    default -> 1f;
                };
            }
            return key.equals("consume-item-list") ? new ConsumeItemList(flags, filter, multipliers) : new ConsumeItemFilter(flags, filter, multipliers);
        }
        if(kind.isA("consume-items")) return ConsumeItems.fixed(flags, stacks(c.map("items"), ContentType.ITEM, content, block));
        if(kind.isA("consume-item-dynamic")) return ConsumeItems.dynamic(flags, c.s("items"));
        if(kind.isA("consume-liquid")) return new ConsumeLiquid(flags, require(content, ContentType.LIQUID, c.s("liquid"), block), c.f("amount"));
        if(kind.isA("consume-liquid-filter")){
            int liquids = content.liquids();
            boolean[] filter = new boolean[liquids];
            float[] multipliers = new float[liquids];
            for(int i = 0; i < liquids; i++){
                LiquidDef liquid = content.liquid(i);
                filter[i] = liquidFilter(block, c, liquid);
                multipliers[i] = key.equals("consume-liquid-flammable") ? liquid.flammability() : 1f;
            }
            return new ConsumeLiquidFilter(flags, c.f("amount"), filter, multipliers);
        }
        if(kind.isA("consume-liquids")) return ConsumeLiquids.fixed(flags, stacks(c.map("liquids"), ContentType.LIQUID, content, block));
        if(kind.isA("consume-liquids-dynamic")) return ConsumeLiquids.dynamic(flags, c.s("liquids"));
        if(kind.isA("consume-power")){
            ConsumePower.Mode mode = switch(key){
                case "consume-power-condition" -> ConsumePower.Mode.CONDITION;
                case "consume-power-dynamic" -> ConsumePower.Mode.DYNAMIC;
                default -> ConsumePower.Mode.FIXED;
            };
            return new ConsumePower(flags, mode, c.f("usage"), c.f("capacity"), c.b("buffered"));
        }
        if(kind.isA("consume-payload-dynamic")) return ConsumePayloads.dynamic(flags, c.s("payloads"));
        throw new IllegalStateException("block " + block.name() + ": consumer kind " + key + " is not ported");
    }

    /**
     * {@code ConsumeItemFilter.filter} for {@code item}: the consumer class's own lambda, or the item turret's ammo
     * filter. Mirrors the bake's {@code Hooks.itemFilter}, which fills the block's combined filter.
     */
    private static boolean itemFilter(BlockDef block, Struct c, ItemDef item){
        String hook = c.s("filter");
        if(isOwnHook(hook)){
            return switch(c.kind().key()){
                case "consume-item-flammable" -> item.flammability() >= c.f("minFlammability");
                case "consume-item-radioactive" -> item.radioactivity() >= c.f("minRadioactivity");
                case "consume-item-explosive" -> item.explosiveness() >= c.f("minExplosiveness");
                case "consume-item-charged" -> item.charge() >= c.f("minCharge");
                case "consume-item-list" -> c.map("itemMultipliers").containsKey(item.name());
                // ConsumeItemFilter.filter starts as i -> false
                default -> false;
            };
        }
        if(hook.equals("hook:item-turret.filter")) return block.fields().map("ammoTypes").containsKey(item.name());
        throw unknownHook(block, hook);
    }

    /** {@code ConsumeLiquidFilter.filter} for {@code liquid}, as {@link #itemFilter}. */
    private static boolean liquidFilter(BlockDef block, Struct c, LiquidDef liquid){
        String hook = c.s("filter");
        if(isOwnHook(hook)){
            return switch(c.kind().key()){
                case "consume-coolant" -> liquid.coolant() && (c.b("allowLiquid") && !liquid.gas() || c.b("allowGas") && liquid.gas())
                    && liquid.temperature() <= c.f("maxTemp") && liquid.flammability() < c.f("maxFlammability");
                case "consume-liquid-flammable" -> liquid.flammability() >= c.f("minFlammability");
                // ConsumeLiquidFilter.filter starts as l -> false
                default -> false;
            };
        }
        if(hook.equals("hook:liquid-turret.filter") || hook.equals("hook:continuous-liquid-turret.filter")){
            return block.fields().map("ammoTypes").containsKey(liquid.name());
        }
        throw unknownHook(block, hook);
    }

    private static float listMultiplier(Struct c, ItemDef item){
        Object value = c.map("itemMultipliers").get(item.name());
        return value == null ? 1f : ((Number)value).floatValue();
    }

    /** Whether a hook is a consumer class's own lambda ({@code hook:ConsumeCoolant#0}), not a block kind's. */
    private static boolean isOwnHook(String hook){
        return hook == null || hook.startsWith("hook:Consume");
    }

    private static Stacks stacks(Map<String, Object> amounts, ContentType type, ContentEpoch content, BlockDef block){
        int[] ids = new int[amounts.size()];
        float[] values = new float[amounts.size()];
        int i = 0;
        for(var entry : amounts.entrySet()){
            ids[i] = require(content, type, entry.getKey(), block);
            values[i++] = ((Number)entry.getValue()).floatValue();
        }
        return Stacks.of(ids, values);
    }

    private static int require(ContentEpoch content, ContentType type, String name, BlockDef block){
        int id = content.id(type, name);
        if(id < 0) throw new IllegalStateException("block " + block.name() + ": no " + type.yamlName() + " named " + name);
        return id;
    }

    private static IllegalStateException unknownHook(BlockDef block, String hook){
        return new IllegalStateException("block " + block.name() + ": consumer hook " + hook + " is not ported");
    }
}
