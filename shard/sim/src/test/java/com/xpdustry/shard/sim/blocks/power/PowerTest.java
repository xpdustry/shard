package com.xpdustry.shard.sim.blocks.power;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Power graphs and the power kinds in a Shard world: a generator powering a consumer through an autolinked node,
 * splits and the ids they draw, batteries, link configs, beams, forks and checkpoints. {@code PowerOracleTest} and
 * {@code GeneratorOracleTest} hold them to vanilla frame by frame.
 */
class PowerTest{
    static final Epoch BLOCKS = VanillaWorlds.VANILLA;

    World world;
    Buildings buildings;

    @BeforeEach
    void world(){
        world = VanillaWorlds.world(24, 24, 1);
        buildings = world.buildings();
    }

    Building place(String block, int x, int y){
        return buildings.setBlock(x, y, BLOCKS.block(block), 1, 0, Cause.SERVER);
    }

    /** As construction finishes a block: placed, then {@code placed()}. */
    Building build(String block, int x, int y){
        Building building = place(block, x, y);
        building.placed();
        return building;
    }

    /** A combustion generator burning coal from a source, a node, and an illuminator. */
    void grid(){
        place("item-source", 1, 1).configureAny(ContentRef.item(BLOCKS.item("coal").id()));
        place("combustion-generator", 2, 1);
        build("power-node", 4, 3);
        build("illuminator", 7, 3);
    }

    void tick(int ticks){
        for(int i = 0; i < ticks; i++) world.tick();
    }

    @Test
    void aGeneratorPowersAConsumerThroughAnAutolinkedNode(){
        grid();
        Building generator = buildings.at(2, 1), node = buildings.at(4, 3), light = buildings.at(7, 3);
        assertTrue(node.power.links.contains(generator.pos()), "the node took the generator when it was placed");
        assertTrue(node.power.links.contains(light.pos()), "the illuminator linked itself to the node");
        assertTrue(light.power.links.contains(node.pos()), "links are kept on both ends");
        assertSame(generator.power.graph, light.power.graph);
        tick(120);
        assertTrue(((Generator)generator).productionEfficiency > 0f, "the generator burns coal");
        assertEquals(1f, light.power.status, "fully satisfied");
        assertEquals(1f, light.power.graph.getSatisfaction());
        assertTrue(PowerGraphs.of(world).list().toList().contains(light.power.graph), "the graph updates");
    }

    @Test
    void removingTheNodeSplitsTheGraphOnePerBranch(){
        grid();
        tick(60);
        Building generator = buildings.at(2, 1), light = buildings.at(7, 3);
        PowerGraph old = light.power.graph;
        int ids = world.ids().peek();
        place("air", 4, 3);
        assertEquals(ids + 2, world.ids().peek(), "a new graph, and its updater's id, for each branch");
        assertNotSame(generator.power.graph, light.power.graph);
        assertNotSame(old, light.power.graph);
        assertFalse(old.isAdded(), "the old graph's updater left the group");
        assertEquals(0, light.power.links.size, "the link went with the node");
        tick(2);
        assertEquals(0f, light.power.status, "nothing powers the illuminator now");
    }

    @Test
    void aBatteryStoresTheSurplusAndCarriesTheConsumersAfter(){
        grid();
        Building battery = place("battery", 3, 1);
        assertSame(battery.power.graph, buildings.at(2, 1).power.graph, "joined by contact");
        tick(300);
        assertTrue(battery.power.status > 0f, "charged: " + battery.power.status);
        buildings.at(1, 1).control(LAccess.enabled, 0, 0, 0, 0);
        // the generator burns what it holds first
        tick(1300);
        assertEquals(0f, ((Generator)buildings.at(2, 1)).productionEfficiency);
        float charged = battery.power.status;
        tick(600);
        assertTrue(battery.power.status < charged, "drained by the illuminator: " + battery.power.status + " < " + charged);
        assertEquals(1f, buildings.at(7, 3).power.status, "still powered from the battery");
    }

    @Test
    void linkConfigsToggleOneAndSetAll(){
        var node = (LaserNode)place("power-node", 5, 5);
        Building east = place("illuminator", 8, 5), north = place("illuminator", 5, 8);
        assertEquals(0, node.power.links.size, "an editor placement doesn't autolink");
        node.configureAny(east.pos());
        assertSame(node.power.graph, east.power.graph);
        node.configureAny(east.pos());
        assertEquals(0, node.power.links.size, "the same link again unlinks");
        assertNotSame(node.power.graph, east.power.graph);
        node.configureAny(new Point2[]{new Point2(3, 0), new Point2(0, 3)});
        assertArrayEquals(new Point2[]{new Point2(3, 0), new Point2(0, 3)}, node.config());
        assertSame(node.power.graph, north.power.graph);
        node.configureAny(new Point2[]{new Point2(0, 3)});
        assertArrayEquals(new Point2[]{new Point2(0, 3)}, node.config(), "a whole list replaces the links");
        assertNotSame(node.power.graph, east.power.graph);
    }

    @Test
    void aBeamNodeLinksTheFirstPoweredBuildingAndFollowsChanges(){
        var beam = (BeamNode)place("beam-node", 5, 5);
        Building light = place("illuminator", 9, 5);
        place("power-node", 5, 3);
        Building south = place("illuminator", 5, 1);
        tick(1);
        assertSame(light, beam.links[0]);
        assertSame(south, beam.links[3], "a laser node doesn't stop a beam");
        assertSame(beam.power.graph, light.power.graph);
        place("plastanium-wall", 7, 5);
        tick(1);
        assertNull(beam.links[0], "insulation stops it");
        assertNotSame(beam.power.graph, light.power.graph);
    }

    @Test
    void aForkKeepsEachGraphSharedByItsMembers(){
        grid();
        tick(30);
        World fork = world.fork();
        Building generator = fork.buildings().at(2, 1), light = fork.buildings().at(7, 3);
        assertSame(generator.power.graph, light.power.graph);
        assertNotSame(buildings.at(2, 1).power.graph, generator.power.graph);
        assertSame(fork, generator.power.graph.world());
        assertTrue(PowerGraphs.of(fork).list().toList().contains(generator.power.graph));
        for(int i = 0; i < 60; i++){
            world.tick();
            fork.tick();
            assertEquals(world.hash(), fork.hash(), "tick " + i);
        }
    }

    @Test
    void aCheckpointCarriesGraphsLinksBeamsAndReactors(){
        grid();
        place("battery", 3, 1);
        place("diode", 4, 1);
        place("battery", 5, 1);
        place("beam-node", 12, 3);
        place("illuminator", 16, 3);
        place("item-source", 10, 10).configureAny(ContentRef.item(BLOCKS.item("thorium").id()));
        place("thorium-reactor", 12, 10);
        build("power-node-large", 12, 16);
        tick(90);
        assertTrue(((NuclearReactor)buildings.at(12, 10)).heat > 0f);

        World restored = Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
        assertEquals(world.hash(), restored.hash(), () -> world.hash().firstDifference(restored.hash()).orElse("?"));
        Buildings copy = restored.buildings();
        assertSame(copy.at(2, 1).power.graph, copy.at(7, 3).power.graph);
        assertSame(copy.at(16, 3), ((BeamNode)copy.at(12, 3)).links[0]);
        assertEquals(PowerGraphs.of(world).list().size(), PowerGraphs.of(restored).list().size());
        for(int i = 0; i < 120; i++){
            world.tick();
            restored.tick();
            int tick = i;
            assertEquals(world.hash(), restored.hash(), () -> "tick " + tick + ": " + world.hash().firstDifference(restored.hash()).orElse("?"));
        }
    }
}
