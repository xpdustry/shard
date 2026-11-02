package com.xpdustry.shard.compat.v160;

import arc.struct.*;
import com.xpdustry.shard.common.geom.Point2;
import com.xpdustry.shard.compat.v160.fixtures.*;
import com.xpdustry.shard.compat.v160.msav.*;
import com.xpdustry.shard.compat.v160.rules.*;
import com.xpdustry.shard.compat.v160.sync.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.objectives.ObjectiveEvent;
import com.xpdustry.shard.sim.objectives.Objectives;
import com.xpdustry.shard.sim.building.KindRegistry;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.testkit.BundledMaps;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.game.MapObjectives.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.logic.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * WP74's JSON and Call gates against vanilla's {@code JsonIO}, byte for byte:
 * <ul>
 *   <li>every bundled map's objectives, read and written by Shard, are vanilla's, and vanilla reads Shard's back to
 *   them;</li>
 *   <li>every bundled map's markers region, read and written by Shard as UBJSON, is vanilla's {@code MapMarkers}
 *   read and written;</li>
 *   <li>each objective and marker kind with every field set, and each marker after every {@code control} rule, with
 *   the visibility lists;</li>
 *   <li>the game stats, and the objective and marker Calls.</li>
 * </ul>
 */
class ObjectivesJsonOracleTest{
    static final RulesJson RULES = new RulesJson(Worlds.EPOCH);
    static final ObjectivesJson JSON = RULES.objectives();
    static final MsavReader READER = new MsavReader(Worlds.EPOCH);

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    @Test
    void everyBundledMapsObjectivesRoundTrip(){
        int withObjectives = 0, objectives = 0;
        for(String path : BundledMaps.all()){
            String tag = READER.meta(BundledMaps.read(path)).get("rules");
            if(tag == null) continue;
            String vanilla = JsonIO.write(JsonIO.read(Rules.class, tag).objectives);
            com.xpdustry.shard.sim.objectives.MapObjectives shard = RULES.read(tag).objectives;
            String written = JSON.writeObjectives(shard);
            assertEquals(vanilla, written, path + ": Shard rewrites the map's objectives");
            assertEquals(vanilla, JsonIO.write(JsonIO.read(MapObjectives.class, written)), path + ": vanilla rewrites Shard's");
            if(!shard.all.isEmpty()) withObjectives++;
            objectives += shard.all.size();
        }
        System.out.println("objectives: " + objectives + " in " + withObjectives + " bundled maps, byte-equal");
        assertTrue(withObjectives >= 50 && objectives >= 240, "maps with objectives: " + withObjectives + ", objectives: " + objectives);
    }

    @Test
    void everyBundledMapsMarkersRoundTrip() throws IOException{
        int maps = 0, markers = 0;
        for(String path : BundledMaps.all()){
            SaveFile save = READER.read(BundledMaps.read(path));
            if(save.markers() == null) continue;
            var vanilla = new mindustry.game.MapMarkers();
            vanilla.read(new DataInputStream(new ByteArrayInputStream(save.markers())));
            byte[] expected = write(vanilla);
            var shard = new com.xpdustry.shard.sim.objectives.MapMarkers();
            shard.load(JSON.readMarkers(save.markers(), pos -> false));
            assertArrayEquals(expected, JSON.writeMarkers(shard), path + ": Shard rewrites the map's markers");
            maps++;
            markers += shard.size();
        }
        System.out.println("markers: " + markers + " in " + maps + " bundled maps' regions, byte-equal");
        // the bundled saves hold no world processor's markers (their objectives' markers are in the rules): the regions
        // decode and encode, and everyMarkerAndControlIsVanillas has the markers themselves
        assertTrue(maps >= 80, "maps with a markers region: " + maps);
    }

    static byte[] write(mindustry.game.MapMarkers markers) throws IOException{
        var out = new ByteArrayOutputStream();
        markers.write(new DataOutputStream(out));
        return out.toByteArray();
    }

    /** The marker kinds by {@code makemarker} name. */
    static final List<String> KINDS = List.of("shapeText", "point", "shape", "text", "line", "texture", "quad", "light");

