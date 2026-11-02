package com.xpdustry.shard.compat.v160.sync;

import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The batching rule, the health queue and the Call mapping registry, without vanilla. */
class SyncCodecsTest{
    static final Epoch EPOCH = Epoch.vanilla();

    @Test
    void aBatchClosesOnlyOncePast800Bytes(){
        var batch = new Batch();
        for(int i = 0; i < 20; i++){
            batch.out().b(new byte[100]);
            batch.end();
        }
        List<Batch.Chunk> chunks = batch.finish();
        // 9 entries of 100 B make 900 > 800: vanilla checks after each entry, so a packet is 800 B plus one entry
        assertEquals(List.of(9, 9, 2), chunks.stream().map(Batch.Chunk::amount).toList());
        assertEquals(List.of(900, 900, 200), chunks.stream().map(c -> c.data().length).toList());
        assertEquals(List.of(), new Batch().finish(), "nothing to send");
        var exact = new Batch();
        for(int i = 0; i < 8; i++){
            exact.out().b(new byte[100]);
            exact.end();
        }
        assertEquals(List.of(8), exact.finish().stream().map(Batch.Chunk::amount).toList(), "exactly 800 doesn't close");
    }

    @Test
    void theGameStateWritesTheFirstCoresItemsInIdOrder(){
        int[] items = new int[EPOCH.items()];
        items[0] = 5;
        items[3] = 700;
        var state = new GameState(120f, 3, 2, false, false, 0, 61, 1L, 2L, List.of(new GameState.Core(1, items), new GameState.Core(2, new int[EPOCH.items()])));
        Packets.StateSnapshot packet = state.packet();
        assertArrayEquals(new byte[]{2, 1, 0, 2, 0, 0, 0, 0, 0, 5, 0, 3, 0, 0, 2, (byte)0xbc, 2, 0, 0}, packet.coreData());
        assertEquals(61, packet.tps());
        assertEquals(255, new GameState(0f, 1, 0, false, false, 0, 1000, 0L, 0L, List.of()).packet().tps() & 0xff, "at most 255");
    }

    World world(){
        var world = new World(WorldConfig.of(EPOCH, 32, 32, 1).withKinds(KindRegistry.builtIn().withPassthrough()));
        world.buildings().beginLoad();
        for(int pos = 0; pos < world.tiles().size(); pos++) world.tiles().floor(pos, EPOCH.block("stone").id());
        world.buildings().endLoad(false);
        return world;
    }

    @Test
    void healthBatchesReadHealthWhenSentAndSkipGoneBuildings(){
        World world = world();
        var health = new HealthUpdates();
        var routers = new ArrayList<Building>();
        for(int i = 0; i < 120; i++){
            Building router = world.buildings().setBlock(i % 30, i / 30, EPOCH.block("router"), 1, 0, Cause.SERVER);
            routers.add(router);
            health.changed(world, router.tile());
            health.changed(world, router.tile()); // a set: once
        }
        routers.getFirst().damage(3f);
        world.buildings().kill(routers.get(1), Cause.SERVER);
        assertTrue(health.pending());
        List<Packets.BuildHealthUpdate> packets = health.drain(world);
        assertFalse(health.pending());
        // 119 standing: a packet closes at 200 ints (100 pairs)
        assertEquals(List.of(200, 38), packets.stream().map(p -> p.buildings().length).toList());
        var pairs = new TreeMap<Integer, Integer>();
        for(var packet : packets) for(int i = 0; i < packet.buildings().length; i += 2) pairs.put(packet.buildings()[i], packet.buildings()[i + 1]);
        assertEquals(Float.floatToRawIntBits(routers.getFirst().maxHealth - 3f), pairs.get(routers.getFirst().pos()), "the health now");
        assertFalse(pairs.containsKey(routers.get(1).pos()), "destroyed: skipped");
        // the order is Arc's IntSet's, over Point2-packed positions
        var arc = new com.xpdustry.shard.common.struct.IntSet();
        for(Building router : routers) arc.add(router.pos());
        var expected = new ArrayList<Integer>();
        arc.each(pos -> {
            if(pos != routers.get(1).pos()) expected.add(pos);
        });
        var actual = new ArrayList<Integer>();
        for(var packet : packets) for(int i = 0; i < packet.buildings().length; i += 2) actual.add(packet.buildings()[i]);
        assertEquals(expected, actual);
    }

    record Probe(int value) implements WorldEvent{
    }

    sealed interface Family extends WorldEvent{
        record One() implements Family{
        }
    }

    static final class Recording implements CallSink{
        final List<String> calls = new ArrayList<>();

        @Override
        public void all(Packet packet){
            calls.add("all " + packet);
        }

        @Override
        public void except(long session, Packet packet){
            calls.add("except " + session + " " + packet);
        }

