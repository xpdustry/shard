package com.xpdustry.shard.sim.building;

import com.xpdustry.shard.sim.world.*;

/**
 * A building's liquids (vanilla {@code LiquidModule}): one amount per liquid id and the current liquid. Allocated only
 * for blocks with {@code hasLiquids}. Liquids are their content ids; flow statistics are not simulation state.
 *
 * <p>Vanilla quirk kept: {@link #remove} is an {@link #add} of a negative amount, so removing a liquid makes it the
 * current one.
 */
public final class LiquidModule{
    public final float[] liquids;
    /** The current liquid's id (vanilla starts at liquid 0). */
    public int current;

    public LiquidModule(int liquidCount){
        liquids = new float[liquidCount];
    }

    private LiquidModule(LiquidModule source){
        liquids = source.liquids.clone();
        current = source.current;
    }

    public LiquidModule copy(){
        return new LiquidModule(this);
    }

    /** The last liquid received or loaded; only meaningful for modules that hold one liquid. */
    public int current(){
        return current;
    }

    public float currentAmount(){
        return liquids[current];
    }

    public float get(int liquid){
        return liquids[liquid];
    }

    /** Empties the module, then holds {@code amount} of {@code liquid}, now current. */
    public void reset(int liquid, float amount){
        java.util.Arrays.fill(liquids, 0f);
        liquids[liquid] = amount;
        current = liquid;
    }

    /** Sets the amount; {@code liquid} becomes current when it is at least the current liquid's amount. */
    public void set(int liquid, float amount){
        if(amount >= liquids[current]) current = liquid;
        liquids[liquid] = amount;
    }

    public void add(int liquid, float amount){
        liquids[liquid] += amount;
        current = liquid;
    }

    /** Removes up to {@code amount}: never below zero. */
    public void remove(int liquid, float amount){
        add(liquid, Math.max(-amount, -liquids[liquid]));
    }

    public void clear(){
        java.util.Arrays.fill(liquids, 0f);
    }

    /** Every positive amount, in id order. */
    public void each(Each action){
        for(int i = 0; i < liquids.length; i++){
            if(liquids[i] > 0) action.accept(i, liquids[i]);
        }
    }

    /** {@code sum}: of {@code calc} over every positive amount. */
    public float sum(Sum calc){
        float sum = 0f;
        for(int i = 0; i < liquids.length; i++){
            if(liquids[i] > 0) sum += calc.get(i, liquids[i]);
        }
        return sum;
    }

    public void hash(Hasher hasher){
        hasher.floats(liquids).i(current);
    }

    @FunctionalInterface
    public interface Each{
        void accept(int liquid, float amount);
    }

    @FunctionalInterface
    public interface Sum{
        float get(int liquid, float amount);
    }
}
