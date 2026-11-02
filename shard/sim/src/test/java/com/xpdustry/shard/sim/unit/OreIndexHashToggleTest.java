package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.toy.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The toggle proof for {@code HashTest.GOLDEN} (WAVES.md "Golden hash"): the toy world has no ores and no miners, and the
 * golden moved only because every world now has an {@link OreIndex}, and because {@code LogicUnits} handed its
 * floor-ore half to it. Put back in {@code LogicUnits}' section as it was fed ({@link OreHashes}), from the index's
 * floor lists, the world without the index hashes to main's golden; with the index every other section is the same.
 */
class OreIndexHashToggleTest{
    /** {@code HashTest.GOLDEN} before W3.7: main at {@code 259e603a} and {@code lead/merge-3a} at {@code df4e4b5d}. */
    static final long MAINS_GOLDEN = 0xeaafce491197919cL;

    static World churned(Systems systems){
        World world = new World(WorldConfig.of(ToyContent.INSTANCE, 48, 48, 42).withSystems(systems));
        world.part(WorldRules.class).rules.disableUnitCap = true;
        world.part(Chaos.class).enabled = true;
        for(int i = 0; i < 500; i++) world.tick();
        return world;
    }

    /** Without the index, and without W3.6's later RegenMends. */
    static Systems withoutOres(){
        var parts = new ArrayList<Class<? extends WorldPart>>();
        for(Class<? extends WorldPart> part : Registries.classes(WorldPart.class)) if(part != com.xpdustry.shard.sim.ai.command.CommandBatches.class && part != com.xpdustry.shard.sim.ai.team.TeamAis.class) if(part != OreIndex.class && part != com.xpdustry.shard.sim.blocks.defense.RegenMends.class && part != com.xpdustry.shard.sim.objectives.ObjectiveState.class
                && part != com.xpdustry.shard.sim.fog.FogControl.class) parts.add(part);
        return Systems.of(Registries.instances(PhaseHandler.class), parts);
    }

    /** The sections of {@code with}, less the ore index's, which must be there, and the later RegenMends'. */
    static List<WorldHash.Section> lessOres(WorldHash with){
        var out = new ArrayList<>(with.sections());
        assertTrue(out.removeIf(section -> section.name().equals(OreIndex.class.getName())), "the ore index section");
        // W3.6's regen projectors' map came later, left out of both sides (RegenMendsHashToggleTest)
        out.removeIf(section -> section.name().equals(com.xpdustry.shard.sim.blocks.defense.RegenMends.class.getName()));
        out.removeIf(section -> section.name().equals(com.xpdustry.shard.sim.objectives.ObjectiveState.class.getName()));
        // W4.3's fog came later, left out of both sides ({@code FogHashToggleTest})
        out.removeIf(section -> section.name().equals(com.xpdustry.shard.sim.fog.FogControl.class.getName()));
        return out;
    }

    @Test
    void withoutTheOreIndexTheGoldenIsMains(){
        World with = churned(com.xpdustry.shard.sim.ai.command.CommandBatchesHashToggleTest.builtInBefore()), without = churned(withoutOres());
        // today's logic-units section is main's less its ore half: the timeouts (none here), then fetch's lists
        var lists = new Hasher();
        without.buildings().hashTeamLists(lists.i(0));
        assertEquals(lists.value(), without.hash().section(OreHashes.LOGIC_UNITS), "no timeouts here");
        // W6.1's fill hitboxes came later, left out ({@code SpatialHashToggleTest})
        long value = com.xpdustry.shard.sim.spatial.SpatialHashes.before(without, OreHashes.before(without, OreIndex.of(with))).value();
        assertEquals(MAINS_GOLDEN, value, String.format("0x%016xL", value));
    }

    @Test
    void theOreIndexAddsOnlyItsOwnSection(){
        WorldHash with = churned(com.xpdustry.shard.sim.ai.command.CommandBatchesHashToggleTest.builtInBefore()).hash(), without = churned(withoutOres()).hash();
        assertEquals(without.sections(), lessOres(with));
    }

    @Test
    void anUnrelatedChangeFailsTheProof(){
        World with = churned(com.xpdustry.shard.sim.ai.command.CommandBatchesHashToggleTest.builtInBefore());
        WorldHash without = churned(withoutOres()).hash();
        // a tile's floor, which the index doesn't hold
        with.tiles().floor(0, with.tiles().floor(0) + 1);
        assertNotEquals(without.sections(), lessOres(with.hash()));
    }
}