        @Override
        public void team(int team, Packet packet){
            calls.add("team " + team + " " + packet);
        }

        @Override
        public void at(float x, float y, Packet packet){
            calls.add("at " + packet);
        }

        @Override
        public void only(long session, Packet packet){
            calls.add("only " + session + " " + packet);
        }
    }

    static CallContext context(World world){
        return new CallContext(){
            @Override
            public World world(){
                return world;
            }

            @Override
            public int player(long session){
                return (int)session + 100;
            }

            @Override
            public void healthChanged(int pos){
            }
        };
    }

    @Test
    void mappingsAreFoundByClassOrFamilyAndEventsWithoutOneSendNothing(){
        var family = new CallMapping<Family>(){
            @Override
            public Class<Family> event(){
                return Family.class;
            }

            @Override
            public void map(Family event, CallContext context, CallSink sink){
                sink.team(4, new Packets.UnitDeath(1));
            }
        };
        var probe = new CallMapping<Probe>(){
            @Override
            public Class<Probe> event(){
                return Probe.class;
            }

            @Override
            public void map(Probe event, CallContext context, CallSink sink){
                sink.only(event.value(), new Packets.UnitDeath(event.value()));
            }
        };
        CallMappings mappings = CallMappings.of(List.of(family, probe));
        var sink = new Recording();
        assertTrue(mappings.map(new Family.One(), context(null), sink));
        assertTrue(mappings.map(new Probe(2), context(null), sink));
        assertFalse(mappings.map(new TileEvent.Changed(0, 0, 0, 0, Cause.SERVER), context(null), sink), "an observation has no Call");
        assertEquals(List.of("team 4 UnitDeath[uid=1]", "only 2 UnitDeath[uid=2]"), sink.calls);
        assertThrows(IllegalArgumentException.class, () -> CallMappings.of(List.of(probe, probe)));
    }

    @Test
    void theBuiltInMappingsCoverUnitsAndBuildings(){
        World world = world();
        var sink = new Recording();
        CallMappings mappings = CallMappings.builtIn();
        mappings.map(new UnitEvent.Death(5), context(world), sink);
        mappings.map(new UnitEvent.Despawn(6), context(world), sink);
        int pos = world.tiles().pos(3, 4);
        mappings.map(new BuildingEvent.Destroyed(0, pos, 1, 1, Cause.SERVER), context(world), sink);
        // a client's own config: its intent forwards it, so the event is no Call (review 39's blocker)
        mappings.map(new BuildingEvent.Configured(0, pos, 1, 7, new Cause.Player(9), true), context(world), sink);
        // a server's configs go to everyone, a player's cause included (configureAny while their action runs)
        mappings.map(new BuildingEvent.Configured(0, pos, 1, 7, new Cause.Player(9), false), context(world), sink);
        mappings.map(new BuildingEvent.Configured(0, pos, 1, 7, Cause.SERVER, false), context(world), sink);
        int packed = com.xpdustry.shard.common.geom.Point2.pack(3, 4);
        assertEquals(List.of("all UnitDeath[uid=5]", "all UnitDespawn[unit=UnitRef[kind=2, ref=6]]", "all BuildDestroyed[build=" + packed + "]",
            "all TileConfig[player=-1, build=" + packed + ", value=7]", "all TileConfig[player=-1, build=" + packed + ", value=7]"), sink.calls);
        assertFalse(mappings.maps(TileEvent.Changed.class));
        assertTrue(mappings.maps(UnitEvent.CapDeath.class));
    }

