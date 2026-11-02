package com.xpdustry.shard.server.ui;

import com.xpdustry.shard.net.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.intent.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.server.sync.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.Packet;
import com.xpdustry.shard.wire.v160_7.Packets;
import org.slf4j.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * The plugin API for vanilla's server-side UI (WP52): each method sends exactly the {@code Call} vanilla's of the same
 * name sends ({@code ui/Menus.java}, {@code NetClient.java:235-305}), with the same packet, overload and channel, to a
 * {@link To}. Any thread; nothing blocks.
 *
 * <p>Menus and text inputs are registered first, as vanilla's {@code Menus.registerMenu} and
 * {@code registerTextInput}: the id they get is what the client answers with, and the listener hears every answer to
 * it, on the answering world's tick thread ({@link Answer}). Menus and text inputs share one id space, so an answer
 * finds exactly one listener; vanilla numbers them apart, which a client can't tell. Plugin packets a client sends
 * ({@code serverPacketReliable} and its kin) go to the handlers of their type ({@code netServer.addPacketHandler}).
 */
public final class Ui implements HostListener{
    private static final Logger LOG = LoggerFactory.getLogger(Ui.class);

    /** Where a Call goes: its target's world {@code Outbox}es, or a connection. Tests record instead. */
    public interface Sender{
        void send(To to, Packet packet);
    }

    @FunctionalInterface
    public interface Listener{
        void answered(Answer answer);
    }

    @FunctionalInterface
    public interface PacketHandler{
        void received(PluginPacket packet);
    }

    private final Host host;
    private volatile Sender sender;
    private final AtomicInteger nextId = new AtomicInteger();
    private final ConcurrentHashMap<Integer, Listener> listeners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<PacketHandler>> handlers = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Listener> everyAnswer = new CopyOnWriteArrayList<>();

    public Ui(Host host){
        this(host, null);
        host.listen(this);
    }

    /** A Ui that sends through {@code sender} and hears no world: what the Call gates record. */
    Ui(Host host, Sender sender){
        this.host = host;
        this.sender = sender == null ? this::deliver : sender;
    }

    /** Sends through {@code sender} instead; null for the Outboxes. */
    public void sender(Sender sender){
        this.sender = sender == null ? this::deliver : sender;
    }

    // ---- registration

    /** {@code Menus.registerMenu(listener)}: the menu's id, for {@link #menu} and {@link #followUpMenu}. */
    public int registerMenu(Listener listener){
        int id = nextId.getAndIncrement();
        listeners.put(id, Objects.requireNonNull(listener, "listener"));
        return id;
    }

    /** {@code Menus.registerTextInput(listener)}: the text input's id, for {@link #textInput}. */
    public int registerTextInput(Listener listener){
        return registerMenu(listener);
    }

    /** Every answer, whatever its id: vanilla's {@code MenuOptionChooseEvent} and {@code TextInputEvent}. */
    public void onAnswer(Listener listener){
        everyAnswer.add(Objects.requireNonNull(listener, "listener"));
    }

    /** {@code netServer.addPacketHandler(type, handler)}, and its binary and logic-data kin by {@link PluginPacket#kind}. */
    public void onPacket(String type, PacketHandler handler){
        handlers.computeIfAbsent(type, t -> new CopyOnWriteArrayList<>()).add(Objects.requireNonNull(handler, "handler"));
    }

    // ---- menus and text input

    /** {@code Call.menu(menuId, title, message, options)}: a menu of option rows. */
    public void menu(To to, int menuId, String title, String message, String[][] options){
        send(to, new Packets.Menu(menuId, title, message, options));
    }

    /** {@code Call.followUpMenu}: a menu that replaces the open one with the same id. */
    public void followUpMenu(To to, int menuId, String title, String message, String[][] options){
        send(to, new Packets.FollowUpMenu(menuId, title, message, options));
    }

    /** {@code Call.hideFollowUpMenu(menuId)}. */
    public void hideFollowUpMenu(To to, int menuId){
        send(to, new Packets.HideFollowUpMenu(menuId));
    }

    /** {@code Call.textInput(id, title, message, textLength, def, numeric)}. */
    public void textInput(To to, int id, String title, String message, int textLength, String def, boolean numeric){
        send(to, new Packets.TextInput(id, title, message, textLength, def, numeric));
    }