    /** Value triples for {@code control}: NaN is "no value". */
    static final double NAN = Double.NaN;
    static final double[][] VALUES = {
        {NAN, NAN, NAN}, {1.5, 2.25, 0}, {0, 3, 1}, {2, 7.5, -1}, {NAN, 4, 5}, {3, NAN, 2}, {1, 0, NAN},
        {Double.longBitsToDouble(0xff8040c0L), Double.longBitsToDouble(0x10203040L), 1}};

    @Test
    void everyMarkerAndControlIsVanillas() throws IOException{
        Vanilla.emptyWorld(16, 16);
        world.tile(4, 5).setBlock(Blocks.router, Team.sharded, 0);
        state.markers = new mindustry.game.MapMarkers();
        var shard = new com.xpdustry.shard.sim.objectives.MapMarkers();
        var vanilla = new ArrayList<ObjectiveMarker>();
        var ours = new ArrayList<com.xpdustry.shard.sim.objectives.ObjectiveMarker>();
        for(int i = 0; i < KINDS.size(); i++){
            ObjectiveMarker v = MapObjectives.markerNameToType.get(KINDS.get(i)).get();
            var s = com.xpdustry.shard.sim.objectives.ObjectiveMarker.byName(KINDS.get(i));
            assertEquals(JsonIO.json.toJson(v, ObjectiveMarker.class), JSON.writeMarker(s), KINDS.get(i) + " as made");
            state.markers.add(i * 3, v);
            shard.add(i * 3, s);
            vanilla.add(v);
            ours.add(s);
        }
        assertArrayEquals(write(state.markers), JSON.writeMarkers(shard), "as made");
        int checked = 0;
        for(LMarkerControl control : LMarkerControl.all){
            if(control == LMarkerControl.remove || control == LMarkerControl.flushText || control == LMarkerControl.texture) continue;
            var ours2 = com.xpdustry.shard.logic.lang.LMarkerControl.valueOf(control.name());
            for(double[] p : VALUES){
                for(int i = 0; i < KINDS.size(); i++){
                    vanilla.get(i).control(control, p[0], p[1], p[2]);
                    ours.get(i).control(shard, ours2, p[0], p[1], p[2]);
                    String when = KINDS.get(i) + " after " + control + Arrays.toString(p);
                    assertEquals(JsonIO.json.toJson(vanilla.get(i), ObjectiveMarker.class), JSON.writeMarker(ours.get(i)), when);
                    checked++;
                }
                assertArrayEquals(write(state.markers), JSON.writeMarkers(shard), "the markers after " + control + Arrays.toString(p));
            }
        }
        for(int i = 0; i < KINDS.size(); i++){
            vanilla.get(i).setText("a [red]text", true);
            ours.get(i).setText("a [red]text", true);
            for(Object texture : new Object[]{"white", Items.copper, world.build(4, 5), null, Blocks.duo}){
                vanilla.get(i).setTexture(texture);
                ours.get(i).setTexture(switch(texture){
                    case String s -> com.xpdustry.shard.sim.objectives.ObjectiveMarker.Texture.string(s);
                    case mindustry.ctype.UnlockableContent c -> com.xpdustry.shard.sim.objectives.ObjectiveMarker.Texture.content(c.name);
                    case mindustry.gen.Building b -> com.xpdustry.shard.sim.objectives.ObjectiveMarker.Texture.building(Point2.pack(4, 5));
                    case null, default -> com.xpdustry.shard.sim.objectives.ObjectiveMarker.Texture.NONE;
                });
                assertEquals(JsonIO.json.toJson(vanilla.get(i), ObjectiveMarker.class), JSON.writeMarker(ours.get(i)), KINDS.get(i) + " texture " + texture);
                // and back: Shard's JSON read by both
                String written = JSON.writeMarker(ours.get(i));
                assertEquals(JsonIO.json.toJson(JsonIO.read(ObjectiveMarker.class, written), ObjectiveMarker.class),
                    JSON.writeMarker(JSON.readMarker(ArcJson.parse(written), pos -> pos == Point2.pack(4, 5))), KINDS.get(i) + " read back");
            }
        }
        // removal swaps the last marker of each list into the gap
        state.markers.remove(3);
        shard.remove(3);
        state.markers.remove(0);
        shard.remove(0);
        assertArrayEquals(write(state.markers), JSON.writeMarkers(shard), "after removals");
        // the saved form read back on both sides
        var reread = new mindustry.game.MapMarkers();
        reread.read(new DataInputStream(new ByteArrayInputStream(write(state.markers))));
        var shardReread = new com.xpdustry.shard.sim.objectives.MapMarkers();
        shardReread.load(JSON.readMarkers(JSON.writeMarkers(shard), pos -> pos == Point2.pack(4, 5)));
        assertArrayEquals(write(reread), JSON.writeMarkers(shardReread), "read back");
        System.out.println("markers: " + checked + " control steps byte-equal");
    }

