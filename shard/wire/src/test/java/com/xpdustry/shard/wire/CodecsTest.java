package com.xpdustry.shard.wire;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.wire.Codecs.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The quirks of vanilla's plan, count and JSON codecs, in {@link TestContext}'s 16x16 world. The oracle tests compare
 * the same codecs with vanilla on random values; these pin the edge cases by name.
 */
class CodecsTest{
    static final WireContext context = TestContext.instance;

    static Reads reads(Consumer<Writes> body){
        Writes write = new Writes();
        body.accept(write);
        return new Reads(write.toByteArray());
    }

    static Plan place(int x, int y, Object config){
        return new Plan(false, Point2.pack(x, y), 4, 1, config);
    }

    //region plans

    @Test
    void plansQueueNetCounts(){
        assertNull(Codecs.readPlansQueueNet(reads(w -> Codecs.writePlansQueueNet(w, null)), context));
        assertEquals(List.of(), Codecs.readPlansQueueNet(reads(w -> w.i(-5)), context), "quirk: a negative count is empty");
        assertThrows(WireException.class, () -> Codecs.readPlansQueueNet(reads(w -> w.i(21)), context));
        List<Plan> plans = List.of(place(1, 1, null), Plan.breaking(Point2.pack(2, 3)), place(4, 5, 7));
        assertEquals(plans, Codecs.readPlansQueueNet(reads(w -> Codecs.writePlansQueueNet(w, plans)), context));
    }

    @Test
    void aPlanOutsideTheWorldEndsAfterItsPosition(){
        //vanilla returns before reading the block, so the next plan starts right after the position
        Reads read = reads(w -> {
            w.i(2);
            w.b(0);
            w.i(Point2.pack(40, 40));
            Codecs.writePlan(w, Plan.breaking(Point2.pack(1, 2)));
        });
        assertEquals(List.of(Plan.breaking(Point2.pack(1, 2))), Codecs.readPlansQueueNet(read, context));
        assertEquals(0, read.remaining());
    }

    @Test
    void aPlacementKeepsItsConfigOnlyWithTheFlag(){
        Reads read = reads(w -> {
            w.b(0);
            w.i(Point2.pack(1, 1));
            w.s(4);
            w.b(1);
            w.b(0);
            WireObjects.writeObject(w, 7);
        });
        assertEquals(place(1, 1, null), Codecs.readPlan(read, context));
        assertEquals(0, read.remaining(), "the config is read anyway");
        //any first byte but 1 is a placement
        Reads two = reads(w -> {
            w.b(2);
            w.i(Point2.pack(1, 1));
            w.s(4);
            w.b(1);
            w.b(1);
            WireObjects.writeObject(w, 7);
        });
        assertEquals(place(1, 1, 7), Codecs.readPlan(two, context));
    }

    @Test
    void maxPlans(){
        List<Plan> many = Collections.nCopies(25, place(1, 1, null));
        assertEquals(20, Codecs.maxPlans(many));
        assertEquals(20, Codecs.readPlansQueueNet(reads(w -> Codecs.writePlansQueueNet(w, many)), context).size());
        //configs count UTF-16 units and bytes; the plan that crosses 500 is still sent
        List<Plan> configs = List.of(place(1, 1, "a".repeat(300)), place(1, 1, new byte[200]), place(1, 1, 7), place(1, 1, null));
        assertEquals(4, Codecs.maxPlans(configs));
        List<Plan> over = List.of(place(1, 1, "a".repeat(300)), place(1, 1, new byte[201]), place(1, 1, "b"));
        assertEquals(2, Codecs.maxPlans(over));
        assertEquals(2, Codecs.maxPlans(List.of(place(1, 1, "🐸".repeat(150)), place(1, 1, "x".repeat(201)), place(1, 1, null))));
    }

    @Test
    void clientPlanCounts(){
        assertNull(Codecs.readClientPlans(reads(w -> Codecs.writeClientPlans(w, null, context)), context));
        assertNull(Codecs.readClientPlans(reads(w -> Codecs.writeClientPlans(w, List.of(), context)), context), "an empty list reads as null");
        assertThrows(WireException.class, () -> Codecs.readClientPlans(reads(w -> w.s(-1)), context), "ensureCapacity refuses a negative count");
        assertThrows(WireException.class, () -> Codecs.readClientPlans(reads(w -> w.s(1001)), context));
    }

