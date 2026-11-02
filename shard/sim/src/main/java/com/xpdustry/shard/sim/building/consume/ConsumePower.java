package com.xpdustry.shard.sim.building.consume;

import com.xpdustry.shard.sim.building.*;

/**
 * Vanilla {@code ConsumePower}, {@code ConsumePowerCondition} and {@code ConsumePowerDynamic}: power from the
 * building's graph.
 *
 * <p><b>The seam with the power graph.</b> This consumer only reads {@code building.power.status}: the share of the
 * request the graph satisfied (unbuffered), or the battery's fill (buffered). The building's
 * {@code sim.blocks.power.PowerGraph} calls {@link #requestedPower} for each consumer, sets {@code status}, and reads
 * {@link Building#shouldConsumePower}, every tick in the power phase.
 */
public final class ConsumePower extends Consume{
    /** Which vanilla class this is: how {@link #requestedPower} asks. */
    public enum Mode{
        /** {@code ConsumePower}: {@link #usage} while {@code shouldConsume()}, or the buffer's free space. */
        FIXED,
        /** {@code ConsumePowerCondition}: {@link #usage} while {@link Building#consumesPowerNow()}. */
        CONDITION,
        /** {@code ConsumePowerDynamic}: {@link Building#powerUse()}. */
        DYNAMIC
    }

    public final Mode mode;
    /** Power per tick. */
    public final float usage;
    /** The buffer's capacity, in power units. */
    public final float capacity;
    /** A battery: it stores power instead of using it, and the consumer list ignores it. */
    public final boolean buffered;

    public ConsumePower(Flags flags, Mode mode, float usage, float capacity, boolean buffered){
        super(flags);
        this.mode = mode;
        this.usage = usage;
        this.capacity = capacity;
        this.buffered = buffered;
    }

    @Override
    public boolean ignore(){
        return buffered;
    }

    /** The share of its request the graph satisfied, which WP37 sets. */
    @Override
    public float efficiency(Building building){
        return building.power.status;
    }

    /** {@code requestedPower}: what the building asks of its graph this tick. */
    public float requestedPower(Building building){
        return switch(mode){
            case FIXED -> buffered ? (1f - building.power.status) * capacity : usage * (building.shouldConsume() ? 1f : 0f);
            case CONDITION -> building.consumesPowerNow() ? usage : 0f;
            case DYNAMIC -> building.powerUse();
        };
    }
}
