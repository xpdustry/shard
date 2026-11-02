package com.xpdustry.shard.compat.v160.sync;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.world.*;

/**
 * The {@code Call}s vanilla sends for one family of {@link WorldEvent}s. Register an implementation with
 * {@code @Register}; {@link CallMappings} finds it by the event's class or the nearest interface it implements, so a
 * family's sealed interface maps every record in it. A family that publishes events adds its mapping as a new class,
 * never by editing a dispatcher.
 *
 * <p>Events arrive in the order the world published them, which their families keep to vanilla's Call order, and
 * after the tick that published them, before that tick's snapshots: vanilla's Calls go out during
 * {@code Logic.update}, its snapshots in {@code NetServer.update} right after. Events with no Call (observations such
 * as {@code TileEvent}s, whose Calls belong to the operation that caused them) have no mapping.
 *
 * @param <E> the event family
 */
@Registry
public interface CallMapping<E extends WorldEvent>{
    /** The event class or family interface this maps. */
    Class<E> event();

    /** Sends {@code event}'s Calls, if it has any, through {@code sink}. */
    void map(E event, CallContext context, CallSink sink);
}
