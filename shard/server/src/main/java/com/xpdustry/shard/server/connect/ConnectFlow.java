package com.xpdustry.shard.server.connect;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.net.*;
import com.xpdustry.shard.net.Packet;
import com.xpdustry.shard.server.admin.*;
import com.xpdustry.shard.server.chat.*;
import com.xpdustry.shard.server.config.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.server.sync.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import org.slf4j.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * The connect flow (WP24) on the transport's threads: {@code ConnectPacket} through {@link Admission} to a session
 * routed to a world, which sends the world; {@code connectConfirm}; chat lines, which {@link Chat} takes; pings; the
 * disconnect. It decodes packets into intents and offers them through the session; it never touches a world
 * (ARCHITECTURE.md §4). Gameplay packets ({@code clientSnapshot} and the rest) go to {@link Host#intents()}.
 *
 * <p>Admission is atomic: after {@link Admission}'s checks, a {@link Ledger} takes the session's place (the player
 * limit, strict mode's duplicates) in one step, and gives it back when the session closes or the connect fails. Once
 * the session is open, where a ban's kick finds it, the flow checks that no moderation change came since the admin
 * model decided, and decides again if one did. What must end with the session (its place, its join's record) ends
 * through {@link Session#whenClosed}, so a disconnect at any point of the connect ends it.
 */
public final class ConnectFlow implements Transport.Handler{
    private static final Logger LOG = LoggerFactory.getLogger(ConnectFlow.class);

    private final Host host;
    private final Sessions sessions;
    private final AdminService admin;
    private final Admission admission;
    private final ConfigService config;
    private final Ledger ledger = new Ledger();
    private final Chat chat;
    private volatile Hooks hooks = Hooks.NONE;
    public final LongAdder malformed = new LongAdder();
    private volatile byte[] discovery = new byte[0];
    private volatile boolean quiet;

    public ConnectFlow(Host host, AdminService admin, ConfigService config, Chat chat){
        this.host = host;
        this.sessions = host.sessions();
        this.admin = admin;
        this.config = config;
        this.chat = chat;
        this.admission = new Admission(admin, config::current);
        host.intents().admin(admin);
    }

    /** No per-player log lines (the load test). */
    public void quiet(boolean quiet){
        this.quiet = quiet;
    }

    /** Fixed points of a connect, where tests hold one; each runs on the connect's thread. */
    interface Hooks{
        Hooks NONE = new Hooks(){
        };

        /** The checks passed; nothing is reserved yet. */
        default void checked(Conn conn){
        }

        /** The session is open and holds its place; the moderation index is checked next. */
        default void opened(Session session){
        }

        /** The session is routed to a world; its join is recorded next. */
        default void routed(Session session){
        }
    }

    void hooks(Hooks hooks){
        this.hooks = Objects.requireNonNull(hooks, "hooks");
    }

    /** Admitted sessions not yet closed: what {@code net.player-limit} counts. */
    public int admitted(){
        return ledger.size();
    }

    /**
     * What a client may send: vanilla's spam limits from the config, two connect packets, and the input snapshots
     * vanilla's client sends every ~66 ms. Breaking either spam limit DOS-bans the address, as in vanilla
     * ({@code ArcNetProvider.java:166-169}, {@code NetClient.java:339-341}); the connect-packet limit is Shard's and
     * only closes.
     */
    public static RateLimits limits(Config config){
        int packets = config.get(ConfigKeys.NET_PACKET_SPAM_LIMIT), chat = config.get(ConfigKeys.NET_CHAT_SPAM_LIMIT);
        return new RateLimits(new RateLimits.Limit(packets, packets / 3.0, RateLimits.Action.BLACKLIST), Collections.nCopies(256, null))
            .with(Packets.CONNECT_PACKET, new RateLimits.Limit(2, 0.1, RateLimits.Action.CLOSE))
            .with(Packets.CLIENT_SNAPSHOT, new RateLimits.Limit(30, 30, RateLimits.Action.DROP))
            .with(Packets.SEND_CHAT_MESSAGE, new RateLimits.Limit(chat, chat / 2.0, RateLimits.Action.BLACKLIST))
            .with(Packets.PING, new RateLimits.Limit(5, 2, RateLimits.Action.DROP));
    }

    @Override
    public void received(Conn conn, Packet packet){
        if(!conn.isOpen()) return;
        Session session = sessions.of(conn);
        switch(packet.id()){
            case Packets.CONNECT_PACKET -> connect(conn, session, packet.payload());
            case Packets.CONNECT_CONFIRM -> {
                if(session != null) check(session, session.confirm(packet.arrivalNanos()));
            }
            case Packets.SEND_CHAT_MESSAGE -> {
                if(session != null) chat(session, packet);
            }
            // the join's asset handshake (NetServer.java:955-993), which vanilla handles before a connection confirms
            case Packets.REQUEST_ASSETS -> {
                if(session == null) return;
                Packets.RequestAssets request = decode(packet, Packets.RequestAssets::read);
                if(request != null && session.load() != null && session.load().world() instanceof HostedWorld world) world.requestAssets(session, request.ids());
            }
            case Packets.REQUEST_WORLD -> {
                if(session != null && session.load() != null && session.load().world() instanceof HostedWorld world) world.requestWorld(session);
            }
            case Packets.PING -> {
                Packets.Ping ping = decode(packet, Packets.Ping::read);
                if(ping != null) conn.sendTcp(Calls.pingResponse(ping.time()));
            }
            // clientSnapshot and the gameplay calls (WP26): decoded and offered to the session's mailbox
            default -> {
                if(session != null) host.intents().received(session, packet);
            }
        }
    }

    private void connect(Conn conn, Session existing, byte[] payload){
        Bootstrap.Connect connect;
        try{
            connect = Identity.read(payload);
        }catch(IllegalArgumentException e){
            malformed.increment();
            conn.close("malformed connect packet: " + e.getMessage());
            return;
        }
        String address = conn.remote().getAddress().getHostAddress();
        var live = new ArrayList<Identity>();
        for(Session other : sessions.all()){
            if(other.state() != SessionState.CLOSED) live.add(other.identity());
        }
        Admission.Verdict verdict = admission.check(connect, address, existing != null, live, ledger.size());
        switch(verdict){
            case Admission.Verdict.Ignore ignore -> conn.close("ignored: " + ignore.detail());
            case Admission.Verdict.Kick kick -> {
                if(kick.reason() == KickReason.WHITELIST) conn.sendTcp(Calls.infoMessage("You are not whitelisted here."));
                kick(conn, connect.uuidString(), kick.reason(), kick.detail());
            }
            case Admission.Verdict.KickText kick -> {
                conn.closeAfter(Calls.kick(kick.text()), "kicked: " + kick.detail());
                log(conn + " kicked: " + kick.detail());
            }
            case Admission.Verdict.Admit admit -> admitted(conn, connect, admit, address);
        }
    }

    private void admitted(Conn conn, Bootstrap.Connect connect, Admission.Verdict.Admit admit, String address){
        Identity identity = admit.identity();
        hooks.checked(conn);
        Config current = config.current();
        Ledger.Ticket ticket;
        switch(ledger.reserve(identity, admit.admin(), current.get(ConfigKeys.GAME_STRICT), current.get(ConfigKeys.NET_PLAYER_LIMIT))){
            case Ledger.Refused refused -> {
                kick(conn, identity.uuid(), refused.reason(), refused.detail());
                return;
            }
            case Ledger.Ticket reserved -> ticket = reserved;
        }
        // null if the connection closed meanwhile: its disconnect has run, and only the place is left to give back
        Session session = sessions.open(conn, identity);
        if(session == null){
            ticket.release();
            return;
        }
        session.whenClosed(ticket::release);
        hooks.opened(session);
        boolean isAdmin = admit.admin();
        if(!admin.isCurrent(admit.indexVersion())){
            // a ban or an admin change since the checks; one published from here on finds this session to kick
            switch(admin.admit(new AdminService.Connect(identity.uuid(), identity.usid(), identity.name(), address, 0))){
                case AdminService.Decision.Ignore ignore -> {
                    conn.close("ignored: " + ignore.detail());
                    return;
                }
                case AdminService.Decision.Kick kick -> {
                    kick(conn, identity.uuid(), kick.reason(), kick.detail());
                    return;
                }
                case AdminService.Decision.Admit again -> isAdmin = again.admin();
            }
        }
        session.admin(isAdmin);
        HostedWorld world = host.join(session);
        if(world == null){
            if(session.state() == SessionState.CONNECTED){
                boolean none = host.worlds().stream().noneMatch(HostedWorld::available);
                if(none) conn.closeAfter(Calls.kick("[scarlet]No world is running here right now."), "no world");
                else kick(conn, identity.uuid(), KickReason.PLAYER_LIMIT, "every world is full");
            }
            return;
        }
        hooks.routed(session);
        var adminConnect = new AdminService.Connect(identity.uuid(), identity.usid(), identity.name(), address, 0);
        var client = new AdminService.Client(identity.locale(), identity.version(), identity.mobile(), identity.version() == Bootstrap.CUSTOM_BUILD);
        CompletableFuture<AdminService.Joined> record = admin.join(adminConnect, client, world.id().name()).exceptionally(failure -> {
            LOG.warn("{}: recording the join failed: {}", session, failure.toString());
            return null;
        });
        // at once if the session closed already
        session.whenClosed(() -> record.thenAccept(joined -> {
            String reason = conn.closeReason();
            if(joined != null) admin.left(joined.session().id(), reason == null ? "closed" : reason);
        }));
        log(session + " connected from " + conn.remote() + " (" + identity.versionType() + ", uuid " + identity.uuid() + ")");
    }

    private void kick(Conn conn, String uuid, KickReason reason, String detail){
        // the cooldown first, as vanilla's NetConnection.kick records it before Call.kick: whoever hears the kick can
        // count on it (a test that pardons the kick once it hears it, say)
        if(reason.hasCooldown()) admin.kicked(uuid, conn.remote().getAddress().getHostAddress(), reason);
        conn.closeAfter(Calls.kick(reason), "kicked: " + reason.vanillaName() + " (" + detail + ")");
        log(conn + " kicked: " + reason.vanillaName() + " (" + detail + ")");
    }

    /** {@code sendChatMessage}: chat's ({@link Chat#received}), which offers it to the session's world. */
    private void chat(Session session, Packet packet){
        Packets.SendChatMessage message = decode(packet, Packets.SendChatMessage::read);
        if(message == null) return;
        if(chat.received(session, message.message(), packet.arrivalNanos()) == Chat.Received.REFUSED) check(session, Session.Offer.REFUSED);
    }

    private void check(Session session, Session.Offer offer){
        if(offer == Session.Offer.REFUSED){
            Conn conn = session.conn();
            conn.closeAfter(Calls.kick("[scarlet]You sent more commands than the server can queue."), "mailbox full");
            log(session + " kicked: its mailbox is full");
        }
    }

    /** A malformed packet is only counted, so no name or lambda is built for its error (a ping's took a string each). */
    private <T> T decode(Packet packet, Decoder<T> decoder){
        try{
            return decoder.read(new Reads(packet.payload()), false, null);
        }catch(WireException | UncheckedIOException e){
            malformed.increment();
            return null;
        }
    }

    @FunctionalInterface
    private interface Decoder<T>{
        T read(Reads read, boolean fromServer, WireContext context);
    }

    @Override
    public void disconnected(Conn conn, String reason){
        // its leave waits in its mailbox until the world takes it, however full the world is
        Session session = sessions.disconnected(conn);
        if(session == null){
            // a handshake that timed out or broke the protocol; a client that gave up is not worth a line
            if(!reason.equals(Transport.CLIENT_CLOSED)) log(conn + " closed before joining: " + reason);
            return;
        }
        host.closed(session);
        chat.closed(session);
        log(session + " disconnected: " + reason);
    }

    /** Packet or chat spam: vanilla's {@code blacklistDos}, until the process ends ({@code NetConnection.java:132}). */
    @Override
    public void blacklisted(Conn conn){
        String address = conn.remote().getAddress().getHostAddress();
        if(admin.dosBan(address)) log("DOS-banned " + address + " for packet spam (" + conn + ")");
    }

    @Override
    public byte[] discover(InetSocketAddress from){
        return discovery;
    }

    /** Refreshes what the server list shows ({@code NetworkIO.writeServerData}). */
    public void discovery(byte[] reply){
        discovery = reply;
    }

    private void log(String message){
        if(!quiet) LOG.info(message);
    }
}
