package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.server.intent.action.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

/**
 * What admission remembers about one player's input, attached to its {@link Player}: the parts of vanilla's
 * {@code NetConnection} ({@code lastReceivedClientSnapshot}, {@code lastReceivedClientTime}, the view rectangle,
 * {@code kicked}), of its {@code PlayerInfo} (the interaction {@code rate} and {@code messageTimer}) and of the player
 * ({@code itemDepositRate}) that decide what a later intent does. It is world state, so a fork or a replay admits the
 * same intents the same way; vanilla keeps the info per uuid across reconnects, Shard per player, so a rejoin starts a
 * fresh window.
 *
 * <p>The counters at the end are the walk gate's (WP26): how far the client's reports were from where the server
 * kept the unit.
 *
 * <p>{@link Persistence#AUTHORITATIVE}.
 */
public final class PlayerInput{
    public static final AttachmentKey<PlayerInput> KEY = AttachmentKey.create("server.intent.player-input", PlayerInput.class,
        Persistence.AUTHORITATIVE, (input, context) -> input.copy(), PlayerInput::hash);

    /** {@code lastReceivedClientSnapshot}: a lower snapshot id is out of date. */
    public int lastSnapshot = -1;
    /** {@code lastReceivedClientTime}, the arrival of the last snapshot applied, in ms; meaningless until set. */
    public long lastClientTime;
    public boolean hasClientTime;
    /** The view the client last reported: centre and size, in world units. */
    public float viewX, viewY, viewWidth, viewHeight;
    /** {@code PlayerInfo.rate}: the interaction rate limit's window. */
    public Ratekeeper interactions = new Ratekeeper();
    /** {@code itemDepositRate}: {@code transferInventory}'s own limit. */
    public Ratekeeper deposits = new Ratekeeper();
    /** {@code PlayerInfo.messageTimer}: the tick of the last rate-limit message. */
    public long lastMessageTick;
    public boolean messaged;
    /** {@code NetConnection.kicked}: nothing more from this player is admitted. */
    public boolean kicked;
    /** Snapshots applied with a unit. */
    public long snapshots;
    /** {@code setPosition} corrections sent: vanilla's rubber bands. */
    public int corrections;
    /** Snapshots whose move the server shortened to the unit's speed. */
    public int clamped;
    /** The farthest the client's reported position was from where the server kept the unit, in world units. */
    public float maxDeviation;
    /** The farthest the unit was from the client's report before applying it: one snapshot's travel. */
    public float maxLag;
    @Derived("the last viewer() made, reused while what it holds is the same")
    private Viewer viewer;

    public PlayerInput(){
    }

    /** {@code player}'s, attached now if it has none. */
    public static PlayerInput of(Player player){
        PlayerInput input = player.attachment(KEY);
        if(input == null){
            input = new PlayerInput();
            player.attach(KEY, input);
        }
        return input;
    }

    /**
     * What {@code player}'s session may see now: its team and the view its client last reported in a
     * {@code clientSnapshot} (vanilla's {@code NetConnection.viewX}…), centre and size in world units. Before its first
     * snapshot, a point at the player. The last one made is returned again while nothing in it changed, so a sync that
     * asks every snapshot allocates only when the view moves.
     */
    public static Viewer viewer(long session, Player player){
        PlayerInput input = player.attachment(KEY);
        if(input == null) return new Viewer(session, player.team, player.x, player.y, 0f, 0f, player.admin);
        boolean reported = input.lastSnapshot >= 0;
        float x = reported ? input.viewX : player.x, y = reported ? input.viewY : player.y;
        float width = reported ? input.viewWidth : 0f, height = reported ? input.viewHeight : 0f;
        Viewer last = input.viewer;
        if(last != null && last.session() == session && last.team() == player.team && last.admin() == player.admin
            && Float.compare(last.x(), x) == 0 && Float.compare(last.y(), y) == 0
            && Float.compare(last.width(), width) == 0 && Float.compare(last.height(), height) == 0){
            return last;
        }
        return input.viewer = new Viewer(session, player.team, x, y, width, height, player.admin);
    }

