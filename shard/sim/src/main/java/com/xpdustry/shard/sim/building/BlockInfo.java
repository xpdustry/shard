package com.xpdustry.shard.sim.building;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.consume.*;

import java.util.*;

/**
 * What the building base reads from a block's definition on hot paths, read once per block and world into plain
 * fields: its {@link Consumers}, item and liquid filters, flags and the {@code Block} fields the base's methods use.
 * {@link Buildings#info(BlockDef)} makes them; {@link Building#info()} is the building's. Hand-built definitions (test
 * content) have no baked fields and get vanilla's {@code Block} defaults.
 */
public final class BlockInfo{
    public final BlockDef block;
    public final Consumers consumers;
    private final boolean[] itemFilter, liquidFilter;
    private final BlockFlag[] flags;
    private final int flagBits;
    /** {@code itemCapacity}, {@code liquidCapacity}, {@code liquidPressure}. */
    public final int itemCapacity;
    public final float liquidCapacity, liquidPressure;
    /** {@code timers}: the size of the building's {@code Interval}. */
    public final int timers;
    public final boolean acceptsItems, instantTransfer, unloadable, canOverdrive, suppressable, targetable, attacks;
    public final boolean consumesPower, outputsPower, logicConfigurable, alwaysAllowDeposit, allowResupply;
    /** {@code connectedPower}, {@code conductivePower} and {@code insulated}, which power graphs walk by (WP37). */
    public final boolean connectedPower, conductivePower, insulated;
    public final float armor, priority, baseExplosiveness, flammabilityScale, explosivenessScale, range;
    /** Whether the kind's building draws a field from the generator when constructed ({@link Building#drawFields}). */
    public final boolean drawsField;

    private BlockInfo(BlockDef block, ContentEpoch content){
        this.block = block;
        boolean baked = block.baked();
        consumers = Consumers.read(block, content);
        itemFilter = baked ? booleans(block.fields().list("itemFilter"), content.items()) : new boolean[content.items()];
        liquidFilter = baked ? booleans(block.fields().list("liquidFilter"), content.liquids()) : new boolean[content.liquids()];
        var found = new ArrayList<BlockFlag>();
        int bits = 0;
        if(baked){
            for(String name : block.flags()){
                BlockFlag flag = BlockFlag.named(name);
                if(flag == null) throw new IllegalStateException("block " + block.name() + " has unknown flag " + name);
                bits |= 1 << flag.ordinal();
                found.add(flag);
            }
        }
        flags = found.toArray(BlockFlag[]::new);
        flagBits = bits;
        itemCapacity = baked ? block.itemCapacity() : 10;
        liquidCapacity = baked ? block.liquidCapacity() : 10f;
        liquidPressure = f(block, "liquidPressure", 1f);
        timers = baked ? block.fields().i("timers") : 1;
        acceptsItems = b(block, "acceptsItems", false);
        instantTransfer = b(block, "instantTransfer", false);
        unloadable = b(block, "unloadable", true);
        canOverdrive = b(block, "canOverdrive", true);
        suppressable = b(block, "suppressable", false);
        targetable = b(block, "targetable", true);
        attacks = b(block, "attacks", false);
        consumesPower = b(block, "consumesPower", false);
        outputsPower = b(block, "outputsPower", false);
        connectedPower = b(block, "connectedPower", true);
        conductivePower = b(block, "conductivePower", false);
        insulated = b(block, "insulated", false);
        logicConfigurable = b(block, "logicConfigurable", false);
        alwaysAllowDeposit = b(block, "alwaysAllowDeposit", false);
        allowResupply = b(block, "allowResupply", false);
        armor = f(block, "armor", 0f);
        priority = f(block, "priority", 0f);
        baseExplosiveness = f(block, "baseExplosiveness", 0f);
        flammabilityScale = f(block, "flammabilityScale", 1f);
        explosivenessScale = f(block, "explosivenessScale", 1f);
        range = f(block, "range", 0f);
        drawsField = baked && DRAWING_FIELDS.stream().anyMatch(block::isA);
    }

    private static final List<KindKey> DRAWING_FIELDS = List.of(KindKey.of("mend-projector"), KindKey.of("overdrive-projector"),
        KindKey.of("shockwave-tower"), KindKey.of("landing-pad"), KindKey.of("auto-door"), KindKey.of("repair-tower"));

    static BlockInfo of(BlockDef block, ContentEpoch content){
        return new BlockInfo(block, content);
    }

    /** {@code consumesItem(item)}: whether any consumer takes it, the default {@code acceptItem}. */
    public boolean consumesItem(int item){
        return itemFilter[item];
    }

    /** {@code consumesLiquid(liquid)}. */
    public boolean consumesLiquid(int liquid){
        return liquidFilter[liquid];
    }

    /** The block's flags, in definition order ({@code flags.array}: the indexer files a building once per entry). */
    public BlockFlag[] flags(){
        return flags.clone();
    }

    /** How many flags the block has. */
    public int flagCount(){
        return flags.length;
    }

    /** Flag {@code i} of {@link #flags()}. */
    public BlockFlag flag(int i){
        return flags[i];
    }

    public boolean hasFlag(BlockFlag flag){
        return (flagBits & 1 << flag.ordinal()) != 0;
    }

    private static boolean[] booleans(List<Object> values, int length){
        var out = new boolean[length];
        for(int i = 0; i < Math.min(length, values.size()); i++) out[i] = (Boolean)values.get(i);
        return out;
    }

    private static boolean b(BlockDef block, String field, boolean fallback){
        return block.baked() && block.fields().has(field) ? block.fields().b(field) : fallback;
    }

    private static float f(BlockDef block, String field, float fallback){
        // some blocks' baked values are whole numbers
        return block.baked() && block.fields().has(field) ? ((Number)block.fields().get(field)).floatValue() : fallback;
    }
}