    @Test
    void everyObjectiveKindIsVanillas() throws ReflectiveOperationException{
        var exec = new MapObjectives();
        var shape = new ShapeTextMarker("@marker", 8f, 16f, 3f, 45f);
        shape.minimap = 1;
        var research = new ResearchObjective(Blocks.duo);
        research.details = "line\nbreak";
        research.flagsAdded = new String[]{"a", "b c"};
        research.flagsRemoved = new String[]{"old"};
        research.markers = new ObjectiveMarker[]{shape, new PointMarker(3, 4)};
        research.completionLogicCode = "setflag \"x\" true";
        research.hidden = true;
        research.editorX = 5;
        research.editorY = -7;
        var produce = new ProduceObjective(Liquids.water);
        var item = new ItemObjective(Items.lead, 40);
        var coreItem = new CoreItemObjective(Items.sand, 7);
        var build = new BuildCountObjective(Blocks.router, 3);
        var unit = new UnitCountObjective(UnitTypes.flare, 2);
        var destroy = new DestroyUnitsObjective(4);
        var timer = new TimerObjective("@objective.enemyescelating", 600f);
        Field countup = TimerObjective.class.getDeclaredField("countup");
        countup.setAccessible(true);
        countup.setFloat(timer, 12.5f);
        var block = new DestroyBlockObjective(Blocks.coreShard, 10, 12, Team.blue);
        var blocks = new DestroyBlocksObjective(Blocks.duo, Team.crux, new arc.math.geom.Point2(1, 2), new arc.math.geom.Point2(0, 5));
        var command = new CommandModeObjective();
        var flag = new FlagObjective("flag2", "text");
        var core = new DestroyCoreObjective();
        Field completed = MapObjective.class.getDeclaredField("completed");
        completed.setAccessible(true);
        completed.setBoolean(flag, true);
        exec.add(research, produce, item, coreItem, build, unit, destroy, timer, block, blocks, command, flag, core);
        flag.parents.add(research);
        core.parents.add(flag);
        core.parents.add(timer);
        String vanilla = JsonIO.write(exec);
        String shard = JSON.writeObjectives(JSON.readObjectives(ArcJson.parse(vanilla)));
        assertEquals(vanilla, shard, "every objective kind");
        assertEquals(vanilla, JsonIO.write(JsonIO.read(MapObjectives.class, shard)), "read back by vanilla");
        // defaults
        var defaults = new MapObjectives();
        for(var provider : MapObjectives.allObjectiveTypes) defaults.add(provider.get());
        String written = JsonIO.write(defaults);
        assertEquals(written, JSON.writeObjectives(JSON.readObjectives(ArcJson.parse(written))), "every kind's defaults");
        // the old lowercase tags drop the whole list
        assertTrue(JSON.readObjectives(ArcJson.parse("[{class:timer,parents:[]}]")).all.isEmpty());
    }

