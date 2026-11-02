package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.sim.blocks.distribution.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W3.1's config gate on the real server: a client's {@code tileConfig} on a bridge conveyor (a relative {@code Point2}
 * and an {@code Integer} position) and on a mass driver (both forms, and {@code -1} to unlink) is applied once and
 * forwarded once to each other client, never back to its sender, as W2.20's single forward does for every block. Each
 * client is a fork of the world it loaded applying its own config and the forwards it got, and must end with the
 * server's links.
 */
class BridgeConfigServerTest{
    @TempDir
    static Path temp;
    static Server server;

    @BeforeAll
    static void start() throws Exception{
        server = Server.start(new Server.Options(ServerTest.configure(temp.resolve("config"), """
            host:
              worlds: [maze]
            chat:
              anti-spam: false
            """), Map.of(), false));
    }

    @AfterAll
    static void stop(){
        if(server != null) server.close();
    }

    static Session session(String name){
        return server.sessions().all().stream().filter(s -> s.identity().plainName().equals(name)).findFirst().orElse(null);
    }

    static TestClient joined(String name) throws Exception{
        var client = new TestClient(server.transport().address());
        client.connect(name);
        client.world();
        client.confirm();
        ServerTest.await(name + " to play", () -> session(name) != null && session(name).state() == SessionState.IN_WORLD);
        return client;
    }

    /** Two bridge conveyors three tiles apart and two mass drivers twenty tiles apart, unlinked, on free floor. */
    record Fixture(int bridge, int other, int driver, int target){
    }

    static Fixture place(World w){
        int[] bridges = free(w, 4, 1);
        int[] drivers = free(w, 23, 3);
        int team = 1;
        Building bridge = w.buildings().setBlock(bridges[0], bridges[1], w.content().block("bridge-conveyor"), team, 0, Cause.SERVER);
        Building other = w.buildings().setBlock(bridges[0] + 3, bridges[1], w.content().block("bridge-conveyor"), team, 0, Cause.SERVER);
        Building driver = w.buildings().setBlock(drivers[0] + 1, drivers[1] + 1, w.content().block("mass-driver"), team, 0, Cause.SERVER);
        Building target = w.buildings().setBlock(drivers[0] + 21, drivers[1] + 1, w.content().block("mass-driver"), team, 0, Cause.SERVER);
        return new Fixture(bridge.pos(), other.pos(), driver.pos(), target.pos());
    }

    /** The bottom-left corner of a free {@code width × height} area away from what {@link #place} took already. */
    static int[] free(World w, int width, int height){
        var tiles = w.tiles();
        for(int y = 2; y < tiles.height() - height - 2; y++){
            search:
            for(int x = 2; x < tiles.width() - width - 2; x++){
                for(int yy = y - 1; yy <= y + height; yy++){
                    for(int xx = x - 1; xx <= x + width; xx++){
                        int pos = tiles.pos(xx, yy);
                        if(tiles.block(pos) != 0 || tiles.solid(pos) || tiles.isDeep(pos) || w.buildings().at(xx, yy) != null) continue search;
                    }
                }
                return new int[]{x, y};
            }
        }
        throw new IllegalStateException("no free area of " + width + " × " + height);
    }

    static Building at(World w, int pos){
        return w.buildings().at(Point2.x(pos), Point2.y(pos));
    }

    static String links(World w, Fixture f){
        return "bridge " + ((ItemBridgeBuild)at(w, f.bridge())).link + " other " + ((ItemBridgeBuild)at(w, f.other())).link
            + " driver " + ((MassDriver)at(w, f.driver())).link + " target " + ((MassDriver)at(w, f.target())).link;
    }

    static <T> T on(HostedWorld world, java.util.function.Function<World, T> task) throws Exception{
        return world.inspect(task).get(Hang.SECONDS, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test
    void bridgeAndDriverConfigsApplyAndForwardOnce() throws Exception{
        try(var alpha = joined("alpha"); var beta = joined("beta")){
            var world = (HostedWorld)session("alpha").world();
            Fixture f = on(world, BridgeConfigServerTest::place);
            int alphaPlayer = on(world, w -> Roster.of(w).player(session("alpha").id()).id());
            var alphaSees = new ConfigForwardServerTest.Replica(on(world, World::fork));
            var betaSees = new ConfigForwardServerTest.Replica(on(world, World::fork));
            String unlinked = on(world, w -> links(w, f));

            List<Object[]> configs = List.of(
                new Object[]{f.bridge(), new Point2(3, 0)},
                new Object[]{f.other(), f.bridge()},
                new Object[]{f.driver(), new Point2(20, 0)},
                new Object[]{f.target(), f.driver()},
                new Object[]{f.target(), -1});
            for(Object[] config : configs){
                int build = (Integer)config[0];
                alphaSees.own(build, config[1]);
                alpha.tcp(Packets.TileConfig.ID, ConfigForwardServerTest.tileConfig(build, config[1]));
                byte[] forward = beta.payload(Packets.TILE_CONFIG);
                assertEquals(alphaPlayer, new Reads(forward).i(), "the forward names its sender");
                betaSees.received(forward);
            }
            assertFalse(beta.receives(Packets.TILE_CONFIG, 500), "one forward per config");
            assertFalse(alpha.receives(Packets.TILE_CONFIG, 0), "the sender ran its own");
            String linked = on(world, w -> links(w, f));
            assertEquals("bridge " + f.other() + " other " + f.bridge() + " driver " + f.target() + " target -1", linked);
            assertNotEquals(unlinked, linked);
            assertEquals(linked, links(alphaSees.world(), f), "alpha's links");
            assertEquals(linked, links(betaSees.world(), f), "beta's links");
        }
    }
}
