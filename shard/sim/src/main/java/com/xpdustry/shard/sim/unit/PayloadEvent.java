package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.sim.world.*;

/**
 * The payload remote calls (InputHandler.java:590-659), one record per Call, published by {@link Payloads} after the
 * local handler ran, as a server-called {@code Call} is sent. A server encodes each as the packet of the same name, to
 * everyone.
 */
public sealed interface PayloadEvent extends WorldEvent{
    /** The carrier's entity id. */
    int unit();

    /** {@code Call.pickedUnitPayload(unit, target)}. */
    record PickedUnit(int unit, int target) implements PayloadEvent{
    }

    /** {@code Call.pickedBuildPayload(unit, build, onGround)}; {@code pos} is the building's, as it stood. */
    record PickedBuild(int unit, int pos, boolean onGround) implements PayloadEvent{
    }

    /** {@code Call.payloadDropped(unit, x, y)}. */
    record Dropped(int unit, float x, float y) implements PayloadEvent{
    }

    /**
     * {@code Call.unitBuildingControlSelect(unit, build)}: the unit entered (or was refused by) the building at
     * {@code pos}; {@code blockUnit} is the turret's position when the unit is a turret's block unit, else -1.
     */
    record ControlSelect(int unit, int blockUnit, int pos) implements PayloadEvent{
    }

    /** {@code Call.unitEnteredPayload(unit, build)}: the unit walked into the payload block at {@code pos} (CommandAI's {@code enterPayload}). */
    record EnteredPayload(int unit, int pos) implements PayloadEvent{
    }
}
