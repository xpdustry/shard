package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.compat.v160.sync.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.sim.blocks.logic.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review 39's blocker, on the real server: a client's {@code tileConfig} reaches each other client once, as vanilla's
 * generated forward sends it ({@code sendExcept(sender)}), and never comes back to its sender; a server-side configure
 * reaches everyone once. A power-node link and a processor link toggle, so a second copy would undo the first: each
 * client is modelled as a fork of the world it loaded, applying its own config (the client already ran it) and the
 * {@code TileConfig}s it received, and must end with the server's links and power graphs, after the tick and after a
 * loading client's buffered Calls are flushed.
 */
class ConfigForwardServerTest{
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

    /** Two power nodes in laser range, unlinked, and a micro processor with a switch in range, on free floor. */
    record Fixture(int node, int other, int processor, int target){
    }

    static Fixture place(World w){
        var tiles = w.tiles();
        for(int y = 2; y < tiles.height() - 6; y++){
            for(int x = 2; x < tiles.width() - 6; x++){
                if(!free(w, x, y, 6, 5)) continue;
                var team = 1;
                Building node = w.buildings().setBlock(x, y, w.content().block("power-node"), team, 0, Cause.SERVER);
                Building other = w.buildings().setBlock(x + 3, y, w.content().block("power-node"), team, 0, Cause.SERVER);
                Building processor = w.buildings().setBlock(x, y + 3, w.content().block("micro-processor"), team, 0, Cause.SERVER);
                Building target = w.buildings().setBlock(x + 2, y + 3, w.content().block("switch"), team, 0, Cause.SERVER);
                return new Fixture(node.pos(), other.pos(), processor.pos(), target.pos());
            }
        }
        throw new IllegalStateException("no free area");
    }

    static boolean free(World w, int x0, int y0, int width, int height){
        var tiles = w.tiles();
        for(int y = y0; y < y0 + height; y++){
            for(int x = x0; x < x0 + width; x++){
                int pos = tiles.pos(x, y);
                if(tiles.block(pos) != 0 || tiles.solid(pos) || tiles.isDeep(pos) || w.buildings().at(x, y) != null) return false;
            }
        }
        return true;
    }

    static Building at(World w, int pos){
        return w.buildings().at(Point2.x(pos), Point2.y(pos));
    }

    /** The links and power graphs a client would show: each node's links, its graph's members, the processor's links. */
    static String links(World w, Fixture f){
        var out = new StringBuilder();
        for(int pos : new int[]{f.node(), f.other()}){
            Building node = at(w, pos);
            var members = new TreeSet<Integer>();
            for(int i = 0; i < node.power.graph.size(); i++) members.add(node.power.graph.member(i).pos());
            out.append("node ").append(pos).append(" links ").append(Arrays.toString(node.power.links.toArray())).append(" graph ").append(members).append('\n');
        }
        out.append("processor links ").append(((LogicBlock)at(w, f.processor())).processor().links());
        return out.toString();
    }

    /** A client's copy of the world: what it loaded, then its own configs and the Calls it got. */
    record Replica(World world){
        /** Its own config: the client ran it before sending ({@code called = Loc.both}). */
        void own(int build, Object value){
            world.buildings().configure(at(world, build), null, value, Cause.SERVER);
        }

        /** A {@code TileConfig} it received, applied as {@code InputHandler.tileConfig} applies it on the client. */
        void received(byte[] payload){
            var read = new Reads(payload);
            read.i();
            int build = read.i();
            Object value = WireObjects.readObjectSafe(read, WorldWireContext.of(world.config()));
            Object config = value instanceof WireObjects.BuildingRef ref ? at(world, ref.pos()) : WireValues.toSim(value, world.content());
            world.buildings().configure(at(world, build), null, config, Cause.SERVER);
        }
    }

    static byte[] tileConfig(int build, Object value){
        var out = new Writes();
        new Packets.TileConfig(-1, build, value).write(out, false, null);
        return out.toByteArray();
    }

