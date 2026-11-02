package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.net.*;
import com.xpdustry.shard.net.Packet;
import com.xpdustry.shard.server.admin.*;
import com.xpdustry.shard.server.config.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.server.sync.*;
import com.xpdustry.shard.server.world.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import org.slf4j.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * The client's gameplay packets as intents (WP26, ARCHITECTURE.md §4): {@link #received} decodes each on the
 * connection's thread into an immutable intent, bounded by the codecs' limits and the transport's 32 KiB, and offers
 * it to the session's {@link Mailbox}. It reads no world state and runs no filter: everything that needs the world
 * happens when the host pumps the mailbox into the inbox and the world admits the intent in {@code COMMANDS}. Only
 * {@code clientSnapshot}s coalesce; a reliable intent that meets a full mailbox kicks the session.
 *
 * <p>After each tick, the host hands the tick's {@link IntentEvent}s to {@link #deliver} on the world thread, which
 * carries out what only the server can: kicks with a cooldown, admin bans and traces. The Calls are {@code Outbound}:
 * the world's sync sends them with the tick's other Calls.
 *
 * <p>Calls the decoder doesn't take: the connect flow's ({@code connectConfirm}, chat, pings, {@code requestWorld} and
 * {@code requestAssets}, which vanilla handles only before a connection confirms).
 */
public final class Intents{
    private static final Logger LOG = LoggerFactory.getLogger(Intents.class);
    private static final Decoder[] DECODERS = decoders();

    private final Sessions sessions;
    private final ConfigService config;
    private volatile AdminService admin;
    /** Packets that failed to decode, and ids no decoder takes. */
    public final LongAdder malformed = new LongAdder(), ignored = new LongAdder();

    public Intents(Sessions sessions, ConfigService config){
        this.sessions = sessions;
        this.config = config;
    }

    /** The admin model kicks and bans go to; until it is set, kicks have no cooldown and admin requests are logged. */
    public void admin(AdminService admin){
        this.admin = admin;
    }

    // ---- the connection's thread

    /** One world's decoding context, for one config generation. */
    private record Decoding(ManagedWorld managed, long generation, Context context){
    }

    /** What a decoder needs: the world's content and map size, and the policy its config gives. */
    record Context(WireContext wire, ContentEpoch content, Policy policy){
        static Context of(WorldConfig world, Policy policy){
            return new Context(WorldWireContext.of(world), world.content(), policy);
        }
    }

    @FunctionalInterface
    interface Decoder{
        /** The intent of a packet's body, or null if vanilla would do nothing with it. Throws {@link WireException}. */
        Intent decode(Reads read, byte[] payload, Context context);
    }

    /**
     * A gameplay packet from {@code session}: decoded and offered to its mailbox, or dropped if the session isn't in a
     * world, the packet is malformed, or no decoder takes it. A reliable intent its mailbox refuses kicks the session.
     */
    public void received(Session session, Packet packet){
        Decoder decoder = packet.id() < DECODERS.length ? DECODERS[packet.id()] : null;
        if(decoder == null){
            ignored.increment();
            return;
        }
        if(!(session.world() instanceof HostedWorld world)) return;
        Context context = context(world);
        if(context == null) return;
        Intent intent;
        try{
            intent = decode(packet.id(), packet.payload(), context);
        }catch(WireException e){
            malformed.increment();
            return;
        }
        if(intent == null) return;
        Session.Offer offer = packet.id() == Packets.ClientSnapshot.ID ? session.offerSnapshot(intent, packet.arrivalNanos())
            : session.offer(intent, packet.arrivalNanos());
        if(offer == Session.Offer.REFUSED){
            session.conn().closeAfter(Calls.kick("[scarlet]You sent more commands than the server can queue."), "mailbox full");
            LOG.info("{} kicked: its mailbox is full", session);
        }
    }

    /**
     * The intent of packet {@code id}'s {@code payload}, or null if no decoder takes it or vanilla would do nothing
     * with it.
     *
     * @throws WireException for a malformed or over-limit body, before anything past the payload's size is allocated
     */
    static Intent decode(int id, byte[] payload, Context context){
        Decoder decoder = id >= 0 && id < DECODERS.length ? DECODERS[id] : null;
        if(decoder == null) return null;
        // WireException.decoding's rule, inline: its name and lambda were a string and a capture per packet
        try{
            return decoder.decode(new Reads(payload), payload, context);
        }catch(UncheckedIOException e){
            throw new WireException("packet " + id + ": " + (e.getCause() instanceof EOFException ? "ends early" : e.getCause().getMessage()), e);
        }catch(WireException e){
            throw new WireException("packet " + id + ": " + e.getMessage(), e);
        }
    }

    /** The packet ids a decoder takes, ascending. */
    static List<Integer> decoded(){
        var out = new ArrayList<Integer>();
        for(int id = 0; id < DECODERS.length; id++) if(DECODERS[id] != null) out.add(id);
        return out;
    }

