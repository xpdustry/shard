package com.xpdustry.shard.server.sync;

import arc.util.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.server.sched.*;
import com.xpdustry.shard.server.session.*;
import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.building.ContentRef;
import com.xpdustry.shard.sim.building.KindRegistry;
import com.xpdustry.shard.sim.rules.Rules;
import com.xpdustry.shard.sim.rules.WorldRules;
import com.xpdustry.shard.sim.unit.NoAi;
import com.xpdustry.shard.sim.unit.Player;
import com.xpdustry.shard.sim.unit.Unit;
import com.xpdustry.shard.sim.waves.WaveState;
import com.xpdustry.shard.sim.world.Cause;
import com.xpdustry.shard.sim.world.World;
import com.xpdustry.shard.sim.world.WorldConfig;
import com.xpdustry.shard.wire.v160_7.Packets;
import mindustry.core.NetServer;
import mindustry.game.Team;
import org.junit.jupiter.api.*;

import java.lang.reflect.*;
import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP25, Call order): what one client is sent by a vanilla server and by Shard's {@link WorldSync} over the same
 * scripted {@value #FRAMES} frames, frame by frame, in order: unit deaths, building damage and destruction, configs of
 * every value kind a server sends (content, null, boolean, string, a link), several in one frame, around the state,
 * entity, block and health snapshots. Vanilla's wall-clock timers are driven to Shard's tick schedule (the world's
 * stagger phases for the 200 ms and 6 s snapshots; 30 ticks for health, polled only while something waits), so both
 * send on the same frames. Every frame then matches byte for byte, but the state snapshot's {@code tps}, which vanilla
 * takes from the headless frame rate.
 */
class CallOrderOracleTest{
    static final Epoch EPOCH = Epoch.vanilla();
    static final long ID = 0x5eed;
    static final int SIZE = 48, FRAMES = 420;
    /** The {@code tps} byte in a state snapshot's body. */
    static final int TPS_OFFSET = 4 + 4 + 4 + 1 + 1 + 4;

    Loopback loopback;
    World world;
    Outbox outbox;
    WorldSync sync;
    Session client;
    final TreeMap<Long, Player> roster = new TreeMap<>();
    final MapState map = new MapState();
    final List<mindustry.gen.Unit> vanillaUnits = new ArrayList<>();
    final List<Unit> shardUnits = new ArrayList<>();
    final Map<Integer, List<Runnable>> script = new TreeMap<>();

    @BeforeEach
    void worlds() throws Exception{
        Vanilla.emptyWorld(SIZE, SIZE);
        state.rules.disableUnitCap = true;
        arc.Core.settings.put("blocksync", true);
        world = new World(WorldConfig.of(EPOCH, SIZE, SIZE, 1).withKinds(KindRegistry.builtIn().withPassthrough()));
        world.buildings().beginLoad();
        for(int pos = 0; pos < world.tiles().size(); pos++) world.tiles().floor(pos, EPOCH.block("stone").id());
        world.buildings().endLoad(false);
        Rules rules = world.part(WorldRules.class).rules;
        rules.disableUnitCap = true;
        // emptyWorld's logic.play() starts the wave countdown
        WaveState.of(world).play(rules);
        // the state snapshots carry the logic random: vanilla's starts where this world's will
        var first = com.xpdustry.shard.sim.logic.LogicPart.require(world).rand();
        mindustry.logic.GlobalVars.rand.setState(first.seed0, first.seed1);

        loopback = new Loopback();
        outbox = new Outbox(SyncSettings.DEFAULT, (session, load) -> fail("overflow"), loopback);
        sync = new WorldSync(ID, SyncSettings.DEFAULT, outbox, EPOCH, BuildingCodecs.builtIn());
        sync.attached(world);

        mindustry.gen.Player vanillaPlayer = mindustry.gen.Player.create();
        vanillaPlayer.con = Vanilla.connection();
        vanillaPlayer.name = "oracle";
        vanillaPlayer.add();
        client = loopback.session("oracle");
        Player player = Player.create(world);
        player.id(vanillaPlayer.id);
        player.name = "oracle";
        player.color = vanillaPlayer.color.rgba();
        player.add();
        roster.put(client.id(), player);
        outbox.member(client);
        outbox.capture(client, 1);
        outbox.confirmed(client.id(), 1);
        Vanilla.runPosted();
    }

    @AfterEach
    void close() throws Exception{
        loopback.close();
    }

    void place(String block, int x, int y){
        mindustry.Vars.world.tile(x, y).setBlock(content.block(block), Team.sharded, 0);
        world.buildings().setBlock(x, y, EPOCH.block(block), Team.sharded.id, 0, Cause.SERVER);
    }

    mindustry.gen.Building vanilla(int x, int y){
        return mindustry.Vars.world.build(x, y);
    }

    Building shard(int x, int y){
        return world.buildings().at(x, y);
    }

    void unit(String type, float x, float y){
        mindustry.gen.Unit vanilla = content.unit(type).create(Team.sharded);
        vanilla.controller(new mindustry.ai.types.NoAI());
        vanilla.set(x, y);
        vanilla.add();
        vanillaUnits.add(vanilla);
        var shard = Unit.create(world, EPOCH.unit(type), Team.sharded.id);
        shard.id(vanilla.id);
        shard.controller(new NoAi());
        shard.set(x, y);
        shard.add();
        shardUnits.add(shard);
    }

    void at(int frame, Runnable action){
        script.computeIfAbsent(frame, f -> new ArrayList<>()).add(action);
    }

    void killUnit(int frame, int index){
        at(frame, () -> {
            vanillaUnits.get(index).kill();
            shardUnits.get(index).kill();
        });
    }

    void damage(int frame, int x, int y, float amount){
        at(frame, () -> {
            vanilla(x, y).damage(amount);
            shard(x, y).damage(amount);
        });
    }

    void destroy(int frame, int x, int y){
        at(frame, () -> {
            vanilla(x, y).kill();
            world.buildings().kill(shard(x, y));
        });
    }

    /** {@code configureAny}: a server's config, sent to everyone with no player. */
    void configure(int frame, int x, int y, Object vanillaValue, Object shardValue){
        at(frame, () -> {
            vanilla(x, y).configureAny(vanillaValue);
            world.buildings().configure(shard(x, y), null, shardValue, Cause.SERVER);
        });
    }

    @Test
    void callsGoOutAsVanillasAroundTheSnapshots() throws Exception{
        for(int i = 0; i < 12; i++) place("router", 4 + i, 4);
        place("sorter", 4, 8);
        place("switch", 6, 8);
        place("message", 8, 8);
        place("power-node", 10, 8);
        place("power-node", 13, 8);
        place("combustion-generator", 16, 8);
        for(int i = 0; i < 8; i++){
            unit("dagger", 60f + i * 30f, 260f);
            unit("flare", 60f + i * 30f, 320f);
        }
        Vanilla.runPosted();

        killUnit(20, 0);
        damage(30, 4, 4, 5f);
        damage(31, 5, 4, 5f);
        damage(45, 6, 4, 5f);
        damage(61, 4, 4, 2f);
        configure(50, 4, 8, content.item("copper"), ContentRef.item(EPOCH.item("copper").id()));
        configure(51, 4, 8, null, null);
        configure(52, 6, 8, true, true);
        configure(52, 8, 8, "hello", "hello");
        configure(53, 10, 8, arc.math.geom.Point2.pack(13, 8), com.xpdustry.shard.common.geom.Point2.pack(13, 8));
        destroy(80, 7, 4);
        killUnit(95, 3);
        // several in one frame, in the order they were made
        at(120, () -> {
            vanillaUnits.get(4).kill();
            shardUnits.get(4).kill();
            vanilla(4, 8).configureAny(content.item("silicon"));
            world.buildings().configure(shard(4, 8), null, ContentRef.item(EPOCH.item("silicon").id()), Cause.SERVER);
            vanilla(8, 4).kill();
            world.buildings().kill(shard(8, 4));
            vanilla(9, 4).damage(3f);
            shard(9, 4).damage(3f);
        });
        killUnit(200, 6);
        destroy(370, 10, 4);

        Field snapshotSync = NetServer.class.getField("snapshotSyncTime");
        long lastHealth = Long.MIN_VALUE / 2;
        var counts = new TreeMap<String, Integer>();
        for(int frame = 1; frame <= FRAMES; frame++){
            int f = frame;
            boolean snapshots = Stagger.snapshotDue(ID, f, SyncSettings.DEFAULT.snapshotTicks());
            boolean blocks = Stagger.snapshotDue(ID, f, SyncSettings.DEFAULT.blockTicks());
            boolean health = f - lastHealth >= SyncSettings.DEFAULT.healthTicks();
            int before = loopback.sent.size();
            var vanillaSent = new ArrayList<Vanilla.Capture.Sent>();
            Vanilla.net(Vanilla.NetMode.server, () -> {
                Vanilla.captured().clear();
                for(Runnable action : script.getOrDefault(f, List.of())) action.run();
                try{
                    snapshotSync.setLong(netServer, snapshots ? 0L : com.xpdustry.shard.oracle.Vanilla.NOT_DUE);
                    due("blockSyncTime", blocks);
                    due("healthSyncTime", health);
                    due("planPreviewSyncTime", false);
                }catch(ReflectiveOperationException e){
                    throw new AssertionError(e);
                }
                Vanilla.frame();
                vanillaSent.addAll(Vanilla.captured());
            });
            world.tick();
            sync.beginTick(world, roster);
            assertFalse(sync.drain(), "nothing dropped");
            sync.endTick(world, map);

            List<String> expected = vanilla(vanillaSent);
            List<String> actual = shard(loopback.sent.subList(before, loopback.sent.size()));
            same(expected, actual, "frame " + f);
            for(String line : expected) counts.merge(line.substring(0, line.indexOf(' ')), 1, Integer::sum);
            if(expected.stream().anyMatch(line -> line.startsWith("BuildHealthUpdate "))) lastHealth = f;
        }
        assertEquals(0, sync.failedCalls());
        // what the script made: the gate compared each of them
        for(String name : List.of("StateSnapshot", "EntitySnapshot", "BlockSnapshot", "BuildHealthUpdate", "UnitDeath", "BuildDestroyed", "TileConfig")){
            assertTrue(counts.getOrDefault(name, 0) > 0, "no " + name + " was sent: " + counts);
        }
        assertEquals(6, counts.get("TileConfig"), counts.toString());
        assertEquals(4, counts.get("UnitDeath"), counts.toString());
        assertEquals(3, counts.get("BuildDestroyed"), counts.toString());
    }

    /** Sets one of {@code NetServer}'s private {@code Timekeeper}s due now, or just reset. */
    static void due(String timer, boolean due) throws ReflectiveOperationException{
        Field field = NetServer.class.getDeclaredField(timer);
        field.setAccessible(true);
        Object keeper = field.get(null);
        Field last = keeper.getClass().getDeclaredField("lastTime");
        last.setAccessible(true);
        last.setLong(keeper, due ? 0L : com.xpdustry.shard.oracle.Vanilla.NOT_DUE);
    }

    /** What reached the one client: everything sent to all, or to its connection. */
    static List<String> vanilla(List<Vanilla.Capture.Sent> sent){
        var out = new ArrayList<String>();
        for(Vanilla.Capture.Sent call : sent){
            if(call.frame() == null || call.target().equals("except")) continue;
            out.add(line(call.frame().id(), call.reliable(), call.frame().body()));
        }
        return out;
    }

    List<String> shard(List<Loopback.Sent> sent){
        var out = new ArrayList<String>();
        for(Loopback.Sent call : sent){
            if(call.session() != client.id()) continue;
            out.add(line(call.id(), call.reliable(), WorldSyncTest.payload(call.frame())));
        }
        return out;
    }

    static String line(int id, boolean reliable, byte[] body){
        if(id == Packets.STATE_SNAPSHOT){
            body = body.clone();
            body[TPS_OFFSET] = 0;
        }
        return Packets.TYPES.get(id).name() + (reliable ? " tcp " : " udp ") + Hex.of(body);
    }

    /** The first line that differs, whole, then the counts. */
    static void same(List<String> expected, List<String> actual, String when){
        for(int i = 0; i < Math.min(expected.size(), actual.size()); i++){
            if(!expected.get(i).equals(actual.get(i))) assertEquals(expected.get(i), actual.get(i), when + ", packet " + i + " of " + expected.size());
        }
        assertEquals(expected.size(), actual.size(), when + ": vanilla " + names(expected) + " vs Shard " + names(actual));
    }

    static List<String> names(List<String> lines){
        return lines.stream().map(line -> line.substring(0, line.indexOf(' ', line.indexOf(' ') + 1))).toList();
    }
}
