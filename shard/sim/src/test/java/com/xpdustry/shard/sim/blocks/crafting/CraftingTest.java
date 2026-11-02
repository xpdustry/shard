package com.xpdustry.shard.sim.blocks.crafting;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.blocks.heat.*;
import com.xpdustry.shard.sim.blocks.power.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The crafting and heat kinds in a Shard world: crafters fed by sources, a separator's seeded picks, heat through a
 * redirector into a heat crafter and gone when the redirector breaks, the flux reactor, forks and checkpoints.
 * {@code CraftingOracleTest} and {@code HeatOracleTest} hold them to vanilla frame by frame.
 */
class CraftingTest{
    static final Epoch BLOCKS = VanillaWorlds.VANILLA;

    World world;
    Buildings buildings;

    @BeforeEach
    void world(){
        world = VanillaWorlds.world(32, 32, 1);
        buildings = world.buildings();
    }

    Building place(String block, int x, int y){
        return place(block, x, y, 0);
    }

    Building place(String block, int x, int y, int rotation){
        return buildings.setBlock(x, y, BLOCKS.block(block), 1, rotation, Cause.SERVER);
    }

    void source(int x, int y, String item){
        place("item-source", x, y).configureAny(ContentRef.item(BLOCKS.item(item).id()));
    }

    int item(String name){
        return BLOCKS.item(name).id();
    }

    void tick(int ticks){
        for(int i = 0; i < ticks; i++) world.tick();
    }

    /** A graphite press on coal and a powered silicon smelter, each dumping into a container. */
    void presses(){
        source(2, 1, "coal");
        place("graphite-press", 3, 1);
        place("container", 5, 1);
        source(2, 5, "coal");
        source(2, 6, "sand");
        place("silicon-smelter", 3, 5);
        place("power-source", 3, 7);
        place("container", 5, 5);
    }

    @Test
    void craftersMakeTheirOutputAndDumpIt(){
        presses();
        var press = (GenericCrafter)buildings.at(3, 1);
        var smelter = (GenericCrafter)buildings.at(3, 5);
        assertSame(GenericCrafter.class, press.getClass());
        tick(600);
        assertTrue(buildings.at(5, 1).items.get(item("graphite")) >= 5, "the press made graphite: " + buildings.at(5, 1).items.get(item("graphite")));
        assertTrue(buildings.at(5, 5).items.get(item("silicon")) >= 10, "the smelter made silicon");
        assertEquals(1f, smelter.warmup, 0.0001f, "warmed up");
        assertEquals(smelter.progress(), smelter.sense(LAccess.progress), 0.0);
        // disabled, it stops and cools
        smelter.control(LAccess.enabled, 0, 0, 0, 0);
        tick(10);
        assertTrue(smelter.warmup < 1f);
        assertEquals(0f, smelter.efficiency);
    }

    @Test
    void aCrafterStopsWhenItsOutputWouldOverflow(){
        source(2, 1, "coal");
        var press = (GenericCrafter)place("graphite-press", 3, 1);
        tick(2000);
        assertEquals(10, press.items.get(item("graphite")), "full of graphite, nowhere to dump it");
        assertFalse(press.shouldConsume());
        assertEquals(0f, press.efficiency);
    }

    @Test
    void aMelterFeedsASeparatorThatPicksBySeed(){
        source(2, 1, "scrap");
        place("melter", 3, 1);
        place("power-source", 3, 2);
        var separator = (Separator)place("separator", 4, 1);
        place("power-source", 4, 3);
        place("container", 6, 1);
        int seed = separator.seed;
        assertEquals(Mathf.randomSeed(separator.pos(), 0, Integer.MAX_VALUE - 1), seed, "seeded from its position");
        tick(1200);
        Building container = buildings.at(6, 1);
        int separated = container.items.get(item("copper")) + container.items.get(item("lead")) + container.items.get(item("graphite"))
            + container.items.get(item("titanium"));
        assertTrue(separated > 10, "the separator separated slag: " + separated);
        assertTrue(container.items.get(item("copper")) > container.items.get(item("titanium")), "copper is the likeliest");
        assertTrue(separator.seed > seed, "each pick advances the seed");
    }

