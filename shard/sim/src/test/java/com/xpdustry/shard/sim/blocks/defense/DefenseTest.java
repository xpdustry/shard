package com.xpdustry.shard.sim.blocks.defense;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.team.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.sim.world.Observer;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Walls, doors, shield walls, mines and thrusters: chains, toggles and solidity, the processor's gate, auto doors, the
 * shield, the mine's trigger, and every kind's state through a fork and a checkpoint. Exact agreement with vanilla is
 * {@code DefenseOracleTest}'s.
 */
class DefenseTest{
    static final ContentEpoch VANILLA = VanillaWorlds.VANILLA;

    World world;

    @BeforeEach
    void world(){
        world = UnitWorlds.uncapped(UnitWorlds.world(40, 40, 3));
    }

    Building place(String block, int x, int y, int team){
        return world.buildings().setBlock(x, y, VANILLA.block(block), team, 0, Cause.SERVER);
    }

    @Test
    void everyWallBlockIsAWallKind(){
        assertInstanceOf(Wall.class, place("copper-wall", 5, 5, Team.SHARDED));
        assertInstanceOf(Wall.class, place("plastanium-wall-large", 7, 5, Team.SHARDED));
        assertInstanceOf(Wall.class, place("surge-wall", 10, 5, Team.SHARDED));
        assertInstanceOf(Thruster.class, place("thruster", 14, 5, Team.SHARDED));
        assertInstanceOf(ShieldWall.class, place("shielded-wall", 20, 5, Team.SHARDED));
        assertInstanceOf(Door.class, place("door-large", 22, 5, Team.SHARDED));
        assertInstanceOf(AutoDoor.class, place("blast-door", 25, 5, Team.SHARDED));
        assertInstanceOf(ShockMine.class, place("shock-mine", 28, 5, Team.SHARDED));
    }

    @Test
    void touchingDoorsChainAndToggleTogether(){
        var a = (Door)place("door", 10, 10, Team.SHARDED);
        var b = (Door)place("door", 10, 11, Team.SHARDED);
        var c = (Door)place("door", 10, 12, Team.SHARDED);
        var crux = (Door)place("door", 11, 12, Team.CRUX);
        assertEquals(List.of(c, b, a), List.of(c.chained()), "the last placed door walked the chain from itself");
        assertSame(c.chain, a.chain, "one chain object, as vanilla's one Seq");
        assertEquals(List.of(crux), List.of(crux.chained()), "another team's door is no link");
        TileGrid tiles = world.tiles();
        assertTrue(tiles.buildSolid(tiles.pos(10, 11)), "shut");
        b.configureAny(true);
        for(Door door : List.of(a, b, c)) assertTrue(door.open);
        assertFalse(crux.open);
        assertFalse(tiles.buildSolid(tiles.pos(10, 11)), "open: the grid follows");
        assertEquals(1, b.sense(LAccess.enabled));
        assertEquals(Boolean.TRUE, b.config());
    }

    /** A removed door splits its chain: its neighbours' proximity is rebuilt without it (Tile.changeBuild). */
    @Test
    void aRemovedDoorSplitsItsChain(){
        var a = (Door)place("door", 10, 10, Team.SHARDED);
        var b = (Door)place("door", 10, 11, Team.SHARDED);
        var c = (Door)place("door", 10, 12, Team.SHARDED);
        world.buildings().remove(b, Cause.SERVER);
        assertEquals(List.of(a), List.of(a.chained()));
        assertEquals(List.of(c), List.of(c.chained()));
        var d = (Door)place("door", 10, 11, Team.SHARDED);
        assertEquals(List.of(d, c, a), List.of(d.chained()), "joined again, breadth first from the new door, in edge order");
    }

    @Test
    void processorsToggleDoorsAtMostEvery80Ticks(){
        var door = (Door)place("door", 10, 10, Team.SHARDED);
        // the toggle timer first fires once 80 ticks have passed
        for(int i = 0; i < 80; i++) world.tick();
        door.control(LAccess.enabled, 1, 0, 0, 0);
        assertTrue(door.open);
        door.control(LAccess.enabled, 0, 0, 0, 0);
        assertTrue(door.open, "too soon");
        for(int i = 0; i < 80; i++) world.tick();
        door.control(LAccess.enabled, 0, 0, 0, 0);
        assertFalse(door.open);
        assertTrue(door.enabled, "control enabled toggles the door, never the building");
    }

    @Test
    void aClosingDoorSkipsItselfWhileAUnitStandsInIt(){
        var a = (Door)place("door", 10, 10, Team.SHARDED);
        var b = (Door)place("door", 10, 11, Team.SHARDED);
        a.configureAny(true);
        Unit.spawn(world, VANILLA.unit("dagger"), Team.SHARDED, 10 * 8f, 11 * 8f, 0f);
        world.tick();
        a.configureAny(false);
        assertFalse(a.open);
        assertTrue(b.open, "the dagger holds it open");
    }

