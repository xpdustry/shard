package com.xpdustry.shard.server.host;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.net.*;
import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.sim.blocks.storage.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static com.xpdustry.shard.server.ServerTest.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * W2.12's game in a hosted world: the map starts with vanilla's {@code logic.play()} (the cores hold their loadout),
 * a joining player spawns at its team's core in the core's unit, and everyone hears {@code playerSpawn}, the player
 * too, after "has connected". A {@code /sync} then streams the player in its unit.
 */
class PlayHookupTest{
    @TempDir
    static Path temp;
    static Server server;

    @BeforeAll
    static void start() throws Exception{
        server = Server.start(new Server.Options(ServerTest.configure(temp.resolve("main"), """
            host:
              worlds: [maze]
            """), Map.of(), false));
    }

    @AfterAll
    static void stop(){
        if(server != null) server.close();
    }

    @Test
    void aJoiningPlayerSpawnsAtItsCoreInTheCoresUnit() throws Exception{
        HostedWorld maze = server.host().world("maze").orElseThrow();
        try(var client = new TestClient(server.transport().address())){
            client.connect("alpha");
            int playerId = client.world().player();
            client.confirm();
            client.message("[accent]alpha[accent] has connected.");

            var spawn = ByteBuffer.wrap(client.payload(Packets.PlayerSpawn.ID));
            int tile = spawn.getInt();
            assertEquals(playerId, spawn.getInt(), "the joining player hears its own spawn");
            Session session = server.sessions().all().getFirst();
            await("alpha to play", () -> session.state() == SessionState.IN_WORLD);

            // read on the tick thread: the player's unit, its core's position, unit type and items
            record Seen(String unit, String coreUnit, int coreTile, int items){
            }
            Seen seen = maze.inspect(world -> {
                Player player = Roster.of(world).player(session.id());
                Core core = Teams.of(world).core(player.team);
                return new Seen(player.unit() == null ? "none" : String.valueOf(player.unit().type()), String.valueOf(core.unitType()),
                    core.pos(), core.items.total());
            }).get(Hang.SECONDS, TimeUnit.SECONDS);
            assertEquals(seen.coreUnit(), seen.unit(), "the core's unit type");
            assertEquals(seen.coreTile(), tile, "the call names the core's tile");
            assertTrue(seen.items() > 0, "the loadout is in the core: logic.play() ran");

            // a /sync streams the live player, in its unit
            client.chat("/sync");
            client.next(Packets.WorldDataBegin.ID);
            assertEquals(playerId, client.world().player());
        }
    }
}