    /** {@code messageTimer.get(ticks)}: true, and restarted, if {@code ticks} passed since it last was. */
    public boolean messageDue(long now, float ticks){
        if(messaged && now - lastMessageTick < ticks && now >= lastMessageTick) return false;
        messaged = true;
        lastMessageTick = now;
        return true;
    }

    PlayerInput copy(){
        var out = new PlayerInput();
        out.lastSnapshot = lastSnapshot;
        out.lastClientTime = lastClientTime;
        out.hasClientTime = hasClientTime;
        out.viewX = viewX;
        out.viewY = viewY;
        out.viewWidth = viewWidth;
        out.viewHeight = viewHeight;
        out.interactions = interactions.copy();
        out.deposits = deposits.copy();
        out.lastMessageTick = lastMessageTick;
        out.messaged = messaged;
        out.kicked = kicked;
        out.snapshots = snapshots;
        out.corrections = corrections;
        out.clamped = clamped;
        out.maxDeviation = maxDeviation;
        out.maxLag = maxLag;
        return out;
    }

    void hash(Hasher hasher){
        hasher.i(lastSnapshot).l(lastClientTime).bool(hasClientTime).f(viewX).f(viewY).f(viewWidth).f(viewHeight);
        interactions.hash(hasher);
        deposits.hash(hasher);
        hasher.l(lastMessageTick).bool(messaged).bool(kicked).l(snapshots).i(corrections).i(clamped).f(maxDeviation).f(maxLag);
    }

    @Register
    public static final class Codec implements AttachmentCodec<PlayerInput>{
        @Override
        public AttachmentKey<PlayerInput> key(){
            return KEY;
        }

        @Override
        public void write(PlayerInput value, CheckpointOut out){
            out.i("lastSnapshot", value.lastSnapshot).l("lastClientTime", value.lastClientTime).bool("hasClientTime", value.hasClientTime)
                .f("viewX", value.viewX).f("viewY", value.viewY).f("viewWidth", value.viewWidth).f("viewHeight", value.viewHeight)
                .i("interactions.occurrences", value.interactions.occurrences).l("interactions.lastTime", value.interactions.lastTime)
                .bool("interactions.started", value.interactions.started)
                .i("deposits.occurrences", value.deposits.occurrences).l("deposits.lastTime", value.deposits.lastTime)
                .bool("deposits.started", value.deposits.started)
                .l("lastMessageTick", value.lastMessageTick).bool("messaged", value.messaged).bool("kicked", value.kicked)
                .l("snapshots", value.snapshots).i("corrections", value.corrections).i("clamped", value.clamped)
                .f("maxDeviation", value.maxDeviation).f("maxLag", value.maxLag);
        }

        @Override
        public PlayerInput read(CheckpointIn in){
            var input = new PlayerInput();
            input.lastSnapshot = in.i("lastSnapshot");
            input.lastClientTime = in.l("lastClientTime");
            input.hasClientTime = in.bool("hasClientTime");
            input.viewX = in.f("viewX");
            input.viewY = in.f("viewY");
            input.viewWidth = in.f("viewWidth");
            input.viewHeight = in.f("viewHeight");
            input.interactions.occurrences = in.i("interactions.occurrences");
            input.interactions.lastTime = in.l("interactions.lastTime");
            input.interactions.started = in.bool("interactions.started");
            input.deposits.occurrences = in.i("deposits.occurrences");
            input.deposits.lastTime = in.l("deposits.lastTime");
            input.deposits.started = in.bool("deposits.started");
            input.lastMessageTick = in.l("lastMessageTick");
            input.messaged = in.bool("messaged");
            input.kicked = in.bool("kicked");
            input.snapshots = in.l("snapshots");
            input.corrections = in.i("corrections");
            input.clamped = in.i("clamped");
            input.maxDeviation = in.f("maxDeviation");
            input.maxLag = in.f("maxLag");
            return input;
        }
    }
}
