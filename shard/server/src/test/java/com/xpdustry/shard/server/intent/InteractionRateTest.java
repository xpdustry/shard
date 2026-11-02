package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.server.intent.action.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Vanilla's block interaction rate limit (Administration.java:73-90) at its defaults: 25 interactions per 6 s window,
 * a message at most every 2 s past that, a 30 s kick past 60. Building, breaking and commanding units don't count,
 * and the window is timed by the packets' recorded arrival.
 */
class InteractionRateTest{
    static final long ALPHA = 7;
    static final Policy ANTI_SPAM = Policy.VANILLA;

    IntentWorld w;
    Player player;
    Building router;

    @BeforeEach
    void world(){
        w = new IntentWorld();
        player = w.joinWith(ALPHA, "alpha", "alpha", 100f, 100f);
        router = w.place("router", 14, 12, player.team, 0);
    }

    Verdict rotate(Policy policy, long arrival){
        return w.admit(ALPHA, new BlockIntents.RotateBlock(policy, router.pos(), true), arrival);
    }

    @Test
    void theWindowAllows25ThenDeniesWithAMessage(){
        for(int i = 0; i < 25; i++) assertEquals(Verdict.ADMITTED, rotate(ANTI_SPAM, 1_000 + i));
        w.drain();
        assertEquals(Verdict.rejected("denied by InteractionRate: rotate"), rotate(ANTI_SPAM, 1_100));
        assertEquals(List.of(new IntentCall.To(ALPHA, new Packets.SendMessage(InteractionRate.MESSAGE))), w.drain());
        assertEquals(25 % 4, router.rotation, "25 turns, none after");
        // one message per 2 s of ticks
        assertEquals(Verdict.rejected("denied by InteractionRate: rotate"), rotate(ANTI_SPAM, 1_200));
        assertEquals(List.of(), w.drain());
        for(int i = 0; i < 120; i++) w.world.tick();
        assertEquals(Verdict.rejected("denied by InteractionRate: rotate"), rotate(ANTI_SPAM, 1_300));
        assertEquals(1, w.drain().size(), "the message again");
    }

    @Test
    void aNewWindowStartsAfterSixSecondsOfArrivals(){
        for(int i = 0; i < 26; i++) rotate(ANTI_SPAM, 1_000 + i);
        assertEquals(Verdict.rejected("denied by InteractionRate: rotate"), rotate(ANTI_SPAM, 7_000), "6 s after the window started is still in it");
        assertEquals(Verdict.ADMITTED, rotate(ANTI_SPAM, 7_001));
    }

    @Test
    void past60InOneWindowTheyAreKicked(){
        for(int i = 0; i < 60; i++) rotate(ANTI_SPAM, 1_000 + i);
        w.drain();
        assertEquals(Verdict.rejected("denied by InteractionRate: rotate"), rotate(ANTI_SPAM, 1_100));
        assertEquals(List.of(new IntentCall.Kick(ALPHA, InteractionRate.KICK_REASON, InteractionRate.KICK_MILLIS)), w.drain());
        assertEquals(Verdict.rejected("kicked"), rotate(ANTI_SPAM, 1_200), "nothing more from them");
    }

    @Test
    void buildingBreakingAndCommandingDontCount(){
        Unit unit = player.unit();
        int conveyor = IntentWorld.VANILLA.block("conveyor").id();
        for(int i = 0; i < 40; i++){
            var plans = List.of(BuildPlan.place(20, 20 + (i % 5), 0, conveyor, null));
            assertEquals(Verdict.ADMITTED, w.admit(ALPHA, new Snapshot(ANTI_SPAM, i, unit.id(), false, unit.x, unit.y, 0, 0, 0, 0, 0, 0, -1, false, false,
                false, true, -1, 0, plans, 0, 0, 100, 100), 1_000 + i));
            assertEquals(Verdict.ADMITTED, w.admit(ALPHA, new UnitIntents.CommandUnits(ANTI_SPAM, new int[0], -1, Codecs.UnitRef.NONE, 0, 0, false, true), 1_000 + i));
        }
        assertEquals(1, unit.plans.size());
        for(int i = 0; i < 25; i++) assertEquals(Verdict.ADMITTED, rotate(ANTI_SPAM, 1_100 + i), "the window is still empty");
    }

    @Test
    void withoutAntiSpamNothingIsLimited(){
        Policy off = new Policy(true, false, 6_000, 25, 60);
        for(int i = 0; i < 100; i++) assertEquals(Verdict.ADMITTED, rotate(off, 1_000));
    }

    @Test
    void theFilterRunsBeforeTheRules(){
        w.rule("no-rotate", ActionType.ROTATE);
        for(int i = 0; i < 25; i++) assertEquals(Verdict.rejected("denied by RuleFilter: rotate"), rotate(ANTI_SPAM, 1_000));
        assertEquals(Verdict.rejected("denied by InteractionRate: rotate"), rotate(ANTI_SPAM, 1_000), "a denied action still counted");
    }
}
