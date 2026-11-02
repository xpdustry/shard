package com.xpdustry.shard.tools.wirelift;

import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The committed schema reads back to itself, strictly. */
class SchemaTest{
    static String committed() throws IOException{
        return Files.readString(Path.of(System.getProperty("com.xpdustry.shard.wirelift.schema")));
    }

    @Test
    void theCommittedSchemaRoundTrips() throws IOException{
        String yaml = committed();
        Schema schema = Schema.parse(yaml);
        assertEquals(yaml, schema.yaml());
        assertEquals("160.7", schema.build());
        assertEquals(173, schema.packets().size());
        assertEquals(List.of(), schema.problems());
        assertEquals(List.of(12), schema.casts().get("Player"));
    }

    /** read() handles every revision from 0 to the current one, and each old one has a body in the class's layout. */
    @Test
    void everyOldRevisionHasABody() throws IOException{
        Schema schema = Schema.parse(committed());
        int old = 0;
        for(Schema.Entity e : schema.entities()){
            if(e.revision() < 0){
                assertEquals(Map.of(), e.old(), e.className());
                continue;
            }
            Set<Integer> expected = new TreeSet<>();
            for(int r = 0; r < e.revision(); r++) expected.add(r);
            assertEquals(expected, e.old().keySet(), e.className());
            for(String body : e.old().values()){
                assertTrue(body.equals(Schema.SAVE) || schema.layouts().get(e.layout()).old().containsKey(body), e.className() + ": " + body);
            }
            old += e.old().size();
        }
        assertEquals(151, old);
        assertEquals(45, schema.layouts().values().stream().mapToInt(l -> l.old().size()).sum());
    }

    /** What a fresh entity holds where an old body leaves a field: Unit's constructor constants, null content as -1. */
    @Test
    void freshEntityDefaults() throws IOException{
        Schema schema = Schema.parse(committed());
        assertEquals(Map.of("updateBuilding", "true"), schema.layouts().get("MechUnit").defaults());
        assertEquals(Map.of("selectedBlock", "-1"), schema.layouts().get("Player").defaults());
        assertEquals(Map.of("flags", "3", "fontSize", "1.0", "z", "151.0"), schema.layouts().get("WorldLabel").defaults());
    }

    @Test
    void unknownKeysFail() throws IOException{
        String yaml = committed();
        var e = assertThrows(IllegalArgumentException.class, () -> Schema.parse(yaml + "extra: 1\n"));
        assertTrue(e.getMessage().contains("'extra'"), e.getMessage());
        String packet = yaml.replaceFirst(", priority: ", ", prio: high, priority: ");
        e = assertThrows(IllegalArgumentException.class, () -> Schema.parse(packet));
        assertTrue(e.getMessage().contains("'prio'"), e.getMessage());
        String field = yaml.replaceFirst(", codec: ", ", kodec: x, codec: ");
        assertThrows(IllegalArgumentException.class, () -> Schema.parse(field));
    }

    @Test
    void wrongTypesFail() throws IOException{
        String yaml = committed();
        assertThrows(IllegalArgumentException.class, () -> Schema.parse(yaml.replaceFirst("\\{id: 0,", "{id: zero,")));
        assertThrows(IllegalArgumentException.class, () -> Schema.parse(yaml.replace("problems: []", "problems: none")));
    }

    @Test
    void yamlKeywordsStayStrings(){
        var old = new LinkedHashMap<String, List<Schema.Field>>(Map.of("Thing.0", List.of(new Schema.Field("_", "f32", null, false, null))));
        var layout = new Schema.Layout("Thing", List.of(new Schema.Field("yes", "null", null, true, "lerp"), new Schema.Field("on", "seq<i32>", "i16", false, null)), null, old);
        var schema = new Schema("160.7", Map.of("Mode", List.of("off", "y")), Map.of("Thing", layout),
            List.of(new Schema.Entity(0, "Thing", "Thing", 2, new TreeMap<>(Map.of(0, "Thing.0", 1, "save")), true, false), new Schema.Entity(1, "Nothing", null, -1, false, false)),
            Map.of("Thing", List.of(0), "None", List.of()),
            List.of(new Schema.Packet(0, "true", "both", true, "high", "-", "i32 a, String \"b\", Entity<Thing> c")), List.of("a \"problem\""));
        Schema back = Schema.parse(schema.yaml());
        assertEquals(schema, back);
        assertEquals(schema.yaml(), back.yaml());
    }
}
