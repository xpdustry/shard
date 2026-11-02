package com.xpdustry.shard.sim.building;

import com.xpdustry.shard.sim.world.*;

/**
 * A building's items (vanilla {@code ItemModule}): one count per item id, the total and the take rotation. Allocated
 * only for blocks with {@code hasItems}. Items are their content ids. The flow statistics vanilla keeps for its UI
 * ({@code flow}, {@code cacheSums}) are not simulation state, so {@code handleFlow} has no counterpart.
 */
public final class ItemModule{
    public final int[] items;
    public int total;
    public int takeRotation;

    public ItemModule(int itemCount){
        items = new int[itemCount];
    }

    private ItemModule(ItemModule source){
        items = source.items.clone();
        total = source.total;
        takeRotation = source.takeRotation;
    }

    public ItemModule copy(){
        return new ItemModule(this);
    }

    /** {@code set(ItemModule)}: this module takes {@code other}'s counts, total and rotation. */
    public void set(ItemModule other){
        total = other.total;
        takeRotation = other.takeRotation;
        System.arraycopy(other.items, 0, items, 0, items.length);
    }

    public int length(){
        return items.length;
    }

    public int get(int item){
        return items[item];
    }

    public boolean has(int item){
        return items[item] > 0;
    }

    public boolean has(int item, int amount){
        return items[item] >= amount;
    }

    /** {@code has(ItemStack[])}: at least each stack's amount. */
    public boolean has(Stacks stacks){
        for(int i = 0; i < stacks.size(); i++){
            if(!has(stacks.id(i), stacks.amount(i))) return false;
        }
        return true;
    }

    /** {@code has(ItemStack[], multiplier)}: each amount scaled and rounded as {@code Math.round}, ids past the module refused. */
    public boolean has(Stacks stacks, float multiplier){
        for(int i = 0; i < stacks.size(); i++){
            if(stacks.id(i) >= items.length || !has(stacks.id(i), Math.round(stacks.amount(i) * multiplier))) return false;
        }
        return true;
    }

    /** {@code hasOne}: at least one of each stack's item. */
    public boolean hasOne(Stacks stacks){
        for(int i = 0; i < stacks.size(); i++){
            if(!has(stacks.id(i), 1)) return false;
        }
        return true;
    }

    public boolean empty(){
        return total == 0;
    }

    public int total(){
        return total;
    }

    public boolean any(){
        return total > 0;
    }

    /** The first item held, in id order, or -1. */
    public int first(){
        for(int i = 0; i < items.length; i++){
            if(items[i] > 0) return i;
        }
        return -1;
    }

    /** Takes one item, starting at the take rotation, and returns it, or -1 when empty. */
    public int take(){
        for(int i = 0; i < items.length; i++){
            int index = i + takeRotation;
            if(index >= items.length) index -= items.length;
            if(items[index] > 0){
                items[index]--;
                total--;
                takeRotation = index + 1;
                return index;
            }
        }
        return -1;
    }

    public void set(int item, int amount){
        total += amount - items[item];
        items[item] = amount;
    }

    public void add(int item, int amount){
        items[item] += amount;
        total += amount;
    }

    public void add(Stacks stacks){
        for(int i = 0; i < stacks.size(); i++) add(stacks.id(i), stacks.amount(i));
    }

    public void add(ItemModule other){
        for(int i = 0; i < other.items.length; i++) add(i, other.items[i]);
    }

    /** Removes up to {@code amount}: never below zero. */
    public void remove(int item, int amount){
        amount = Math.min(amount, items[item]);
        items[item] -= amount;
        total -= amount;
    }

    public void remove(Stacks stacks){
        for(int i = 0; i < stacks.size(); i++) remove(stacks.id(i), stacks.amount(i));
    }

    public void clear(){
        java.util.Arrays.fill(items, 0);
        total = 0;
    }

    /** Every non-zero count, in id order. */
    public void each(Each action){
        for(int i = 0; i < items.length; i++){
            if(items[i] != 0) action.accept(i, items[i]);
        }
    }

    /** {@code sum}: of {@code calc} over every positive count. */
    public float sum(Sum calc){
        float sum = 0f;
        for(int i = 0; i < items.length; i++){
            if(items[i] > 0) sum += calc.get(i, items[i]);
        }
        return sum;
    }

    public void hash(Hasher hasher){
        hasher.ints(items).i(total).i(takeRotation);
    }

    @FunctionalInterface
    public interface Each{
        void accept(int item, int amount);
    }

    @FunctionalInterface
    public interface Sum{
        float get(int item, int amount);
    }
}
