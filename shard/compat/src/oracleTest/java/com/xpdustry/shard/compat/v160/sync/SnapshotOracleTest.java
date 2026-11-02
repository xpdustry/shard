package com.xpdustry.shard.compat.v160.sync;

import arc.util.*;
import com.xpdustry.shard.common.math.Rand;
import com.xpdustry.shard.compat.v160.entity.*;
import com.xpdustry.shard.compat.v160.world.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.building.Building;
import com.xpdustry.shard.sim.building.BuildingEvent;
import com.xpdustry.shard.content.KindKey;
import com.xpdustry.shard.sim.building.KindRegistry;
import com.xpdustry.shard.sim.logic.LogicPart;
import com.xpdustry.shard.sim.rules.Rules;
import com.xpdustry.shard.sim.rules.WorldRules;
import com.xpdustry.shard.sim.waves.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.sim.world.Observer;
import com.xpdustry.shard.sim.world.World;
import com.xpdustry.shard.wire.v160_7.*;
import mindustry.core.NetServer;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.world.blocks.*;
import mindustry.world.blocks.storage.*;
import org.junit.jupiter.api.*;

import java.lang.reflect.*;
import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/**
 * Gate (WP25): the snapshot bodies Shard writes are the ones vanilla's {@code NetServer} writes for the same world,
 * byte for byte and packet for packet, so the 800-byte batches close at the same entries:
 * <ul>
 *   <li>entity snapshots of a player and 60 units, half of them flying off with a velocity, every 12 frames for
 *   {@value #FRAMES} frames ({@code writeEntitySnapshotsAll});</li>
 *   <li>building health over three rounds of damage to 300 routers, in the order of vanilla's {@code IntSet}, which
 *   grows and is cleared between rounds ({@code NetServer.sync} with only the health timer due);</li>
 *   <li>the state snapshot of a world counting down to its first wave ({@code writeStateSnapshot}), whose logic
 *   random no processor has made yet, with vanilla's {@code GlobalVars.rand} set to the generator the world will
 *   start with;</li>
 *   <li>the state snapshot every 12 frames for {@value #FRAMES} frames while a processor draws {@code op rand}, with a
 *   nonzero universe time, byte for byte, both generators seeded as the oracle seeds vanilla's;</li>
 *   <li>block snapshots of every synced kind that has a codec ({@code writeBlockSnapshots}).</li>
 * </ul>
 * The state snapshots prove that Shard sends its world's live generator and the universe time it is given
 * ({@code SyncSettings.universeSeconds}). They don't make a Shard world's generator a vanilla server's: vanilla seeds
 * {@code GlobalVars.rand} from the clock once per JVM, Shard from the world's seed, and a stock client takes the
 * server's from each snapshot. The world stream still sends the map's seeds (review 39, W2.20's report).
 */
class SnapshotOracleTest{
    static final Epoch EPOCH = Epoch.vanilla();
    static final BuildingCodecs CODECS = BuildingCodecs.builtIn();
    static final int SIZE = 64, FRAMES = 240;

    World world;
    final List<com.xpdustry.shard.sim.unit.Unit> shardUnits = new ArrayList<>();

    @BeforeEach
    void worlds(){
        Vanilla.emptyWorld(SIZE, SIZE);
        state.rules.disableUnitCap = true;
        world = new World(WorldConfig.of(EPOCH, SIZE, SIZE, 1).withKinds(KindRegistry.builtIn().withPassthrough()));
        world.buildings().beginLoad();
        for(int pos = 0; pos < world.tiles().size(); pos++) world.tiles().floor(pos, EPOCH.block("stone").id());
        world.buildings().endLoad(false);
        world.part(WorldRules.class).rules.disableUnitCap = true;
    }

    /** A connected vanilla player and its Shard copy: health sync waits for a player, and snapshots carry it. */
    void player(){
        Player vanilla = Player.create();
        vanilla.con = Vanilla.connection();
        vanilla.name = "oracle";
        vanilla.add();
        var shard = com.xpdustry.shard.sim.unit.Player.create(world);
        shard.id(vanilla.id);
        shard.name = "oracle";
        shard.color = vanilla.color.rgba();
        shard.add();
        Vanilla.runPosted();
    }

    void unit(String type, float x, float y, float rotation, float vx, float vy){
        mindustry.gen.Unit vanilla = content.unit(type).create(Team.sharded);
        vanilla.controller(new mindustry.ai.types.NoAI());
        vanilla.set(x, y);
        vanilla.rotation = rotation;
        vanilla.vel.set(vx, vy);
        vanilla.add();
        var shard = com.xpdustry.shard.sim.unit.Unit.create(world, EPOCH.unit(type), Team.sharded.id);
        shard.id(vanilla.id);
        shard.controller(new com.xpdustry.shard.sim.unit.NoAi());
        shard.set(x, y);
        shard.rotation = rotation;
        shard.vel.set(vx, vy);
        shard.add();
        shardUnits.add(shard);
    }

