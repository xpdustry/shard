package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.sim.blocks.turret.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;
import com.xpdustry.shard.wire.v160_7.Packets;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code unitControl} with a block unit (kind 1, a turret by position), which W2.14 left to WP60: the player takes the
 * turret's block unit, the call is forwarded as received, and the player's unit is named by the turret's position.
 * An enemy team's turret is refused, as vanilla refuses a unit of another team.
 */
class BlockUnitControlTest{
    static final long ALPHA = 7;

    IntentWorld w;
    Player player;

    @BeforeEach
    void world(){
        w = new IntentWorld();
        w.rules().possessionAllowed = true;
        player = w.joinWith(ALPHA, "alpha", "alpha", 100f, 100f);
    }

    @Test
    void aPlayerTakesATurret(){
        Building duo = w.place("duo", 14, 12, player.team, 0);
        var ref = Codecs.UnitRef.block(Point2.pack(14, 12));
        w.drain();
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, new UnitIntents.UnitControl(Policy.VANILLA, ref), 1_000));
        assertSame(((TurretBuild)duo).blockUnit(), player.unit());
        assertTrue(((TurretBuild)duo).controlled());
        assertEquals(ref, Acting.ref(player.unit()), "writeUnit names it by its turret");
        assertEquals(1, IntentWorld.calls(w.drain(), Packets.UnitControl.class).size(), "forwarded");
    }

    @Test
    void anEnemyTurretIsRefused(){
        w.place("duo", 14, 12, player.team == 1 ? 2 : 1, 0);
        Unit before = player.unit();
        Verdict verdict = w.admit(ALPHA, new UnitIntents.UnitControl(Policy.VANILLA, Codecs.UnitRef.block(Point2.pack(14, 12))), 1_000);
        assertNotEquals(Verdict.ADMITTED, verdict);
        assertSame(before, player.unit());
    }
}
