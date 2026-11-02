package com.xpdustry.shard.wire;

import arc.func.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.wire.v160_7.*;
import com.xpdustry.shard.wire.v160_7.EntityClasses.*;
import mindustry.gen.*;
import mindustry.net.Net;
import mindustry.net.Packet;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The generated tables against a booted vanilla: packet ids, names, priorities and {@code allow()}, the
 * {@code EntityMapping} classes, and the enums the wire carries as ordinals (with the sizes {@link WireObjects} and
 * {@link Codecs} hard-code).
 */
class RegistryTest{
    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    @Test
    void packets(){
        assertEquals(173, Packets.TYPES.size());
        for(PacketType type : Packets.TYPES){
            Packet packet = Net.newPacket((byte)type.id());
            assertEquals(type.name(), PacketRoundTripTest.vanillaName(packet));
            assertEquals(type.priority().ordinal(), packet.getPriority(), type.name());
            assertEquals(type.direction().toServerAllowed(), packet.allow(true), type.name() + ": a server reads it");
            assertEquals(type.direction().toClientAllowed(), packet.allow(false), type.name() + ": a client reads it");
            assertEquals(type.framework(), !packet.getClass().getName().startsWith("mindustry.gen."), type.name() + ": framework");
        }
        assertThrows(RuntimeException.class, () -> Net.newPacket((byte)173), "vanilla has no packet 173 either");
    }

    @Test
    void entityClasses(){
        int classes = 0;
        for(int id = 0; id < 256; id++){
            Prov<?> constructor = EntityMapping.map(id);
            EntityClass type = EntityClasses.get(id);
            if(constructor == null){
                assertNull(type, "class " + id);
                continue;
            }
            classes++;
            Entityc entity = (Entityc)constructor.get();
            assertNotNull(type, entity.getClass().getSimpleName());
            assertEquals(entity.getClass().getSimpleName(), type.name());
            assertEquals(id, entity.classId() & 0xFF, type.name());
            assertEquals(entity.serialize(), type.serialize(), type.name() + ": serialize");
            assertEquals(entity instanceof Unit, type.unit(), type.name() + ": unit");
            assertEquals(entity instanceof Syncc, type.synced(), type.name() + ": synced");
        }
        assertEquals(EntityClasses.ALL.size(), classes);
    }

    static void sameEnum(Class<? extends Enum<?>> vanilla, Enum<?>[] generated){
        assertEquals(Arrays.stream(vanilla.getEnumConstants()).map(Enum::name).toList(), Arrays.stream(generated).map(Enum::name).toList(), vanilla.getName());
    }

    @Test
    void enums(){
        sameEnum(mindustry.net.Packets.AdminAction.class, AdminAction.values());
        sameEnum(mindustry.net.Packets.KickReason.class, KickReason.values());
        sameEnum(mindustry.logic.LAccess.class, LAccess.values());
        sameEnum(mindustry.logic.LMarkerControl.class, LMarkerControl.values());
        sameEnum(mindustry.ctype.ContentType.class, ContentType.values());
        sameEnum(mindustry.ui.builder.UiKey.class, UiKey.values());

        assertEquals(ContentType.values().length, WireObjects.CONTENT_TYPES);
        assertEquals(LAccess.values().length, WireObjects.LACCESS_VALUES);
        assertEquals(UiKey.row.ordinal(), Codecs.UI_NODE_TYPES);
        assertEquals(UiKey.values().length, Codecs.UI_KEYS);
        assertThrows(WireException.class, () -> KickReason.of(KickReason.values().length));
        assertThrows(WireException.class, () -> AdminAction.of(-1));
    }
}
