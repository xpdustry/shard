package com.xpdustry.shard.sim.building.consume;

import com.xpdustry.shard.sim.building.*;

import java.util.*;

/**
 * Vanilla {@code ConsumePayloads}, {@code ConsumePayloadDynamic} and {@code ConsumePayloadFilter}: payloads held in
 * the building's {@link PayloadStock}, taken on {@link #trigger}.
 *
 * <ul>
 *   <li>{@link #fixed}: a list of stacks, each scaled by the multiplier.</li>
 *   <li>{@link #dynamic}: the stacks {@link Building#consumedPayloads()} gives, named by {@link #hook}.</li>
 *   <li>{@link #filter}: one of any {@code fitting} content (blocks first, then units, as vanilla's cache), unscaled.</li>
 * </ul>
 */
public final class ConsumePayloads extends Consume{
    private enum Form{FIXED, DYNAMIC, FILTER}

    private final Form form;
    private final List<PayloadStock.Stack> stacks;
    /** The dynamic consumer's hook name ({@code hook:unit-assembler.payloads}), or null. */
    public final String hook;

    private ConsumePayloads(Flags flags, Form form, List<PayloadStock.Stack> stacks, String hook){
        super(flags);
        this.form = form;
        this.stacks = List.copyOf(stacks);
        this.hook = hook;
    }

    /** {@code ConsumePayloads}. */
    public static ConsumePayloads fixed(Flags flags, List<PayloadStock.Stack> stacks){
        return new ConsumePayloads(flags, Form.FIXED, stacks, null);
    }

    /** {@code ConsumePayloadDynamic}. */
    public static ConsumePayloads dynamic(Flags flags, String hook){
        return new ConsumePayloads(flags, Form.DYNAMIC, List.of(), hook);
    }

    /** {@code ConsumePayloadFilter}: {@code fitting} holds one stack of amount 1 per accepted content, in vanilla's order. */
    public static ConsumePayloads filter(Flags flags, List<PayloadStock.Stack> fitting){
        return new ConsumePayloads(flags, Form.FILTER, fitting, null);
    }

    private List<PayloadStock.Stack> stacks(Building building){
        return form == Form.DYNAMIC ? building.consumedPayloads() : stacks;
    }

    @Override
    public float efficiency(Building building){
        PayloadStock payloads = building.payloads();
        if(form == Form.FILTER){
            for(PayloadStock.Stack stack : stacks){
                if(payloads.contains(stack.type(), stack.id(), 1)) return 1f;
            }
            return 0f;
        }
        float multiplier = multiplier(building);
        for(PayloadStock.Stack stack : stacks(building)){
            if(!payloads.contains(stack.type(), stack.id(), Math.round(stack.amount() * multiplier))) return 0f;
        }
        return 1f;
    }

    @Override
    public void trigger(Building building){
        PayloadStock payloads = building.payloads();
        if(form == Form.FILTER){
            for(PayloadStock.Stack stack : stacks){
                if(payloads.contains(stack.type(), stack.id(), 1)){
                    payloads.remove(stack.type(), stack.id(), 1);
                    return;
                }
            }
            return;
        }
        float multiplier = multiplier(building);
        for(PayloadStock.Stack stack : stacks(building)){
            payloads.remove(stack.type(), stack.id(), Math.round(stack.amount() * multiplier));
        }
    }
}