    /** The packets of {@code type} vanilla sent while {@code action} ran as a server. */
    static <T> List<T> sent(Class<T> type, ThrowingRunnable action){
        return captured(type, action).stream().map(sent -> type.cast(sent.packet())).toList();
    }

    /** What vanilla sent of {@code type} while {@code action} ran as a server, with each frame serialized as it went. */
    static List<Vanilla.Capture.Sent> captured(Class<?> type, ThrowingRunnable action){
        var out = new ArrayList<Vanilla.Capture.Sent>();
        Vanilla.net(Vanilla.NetMode.server, () -> {
            Vanilla.captured().clear();
            try{
                action.run();
            }catch(Exception e){
                throw new AssertionError(e);
            }
            for(Vanilla.Capture.Sent sent : Vanilla.captured()) if(type.isInstance(sent.packet())) out.add(sent);
        });
        return out;
    }

    interface ThrowingRunnable{
        void run() throws Exception;
    }

    @Test
    void entitySnapshotsAreVanillasPacketForPacket(){
        player();
        for(int i = 0; i < 60; i++){
            boolean flying = i % 2 == 0;
            unit(flying ? "flare" : "dagger", 40f + (i % 10) * 30f, 40f + (i / 10) * 60f, i * 13f,
                flying ? 0.2f + i * 0.005f : 0f, flying ? -0.1f : 0f);
        }
        Vanilla.runPosted();
        var snapshots = new EntitySnapshots(new EntityProjector(EPOCH, CODECS));
        int compared = 0, packets = 0;
        for(int frame = 0; frame < FRAMES; frame++){
            Vanilla.frame();
            world.tick();
            if(frame % 12 != 0) continue;
            String at = "frame " + frame;
            List<EntitySnapshotCallPacket> expected = sent(EntitySnapshotCallPacket.class, netServer::writeEntitySnapshotsAll);
            List<Packets.EntitySnapshot> actual = snapshots.all(world, WorldWireContext.capture(world));
            assertEquals(expected.size(), actual.size(), at + ": packets");
            for(int i = 0; i < expected.size(); i++){
                byte[] want = expected.get(i).data, got = actual.get(i).data();
                assertEquals(expected.get(i).amount, actual.get(i).amount(), at + " packet " + i + ": entities");
                assertArrayEquals(want, got, () -> at + "\nvanilla " + Hex.of(want) + "\nshard   " + Hex.of(got));
            }
            compared++;
            packets += expected.size();
        }
        assertEquals(FRAMES / 12, compared);
        assertTrue(packets >= compared * 3, "every snapshot took several packets: " + packets);
        assertEquals(0, snapshots.skipped());
        // flares have a high drag: a few pixels, but every snapshot after the first carried new positions
        assertTrue(shardUnits.getFirst().x > 42f, "the flares moved: " + shardUnits.getFirst().x);
    }

    /** {@code unitSpawn}: every unit type, carried as vanilla's {@code UnitSyncContainer} writes it. */
    @Test
    void unitSpawnCarriesTheUnitAsVanillaWritesIt(){
        int index = 0;
        for(mindustry.type.UnitType type : content.units()){
            // a block's unit (a turret's) isn't spawned this way, and vanilla's container can't be read back as one
            if(type.create(Team.sharded) instanceof BlockUnitc) continue;
            unit(type.name, 40f + index * 3f, 60f + index, index * 7f, 0f, 0f);
            mindustry.gen.Unit vanilla = Groups.unit.getByID(shardUnits.getLast().id());
            List<String> expected = CallFrames.vanilla(sent(mindustry.gen.Call::unitSpawn, vanilla), Set.of(Packets.UnitSpawn.ID));
            List<String> actual = CallFrames.shard(world, List.of(new com.xpdustry.shard.sim.combat.CombatEvent.UnitSpawned(shardUnits.getLast().id())));
            CallFrames.same(expected, actual, type.name);
            assertEquals(1, actual.size(), type.name);
            index++;
        }
        assertTrue(index > 50, "unit types: " + index);
    }

    static List<Vanilla.Capture.Sent> sent(java.util.function.Consumer<mindustry.entities.Units.UnitSyncContainer> call, mindustry.gen.Unit unit){
        return captured(UnitSpawnCallPacket.class, () -> call.accept(new mindustry.entities.Units.UnitSyncContainer(unit)));
    }

