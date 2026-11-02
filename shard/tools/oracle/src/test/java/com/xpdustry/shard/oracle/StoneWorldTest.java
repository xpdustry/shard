package com.xpdustry.shard.oracle;

import mindustry.gen.*;
import org.junit.jupiter.api.*;
import com.xpdustry.shard.oracle.Oracle.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The stone-world PoC's bytes (recorded from its own encoders) and note 16's
 * hand-written minimal stream, decoded by vanilla.
 */
class StoneWorldTest{
    record Sent(int id, byte[] body){}

    static List<Sent> session;

    @BeforeAll
    static void load() throws IOException{
        Vanilla.boot();
        session = new ArrayList<>();
        for(String line : Files.readAllLines(fixture("stone-world-session.txt"))){
            String[] parts = line.split(" ");
            session.add(new Sent(Integer.parseInt(parts[0]), parts.length > 1 ? Hex.parse(parts[1]) : new byte[0]));
        }
    }

    @BeforeEach
    void reset(){
        Vanilla.reset();
    }

    @Test
    void worldStreamDecodes() throws IOException{
        WorldView world = Oracle.decodeWorldStream(Files.readAllBytes(fixture("stone-world-stream.bin")));

        assertTrue(world.deflated());
        assertEquals(50, world.width());
        assertEquals(50, world.height());
        assertEquals(Map.of("stone", 2500), world.floors());
        assertEquals(Map.of("air", 2500), world.blocks());
        assertEquals(Map.of("name", "Stone World"), world.tags());
        assertEquals(1, world.player().id());
        assertEquals("frog", world.player().fields().get("name"));
        assertEquals(Map.of("$team", "sharded"), world.player().fields().get("team"));
        //the PoC remaps blocks to a 2-entry palette: [air, stone]
        assertEquals(Map.of("block", Map.of("count", 2, "unknown", List.of())), world.contentHeader());
        assertTrue(world.buildings().isEmpty());
        assertTrue(world.entities().isEmpty());
        assertEquals(List.of(), world.warnings());
        assertEquals("map", world.sections().get(7).name());

        //vanilla's own state agrees
        assertEquals("stone", mindustry.Vars.world.tile(25, 25).floor().name);
        assertEquals("frog", Vanilla.localPlayer().name);
    }

    @Test
    void sessionPacketsDecode(){
        //the stream is reassembled from StreamBegin + StreamChunk, as the client does
        PacketView begin = Oracle.decodePacket(session.getFirst().id(), session.getFirst().body());
        assertEquals("StreamBegin", begin.packet());
        assertEquals(2, ((Number)begin.value("type")).intValue());
        int total = ((Number)begin.value("total")).intValue();

        var stream = new ByteArrayOutputStream();
        for(Sent sent : session.subList(1, session.size())){
            PacketView view = Oracle.decodePacket(sent.id(), sent.body(), Oracle.Direction.toClient);
            assertTrue(view.warnings().isEmpty(), view.warnings().toString());
            if(view.packet().equals("StreamChunk")) stream.writeBytes(Hex.parse((String)((Map<?, ?>)view.value("data")).get("$hex")));
        }
        assertEquals(total, stream.size());
        assertEquals(50, Oracle.decodeWorldStream(stream.toByteArray()).width());

        PacketView chat = Oracle.decodePacket(106, session.get(session.size() - 3).body());
        assertEquals("SendMessageCallPacket", chat.packet());
        assertEquals("[accent]Welcome to Shard's stone world, frog[accent]!", chat.value("message"));
        assertEquals(0, chat.fields().getFirst().offset());

        PacketView state = Oracle.decodePacket(141, session.get(session.size() - 2).body());
        assertEquals("StateSnapshotCallPacket", state.packet());
        assertEquals(false, state.value("paused"));
        assertEquals(60, ((Number)state.value("tps")).intValue());
    }

    @Test
    void entitySnapshotBindsTheGamma() throws IOException{
        //the client loads the world first: the player is local and has id 1
        Oracle.decodeWorldStream(Files.readAllBytes(fixture("stone-world-stream.bin")));
        EntitySnapshotView snapshot = Oracle.decodeEntitySnapshot(session.getLast().body());

        assertEquals(2, snapshot.amount());
        assertEquals(0, snapshot.trailingBytes());
        assertEquals(List.of(), snapshot.warnings());

        SyncedEntity gamma = snapshot.entities().get(0);
        assertEquals(31, gamma.classId());
        assertEquals("mindustry.gen.UnitEntityLegacyGamma", gamma.className());
        assertEquals(2, gamma.id());
        assertEquals(84, gamma.length()); //5 header bytes + 79 (no weapon mounts sent)
        assertTrue(gamma.created());
        assertEquals(Map.of("$ref", "unit:gamma"), gamma.fields().get("type"));

        SyncedEntity frog = snapshot.entities().get(1);
        assertEquals(12, frog.classId());
        assertFalse(frog.created()); //the local player from the world stream

        //vanilla's client state: the gamma is the local player's unit
        Unit unit = Groups.unit.getByID(2);
        assertNotNull(unit);
        assertSame(Vanilla.localPlayer(), unit.controller());
        assertSame(unit, Vanilla.localPlayer().unit());
        assertTrue(Vanilla.asClient(unit::isLocal));
    }

    @Test
    void note16MinimalStream() throws IOException{
        byte[] raw = Files.readAllBytes(fixture("note16-minimal-world-raw.bin"));
        byte[] deflated = Files.readAllBytes(fixture("note16-minimal-world-deflated.bin"));
        assertArrayEquals(raw, Oracle.inflate(deflated));

        WorldView world = Oracle.decodeWorldStream(raw);
        assertFalse(world.deflated());
        assertEquals(211, world.rawLength());
        assertEquals(7, world.player().id());
        assertEquals(Map.of(), world.contentHeader());
        //the offsets annotated in note 16, section 1.2
        assertEquals(new Section("content header", 100, 1), world.section("content header"));
        assertEquals(new Section("map", 101, 94), world.section("map"));
        assertEquals(new Section("markers", 205, 2), world.section("markers"));
    }

    @Test
    void errorsNameTheSectionAndOffset() throws IOException{
        byte[] raw = Files.readAllBytes(fixture("note16-minimal-world-raw.bin"));

        //a block run that overflows the map: the last run's count byte (offset 194) 0xc3 -> 0xff
        byte[] overflow = raw.clone();
        overflow[194] = (byte)0xff;
        DecodeException e = assertThrows(DecodeException.class, () -> Oracle.decodeWorldStream(overflow));
        assertEquals("map", e.section);
        assertEquals(101, e.sectionOffset);
        assertEquals(195, e.offset);

        //a cut-off stream
        byte[] cut = Arrays.copyOf(raw, 150);
        e = assertThrows(DecodeException.class, () -> Oracle.decodeWorldStream(cut));
        assertEquals("map", e.section);
        assertEquals(150, e.offset);
        assertTrue(e.getMessage().contains("EOFException"), e.getMessage());

        //a trailing byte
        byte[] trailing = Arrays.copyOf(raw, raw.length + 1);
        e = assertThrows(DecodeException.class, () -> Oracle.decodeWorldStream(trailing));
        assertEquals("end", e.section);
        assertEquals(211, e.offset);
    }

    static Path fixture(String name){
        try{
            return Path.of(StoneWorldTest.class.getResource("/fixtures/" + name).toURI());
        }catch(Exception e){
            throw new RuntimeException(e);
        }
    }
}
