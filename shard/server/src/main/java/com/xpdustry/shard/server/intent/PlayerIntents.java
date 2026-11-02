package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.intent.action.*;
import com.xpdustry.shard.server.sync.PlanPreviews;
import com.xpdustry.shard.sim.build.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.AdminAction;
import com.xpdustry.shard.wire.v160_7.Packets;

import java.util.*;

/**
 * The other remote calls a player sends (InputHandler.java:262-300, 552-659, NetServer.java:640-710, 905-952, the
 * menus): team plans, plan previews, pings, the editor's team, admin requests, payloads, menus, text input, and the
 * custom and logic packets plugins read. What the world doesn't act on is published as an {@link IntentEvent}.
 */
public final class PlayerIntents{
    private PlayerIntents(){
    }

    /**
     * {@code deletePlans(player, positions)}: remove the team's rebuild plans at these tiles. The client removed them
     * already ({@code called = Loc.both}); a refused request isn't forwarded and nothing restores them, as in vanilla.
     * The plans are construction's ({@link RebuildPlans#delete}).
     */
    public record DeletePlans(Policy policy, int[] positions) implements Guarded{
        @Override
        public Verdict admit(World world, IntentEnvelope envelope){
            Acting acting = Acting.of(world, envelope, policy);
            Verdict refused = Acting.refused(acting);
            if(refused != null) return refused;
            Verdict denied = acting.denied(acting.action(ActionType.REMOVE_PLANNED).plans(positions).build());
            if(denied != null) return denied;
            RebuildPlans.delete(world, acting.player.team, positions);
            acting.forward(new Packets.DeletePlans(acting.player.id(), positions));
            return Verdict.ADMITTED;
        }
    }

    /**
     * {@code clientPlanSnapshot(player, groupId, plans)}: the plans a builder is laying out, for its teammates to see.
     * They are only drawn, so they aren't world state: the sync layer assembles a player's batches
     * ({@code handlePreviewPlans}) and resends them every 0.5 s (WP25) from {@link Previewed}. {@code plans} is the
     * {@code ClientPlans} bytes, checked at decode.
     */
    public record ClientPlanSnapshot(int groupId, byte[] plans) implements Guarded{
        @Override
        public Verdict admit(World world, IntentEnvelope envelope){
            Acting acting = Acting.of(world, envelope, Policy.VANILLA);
            Verdict refused = Acting.refused(acting);
            if(refused != null) return refused;
            List<Codecs.ClientPlan> read = WireException.decoding("plan previews",
                () -> Codecs.readClientPlans(new Reads(plans), WorldWireContext.of(world.config())));
            world.publish(new Previewed(envelope.session(), acting.player.id(), acting.player.team, groupId, read == null ? List.of() : read));
            return Verdict.ADMITTED;
        }
    }

    /** A builder's plan previews, one batch of a group: the world's sync assembles and shows them ({@link PlanPreviews}). */
    public record Previewed(long session, int player, int team, int groupId, List<Codecs.ClientPlan> plans) implements IntentEvent, PlanPreviews{
    }

    /** {@code pingLocation(player, x, y, text)}: a marker for the player's team, which the clients draw. */
    public record PingLocation(Policy policy, float x, float y, String text) implements Guarded{
        @Override
        public Verdict admit(World world, IntentEnvelope envelope){
            Acting acting = Acting.of(world, envelope, policy);
            Verdict refused = Acting.refused(acting);
            if(refused != null) return refused;
            Verdict denied = acting.denied(acting.action(ActionType.PING_LOCATION).ping(x, y, text).build());
            if(denied != null) return denied;
            // the server keeps no ping: Vars.player is null on a server
            acting.forward(new Packets.PingLocation(acting.player.id(), x, y, text));
            return Verdict.ADMITTED;
        }
    }

    /** {@code setPlayerTeamEditor(player, team)}: in the editor only, the player switches team. */
    public record SetPlayerTeamEditor(int team) implements Guarded{
        @Override
        public Verdict admit(World world, IntentEnvelope envelope){
            Acting acting = Acting.of(world, envelope, Policy.VANILLA);
            Verdict refused = Acting.refused(acting);
            if(refused != null) return refused;
            if(world.buildings().rules().editor) acting.player.team(team);
            acting.forward(new Packets.SetPlayerTeamEditor(acting.player.id(), team));
            return Verdict.ADMITTED;
        }
    }

