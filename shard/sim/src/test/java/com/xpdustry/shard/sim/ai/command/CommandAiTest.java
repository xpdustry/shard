package com.xpdustry.shard.sim.ai.command;

import com.xpdustry.shard.sim.ai.CommandAi;
import com.xpdustry.shard.sim.path.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.Team;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CommandAi} on Shard alone: a mixed group ordered across a wall arrives in formation, a mega loads units and
 * unloads them at a target, and forks and checkpoint restores taken mid-order (groups and pending batches included)
 * stay hash-exact. Parity with vanilla is the command oracles' ({@code CommandOracleTest}).
 */
class CommandAiTest{
    static World world(){
        World world = VanillaWorlds.world(64, 48, 7);
        Rules rules = world.part(WorldRules.class).rules;
        rules.waveTimer = false;
        rules.disableUnitCap = true;
        rules.fire = false;
        world.part(PathService.class).invalidateAll();
        // a wall across the map with one gap
        for(int y = 0; y < 48; y++) if(y < 22 || y > 25) world.buildings().setBlock(32, y, world.content().block("stone-wall"), 0, 0, Cause.SERVER);
        return world;
    }

    static Unit spawn(World world, String type, float x, float y){
        return Unit.spawn(world, world.content().unit(type), Team.SHARDED, x, y, 0f);
    }

    static Unit[] army(World world){
        return new Unit[]{spawn(world, "dagger", 80f, 80f), spawn(world, "dagger", 96f, 88f), spawn(world, "mace", 80f, 120f),
            spawn(world, "atrax", 100f, 140f), spawn(world, "flare", 120f, 100f), spawn(world, "flare", 124f, 110f)};
    }

    @Test
    void aMixedGroupCrossesTheWallInFormation(){
        World world = world();
        Unit[] units = army(world);
        world.tick();
        Orders.commandUnits(world, Team.SHARDED, units, new Orders.Target(null, null, 50 * 8f, 30 * 8f), false, true);
        var dagger = (CommandAi)units[0].controller;
        assertNotNull(dagger.group);
        assertTrue(dagger.group.valid);
        assertNotSame(dagger.group, ((CommandAi)units[4].controller).group, "flyers form their own group");
        for(int i = 0; i < 2400 && anyMoving(units); i++) world.tick();
        for(Unit unit : units){
            assertNull(((CommandAi)unit.controller).targetPos, unit.type().name() + " arrived at " + unit.x + "," + unit.y);
            assertTrue(unit.x > 33 * 8f, unit.type().name() + " crossed the wall");
        }
    }

    static boolean anyMoving(Unit[] units){
        for(Unit unit : units) if(((CommandAi)unit.controller).targetPos != null) return true;
        return false;
    }

    @Test
    void aMegaLoadsUnitsAndUnloadsThemAtTheTarget(){
        World world = world();
        Unit mega = spawn(world, "mega", 80f, 80f);
        Unit a = spawn(world, "dagger", 82f, 80f), b = spawn(world, "dagger", 78f, 84f);
        world.tick();
        var ai = (CommandAi)mega.controller;
        Orders.setUnitCommand(world, Team.SHARDED, new Unit[]{mega}, UnitCommand.LOAD_UNITS);
        for(int i = 0; i < 120; i++) world.tick();
        assertEquals(2, mega.payloads.size(), "loaded both daggers");
        assertFalse(a.isAdded() || b.isAdded());
        // carry them across the wall, then unload there: unloadPayload drops at once, wherever the carrier is
        Orders.setUnitCommand(world, Team.SHARDED, new Unit[]{mega}, UnitCommand.MOVE);
        Orders.commandUnits(world, Team.SHARDED, new Unit[]{mega}, new Orders.Target(null, null, 50 * 8f, 30 * 8f), false, true);
        for(int i = 0; i < 1200 && ai.targetPos != null; i++) world.tick();
        assertNull(ai.targetPos, "the mega arrived");
        assertEquals(2, mega.payloads.size(), "still carrying");
        Orders.setUnitCommand(world, Team.SHARDED, new Unit[]{mega}, UnitCommand.UNLOAD_PAYLOAD);
        for(int i = 0; i < 120 && Payloads.hasPayload(mega); i++) world.tick();
        assertFalse(Payloads.hasPayload(mega), "unloaded");
        assertTrue(a.isAdded() && b.isAdded());
        assertTrue(a.x > 33 * 8f && b.x > 33 * 8f, "dropped across the wall");
        assertEquals(UnitCommand.UNLOAD_PAYLOAD, ai.command());
    }

    @Test
    void forksAndCheckpointsCarryOrdersGroupsAndBatches(){
        World world = world();
        Unit[] units = army(world);
        world.tick();
        // a non-final batch stays pending; the final one to another point forms groups
        Orders.commandUnits(world, Team.SHARDED, new Unit[]{units[0]}, new Orders.Target(null, null, 10f, 10f), false, false);
        Orders.commandUnits(world, Team.SHARDED, units, new Orders.Target(null, null, 50 * 8f, 30 * 8f), false, true);
        UnitStances stances = UnitStances.of(world.content());
        Orders.setUnitStance(world, Team.SHARDED, units, stances.holdFire, true);
        for(int i = 0; i < 60; i++) world.tick();
        assertEquals(1, CommandBatches.of(world).pending());

        World fork = world.fork();
        World restored = Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
        assertEquals(world.hash(), fork.hash(), "the fork");
        assertEquals(world.hash(), restored.hash(), "the restored world");
        var group = ((CommandAi)restored.units().get(0).controller).group;
        assertNotNull(group);
        assertSame(group, ((CommandAi)restored.units().get(1).controller).group, "a group is still shared after a restore");
        for(int i = 0; i < 400; i++){
            world.tick();
            fork.tick();
            restored.tick();
            assertEquals(world.hash(), fork.hash(), "the fork after " + i);
            assertEquals(world.hash(), restored.hash(), "the restored world after " + i);
        }
    }

    @Test
    void stancesFollowTheirIncompatibilities(){
        World world = world();
        Unit poly = spawn(world, "poly", 80f, 80f);
        world.tick();
        var ai = (CommandAi)poly.controller;
        UnitStances table = UnitStances.of(world.content());
        Orders.setUnitCommand(world, Team.SHARDED, new Unit[]{poly}, UnitCommand.MINE);
        world.tick();
        assertTrue(ai.hasStance(table.mineAuto), "mine turns mineauto on");
        int copper = table.itemStance(world.content().item("copper").id());
        ai.setStance(copper);
        assertTrue(ai.hasStance(copper));
        assertFalse(ai.hasStance(table.mineAuto), "an item stance excludes mineauto");
        assertFalse(table.compatible(table.patrol, UnitCommand.REBUILD));
        assertFalse(table.compatible(table.boost, UnitCommand.ENTER_PAYLOAD));
        assertFalse(table.toggles(table.stop));
        assertFalse(table.toggles(table.mineAuto));
    }
}
