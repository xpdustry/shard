package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.server.intent.action.*;
import com.xpdustry.shard.sim.ai.CommandAi;
import com.xpdustry.shard.sim.ai.command.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The RTS remote calls a stock client sends (InputHandler.java:310-503), admitted through W2.14's action filter and
 * applied by {@link Orders}: one test per call, each checking the filter refuses it, then what an admitted one does
 * and that it is forwarded.
 */
class RtsIntentsTest{
    static final long ALPHA = 7, BETA = 8;
    static final Policy POLICY = new Policy(true, false, 6_000, 25, 60);

    IntentWorld w;
    Player player;

    @BeforeEach
    void setUp(){
        w = new IntentWorld();
        player = w.joinWith(ALPHA, "alpha", "alpha", 100f, 100f);
    }

    Unit spawn(String type, int team, float x, float y){
        Unit unit = Unit.spawn(w.world, IntentWorld.VANILLA.unit(type), team, x, y, 0f);
        w.world.tick();
        w.drain();
        return unit;
    }

    static int stance(String name){
        return IntentWorld.VANILLA.unitStances().indexOf(name);
    }

    static int command(String name){
        return IntentWorld.VANILLA.unitCommands().indexOf(name);
    }

    @Test
    void commandUnitsMovesAndFormsAGroupPerLayer(){
        Unit a = spawn("dagger", player.team, 120f, 100f), b = spawn("dagger", player.team, 140f, 100f), f = spawn("flare", player.team, 130f, 120f);
        Unit enemy = spawn("dagger", player.team + 1, 300f, 300f);
        int[] ids = {a.id(), b.id(), f.id(), enemy.id()};

        w.rule("no-command", ActionType.COMMAND_UNITS);
        var order = new UnitIntents.CommandUnits(POLICY, ids, -1, Codecs.UnitRef.NONE, 200f, 240f, false, true);
        assertNotEquals(Verdict.ADMITTED, w.admit(ALPHA, order, 1_000));
        assertNull(((CommandAi)a.controller).targetPos, "denied: nothing moves");
        w.apply(new ActionRules.Remove("no-command"));
        w.drain();

        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, order, 2_000));
        var ai = (CommandAi)a.controller;
        assertEquals(new Vec2(200f, 240f), ai.targetPos);
        assertNull(((CommandAi)enemy.controller).targetPos, "another team's unit takes no order");
        UnitGroup ground = ai.group, air = ((CommandAi)f.controller).group;
        assertNotNull(ground);
        assertSame(ground, ((CommandAi)b.controller).group, "ground units share a group");
        assertNotSame(ground, air, "the flare is in its own layer's group");
        assertTrue(ground.valid && ground.size == 2 && air.size == 1);
        assertEquals(0, CommandBatches.of(w.world).pending(), "the final batch is consumed");
        assertEquals(1, IntentWorld.calls(w.drain(), Packets.CommandUnits.class).size(), "forwarded");
    }

    @Test
    void commandUnitsAttacksAnEnemyAndQueues(){
        Unit a = spawn("dagger", player.team, 120f, 100f);
        Unit enemy = spawn("dagger", player.team + 1, 300f, 300f);
        var ai = (CommandAi)a.controller;
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, new UnitIntents.CommandUnits(POLICY, new int[]{a.id()}, -1, Codecs.UnitRef.unit(enemy.id()),
            0f, 0f, false, true), 1_000));
        assertSame(enemy, ai.attackTarget);
        assertNull(ai.group, "a single unit gets no group");
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, new UnitIntents.CommandUnits(POLICY, new int[]{a.id()}, -1, Codecs.UnitRef.NONE,
            64f, 64f, true, true), 2_000));
        assertEquals(1, ai.queue.length, "queued behind the attack");
    }

    @Test
    void nonFinalBatchesWaitForTheFinalOne(){
        Unit a = spawn("dagger", player.team, 120f, 100f), b = spawn("dagger", player.team, 140f, 100f);
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, new UnitIntents.CommandUnits(POLICY, new int[]{a.id()}, -1, Codecs.UnitRef.NONE, 200f, 200f, false, false), 1_000));
        assertEquals(1, CommandBatches.of(w.world).pending());
        assertNull(((CommandAi)a.controller).group);
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, new UnitIntents.CommandUnits(POLICY, new int[]{b.id(), b.id()}, -1, Codecs.UnitRef.NONE, 200f, 200f, false, true), 2_000));
        assertNotNull(((CommandAi)a.controller).group, "the first batch's unit joins the group");
        assertEquals(0, CommandBatches.of(w.world).pending());
    }

    @Test
    void setUnitCommand(){
        Unit poly = spawn("poly", player.team, 120f, 100f);
        var ai = (CommandAi)poly.controller;
        ai.commandPosition(new Vec2(300f, 300f));
        ai.setStance(stance("patrol"));
        var order = new UnitIntents.SetUnitCommand(POLICY, new int[]{poly.id()}, command("rebuild"));
        w.rule("no-command", ActionType.COMMAND_UNITS);
        assertNotEquals(Verdict.ADMITTED, w.admit(ALPHA, order, 1_000));
        w.apply(new ActionRules.Remove("no-command"));
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, order, 2_000));
        assertEquals(UnitCommand.REBUILD, ai.command());
        assertNull(ai.targetPos, "rebuild resets targets");
        assertFalse(ai.hasStance(stance("patrol")), "patrol isn't compatible with rebuild");
        // a command the type lacks is ignored
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, new UnitIntents.SetUnitCommand(POLICY, new int[]{poly.id()}, command("loadUnits")), 3_000));
        assertEquals(UnitCommand.REBUILD, ai.command());
        assertNotEquals(Verdict.ADMITTED, w.admit(ALPHA, new UnitIntents.SetUnitCommand(POLICY, new int[]{poly.id()}, 99), 4_000));
    }

    @Test
    void setUnitStance(){
        Unit dagger = spawn("dagger", player.team, 120f, 100f);
        var ai = (CommandAi)dagger.controller;
        var hold = new UnitIntents.SetUnitStance(POLICY, new int[]{dagger.id()}, stance("holdfire"), true);
        w.rule("no-command", ActionType.COMMAND_UNITS);
        assertNotEquals(Verdict.ADMITTED, w.admit(ALPHA, hold, 1_000));
        assertFalse(ai.hasStance(stance("holdfire")));
        w.apply(new ActionRules.Remove("no-command"));
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, hold, 2_000));
        assertTrue(ai.hasStance(stance("holdfire")));
        assertFalse(ai.shouldFire());
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, new UnitIntents.SetUnitStance(POLICY, new int[]{dagger.id()}, stance("holdfire"), false), 3_000));
        assertFalse(ai.hasStance(stance("holdfire")));
        // a stance the type doesn't have is ignored: daggers can't boost
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, new UnitIntents.SetUnitStance(POLICY, new int[]{dagger.id()}, stance("boost"), true), 4_000));
        assertFalse(ai.hasStance(stance("boost")));
        // stop cancels orders
        ai.commandPosition(new Vec2(300f, 300f));
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, new UnitIntents.SetUnitStance(POLICY, new int[]{dagger.id()}, stance("stop"), true), 5_000));
        assertNull(ai.targetPos);
    }

    @Test
    void commandBuilding(){
        Building router = w.place("router", 13, 13, player.team, 0);
        var order = new UnitIntents.CommandBuilding(POLICY, new int[]{router.pos()}, 40f, 40f);
        w.rule("no-command", ActionType.COMMAND_BUILDING);
        assertNotEquals(Verdict.ADMITTED, w.admit(ALPHA, order, 1_000));
        w.apply(new ActionRules.Remove("no-command"));
        w.drain();
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, order, 2_000));
        assertNull(router.getCommandPosition(), "a router isn't commandable");
        assertEquals(1, IntentWorld.calls(w.drain(), Packets.CommandBuilding.class).size(), "forwarded");
    }
}