    @Test
    void healthBatchesAreVanillasInItsOrder() throws Exception{
        player();
        var vanillaRouters = new ArrayList<mindustry.gen.Building>();
        var shardRouters = new ArrayList<Building>();
        for(int i = 0; i < 300; i++){
            int x = 2 + i % 50, y = 10 + i / 50;
            mindustry.Vars.world.tile(x, y).setBlock(content.block("router"), Team.sharded, 0);
            vanillaRouters.add(mindustry.Vars.world.build(x, y));
            shardRouters.add(world.buildings().create(EPOCH.block("router"), Team.sharded.id, x, y, 0, Cause.SERVER));
        }
        Vanilla.frame();
        world.tick();
        Observer observer = world.observe(1 << 12, List.of(BuildingEvent.HealthChanged.class), null);
        var health = new HealthUpdates();
        Method sync = NetServer.class.getDeclaredMethod("sync");
        sync.setAccessible(true);
        // {from, to, step}: every router, a scattered few, then a short run
        int[][] rounds = {{0, 300, 1}, {7, 300, 11}, {3, 40, 3}};
        for(int[] round : rounds){
            String at = "round " + Arrays.toString(round);
            // vanilla clears the IntSeq a packet holds once it is sent: compare the bodies serialized as they went
            List<Vanilla.Capture.Sent> expected = captured(BuildHealthUpdateCallPacket.class, () -> {
                for(int i = round[0]; i < round[1]; i += round[2]){
                    vanillaRouters.get(i).damage(1f + i % 3);
                    shardRouters.get(i).damage(1f + i % 3);
                }
                due("healthSyncTime", true);
                due("blockSyncTime", false);
                due("planPreviewSyncTime", false);
                netServer.snapshotSyncTime = com.xpdustry.shard.oracle.Vanilla.NOT_DUE;
                sync.invoke(netServer);
            });
            // what's published between ticks reaches observers at the end of the next one
            world.tick();
            for(WorldEvent event : observer.drain()) health.changed(world, ((BuildingEvent.HealthChanged)event).pos());
            List<Packets.BuildHealthUpdate> actual = health.drain(world);
            assertEquals(expected.size(), actual.size(), at + ": packets");
            for(int i = 0; i < expected.size(); i++){
                byte[] want = expected.get(i).frame().body(), got = body(actual.get(i), world);
                assertArrayEquals(want, got, () -> at + "\nvanilla " + Hex.of(want) + "\nshard   " + Hex.of(got));
            }
            assertTrue(expected.size() >= 1, at + ": something was sent");
        }
    }

    static byte[] body(com.xpdustry.shard.wire.Packet packet, World world){
        var out = new com.xpdustry.shard.common.io.Writes();
        packet.write(out, true, WorldWireContext.capture(world));
        return out.toByteArray();
    }

    /** Sets one of {@code NetServer}'s private {@code Timekeeper}s due now, or just reset. */
    static void due(String timer, boolean due) throws Exception{
        Field field = NetServer.class.getDeclaredField(timer);
        field.setAccessible(true);
        Object keeper = field.get(null);
        Field last = keeper.getClass().getDeclaredField("lastTime");
        last.setAccessible(true);
        last.setLong(keeper, due ? 0L : com.xpdustry.shard.oracle.Vanilla.NOT_DUE);
    }

    @Test
    void theStateSnapshotIsVanillas(){
        state.rules.waves = true;
        state.rules.waveTimer = true;
        state.rules.waveSpacing = 600f;
        state.wavetime = 1200f;
        Rules rules = world.part(WorldRules.class).rules;
        rules.waves = true;
        rules.waveTimer = true;
        rules.waveSpacing = 600f;
        WaveState waves = WaveState.of(world);
        waves.wavetime = 1200f;
        var map = new MapState();
        // the generator a world starts with, read without making it
        Rand first = LogicPart.require(world).rand();
        mindustry.logic.GlobalVars.rand.setState(first.seed0, first.seed1);
        for(int frame = 0; frame < 120; frame++){
            Vanilla.frame();
            world.tick();
            if(frame % 12 != 0) continue;
            String at = "frame " + frame;
            StateSnapshotCallPacket expected = sent(StateSnapshotCallPacket.class, netServer::writeStateSnapshot).getFirst();
            Packets.StateSnapshot actual = GameState.of(world, map, expected.tps & 0xff, 0).packet();
            assertEquals(expected.waveTime, actual.waveTime(), at + ": wavetime");
            assertEquals(expected.wave, actual.wave(), at + ": wave");
            assertEquals(expected.enemies, actual.enemies(), at + ": enemies");
            assertEquals(expected.paused, actual.paused(), at + ": paused");
            assertEquals(expected.gameOver, actual.gameOver(), at + ": gameOver");
            assertEquals(expected.timeData, actual.timeData(), at + ": universe seconds");
            assertEquals(expected.tps, actual.tps(), at + ": tps");
            assertEquals(expected.rand0, actual.rand0(), at + ": logic random");
            assertEquals(expected.rand1, actual.rand1(), at + ": logic random");
            assertArrayEquals(expected.coreData, actual.coreData(), at + ": cores");
        }
        assertTrue(waves.wavetime < 1200f - 100f, "counting down: " + waves.wavetime);
        assertEquals(first.seed0, LogicPart.require(world).rand().seed0, "no processor, so no draw");
    }

