package com.xpdustry.shard.sim.logic;

import com.xpdustry.shard.logic.*;
import com.xpdustry.shard.sim.building.*;

/** A building that runs a {@link Processor}: vanilla's {@code LogicBuild}, as {@code control} and links see it. */
public interface ProcessorBuilding{
    Processor processor();

    /** {@code LogicBuild.validLink(other)}: which buildings this processor may link to and control. */
    boolean validLink(Building other);
}
