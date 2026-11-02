package com.xpdustry.shard.wire;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.wire.v160_7.*;
import com.xpdustry.shard.wire.v160_7.EntityClasses.*;
import org.junit.jupiter.api.*;

import java.util.function.*;

import static org.junit.jupiter.api.Assertions.*;

/** What the generated registry refuses before any codec runs. The oracle tests check what it accepts. */
class GeneratedTest{
    static final WireContext context = TestContext.instance;

    static WireException rejects(Executable read){
        return assertThrows(WireException.class, read::run);
    }

    interface Executable{
        void run();
    }

    static Reads reads(Consumer<Writes> body){
        Writes write = new Writes();
        body.accept(write);
        return new Reads(write.toByteArray());
    }

    @Test
    void packetIds(){
        for(int id : new int[]{-1, Packets.TYPES.size(), 255}){
            assertNull(Packets.type(id));
            rejects(() -> Packets.read(id, new Reads(new byte[64]), false, context));
        }
        for(PacketType type : Packets.TYPES){
            assertEquals(type, Packets.type(type.id()));
            if(type.framework()){
                var e = rejects(() -> Packets.read(type.id(), new Reads(new byte[64]), type.direction() != PacketType.Direction.toServer, context));
                assertTrue(e.getMessage().contains(type.name()), e.getMessage());
            }
        }
    }

    @Test
    void directions(){
        var e = rejects(() -> Packets.read(Packets.PING, reads(w -> w.l(1)), true, context));
        assertEquals("Ping may not be sent by the server", e.getMessage());
        e = rejects(() -> Packets.read(Packets.SEND_MESSAGE, reads(w -> Codecs.writeString(w, "hi")), false, context));
        assertEquals("SendMessage may not be sent by a client", e.getMessage());
        assertEquals(new Packets.Ping(1), Packets.read(Packets.PING, reads(w -> w.l(1)), false, context));
    }

    @Test
    void bothWayPacketsCarryTheSenderFromTheServer(){
        var tap = new Packets.TileTap(1, 0x00030004);
        Writes server = new Writes(), client = new Writes();
        tap.write(server, true, context);
        tap.write(client, false, context);
        assertEquals(8, server.size());
        assertEquals(4, client.size());
        assertEquals(tap, Packets.read(Packets.TILE_TAP, new Reads(server.toByteArray()), true, context));
        assertEquals(new Packets.TileTap(-1, 0x00030004), Packets.read(Packets.TILE_TAP, new Reads(client.toByteArray()), false, context));
    }

    @Test
    void readEntitiesMustBeOfTheCastType(){
        //TestContext: entity 1 is a player, 2-9 are mech units, anything else doesn't exist
        Function<Integer, Reads> request = other -> reads(w -> {
            w.i(other);
            w.b(AdminAction.kick.ordinal());
            WireObjects.writeObjectSafe(w, null);
        });
        assertEquals(1, ((Packets.AdminRequest)Packets.read(Packets.ADMIN_REQUEST, request.apply(1), false, context)).other());
        assertEquals(77, ((Packets.AdminRequest)Packets.read(Packets.ADMIN_REQUEST, request.apply(77), false, context)).other(), "unknown is null");
        var e = rejects(() -> Packets.read(Packets.ADMIN_REQUEST, request.apply(2), false, context));
        assertTrue(e.getMessage().contains("not a Player"), e.getMessage());
        //the server's copy of a both-way packet casts its sender too
        rejects(() -> Packets.read(Packets.TILE_TAP, reads(w -> {
            w.i(2);
            w.i(0);
        }), true, context));
        assertTrue(EntityClasses.isPlayer(EntityClasses.PLAYER));
        assertTrue(EntityClasses.isItemsc(EntityClasses.MECH_UNIT));
        assertFalse(EntityClasses.isItemsc(EntityClasses.PLAYER));
    }

    @Test
    void aShortBodyNamesThePacket(){
        var e = rejects(() -> Packets.read(Packets.PING, new Reads(new byte[7]), false, context));
        assertTrue(e.getMessage().startsWith("Ping"), e.getMessage());
    }

    static EntityClass find(Predicate<EntityClass> filter){
        return EntityClasses.ALL.stream().filter(filter).findFirst().orElseThrow();
    }

    @Test
    void entityClasses(){
        assertNull(EntityClasses.get(-1));
        assertNull(EntityClasses.get(256));
        for(EntityClass c : EntityClasses.ALL) assertSame(c, EntityClasses.get(c.id()));

        EntityClass unsynced = find(c -> !c.synced());
        rejects(() -> EntityClasses.readSync(new Reads(new byte[64]), unsynced.id(), false, context));
        rejects(() -> EntityClasses.readSync(new Reads(new byte[64]), 255, false, context));

        EntityClass unsaved = find(c -> c.revision() < 0);
        rejects(() -> EntityClasses.readSave(new Reads(new byte[64]), unsaved.id(), context));
        EntityClass saved = find(c -> c.revision() >= 0);
        var e = rejects(() -> EntityClasses.readSave(reads(w -> w.s(saved.revision() + 1)), saved.id(), context));
        assertTrue(e.getMessage().contains("has no revision " + (saved.revision() + 1)), e.getMessage());
        rejects(() -> EntityClasses.readSave(reads(w -> w.s(-1)), saved.id(), context));
    }

    @Test
    void payloads(){
        assertNull(EntityClasses.readPayload(reads(w -> w.bool(false)), context));
        rejects(() -> EntityClasses.readPayload(reads(w -> {
            w.bool(true);
            w.b(2);
        }), context));
        EntityClass notAUnit = find(c -> !c.unit() && c.revision() >= 0);
        var e = rejects(() -> EntityClasses.readPayload(reads(w -> {
            w.bool(true);
            w.b(0);
            w.b(notAUnit.id());
            w.s(notAUnit.revision());
        }), context));
        assertTrue(e.getMessage().contains("not a unit"), e.getMessage());
        rejects(() -> EntityClasses.readPayload(reads(w -> {
            w.bool(true);
            w.b(0);
            w.b(-1);
        }), context));
    }

    @Test
    void enumOrdinals(){
        assertEquals(KickReason.banned, KickReason.of(KickReason.banned.ordinal()));
        rejects(() -> KickReason.of(-1));
        rejects(() -> AdminAction.of(AdminAction.values().length));
        rejects(() -> LMarkerControl.of(255));
    }
}