    /**
     * {@code adminRequest(player, other, action, params)} (NetServer.java:906): an admin acts on a player who isn't an
     * admin, or on themselves. A team switch and a wave skip are the world's, applied here; kicks, bans and traces are
     * the server's. Every admitted request is published as {@link AdminRequested}, vanilla's
     * {@code AdminRequestEvent}, which the server carries out after the tick.
     *
     * @param params the {@code readObjectSafe} bytes
     */
    public record AdminRequest(int other, AdminAction action, byte[] params) implements Guarded{
        @Override
        public Verdict admit(World world, IntentEnvelope envelope){
            Acting acting = Acting.of(world, envelope, Policy.VANILLA);
            Verdict refused = Acting.refused(acting);
            if(refused != null) return refused;
            Player player = acting.player;
            // ACCESS DENIED
            if(!player.admin) return Verdict.rejected("not an admin");
            Roster roster = Roster.of(world);
            long target = -1;
            Player otherPlayer = null;
            for(var entry : roster.players().entrySet()){
                if(entry.getValue().id() == other && entry.getValue().isAdded()){
                    target = entry.getKey();
                    otherPlayer = entry.getValue();
                }
            }
            if(otherPlayer == null || (otherPlayer.admin && otherPlayer != player)) return Verdict.rejected("no such player, or an admin");
            world.publish(new AdminRequested(envelope.session(), target, action));
            switch(action){
                // no verification is done, so admins can hypothetically spam waves, as vanilla says
                case wave -> Waves.skipWave(world);
                case switchTeam -> {
                    // params instanceof Team: the config reader gives a team as a ConfigValue.Team
                    if(acting.config(params) instanceof ConfigValue.Team team) otherPlayer.team(team.id());
                }
                default -> {
                }
            }
            return Verdict.ADMITTED;
        }
    }

    /** An admin's {@code adminRequest}, admitted: the server kicks, bans or traces after the tick, and logs each. */
    public record AdminRequested(long session, long target, AdminAction action) implements IntentEvent{
    }

    /**
     * {@code requestUnitPayload(player, target)}: a payload unit picks up a grounded AI unit next to it
     * ({@code Call.pickedUnitPayload}, {@link Payloads#requestUnitPayload}). No filter asks about it.
     */
    public record RequestUnitPayload(Codecs.UnitRef target) implements Guarded{
        @Override
        public Verdict admit(World world, IntentEnvelope envelope){
            Acting acting = Acting.of(world, envelope, Policy.VANILLA);
            Verdict refused = Acting.refused(acting);
            if(refused != null) return refused;
            Unit unit = acting.player.unit();
            Unit other = acting.unit(target);
            if(unit == null || unit.payloads == null || other == null) return Verdict.rejected("nothing to pick up");
            if(!other.isAI() || !other.isGrounded() || !other.within(unit.x, unit.y, unit.hitSize * 2f + other.hitSize * 2f)){
                return Verdict.rejected("can't pick it up");
            }
            if(!Payloads.requestUnitPayload(unit, other)) return Verdict.rejected("can't pick it up");
            return Verdict.ADMITTED;
        }
    }

    /**
     * {@code requestBuildPayload(player, build)}: a payload unit picks up a building within reach, or what it carries,
     * once the filters allowed it ({@code Call.pickedBuildPayload}, {@link Payloads#requestBuildPayload}).
     */
    public record RequestBuildPayload(Policy policy, int build) implements Guarded{
        @Override
        public Verdict admit(World world, IntentEnvelope envelope){
            Acting acting = Acting.of(world, envelope, policy);
            Verdict refused = Acting.refused(acting);
            if(refused != null) return refused;
            Unit unit = acting.player.unit();
            Building building = acting.building(build);
            if(unit == null || unit.payloads == null || building == null) return Verdict.rejected("nothing to pick up");
            if(!Payloads.inReach(unit, building)) return Verdict.rejected("out of reach");
            PlayerAction action = acting.action(ActionType.PICKUP_BLOCK).tile(building.tileX(), building.tileY()).unit(unit).build();
            Verdict denied = acting.denied(action);
            if(denied != null) return denied;
            if(!Payloads.requestBuildPayload(unit, building, new Cause.Player(envelope.session()))) return Verdict.rejected("can't pick it up");
            return Verdict.ADMITTED;
        }
    }

