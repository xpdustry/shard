package com.xpdustry.shard.sim.building;

import com.xpdustry.shard.sim.world.*;

/**
 * What vanilla tells clients about a building with a Call, as domain events (WAVES.md "Domain events, not Calls"):
 * published where vanilla sends the Call, after its local effects, so the sync layer can send them in vanilla's order.
 * Positions are origin tiles, {@code y * width + x}. Team changes are {@link com.xpdustry.shard.sim.tile.TileEvent.TeamChanged}.
 */
public sealed interface BuildingEvent extends WorldEvent{
    long tick();

    int pos();

    /**
     * {@code Call.tileConfig(player, build, value)}: {@code value} was applied, from {@code cause} ({@link Cause#SERVER}
     * for {@code configureAny}). Vanilla fires {@code ConfigEvent} right after. The value is kept as
     * {@link ConfigValue#of} makes it: a later change to the array it was given, or to a building it named, doesn't
     * reach the event.
     *
     * @param forwarded the config is a client's own {@code tileConfig}, which its client already ran: the input that
     *     applied it sends vanilla's forward to the other players ({@code forward = true}), so this event is not a Call
     *     of its own. Every other config, a player's cause or not, is the server's {@code Call.tileConfig}.
     */
    record Configured(long tick, int pos, int block, Object value, Cause cause, boolean forwarded) implements BuildingEvent{
        public Configured{
            value = ConfigValue.of(value);
        }
    }

    /**
     * {@code Call.buildDestroyed(build)}: the building was destroyed and its tile is air. {@code TileEvent.Changed}s for
     * its tiles come before it, as vanilla's local {@code killed()} runs before the Call is sent.
     */
    record Destroyed(long tick, int pos, int block, int team, Cause cause) implements BuildingEvent{
    }

    /**
     * {@code healthChanged()}: {@code netServer.buildHealthUpdate(build)}, which vanilla batches into
     * {@code Call.buildHealthUpdate}. One per call, so a building can have several in a tick; the last is current.
     */
    record HealthChanged(long tick, int pos, float health) implements BuildingEvent{
    }
}