    /**
     * Review 39: a processor draws {@code op rand} twice a tick and the universe time is nonzero, so each state snapshot
     * carries another random and the time; each is vanilla's, byte for byte.
     */
    @Test
    void theStateSnapshotCarriesTheLiveRandomAndTheUniverseTime() throws Exception{
        int seconds = 4_321;
        Field field = mindustry.game.Universe.class.getDeclaredField("seconds");
        field.setAccessible(true);
        int was = field.getInt(universe);
        field.setInt(universe, seconds);
        try{
            // emptyWorld's logic.play() starts the wave countdown; GlobalVars.rand, as the oracle seeds it
            WaveState.of(world).play(world.buildings().rules());
            LogicPart.require(world).logic().rand(new Rand(0));
            String code = "op rand r 100\nop rand q 1";
            mindustry.Vars.world.tile(8, 8).setBlock(content.block("micro-processor"), Team.sharded, 0);
            mindustry.Vars.world.build(8, 8).configureAny(mindustry.world.blocks.logic.LogicBlock.compress(code, new arc.struct.Seq<>()));
            Vanilla.runPosted();
            world.buildings().setBlock(8, 8, EPOCH.block("micro-processor"), Team.sharded.id, 0, Cause.SERVER)
                .configureAny(com.xpdustry.shard.logic.LogicConfig.compress(code, List.of()));
            var map = new MapState();
            var randoms = new TreeSet<Long>();
            for(int frame = 0; frame < FRAMES; frame++){
                Vanilla.frame();
                world.tick();
                if(frame % 12 != 0) continue;
                String at = "frame " + frame;
                Vanilla.Capture.Sent expected = captured(StateSnapshotCallPacket.class, netServer::writeStateSnapshot).getFirst();
                int tps = ((StateSnapshotCallPacket)expected.packet()).tps & 0xff;
                Packets.StateSnapshot actual = GameState.of(world, map, tps, seconds).packet();
                byte[] want = expected.frame().body(), got = body(actual, world);
                assertArrayEquals(want, got, () -> at + "\nvanilla " + Hex.of(want) + "\nshard   " + Hex.of(got));
                assertEquals(seconds, actual.timeData(), at);
                randoms.add(actual.rand0());
            }
            assertEquals(FRAMES / 12, randoms.size(), "a new random in every snapshot");
        }finally{
            field.setInt(universe, was);
        }
    }

    @Test
    void blockSnapshotsAreVanillasForEverySyncedKindWithACodec(){
        KindRegistry kinds = world.kinds();
        var names = new ArrayList<String>();
        for(int id = 0; id < EPOCH.blocks(); id++){
            var block = EPOCH.block(id);
            KindKey key = kinds.key(block);
            if(kinds.tileKind(key) || kinds.passesThrough(key) || CODECS.get(key) == null) continue;
            mindustry.world.Block vanilla = content.block(block.name());
            // a core would make a team active, and a construct site isn't a placed block
            if(vanilla == null || !vanilla.sync || vanilla instanceof CoreBlock || vanilla instanceof ConstructBlock || vanilla.size > 4) continue;
            names.add(block.name());
        }
        assumeFalse(names.isEmpty(), "no synced kind has a codec yet");
        var placed = new ArrayList<String>();
        int x = 3, y = 3;
        for(String name : names){
            mindustry.Vars.world.tile(x, y).setBlock(content.block(name), Team.sharded, 0);
            world.buildings().create(EPOCH.block(name), Team.sharded.id, x, y, 0, Cause.SERVER);
            placed.add(name);
            x += 6;
            if(x >= SIZE - 3){
                x = 3;
                y += 6;
                if(y >= SIZE - 3) break;
            }
        }
        Vanilla.frame();
        world.tick();
        List<BlockSnapshotCallPacket> expected = sent(BlockSnapshotCallPacket.class, netServer::writeBlockSnapshots);
        List<Packets.BlockSnapshot> actual = new BlockSnapshots(EPOCH, CODECS).write(world);
        assertEquals(expected.size(), actual.size(), placed + ": packets");
        for(int i = 0; i < expected.size(); i++){
            byte[] want = expected.get(i).data, got = actual.get(i).data();
            assertEquals(expected.get(i).amount, actual.get(i).amount(), "packet " + i + ": blocks");
            assertArrayEquals(want, got, () -> placed + "\nvanilla " + Hex.of(want) + "\nshard   " + Hex.of(got));
        }
    }
}