    @Test
    void clientPlans(){
        //block 2 rotates and block 3 doesn't, so only the first carries a rotation byte
        List<ClientPlan> plans = List.of(new ClientPlan(1, 2, 2, 3, 5), new ClientPlan(65535, 0, 3, 0, true), new ClientPlan(0, 0, 3, 0, null));
        Writes write = new Writes();
        Codecs.writeClientPlans(write, plans, context);
        assertEquals(2 + (4 + 2 + 1 + 5) + (4 + 2 + 2) + (4 + 2 + 1), write.size());
        assertEquals(plans, Codecs.readClientPlans(new Reads(write.toByteArray()), context));
        //a config a client plan may not carry is sent as null
        assertEquals(List.of(new ClientPlan(1, 1, 3, 0, null)),
            Codecs.readClientPlans(reads(w -> Codecs.writeClientPlans(w, List.of(new ClientPlan(1, 1, 3, 0, "text")), context)), context));
    }

    @Test
    void clientPlanRejections(){
        assertThrows(WireException.class, () -> Codecs.readClientPlans(reads(w -> {
            w.s(1);
            w.i(0);
            w.s(100);
        }), context), "an unknown block");
        assertThrows(WireException.class, () -> Codecs.readClientPlans(reads(w -> {
            w.s(1);
            w.i(0);
            w.s(3);
            WireObjects.writeObject(w, "text");
        }), context), "a string config");
    }

    //endregion
    //region counts and JSON

    @Test
    void counts(){
        Reads read = new Reads(new byte[8]);
        assertEquals(2, Codecs.count(read, 2, 4, "ints"));
        assertThrows(WireException.class, () -> Codecs.count(read, 3, 4, "ints"));
        assertThrows(WireException.class, () -> Codecs.count(read, -1, 4, "ints"));
        assertThrows(WireException.class, () -> Codecs.count(read, Integer.MAX_VALUE, 4, "ints"), "no overflow");
        assertThrows(WireException.class, () -> Codecs.readInts(reads(w -> w.s(-1))));
        assertThrows(WireException.class, () -> Codecs.readBytes(reads(w -> w.s(10))));
    }

    @Test
    void jsonCaps(){
        assertEquals("{}", Codecs.readRules(reads(w -> Codecs.writeJson(w, "{}"))));
        assertEquals("é", Codecs.readObjectives(reads(w -> Codecs.writeJson(w, "é"))), "UTF-8, not modified UTF-8");
        //the cap is checked before reading, so a hostile length costs nothing
        assertThrows(WireException.class, () -> Codecs.readRules(reads(w -> w.i(Codecs.MAX_RULES + 1))));
        assertThrows(WireException.class, () -> Codecs.readObjectives(reads(w -> w.i(Codecs.MAX_OBJECTIVES + 1))));
        assertThrows(WireException.class, () -> Codecs.readObjectiveMarker(reads(w -> w.i(Codecs.MAX_MARKER + 1))));
        String marker = "m".repeat(Codecs.MAX_MARKER);
        assertEquals(marker, Codecs.readObjectiveMarker(reads(w -> Codecs.writeJson(w, marker))));
    }

    //endregion
    //region statuses and UI

    @Test
    void unknownStatusesFail(){
        assertEquals(StatusEntry.of(3, 2f), Codecs.readStatus(reads(w -> Codecs.writeStatus(w, StatusEntry.of(3, 2f), context)), context));
        var dynamic = new StatusEntry(5, 1f, 2f, 1f, 1f, 1f, 1f, 1f, 4f);
        assertEquals(dynamic, Codecs.readStatus(reads(w -> Codecs.writeStatus(w, dynamic, context)), context));
        for(int unknown : new int[]{-1, 10, Short.MAX_VALUE}){
            assertThrows(WireException.class, () -> Codecs.readStatus(reads(w -> {
                w.s(unknown);
                w.f(1f);
            }), context));
        }
    }

    static UiNode nested(int depth){
        UiNode node = new UiNode(0, List.of(new UiEntry(13, "leaf")));
        for(int i = 1; i < depth; i++) node = new UiNode(0, List.of(new UiEntry(13, 1f), new UiEntry(14, node)));
        return node;
    }

    @Test
    void uiBuildersAreBoundedInDepth(){
        UiNode deepest = nested(Codecs.UI_MAX_DEPTH);
        assertEquals(deepest, Codecs.readUiBuilder(reads(w -> Codecs.writeUiBuilder(w, deepest))));

        Writes write = new Writes();
        assertThrows(WireException.class, () -> Codecs.writeUiBuilder(write, nested(Codecs.UI_MAX_DEPTH + 1)));
        assertEquals(0, write.size(), "nothing written");

        //a hand-made chain, deep enough to overflow an unbounded reader's stack
        Writes chain = new Writes();
        for(int i = 0; i < 100_000; i++){
            chain.b(0);
            chain.s(1);
            chain.s(14);
            chain.b(3);
        }
        var e = assertThrows(WireException.class, () -> Codecs.readUiBuilder(new Reads(chain.toByteArray())));
        assertTrue(e.getMessage().contains("deep"), e.getMessage());
    }

    //endregion
}
