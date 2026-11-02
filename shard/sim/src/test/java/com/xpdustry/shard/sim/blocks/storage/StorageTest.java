package com.xpdustry.shard.sim.blocks.storage;

import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.game.*;
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
 * Cores and storage: item acceptance and incineration, linking and its capacity, sharing one module across a team's
 * cores and through forks and checkpoints, unlinking on removal, team data, player spawns and {@link Play}. Exact
 * agreement with vanilla is {@code StorageOracleTest}'s and {@code CoreSpawnOracleTest}'s.
 */
class StorageTest{
    static final ContentEpoch VANILLA = VanillaWorlds.VANILLA;
    static final int COPPER = VANILLA.item("copper").id(), SAND = VANILLA.item("sand").id();

    World world;
    Rules rules;

    @BeforeEach
    void world(){
        world = UnitWorlds.world(40, 40, 3);
        rules = world.part(WorldRules.class).rules;
        rules.coreIncinerates = false;
    }

    Building place(String block, int x, int y, int team){
        return world.buildings().setBlock(x, y, VANILLA.block(block), team, 0, Cause.SERVER);
    }

    @Test
    void aCoreTakesItemsUpToItsCapacityThenBurnsThem(){
        var core = (Core)place("core-shard", 10, 10, Team.SHARDED);
        assertEquals(4000, core.storageCapacity);
        core.items.set(COPPER, 3999);
        assertTrue(core.acceptItem(core, COPPER));
        core.handleItem(core, COPPER);
        assertEquals(4000, core.items.get(COPPER));
        assertFalse(core.acceptItem(core, COPPER));
        long before = world.rand().getState(0);
        core.handleItem(core, COPPER);
        assertEquals(4000, core.items.get(COPPER), "burnt");
        assertNotEquals(before, world.rand().getState(0), "the burn effect's chance draws");
        rules.coreIncinerates = true;
        assertTrue(core.acceptItem(core, COPPER), "incinerating cores take everything");
        assertEquals(Integer.MAX_VALUE / 2, core.getMaximumAccepted(COPPER));
        core.handleStack(COPPER, 50, Team.SHARDED);
        assertEquals(4000, core.items.get(COPPER), "a stack is capped at the capacity");
    }

    @Test
    void erekirCoresBurnItemsTheyCantBuildWith(){
        var core = (Core)place("core-bastion", 10, 10, Team.SHARDED);
        assertFalse(VANILLA.item("sand").buildable());
        core.handleItem(core, SAND);
        core.handleStack(SAND, 30, Team.SHARDED);
        assertEquals(0, core.items.get(SAND));
        core.handleItem(core, VANILLA.item("beryllium").id());
        assertEquals(1, core.items.total());
    }

    @Test
    void adjacentStorageLinksToTheCoreAndAddsItsCapacity(){
        var core = (Core)place("core-shard", 10, 10, Team.SHARDED);
        var vault = (Storage)place("vault", 13, 10, Team.SHARDED);
        var crux = (Storage)place("container", 10, 13, Team.CRUX);
        var far = (Storage)place("container", 20, 20, Team.SHARDED);
        assertSame(core, vault.linkedCore);
        assertSame(core.items, vault.items);
        assertEquals(4000 + 1000, core.storageCapacity);
        assertNull(crux.linkedCore, "another team's storage doesn't link");
        assertNull(far.linkedCore);
        vault.handleItem(vault, COPPER);
        assertEquals(1, core.items.get(COPPER), "linked storage hands items to the core");
        assertEquals(core.getMaximumAccepted(COPPER), vault.getMaximumAccepted(COPPER));
        assertEquals(6, vault.explosionItemCap(), "linked storage explodes less");

        // the reinforced vault doesn't merge
        var reinforced = (Storage)place("reinforced-vault", 10, 7, Team.SHARDED);
        assertNull(reinforced.linkedCore);
        assertNotSame(core.items, reinforced.items);
    }

    @Test
    void aTeamsCoresShareOneModuleAndTheirCapacity(){
        var first = (Core)place("core-shard", 10, 10, Team.SHARDED);
        first.items.set(COPPER, 500);
        place("container", 12, 10, Team.SHARDED);
        var second = (Core)place("core-foundation", 30, 30, Team.SHARDED);
        assertSame(first.items, second.items);
        assertEquals(500, second.items.get(COPPER));
        assertEquals(4000 + 300 + 9000, first.storageCapacity);
        assertEquals(first.storageCapacity, second.storageCapacity);
        Teams teams = Teams.of(world);
        assertEquals(List.of(first, second), teams.cores(Team.SHARDED));
        assertSame(first, teams.core(Team.SHARDED));
        assertTrue(teams.isActive(world, Team.SHARDED));
    }

