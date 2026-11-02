package com.xpdustry.shard.sim.building;

import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.blocks.distribution.*;
import com.xpdustry.shard.sim.blocks.sandbox.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The building base against vanilla's {@code BuildingComp}, on the ported router, item source and item void: item
 * movement, config dispatch and its event, logic control, damage and death, the indexer's lists, the draws construction
 * makes, and checkpoints of it all. {@code RouterOracleTest} holds the router to vanilla frame by frame.
 */
class BuildingBaseTest{
    static final Epoch BLOCKS = VanillaWorlds.VANILLA;
    static final int COPPER = BLOCKS.item("copper").id(), LEAD = BLOCKS.item("lead").id();

    World world;
    Buildings buildings;

    @BeforeEach
    void world(){
        world = VanillaWorlds.world(24, 24, 1);
        buildings = world.buildings();
    }

    Building place(String block, int x, int y){
        return place(block, 1, x, y);
    }

    Building place(String block, int team, int x, int y){
        return buildings.setBlock(x, y, BLOCKS.block(block), team, 0, Cause.SERVER);
    }

    @Test
    void dumpGivesEachNeighbourOneItemInProximityOrder(){
        var source = (ItemSource)place("item-source", 5, 5);
        for(int[] at : new int[][]{{4, 5}, {6, 5}, {5, 4}, {5, 6}}) place("router", at[0], at[1]);
        List<Building> order = source.proximityList();
        assertEquals(4, order.size());
        for(int i = 0; i < 4; i++){
            source.items.set(COPPER, 1);
            assertTrue(source.dump(COPPER));
            assertEquals(1, order.get(i).items.get(COPPER), "dump " + i + " goes to the next neighbour");
        }
        source.items.set(COPPER, 1);
        assertFalse(source.dump(COPPER), "every router holds an item");
        assertFalse(source.put(LEAD));
        source.offload(LEAD);
        assertEquals(1, source.items.get(LEAD), "offload keeps what no neighbour takes");
    }

    @Test
    void configsDispatchByTypeAndPublishAfterTheTick(){
        var source = (ItemSource)place("item-source", 5, 5);
        var other = (ItemSource)place("item-source", 8, 5);
        var observer = world.observe(16, List.of(BuildingEvent.Configured.class), null);
        source.configureAny(ContentRef.item(COPPER));
        assertEquals(COPPER, source.outputItem);
        assertEquals(ContentRef.item(COPPER), source.config());
        other.configured(null, source);
        assertEquals(COPPER, other.outputItem, "a building value copies that building's config");
        source.configureAny(null);
        assertEquals(-1, source.outputItem, "null clears");
        assertNull(observer.poll(), "events wait for the end of the tick");
        world.tick();
        var events = observer.drain();
        assertEquals(2, events.size(), "configured() alone sends no Call: " + events);
        var set = (BuildingEvent.Configured)events.getFirst();
        assertEquals(world.tiles().pos(5, 5), set.pos());
        assertEquals(ContentRef.item(COPPER), set.value());
        assertEquals(Cause.SERVER, set.cause());
        assertNull(((BuildingEvent.Configured)events.get(1)).value());
    }

    @Test
    void logicDisablesARouterUntilItIsEnabledAgain(){
        var source = (ItemSource)place("item-source", 4, 5);
        var router = (Router)place("router", 5, 5);
        place("item-void", 6, 5);
        source.configureAny(ContentRef.item(COPPER));
        router.control(LAccess.enabled, 0, 0, 0, 0);
        for(int i = 0; i < 5; i++) world.tick();
        assertEquals(1, router.items.get(COPPER), "a disabled router takes one item and keeps it");
        assertEquals(BlockStatus.logicDisable, router.status(0));
        router.control(LAccess.enabled, 1, 0, 0, 0);
        world.tick();
        assertEquals(-1, router.lastItem, "it hands the item to the void at once");
        assertEquals(0, router.items.total());
    }

    @Test
    void damageFollowsTheDamagedListAndDeathClearsTheTile(){
        Building router = place("router", 5, 5);
        int pos = router.tile();
        var observer = world.observe(16, List.of(BuildingEvent.HealthChanged.class, BuildingEvent.Destroyed.class), null);
        router.damage(router.maxHealth / 2);
        assertTrue(router.damaged());
        assertEquals(List.of(router), buildings.damaged(1));
        router.heal();
        assertEquals(List.of(), buildings.damaged(1));
        router.damage(router.maxHealth * 2);
        assertFalse(router.isValid());
        assertNull(buildings.at(pos));
        assertEquals(BLOCKS.block("air").id(), world.tiles().block(pos));
        world.tick();
        var events = observer.drain();
        assertEquals(List.of(BuildingEvent.HealthChanged.class, BuildingEvent.HealthChanged.class,
            BuildingEvent.HealthChanged.class, BuildingEvent.Destroyed.class), events.stream().map(Object::getClass).toList());
        var destroyed = (BuildingEvent.Destroyed)events.getLast();
        assertEquals(pos, destroyed.pos());
        assertEquals(BLOCKS.block("router").id(), destroyed.block());
        assertEquals(1, destroyed.team());
    }

