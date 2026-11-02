package com.xpdustry.shard.sim.team;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.blocks.storage.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.sim.world.Observer;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WP83: a PvP team that loses its last core is destroyed to derelict on the next tick ({@link DeadTeams}), only under
 * {@code pvp} and {@code cleanupDeadTeams}, and only once its last core is gone; a posted check survives a fork and a
 * checkpoint. Vanilla parity is {@code PvpOracleTest}'s.
 */
class DeadTeamsTest{
    World world;
    Rules rules;
    Observer events;

    @BeforeEach
    void world(){
        world = UnitWorlds.world(60, 60, 5);
        rules = world.part(WorldRules.class).rules;
        rules.waves = false;
        rules.pvp = true;
        events = world.observe(1 << 12, List.of(TeamEvent.class), null);
    }

    Building place(String block, int x, int y, int team){
        BlockDef def = world.content().block(block);
        return world.buildings().setBlock(x, y, def, team, 0, Cause.SERVER);
    }

    /** Sharded: a core; crux: two cores, walls, and a node linked to a battery (links stay within a team). */
    void bases(){
        place("core-shard", 10, 10, Team.SHARDED);
        place("core-shard", 45, 45, Team.CRUX);
        place("core-shard", 45, 30, Team.CRUX);
        for(int x = 40; x < 44; x++) place("copper-wall", x, 40, Team.CRUX);
        place("battery", 20, 10, Team.CRUX);
        Building node = place("power-node", 23, 10, Team.CRUX);
        node.configureAny(Point2.pack(20, 10));
        world.tick();
        events.drain();
        assertEquals(1, node.power.links.size, "the node linked the battery");
    }

    List<WorldEvent> tick(){
        world.tick();
        return events.drain();
    }

    @Test
    void theLastCoreLostTurnsTheTeamDerelictOnTheNextTick(){
        bases();
        world.buildings().kill(world.buildings().at(45, 30));
        assertEquals(List.of(), tick(), "a core is left");
        assertEquals(Team.CRUX, world.buildings().at(40, 40).team);

        world.buildings().kill(world.buildings().at(45, 45));
        assertEquals(Team.CRUX, world.buildings().at(40, 40).team, "not in the tick it died");
        List<WorldEvent> posted = tick();
        assertEquals(1, posted.size(), posted::toString);
        var set = assertInstanceOf(TeamEvent.SetTeams.class, posted.getFirst());
        assertEquals(Team.DERELICT, set.team());
        assertEquals(6, set.positions().length, "four walls, the battery and the node");
        for(int x = 40; x < 44; x++){
            Building wall = world.buildings().at(x, 40);
            assertTrue(wall == null || wall.team == Team.DERELICT, "wall " + x + ", unless a random kill took it");
        }
        Building node = world.buildings().at(23, 10), battery = world.buildings().at(20, 10);
        if(node != null && battery != null){
            assertEquals(List.of(Team.DERELICT, Team.DERELICT), List.of(node.team, battery.team));
            assertEquals(1, node.power.links.size, "a link within the moved buildings stays");
            assertSame(node.power.graph, battery.power.graph, "one graph again after the reflow");
        }
        assertEquals(Team.SHARDED, world.buildings().at(10, 10).team);
        assertFalse(Teams.of(world).get(Team.CRUX).plans().isEmpty(), "vanilla quirk: re-planned by the dead team");
    }

    @Test
    void onlyInPvpWithCleanupOn(){
        bases();
        rules.cleanupDeadTeams = false;
        world.buildings().kill(world.buildings().at(45, 30));
        world.buildings().kill(world.buildings().at(45, 45));
        assertEquals(List.of(), tick());
        rules.cleanupDeadTeams = true;
        rules.pvp = false;
        assertEquals(List.of(), tick(), "the check ran once, in its tick");
        assertEquals(Team.CRUX, world.buildings().at(40, 40).team);
    }

    @Test
    void aPostedCheckSurvivesAForkAndACheckpoint(){
        bases();
        world.buildings().kill(world.buildings().at(45, 30));
        world.tick();
        world.buildings().kill(world.buildings().at(45, 45));
        WorldHash hash = world.hash();
        World fork = world.fork();
        World restored = Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
        assertEquals(hash, fork.hash());
        assertEquals(hash, restored.hash());
        for(int i = 0; i < 400; i++){
            world.tick();
            fork.tick();
            restored.tick();
        }
        assertEquals(world.hash(), fork.hash());
        assertEquals(world.hash(), restored.hash());
        assertEquals(Team.DERELICT, restored.buildings().at(40, 40) == null ? Team.DERELICT : restored.buildings().at(40, 40).team);
    }
}
