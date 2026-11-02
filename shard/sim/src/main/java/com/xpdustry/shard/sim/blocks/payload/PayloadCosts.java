package com.xpdustry.shard.sim.blocks.payload;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.build.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.building.consume.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * What a payload costs, as vanilla's {@code Payload.requirements()} and {@code buildTime()} give it: a building's block's
 * requirements (sorted by item id, {@code Block.init}) and build time; a unit type's {@code getTotalRequirements()} and
 * {@code getBuildTime()} (UnitType.java:1314-1395), which walk back through the first reconstructor that makes it (by
 * block id) to the factory or assembler that makes the first stage, summing every stage's items in item id order. A
 * unit's build time is vanilla's: -1, its field's default, plus each item's cost times its amount.
 *
 * <p>Unit costs depend on the content only, so they are worked out once per epoch.
 */
public final class PayloadCosts{
    private static final Map<ContentEpoch, PayloadCosts> BY_EPOCH = new WeakHashMap<>();

    private final ContentEpoch content;
    private final Stacks[] unitRequirements;
    private final float[] unitBuildTimes;

    private PayloadCosts(ContentEpoch content){
        this.content = content;
        unitRequirements = new Stacks[content.units()];
        unitBuildTimes = new float[content.units()];
    }

    private static PayloadCosts of(ContentEpoch content){
        synchronized(BY_EPOCH){
            return BY_EPOCH.computeIfAbsent(content, PayloadCosts::new);
        }
    }

    /** The placement data of the block with id {@code block}: the world's construction's, or made afresh. */
    public static PlaceInfo place(World world, int block){
        return Construction.present(world) ? Construction.of(world).info(block) : PlaceInfo.of(world.content().block(block), world.content());
    }

    /** {@code payload.requirements()}. */
    public static Stacks requirements(World world, Payload payload){
        return switch(payload){
            case Payload.BuildPayload build -> blockRequirements(world, build.block().id());
            case Payload.UnitPayload unit -> unitRequirements(world.content(), unit.unit().type().id());
        };
    }

    /** {@code payload.buildTime()}. */
    public static float buildTime(World world, Payload payload){
        return switch(payload){
            case Payload.BuildPayload build -> place(world, build.block().id()).buildTime;
            case Payload.UnitPayload unit -> unitBuildTime(world.content(), unit.unit().type().id());
        };
    }

    /** {@code block.requirements}, in item id order. */
    public static Stacks blockRequirements(World world, int block){
        PlaceInfo info = place(world, block);
        int[] pairs = new int[info.items.length * 2];
        for(int i = 0; i < info.items.length; i++){
            pairs[i * 2] = info.items[i];
            pairs[i * 2 + 1] = info.amounts[i];
        }
        return Stacks.items(pairs);
    }

    /** {@code type.getTotalRequirements()}. */
    public static Stacks unitRequirements(ContentEpoch content, int unit){
        PayloadCosts costs = of(content);
        synchronized(costs){
            return costs.total(unit);
        }
    }

    /** {@code type.getBuildTime()}. */
    public static float unitBuildTime(ContentEpoch content, int unit){
        PayloadCosts costs = of(content);
        synchronized(costs){
            costs.total(unit);
            return costs.unitBuildTimes[unit];
        }
    }

    /** {@code getTotalRequirements()}, memoized as vanilla's is, with its guard against circular plans. */
    private Stacks total(int unit){
        if(unitRequirements[unit] != null) return unitRequirements[unit];
        int[] previous = {-1};
        int[] result = requirements(unit, previous);
        // prevents stack overflow if requirements are circular and result != null
        unitRequirements[unit] = Stacks.EMPTY;
        Stacks total = Stacks.EMPTY;
        if(result != null){
            int[] sums = new int[content.items()];
            for(int i = 0; i < result.length; i += 2) sums[result[i]] += result[i + 1];
            if(previous[0] != -1){
                Stacks before = total(previous[0]);
                for(int i = 0; i < before.size(); i++) sums[before.id(i)] += before.amount(i);
            }
            total = sequence(sums);
        }
        unitRequirements[unit] = total;
        float buildTime = -1f;
        for(int i = 0; i < total.size(); i++) buildTime += content.item(total.id(i)).cost() * total.amount(i);
        unitBuildTimes[unit] = buildTime;
        return total;
    }

