package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.Packets;

import java.util.*;

/**
 * The client calls vanilla forwards once its server ran them ({@code @Remote(forward = true)}), and to whom: the
 * generated {@code __forward} uses {@code net.sendExcept(sender)} when the call's {@code called} includes the client,
 * which already ran it, and {@code net.send} otherwise (CallGenerator.java:328-333). {@code ForwardsTest} holds this
 * table to the generated {@code Call} class of the vanilla jar.
 */
public final class Forwards{
    /** Forwarded calls the client runs itself first ({@code called = Loc.both}): everyone but the sender. */
    static final Set<Integer> EXCEPT_SENDER = Set.of(Packets.TileConfig.ID, Packets.DeletePlans.ID, Packets.UnitControl.ID,
        Packets.SetPlayerTeamEditor.ID);
    /** Forwarded calls only the server runs ({@code called = Loc.server}): everyone, the sender included. */
    static final Set<Integer> EVERYONE = Set.of(Packets.RotateBlock.ID, Packets.UnitClear.ID, Packets.CommandUnits.ID,
        Packets.SetUnitCommand.ID, Packets.SetUnitStance.ID, Packets.CommandBuilding.ID, Packets.RequestItem.ID,
        Packets.TransferInventory.ID, Packets.PingLocation.ID);

    private Forwards(){
    }

    /** The forward of {@code packet}, which {@code sender} sent. */
    public static IntentCall of(long sender, Packet packet){
        int id = packet.packetId();
        if(EXCEPT_SENDER.contains(id)) return new IntentCall.AllExcept(sender, packet);
        if(EVERYONE.contains(id)) return new IntentCall.All(packet);
        throw new IllegalArgumentException("vanilla doesn't forward packet " + id);
    }
}
