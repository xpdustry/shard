package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.intent.action.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.Packets;

/**
 * One intent's admission, on the world thread: the acting {@link Player} the session has in this world, its
 * {@link PlayerInput}, the {@link Policy} the packet was decoded under, and what the admission says back. It is the
 * {@link ActionContext} filters see. Vanilla's handlers start with {@code if(con.player == null || con.kicked) return}
 * (the generated {@code handleServer}); {@link #of} is that check.
 */
final class Acting implements ActionContext{
    final World world;
    final IntentEnvelope envelope;
    final Player player;
    final PlayerInput input;
    final Policy policy;
    private final ActionFilters filters;

    private Acting(World world, IntentEnvelope envelope, Player player, Policy policy, ActionFilters filters){
        this.world = world;
        this.envelope = envelope;
        this.player = player;
        this.input = PlayerInput.of(player);
        this.policy = policy;
        this.filters = filters;
    }

    /** The admission of {@code envelope}, or null if the session has no player here. */
    static Acting of(World world, IntentEnvelope envelope, Policy policy){
        return of(world, envelope, policy, ActionFilters.builtIn());
    }

    static Acting of(World world, IntentEnvelope envelope, Policy policy, ActionFilters filters){
        Roster roster = Roster.of(world);
        Player player = roster == null ? null : roster.player(envelope.session());
        return player == null || !player.isAdded() ? null : new Acting(world, envelope, player, policy, filters);
    }

    /** The common start of every handler: the verdict to return now, or null to go on. */
    static Verdict refused(Acting acting){
        if(acting == null) return Verdict.rejected("not playing");
        if(acting.input.kicked) return Verdict.rejected("kicked");
        return null;
    }

    long session(){
        return envelope.session();
    }

    // ---- references, as TypeIO reads them on vanilla's main thread

    /** {@code readBuilding}: {@code world.build(pos)}, the building covering the tile, or null. */
    Building building(int pos){
        return pos == -1 ? null : world.buildings().at(Point2.x(pos), Point2.y(pos));
    }

    /** {@code readTile}: whether the packed position is on the map. */
    boolean tileExists(int pos){
        return pos != -1 && world.tiles().inBounds(Point2.x(pos), Point2.y(pos));
    }

    /**
     * {@code readUnit}: kind 2 is {@code Groups.unit.getByID}, kind 1 a block's unit ({@code ControlBlock.unit()}),
     * anything else null. Block units come with turrets (WP60); until then kind 1 reads as null.
     */
    Unit unit(Codecs.UnitRef ref){
        if(ref == null) return null;
        // TypeIO.readUnit's kind 1: a ControlBlock's unit, by its building's position (WP60)
        if(ref.kind() == 1) return com.xpdustry.shard.sim.blocks.turret.BlockUnits.at(world, ref.ref());
        if(ref.kind() != 2) return null;
        return unit(ref.ref());
    }

    /** {@code Groups.unit.getByID(id)}. */
    Unit unit(int id){
        EntityGroups groups = EntityGroups.of(world);
        return groups != null && groups.byId(id) instanceof Unit unit && unit.isAdded() ? unit : null;
    }

    /**
     * {@code readObjectSafe} on the world thread, as a sim config value: a building reference becomes the building and
     * a unit reference the unit, or null, in an object array too, as vanilla's nested read gives them.
     */
    Object config(byte[] value){
        return sim(WireException.decoding("a config", () -> WireObjects.readObjectSafe(new Reads(value), WorldWireContext.of(world.config()))));
    }

    private Object sim(Object read){
        return switch(read){
            case WireObjects.BuildingRef ref -> building(ref.pos());
            case WireObjects.UnitIdRef ref -> unit(ref.id());
            // Point2[] and Vec2[] hold no references
            case Object[] values when !(values instanceof Point2[]) && !(values instanceof Codecs.Vec2[]) -> {
                var out = new Object[values.length];
                for(int i = 0; i < values.length; i++) out[i] = sim(values[i]);
                yield out;
            }
            case null, default -> WireValues.toSim(read, world.content());
        };
    }

    /** {@code writeUnit}: a unit as the wire names it. */
    static Codecs.UnitRef ref(Unit unit){
        if(unit == null) return Codecs.UnitRef.NONE;
        return unit.blockTile != null ? Codecs.UnitRef.block(unit.blockTile.pos()) : Codecs.UnitRef.unit(unit.id());
    }

    // ---- filters

    /** {@code allowAction}: null if every filter allows {@code action}, else the rejection naming the filter. */
    Verdict denied(PlayerAction action){
        ActionFilter denier = filters.denier(action, this);
        return denier == null ? null : Verdict.rejected("denied by " + denier.getClass().getSimpleName() + ": " + action.type().vanillaName());
    }

    PlayerAction.Builder action(ActionType type){
        return PlayerAction.of(player, session(), type);
    }

    // ---- ActionContext

    @Override
    public World world(){
        return world;
    }

    @Override
    public Policy policy(){
        return policy;
    }

    @Override
    public long arrivalMillis(){
        return Math.floorDiv(envelope.arrivalNanos(), 1_000_000L);
    }

    @Override
    public PlayerInput input(){
        return input;
    }

    @Override
    public void message(String text){
        reply(new Packets.SendMessage(text));
    }

    @Override
    public void kick(String reason, long banMillis){
        if(input.kicked) return;
        input.kicked = true;
        world.publish(new IntentCall.Kick(session(), reason, banMillis));
    }

    // ---- calls

    /** A Call to the acting player only. */
    void reply(Packet packet){
        world.publish(new IntentCall.To(session(), packet));
    }

    /** A server Call ({@code Call.takeItems}...): to everyone, the acting player included. */
    void all(Packet packet){
        world.publish(new IntentCall.All(packet));
    }

    /** The forward of the admitted call: to everyone, or to everyone else if the sender already ran it. */
    void forward(Packet packet){
        world.publish(Forwards.of(session(), packet));
    }
}
