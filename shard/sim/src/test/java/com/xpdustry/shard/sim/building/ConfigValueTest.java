package com.xpdustry.shard.sim.building;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.logic.*;
import com.xpdustry.shard.sim.logic.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review 38: a published {@link BuildingEvent.Configured} keeps the value as it was applied. Changing the array a config
 * came from, before the observer drains, changes neither event, and they keep their order.
 */
class ConfigValueTest{
    static final Epoch BLOCKS = VanillaWorlds.VANILLA;

    World world;

    @BeforeEach
    void world(){
        world = VanillaWorlds.world(24, 24, 1);
    }

    Building place(String block, int x, int y){
        return world.buildings().setBlock(x, y, BLOCKS.block(block), 1, 0, Cause.SERVER);
    }

    @Test
    void aProcessorsCodeIsKeptAsConfigured(){
        Building processor = place("logic-processor", 6, 6);
        var observer = world.observe(16, List.of(BuildingEvent.Configured.class), null);
        byte[] code = LogicConfig.compress("set a 1", List.of()), other = LogicConfig.compress("set b 2", List.of());
        assertEquals(code.length, other.length);
        byte[] first = code.clone();
        processor.configureAny(code);
        System.arraycopy(other, 0, code, 0, code.length);
        processor.configureAny(code);
        Arrays.fill(code, (byte)0);
        world.tick();

        List<WorldEvent> events = observer.drain();
        assertEquals(List.of(new ConfigValue.Bytes(first), new ConfigValue.Bytes(other)),
            events.stream().map(e -> ((BuildingEvent.Configured)e).value()).toList());
        assertEquals("set b 2", ((ProcessorBuilding)processor).processor().code());
        // the event's bytes are a copy too
        ((ConfigValue.Bytes)((BuildingEvent.Configured)events.getFirst()).value()).bytes()[0] = 7;
        assertEquals(new ConfigValue.Bytes(first), ((BuildingEvent.Configured)events.getFirst()).value());
    }

    @Test
    void pointArraysAreKeptAsConfigured(){
        Building router = place("router", 5, 5);
        var observer = world.observe(16, List.of(BuildingEvent.Configured.class), null);
        Point2[] links = {new Point2(1, 2), null};
        router.configureAny(links);
        links[0] = new Point2(3, 4);
        router.configureAny(links);
        links[1] = new Point2(5, 6);
        world.tick();

        assertEquals(List.of(new ConfigValue.Points(Arrays.asList(new Point2(1, 2), null)), new ConfigValue.Points(Arrays.asList(new Point2(3, 4), null))),
            observer.drain().stream().map(e -> ((BuildingEvent.Configured)e).value()).toList());
    }

    @Test
    void nestedArraysAndWorldReferencesAreFrozen(){
        Building router = place("router", 5, 5);
        byte[] bytes = {1, 2};
        Point2[] points = {new Point2(1, 1)};
        var vec = new Vec2(0.5f, 1.5f);
        Object[] value = {bytes, points, router, vec, null, "text", new int[]{3}, new boolean[]{true}, new Object[]{bytes}};
        Object frozen = ConfigValue.of(value);
        bytes[0] = 9;
        points[0] = new Point2(2, 2);
        vec.x = 4f;
        value[5] = "changed";

        var expected = new ConfigValue.Values(Arrays.asList(new ConfigValue.Bytes(new byte[]{1, 2}), new ConfigValue.Points(List.of(new Point2(1, 1))),
            new ConfigValue.BuildingAt(router.tile()), new ConfigValue.Vec(0.5f, 1.5f), null, "text", new ConfigValue.Ints(new int[]{3}),
            new ConfigValue.Booleans(new boolean[]{true}), new ConfigValue.Values(List.of(new ConfigValue.Bytes(new byte[]{1, 2})))));
        assertEquals(expected, frozen);
        assertThrows(UnsupportedOperationException.class, () -> ((ConfigValue.Values)frozen).values().set(0, null));
        assertSame(frozen, ConfigValue.of(frozen));
    }

    @Test
    void immutableValuesStayAndOthersAreRefused(){
        var ref = ContentRef.item(0);
        for(Object value : new Object[]{null, true, 1, 2L, 3f, 4d, 'c', "s", ref, new Point2(1, 2), com.xpdustry.shard.content.ContentType.ITEM}){
            assertSame(value, ConfigValue.of(value), String.valueOf(value));
        }
        assertThrows(IllegalArgumentException.class, () -> ConfigValue.of(new StringBuilder("mutable")));
    }

    @Test
    void aSyncedBuildingIsItsTile(){
        Building router = place("router", 5, 5);
        var event = new LogicEvent.SyncVariable(0, 0, 0, router);
        assertEquals(new ConfigValue.BuildingAt(router.tile()), event.value());
    }
}
