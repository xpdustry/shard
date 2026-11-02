package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.sim.blocks.defense.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A player's tap on a door is a {@code tileConfig} with a {@code Boolean} (DoorBuild.tapped): admitted, it opens the
 * door's whole chain and the grid's solidity with it, and goes out as vanilla's forward to everyone but the sender,
 * whose client already ran it. Another team's door doesn't take it ({@code Units.canInteract}).
 */
class DoorIntentTest{
    static final long ALPHA = 7;
    static final Policy POLICY = new Policy(true, false, 6_000, 25, 60);

    IntentWorld w;
    Player player;

    @BeforeEach
    void world(){
        w = new IntentWorld();
        player = w.joinWith(ALPHA, "alpha", "alpha", 100f, 100f);
    }

    static byte[] object(Object value){
        var out = new Writes(16);
        WireObjects.writeObjectSafe(out, value);
        return out.toByteArray();
    }

    @Test
    void aPlayersTapOpensTheChainAndIsForwarded(){
        var a = (Door)w.place("door", 14, 12, player.team, 0);
        var b = (Door)w.place("door", 14, 13, player.team, 0);
        var configured = w.world.observe(16, List.of(BuildingEvent.Configured.class), null);
        assertTrue(w.world.tiles().buildSolid(a.tile()));
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, new BlockIntents.TileConfig(POLICY, a.pos(), object(Boolean.TRUE)), 1_000));
        assertTrue(a.open);
        assertTrue(b.open, "the chain opens with it");
        assertFalse(w.world.tiles().buildSolid(a.tile()), "units pass");
        var forwards = IntentWorld.calls(w.drain(), Packets.TileConfig.class);
        assertEquals(List.of(new Packets.TileConfig(player.id(), a.pos(), true)), forwards);
        var events = configured.drain();
        assertEquals(1, events.size());
        assertTrue(((BuildingEvent.Configured)events.getFirst()).forwarded(), "the forward is the config's only Call");

        w.admit(ALPHA, new BlockIntents.TileConfig(POLICY, b.pos(), object(Boolean.FALSE)), 2_000);
        assertFalse(a.open);
        assertFalse(b.open);
        assertTrue(w.world.tiles().buildSolid(b.tile()), "units are blocked again");
    }

    @Test
    void anEnemysDoorRefusesTheTap(){
        var door = (Door)w.place("door", 14, 12, Team.CRUX, 0);
        w.admit(ALPHA, new BlockIntents.TileConfig(POLICY, door.pos(), object(Boolean.TRUE)), 1_000);
        assertFalse(door.open);
        assertEquals(List.of(), IntentWorld.calls(w.drain(), Packets.TileConfig.class).stream()
            .filter(call -> Boolean.TRUE.equals(call.value())).toList(), "nothing forwarded");
    }
}
