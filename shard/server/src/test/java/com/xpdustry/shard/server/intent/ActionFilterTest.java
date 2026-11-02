package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.sim.ai.CommandAi;
import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.server.intent.action.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import org.junit.jupiter.api.*;

import java.util.*;

import static com.xpdustry.shard.server.intent.IntentWorld.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Each of vanilla's 16 action types can be denied by an admin's rule, with vanilla's rollback: a refused config sends
 * the building's own config back, a refused plan is removed from the client's queue, and every refused call is left
 * unforwarded with the world unchanged. The same actions without the rule go through, so the denial is the rule's.
 */
class ActionFilterTest{
    static final long ALPHA = 7;
    static final Policy POLICY = new Policy(true, false, 6_000, 25, 60);

    IntentWorld w;
    Player player;
    Unit unit;

    @BeforeEach
    void world(){
        w = new IntentWorld();
        player = w.joinWith(ALPHA, "alpha", "alpha", 100f, 100f);
        unit = player.unit();
    }

    /** The intent is rejected by the rule, and the rule's message is the player's only other Call but {@code rollback}. */
    void deniedWith(ActionType type, Intent intent, Packet... rollback){
        denied(type, intent, Verdict.rejected("denied by RuleFilter: " + type.vanillaName()), rollback);
    }

    /** A refused plan leaves its snapshot admitted: clientSnapshot drops the plan and applies the rest. */
    void planDenied(ActionType type, BuildPlan plan, Packet rollback){
        denied(type, snapshot(List.of(plan)), Verdict.ADMITTED, rollback);
    }

    void denied(ActionType type, Intent intent, Verdict expectedVerdict, Packet... rollback){
        w.rule("no-" + type.vanillaName(), type);
        w.drain();
        Verdict verdict = w.admit(ALPHA, intent, 1_000);
        assertEquals(expectedVerdict, verdict);
        List<IntentEvent> events = w.drain();
        var expected = new ArrayList<IntentEvent>();
        expected.add(new IntentCall.To(ALPHA, new Packets.SendMessage("[scarlet]Not here.")));
        for(Packet packet : rollback) expected.add(new IntentCall.To(ALPHA, packet));
        assertEquals(expected.size(), events.size(), "the message and vanilla's rollback, nothing forwarded: " + events);
        for(int i = 0; i < expected.size(); i++) assertCall(expected.get(i), events.get(i));
    }

    /** Packets with arrays compare by content. */
    static void assertCall(IntentEvent expected, IntentEvent actual){
        assertEquals(expected.getClass(), actual.getClass());
        assertEquals(Objects.toString(packet(expected)).replaceAll("@[0-9a-f]+", ""), Objects.toString(packet(actual)).replaceAll("@[0-9a-f]+", ""));
    }

    static Object packet(IntentEvent event){
        return switch(event){
            case IntentCall.To to -> to.packet();
            case IntentCall.All all -> all.packet();
            case IntentCall.AllExcept all -> all.packet();
            default -> event;
        };
    }

    static byte[] object(Object value){
        var out = new Writes(16);
        WireObjects.writeObjectSafe(out, value);
        return out.toByteArray();
    }

    Snapshot snapshot(List<BuildPlan> plans){
        return new Snapshot(POLICY, 0, unit.id(), false, unit.x, unit.y, 0, 0, 0, 0, 0, 0, -1, false, false, false, true, -1, 0, plans,
            0, 0, 100, 100);
    }

    @Test
    void breakBlock(){
        w.place("router", 14, 12, player.team, 0);
        planDenied(ActionType.BREAK_BLOCK, BuildPlan.breaking(14, 12), new Packets.RemoveQueueBlock(14, 12, true));
        assertEquals(0, unit.plans.size(), "the plan isn't queued");
    }

    @Test
    void placeBlock(){
        int conveyor = VANILLA.block("conveyor").id();
        planDenied(ActionType.PLACE_BLOCK, BuildPlan.place(14, 12, 1, conveyor, null), new Packets.RemoveQueueBlock(14, 12, false));
        assertEquals(0, unit.plans.size());
    }

