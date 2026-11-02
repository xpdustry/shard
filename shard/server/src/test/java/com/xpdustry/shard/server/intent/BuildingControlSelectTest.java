package com.xpdustry.shard.server.intent;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.sim.blocks.payload.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.wire.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code buildingControlSelect} (InputHandler.java:754), as a ctrl-click sends it: a player controlling a turret who
 * selects the core respawns there as its unit type; a player's dagger standing on a payload conveyor enters it, gets a
 * new id the next tick, and the player is left without a unit.
 */
class BuildingControlSelectTest{
    static final long ALPHA = 7;

    IntentWorld w;
    Player player;

    @BeforeEach
    void world(){
        w = new IntentWorld();
        w.rules().possessionAllowed = true;
        player = w.joinWith(ALPHA, "alpha", "dagger", 160f, 160f);
    }

    @Test
    void aPlayerInATurretSelectsTheCore(){
        Building core = w.place("core-shard", 5, 5, player.team, 0);
        w.place("duo", 14, 12, player.team, 0);
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, new UnitIntents.UnitControl(Policy.VANILLA, Codecs.UnitRef.block(Point2.pack(14, 12))), 1_000));
        assertNotNull(player.unit().blockTile, "the player controls the duo");
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, new UnitIntents.BuildingControlSelect(Policy.VANILLA, core.pos()), 1_100));
        assertEquals("alpha", player.unit().type().name(), "respawned as the core's unit");
        assertTrue(player.unit().spawnedByCore);
    }

    @Test
    void aDaggerEntersThePayloadConveyorUnderIt(){
        var conveyor = (PayloadConveyorBuild)w.place("payload-conveyor", 20, 20, player.team, 0);
        Unit dagger = player.unit();
        int id = dagger.id();
        assertEquals(Verdict.ADMITTED, w.admit(ALPHA, new UnitIntents.BuildingControlSelect(Policy.VANILLA, conveyor.pos()), 1_000));
        assertTrue(conveyor.item instanceof Payload.UnitPayload(Unit carried) && carried == dagger, "the conveyor holds the dagger");
        assertTrue(player.dead(), "the player left the dagger");
        assertFalse(dagger.isAdded());
        w.world.tick();
        assertNotEquals(id, dagger.id(), "a new id the tick after it entered");
    }
}