    @Test
    void flagListsFollowPlacementTeamChangesAndRemoval(){
        // MendProjector.java:48
        Building mender = place("mend-projector", 5, 5);
        assertEquals(List.of(mender), buildings.flagged(1, BlockFlag.blockRepair));
        buildings.changeTeam(mender, 2, Cause.SERVER);
        assertEquals(List.of(), buildings.flagged(1, BlockFlag.blockRepair));
        assertEquals(List.of(mender), buildings.flagged(2, BlockFlag.blockRepair));
        assertTrue(buildings.activeTeams().contains(1) && buildings.activeTeams().contains(2), "teams that ever built");
        buildings.remove(mender, Cause.SERVER);
        assertEquals(List.of(), buildings.flagged(2, BlockFlag.blockRepair));
    }

    @Test
    void derelictAndOutOfAreaBuildingsMayNotUpdate(){
        Building derelict = place("router", 0, 5, 5);
        derelict.checkAllowUpdate();
        assertFalse(derelict.enabled);
        Building inside = place("router", 2, 2), outside = place("router", 8, 5);
        Rules rules = world.part(WorldRules.class).rules;
        rules.limitMapArea = rules.disableOutsideArea = true;
        rules.limitWidth = rules.limitHeight = 4;
        inside.checkAllowUpdate();
        outside.checkAllowUpdate();
        assertTrue(inside.enabled);
        assertFalse(outside.enabled);
        outside.enabled = true;
        rules.disableOutsideArea = false;
        outside.checkAllowUpdate();
        assertTrue(outside.enabled, "checkAllowUpdate never enables, and here it has no reason to disable");
    }

    @Test
    void legacyBlocksLeaveWhenTheMapFinishesLoading(){
        buildings.beginLoad();
        buildings.setBlock(3, 3, BLOCKS.block("legacy-mech-pad"), 1, 0, Cause.SERVER);
        buildings.setBlock(10, 10, BLOCKS.block("legacy-unit-factory-air"), 2, 1, Cause.SERVER);
        buildings.endLoad(false);
        int pad = world.tiles().pos(3, 3);
        assertNull(buildings.at(pad), "LegacyBlock.removeSelf");
        assertEquals(BLOCKS.block("air").id(), world.tiles().block(pad));
        // LegacyUnitFactory: its replacement, at the building's team and rotation
        Building factory = buildings.at(10, 10);
        assertEquals("air-factory", factory.block().name());
        assertEquals(2, factory.team);
        assertEquals(1, factory.rotation);
    }

    @Test
    void constructionDrawsIdsAndValuesAsVanillaDoes(){
        World plain = VanillaWorlds.world(24, 24, 1);
        // a mend projector: its id, its charge (MendProjector.java:85), its power graph's id
        place("mend-projector", 5, 5);
        plain.buildings().setBlock(5, 5, BLOCKS.block("router"), 1, 0, Cause.SERVER);
        plain.rand().nextLong();
        assertEquals(plain.ids().peek() + 1, world.ids().peek());
        assertEquals(plain.rand().nextLong(), world.rand().nextLong());
        // a duo: its id, then its block unit's id and three values (Turret.java:297)
        place("duo", 9, 5);
        plain.buildings().setBlock(9, 5, BLOCKS.block("router"), 1, 0, Cause.SERVER);
        for(int i = 0; i < 3; i++) plain.rand().nextLong();
        assertEquals(plain.ids().peek() + 2, world.ids().peek());
        assertEquals(plain.rand().nextLong(), world.rand().nextLong());
    }

    @Test
    void aCheckpointCarriesRoutersSourcesAndTheIndexer(){
        var copper = (ItemSource)place("item-source", 2, 5);
        for(int x = 3; x <= 8; x++) place("router", x, 5);
        place("item-void", 9, 5);
        var lead = (ItemSource)place("item-source", 5, 4);
        copper.configureAny(ContentRef.item(COPPER));
        lead.configureAny(ContentRef.item(LEAD));
        place("mend-projector", 12, 12).damage(10f);
        for(int i = 0; i < 37; i++) world.tick();

        World restored = Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
        assertEquals(world.hash(), restored.hash(), () -> world.hash().firstDifference(restored.hash()).orElse("?"));
        var live = (Router)world.buildings().at(5, 5);
        var copy = (Router)restored.buildings().at(5, 5);
        assertArrayEquals(live.cycles, copy.cycles);
        assertEquals(live.lastItem, copy.lastItem);
        assertEquals(live.lastInput, copy.lastInput);
        assertEquals(live.time, copy.time);
        assertEquals(positions(buildings.damaged(1)), positions(restored.buildings().damaged(1)));
        assertEquals(positions(buildings.flagged(1, BlockFlag.blockRepair)), positions(restored.buildings().flagged(1, BlockFlag.blockRepair)));
        for(int i = 0; i < 120; i++){
            world.tick();
            restored.tick();
            int tick = i;
            assertEquals(world.hash(), restored.hash(), () -> "tick " + tick + ": " + world.hash().firstDifference(restored.hash()).orElse("?"));
        }
    }

    static List<Integer> positions(List<Building> list){
        return list.stream().map(Building::tile).toList();
    }
}
