package com.xpdustry.shard.sim.blocks.defense;

import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.blocks.turret.*;
import com.xpdustry.shard.sim.blocks.units.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W3.6's kinds in one Shard world, every one of them working: menders and regen projectors healing walls, an
 * overdrive boosting a conveyor, force and base shields under fire, point defence, a tractor beam, repair turrets and
 * towers on units, a shockwave tower, a radar and a build tower. A fork and a checkpoint of it tick on exactly as the
 * world does. {@code ProjectorOracleTest} and {@code BuildTowerOracleTest} hold the kinds to vanilla frame by frame.
 */
class ProjectorTest{
    static final Epoch BLOCKS = VanillaWorlds.VANILLA;
    static final int SHARDED = 1, CRUX = 2;

    World world;
    Buildings buildings;

    @BeforeEach
    void world(){
        world = VanillaWorlds.world(96, 96, 1);
        buildings = world.buildings();
    }

    Building place(String block, int x, int y, int team){
        return buildings.setBlock(x, y, BLOCKS.block(block), team, 0, Cause.SERVER);
    }

    void liquid(Building building, String liquid, float amount){
        building.handleLiquid(building, BLOCKS.liquid(liquid).id(), amount);
    }

    void items(Building building, String item, int amount){
        for(int i = 0; i < amount; i++) building.handleItem(null, BLOCKS.item(item).id());
    }

    Unit unit(String type, int team, int x, int y, float health){
        Unit unit = Unit.spawn(world, BLOCKS.unit(type), team, x * 8f, y * 8f, 0f);
        unit.health = health;
        return unit;
    }

    /** Every kind, each set up to do its job. */
    void everything(){
        place("mender", 10, 10, SHARDED);
        place("power-source", 9, 10, SHARDED);
        items(buildings.at(10, 10), "silicon", 2);
        place("copper-wall", 12, 10, SHARDED).damage(100f);
        place("overdrive-projector", 10, 20, SHARDED);
        place("power-source", 9, 20, SHARDED);
        place("conveyor", 12, 22, SHARDED);
        place("regen-projector", 20, 10, SHARDED);
        place("power-source", 18, 10, SHARDED);
        liquid(buildings.at(20, 10), "hydrogen", 20f);
        place("copper-wall", 22, 13, SHARDED).damage(100f);
        place("force-projector", 40, 40, SHARDED);
        place("power-source", 38, 40, SHARDED);
        place("shockwave-tower", 40, 50, SHARDED);
        place("power-source", 38, 50, SHARDED);
        liquid(buildings.at(40, 50), "cyanogen", 20f);
        place("segment", 44, 44, SHARDED);
        place("power-source", 43, 44, SHARDED);
        place("shield-projector", 70, 70, SHARDED);
        place("power-source", 68, 70, SHARDED);
        place("duo", 52, 40, CRUX);
        items(buildings.at(52, 40), "graphite", 10);
        place("hail", 60, 44, CRUX);
        items(buildings.at(60, 44), "graphite", 10);
        place("duo", 70, 94, CRUX);
        items(buildings.at(70, 94), "copper", 10);
        place("parallax", 20, 60, SHARDED);
        place("power-source", 19, 60, SHARDED);
        unit("flare", CRUX, 30, 70, 70f);
        place("repair-point", 10, 40, SHARDED);
        place("power-source", 9, 40, SHARDED);
        unit("dagger", SHARDED, 12, 42, 30f);
        place("repair-turret", 10, 50, SHARDED);
        place("power-source", 9, 50, SHARDED);
        unit("mace", SHARDED, 14, 52, 50f);
        place("unit-repair-tower", 20, 40, SHARDED);
        place("power-source", 19, 40, SHARDED);
        liquid(buildings.at(20, 40), "ozone", 20f);
        unit("dagger", SHARDED, 22, 43, 40f);
        place("radar", 30, 10, SHARDED);
        place("power-source", 29, 10, SHARDED);
        place("build-tower", 30, 30, SHARDED);
        place("power-source", 28, 30, SHARDED);
        liquid(buildings.at(30, 30), "nitrogen", 20f);
    }

    void tick(World world, int ticks){
        for(int i = 0; i < ticks; i++) world.tick();
    }

    @Test
    void everyKindDoesItsJob(){
        everything();
        tick(world, 400);
        assertTrue(buildings.at(12, 10).health > 220f, "the mender healed its wall");
        assertTrue(buildings.at(22, 13).health > 220f, "the regen projector healed its wall");
        assertTrue(((MendProjector)buildings.at(10, 10)).phaseHeat > 0.5f, "silicon warmed the mender");
        assertTrue(buildings.at(12, 22).timeScale > 1f, "the overdrive boosts the conveyor");
        assertTrue(((ForceProjector)buildings.at(40, 40)).radscl > 0.9f, "the force shield is up");
        assertTrue(((Radar)buildings.at(30, 10)).progress > 0.5f, "the radar discovered");
        assertTrue(((BaseShield)buildings.at(70, 70)).radius() > 150f, "the base shield grew");
        assertNotNull(((BuildTurret)buildings.at(30, 30)).unit, "the build tower has its unit");
        assertTrue(buildings.at(30, 30) instanceof BuildTurret);
        assertTrue(buildings.at(44, 44) instanceof PointDefenseTurret);
        assertTrue(buildings.at(20, 60) instanceof TractorBeamTurret);
        assertTrue(buildings.at(10, 40) instanceof RepairTurret);
        assertTrue(buildings.at(20, 40) instanceof RepairTower);
        assertTrue(buildings.at(40, 50) instanceof ShockwaveTower);
    }

    /** The first building whose own hash differs between two worlds, or "". */
    static String firstDifferentBuilding(World a, World b){
        for(int i = 0; i < a.buildings().indexLimit(); i++){
            Building x = a.buildings().get(i), y = b.buildings().get(i);
            if(x == null && y == null) continue;
            if(x == null || y == null) return "index " + i + ": " + x + " vs " + y;
            var hx = new Hasher();
            var hy = new Hasher();
            x.hashState(hx);
            y.hashState(hy);
            if(hx.value() != hy.value()) return x + " (" + x.getClass().getSimpleName() + ")";
        }
        return "";
    }

    @Test
    void aForkTicksOnAsTheWorld(){
        everything();
        tick(world, 200);
        World fork = world.fork();
        for(int i = 0; i < 200; i++){
            world.tick();
            fork.tick();
            int tick = i;
            assertEquals(world.hash(), fork.hash(), () -> "tick " + tick + ": " + world.hash().firstDifference(fork.hash()).orElse("?"));
        }
    }

    @Test
    void aCheckpointTicksOnAsTheWorld(){
        everything();
        tick(world, 200);
        World restored = Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
        assertEquals("", firstDifferentBuilding(world, restored));
        assertEquals(world.hash(), restored.hash(), () -> world.hash().firstDifference(restored.hash()).orElse("?"));
        for(int i = 0; i < 200; i++){
            world.tick();
            restored.tick();
            int tick = i;
            assertEquals(world.hash(), restored.hash(), () -> "tick " + tick + ": " + world.hash().firstDifference(restored.hash()).orElse("?"));
        }
    }
}