    @Test
    void autoDoorsOpenForTheirTeamsGroundUnits(){
        var door = (AutoDoor)place("blast-door", 20, 20, Team.SHARDED);
        Observer events = world.observe(64, List.of(DefenseEvent.class), null);
        Unit enemy = Unit.spawn(world, VANILLA.unit("dagger"), Team.CRUX, 22 * 8f, 26 * 8f, 0f);
        for(int i = 0; i < 25; i++) world.tick();
        assertFalse(door.open, "not for an enemy");
        enemy.remove();
        Unit.spawn(world, VANILLA.unit("dagger"), Team.SHARDED, 18 * 8f, 20 * 8f, 0f);
        for(int i = 0; i < 25; i++) world.tick();
        assertTrue(door.open);
        assertFalse(world.tiles().buildSolid(door.tile()));
        List<WorldEvent> toggles = events.drain();
        assertEquals(1, toggles.size(), toggles.toString());
        var toggled = (DefenseEvent.AutoDoorToggled)toggles.getFirst();
        assertEquals(door.tile(), toggled.pos());
        assertTrue(toggled.open());
    }

    @Test
    void aShieldTakesHitsFirstOnlyWhilePowered(){
        // 2x2, over (10..11, 10..11)
        var wall = (ShieldWall)place("shielded-wall", 10, 10, Team.SHARDED);
        assertEquals(900f, wall.shield);
        world.tick();
        wall.damage(100f);
        assertEquals(wall.maxHealth - 100f, wall.health, 0.001f, "unpowered: broken, the wall takes it");
        assertEquals(900f, wall.shield);
        place("power-source", 12, 10, Team.SHARDED);
        for(int i = 0; i < 10 && wall.broken(); i++) world.tick();
        assertFalse(wall.broken(), "powered: " + wall.power.status);
        float health = wall.health;
        wall.damage(1000f);
        assertEquals(0f, wall.shield);
        assertEquals(600f, wall.breakTimer, "broken for breakCooldown");
        assertEquals(health - 100f, wall.health, 0.001f, "the rest went through");
        assertEquals(1f, wall.hit);
    }

    @Test
    void aShockMineFiresUnderAnEnemyAndHurtsItself(){
        var mine = (ShockMine)place("shock-mine", 10, 10, Team.CRUX);
        // the damage timer first fires once 80 ticks have passed
        for(int i = 0; i < 100; i++) world.tick();
        long before = world.rand().getState(0);
        Unit dagger = Unit.spawn(world, VANILLA.unit("dagger"), Team.SHARDED, 10 * 8f, 10 * 8f, 0f);
        dagger.controller(new NoAi());
        world.tick();
        assertTrue(mine.health < mine.maxHealth, "the mine took its tileDamage");
        assertNotEquals(before, world.rand().getState(0), "the tendrils' angles");
        assertTrue(world.bullets().size() > 0, "lightning");
    }

    @Test
    void forksAndCheckpointsKeepEveryKindsState(){
        var a = (Door)place("door", 10, 10, Team.SHARDED);
        place("door", 10, 11, Team.SHARDED);
        a.configureAny(true);
        var shield = (ShieldWall)place("shielded-wall", 14, 10, Team.SHARDED);
        place("power-source", 16, 10, Team.SHARDED);
        shield.damage(50f);
        place("blast-door", 20, 20, Team.SHARDED);
        Unit.spawn(world, VANILLA.unit("dagger"), Team.SHARDED, 18 * 8f, 20 * 8f, 0f);
        var wall = (Wall)place("phase-wall", 5, 5, Team.SHARDED);
        wall.hit = 1f;
        place("thruster", 30, 30, Team.SHARDED);
        place("shock-mine", 25, 10, Team.CRUX);
        for(int i = 0; i < 30; i++) world.tick();

        World fork = world.fork();
        World restored = Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
        for(World copy : List.of(fork, restored)){
            assertEquals(world.hash(), copy.hash(), () -> world.hash().firstDifference(copy.hash()).orElse("?"));
            var door = (Door)copy.buildings().at(10, 10);
            assertTrue(door.open);
            assertEquals(2, door.chained().length);
            assertSame(door.chain, ((Door)copy.buildings().at(10, 11)).chain, "still one chain object");
            assertSame(copy.buildings().at(10, 11), door.chained()[0]);
            for(int i = 0; i < 30; i++){
                copy.tick();
            }
        }
        for(int i = 0; i < 30; i++) world.tick();
        assertEquals(world.hash(), fork.hash());
        assertEquals(world.hash(), restored.hash());
    }
}