    @Test
    void removingACoreGivesLinkedStorageItsShare(){
        var core = (Core)place("core-shard", 10, 10, Team.SHARDED);
        var container = (Storage)place("container", 12, 10, Team.SHARDED);
        var vault = (Storage)place("vault", 10, 13, Team.SHARDED);
        core.items.set(COPPER, 1300);
        core.items.set(SAND, 7);
        world.buildings().remove(core, Cause.SERVER);
        assertNull(container.linkedCore);
        assertNotSame(container.items, vault.items);
        // by capacity: 300 / 1300 and 1000 / 1300 of each, capped
        assertEquals(300, container.items.get(COPPER));
        assertEquals(1000, vault.items.get(COPPER));
        assertEquals((int)Math.min(300, 7 * 300f / 1300), container.items.get(SAND));
        assertEquals(List.of(), Teams.of(world).cores(Team.SHARDED));
    }

    @Test
    void forksAndCheckpointsKeepTheSharing(){
        var core = (Core)place("core-shard", 10, 10, Team.SHARDED);
        place("container", 12, 10, Team.SHARDED);
        place("core-shard", 30, 30, Team.SHARDED);
        core.items.set(COPPER, 10);
        world.tick();

        World fork = world.fork();
        World restored = Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
        for(World copy : List.of(fork, restored)){
            assertEquals(world.hash(), copy.hash(), () -> world.hash().firstDifference(copy.hash()).orElse("?"));
            var c = (Core)copy.buildings().at(10, 10);
            var s = (Storage)copy.buildings().at(12, 10);
            var other = (Core)copy.buildings().at(30, 30);
            assertSame(c.items, s.items);
            assertSame(c.items, other.items);
            assertNotSame(core.items, c.items);
            assertSame(c, s.linkedCore);
            assertEquals(List.of(c, other), Teams.of(copy).cores(Team.SHARDED));
            s.handleItem(s, COPPER);
            assertEquals(11, other.items.get(COPPER));
        }
        assertEquals(10, core.items.get(COPPER), "the source is untouched");
    }

    @Test
    void deadPlayersRespawnAtTheBestCoreWithItsUnitType(){
        place("core-shard", 10, 10, Team.SHARDED);
        var nucleus = (Core)place("core-nucleus", 30, 30, Team.SHARDED);
        Observer events = world.observe(64, List.of(CoreEvent.class), null);
        Player player = Player.create(world);
        player.team(Team.SHARDED);
        player.add();
        for(int i = 0; i < Player.DEATH_DELAY - 1; i++) world.tick();
        assertNull(player.unit());
        int next = world.ids().peek();
        world.tick();

        Unit unit = player.unit();
        assertNotNull(unit, "spawned after the death delay");
        assertEquals("gamma", unit.type().name(), "the largest core's type");
        assertEquals(next, unit.id(), "the unit takes the next entity id");
        assertEquals(nucleus.x(), unit.x);
        assertEquals(nucleus.y(), unit.y, 1f, "pushed up a little since");
        assertTrue(unit.spawnedByCore);
        assertTrue(unit.isAdded());
        assertSame(player, unit.player());
        List<WorldEvent> published = events.drain();
        assertEquals(1, published.size());
        var spawn = (CoreEvent.PlayerSpawn)published.getFirst();
        assertEquals(List.of(nucleus.tile(), player.id(), unit.id()), List.of(spawn.pos(), spawn.player(), spawn.unit()));
    }

    @Test
    void eachCoreSpawnsItsOwnType(){
        var expected = new LinkedHashMap<String, String>();
        expected.put("core-shard", "alpha");
        expected.put("core-foundation", "beta");
        expected.put("core-nucleus", "gamma");
        expected.put("core-bastion", "evoke");
        expected.put("core-citadel", "incite");
        expected.put("core-acropolis", "emanate");
        for(var entry : expected.entrySet()){
            world();
            var core = (Core)place(entry.getKey(), 10, 10, Team.SHARDED);
            assertEquals(entry.getValue(), core.unitType().name(), entry.getKey());
        }
    }

    @Test
    void playGivesTheLoadoutAndHeals(){
        var core = (Core)place("core-shard", 10, 10, Team.SHARDED);
        var crux = (Core)place("core-shard", 30, 30, Team.CRUX);
        core.items.set(SAND, 40);
        core.health = 5f;
        rules.loadout = List.of(new ItemStack(COPPER, 300), new ItemStack(SAND, 9000));
        rules.waveSpacing = 600f;
        Play.Started started = Play.play(world);
        assertEquals(1200f, started.wavetime());
        assertEquals(300, core.items.get(COPPER));
        assertEquals(4000, core.items.get(SAND), "capped at the free space");
        assertEquals(4300, core.items.total(), "nothing else");
        assertEquals(core.maxHealth, core.health);
        assertEquals(300, crux.items.get(COPPER), "every active team");
        rules.initialWaveSpacing = 90f;
        assertEquals(90f, Play.play(world).wavetime());
    }
}
