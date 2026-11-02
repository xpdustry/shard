package com.xpdustry.shard.server.admin;

import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The wire enums against vanilla's, from the jar on the test classpath (pure enums: no game state is touched). */
class VanillaEnumsTest{
    static Enum<?>[] vanilla(String name) throws Exception{
        return (Enum<?>[])Class.forName(name).getEnumConstants();
    }

    @Test
    void kickReasonsMatchVanillaIdsAndQuietFlags() throws Exception{
        var vanilla = vanilla("mindustry.net.Packets$KickReason");
        assertEquals(vanilla.length, KickReason.values().length);
        var quiet = Class.forName("mindustry.net.Packets$KickReason").getField("quiet");
        for(var reason : vanilla){
            var ours = KickReason.byId(reason.ordinal());
            assertEquals(reason.name(), ours.vanillaName());
            assertEquals(quiet.getBoolean(reason), ours.quiet, reason.name());
            assertEquals(reason.ordinal(), ours.id());
        }
    }

    @Test
    void adminActionsMatchVanilla() throws Exception{
        var vanilla = vanilla("mindustry.net.Packets$AdminAction");
        var names = Arrays.stream(AdminAction.values()).map(a -> a.name().toLowerCase(Locale.ROOT).replace("_", "")).toList();
        var theirs = Arrays.stream(vanilla).map(e -> e.name().toLowerCase(Locale.ROOT)).toList();
        assertEquals(theirs, names);
        assertEquals(AdminAction.TRACE, AdminAction.byId(2));
    }

    @Test
    void cooldownsFollowNetConnection(){
        var withCooldown = EnumSet.noneOf(KickReason.class);
        for(var reason : KickReason.values()) if(reason.hasCooldown()) withCooldown.add(reason);
        assertEquals(EnumSet.of(KickReason.KICK, KickReason.BANNED, KickReason.VOTE), withCooldown);
        assertThrows(IllegalArgumentException.class, () -> KickReason.byId(16));
    }
}
