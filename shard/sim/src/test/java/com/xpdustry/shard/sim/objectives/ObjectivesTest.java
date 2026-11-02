package com.xpdustry.shard.sim.objectives;

import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.logic.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Markers from world-processor statements, the marker lists' index bookkeeping, and objectives completing in a pass. */
class ObjectivesTest{
    static World restore(World world){
        return Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
    }

    static void script(World world, String code){
        LogicPart.require(world).runScript(code, MapObjectives.SCRIPT_INSTRUCTIONS, false, null);
    }

    @Test
    void worldProcessorStatementsMakeAndChangeMarkers(){
        World world = VanillaWorlds.world(16, 16, 1);
        script(world, """
            makemarker shapeText 5 3 4 false
            setmarker radius 5 9
            setmarker color 5 %ff0000ff
            print "hello"
            setmarker flushText 5 false
            makemarker shapeText 5 0 0 false
            makemarker point 6 1 1 false
            setmarker minimap 6 1
            makemarker unknown 7 1 1 false
            """);
        MapMarkers markers = ObjectiveState.of(world).markers;
        assertEquals(2, markers.size());
        var text = (ObjectiveMarker.ShapeText)markers.get(5);
        assertEquals(24f, text.pos.x);
        assertEquals(32f, text.pos.y);
        assertEquals(9f, text.radius);
        assertEquals("hello", text.text);
        assertEquals(new Color(1f, 0f, 0f, 1f), text.color);
        var point = (ObjectiveMarker.Point)markers.get(6);
        assertEquals(List.of(text, point), markers.list(MapMarkers.WORLD));
        assertEquals(List.of(point), markers.list(MapMarkers.MINIMAP));
        assertEquals(0, point.minimap);
        script(world, "setmarker remove 5 0");
        assertNull(markers.get(5));
        assertEquals(List.of(point), markers.list(MapMarkers.WORLD));
        assertEquals(0, point.world, "the last marker took the removed one's place");
    }

    @Test
    void hidingAMarkerSwapsTheLastIntoItsPlace(){
        var markers = new MapMarkers();
        var a = new ObjectiveMarker.Shape();
        var b = new ObjectiveMarker.Shape();
        var c = new ObjectiveMarker.Shape();
        markers.add(1, a);
        markers.add(2, b);
        markers.add(3, c);
        a.control(markers, LMarkerControl.world, 0, Double.NaN, Double.NaN);
        assertEquals(-1, a.world);
        assertEquals(List.of(c, b), markers.list(MapMarkers.WORLD));
        assertEquals(0, c.world);
        var replacement = new ObjectiveMarker.Text();
        markers.add(2, replacement);
        assertEquals(List.of(c, replacement), markers.list(MapMarkers.WORLD));
        assertEquals(1, replacement.world);
        var copy = markers.copy();
        assertEquals(markers.list(MapMarkers.WORLD).size(), copy.list(MapMarkers.WORLD).size());
        assertNotSame(markers.get(3), copy.get(3));
    }

    @Test
    void objectivesWhoseParentsCompleteRunInTheSamePass(){
        World world = VanillaWorlds.world(16, 16, 1);
        Rules rules = world.part(WorldRules.class).rules;
        var timer = new MapObjective.Timer();
        timer.duration = 3f;
        timer.flagsAdded.add("a");
        var first = new MapObjective.Flag();
        first.flag = "a";
        first.parents.add(0);
        first.completionLogicCode = "setflag \"b\" true";
        var second = new MapObjective.Flag();
        second.flag = "b";
        second.parents.add(1);
        second.flagsRemoved.add("a");
        rules.objectives = new MapObjectives(List.of(timer, first, second));
        var observer = world.observe(64, List.of(ObjectiveEvent.class), null);
        world.tick();
        world.tick();
        assertFalse(timer.completed);
        assertEquals(2f, timer.countup);
        world.tick();
        assertTrue(timer.completed && first.completed && second.completed);
        assertEquals(new TreeSet<>(List.of("b")), rules.objectiveFlags);
        world.tick();
        var completed = observer.drain().stream().filter(e -> e instanceof ObjectiveEvent.Completed).map(e -> ((ObjectiveEvent.Completed)e).index()).toList();
        assertEquals(List.of(0, 1, 2), completed);
    }

    @Test
    void forksAndCheckpointsKeepObjectivesMarkersAndStats(){
        World world = VanillaWorlds.world(16, 16, 1);
        Rules rules = world.part(WorldRules.class).rules;
        var timer = new MapObjective.Timer();
        timer.markers.add(new ObjectiveMarker.Line());
        var blocks = new MapObjective.DestroyBlocks();
        blocks.positions.add(new com.xpdustry.shard.common.geom.Point2(3, 4));
        rules.objectives = new MapObjectives(List.of(timer, blocks));
        script(world, "makemarker quad 1 2 2 true\nmakemarker texture 2 1 1 true\nsetmarker light 2 1");
        ObjectiveState state = ObjectiveState.of(world);
        GameStats.increment(state.stats.placedBlockCount, 257);
        state.locales.put("en", "key", "value");
        for(int i = 0; i < 10; i++) world.tick();
        assertEquals(world.hash(), world.fork().hash());
        World restored = restore(world);
        assertEquals(world.hash(), restored.hash());
        assertEquals("value", ObjectiveState.of(restored).locales.getProperty("key"));
        assertEquals("???other???", ObjectiveState.of(restored).locales.getProperty("other"));
        assertEquals(List.of(ObjectiveState.of(restored).markers.get(2)), ObjectiveState.of(restored).markers.list(MapMarkers.LIGHT));
    }

    @Test
    void theDefaultBlocksAreVanillas(){
        assertEquals("conveyor", VanillaWorlds.VANILLA.block(MapObjective.CONVEYOR).name());
        assertEquals("router", VanillaWorlds.VANILLA.block(MapObjective.ROUTER).name());
    }
}