    /** {@code ItemSeq.toArray()}: the non-zero sums in item id order. */
    private static Stacks sequence(int[] sums){
        int count = 0;
        for(int sum : sums) if(sum != 0) count++;
        int[] pairs = new int[count * 2];
        int at = 0;
        for(int item = 0; item < sums.length; item++){
            if(sums[item] == 0) continue;
            pairs[at++] = item;
            pairs[at++] = sums[item];
        }
        return Stacks.items(pairs);
    }

    /**
     * {@code getRequirements(prevReturn, null)}: the first reconstructor upgrading to {@code unit} with an item
     * consumer (and the unit it upgrades, into {@code previous}), else the first factory plan, else the first assembler
     * plan (each block or unit stack counted as its requirements); null if none makes it. As (item, amount) pairs.
     */
    private int[] requirements(int unit, int[] previous){
        String name = content.unit(unit).name();
        for(int id = 0; id < content.blocks(); id++){
            BlockDef block = content.block(id);
            if(!block.baked() || !block.isA(KindKey.of("reconstructor"))) continue;
            String from = null;
            for(Object upgrade : block.fields().list("upgrades")){
                List<?> pair = (List<?>)upgrade;
                if(name.equals(pair.get(1))){
                    from = (String)pair.get(0);
                    break;
                }
            }
            if(from == null) continue;
            // the first reconstructor making it decides, with or without an item consumer
            ConsumeItems items = Consumers.read(block, content).find(ConsumeItems.class);
            if(items == null || items.dynamic()) break;
            previous[0] = content.unit(from).id();
            int[] out = new int[items.items.size() * 2];
            for(int i = 0; i < items.items.size(); i++){
                out[i * 2] = items.items.id(i);
                out[i * 2 + 1] = items.items.amount(i);
            }
            return out;
        }
        for(int id = 0; id < content.blocks(); id++){
            BlockDef block = content.block(id);
            if(!block.baked() || !block.isA(KindKey.of("unit-factory"))) continue;
            for(var plan : block.fields().structs("plans")){
                if(!name.equals(plan.s("unit"))) continue;
                var stacks = plan.map("requirements");
                int[] out = new int[stacks.size() * 2];
                int at = 0;
                for(var stack : stacks.entrySet()){
                    out[at++] = content.item(stack.getKey()).id();
                    out[at++] = (Integer)stack.getValue();
                }
                return out;
            }
        }
        for(int id = 0; id < content.blocks(); id++){
            BlockDef block = content.block(id);
            if(!block.baked() || !block.isA(KindKey.of("unit-assembler"))) continue;
            for(var plan : block.fields().structs("plans")){
                if(!name.equals(plan.s("unit"))) continue;
                int[] sums = new int[content.items()];
                for(Object entry : plan.list("requirements")){
                    var stack = ((Map<?, ?>)entry).entrySet().iterator().next();
                    String part = (String)stack.getKey();
                    int amount = (Integer)stack.getValue();
                    ContentDef unitPart = content.find(ContentType.UNIT, part);
                    if(unitPart != null){
                        Stacks parts = total(unitPart.id());
                        for(int i = 0; i < parts.size(); i++) sums[parts.id(i)] += parts.amount(i) * amount;
                    }else{
                        for(var req : content.block(part).requirements().entrySet()){
                            sums[content.item(req.getKey()).id()] += req.getValue() * amount;
                        }
                    }
                }
                Stacks seq = sequence(sums);
                int[] out = new int[seq.size() * 2];
                for(int i = 0; i < seq.size(); i++){
                    out[i * 2] = seq.id(i);
                    out[i * 2 + 1] = seq.amount(i);
                }
                return out;
            }
        }
        return null;
    }
}