    @Test
    void statsAreVanillas(){
        var stats = new mindustry.game.GameStats();
        stats.enemyUnitsDestroyed = 3;
        stats.wavesLasted = 4;
        stats.buildingsBuilt = 5;
        stats.buildingsDeconstructed = 6;
        stats.buildingsDestroyed = 7;
        stats.unitsCreated = 8;
        stats.placedBlockCount.put(Blocks.conveyor, 9);
        stats.destroyedBlockCount.put(Blocks.duo, 10);
        stats.coreItemCount.put(Items.copper, 11);
        String vanilla = JsonIO.write(stats);
        assertEquals(vanilla, JSON.writeStats(JSON.readStats(vanilla)));
        assertEquals(JsonIO.write(new mindustry.game.GameStats()), JSON.writeStats(JSON.readStats("{}")));
    }

    @Test
    void objectiveAndMarkerCallsAreVanillas(){
        Vanilla.emptyWorld(16, 16);
        world.tile(4, 5).setBlock(Blocks.router, Team.sharded, 0);
        state.markers = new mindustry.game.MapMarkers();
        var exec = new MapObjectives();
        exec.add(new TimerObjective("t", 30f), new FlagObjective("f", null));
        Vanilla.captured().clear();
        Vanilla.net(Vanilla.NetMode.server, () -> {
            var marker = new ShapeMarker(8f, 16f);
            marker.radius = 3f;
            Call.createMarker(3, marker);
            Call.updateMarker(3, LMarkerControl.radius, 1.5, NAN, 2);
            Call.updateMarker(3, LMarkerControl.world, 0, NAN, NAN);
            Call.updateMarkerText(3, LMarkerControl.flushText, true, "hello");
            Call.updateMarkerTexture(3, "white");
            Call.updateMarkerTexture(3, Items.copper);
            Call.updateMarkerTexture(3, world.build(4, 5));
            Call.updateMarkerTexture(3, null);
            Call.removeMarker(3);
            Call.setObjectives(exec);
            Call.completeObjective(1);
            Call.clearObjectives();
        });
        var ids = new TreeSet<Integer>();
        for(var type : com.xpdustry.shard.wire.v160_7.Packets.TYPES){
            if(type.name().contains("Marker") || type.name().contains("Objective")) ids.add(type.id());
        }
        List<String> expected = CallFrames.vanilla(Vanilla.captured(), ids);

        World shard = new World(WorldConfig.of(Worlds.EPOCH, 16, 16, 0).withKinds(KindRegistry.builtIn().withPassthrough()));
        var observer = shard.observe(64, List.of(ObjectiveEvent.class), null);
        var marker = new com.xpdustry.shard.sim.objectives.ObjectiveMarker.Shape();
        marker.pos.set(8f, 16f);
        marker.radius = 3f;
        Objectives.createMarker(shard, 3, marker);
        Objectives.updateMarker(shard, 3, com.xpdustry.shard.logic.lang.LMarkerControl.radius, 1.5, NAN, 2);
        Objectives.updateMarker(shard, 3, com.xpdustry.shard.logic.lang.LMarkerControl.world, 0, NAN, NAN);
        Objectives.updateMarkerText(shard, 3, com.xpdustry.shard.logic.lang.LMarkerControl.flushText, true, "hello");
        Objectives.updateMarkerTexture(shard, 3, com.xpdustry.shard.sim.objectives.ObjectiveMarker.Texture.string("white"));
        Objectives.updateMarkerTexture(shard, 3, com.xpdustry.shard.sim.objectives.ObjectiveMarker.Texture.content("copper"));
        Objectives.updateMarkerTexture(shard, 3, com.xpdustry.shard.sim.objectives.ObjectiveMarker.Texture.building(Point2.pack(4, 5)));
        Objectives.updateMarkerTexture(shard, 3, com.xpdustry.shard.sim.objectives.ObjectiveMarker.Texture.NONE);
        Objectives.removeMarker(shard, 3);
        Objectives.set(shard, JSON.readObjectives(ArcJson.parse(JsonIO.write(exec))));
        Objectives.complete(shard, 1);
        Objectives.clear(shard);
        shard.tick();
        List<String> actual = CallFrames.shard(shard, observer.drain());
        assertEquals(12, expected.size(), "vanilla's Calls: " + expected);
        CallFrames.same(expected, actual, "objective and marker Calls");
    }
}
