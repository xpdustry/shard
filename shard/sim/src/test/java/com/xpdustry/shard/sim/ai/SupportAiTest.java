package com.xpdustry.shard.sim.ai;

import com.xpdustry.shard.sim.build.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The miner, builder and repair AIs on their own: each, caught mid-job, goes on the same in a fork and in a restored
 * checkpoint, tick for tick, and the identities they hold survive both: a builder's {@code lastPlan} is still one of
 * its team's plans, and a follower builds its leader's own plan again. Agreement with vanilla is the oracle tests'
 * ({@code MinerOracleTest}, {@code BuilderOracleTest}, {@code RepairOracleTest}).
 */
class SupportAiTest{
    World world;

    @BeforeEach
    void world(){
        world = UnitWorlds.world(96, 96, 11);
    }

    Building place(String block, int x, int y){
        return world.buildings().setBlock(x, y, world.content().block(block), Team.SHARDED, 0, Cause.SERVER);
    }

    Unit spawn(String type, float x, float y){
        return Unit.spawn(world, world.content().unit(type), Team.SHARDED, x, y, 0f);
    }

    int item(String name){
        return world.content().item(name).id();
    }

    static AiController ai(Unit unit){
        return unit.controller instanceof CommandAi command ? command.commandController : null;
    }

    /** Ticks until {@code done}, at most {@code max} ticks; fails if it never is. */
    void until(int max, BooleanSupplier done, String what){
        for(int i = 0; i < max; i++){
            if(done.getAsBoolean()) return;
            world.tick();
        }
        fail(what + " never happened in " + max + " ticks");
    }

    /** A fork and a restored checkpoint of the world, which then run {@code ticks} ticks beside it, hash for hash. */
    List<World> copiesAgree(int ticks){
        World fork = world.fork();
        World restored = Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
        List<World> copies = List.of(fork, restored);
        for(World copy : copies) assertEquals(world.hash(), copy.hash(), () -> world.hash().firstDifference(copy.hash()).orElse("?"));
        for(int i = 0; i < ticks; i++){
            world.tick();
            for(World copy : copies){
                copy.tick();
                int at = i;
                assertEquals(world.hash(), copy.hash(), () -> "after " + at + ": " + world.hash().firstDifference(copy.hash()).orElse("?"));
            }
        }
        return copies;
    }

    @Test
    void monosMiningGoOnInForksAndCheckpoints(){
        Building core = place("core-shard", 20, 20);
        for(int x = 30; x < 33; x++){
            for(int y = 18; y < 21; y++) world.tiles().overlay(world.tiles().pos(x, y), world.content().block("ore-copper").id());
        }
        for(int x = 14; x < 16; x++){
            for(int y = 30; y < 32; y++) world.tiles().overlay(world.tiles().pos(x, y), world.content().block("ore-lead").id());
        }
        // the world loaded empty: index the ores as a map load would
        OreIndex.of(world).loaded();
        Unit first = spawn("mono", 20 * 8f, 24 * 8f);
        spawn("mono", 22 * 8f, 24 * 8f);
        // a command AI makes its command's controller at its first update
        world.tick();
        assertInstanceOf(MinerAi.class, ai(first), "a mono's default command mines");
        until(2_000, () -> first.mineTile != -1 && first.stackAmount > 0, "a mono mining with items");
        copiesAgree(900);
        assertTrue(core.items.get(item("copper")) + core.items.get(item("lead")) > 0, "ore reached the core");
    }