    /** An electric heater facing a redirector facing an atmospheric concentrator, all powered. */
    void heatLine(){
        place("power-source", 2, 4);
        place("electric-heater", 2, 5, 0);
        place("heat-redirector", 5, 5, 0);
        place("atmospheric-concentrator", 8, 5);
        place("power-source", 8, 7);
    }

    @Test
    void heatGoesThroughARedirectorAndStopsWhenItBreaks(){
        heatLine();
        var heater = (HeatProducer)buildings.at(2, 5);
        var redirector = (HeatConductor)buildings.at(5, 5);
        var crafter = (HeatCrafter)buildings.at(8, 5);
        tick(120);
        assertTrue(heater.heat > 0f);
        assertEquals(heater.heat(), redirector.heat, 0.0001f, "the redirector passes the heater's heat on");
        assertTrue(crafter.heat > 0f, "the concentrator gets heat");
        assertTrue(redirector.cameFrom.contains(heater.id()));
        assertTrue(crafter.liquids.get(BLOCKS.liquid("nitrogen").id()) > 0f, "it makes nitrogen");
        assertEquals(crafter.heat, crafter.sense(LAccess.heat), 0.0);
        place("air", 5, 5);
        tick(1);
        assertEquals(0f, crafter.heat, "no heat once the redirector is gone");
        tick(5);
        assertEquals(0f, crafter.efficiency);
    }

    @Test
    void aBackwardRedirectorGivesNoHeat(){
        heatLine();
        place("heat-redirector", 5, 5, 2);
        tick(60);
        // the heater faces it, so it takes the heat, but it points back and gives the crafter none
        assertEquals(3f, ((HeatConductor)buildings.at(5, 5)).heat, 0.0001f);
        assertEquals(0f, ((HeatCrafter)buildings.at(8, 5)).heat);
    }

    @Test
    void aFluxReactorRunsOnHeatAndCoolant(){
        place("heat-source", 2, 10, 0);
        var reactor = (VariableReactor)place("flux-reactor", 5, 10);
        place("battery", 8, 10);
        int cyanogen = BLOCKS.liquid("cyanogen").id();
        for(int i = 0; i < 60; i++){
            reactor.liquids.add(cyanogen, Math.min(5f, reactor.info().liquidCapacity - reactor.liquids.get(cyanogen)));
            tick(1);
        }
        assertTrue(reactor.heat > 0f, "heat from the heat source");
        assertTrue(reactor.productionEfficiency > 0f, "it makes power");
        assertTrue(reactor.instability < 0.01f, "cooled: " + reactor.instability);
        // without coolant it grows unstable and blows up
        reactor.liquids.clear();
        tick(400);
        assertFalse(reactor.isValid(), "it destroyed itself");
    }

    @Test
    void aForkContinuesBitForBit(){
        presses();
        heatLine();
        source(12, 1, "scrap");
        place("melter", 13, 1);
        place("power-source", 13, 2);
        place("separator", 14, 1);
        place("power-source", 14, 3);
        place("incinerator", 16, 1);
        place("power-source", 16, 2);
        tick(90);
        World fork = world.fork();
        for(int i = 0; i < 300; i++){
            world.tick();
            fork.tick();
            assertEquals(world.hash(), fork.hash(), "tick " + i);
        }
    }

    @Test
    void aCheckpointCarriesCraftersSeparatorsAndHeat(){
        presses();
        heatLine();
        source(12, 1, "scrap");
        place("melter", 13, 1);
        place("power-source", 13, 2);
        place("separator", 14, 1);
        place("power-source", 14, 3);
        place("heat-router", 12, 12, 0);
        place("heat-source", 10, 12, 0);
        place("flux-reactor", 16, 12);
        tick(150);
        World restored = Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
        assertEquals(world.hash(), restored.hash(), () -> world.hash().firstDifference(restored.hash()).orElse("?"));
        assertEquals(((Separator)buildings.at(14, 1)).seed, ((Separator)restored.buildings().at(14, 1)).seed);
        assertEquals(((HeatConductor)buildings.at(12, 12)).cameFrom.toSeq().size, ((HeatConductor)restored.buildings().at(12, 12)).cameFrom.toSeq().size);
        for(int tick = 0; tick < 200; tick++){
            world.tick();
            restored.tick();
            int at = tick;
            assertEquals(world.hash(), restored.hash(), () -> "tick " + at + ": " + world.hash().firstDifference(restored.hash()).orElse("?"));
        }
    }
}