    private Context context(HostedWorld world){
        ManagedWorld managed = world.managed();
        if(managed == null) return null;
        long generation = config.generation();
        // kept on the world, replaced when the world or the config changes: a map lookup per packet was a tenth of udp-recv
        Decoding current = world.decoding() instanceof Decoding kept ? kept : null;
        if(current == null || current.managed() != managed || current.generation() != generation){
            current = new Decoding(managed, generation, Context.of(managed.config(), Policy.of(config.current().world(world.id().name()))));
            world.decoding(current);
        }
        return current.context();
    }

    /** {@code readObjectSafe} at the reader, as the bytes it took: decoded again at admission, against the world. */
    private static byte[] objectBytes(Reads read, byte[] payload, WireContext context){
        int start = read.position();
        WireObjects.readObjectSafe(read, context);
        return Arrays.copyOfRange(payload, start, read.position());
    }

    private static Decoder[] decoders(){
        var out = new Decoder[256];
        out[Packets.ClientSnapshot.ID] = (read, payload, c) -> snapshot(Packets.ClientSnapshot.read(read, false, c.wire()), c);
        out[Packets.TileConfig.ID] = (read, payload, c) -> {
            int build = read.i();
            return new BlockIntents.TileConfig(c.policy(), build, objectBytes(read, payload, c.wire()));
        };
        out[Packets.RotateBlock.ID] = (read, payload, c) -> {
            var p = Packets.RotateBlock.read(read, false, c.wire());
            return new BlockIntents.RotateBlock(c.policy(), p.build(), p.direction());
        };
        out[Packets.TileTap.ID] = (read, payload, c) -> new BlockIntents.TileTap(Packets.TileTap.read(read, false, c.wire()).tile());
        out[Packets.RequestBlockSnapshot.ID] = (read, payload, c) -> new BlockIntents.RequestBlockSnapshot(Packets.RequestBlockSnapshot.read(read, false, c.wire()).pos());
        out[Packets.DeletePlans.ID] = (read, payload, c) -> new PlayerIntents.DeletePlans(c.policy(), Packets.DeletePlans.read(read, false, c.wire()).positions());
        out[Packets.ClientPlanSnapshot.ID] = (read, payload, c) -> {
            int groupId = read.i();
            int start = read.position();
            Codecs.readClientPlans(read, c.wire());
            return new PlayerIntents.ClientPlanSnapshot(groupId, Arrays.copyOfRange(payload, start, read.position()));
        };
        out[Packets.UnitControl.ID] = (read, payload, c) -> new UnitIntents.UnitControl(c.policy(), Packets.UnitControl.read(read, false, c.wire()).unit());
        out[Packets.UnitClear.ID] = (read, payload, c) -> new UnitIntents.UnitClear(c.policy());
        out[Packets.CommandUnits.ID] = (read, payload, c) -> {
            var p = Packets.CommandUnits.read(read, false, c.wire());
            return new UnitIntents.CommandUnits(c.policy(), p.unitIds(), p.buildTarget(), p.unitTarget(), p.posTarget().x(), p.posTarget().y(),
                p.queueCommand(), p.finalBatch());
        };
        out[Packets.SetUnitCommand.ID] = (read, payload, c) -> {
            var p = Packets.SetUnitCommand.read(read, false, c.wire());
            // readCommand: 255 is null, and the handler returns at once
            return p.command() == Codecs.NO_COMMAND ? null : new UnitIntents.SetUnitCommand(c.policy(), p.unitIds(), p.command());
        };
        out[Packets.SetUnitStance.ID] = (read, payload, c) -> {
            var p = Packets.SetUnitStance.read(read, false, c.wire());
            return p.stance() == Codecs.NO_COMMAND ? null : new UnitIntents.SetUnitStance(c.policy(), p.unitIds(), p.stance(), p.enable());
        };
        out[Packets.CommandBuilding.ID] = (read, payload, c) -> {
            var p = Packets.CommandBuilding.read(read, false, c.wire());
            return new UnitIntents.CommandBuilding(c.policy(), p.buildings(), p.target().x(), p.target().y());
        };
        out[Packets.BuildingControlSelect.ID] = (read, payload, c) -> new UnitIntents.BuildingControlSelect(c.policy(), Packets.BuildingControlSelect.read(read, false, c.wire()).build());
        out[Packets.RequestItem.ID] = (read, payload, c) -> {
            var p = Packets.RequestItem.read(read, false, c.wire());
            return new ItemIntents.RequestItem(c.policy(), p.build(), p.item(), p.amount());
        };
        out[Packets.TransferInventory.ID] = (read, payload, c) -> new ItemIntents.TransferInventory(c.policy(), Packets.TransferInventory.read(read, false, c.wire()).build());
        out[Packets.DropItem.ID] = (read, payload, c) -> new ItemIntents.DropItem(Packets.DropItem.read(read, false, c.wire()).angle());
        out[Packets.PingLocation.ID] = (read, payload, c) -> {
            var p = Packets.PingLocation.read(read, false, c.wire());
            return new PlayerIntents.PingLocation(c.policy(), p.x(), p.y(), p.text());
        };
        out[Packets.SetPlayerTeamEditor.ID] = (read, payload, c) -> new PlayerIntents.SetPlayerTeamEditor(Packets.SetPlayerTeamEditor.read(read, false, c.wire()).team());
        out[Packets.AdminRequest.ID] = (read, payload, c) -> {
            // the entity is checked at admission; on this thread any id reads, as an unknown one does for vanilla
            int other = read.i();
            var action = com.xpdustry.shard.wire.v160_7.AdminAction.of(read.b());
            return new PlayerIntents.AdminRequest(other, action, objectBytes(read, payload, c.wire()));
        };
        out[Packets.RequestUnitPayload.ID] = (read, payload, c) -> new PlayerIntents.RequestUnitPayload(Packets.RequestUnitPayload.read(read, false, c.wire()).target());
        out[Packets.RequestBuildPayload.ID] = (read, payload, c) -> new PlayerIntents.RequestBuildPayload(c.policy(), Packets.RequestBuildPayload.read(read, false, c.wire()).build());
        out[Packets.RequestDropPayload.ID] = (read, payload, c) -> {
            var p = Packets.RequestDropPayload.read(read, false, c.wire());
            return new PlayerIntents.RequestDropPayload(c.policy(), p.x(), p.y());
        };
        out[Packets.MenuChoose.ID] = (read, payload, c) -> {
            var p = Packets.MenuChoose.read(read, false, c.wire());
            return new PlayerIntents.MenuChoose(p.menuId(), p.option());
        };
        out[Packets.TextInputResult.ID] = (read, payload, c) -> {
            var p = Packets.TextInputResult.read(read, false, c.wire());
            return new PlayerIntents.TextInputResult(p.textInputId(), p.text());
        };
        out[Packets.MenuBuilderChoose.ID] = (read, payload, c) -> {
            int menuId = read.i();
            int start = read.position();
            Codecs.readMenuResult(read);
            return new PlayerIntents.MenuBuilderChoose(menuId, Arrays.copyOfRange(payload, start, read.position()));
        };
        out[Packets.ServerPacketReliable.ID] = (read, payload, c) -> {
            var p = Packets.ServerPacketReliable.read(read, false, c.wire());
            return text(p.type(), p.contents(), true);
        };
        out[Packets.ServerPacketUnreliable.ID] = (read, payload, c) -> {
            var p = Packets.ServerPacketUnreliable.read(read, false, c.wire());
            return text(p.type(), p.contents(), false);
        };
        out[Packets.ServerBinaryPacketReliable.ID] = (read, payload, c) -> {
            var p = Packets.ServerBinaryPacketReliable.read(read, false, c.wire());
            // a null type has no handler, as text() drops it (W5.5's fuzzer: Ui.custom threw on it)
            return p.type() == null ? null : new PlayerIntents.Custom(PlayerIntents.Custom.Kind.BINARY, p.type(), p.contents(), true);
        };
        out[Packets.ServerBinaryPacketUnreliable.ID] = (read, payload, c) -> {
            var p = Packets.ServerBinaryPacketUnreliable.read(read, false, c.wire());
            // a null type has no handler, as text() drops it (W5.5's fuzzer: Ui.custom threw on it)
            return p.type() == null ? null : new PlayerIntents.Custom(PlayerIntents.Custom.Kind.BINARY, p.type(), p.contents(), false);
        };
        out[Packets.ClientLogicDataReliable.ID] = (read, payload, c) -> logic(read, payload, c, true);
        out[Packets.ClientLogicDataUnreliable.ID] = (read, payload, c) -> logic(read, payload, c, false);
        out[Packets.RequestDebugStatus.ID] = (read, payload, c) -> new PlayerIntents.RequestDebugStatus();
        return out;
    }