    /** {@code Call.textInput(id, title, message, textLength, def, numeric, allowEmpty)}. */
    public void textInput(To to, int id, String title, String message, int textLength, String def, boolean numeric, boolean allowEmpty){
        send(to, new Packets.TextInput2(id, title, message, textLength, def, numeric, allowEmpty));
    }

    // ---- messages, popups and toasts

    /** {@code Call.infoMessage}: a dialog with an OK button. */
    public void infoMessage(To to, String message){
        send(to, new Packets.InfoMessage(message));
    }

    /** {@code Call.announce}: a line across the top of the screen. */
    public void announce(To to, String message){
        send(to, new Packets.Announce(message));
    }

    /** {@code Call.infoToast}. */
    public void infoToast(To to, String message, float duration){
        send(to, new Packets.InfoToast(message, duration));
    }

    /** {@code Call.warningToast(unicode, text)}. */
    public void warningToast(To to, int unicode, String text){
        send(to, new Packets.WarningToast(unicode, text));
    }

    /** {@code Call.setHudText} (unreliable) or {@code setHudTextReliable}. */
    public void hudText(To to, String message, boolean reliable){
        send(to, reliable ? new Packets.SetHudTextReliable(message) : new Packets.SetHudText(message));
    }

    /** {@code Call.hideHudText()}. */
    public void hideHudText(To to){
        send(to, new Packets.HideHudText());
    }

    /**
     * {@code Call.infoPopup} (unreliable) or {@code infoPopupReliable}: text placed by {@code align} and margins, for
     * {@code duration} seconds. With an {@code id}, a popup of the same id is replaced (vanilla's overload with one).
     */
    public void infoPopup(To to, String message, String id, float duration, int align, int top, int left, int bottom, int right, boolean reliable){
        Packet packet;
        if(id == null){
            packet = reliable ? new Packets.InfoPopupReliable(message, duration, align, top, left, bottom, right)
                : new Packets.InfoPopup(message, duration, align, top, left, bottom, right);
        }else{
            packet = reliable ? new Packets.InfoPopupReliable2(message, id, duration, align, top, left, bottom, right)
                : new Packets.InfoPopup2(message, id, duration, align, top, left, bottom, right);
        }
        send(to, packet);
    }

    /** {@code Call.openURI}: the client asks its player first. */
    public void openUri(To to, String uri){
        send(to, new Packets.OpenURI(uri));
    }

    /** {@code Call.copyToClipboard}. */
    public void copyToClipboard(To to, String text){
        send(to, new Packets.CopyToClipboard(text));
    }

    // ---- world labels

    /** {@code Call.label(message, duration, worldx, worldy)} (unreliable) or {@code labelReliable}. */
    public void label(To to, String message, float duration, float x, float y, boolean reliable){
        send(to, reliable ? new Packets.LabelReliable(message, duration, x, y) : new Packets.Label(message, duration, x, y));
    }

    /**
     * {@code Call.label(message, id, duration, worldx, worldy, flags)} or {@code labelReliable}: a label of the same id
     * is replaced, and {@link #removeLabel} removes it.
     */
    public void label(To to, String message, int id, float duration, float x, float y, int flags, boolean reliable){
        send(to, reliable ? new Packets.LabelReliable3(message, id, duration, x, y, flags) : new Packets.Label3(message, id, duration, x, y, flags));
    }

    /** {@code Call.removeWorldLabel(id)}. */
    public void removeLabel(To to, int id){
        send(to, new Packets.RemoveWorldLabel(id));
    }

    // ---- effects and sounds

    /**
     * {@code Call.effect(effect, x, y, rotation, color)} (unreliable) or {@code effectReliable}.
     *
     * @param effect vanilla's effect id ({@code Effect.id}, creation order in {@code Fx})
     * @param color RGBA8888
     */
    public void effect(To to, int effect, float x, float y, float rotation, int color, boolean reliable){
        send(to, reliable ? new Packets.EffectReliable(effect, x, y, rotation, color) : new Packets.Effect(effect, x, y, rotation, color));
    }

