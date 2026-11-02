package com.xpdustry.shard.sim.building.consume;

/** Vanilla {@code ConsumeLiquidBase}: a liquid used at {@link #amount} per tick while the building works. */
public abstract class ConsumeLiquidBase extends Consume{
    /** Amount used per tick. */
    public final float amount;

    protected ConsumeLiquidBase(Flags flags, float amount){
        super(flags);
        this.amount = amount;
    }

    /** Whether this consumer uses {@code liquid} ({@code consumes(Liquid)}). */
    public abstract boolean consumes(int liquid);
}