    private static Intent text(String type, String contents, boolean reliable){
        if(type == null) return null;
        byte[] bytes = contents == null ? new byte[0] : contents.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new PlayerIntents.Custom(PlayerIntents.Custom.Kind.TEXT, type, bytes, reliable);
    }

    private static Intent logic(Reads read, byte[] payload, Context c, boolean reliable){
        String channel = Codecs.readString(read);
        if(channel == null) return null;
        return new PlayerIntents.Custom(PlayerIntents.Custom.Kind.LOGIC, channel, objectBytes(read, payload, c.wire()), reliable);
    }

    /** {@code clientSnapshot} as an intent: plans as the sim holds them, with configs a queue may keep. */
    static Snapshot snapshot(Packets.ClientSnapshot p, Context context){
        Policy policy = context.policy();
        List<BuildPlan> plans = new ArrayList<>(p.plans() == null ? 0 : p.plans().size());
        if(p.plans() != null){
            for(Codecs.Plan wire : p.plans()){
                BuildPlan plan = WireValues.plan(wire, context.content());
                plan.config = Configs.storable(plan.config);
                plans.add(plan);
            }
        }
        return new Snapshot(policy, p.snapshotID(), p.unitID(), p.dead(), p.x(), p.y(), p.pointerX(), p.pointerY(), p.rotation(), p.baseRotation(),
            p.xVelocity(), p.yVelocity(), p.mining(), p.boosting(), p.shooting(), p.chatting(), p.building(), p.selectedBlock(), p.selectedRotation(),
            plans, p.viewX(), p.viewY(), p.viewWidth(), p.viewHeight());
    }

