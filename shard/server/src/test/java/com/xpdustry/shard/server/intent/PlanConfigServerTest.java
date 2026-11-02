package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.*;
import com.xpdustry.shard.server.*;
import com.xpdustry.shard.server.host.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.sim.blocks.logic.*;
import com.xpdustry.shard.sim.build.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.logic.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review 40, on the real server: construction applies a plan's processor code only through the bounds and the tick's
 * budget direct configs go through. A code past the bounds builds the processor empty, and every client's one
 * {@code constructFinish} carries null, so they all end with the server's code; many processors finishing in a tick
 * with near-limit codes inflate no more than its budget, as the budget's own counters tell.
 */
class PlanConfigServerTest{
    @TempDir
    Path temp;

    static Server start(Path dir, String worlds) throws Exception{
        return Server.start(new Server.Options(ServerTest.configure(dir, """
            host:
              worlds: %s
            chat:
              anti-spam: false
            """.formatted(worlds)), Map.of(), false));
    }

    static Session session(Server server, String name){
        return server.sessions().all().stream().filter(s -> s.identity().plainName().equals(name)).findFirst().orElse(null);
    }

    static TestClient joined(Server server, String name) throws Exception{
        var client = new TestClient(server.transport().address());
        client.connect(name);
        client.world();
        client.confirm();
        ServerTest.await(name + " to play", () -> session(server, name) != null && session(server, name).state() == SessionState.IN_WORLD);
        return client;
    }

    static <T> T on(HostedWorld world, Function<World, T> task) throws Exception{
        return world.inspect(task).get(Hang.SECONDS, TimeUnit.SECONDS);
    }

    static Player player(World w, Server server, String name){
        return Roster.of(w).player(session(server, name).id());
    }

