package com.xpdustry.shard.compat.v160.combat;

import com.xpdustry.shard.common.io.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.wire.v160_7.*;
import mindustry.game.*;
import mindustry.gen.*;
import org.junit.jupiter.api.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/** Gate: a created bullet's packet is vanilla's {@code Call.createBullet}, id and bytes, for every bullet type. */
class BulletCallsOracleTest{
    @Test
    void createBulletIsVanillas(){
        Vanilla.boot();
        int types = content.bullets().size;
        assertTrue(types > 100);
        for(int id = 0; id < types; id++){
            var event = new BulletEvent.Created(id, id % 7, 12.5f + id, -3f * id, id * 1.5f, -1f + id, 0.75f, 1.25f);
            var type = content.bullet(id);
            // a server runs the call too: a bullet that spawns a unit sends the unit's spawn first
            Wire.Frame frame = Golden.calls(() -> Call.createBullet(type, Team.get(event.team()), event.x(), event.y(),
                event.angle(), event.damage(), event.velocityScl(), event.lifetimeScl())).getLast();
            Packets.CreateBullet packet = BulletCalls.createBullet(event);
            assertEquals(frame.id(), packet.packetId());
            var write = new Writes();
            packet.write(write, true, null);
            assertArrayEquals(frame.body(), write.toByteArray(), "bullet " + id);
        }
        assertNull(BulletCalls.packet(new BulletEvent.Hit(0, 1, 0f, 0f, 0f)));
    }
}
