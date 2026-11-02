package com.xpdustry.shard.sim.blocks.turret;

import com.xpdustry.shard.common.geom.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A player controlling a duo through its block unit ({@code BlockUnitComp}, {@code ControlBlock}): the unit takes the
 * id the duo drew at construction and draws nothing more, stays the player's while the duo stands (it is never added),
 * and the duo aims where the player aims and fires when the player shoots, with no enemy in sight. When the duo dies,
 * the player lets go. Forks and checkpoints keep the link.
 */
class BlockUnitTest{
    World world;
    TurretBuild duo;
    Player player;

    @BeforeEach
    void world(){
        world = VanillaWorlds.world(32, 32, 3);
        duo = (TurretBuild)world.buildings().setBlock(10, 10, VanillaWorlds.VANILLA.block("duo"), 1, 0, Cause.SERVER);
        for(int i = 0; i < 10; i++) duo.handleItem(null, VanillaWorlds.VANILLA.item("copper").id());
        player = Player.create(world);
        player.team = 1;
        player.add();
    }

    @Test
    void aPlayerAimsAndFiresTheDuo(){
        int nextId = world.ids().peek();
        long seed0 = world.rand().getState(0);
        Unit unit = BlockUnits.at(world, Point2.pack(10, 10));
        assertSame(unit, duo.blockUnit(), "one block unit per turret");
        assertEquals(duo.blockUnitId, unit.id(), "the id drawn at construction");
        assertEquals(nextId, world.ids().peek(), "making it draws no id");
        assertEquals(seed0, world.rand().getState(0), "nor a random value");
        assertTrue(unit.isAI(), "a CommandAI, so unitControl may take it");
        assertFalse(unit.isAdded());
        assertEquals(duo.x(), unit.x);
        assertEquals(duo.block().size() * 8 * 0.7f, unit.hitSize);

        player.unit(unit);
        assertTrue(duo.controlled());
        unit.aimX = duo.x() + 80f;
        unit.aimY = duo.y();
        unit.isShooting = true;
        for(int i = 0; i < 60; i++) world.tick();
        assertSame(unit, player.unit(), "the player keeps the block unit while the duo stands");
        assertEquals(0f, duo.turretRotation, 1e-3, "the duo turned to the player's aim");
        assertTrue(duo.totalShots > 0, "the duo fired for the player");
        assertEquals(BlockUnits.CTRL_PLAYER, duo.sense(LAccess.controlled));
        assertSame(player, duo.senseObject(LAccess.controller));

        World fork = world.fork();
        assertEquals(world.hash(), fork.hash());
        var forked = (TurretBuild)fork.buildings().at(10, 10);
        assertSame(forked, forked.blockUnit.blockTile, "the fork's block unit points at the fork's duo");

        unit.isShooting = false;
        int shots = duo.totalShots;
        for(int i = 0; i < 40; i++) world.tick();
        assertEquals(shots, duo.totalShots, "no shots once the player stops");

        world.buildings().kill(duo);
        world.tick();
        assertNull(player.unit(), "the player lets go of a dead turret's unit");
    }

    @Test
    void aProcessorCannotOverrideThePlayer(){
        player.unit(duo.blockUnit());
        duo.control(LAccess.shoot, 3, 4, 1, 0);
        assertFalse(duo.logicControlled(), "control shoot is ignored while a player controls the turret");
    }
}