    static <T> T on(HostedWorld world, java.util.function.Function<World, T> task) throws Exception{
        return world.inspect(task).get(Hang.SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void eachConfigReachesEveryOtherClientOnceAndTheirGraphsAreTheServers() throws Exception{
        try(var alpha = joined("alpha"); var beta = joined("beta"); var gamma = new TestClient(server.transport().address())){
            var world = (HostedWorld)session("alpha").world();
            Fixture f = on(world, ConfigForwardServerTest::place);
            int alphaPlayer = on(world, w -> Roster.of(w).player(session("alpha").id()).id());
            var alphaSees = new Replica(on(world, World::fork));
            var betaSees = new Replica(on(world, World::fork));
            String unlinked = on(world, w -> links(w, f));

            // alpha links the nodes and the processor to its switch
            alphaSees.own(f.node(), f.other());
            alpha.tcp(Packets.TileConfig.ID, tileConfig(f.node(), f.other()));
            alphaSees.own(f.processor(), f.target());
            alpha.tcp(Packets.TileConfig.ID, tileConfig(f.processor(), f.target()));
            for(int i = 0; i < 2; i++){
                byte[] forward = beta.payload(Packets.TILE_CONFIG);
                assertEquals(alphaPlayer, new Reads(forward).i(), "the forward names its sender");
                betaSees.received(forward);
            }
            assertFalse(beta.receives(Packets.TILE_CONFIG, 500), "one forward per config");
            assertFalse(alpha.receives(Packets.TILE_CONFIG, 0), "the sender ran its own");
            String linked = on(world, w -> links(w, f));
            assertNotEquals(unlinked, linked, "the configs linked something");
            assertEquals(linked, links(alphaSees.world(), f), "alpha's links and graphs");
            assertEquals(linked, links(betaSees.world(), f), "beta's links and graphs");

            // a server-side configure (configureAny, logic, a plugin) unlinks the nodes: once, to everyone
            on(world, w -> {
                w.buildings().configure(at(w, f.node()), null, f.other(), Cause.SERVER);
                return null;
            });
            for(var client : List.of(alpha, beta)){
                byte[] call = client.payload(Packets.TILE_CONFIG);
                assertEquals(-1, new Reads(call).i(), "configureAny names no player");
                (client == alpha ? alphaSees : betaSees).received(call);
            }
            assertFalse(alpha.receives(Packets.TILE_CONFIG, 500));
            assertFalse(beta.receives(Packets.TILE_CONFIG, 0));
            String serverUnlinked = on(world, w -> links(w, f));
            assertEquals(serverUnlinked, links(alphaSees.world(), f));
            assertEquals(serverUnlinked, links(betaSees.world(), f));

            // a node's link set (Point2[], relative), as a client sends one: it relinks the nodes
            var linkSet = new Point2[]{new Point2(Point2.x(f.other()) - Point2.x(f.node()), 0)};
            alphaSees.own(f.node(), linkSet);
            alpha.tcp(Packets.TileConfig.ID, tileConfig(f.node(), linkSet));
            betaSees.received(beta.payload(Packets.TILE_CONFIG));
            assertFalse(beta.receives(Packets.TILE_CONFIG, 500));
            assertFalse(alpha.receives(Packets.TILE_CONFIG, 0));
            String relinked = on(world, w -> links(w, f));
            assertNotEquals(serverUnlinked, relinked, "the link set linked the nodes");
            assertEquals(relinked, links(alphaSees.world(), f));
            assertEquals(relinked, links(betaSees.world(), f));
            serverUnlinked = relinked;

            // gamma loads the world; alpha unlinks the processor meanwhile, and gamma gets it once after its confirm
            gamma.connect("gamma");
            gamma.world();
            assertEquals(SessionState.LOADING, session("gamma").state());
            var gammaSees = new Replica(on(world, World::fork));
            alphaSees.own(f.processor(), f.target());
            alpha.tcp(Packets.TileConfig.ID, tileConfig(f.processor(), f.target()));
            betaSees.received(beta.payload(Packets.TILE_CONFIG));
            assertFalse(gamma.receives(Packets.TILE_CONFIG, 500), "held while gamma loads");
            gamma.confirm();
            ServerTest.await("gamma to play", () -> session("gamma").state() == SessionState.IN_WORLD);
            gammaSees.received(gamma.payload(Packets.TILE_CONFIG));
            assertFalse(gamma.receives(Packets.TILE_CONFIG, 500), "one buffered forward");
            assertFalse(alpha.receives(Packets.TILE_CONFIG, 0));
            assertFalse(beta.receives(Packets.TILE_CONFIG, 0));
            String flushed = on(world, w -> links(w, f));
            assertNotEquals(serverUnlinked, flushed);
            for(var replica : List.of(alphaSees, betaSees, gammaSees)) assertEquals(flushed, links(replica.world(), f));
        }
    }

    static byte[] object(Object value){
        var out = new Writes();
        WireObjects.writeObjectSafe(out, value);
        return out.toByteArray();
    }

    /** A value a client sends, and the one vanilla's forward writes: {@code writeObject} of what {@code readObjectSafe} read. */
    record Sample(String name, byte[] sent, byte[] forwarded){
        static Sample same(String name, Object value){
            return new Sample(name, object(value), object(value));
        }

        static Sample as(String name, Object sent, Object forwarded){
            return new Sample(name, object(sent), object(forwarded));
        }
    }

    /**
     * Review 39's minor finding: every tag {@code readObjectSafe} admits, nested in an object array too, is admitted
     * with no failed verdict and forwarded once, as vanilla writes what it read: a reference to nothing and a legacy
     * value as null, a building by its origin, a unit command as content. The router they configure takes none of
     * them and is left as it was.
     */
    @Test
    void everySafeValueIsForwardedOnceAsVanillaWritesIt() throws Exception{
        try(var delta = joined("delta"); var epsilon = joined("epsilon")){
            var world = (HostedWorld)session("delta").world();
            int deltaPlayer = on(world, w -> Roster.of(w).player(session("delta").id()).id());
            int[] at = on(world, w -> {
                var tiles = w.tiles();
                for(int y = 2; y < tiles.height() - 6; y++){
                    for(int x = 2; x < tiles.width() - 8; x++){
                        if(!free(w, x, y, 8, 3)) continue;
                        Building router = w.buildings().setBlock(x, y, w.content().block("router"), 1, 0, Cause.SERVER);
                        Building container = w.buildings().setBlock(x + 2, y, w.content().block("container"), 1, 0, Cause.SERVER);
                        var unit = com.xpdustry.shard.sim.unit.Unit.spawn(w, w.content().unit("dagger"), 1, (x + 6) * 8f, y * 8f, 0f);
                        return new int[]{router.pos(), container.pos(), Point2.pack(container.tileX() + 1, container.tileY() + 1), Point2.pack(x + 7, y + 2), unit.id()};
                    }
                }
                throw new IllegalStateException("no free area");
            });
            int router = at[0], container = at[1], containerCorner = at[2], empty = at[3], unit = at[4];
            assertNotEquals(container, containerCorner);
            int copper = on(world, w -> w.content().item("copper").id());
            int planet = ContentType.VANILLA_NAMES.indexOf("planet"), command = ContentType.VANILLA_NAMES.indexOf("unitCommand");
            var vecs = new Codecs.Vec2[]{new Codecs.Vec2(1f, 2f), new Codecs.Vec2(-3f, 0.5f)};
            var points = new Point2[]{new Point2(1, 0), new Point2(0, -2)};
            Object[] nested = {null, 7, "s", true, 1.5d, new WireObjects.BuildingRef(containerCorner), new WireObjects.UnitIdRef(unit),
                new WireObjects.UnitIdRef(999_999), new WireObjects.TeamRef(3), new WireObjects.LAccessRef(5), new WireObjects.ContentRef(0, copper),
                new WireObjects.TechNodeRef(0, copper), new int[]{4, 5}, new Codecs.Vec2(1f, 1f), new Point2(2, 3), new WireObjects.UnitCommandRef(0)};
            Object[] nestedForwarded = nested.clone();
            nestedForwarded[5] = new WireObjects.BuildingRef(container);
            nestedForwarded[7] = null;
            nestedForwarded[15] = new WireObjects.ContentRef(command, 0);
            var samples = List.of(
                Sample.same("null", null),
                Sample.same("int", 7),
                Sample.same("long", 1L << 40),
                Sample.same("float", 2.5f),
                Sample.same("string", "hi"),
                new Sample("no string", new byte[]{WireObjects.STRING, 0}, object(null)),
                Sample.same("content", new WireObjects.ContentRef(0, copper)),
                Sample.as("no content", new WireObjects.ContentRef(0, 9_999), null),
                Sample.same("a planet", new WireObjects.ContentRef(planet, 1)),
                Sample.same("IntSeq", new WireObjects.IntSeqValue(new int[]{1, 2, 3})),
                Sample.same("Point2", new Point2(3, 4)),
                Sample.same("Point2[]", points),
                Sample.same("tech node", new WireObjects.TechNodeRef(0, copper)),
                Sample.same("boolean", true),
                Sample.same("double", 1.5d),
                Sample.same("building", new WireObjects.BuildingRef(router)),
                Sample.as("a building's corner", new WireObjects.BuildingRef(containerCorner), new WireObjects.BuildingRef(container)),
                Sample.as("no building", new WireObjects.BuildingRef(empty), null),
                Sample.same("sensor", new WireObjects.LAccessRef(5)),
                Sample.same("bytes", new byte[]{1, 2, 3}),
                new Sample("legacy", new byte[]{WireObjects.LEGACY, 9}, object(null)),
                Sample.same("boolean[]", new boolean[]{true, false}),
                Sample.same("unit", new WireObjects.UnitIdRef(unit)),
                Sample.as("no unit", new WireObjects.UnitIdRef(999_999), null),
                Sample.same("Vec2[]", vecs),
                Sample.same("Vec2", new Codecs.Vec2(0.25f, 8f)),
                Sample.same("team", new WireObjects.TeamRef(2)),
                Sample.same("int[]", new int[]{5, 6}),
                Sample.as("Object[]", nested, nestedForwarded),
                Sample.as("unit command", new WireObjects.UnitCommandRef(0), new WireObjects.ContentRef(command, 0)));
            long logged = on(world, w -> w.log().position());
            for(Sample sample : samples){
                var payload = new Writes();
                payload.i(router);
                payload.b(sample.sent());
                delta.tcp(Packets.TileConfig.ID, payload.toByteArray());
                var expected = new Writes();
                expected.i(deltaPlayer);
                expected.i(router);
                expected.b(sample.forwarded());
                byte[] forwarded = epsilon.payload(Packets.TILE_CONFIG);
                assertArrayEquals(expected.toByteArray(), forwarded, () -> sample.name() + ": " + HexFormat.of().formatHex(forwarded));
            }
            assertFalse(epsilon.receives(Packets.TILE_CONFIG, 500), "one forward each");
            assertFalse(delta.receives(Packets.TILE_CONFIG, 0), "no rollback, no echo");
            List<String> verdicts = on(world, w -> w.log().since(logged).stream()
                .filter(e -> e.input() instanceof IntentEnvelope envelope && envelope.payload() instanceof BlockIntents.TileConfig)
                .map(e -> e.verdict().toString()).toList());
            assertEquals(Collections.nCopies(samples.size(), Verdict.ADMITTED.toString()), verdicts);
            assertNull(on(world, w -> at(w, router).config()), "the router took none of them");
        }
    }

    /** The Calls the built-in mappings make of {@code event}, as {@code "all <packet>"} or {@code "except <session> <packet>"}. */
    static List<String> calls(World world, WorldEvent event){
        var out = new ArrayList<String>();
        var context = new CallContext(){
            @Override
            public World world(){
                return world;
            }

            @Override
            public int player(long session){
                return -1;
            }

            @Override
            public void healthChanged(int pos){
            }
        };
        CallMappings.builtIn().map(event, context, new CallSink(){
            @Override
            public void all(Packet packet){
                out.add("all " + packet);
            }

            @Override
            public void except(long session, Packet packet){
                out.add("except " + session + " " + packet);
            }

            @Override
            public void team(int team, Packet packet){
                out.add("team " + team + " " + packet);
            }

            @Override
            public void at(float x, float y, Packet packet){
                out.add("at " + packet);
            }

            @Override
            public void only(long session, Packet packet){
                out.add("only " + session + " " + packet);
            }
        });
        return out;
    }
}