    @Test
    void frozenValuesAreWrittenAsTypeIOWritesWhatTheyStandFor(){
        World world = world();
        int packed = com.xpdustry.shard.common.geom.Point2.pack(3, 4);
        byte[] code = {1, 2};
        Object frozen = ConfigValue.of(code);
        code[0] = 9;
        assertArrayEquals(written(new byte[]{1, 2}), written(WireValues.of(frozen, world)), "the event kept a copy");
        assertArrayEquals(written(new WireObjects.IntSeqValue(new int[]{5})), written(WireValues.of(ConfigValue.of(new int[]{5}), world)));
        assertArrayEquals(written(new Codecs.Vec2(1f, 2f)), written(WireValues.of(ConfigValue.of(new com.xpdustry.shard.common.geom.Vec2(1f, 2f)), world)));
        var points = new com.xpdustry.shard.common.geom.Point2[]{new com.xpdustry.shard.common.geom.Point2(1, 1)};
        assertArrayEquals(written(points), written(WireValues.of(ConfigValue.of(points), world)));
        // TypeIO refuses arrays in arrays, so an object array holds the rest
        Object values = ConfigValue.of(new Object[]{null, new ConfigValue.BuildingAt(world.tiles().pos(3, 4)), new ConfigValue.EntityId(42),
            new ConfigValue.Team(2), com.xpdustry.shard.logic.lang.LAccess.health, ContentRef.item(3), 7.5d, "text"});
        Object[] expected = {null, new WireObjects.BuildingRef(packed), new WireObjects.UnitIdRef(42), new WireObjects.TeamRef(2),
            new WireObjects.LAccessRef(com.xpdustry.shard.logic.lang.LAccess.health.ordinal()), new WireObjects.ContentRef(0, 3), 7.5d, "text"};
        assertArrayEquals(written(expected), written(WireValues.of(values, world)));
        assertNull(WireValues.of(null, world));
        // review 39: the values only a client's config carries keep their tags
        var vecs = new com.xpdustry.shard.common.geom.Vec2[]{new com.xpdustry.shard.common.geom.Vec2(1f, 2f), new com.xpdustry.shard.common.geom.Vec2(3f, 4f)};
        assertArrayEquals(written(new Codecs.Vec2[]{new Codecs.Vec2(1f, 2f), new Codecs.Vec2(3f, 4f)}), written(WireValues.of(ConfigValue.of(vecs), world)), "tag 18");
        assertArrayEquals(written(new int[]{4, 5}), written(WireValues.of(ConfigValue.of(new ConfigValue.IntArray(new int[]{4, 5})), world)), "tag 21");
        assertArrayEquals(written(new WireObjects.TechNodeRef(0, 3)), written(WireValues.of(ConfigValue.of(new ConfigValue.TechNode(ContentRef.item(3))), world)));
        assertArrayEquals(written(new WireObjects.ContentRef(16, 1)), written(WireValues.of(ConfigValue.of(new ContentRef.Foreign(16, 1)), world)));
        assertArrayEquals(written(new Object[]{new int[]{6}, new WireObjects.TeamRef(4)}),
            written(WireValues.of(ConfigValue.of(new Object[]{new ConfigValue.IntArray(new int[]{6}), new ConfigValue.Team(4)}), world)), "an int[] nests");
    }

    static byte[] written(Object value){
        var out = new com.xpdustry.shard.common.io.Writes();
        WireObjects.writeObject(out, value);
        return out.toByteArray();
    }

    @Test
    void processorCallsGoToEveryone(){
        World world = world();
        var sink = new Recording();
        CallMappings mappings = CallMappings.builtIn();
        int pos = world.tiles().pos(3, 4), packed = com.xpdustry.shard.common.geom.Point2.pack(3, 4);
        mappings.map(new com.xpdustry.shard.sim.logic.LogicEvent.SyncVariable(0, pos, 3, new ConfigValue.Team(2)), context(world), sink);
        mappings.map(new com.xpdustry.shard.sim.logic.LogicEvent.SetFlag(0, "boss", true), context(world), sink);
        mappings.map(new com.xpdustry.shard.sim.logic.LogicEvent.SetTile(0, pos, 5, 2, 1), context(world), sink);
        mappings.map(new com.xpdustry.shard.sim.logic.LogicEvent.SetFloor(0, pos, 6, 0), context(world), sink);
        mappings.map(new com.xpdustry.shard.sim.logic.LogicEvent.SetOverlay(0, pos, 7), context(world), sink);
        mappings.map(new com.xpdustry.shard.sim.logic.LogicEvent.SetMapArea(0, 1, 2, 30, 40), context(world), sink);
        assertEquals(List.of("all SyncVariable[building=" + packed + ", variable=3, value=TeamRef[id=2]]", "all SetFlag[flag=boss, add=true]",
            "all SetTile[tile=" + packed + ", block=5, team=2, rotation=1]", "all SetFloor[tile=" + packed + ", floor=6, overlay=0]",
            "all SetOverlay[tile=" + packed + ", overlay=7]", "all SetMapArea[x=1, y=2, w=30, h=40]"), sink.calls);
    }

    @Test
    void coreAndGameCallsGoToEveryone(){
        World world = world();
        var sink = new Recording();
        CallMappings mappings = CallMappings.builtIn();
        int pos = world.tiles().pos(3, 4), packed = com.xpdustry.shard.common.geom.Point2.pack(3, 4);
        mappings.map(new com.xpdustry.shard.sim.blocks.storage.CoreEvent.PlayerSpawn(0, pos, 12, 40), context(world), sink);
        mappings.map(new com.xpdustry.shard.sim.blocks.storage.CoreEvent.SetTile(0, pos, 339, 2, 0), context(world), sink);
        mappings.map(new com.xpdustry.shard.sim.game.GameEvent.GameOver(0, 2), context(world), sink);
        assertEquals(List.of("all PlayerSpawn[tile=" + packed + ", player=12]", "all SetTile[tile=" + packed + ", block=339, team=2, rotation=0]",
            "all UpdateGameOver[winner=2]"), sink.calls);
    }
}