    @Test
    void polysRebuildingAndFollowingGoOnInForksAndCheckpoints(){
        Building core = place("core-shard", 40, 40);
        core.items.set(item("copper"), 2_000);
        for(int i = 0; i < 8; i++) place("copper-wall-large", 32 + i * 2, 48);
        Unit leader = spawn("poly", 40 * 8f, 44 * 8f);
        spawn("poly", 44 * 8f, 44 * 8f);
        spawn("poly", 36 * 8f, 44 * 8f);
        // a command AI makes its command's controller at its first update
        world.tick();
        assertInstanceOf(BuilderAi.class, ai(leader), "a poly's default command rebuilds");
        for(int i = 0; i < 8; i++){
            Building wall = world.buildings().at(32 + i * 2, 48);
            wall.damage(wall.maxHealth * 2f);
        }
        assertEquals(8, Teams.of(world).get(Team.SHARDED).plans().size(), "each wall left a rebuild plan");
        // one poly holds a team plan as its lastPlan while another follows a builder
        until(3_000, () -> following() != null && withLastPlan() != null, "a follower beside a poly with a last plan");
        Unit follower = following();
        BuilderAi follows = (BuilderAi)ai(follower);
        assertSame(Builder.buildPlan(follows.following), follower.plans.first(), "the follower builds its leader's own plan");
        int planner = withLastPlan().id();

        List<World> copies = copiesAgree(0);
        for(World copy : copies){
            BuilderAi ai = (BuilderAi)ai(unit(copy, planner));
            assertNotNull(ai.lastPlan, "the copy's lastPlan");
            assertTrue(Teams.of(copy).get(Team.SHARDED).plans().stream().anyMatch(plan -> plan == ai.lastPlan),
                "the copy's lastPlan is one of its team's plans");
            // FollowerPlans: the copy's follower holds its leader's plan object again before anything runs
            Unit unit = unit(copy, follower.id());
            assertSame(Builder.buildPlan(((BuilderAi)ai(unit)).following), unit.plans.first(), "the copy's follower shares its leader's plan");
        }
        // and still does after a tick, as the world's
        world.tick();
        assertNotNull(follows.following, "still following");
        for(World copy : copies){
            copy.tick();
            assertEquals(world.hash(), copy.hash());
            Unit unit = unit(copy, follower.id());
            assertSame(Builder.buildPlan(((BuilderAi)ai(unit)).following), unit.plans.first(), "the copy's follower builds its leader's own plan");
        }
        copiesAgree(1_200);
        for(int i = 0; i < 8; i++){
            assertEquals("copper-wall-large", world.buildings().at(32 + i * 2, 48).block().name(), "wall " + i + " stands again");
        }
    }

    static Unit unit(World world, int id){
        for(int i = 0; i < world.units().size(); i++) if(world.units().get(i).id() == id) return world.units().get(i);
        throw new AssertionError("no unit " + id);
    }

    /** A poly following another builder, or null. */
    Unit following(){
        for(int i = 0; i < world.units().size(); i++){
            Unit unit = world.units().get(i);
            if(ai(unit) instanceof BuilderAi ai && ai.following != null && !unit.plans.isEmpty()) return unit;
        }
        return null;
    }

    /** A poly whose lastPlan is one of its team's plans, or null. */
    Unit withLastPlan(){
        List<BlockPlan> plans = Teams.of(world).get(Team.SHARDED).plans();
        for(int i = 0; i < world.units().size(); i++){
            Unit unit = world.units().get(i);
            if(ai(unit) instanceof BuilderAi ai && ai.lastPlan != null && plans.stream().anyMatch(plan -> plan == ai.lastPlan)) return unit;
        }
        return null;
    }

    @Test
    void megasRepairingGoOnInForksAndCheckpoints(){
        place("core-shard", 40, 40);
        var hurt = new ArrayList<Building>();
        for(int i = 0; i < 4; i++) hurt.add(place(i % 2 == 0 ? "titanium-wall" : "copper-wall", 34 + i * 4, 48));
        Unit mega = spawn("mega", 40 * 8f, 44 * 8f);
        spawn("mega", 36 * 8f, 36 * 8f);
        // a command AI makes its command's controller at its first update
        world.tick();
        assertInstanceOf(RepairAi.class, ai(mega), "a mega's default command repairs");
        for(Building wall : hurt) wall.damage(wall.maxHealth * 0.7f);
        until(600, () -> ai(mega) instanceof RepairAi repair && repair.damagedTarget != null, "a mega targeting a damaged wall");
        copiesAgree(1_200);
        for(Building wall : hurt) assertFalse(wall.damaged(), "repaired: " + wall);
    }
}