    /**
     * Free tiles where the team may place a micro processor, the nearest {@code count} to (x, y) and at most 18 tiles off
     * on each axis: within alpha's build range of (x, y).
     */
    static List<Integer> spots(World w, int team, int x, int y, int count){
        var out = new ArrayList<Integer>();
        BlockDef micro = w.content().block("micro-processor");
        for(int r = 1; r <= 18 && out.size() < count; r++){
            for(int dy = -r; dy <= r && out.size() < count; dy++){
                for(int dx = -r; dx <= r && out.size() < count; dx++){
                    if(Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                    if(Build.validPlace(w, micro, team, x + dx, y + dy, 0)) out.add(Point2.pack(x + dx, y + dy));
                }
            }
        }
        return out;
    }

    static int processors(World w){
        return w.content().block("micro-processor").id();
    }

    static String code(World w, int pos){
        return w.buildings().at(Point2.x(pos), Point2.y(pos)) instanceof LogicBlock logic ? logic.processor().code() : null;
    }

    @Test
    void aProcessorPlannedWithARefusedCodeIsBuiltEmptyForEveryone() throws Exception{
        try(var server = start(temp.resolve("one"), "[maze]"); var alpha = joined(server, "alpha"); var beta = joined(server, "beta")){
            var world = (HostedWorld)session(server, "alpha").world();
            assertSame(world, session(server, "beta").world());
            on(world, w -> w.part(WorldRules.class).rules.infiniteResources = true);
            ServerTest.await("alpha's unit", () -> {
                try{
                    return on(world, w -> player(w, server, "alpha").unit() != null && !player(w, server, "alpha").dead());
                }catch(Exception e){
                    throw new IllegalStateException(e);
                }
            });
            record Spot(int unit, float x, float y, int refused, int accepted){
            }
            Spot spot = on(world, w -> {
                Unit unit = player(w, server, "alpha").unit();
                List<Integer> two = spots(w, unit.team, (int)(unit.x / 8), (int)(unit.y / 8), 2);
                return new Spot(unit.id(), unit.x, unit.y, two.get(0), two.get(1));
            });
            byte[] refused = PlanConfigAdmissionTest.longNamed(), accepted = LogicConfig.compress("set a 3", List.of());
            int micro = on(world, PlanConfigServerTest::processors);
            var plans = List.of(new Codecs.Plan(false, spot.refused(), micro, 0, refused), new Codecs.Plan(false, spot.accepted(), micro, 0, accepted));
            var snapshot = new Packets.ClientSnapshot(1, spot.unit(), false, spot.x(), spot.y(), 0, 0, 0, 0, 0, 0, -1, false, false, false, true,
                (short)-1, 0, plans, spot.x(), spot.y(), 400, 300);
            WireContext wire = on(world, w -> WorldWireContext.of(w.config()));
            var out = new Writes();
            snapshot.write(out, false, wire);
            alpha.tcp(Packets.ClientSnapshot.ID, out.toByteArray());

            // each client: one constructFinish per processor, carrying what the server applied
            for(var client : List.of(alpha, beta)){
                var finishes = new TreeMap<Integer, Object>();
                for(int i = 0; i < 2; i++){
                    var finish = Packets.ConstructFinish.read(new Reads(client.payload(Packets.CONSTRUCT_FINISH)), true, wire);
                    assertNull(finishes.put(finish.tile(), finish.config()), "one per processor");
                }
                assertFalse(client.receives(Packets.CONSTRUCT_FINISH, 500), "no other");
                assertEquals(Set.of(spot.refused(), spot.accepted()), finishes.keySet());
                assertNull(finishes.get(spot.refused()), "the refused code: none");
                assertArrayEquals(accepted, (byte[])finishes.get(spot.accepted()), "the accepted code, as the plan carried it");
            }
            // what a client applies is the server's: nothing, then the accepted code
            assertEquals("", on(world, w -> code(w, spot.refused())));
            assertEquals(LogicConfig.decode(accepted).code(), on(world, w -> code(w, spot.accepted())));
        }
    }

    /** About 240 kB inflated: the most code vanilla reads, and 2,000 links with names at Shard's longest. */
    static byte[] nearLimit(){
        var links = new ArrayList<LogicConfig.Link>();
        for(int i = 0; i < 2_000; i++) links.add(new LogicConfig.Link(String.format("%064d", i), 1 + i, 0));
        return LogicConfig.compress("set a 1\n#" + "x".repeat(LogicConfig.maxByteLen - 16), links);
    }

    static long ticks(HostedWorld world) throws Exception{
        return on(world, World::ticks);
    }

    /** The builders, used on the world thread only: {@code count} flying alphas within reach of as many spots. */
    record Crew(List<Unit> units, List<Integer> spots){
    }

    static Crew crew(HostedWorld built, Server server, int count) throws Exception{
        return on(built, w -> {
            Rules rules = w.part(WorldRules.class).rules;
            rules.infiniteResources = true;
            rules.disableUnitCap = true;
            int team = player(w, server, "alpha").team;
            for(int cy = 8; cy < w.tiles().height() - 8; cy += 8){
                for(int cx = 8; cx < w.tiles().width() - 8; cx += 8){
                    List<Integer> spots = spots(w, team, cx, cy, count);
                    if(spots.size() < count) continue;
                    var units = new ArrayList<Unit>();
                    for(int i = 0; i < count; i++){
                        Unit unit = Unit.spawn(w, w.content().unit("alpha"), team, cx * 8, cy * 8, 0f);
                        unit.controller(new NoAi());
                        units.add(unit);
                    }
                    return new Crew(units, spots);
                }
            }
            throw new IllegalStateException("no free patch");
        });
    }

    /**
     * Each builder's processor, built since the last wave: counts the configured ones, clears them, and plans them
     * again with {@code config}, for the next tick to build.
     */
    static int wave(World w, Crew crew, byte[] config){
        int done = 0;
        BlockDef micro = w.content().block("micro-processor");
        for(int i = 0; i < crew.spots().size(); i++){
            int pos = crew.spots().get(i);
            String code = code(w, pos);
            if(code != null && !code.isEmpty()) done++;
            if(code != null) w.buildings().setBlock(Point2.x(pos), Point2.y(pos), w.content().block(0), VanillaIds.DERELICT, 0, Cause.SERVER);
            Builder.addBuild(crew.units().get(i), BuildPlan.place(Point2.x(pos), Point2.y(pos), 0, micro, config), true);
        }
        return done;
    }

    /** What a wave's tick built, and the config budget's counters for it. */
    record Built(int built, int configured, long configTick, int configsAdmitted, int configBytes){
    }

    @Test
    void manyProcessorsFinishingInATickShareItsBudget() throws Exception{
        try(var server = start(temp.resolve("budget"), "[maze]"); var alpha = joined(server, "alpha")){
            HostedWorld built = (HostedWorld)session(server, "alpha").world();
            byte[] config = nearLimit();
            int fits = LogicPart.CONFIG_BYTES_PER_TICK / LogicConfig.measure(config, LogicConfig.MAX_INFLATED);
            assertEquals(4, fits);
            int count = 64, waves = 6;
            Crew crew = crew(built, server, count);
            for(int i = 0; i < waves; i++){
                long tick = on(built, w -> {
                    wave(w, crew, config);
                    return w.ticks();
                });
                // the next tick builds them all
                ServerTest.await("tick " + tick, () -> {
                    try{
                        return ticks(built) > tick;
                    }catch(Exception e){
                        throw new IllegalStateException(e);
                    }
                });
                Built done = on(built, w -> {
                    int all = 0, configured = 0;
                    for(int pos : crew.spots()){
                        String code = code(w, pos);
                        if(code != null) all++;
                        if(code != null && !code.isEmpty()) configured++;
                    }
                    LogicPart logic = LogicPart.require(w);
                    return new Built(all, configured, logic.configTick(), logic.configsAdmitted(), logic.configBytes());
                });
                String what = "wave " + i + ": " + done;
                assertEquals(count, done.built(), what);
                // unbounded, the tick inflated 64 codes of 240 kB on the tick thread
                assertEquals(fits, done.configured(), what);
                assertEquals(tick, done.configTick(), what);
                assertEquals(fits, done.configsAdmitted(), what);
                assertTrue(done.configBytes() <= LogicPart.CONFIG_BYTES_PER_TICK, what);
            }
            ServerTest.await("alpha's constructFinish Calls", () -> alpha.queue(Packets.CONSTRUCT_FINISH).size() >= count * waves);
        }
    }

    /**
     * The timing beside {@link #manyProcessorsFinishingInATickShareItsBudget}'s counts, as evidence, so slow: processors
     * finishing every tick for 1.5 s leave both worlds ticking and the other world's player getting state snapshots.
     */
    @Test
    @Tag("slow")
    void manyProcessorsFinishingEveryTickLeaveBothWorldsTicking() throws Exception{
        try(var server = start(temp.resolve("two"), "[maze, fortress]"); var alpha = joined(server, "alpha"); var beta = joined(server, "beta")){
            HostedWorld built = (HostedWorld)session(server, "alpha").world(), other = (HostedWorld)session(server, "beta").world();
            assertNotSame(built, other, "the host puts the second player in the emptier world");
            byte[] config = nearLimit();
            int fits = LogicPart.CONFIG_BYTES_PER_TICK / LogicConfig.measure(config, LogicConfig.MAX_INFLATED);
            int count = 64;
            Crew crew = crew(built, server, count);
            beta.queue(Packets.STATE_SNAPSHOT).clear();

            // 90 waves, a wave per tick of the built world: a second and a half of play at 60 Hz, counted
            int most = 0, waves = 90;
            Pace pace = Pace.begin(built, other);
            for(int i = 0; i < waves; i++) most = Math.max(most, on(built, w -> wave(w, crew, config)));

            assertEquals(fits, most, "a tick's budget's worth of codes applied per tick, in " + waves + " waves");
            // unbounded, each tick read 64 codes of 240 kB on the tick thread: the built world falls far behind the other
            pace.assertKeptPace(beta.queue(Packets.STATE_SNAPSHOT).size());
            ServerTest.await("alpha's constructFinish Calls", () -> alpha.queue(Packets.CONSTRUCT_FINISH).size() >= count);
        }
    }
}
