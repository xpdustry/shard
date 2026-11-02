package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.sim.blocks.storage.*;
import com.xpdustry.shard.sim.blocks.transport.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review 40's blocker: a plan's rotation is a signed byte on the wire, and a derelict duct bridge repaired at -1
 * indexed its friendly link's {@code occupied} with it, which threw out of the tick and parked the world. A placement
 * rotated outside 0 to 3 is refused at admission, whatever it would do: place, repair a derelict block, or rotate one in
 * place.
 */
class PlanRotationTest{
    static final long ALPHA = 7;

    IntentWorld w;
    Player player;
    Unit unit;
    Intents.Context context;
    int snapshots;
    long millis = 1_000;

    @BeforeEach
    void world(){
        w = new IntentWorld();
        player = w.joinWith(ALPHA, "alpha", "alpha", 100f, 100f);
        unit = player.unit();
        // sun's rules place both planets' blocks: the conveyor is serpulo's, the duct bridge erekir's
        w.rules().planet = 0;
        ((Core)w.place("core-shard", 5, 5, player.team, 0)).items.set(IntentWorld.item("copper").id(), 1_000);
        // a derelict bridge two tiles above a friendly one: at rotation 3, which -1 is mod 4, it links down to it
        w.place("duct-bridge", 16, 12, player.team, 0);
        w.place("duct-bridge", 16, 14, VanillaIds.DERELICT, 0);
        w.place("conveyor", 10, 14, player.team, 0);
        w.world.tick();
        w.drain();
        context = Intents.Context.of(w.world.config(), Policy.VANILLA);
    }

    static Codecs.Plan place(String block, int x, int y, int rotation){
        return new Codecs.Plan(false, Point2.pack(x, y), IntentWorld.VANILLA.block(block).id(), rotation, null);
    }

    /** {@code clientSnapshot} with {@code plans}, written as a client writes it and decoded as the server reads it. */
    Verdict send(boolean building, Codecs.Plan... plans){
        var packet = new Packets.ClientSnapshot(snapshots++, unit.id(), false, unit.x, unit.y, 0, 0, 0, 0, 0, 0, -1, false, false, false, building,
            (short)-1, 0, List.of(plans), unit.x, unit.y, 400, 300);
        var out = new Writes();
        packet.write(out, false, context.wire());
        var snapshot = assertInstanceOf(Snapshot.class, Intents.decode(Packets.ClientSnapshot.ID, out.toByteArray(), context));
        millis += 66;
        return w.admit(ALPHA, snapshot, millis);
    }

    @Test
    void aDerelictBridgeRepairedAtMinusOneIsRefusedAndTheWorldKeepsTicking(){
        assertEquals(Verdict.ADMITTED, send(true, place("duct-bridge", 16, 14, -1)));
        assertTrue(unit.plans.isEmpty(), "the plan is refused");
        for(int tick = 0; tick < 120; tick++) w.world.tick();
        assertEquals(VanillaIds.DERELICT, w.world.buildings().at(16, 14).team, "nothing repaired it");

        // the same repair at 3: the fixture reaches the index -1 would have taken
        assertEquals(Verdict.ADMITTED, send(true, place("duct-bridge", 16, 14, 3)));
        for(int tick = 0; tick < 120; tick++) w.world.tick();
        var bridge = assertInstanceOf(DuctBridge.class, w.world.buildings().at(16, 14));
        assertEquals(player.team, bridge.team);
        assertEquals(3, bridge.rotation);
        var friendly = assertInstanceOf(DuctBridge.class, w.world.buildings().at(16, 12));
        assertSame(friendly, bridge.lastLink);
        assertSame(bridge, friendly.occupied[3]);
    }

    @Test
    void onlyPlacementsRotatedZeroToThreeAreAdmitted(){
        for(int rotation = Byte.MIN_VALUE; rotation <= Byte.MAX_VALUE; rotation++){
            // not building: the queue is admitted and kept, and nothing is built
            assertEquals(Verdict.ADMITTED, send(false, place("conveyor", 30, 30, rotation), place("duct-bridge", 16, 14, rotation),
                place("conveyor", 10, 14, rotation), Codecs.Plan.breaking(Point2.pack(16, 12))));
            var expected = new ArrayList<String>();
            if(rotation >= 0 && rotation <= 3){
                expected.add("30,30 " + rotation);
                expected.add("16,14 " + rotation);
                // the conveyor already faces 0: a done plan
                if(rotation != 0) expected.add("10,14 " + rotation);
            }
            expected.add("16,12 breaking");
            var queue = new ArrayList<String>();
            for(int i = 0; i < unit.plans.size(); i++){
                BuildPlan plan = unit.plans.get(i);
                queue.add(plan.x + "," + plan.y + " " + (plan.breaking ? "breaking" : plan.rotation));
            }
            assertEquals(expected, queue, "rotation " + rotation);
        }
        assertEquals(0, w.world.buildings().at(10, 14).rotation);
        assertEquals(VanillaIds.DERELICT, w.world.buildings().at(16, 14).team);
    }
}
