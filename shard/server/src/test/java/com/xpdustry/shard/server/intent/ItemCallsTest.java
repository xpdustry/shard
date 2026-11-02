package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.compat.v160.sync.*;
import com.xpdustry.shard.logic.*;
import com.xpdustry.shard.server.intent.action.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.sim.world.Observer;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Each item transfer sends its Call exactly once, whichever path moves the items: a player's {@code requestItem} and
 * {@code transferInventory} (W2.14's intents), a processor's {@code ucontrol itemTake} and {@code itemDrop} (W2.23's
 * statements), and a mono's deposits ({@code MinerAI}, {@code MinerComp}). They all go through {@link ItemTransfers}.
 * Every event a tick publishes goes through the built-in Call mappings, with the intents' own Calls, as the server
 * sends them. So a second event for one transfer, or a mapping left in another family, would count the transfer twice:
 * each tick, the amounts the Calls carry into or out of a building must be what it gained or lost.
 */
class ItemCallsTest{
    static final Policy POLICY = new Policy(true, false, 6_000, 25, 60);
    static final long ALPHA = 1;
    static final int TEAM = 1;

    IntentWorld w;
    Observer events;
    int copper;

    @BeforeEach
    void world(){
        w = new IntentWorld(64, 64);
        events = w.world.observe(1 << 16);
        copper = IntentWorld.item("copper").id();
    }

    /** The Calls of the ticks since the last call, in order: the world's events mapped, and the intents' own. */
    List<Packet> calls(){
        var out = new ArrayList<Packet>();
        CallContext context = new CallContext(){
            @Override
            public World world(){
                return w.world;
            }

            @Override
            public int player(long session){
                return -1;
            }

            @Override
            public void healthChanged(int pos){
            }
        };
        CallSink sink = new CallSink(){
            @Override
            public void all(Packet packet){
                out.add(packet);
            }

            @Override
            public void except(long session, Packet packet){
                out.add(packet);
            }

            @Override
            public void team(int team, Packet packet){
                out.add(packet);
            }

            @Override
            public void at(float x, float y, Packet packet){
                out.add(packet);
            }

            @Override
            public void only(long session, Packet packet){
                out.add(packet);
            }
        };
        var intents = new ArrayList<IntentEvent>();
        for(WorldEvent event : events.drain()){
            if(event instanceof IntentEvent intent) intents.add(intent);
            else CallMappings.builtIn().map(event, context, sink);
        }
        out.addAll(IntentWorld.calls(intents, Packet.class));
        return out;
    }

    static long count(List<Packet> calls, Class<? extends Packet> type){
        return calls.stream().filter(type::isInstance).count();
    }

    /** What the Calls of {@code type} carry into ({@code TransferItemTo}) or out of ({@code TakeItems}) {@code build}. */
    static int carried(List<Packet> calls, Class<? extends Packet> type, Building build){
        int sum = 0;
        for(Packet call : calls){
            if(type == Packets.TransferItemTo.class && call instanceof Packets.TransferItemTo to && to.build() == build.pos()) sum += to.amount();
            if(type == Packets.TakeItems.class && call instanceof Packets.TakeItems take && take.build() == build.pos()) sum += take.amount();
        }
        return sum;
    }

    @Test
    void aPlayersRequestAndDepositAreOneCallEach(){
        Building container = w.place("container", 14, 12, TEAM, 0);
        container.items.set(copper, 100);
        w.joinWith(ALPHA, "alpha", "alpha", 14 * 8f, 15 * 8f);
        calls();

        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, new ItemIntents.RequestItem(POLICY, container.pos(), copper, 10), 1_000));
        List<Packet> calls = calls();
        assertEquals(1, count(calls, Packets.TakeItems.class), "one takeItems: " + calls);
        assertEquals(1, count(calls, Packets.RequestItem.class), "the request forwarded once: " + calls);
        assertEquals(10, carried(calls, Packets.TakeItems.class, container));
        assertEquals(90, container.items.get(copper));

        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, new ItemIntents.TransferInventory(POLICY, container.pos()), 2_000));
        calls = calls();
        assertEquals(1, count(calls, Packets.TransferItemTo.class), "one transferItemTo: " + calls);
        assertEquals(1, count(calls, Packets.TransferInventory.class), "the deposit forwarded once: " + calls);
        assertEquals(10, carried(calls, Packets.TransferItemTo.class, container));
        assertEquals(100, container.items.get(copper));
    }

    @Test
    void aProcessorsTakesAndDropsAreOneCallEach(){
        Building from = w.place("container", 20, 20, TEAM, 0), to = w.place("container", 24, 20, TEAM, 0);
        from.items.set(copper, 300);
        Building processor = w.place("micro-processor", 22, 24, TEAM, 0);
        processor.configureAny(LogicConfig.compress("""
            ubind @mono
            ucontrol itemTake container1 @copper 10 0 0
            ucontrol itemDrop container2 10 0 0
            """, List.of()));
        processor.configureAny(Point2.pack(20, 20));
        processor.configureAny(Point2.pack(24, 20));
        Unit.spawn(w.world, IntentWorld.VANILLA.unit("mono"), TEAM, 22 * 8f, 21 * 8f, 0f);
        calls();
        int took = 0, dropped = 0;
        for(int tick = 0; tick < 600; tick++){
            int fromBefore = from.items.get(copper), toBefore = to.items.get(copper);
            w.world.tick();
            List<Packet> calls = calls();
            int out = carried(calls, Packets.TakeItems.class, from), in = carried(calls, Packets.TransferItemTo.class, to);
            assertEquals(fromBefore - from.items.get(copper), out, "takeItems at tick " + tick + ": " + calls);
            assertEquals(to.items.get(copper) - toBefore, in, "transferItemTo at tick " + tick + ": " + calls);
            took += (int)count(calls, Packets.TakeItems.class);
            dropped += (int)count(calls, Packets.TransferItemTo.class);
        }
        assertTrue(took >= 2 && dropped >= 2, "the processor moved items: took " + took + ", dropped " + dropped);
    }

    @Test
    void aMonosDepositsAreOneCallEach(){
        Building core = w.place("core-shard", 20, 20, TEAM, 0);
        for(int x = 27; x < 30; x++){
            for(int y = 19; y < 22; y++) w.world.tiles().overlay(w.world.tiles().pos(x, y), IntentWorld.VANILLA.block("ore-copper").id());
        }
        // the world loaded as stone: index the ores as a map load would
        OreIndex.of(w.world).loaded();
        Unit.spawn(w.world, IntentWorld.VANILLA.unit("mono"), TEAM, 20 * 8f, 24 * 8f, 0f);
        calls();
        int deposits = 0;
        for(int tick = 0; tick < 3_000; tick++){
            int before = core.items.get(copper);
            w.world.tick();
            List<Packet> calls = calls();
            assertEquals(core.items.get(copper) - before, carried(calls, Packets.TransferItemTo.class, core), "transferItemTo at tick " + tick + ": " + calls);
            deposits += (int)count(calls, Packets.TransferItemTo.class);
        }
        assertTrue(deposits >= 2, "the mono deposited: " + deposits);
    }
}
