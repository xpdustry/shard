package com.xpdustry.shard.sim.building;

import java.util.*;

/**
 * An immutable list of content stacks (vanilla {@code ItemStack[]}, {@code LiquidStack[]}, {@code PayloadStack}
 * sequences): ids with integer or float amounts, in definition order. Consumers hold them; dynamic consumers get them
 * from their building.
 */
public final class Stacks{
    /** No stacks ({@code ItemStack.empty}). */
    public static final Stacks EMPTY = new Stacks(new int[0], new float[0]);

    private final int[] ids;
    private final float[] amounts;

    private Stacks(int[] ids, float[] amounts){
        this.ids = ids;
        this.amounts = amounts;
    }

    /** Stacks of {@code ids[i]} × {@code amounts[i]}; the arrays are copied. */
    public static Stacks of(int[] ids, float[] amounts){
        if(ids.length != amounts.length) throw new IllegalArgumentException(ids.length + " ids, " + amounts.length + " amounts");
        return ids.length == 0 ? EMPTY : new Stacks(ids.clone(), amounts.clone());
    }

    /** Integer stacks, alternating id and amount: {@code Stacks.items(copper, 2, lead, 1)}. */
    public static Stacks items(int... idAmountPairs){
        if(idAmountPairs.length % 2 != 0) throw new IllegalArgumentException("ids and amounts come in pairs");
        int[] ids = new int[idAmountPairs.length / 2];
        float[] amounts = new float[ids.length];
        for(int i = 0; i < ids.length; i++){
            ids[i] = idAmountPairs[i * 2];
            amounts[i] = idAmountPairs[i * 2 + 1];
        }
        return ids.length == 0 ? EMPTY : new Stacks(ids, amounts);
    }

    public int size(){
        return ids.length;
    }

    public boolean isEmpty(){
        return ids.length == 0;
    }

    /** The content id of stack {@code i}. */
    public int id(int i){
        return ids[i];
    }

    /** The amount of stack {@code i}, as an item count. */
    public int amount(int i){
        return (int)amounts[i];
    }

    /** The amount of stack {@code i}, as a liquid amount per tick. */
    public float amountF(int i){
        return amounts[i];
    }

    @Override
    public boolean equals(Object o){
        return o instanceof Stacks s && Arrays.equals(ids, s.ids) && Arrays.equals(amounts, s.amounts);
    }

    @Override
    public int hashCode(){
        return 31 * Arrays.hashCode(ids) + Arrays.hashCode(amounts);
    }

    @Override
    public String toString(){
        var out = new StringBuilder("[");
        for(int i = 0; i < ids.length; i++){
            if(i > 0) out.append(", ");
            out.append(ids[i]).append('×').append(amounts[i]);
        }
        return out.append(']').toString();
    }
}