    // ---- the world thread, after a tick

    /**
     * Carries out what an intent published in {@code world}'s last tick that only the server's own path can: a kick
     * with its cooldown, an admin request. Runs on the world's thread, after the tick committed and its Calls went out
     * ({@link IntentCall.To} and the rest are {@code Outbound}: the world's sync sends them first), so a kick follows
     * the Calls published before it, as in vanilla; it never blocks.
     */
    public void deliver(World world, IntentEvent event){
        switch(event){
            case IntentCall.Kick kick -> kick(kick.session(), kick.reason(), Duration.ofMillis(kick.banMillis()));
            case PlayerIntents.AdminRequested request -> admin(world, request);
            default -> {
                // Calls go through the Outbox; taps, menus, custom packets, previews and block snapshot requests are
                // for plugins and the sync layer
            }
        }
    }

    private void kick(long id, String reason, Duration ban){
        Session session = sessions.get(id);
        if(session == null) return;
        session.conn().closeAfter(Calls.kick(reason), "kicked: " + reason);
        AdminService admin = this.admin;
        if(admin != null && !ban.isZero()) admin.kicked(session.identity().uuid(), session.conn().remote().getAddress().getHostAddress(), ban);
        LOG.info("{} kicked: {}", session, reason);
    }

    /** {@code adminRequest}'s server side (NetServer.java:920-951), with vanilla's log lines. */
    private void admin(World world, PlayerIntents.AdminRequested request){
        Session by = sessions.get(request.session()), target = sessions.get(request.target());
        if(by == null || target == null) return;
        AdminService admin = this.admin;
        String actor = by.identity().plainName(), actorId = "player:" + by.identity().uuid();
        String targetAddress = target.conn().remote().getAddress().getHostAddress();
        switch(request.action()){
            case kick -> {
                target.conn().closeAfter(Calls.kick(KickReason.KICK), "kicked by " + actor);
                if(admin != null) admin.kicked(target.identity().uuid(), targetAddress, KickReason.KICK, actorId);
                LOG.info("{} [{}] has kicked {} [{}].", actor, by.identity().uuid(), target.identity().plainName(), target.identity().uuid());
            }
            case ban -> {
                if(admin != null){
                    admin.banPlayerAt(target.identity().uuid(), targetAddress, "banned in game by " + actor, null, actorId).exceptionally(failure -> {
                        LOG.warn("banning {} failed: {}", target, failure.toString());
                        return null;
                    });
                    // other.kick(banned): the ban's kick has its cooldown and counts, as any kick
                    admin.kicked(target.identity().uuid(), targetAddress, KickReason.BANNED, actorId);
                }
                target.conn().closeAfter(Calls.kick(KickReason.BANNED), "banned by " + actor);
                LOG.info("{} [{}] has banned {} [{}].", actor, by.identity().uuid(), target.identity().plainName(), target.identity().uuid());
            }
            case trace -> {
                if(admin == null) return;
                Player player = Roster.of(world).player(request.target());
                int id = player == null ? -1 : player.id();
                admin.trace(target.identity().uuid(), new AdminService.Live(targetAddress,
                    target.identity().locale(), target.identity().version() == Bootstrap.CUSTOM_BUILD, target.identity().mobile()))
                    .thenAccept(info -> by.conn().sendTcp(Calls.frame(new Packets.TraceInfo(id, new Codecs.TraceInfo(info.ip(), info.uuid(),
                        info.locale(), info.modded(), info.mobile(), info.timesJoined(), info.timesKicked(), info.ips(), info.names())))))
                    .exceptionally(failure -> {
                        LOG.warn("tracing {} failed: {}", target, failure.toString());
                        return null;
                    });
            }
            // the world skipped it at admission
            case wave -> LOG.info("{} [{}] has skipped the wave.", actor, by.identity().uuid());
            case switchTeam -> {
            }
        }
    }
}