    /**
     * {@code requestDropPayload(player, x, y)}: a payload unit drops its last payload within 4 tiles of itself, once the
     * filters allowed it ({@code Call.payloadDropped}, sent whether or not it drops: {@link Payloads#requestDropPayload}).
     */
    public record RequestDropPayload(Policy policy, float x, float y) implements Guarded{
        @Override
        public Verdict admit(World world, IntentEnvelope envelope){
            Acting acting = Acting.of(world, envelope, policy);
            Verdict refused = Acting.refused(acting);
            if(refused != null) return refused;
            Player player = acting.player;
            // vanilla casts to Payloadc and fails on any other unit
            if(player.dead() || player.unit().payloads == null) return Verdict.rejected("not a payload unit");
            Unit unit = player.unit();
            if(unit.payloads.isEmpty()) return Verdict.rejected("nothing to drop");
            int on = unit.tileOn();
            var action = acting.action(ActionType.DROP_PAYLOAD).payload(unit.payloads.get(unit.payloads.size() - 1));
            if(on != -1) action.tile(world.tiles().x(on), world.tiles().y(on));
            Verdict denied = acting.denied(action.build());
            if(denied != null) return denied;
            Payloads.requestDropPayload(unit, x, y, new Cause.Player(envelope.session()));
            return Verdict.ADMITTED;
        }
    }

    /** {@code menuChoose(player, menuId, option)}: a menu's answer, for whoever showed it. */
    public record MenuChoose(int menuId, int option) implements Guarded{
        @Override
        public Verdict admit(World world, IntentEnvelope envelope){
            return answered(world, envelope, new Answered(envelope.session(), menuId, option, null, null));
        }
    }

    /** {@code textInputResult(player, id, text)}: a text field's answer. */
    public record TextInputResult(int textInputId, String text) implements Guarded{
        @Override
        public Verdict admit(World world, IntentEnvelope envelope){
            return answered(world, envelope, new Answered(envelope.session(), textInputId, -1, text, null));
        }
    }

    /** {@code menuBuilderChoose(player, menuId, results)}: a built menu's answer, as its {@code MenuResult} bytes. */
    public record MenuBuilderChoose(int menuId, byte[] results) implements Guarded{
        @Override
        public Verdict admit(World world, IntentEnvelope envelope){
            return answered(world, envelope, new Answered(envelope.session(), menuId, -1, null, results));
        }
    }

    /** A menu, text input or built menu's answer; only the fields of its kind are set. */
    public record Answered(long session, int id, int option, String text, byte[] results) implements IntentEvent{
    }

    private static Verdict answered(World world, IntentEnvelope envelope, Answered answer){
        Acting acting = Acting.of(world, envelope, Policy.VANILLA);
        Verdict refused = Acting.refused(acting);
        if(refused != null) return refused;
        world.publish(answer);
        return Verdict.ADMITTED;
    }

    /**
     * {@code serverPacketReliable}, {@code serverBinaryPacketReliable}, {@code clientLogicDataReliable} and their
     * unreliable twins: data for plugins' handlers, by type or channel. {@code contents} is the text's UTF-8, the
     * bytes, or the logic value's {@code readObjectSafe} bytes.
     */
    public record Custom(Kind kind, String type, byte[] contents, boolean reliable) implements Guarded{
        public enum Kind{TEXT, BINARY, LOGIC}

        @Override
        public Verdict admit(World world, IntentEnvelope envelope){
            Acting acting = Acting.of(world, envelope, Policy.VANILLA);
            Verdict refused = Acting.refused(acting);
            if(refused != null) return refused;
            world.publish(new CustomReceived(envelope.session(), kind, type, contents, reliable));
            return Verdict.ADMITTED;
        }
    }

    public record CustomReceived(long session, Custom.Kind kind, String type, byte[] contents, boolean reliable) implements IntentEvent{
    }

    /** {@code requestDebugStatus(player)}: the connection's flags and last snapshot id, on both channels. */
    public record RequestDebugStatus() implements Guarded{
        @Override
        public Verdict admit(World world, IntentEnvelope envelope){
            Acting acting = Acting.of(world, envelope, Policy.VANILLA);
            Verdict refused = Acting.refused(acting);
            if(refused != null) return refused;
            // hasDisconnected 1, hasConnected 2, isAdded 4, hasBegunConnecting 8: a playing session has all but the first
            int flags = 2 | 4 | 8;
            acting.reply(new Packets.DebugStatusClient(flags, acting.input.lastSnapshot));
            acting.reply(new Packets.DebugStatusClientUnreliable(flags, acting.input.lastSnapshot));
            return Verdict.ADMITTED;
        }
    }
}
