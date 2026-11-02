package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.server.intent.action.*;
import com.xpdustry.shard.server.sync.SnapshotRequest;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.Packets;

/**
 * The remote calls on one building (InputHandler.java:698-751, NetServer.java:728): configure it, rotate it, tap it,
 * ask for its state.
 */
public final class BlockIntents{
    private BlockIntents(){
    }

    /**
     * {@code Units.canInteract(player, build)} (Units.java:134): the player's team's, a derelict one, or the editor's.
     */
    static boolean canInteract(Player player, Building build){
        return build.team == player.team || build.team == com.xpdustry.shard.sim.rules.VanillaIds.DERELICT || build.rules().editor;
    }

    /**
     * {@code tileConfig(player, build, value)}. The client already applied it ({@code called = Loc.both}), so a refusal
     * sends it the building's own config back, as vanilla does with a {@code TileConfigCallPacket}; an admitted config
     * is forwarded to every other player, once: this forward is its only Call, as the building's
     * {@code Configured} event is marked {@link BuildingEvent.Configured#forwarded}. {@code value} is the
     * {@code readObjectSafe} bytes, decoded at admission, where a building value reads as the building.
     */
    public record TileConfig(Policy policy, int build, byte[] value) implements Guarded{
        @Override
        public Verdict admit(World world, IntentEnvelope envelope){
            Acting acting = Acting.of(world, envelope, policy);
            Verdict refused = Acting.refused(acting);
            if(refused != null) return refused;
            Building building = acting.building(build);
            if(building == null) return Verdict.rejected("building is null");
            Object config = acting.config(value);
            Player player = acting.player;
            Verdict denied = canInteract(player, building) ? acting.denied(acting.action(ActionType.CONFIGURE)
                .tile(building.tileX(), building.tileY()).config(config).build()) : Verdict.rejected("can't interact");
            if(denied == null){
                // Shard's own bounds (a processor's code), checked before any handler runs
                String unbounded = building.admitConfig(config);
                if(unbounded != null) denied = Verdict.rejected(unbounded);
            }
            if(denied != null){
                // undo the config on the client
                acting.reply(new Packets.TileConfig(player.id(), building.pos(), WireValues.toWire(building.config(), world.content())));
                return denied;
            }
            // updateLastAccess(player): lastAccessed is display state, not kept
            world.buildings().configureForwarded(building, player.dead() ? null : player.unit(), config, new Cause.Player(envelope.session()));
            acting.forward(new Packets.TileConfig(player.id(), building.pos(), WireValues.toWire(config, world.content())));
            return Verdict.ADMITTED;
        }
    }

    /**
     * {@code rotateBlock(player, build, direction)}: a quarter turn, counter-clockwise when {@code direction}. The
     * client waits for the server ({@code called = Loc.server}), so a refusal sends nothing back; an admitted turn,
     * and a request naming no building, go to every player.
     */
    public record RotateBlock(Policy policy, int build, boolean direction) implements Guarded{
        @Override
        public Verdict admit(World world, IntentEnvelope envelope){
            Acting acting = Acting.of(world, envelope, policy);
            Verdict refused = Acting.refused(acting);
            if(refused != null) return refused;
            Player player = acting.player;
            Building building = acting.building(build);
            if(building != null){
                int rotation = Mathf.mod(building.rotation + (direction ? 1 : -1), 4);
                Verdict denied = canInteract(player, building) ? acting.denied(acting.action(ActionType.ROTATE)
                    .tile(building.tileX(), building.tileY()).rotation(rotation).build()) : Verdict.rejected("can't interact");
                if(denied != null) return denied;
                world.buildings().rotate(building, rotation, new Cause.Player(envelope.session()));
            }
            // a missing building returns before the forward vanilla sends anyway
            acting.forward(new Packets.RotateBlock(player.id(), building == null ? Codecs.NONE : building.pos(), direction));
            return Verdict.ADMITTED;
        }
    }

    /**
     * {@code tileTap(player, tile)}: only plugins hear of it ({@code TapEvent}); nothing is sent. Published as
     * {@link Tapped}.
     */
    public record TileTap(int tile) implements Guarded{
        @Override
        public Verdict admit(World world, IntentEnvelope envelope){
            Acting acting = Acting.of(world, envelope, Policy.VANILLA);
            Verdict refused = Acting.refused(acting);
            if(refused != null) return refused;
            if(!acting.tileExists(tile)) return Verdict.rejected("no tile");
            world.publish(new Tapped(envelope.session(), acting.player.id(), tile));
            return Verdict.ADMITTED;
        }
    }

    /** A player tapped a tile ({@code TapEvent}), packed. */
    public record Tapped(long session, int player, int tile) implements IntentEvent{
    }

    /**
     * {@code requestBlockSnapshot(player, pos)}: the state of one of the player's team's buildings, as a
     * {@code blockSnapshot} of its {@code writeSync}. The sync layer answers it (WP25) from {@link SnapshotWanted}.
     */
    public record RequestBlockSnapshot(int pos) implements Guarded{
        @Override
        public Verdict admit(World world, IntentEnvelope envelope){
            Acting acting = Acting.of(world, envelope, Policy.VANILLA);
            Verdict refused = Acting.refused(acting);
            if(refused != null) return refused;
            Building building = acting.building(pos);
            if(building == null || building.team != acting.player.team) return Verdict.rejected("not the team's building");
            world.publish(new SnapshotWanted(envelope.session(), building.pos()));
            return Verdict.ADMITTED;
        }
    }

    /** A player asked for a building's {@code blockSnapshot}: the world's sync sends it ({@link SnapshotRequest}). */
    public record SnapshotWanted(long session, int pos) implements IntentEvent, SnapshotRequest{
    }
}
