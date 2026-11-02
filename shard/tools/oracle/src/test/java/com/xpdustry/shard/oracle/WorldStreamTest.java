package com.xpdustry.shard.oracle;

import mindustry.content.*;
import mindustry.gen.*;
import org.junit.jupiter.api.*;
import com.xpdustry.shard.oracle.Oracle.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/** Built-in maps through vanilla's world stream writer, then the oracle's decoder. */
class WorldStreamTest{
    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    @Test
    void builtInMapRoundTrips(){
        Golden.WorldStreamBytes golden = Golden.worldStream("fortress");
        List<BuildingView> hosted = Oracle.buildings();
        int width = world.width(), height = world.height();
        Map<String, Integer> coreItems = hosted.stream().filter(b -> b.block().startsWith("core-")).findFirst().orElseThrow().items();

        Vanilla.reset();
        WorldView view = Oracle.decodeWorldStream(golden.deflated());

        assertEquals(width, view.width());
        assertEquals(height, view.height());
        assertEquals("Fortress", view.tags().get("name"));
        assertEquals("oracle", view.player().fields().get("name"));
        assertEquals(List.of(), view.warnings());
        //vanilla writes a full content header: every type with content
        assertEquals(447, ((Map<?, ?>)view.contentHeader().get("block")).get("count"));
        assertEquals(hosted.size(), view.buildings().size());
        for(int i = 0; i < hosted.size(); i++){
            assertEquals(hosted.get(i).block(), view.buildings().get(i).block());
            assertEquals(hosted.get(i).x(), view.buildings().get(i).x());
            assertEquals(hosted.get(i).team(), view.buildings().get(i).team());
        }
        assertEquals(coreItems, view.buildings().stream().filter(b -> b.block().startsWith("core-")).findFirst().orElseThrow().items());

        //the decoded world writes back to the same bytes, except two sections a vanilla client changes while loading:
        //rules (BlockIndexer creates a TeamRule for all 256 teams on WorldLoadEvent; a hosting server replaces its rules
        //after loading) and team plans (the client's active team list includes every team with a core)
        byte[] again = Golden.worldStream(Vanilla.localPlayer()).raw();
        Vanilla.reset();
        WorldView reloaded = Oracle.decodeWorldStream(again);
        for(Section section : view.sections()){
            if(section.name().equals("rules") || section.name().equals("team plans")) continue;
            Section other = reloaded.section(section.name());
            assertArrayEquals(Arrays.copyOfRange(golden.raw(), section.offset(), section.offset() + section.length()),
                Arrays.copyOfRange(again, other.offset(), other.offset() + other.length()), section.name());
        }
    }

    @Test
    void everyDefaultMapDecodes(){
        for(var map : maps.all().copy()){
            String name = map.file.nameWithoutExtension();
            byte[] stream = Golden.worldStream(name).deflated();
            Vanilla.reset();
            WorldView view = Oracle.decodeWorldStream(stream);
            assertEquals(map.width, view.width(), name);
            assertFalse(view.buildings().isEmpty(), name);
        }
    }

    @Test
    void sectorMapAndUnitsInTheStream(){
        Vanilla.host("serpulo/groundZero");
        Unit dagger = UnitTypes.dagger.spawn(state.rules.defaultTeam, 100f, 100f);
        Vanilla.runPosted();
        Player joining = Player.create();
        joining.name = "late";
        byte[] stream = Golden.worldStream(joining).deflated();
        int units = Groups.unit.size();

        Vanilla.reset();
        WorldView view = Oracle.decodeWorldStream(stream);
        assertEquals(units, view.entities().size());
        EntityView unit = view.entities().stream().filter(e -> e.id() == dagger.id).findFirst().orElseThrow();
        assertEquals("mindustry.gen.MechUnit", unit.className());
        assertEquals(Map.of("$ref", "unit:dagger"), unit.fields().get("type"));
        assertEquals(100f, unit.fields().get("x"));
    }
}