    @Test
    void plansTheRuleAllowsStayQueued(){
        w.rule("no-break", ActionType.BREAK_BLOCK);
        w.place("router", 14, 12, player.team, 0);
        int conveyor = VANILLA.block("conveyor").id();
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, snapshot(List.of(BuildPlan.breaking(14, 12), BuildPlan.place(15, 12, 1, conveyor, null))), 1_000));
        assertEquals(1, unit.plans.size(), "only the breaking plan is refused");
        assertFalse(unit.plans.first().breaking);
    }

    @Test
    void rotate(){
        Building router = w.place("router", 14, 12, player.team, 0);
        deniedWith(ActionType.ROTATE, new BlockIntents.RotateBlock(POLICY, router.pos(), true));
        assertEquals(0, router.rotation);
        w.apply(new ActionRules.Remove("no-rotate"));
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, new BlockIntents.RotateBlock(POLICY, router.pos(), true), 2_000));
        assertEquals(1, router.rotation);
        var forwards = w.drain();
        assertEquals(1, forwards.size());
        assertCall(new IntentCall.All(new Packets.RotateBlock(player.id(), router.pos(), true)), forwards.getFirst());
    }

    @Test
    void configure(){
        var source = (com.xpdustry.shard.sim.blocks.sandbox.ItemSource)w.place("item-source", 14, 12, player.team, 0);
        int lead = item("lead").id(), copper = item("copper").id();
        source.outputItem = lead;
        byte[] value = object(new WireObjects.ContentRef(0, copper));
        deniedWith(ActionType.CONFIGURE, new BlockIntents.TileConfig(POLICY, source.pos(), value),
            new Packets.TileConfig(player.id(), source.pos(), new WireObjects.ContentRef(0, lead)));
        assertEquals(lead, source.outputItem, "the old config stays");
        w.apply(new ActionRules.Remove("no-configure"));
        var configured = w.world.observe(16, List.of(BuildingEvent.Configured.class), null);
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, new BlockIntents.TileConfig(POLICY, source.pos(), value), 2_000));
        assertEquals(copper, source.outputItem);
        var forwards = w.drain();
        assertEquals(1, forwards.size());
        // called = Loc.both: the sender already applied it
        assertCall(new IntentCall.AllExcept(ALPHA, new Packets.TileConfig(player.id(), source.pos(), new WireObjects.ContentRef(0, copper))), forwards.getFirst());
        // and the forward is the config's only Call: the sync layer makes none of its event (review 39's blocker)
        var events = configured.drain();
        assertEquals(1, events.size());
        assertTrue(((BuildingEvent.Configured)events.getFirst()).forwarded());
        assertEquals(List.of(), ConfigForwardServerTest.calls(w.world, events.getFirst()));
    }

    @Test
    void withdrawItem(){
        Building router = w.place("router", 13, 13, player.team, 0);
        int copper = item("copper").id();
        router.items.add(copper, 1);
        deniedWith(ActionType.WITHDRAW_ITEM, new ItemIntents.RequestItem(POLICY, router.pos(), copper, 1));
        assertEquals(1, router.items.get(copper));
        assertEquals(0, unit.stackAmount);
    }

    @Test
    void depositItem(){
        Building router = w.place("router", 13, 13, player.team, 0);
        int copper = item("copper").id();
        unit.stackItem = copper;
        unit.stackAmount = 5;
        deniedWith(ActionType.DEPOSIT_ITEM, new ItemIntents.TransferInventory(POLICY, router.pos()));
        assertEquals(5, unit.stackAmount);
        assertEquals(0, router.items.get(copper));
    }

    @Test
    void control(){
        Unit dagger = Unit.spawn(w.world, VANILLA.unit("dagger"), player.team, 120f, 100f, 0f);
        w.world.tick();
        deniedWith(ActionType.CONTROL, new UnitIntents.UnitControl(POLICY, Codecs.UnitRef.unit(dagger.id())));
        assertSame(unit, player.unit());
        assertTrue(dagger.isAI());
    }

    @Test
    void buildSelect(){
        Building router = w.place("router", 13, 13, player.team, 0);
        deniedWith(ActionType.BUILD_SELECT, new UnitIntents.BuildingControlSelect(POLICY, router.pos()));
    }

    @Test
    void commandDeniesNothingInV160(){
        w.rule("no-command", ActionType.COMMAND);
        Building router = w.place("router", 14, 12, player.team, 0);
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, new BlockIntents.RotateBlock(POLICY, router.pos(), true), 1_000));
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, new UnitIntents.CommandUnits(POLICY, new int[0], -1, Codecs.UnitRef.NONE, 0, 0, false, true), 1_100));
        assertEquals(1, router.rotation);
    }

    @Test
    void removePlanned(){
        deniedWith(ActionType.REMOVE_PLANNED, new PlayerIntents.DeletePlans(POLICY, new int[]{Point2.pack(3, 4)}));
    }

    @Test
    void commandUnits(){
        Unit dagger = Unit.spawn(w.world, VANILLA.unit("dagger"), player.team, 120f, 100f, 0f);
        w.world.tick();
        var ai = (CommandAi)dagger.controller;
        int command = ai.command;
        deniedWith(ActionType.COMMAND_UNITS, new UnitIntents.CommandUnits(POLICY, new int[]{dagger.id()}, -1, Codecs.UnitRef.NONE, 200f, 200f, false, true));
        assertNull(ai.targetPos);
        int rebuild = VANILLA.unitCommands().indexOf("rebuild");
        assertEquals(Verdict.rejected("denied by RuleFilter: commandUnits"), w.admit(ALPHA, new UnitIntents.SetUnitCommand(POLICY, new int[]{dagger.id()}, rebuild), 2_000));
        assertEquals(command, ai.command);
        int hold = VANILLA.unitStances().indexOf("holdfire");
        assertEquals(Verdict.rejected("denied by RuleFilter: commandUnits"), w.admit(ALPHA, new UnitIntents.SetUnitStance(POLICY, new int[]{dagger.id()}, hold, true), 3_000));
        assertFalse(ai.hasStance(hold));
        w.apply(new ActionRules.Remove("no-commandUnits"));
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, new UnitIntents.CommandUnits(POLICY, new int[]{dagger.id()}, -1, Codecs.UnitRef.NONE, 200f, 200f, false, true), 4_000));
        assertEquals(new Vec2(200f, 200f), ai.targetPos);
    }

    @Test
    void commandBuilding(){
        Building router = w.place("router", 13, 13, player.team, 0);
        deniedWith(ActionType.COMMAND_BUILDING, new UnitIntents.CommandBuilding(POLICY, new int[]{router.pos()}, 40f, 40f));
    }

    @Test
    void respawn(){
        deniedWith(ActionType.RESPAWN, new UnitIntents.UnitClear(POLICY));
        assertSame(unit, player.unit());
    }

    @Test
    void pickupBlock(){
        Unit mega = w.give(player, "mega", 100f, 100f);
        assertNotNull(mega.payloads, "a payload unit");
        Building router = w.place("router", 13, 13, player.team, 0);
        deniedWith(ActionType.PICKUP_BLOCK, new PlayerIntents.RequestBuildPayload(POLICY, router.pos()));
        w.apply(new ActionRules.Remove("no-pickupBlock"));
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, new PlayerIntents.RequestBuildPayload(POLICY, router.pos()), 2_000));
        assertNull(w.world.buildings().at(13, 13), "picked up once allowed");
        assertEquals(1, mega.payloads.size());
    }

    @Test
    void dropPayload(){
        Unit mega = w.give(player, "mega", 100f, 100f);
        mega.payloads.add(new Payload.UnitPayload(Unit.create(w.world, VANILLA.unit("dagger"), player.team)));
        deniedWith(ActionType.DROP_PAYLOAD, new PlayerIntents.RequestDropPayload(POLICY, 104f, 100f));
        assertEquals(1, mega.payloads.size());
    }

    @Test
    void pingLocation(){
        deniedWith(ActionType.PING_LOCATION, new PlayerIntents.PingLocation(POLICY, 40f, 40f, "here"));
    }

    @Test
    void everyTypeIsCovered(){
        // one test per type above, by vanilla's name
        var names = new TreeSet<String>();
        for(var method : getClass().getDeclaredMethods()) if(method.isAnnotationPresent(Test.class)) names.add(method.getName());
        for(ActionType type : ActionType.values()){
            String test = type == ActionType.COMMAND ? "commandDeniesNothingInV160" : type.vanillaName();
            assertTrue(names.contains(test), "no test for " + type);
        }
    }

    @Test
    void aRuleForAnotherTeamOrSessionAllows(){
        Building router = w.place("router", 14, 12, player.team, 0);
        assertEquals(Verdict.ADMITTED, w.apply(new ActionRules.Put(new ActionRules.Rule("crux", ActionType.ROTATE.bit(), 2, -1, ""))));
        assertEquals(Verdict.ADMITTED, w.apply(new ActionRules.Put(new ActionRules.Rule("other", ActionType.ROTATE.bit(), -1, 99, ""))));
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, new BlockIntents.RotateBlock(POLICY, router.pos(), false), 1_000));
        assertEquals(3, router.rotation);
        assertEquals(Verdict.ADMITTED, w.apply(new ActionRules.Put(new ActionRules.Rule("alpha", ActionType.ROTATE.bit(), -1, ALPHA, ""))));
        assertEquals(Verdict.rejected("denied by RuleFilter: rotate"), w.admit(ALPHA, new BlockIntents.RotateBlock(POLICY, router.pos(), false), 2_000));
    }

    @Test
    void rulesAreWorldStateThatForksKeepAndTheHashCovers(){
        w.rule("no-rotate", ActionType.ROTATE);
        World fork = w.world.fork();
        assertEquals(w.world.hash(), fork.hash());
        assertEquals(Verdict.ADMITTED, new ActionRules.Remove("no-rotate").apply(fork, Cause.SERVER));
        assertTrue(ActionRules.of(fork).rules().isEmpty());
        assertEquals(1, ActionRules.of(w.world).rules().size(), "the fork's change stays in the fork");
        assertNotEquals(w.world.hash(), fork.hash());
    }
}
