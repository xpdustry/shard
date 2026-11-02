package com.xpdustry.shard.sim.blocks.liquid;

import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.blocks.sandbox.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.puddle.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Liquid blocks in a Shard world alone: what each kind does with liquid, leaks, logic {@code enabled}, and checkpoints
 * and forks of it all continuing bit for bit. {@code LiquidOracleTest} holds the same kinds to vanilla frame by frame.
 */
class LiquidTest{
    static final Epoch BLOCKS = VanillaWorlds.VANILLA;
    static final int WATER = BLOCKS.liquid("water").id(), OIL = BLOCKS.liquid("oil").id();

    World world;
    Buildings buildings;

    @BeforeEach
    void world(){
        world = VanillaWorlds.world(24, 24, 1);
        buildings = world.buildings();
    }

    Building place(String block, int x, int y, int rotation){
        return buildings.setBlock(x, y, BLOCKS.block(block), 1, rotation, Cause.SERVER);
    }

    void line(String block, int x, int y, int length){
        for(int i = 0; i < length; i++) place(block, x + i, y, 0);
    }

    void source(int x, int y, int liquid){
        place("liquid-source", x, y, 0).configureAny(ContentRef.liquid(liquid));
    }

    @Test
    void aSourceFillsATankAndAnEmptyLineSleeps(){
        source(1, 5, WATER);
        line("conduit", 2, 5, 5);
        // a 3x3 tank around (8, 5)
        var tank = place("liquid-tank", 8, 5, 0);
        var last = buildings.at(6, 5);
        for(int i = 0; i < 120; i++) world.tick();
        assertEquals(WATER, buildings.at(2, 5).liquids.current());
        assertTrue(tank.liquids.get(WATER) > 0f, "water reaches the tank");
        assertFalse(last.sleeping(), "the last conduit keeps moving");
        assertEquals(new ContentRef(com.xpdustry.shard.content.ContentType.LIQUID, WATER), buildings.at(1, 5).config());

        // a conduit stops filling a tank once their fractions meet, so take the tank away: the line leaks out
        buildings.at(1, 5).configureAny(null);
        buildings.setBlock(8, 5, BLOCKS.block("air"), 0, 0, Cause.SERVER);
        for(int i = 0; i < 20; i++) world.tick();
        assertNotNull(Puddles.of(world).get(world.tiles().pos(7, 5)), "it leaks into a puddle where the tank was");
        for(int i = 0; i < 400; i++) world.tick();
        assertTrue(last.liquids.currentAmount() <= 0.0001f, "the line drains");
        assertTrue(last.sleeping(), "an empty conduit sleeps");
        assertEquals(0f, buildings.at(1, 5).liquids.get(WATER), "a cleared source empties itself");
    }

    @Test
    void aConduitWithNothingInFrontLeaksAndAnArmoredOneDoesnt(){
        source(1, 5, WATER);
        line("conduit", 2, 5, 3);
        source(1, 8, WATER);
        line("plated-conduit", 2, 8, 3);
        for(int i = 0; i < 60; i++) world.tick();
        var puddles = Puddles.of(world);
        assertNotNull(puddles.get(world.tiles().pos(5, 5)), "a puddle in front of the open conduit");
        assertNull(puddles.get(world.tiles().pos(5, 8)), "none in front of the plated one");
    }

    @Test
    void aConduitRefusesItsFrontAndAJunctionPassesThrough(){
        var conduit = place("conduit", 5, 5, 0);
        var front = place("conduit", 6, 5, 2);
        assertFalse(conduit.acceptLiquid(front, WATER), "nothing enters a conduit from its front");
        var back = place("conduit", 4, 5, 0);
        assertTrue(conduit.acceptLiquid(back, WATER));
        conduit.liquids.add(OIL, 1f);
        assertFalse(conduit.acceptLiquid(back, WATER), "a conduit holding oil refuses water");

        var junction = place("liquid-junction", 10, 5, 0);
        var into = place("conduit", 9, 5, 0);
        var out = place("conduit", 11, 5, 0);
        assertSame(out, junction.getLiquidDestination(into, WATER));
        junction.control(LAccess.enabled, 0, 0, 0, 0);
        assertSame(junction, junction.getLiquidDestination(into, WATER), "a disabled junction is its own destination");
    }

    @Test
    void aCheckpointAndAForkCarryEveryKind(){
        source(1, 5, WATER);
        line("conduit", 2, 5, 2);
        place("liquid-router", 4, 5, 0);
        place("liquid-junction", 5, 5, 0);
        line("pulse-conduit", 6, 5, 2);
        place("reinforced-bridge-conduit", 8, 5, 0);
        place("reinforced-bridge-conduit", 11, 5, 0);
        place("liquid-tank", 13, 5, 0);
        line("plated-conduit", 15, 5, 2);
        place("liquid-void", 17, 5, 0);
        source(5, 2, OIL);
        place("conduit", 5, 3, 1);
        place("conduit", 5, 4, 1);
        place("conduit", 5, 6, 1);
        source(1, 9, WATER);
        place("bridge-conduit", 2, 9, 0).configureAny(com.xpdustry.shard.common.geom.Point2.pack(5, 9));
        place("bridge-conduit", 5, 9, 0);
        place("liquid-void", 6, 9, 0);
        for(int i = 0; i < 150; i++) world.tick();
        var bridgeConduit = (LiquidBridge)buildings.at(2, 9);
        assertTrue(bridgeConduit.hadValidLink && bridgeConduit.warmup > 0.25f, "the bridge conduit linked and warmed up");
        assertTrue(bridgeConduit.wasMoved, "and moves water");
        var bridge = (DirectionLiquidBridge)buildings.at(8, 5);
        assertSame(buildings.at(11, 5), bridge.lastLink);
        assertSame(bridge, ((DirectionLiquidBridge)buildings.at(11, 5)).occupied[0]);

        World restored = Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
        World fork = world.fork();
        assertEquals(world.hash(), restored.hash(), () -> world.hash().firstDifference(restored.hash()).orElse("?"));
        assertEquals(world.hash(), fork.hash(), () -> world.hash().firstDifference(fork.hash()).orElse("?"));
        for(int i = 0; i < 300; i++){
            if(i == 100){
                for(World w : new World[]{world, restored, fork}) w.buildings().at(1, 5).configureAny(ContentRef.liquid(OIL));
            }
            world.tick();
            restored.tick();
            fork.tick();
            int tick = i;
            assertEquals(world.hash(), restored.hash(), () -> "tick " + tick + ": " + world.hash().firstDifference(restored.hash()).orElse("?"));
            assertEquals(world.hash(), fork.hash(), () -> "tick " + tick + ": " + world.hash().firstDifference(fork.hash()).orElse("?"));
        }
        assertEquals(OIL, ((LiquidSource)restored.buildings().at(1, 5)).source);
        assertSame(restored.buildings().at(11, 5), ((DirectionLiquidBridge)restored.buildings().at(8, 5)).lastLink);
        assertEquals(com.xpdustry.shard.common.geom.Point2.pack(5, 9), ((LiquidBridge)restored.buildings().at(2, 9)).link);
    }
}
