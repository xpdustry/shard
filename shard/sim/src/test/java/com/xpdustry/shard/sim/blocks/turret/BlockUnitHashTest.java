package com.xpdustry.shard.sim.blocks.turret;

import com.xpdustry.shard.sim.ai.CommandAi;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Review 40: a turret's block unit is in no world list, so the turret's hash holds its state, possessed or not: the aim
 * and shooting flag a controlling player gives it, which the turret fires by, and its controller. Two worlds that
 * differ only there hash apart before the next tick, and their duos then fire differently. A checkpoint keeps it all.
 */
class BlockUnitHashTest{
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
        // the player aims the loaded duo to the right and fires
        Unit unit = duo.blockUnit();
        player.unit(unit);
        unit.aimX = duo.x() + 80f;
        unit.aimY = duo.y();
        unit.isShooting = true;
        for(int i = 0; i < 10; i++) world.tick();
    }

    static TurretBuild duo(World world){
        return (TurretBuild)world.buildings().at(10, 10);
    }

    static List<String> bullets(World world){
        var out = new ArrayList<String>();
        for(int i = 0; i < world.bullets().size(); i++) out.add(world.bullets().x[i] + "," + world.bullets().y[i]);
        return out;
    }

    static void tick(int ticks, World... worlds){
        for(int i = 0; i < ticks; i++) for(World world : worlds) world.tick();
    }

    @Test
    void aForkAimedElsewhereHashesApartAndShootsElsewhere(){
        World fork = world.fork();
        assertEquals(world.hash(), fork.hash());
        duo(fork).blockUnit.aimY = duo.y() + 80f;
        assertEquals("buildings", world.hash().firstDifference(fork.hash()).orElseThrow(), "the duo's section");

        tick(60, world, fork);
        assertNotEquals(duo.turretRotation, duo(fork).turretRotation, "the fork's duo turned up");
        assertFalse(bullets(fork).isEmpty());
        assertNotEquals(bullets(world), bullets(fork), "the shots");
    }

    @Test
    void theShootingFlagIsHashed(){
        World fork = world.fork();
        duo(fork).blockUnit.isShooting = false;
        assertEquals("buildings", world.hash().firstDifference(fork.hash()).orElseThrow());
        int shots = duo(fork).totalShots;
        tick(60, world, fork);
        assertEquals(shots, duo(fork).totalShots, "the fork's duo stopped");
        assertTrue(duo.totalShots > shots);
    }

    @Test
    void anIdleBlockUnitsControllerIsHashed(){
        player.clearUnit();
        world.tick();
        var ai = assertInstanceOf(CommandAi.class, duo.blockUnit.controller, "the type's controller again");
        World fork = world.fork();
        assertEquals(world.hash(), fork.hash());
        ((CommandAi)duo(fork).blockUnit.controller).command = ai.command + 1;
        assertEquals("buildings", world.hash().firstDifference(fork.hash()).orElseThrow());
    }

    @Test
    void thePlayersSectionHoldsTheUnitOnceItsTurretIsGone(){
        world.buildings().remove(duo, Cause.SERVER);
        World fork = world.fork();
        Unit unit = Players.of(fork).list().get(0).unit();
        assertNotNull(unit, "held until the player's next update");
        unit.aimY += 8f;
        assertNotEquals(world.hash(), fork.hash());
    }

    @Test
    void aCheckpointKeepsThePossessedUnit(){
        World restored = Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
        assertEquals(world.hash(), restored.hash());
        assertSame(Players.of(restored).list().get(0).unit(), duo(restored).blockUnit, "the player still controls it");
        tick(60, world, restored);
        assertEquals(world.hash(), restored.hash());
        assertEquals(bullets(world), bullets(restored));
        assertTrue(duo(restored).totalShots > 0);
    }

    /**
     * W5.5's F3: the block unit exists from the turret's construction, as vanilla's does, so a client's reference to an
     * idle turret's unit ({@code BlockUnits.at}, admitted or refused) is a pure read: two forks, one of which read it, hash
     * alike now and 300 ticks on.
     */
    @Test
    void readingAnIdleTurretsBlockUnitChangesNothing(){
        var idle = (TurretBuild)world.buildings().setBlock(20, 20, VanillaWorlds.VANILLA.block("duo"), 1, 0, Cause.SERVER);
        assertNotNull(idle.blockUnit, "made at construction");
        world.tick();
        World read = world.fork(), untouched = world.fork();
        assertSame(duo(read).blockUnit, BlockUnits.at(read, com.xpdustry.shard.common.geom.Point2.pack(10, 10)));
        assertNotNull(BlockUnits.at(read, com.xpdustry.shard.common.geom.Point2.pack(20, 20)));
        assertEquals(untouched.hash().value(), read.hash().value(), "right after the read");
        tick(300, read, untouched);
        assertEquals(untouched.hash().value(), read.hash().value(), "300 ticks on");
    }
}
