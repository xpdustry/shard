package com.xpdustry.shard.sim.team;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.sim.blocks.storage.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.sim.world.Observer;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Vanilla's {@code CoreChangeEvent} as a domain event ({@link CoreChangeEvent}), one case per firing site of
 * {@code CoreBuild}: {@code created} (CoreBlock.java:593), {@code changeTeam} (:606) and {@code onDestroyed} (:708);
 * a core payload made off the map fires nothing until it is placed ({@code BuildingComp.init} runs {@code created}).
 * The order among a tick's other events is {@code PvpOutcomeOracleTest}'s, against vanilla's listener.
 */
class CoreChangeEventTest{
    World world;
    Observer events;

    @BeforeEach
    void world(){
        world = UnitWorlds.world(60, 60, 7);
        Rules rules = world.part(WorldRules.class).rules;
        rules.waves = false;
        rules.pvp = true;
        events = world.observe(1 << 10, List.of(CoreChangeEvent.class), null);
    }

    List<WorldEvent> tick(){
        world.tick();
        return events.drain();
    }

    CoreChangeEvent event(int x, int y, int team, CoreChangeEvent.Change change){
        return new CoreChangeEvent(world.ticks(), Point2.pack(x, y), world.content().block("core-shard").id(), team, change);
    }

    @Test
    void aPlacedCoreIsCreated(){
        world.buildings().setBlock(10, 10, world.content().block("core-shard"), Team.SHARDED, 0, Cause.SERVER);
        CoreChangeEvent expected = event(10, 10, Team.SHARDED, CoreChangeEvent.Change.CREATED);
        assertEquals(List.of(expected), tick());
        world.buildings().setBlock(20, 20, world.content().block("copper-wall"), Team.SHARDED, 0, Cause.SERVER);
        assertEquals(List.of(), tick(), "only cores fire it");
    }

    @Test
    void aCoreThatChangesTeamFiresWithItsNewTeam(){
        Core core = (Core)world.buildings().setBlock(10, 10, world.content().block("core-shard"), Team.SHARDED, 0, Cause.SERVER);
        tick();
        core.changeTeam(Team.CRUX, Cause.SERVER);
        CoreChangeEvent expected = event(10, 10, Team.CRUX, CoreChangeEvent.Change.TEAM);
        assertEquals(List.of(expected), tick());
        core.changeTeam(Team.CRUX, Cause.SERVER);
        assertEquals(List.of(), tick(), "vanilla returns early on the same team");
    }

    @Test
    void aDestroyedCoreFiresWithTheTeamThatLostIt(){
        world.part(WorldRules.class).rules.coreCapture = false;
        Building core = world.buildings().setBlock(10, 10, world.content().block("core-shard"), Team.SHARDED, 0, Cause.SERVER);
        tick();
        world.buildings().kill(core);
        CoreChangeEvent expected = event(10, 10, Team.SHARDED, CoreChangeEvent.Change.DESTROYED);
        assertEquals(List.of(expected), tick());
    }

    /**
     * A capture fires it three times, as vanilla does: destroyed, then created by {@code afterDestroyed}'s
     * {@code tile.setBlock} (CoreBlock.java:726), then created again by the posted {@code setNet}, whose
     * {@code Call.setTile} sets the block on the server too (:734).
     */
    @Test
    void aCapturedCoreIsDestroyedThenCreatedTwiceForItsCaptor(){
        world.part(WorldRules.class).rules.coreCapture = true;
        Core core = (Core)world.buildings().setBlock(10, 10, world.content().block("core-shard"), Team.SHARDED, 0, Cause.SERVER);
        tick();
        core.damage(Team.CRUX, 1f);
        world.buildings().kill(core);
        var expected = List.of(event(10, 10, Team.SHARDED, CoreChangeEvent.Change.DESTROYED), event(10, 10, Team.CRUX, CoreChangeEvent.Change.CREATED),
            event(10, 10, Team.CRUX, CoreChangeEvent.Change.CREATED));
        assertEquals(expected, tick());
    }

    @Test
    void aCorePayloadFiresOnlyOncePlaced(){
        Building payload = world.buildings().createPayload(world.content().block("core-shard"), Team.SHARDED);
        assertEquals(List.of(), tick(), "made off the map");
        world.buildings().place(payload, 30, 30, 0, Cause.SERVER);
        var expected = List.of(event(30, 30, Team.SHARDED, CoreChangeEvent.Change.CREATED));
        assertEquals(expected, tick());
    }
}
