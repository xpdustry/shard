package com.xpdustry.shard.oracle;

import arc.util.io.*;
import mindustry.content.*;
import mindustry.gen.*;
import mindustry.world.*;
import mindustry.world.blocks.legacy.*;
import mindustry.world.blocks.distribution.Conveyor.*;
import org.junit.jupiter.api.*;
import com.xpdustry.shard.oracle.Oracle.*;
import com.xpdustry.shard.oracle.Vanilla.*;

import java.io.*;
import java.nio.*;
import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/** Golden bytes from vanilla's writers, checked against vanilla's readers. */
class GoldenTest{
    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    @Test
    void everyUnitTypeSyncsAndReadsBack(){
        List<Golden.EntityBytes> units = Golden.units();
        assertEquals(content.units().size, units.size());
        for(Golden.EntityBytes unit : units){
            assertNull(unit.error(), unit.type());
            //block units (a player controlling a turret) are never added to the world, so never in a snapshot
            if(content.unit(unit.type()).constructor.get() instanceof BlockUnitc) continue;
            //as an entity snapshot: i32 id, u8 class id, writeSync
            byte[] data = ByteBuffer.allocate(5 + unit.sync().length).putInt(unit.id() + 1000).put((byte)unit.classId()).put(unit.sync()).array();
            Vanilla.reset();
            EntitySnapshotView view = Oracle.decodeEntitySnapshot(1, data);
            assertEquals(0, view.trailingBytes(), unit.type());
            assertEquals(Map.of("$ref", "unit:" + unit.type()), view.entities().getFirst().fields().get("type"), unit.type());
        }
        Golden.EntityBytes gamma = units.stream().filter(u -> u.type().equals("gamma")).findFirst().orElseThrow();
        assertEquals(31, gamma.classId());
        assertEquals("mindustry.gen.UnitEntityLegacyGamma", gamma.className());
    }

    @Test
    void everyBuildingReadsBackToItsLength(){
        List<Golden.BuildingBytes> buildings = Golden.buildings();
        List<String> failed = new ArrayList<>();
        for(Golden.BuildingBytes golden : buildings){
            //legacy blocks only exist to read old saves: their read() does not match their write()
            if(content.block(golden.block()) instanceof LegacyBlock) continue;
            if(golden.error() != null){
                failed.add(golden.block() + ": " + golden.error());
                continue;
            }
            Vanilla.emptyWorld(32, 32);
            world.tile(16, 16).setBlock(content.block(golden.block()), mindustry.game.Team.sharded, 0);
            var in = new ByteArrayInputStream(golden.chunk());
            var reads = new Reads(new DataInputStream(in));
            world.tile(16, 16).build.readAll(reads, reads.b());
            assertEquals(0, in.available(), golden.block() + " left bytes unread");
        }
        assertEquals(List.of(), failed);
        assertEquals(content.blocks().count(Block::hasBuilding), buildings.size());
    }

    @Test
    void conveyorHoldingItems(){
        Golden.BuildingBytes empty = Golden.building("conveyor");
        Golden.BuildingBytes loaded = Golden.building(Blocks.conveyor, 0, b -> {
            b.handleItem(b, Items.copper);
            b.handleItem(b, Items.lead);
        });
        assertEquals(4, empty.write().length); //i32 len = 0
        assertEquals(4 + 2 * 4, loaded.write().length); //+ one packed i32 per item
        assertEquals(1, loaded.version());

        //decoded back through a world stream
        Vanilla.emptyWorld(8, 8);
        Building b = world.tile(4, 4).build;
        assertNull(b);
        world.tile(4, 4).setBlock(Blocks.conveyor, mindustry.game.Team.sharded, 0);
        world.tile(4, 4).build.handleItem(world.tile(4, 4).build, Items.copper);
        byte[] stream = Golden.worldStream(Vanilla.localPlayer()).deflated();
        Vanilla.reset();
        WorldView view = Oracle.decodeWorldStream(stream);
        BuildingView conveyor = view.building(4, 4);
        assertEquals("conveyor", conveyor.block());
        assertEquals(1, conveyor.fields().get("len"));
        assertEquals(1, ((ConveyorBuild)world.build(4, 4)).len);
    }

    @Test
    void callPacketsFromVanillaCallSites(){
        Wire.Frame chat = Golden.call(() -> Call.sendMessage("hi"));
        assertEquals(106, chat.id());
        assertEquals("SendMessageCallPacket", chat.packet());
        assertEquals("0100026869", Hex.of(chat.body()));
        //u8 id, u16 length, u8 compression 0, body
        assertEquals("6a000500" + "0100026869", Hex.of(chat.serialized()));
        assertEquals("0009" + "6a000500" + "0100026869", Hex.of(chat.tcp()));
        assertEquals("hi", Oracle.decodeFrame(chat.serialized(), Direction.toClient).value("message"));

        //bodies of 36 bytes and more are LZ4-compressed on the wire
        String text = "a long message ".repeat(8);
        Wire.Frame big = Golden.call(() -> Call.sendMessage(text));
        assertTrue(big.compressed());
        assertEquals(text, Oracle.decodeFrame(big.serialized(), Direction.toClient).value("message"));

        //to one connection
        Wire.Frame kick = Golden.call(() -> Call.kick(Vanilla.connection(), "bye"));
        assertEquals("KickCallPacket", kick.packet());
    }

    @Test
    void bothSidePacketsDependOnTheDirection(){
        Building sorter = Sim.empty(8, 8).place("sorter", 4, 4, 0);
        Player someone = Player.create();
        someone.id = 42;

        //server to client: the player is written
        Wire.Frame toClient = Golden.call(() -> Call.tileConfig(someone, sorter, Items.copper));
        //client to server: it is not (the server knows who sent it)
        Wire.Frame toServer = Golden.calls(NetMode.client, () -> Call.tileConfig(someone, sorter, Items.copper)).getFirst();
        assertEquals(toClient.id(), toServer.id());
        assertEquals(toClient.body().length, toServer.body().length + 4); //TypeIO.writeEntity: i32 id

        PacketView client = Oracle.decodePacket(toClient.id(), toClient.body(), Direction.toClient);
        assertEquals(List.of("player", "build", "value"), client.fields().stream().map(FieldView::name).toList());
        assertEquals(0, client.fields().get(0).offset());
        assertEquals(4, client.fields().get(1).offset());
        assertEquals(Map.of("$ref", "item:copper"), client.value("value"));

        PacketView server = Oracle.decodePacket(toServer.id(), toServer.body(), Direction.toServer);
        assertEquals(0, server.fields().stream().filter(f -> f.name().equals("build")).findFirst().orElseThrow().offset());
        assertEquals(List.of(), server.warnings());
    }

    @Test
    void packetErrorsNameTheField(){
        //stateSnapshot cut off inside its 8th field (rand0, an i64 at offset 19)
        var packet = new StateSnapshotCallPacket();
        packet.coreData = new byte[]{0};
        Wire.Frame state = Golden.packet(packet, Direction.toClient);
        byte[] cut = Arrays.copyOf(state.body(), 22);
        DecodeException e = assertThrows(DecodeException.class, () -> Oracle.decodePacket(state.id(), cut));
        assertEquals("field rand0", e.section);
        assertEquals(19, e.sectionOffset);
    }
}
