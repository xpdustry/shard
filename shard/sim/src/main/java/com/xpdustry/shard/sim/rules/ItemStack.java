package com.xpdustry.shard.sim.rules;

/** An item id and an amount (vanilla {@code ItemStack}, which compares by item and amount). */
public record ItemStack(int item, int amount){
}
