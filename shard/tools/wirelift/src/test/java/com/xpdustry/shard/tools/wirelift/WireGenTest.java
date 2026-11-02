package com.xpdustry.shard.tools.wirelift;

import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/** WireGen is deterministic, matches the committed sources, and refuses schemas it can't compile faithfully. */
class WireGenTest{
    static final String PACKAGE = "com.xpdustry.shard.wire.v160_7";

    static Schema committed() throws IOException{
        return Schema.parse(Files.readString(Path.of(System.getProperty("com.xpdustry.shard.wirelift.schema"))));
    }

    @Test
    void theCommittedSourcesAreGenerated() throws IOException{
        Map<String, String> files = WireGen.generate(committed(), PACKAGE);
        assertEquals(files, WireGen.generate(committed(), PACKAGE), "deterministic");
        Path dir = Path.of(System.getProperty("com.xpdustry.shard.wirelift.generated")).resolve(PACKAGE.replace('.', '/'));
        Map<String, String> present = new TreeMap<>();
        try(var list = Files.list(dir)){
            for(Path p : list.toList()) present.put(p.getFileName().toString(), Files.readString(p));
        }
        assertEquals(files.keySet(), present.keySet());
        for(var file : files.entrySet()) assertEquals(file.getValue(), present.get(file.getKey()), file.getKey() + " is stale");
    }

    static Schema withPacket(Schema schema, int id, UnaryOperator<Schema.Packet> change){
        List<Schema.Packet> packets = new ArrayList<>(schema.packets());
        packets.set(id, change.apply(packets.get(id)));
        return new Schema(schema.build(), schema.enums(), schema.layouts(), schema.entities(), schema.casts(), packets, schema.problems());
    }

    static Schema.Packet layout(Schema.Packet p, String layout){
        return new Schema.Packet(p.id(), p.name(), p.direction(), p.framework(), p.priority(), p.reliable(), layout);
    }

    static Schema.Packet packet(Schema schema, String name){
        return schema.packets().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }

    static void refuses(Schema schema, String message){
        var e = assertThrows(IllegalArgumentException.class, () -> WireGen.generate(schema, PACKAGE));
        assertTrue(e.getMessage().contains(message), e.getMessage());
    }

    @Test
    void refusesProblems() throws IOException{
        Schema s = committed();
        refuses(new Schema(s.build(), s.enums(), s.layouts(), s.entities(), s.casts(), s.packets(), List.of("SomePacket: unknown write")), "problems");
    }

    @Test
    void refusesUnknownCodecs() throws IOException{
        Schema s = committed();
        int tileTap = packet(s, "TileTap").id();
        refuses(withPacket(s, tileTap, p -> layout(p, "[if net.server() eq], ?Entity<Player> player, Frob tile")), "no Java mapping for codec Frob");
        var fields = new ArrayList<>(s.layouts().get("Fire").sync());
        fields.set(0, new Schema.Field(fields.getFirst().name(), "seq<Frob>", "i32", false, null));
        Map<String, Schema.Layout> layouts = new LinkedHashMap<>(s.layouts());
        layouts.put("Fire", new Schema.Layout("Fire", fields, s.layouts().get("Fire").save()));
        refuses(new Schema(s.build(), s.enums(), layouts, s.entities(), s.casts(), s.packets(), s.problems()), "no Java mapping for codec Frob");
    }

    @Test
    void refusesLayoutsItCannotCompile() throws IOException{
        Schema s = committed();
        int tileTap = packet(s, "TileTap").id(), ping = packet(s, "Ping").id();
        refuses(withPacket(s, tileTap, p -> layout(p, "[if net.server() eq], ?Entity<Player> player, [loop]")), "unsupported layout item '[loop]'");
        refuses(withPacket(s, tileTap, p -> layout(p, "?Entity<Player> player, Tile tile")), "and its guard disagree");
        refuses(withPacket(s, tileTap, p -> layout(p, "[if net.server() eq], ?Tile tile")), "guarded field");
        refuses(withPacket(s, tileTap, p -> layout(p, "[if net.server() eq], ?Entity player, Tile tile")), "guarded field");
        refuses(withPacket(s, tileTap, p -> layout(p, "[if net.server() eq], ?Entity<Player> player, Entity<Building> tile")), "no entity classes for cast Building");
        refuses(withPacket(s, ping, p -> layout(p, "[if net.server() eq], ?Entity<Player> player, i64 time")), "outside a both-way packet");
        refuses(withPacket(s, tileTap, p -> layout(p, "[if net.server() eq], ?Entity<Player> player, Tile write")), "reserved");
        refuses(withPacket(s, tileTap, p -> new Schema.Packet(p.id(), p.name(), "sideways", false, p.priority(), p.reliable(), p.layout())), "unknown direction");
        refuses(withPacket(s, tileTap, p -> new Schema.Packet(p.id() + 1, p.name(), p.direction(), false, p.priority(), p.reliable(), p.layout())), "0..n in order");
    }

    @Test
    void refusesOldRevisionsItCannotRead() throws IOException{
        Schema s = committed();
        Map<String, Schema.Layout> layouts = new LinkedHashMap<>(s.layouts());
        Schema.Layout fire = s.layouts().get("Fire");
        var old = new LinkedHashMap<>(fire.old());
        old.put("Fire.0", List.of(new Schema.Field("lifetime", "Team", null, false, null)));
        layouts.put("Fire", new Schema.Layout("Fire", fire.sync(), fire.save(), old));
        refuses(new Schema(s.build(), s.enums(), layouts, s.entities(), s.casts(), s.packets(), s.problems()), "Fire.Fire.0.lifetime is int, but the layout has float");

        List<Schema.Entity> entities = new ArrayList<>(s.entities());
        for(int i = 0; i < entities.size(); i++){
            Schema.Entity e = entities.get(i);
            if(e.className().equals("Fire")){
                entities.set(i, new Schema.Entity(e.id(), e.className(), e.layout(), e.revision(), new TreeMap<>(Map.of(0, "Fire.7")), e.serialize(), e.unit()));
            }
        }
        refuses(new Schema(s.build(), s.enums(), s.layouts(), entities, s.casts(), s.packets(), s.problems()), "no old body Fire.7");
    }

    @Test
    void refusesEnumConstantsThatAreNotIdentifiers() throws IOException{
        Schema s = committed();
        Map<String, List<String>> enums = new LinkedHashMap<>(s.enums());
        enums.put("KickReason", List.of("kick", "two words"));
        refuses(new Schema(s.build(), enums, s.layouts(), s.entities(), s.casts(), s.packets(), s.problems()), "is not a Java identifier");
    }
}
