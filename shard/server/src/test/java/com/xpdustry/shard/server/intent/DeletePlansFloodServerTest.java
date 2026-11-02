package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.net.Frame;
import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.sim.team.*;
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
 * Review 40, on the real server with the interaction rate limit off: a team with 20,000 rebuild plans, and a client
 * sending {@code deletePlans} packets of the most positions a packet holds, which match no plan or repeat one tile.
 * Vanilla's handler checks every plan against every position, 164 million comparisons a packet; Shard's indexes them.
 * Neither world waits long for its next tick (catch-up ticks would hide a stall from a count of ticks), both keep
 * their tick rate, the other world's player keeps getting state snapshots, and the plans stay.
 *
 * <p>Wall-clock evidence, so slow: the gate, which counts the work a packet does, is {@link DeletePlansCostTest}.
 * With vanilla's loop in place of the index, the flooded world's longest wait was 1,544 ms.
 */
@Tag("slow")
class DeletePlansFloodServerTest{
    /** The most positions a packet holds: {@link Frame#MAX_PAYLOAD} less the count's short, in ints. */
    static final int MAX_POSITIONS = (Frame.MAX_PAYLOAD - 2) / 4;
    static final int PLANS = 20_000;

    @TempDir
    static Path temp;
    static Server server;

    @BeforeAll
    static void start() throws Exception{
        server = Server.start(new Server.Options(ServerTest.configure(temp.resolve("delete"), """
            host:
              worlds: [maze, fortress]
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

    static <T> T on(HostedWorld world, java.util.function.Function<World, T> task) throws Exception{
        return world.inspect(task).get(Hang.SECONDS, TimeUnit.SECONDS);
    }

    static int admitted(HostedWorld world) throws Exception{
        return on(world, w -> {
            int admitted = 0;
            for(int i = 0; i < w.log().size(); i++){
                if(w.log().get(i).input() instanceof IntentEnvelope envelope && envelope.payload() instanceof PlayerIntents.DeletePlans) admitted++;
            }
            return admitted;
        });
    }

    static byte[] deletePlans(int[] positions){
        var out = new Writes();
        new Packets.DeletePlans(-1, positions).write(out, false, null);
        return out.toByteArray();
    }

    @Test
    void floodsOfPositionsLeaveBothWorldsTicking() throws Exception{
        try(var alpha = joined("alpha"); var beta = joined("beta")){
            var flooded = (HostedWorld)session("alpha").world();
            var other = (HostedWorld)session("beta").world();
            assertNotSame(flooded, other, "the host puts the second player in the emptier world");
            int team = on(flooded, w -> {
                int t = Roster.of(w).player(session("alpha").id()).team;
                List<BlockPlan> plans = Teams.of(w).get(t).plans();
                int block = w.content().block("conveyor").id();
                for(int i = 0; i < PLANS; i++) plans.add(new BlockPlan(i % 200, i / 200, 0, block, null));
                return t;
            });
            beta.queue(Packets.STATE_SNAPSHOT).clear();

            // positions past every plan's tile, and one tile over and over; repeating, so they compress into a client's
            // frame (Frame.MAX_SERVER_BODY)
            int[] missing = new int[MAX_POSITIONS], repeated = new int[MAX_POSITIONS];
            for(int i = 0; i < MAX_POSITIONS; i++) missing[i] = Point2.pack(300 + i % 64, 300);
            Arrays.fill(repeated, Point2.pack(400, 400));
            byte[] first = deletePlans(missing), second = deletePlans(repeated);
            assertTrue(first.length <= Frame.MAX_PAYLOAD, first.length + " bytes");
            int count = 48;
            Pace pace = Pace.begin(flooded, other);
            for(int i = 0; i < count; i++) alpha.tcp(Packets.DeletePlans.ID, i % 2 == 0 ? first : second);
            // until every packet is admitted, then play on, counted in ticks
            ServerTest.await("every packet to be admitted", () -> {
                try{
                    return admitted(flooded) >= count;
                }catch(Exception e){
                    throw new AssertionError(e);
                }
            });
            pace.play();

            int left = on(flooded, w -> Teams.of(w).get(team).plans().size());
            assertEquals(PLANS, left, "no position matched a plan");
            assertEquals(SessionState.IN_WORLD, session("alpha").state(), "not kicked");
            // unbounded, a tick admitting 16 of them takes seconds: the flooded world falls far behind the other
            pace.assertKeptPace(beta.queue(Packets.STATE_SNAPSHOT).size());
        }
    }
}