    /** {@code Call.effect(effect, x, y, rotation, color, data)}: with a {@code writeObject} value. */
    public void effect(To to, int effect, float x, float y, float rotation, int color, Object data){
        send(to, new Packets.Effect2(effect, x, y, rotation, color, data));
    }

    /**
     * {@code Call.sound(sound, volume, pitch, pan)}.
     *
     * @param sound vanilla's sound id ({@code Sounds.getSoundId})
     */
    public void sound(To to, int sound, float volume, float pitch, float pan){
        send(to, new Packets.Sound((short)sound, volume, pitch, pan));
    }

    /** {@code Call.soundAt(sound, x, y, volume, pitch)}. */
    public void soundAt(To to, int sound, float x, float y, float volume, float pitch){
        send(to, new Packets.SoundAt((short)sound, x, y, volume, pitch));
    }

    // ---- plugin packets

    /** {@code Call.clientPacketReliable(type, contents)} or {@code clientPacketUnreliable}. */
    public void clientPacket(To to, String type, String contents, boolean reliable){
        send(to, reliable ? new Packets.ClientPacketReliable(type, contents) : new Packets.ClientPacketUnreliable(type, contents));
    }

    /** {@code Call.clientBinaryPacketReliable(type, contents)} or {@code clientBinaryPacketUnreliable}. */
    public void clientBinaryPacket(To to, String type, byte[] contents, boolean reliable){
        send(to, reliable ? new Packets.ClientBinaryPacketReliable(type, contents) : new Packets.ClientBinaryPacketUnreliable(type, contents));
    }

    // ---- sending

    private void send(To to, Packet packet){
        sender.send(Objects.requireNonNull(to, "to"), packet);
    }

    /** The default sender: each world's Outbox, encoded once; a player in no world, its connection. */
    private void deliver(To to, Packet packet){
        Frame frame = Calls.frame(packet);
        switch(to){
            case To.Player(Session session) -> {
                if(session.world() instanceof HostedWorld world) world.outbox().offer(Audience.only(session.id()), frame);
                else if(Packets.TYPES.get(packet.packetId()).reliable()) session.conn().sendTcp(frame);
                else session.conn().sendUdp(frame);
            }
            case To.World(HostedWorld world) -> world.outbox().offer(Audience.ALL, frame);
            case To.Team(HostedWorld world, int team) -> world.outbox().offer(Audience.team(team), frame);
            case To.Everyone() -> {
                for(HostedWorld world : host.worlds()) world.outbox().offer(Audience.ALL, frame);
            }
        }
    }

    // ---- answers, on the world's thread

    @Override
    public void event(HostedWorld world, World state, WorldEvent event){
        switch(event){
            case PlayerIntents.Answered answered -> answered(world, state, answered);
            case PlayerIntents.CustomReceived custom -> custom(world, state, custom);
            default -> {
            }
        }
    }

    private void answered(HostedWorld world, World state, PlayerIntents.Answered answered){
        Session session = host.sessions().get(answered.session());
        Roster roster = Roster.of(state);
        Player player = roster == null ? null : roster.player(answered.session());
        if(session == null || player == null) return;
        var answer = new Answer(world, state, session, player, answered.id(), answered.option(), answered.text());
        for(Listener listener : everyAnswer) run(listener, answer);
        Listener listener = listeners.get(answered.id());
        if(listener != null) run(listener, answer);
    }

    private void custom(HostedWorld world, World state, PlayerIntents.CustomReceived custom){
        List<PacketHandler> list = handlers.get(custom.type());
        if(list == null) return;
        Session session = host.sessions().get(custom.session());
        if(session == null) return;
        var packet = new PluginPacket(world, state, session, PluginPacket.Kind.valueOf(custom.kind().name()), custom.type(), custom.contents(), custom.reliable());
        for(PacketHandler handler : list){
            try{
                handler.received(packet);
            }catch(RuntimeException e){
                LOG.error("{}: a packet handler for '{}' failed", world.id(), custom.type(), e);
            }
        }
    }

    private static void run(Listener listener, Answer answer){
        try{
            listener.answered(answer);
        }catch(RuntimeException e){
            LOG.error("{}: a menu listener for {} failed", answer.world().id(), answer.id(), e);
        }
    }
}
