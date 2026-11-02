package com.xpdustry.shard.sim.logic;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.logic.*;
import com.xpdustry.shard.sim.blocks.distribution.*;
import com.xpdustry.shard.sim.blocks.logic.*;
import com.xpdustry.shard.sim.blocks.sandbox.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Processors as buildings: links that follow the tiles they name, {@code control enabled} on a ported block, configs,
 * memory, messages and switches, and checkpoints and forks of a running program. {@code LogicOracleTest} holds them to
 * vanilla frame by frame.
 */
class ProcessorTest{
    static final Epoch BLOCKS = VanillaWorlds.VANILLA;
    static final int COPPER = BLOCKS.item("copper").id();

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

    /** A processor running {@code code}, linked to {@code links} in order as a player links them. */
    LogicBlock processor(String block, int x, int y, String code, int[]... links){
        var processor = (LogicBlock)place(block, x, y);
        processor.configureAny(LogicConfig.compress(code, List.of()));
        for(int[] link : links) processor.configureAny(Point2.pack(link[0], link[1]));
        return processor;
    }

    static Object var(LogicBlock building, String name){
        Processor p = building.processor();
        int index = p.program().variable(name);
        if(index < 0) throw new IllegalArgumentException("no variable " + name);
        return p.variable(index);
    }

    void ticks(int ticks){
        for(int i = 0; i < ticks; i++) world.tick();
    }

    /** A source feeding a router that feeds a void. */
    Router line(){
        var source = (ItemSource)place("item-source", 4, 5);
        var router = (Router)place("router", 5, 5);
        place("item-void", 6, 5);
        source.configureAny(ContentRef.item(COPPER));
        return router;
    }

    @Test
    void controlEnabledTogglesARouterAndUnlinkingReenablesIt(){
        Router router = line();
        LogicBlock processor = processor("micro-processor", 5, 7, """
            control enabled router1 0
            wait 0.5
            control enabled router1 1
            wait 0.5
            """, new int[]{5, 5});
        assertEquals("router1", processor.processor().links().getFirst().name());

        var enabled = new StringBuilder();
        for(int i = 0; i < 80; i++){
            world.tick();
            enabled.append(router.enabled ? '1' : '0');
        }
        assertTrue(enabled.toString().matches("1?0+1+0+1*"), enabled.toString());
        assertSame(processor, router.lastDisabler);

        // unlinking re-enables a router this processor disabled
        while(router.enabled) world.tick();
        processor.configureAny(Point2.pack(5, 5));
        assertTrue(router.enabled);
        assertEquals(0, processor.processor().linkCount());
    }

    @Test
    void removingTheProcessorReenablesWhatItDisabled(){
        Router router = line();
        LogicBlock processor = processor("micro-processor", 5, 7, "control enabled router1 0", new int[]{5, 5});
        ticks(2);
        assertFalse(router.enabled);
        buildings.remove(processor, Cause.SERVER);
        assertTrue(router.enabled);
    }

    @Test
    void linksFollowTheirTilesInTheTickTheyChange(){
        place("memory-cell", 8, 8);
        LogicBlock processor = processor("logic-processor", 6, 6, """
            set n @links
            write n cell1 0
            """, new int[]{8, 8});
        ticks(2);
        assertEquals(1.0, var(processor, "n"));

        buildings.remove(buildings.at(8, 8), Cause.SERVER);
        world.tick();
        assertEquals(0.0, var(processor, "n"), "the cell is gone");

        var cell = (MemoryBlock)place("memory-cell", 8, 8);
        world.tick();
        assertEquals(1.0, var(processor, "n"), "a cell on the linked tile is linked again");
        assertEquals(1.0, cell.numbers[0]);

        buildings.changeTeam(cell, 2, Cause.SERVER);
        world.tick();
        assertEquals(0.0, var(processor, "n"), "another team's cell is no link");
    }

    @Test
    void codeAndLinksRoundTripThroughTheConfig(){
        place("memory-cell", 8, 8);
        place("switch", 4, 6);
        LogicBlock a = processor("logic-processor", 6, 6, "write 3 cell1 0\nsensor s switch1 @enabled", new int[]{8, 8}, new int[]{4, 6});
        var config = (byte[])a.config();
        LogicConfig.Decoded decoded = LogicConfig.decode(config);
        assertEquals(a.processor().code(), decoded.code());
        assertEquals(List.of(new LogicConfig.Link("cell1", 2, 2), new LogicConfig.Link("switch1", -2, 0)), decoded.links());

        // the same config one tile down links the same offsets
        place("memory-cell", 8, 7);
        place("switch", 4, 5);
        var b = (LogicBlock)place("logic-processor", 6, 5);
        b.configureAny(config);
        assertEquals(a.processor().code(), b.processor().code());
        assertEquals(List.of("cell1@8,7", "switch1@4,5"), b.processor().links().stream().map(l -> l.name() + "@" + l.x + "," + l.y).toList());
        assertArrayEquals(config, (byte[])b.config());
    }

    @Test
    void memoryMessagesAndSwitches(){
        var cell = (MemoryBlock)place("memory-cell", 8, 8);
        var message = (MessageBlock)place("message", 8, 6);
        var toggle = (SwitchBlock)place("switch", 8, 4);
        toggle.configureAny(true);
        LogicBlock processor = processor("logic-processor", 6, 6, """
            sensor on switch1 @enabled
            write on cell1 1
            write @this cell1 2
            read me cell1 2
            read far cell1 99
            print "on: "
            print on
            printflush message1
            sensor size message1 @bufferSize
            control enabled switch1 0
            stop
            """, new int[]{8, 8}, new int[]{8, 6}, new int[]{8, 4});
        ticks(3);
        assertEquals(1.0, cell.numbers[1]);
        assertSame(processor, cell.objects[2]);
        assertSame(processor, var(processor, "me"));
        assertNull(var(processor, "far"), "out of bounds reads null");
        assertEquals("on: 1", message.message.toString());
        assertEquals(5.0, var(processor, "size"));
        assertFalse(toggle.enabled);
    }

    @Test
    void aCheckpointMidProgramContinuesExactly(){
        line();
        place("memory-cell", 8, 8);
        place("message", 8, 6);
        processor("logic-processor", 6, 6, """
            op rand r 10
            op add total total r
            write total cell1 0
            write @this cell1 1
            print total
            printflush message1
            control enabled router1 0
            wait 0.2
            control enabled router1 1
            sensor items router1 @totalItems
            wait 0.1
            """, new int[]{8, 8}, new int[]{8, 6}, new int[]{5, 5});
        processor("micro-processor", 10, 10, "op add i i 1");
        for(int at : new int[]{1, 13, 37}){
            ticks(at);
            World restored = Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
            World fork = world.fork();
            for(World other : new World[]{restored, fork}){
                assertEquals(world.hash(), other.hash(), () -> world.hash().firstDifference(other.hash()).orElse("?"));
            }
            for(int i = 0; i < 150; i++){
                world.tick();
                restored.tick();
                fork.tick();
                int tick = i;
                for(World other : new World[]{restored, fork}){
                    assertEquals(world.hash(), other.hash(), () -> "at " + at + ", then tick " + tick + ": " + world.hash().firstDifference(other.hash()).orElse("?"));
                }
            }
            var live = (LogicBlock)world.buildings().at(6, 6);
            var copy = (LogicBlock)restored.buildings().at(6, 6);
            assertEquals(var(live, "total"), var(copy, "total"));
            assertSame(copy, ((MemoryBlock)restored.buildings().at(8, 8)).objects[1], "references point into their own world");
        }
    }
}
