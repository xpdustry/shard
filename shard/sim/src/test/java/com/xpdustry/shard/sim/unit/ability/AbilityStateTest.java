package com.xpdustry.shard.sim.unit.ability;

import com.xpdustry.shard.content.epoch.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every ported ability with state, busy in one Shard world: shields under fire, repair and shield fields over hurt
 * allies, a suppression field over an enemy mender, a neoplasm crawler dying into renales. A fork and a checkpoint of
 * it tick on exactly as the world does, and every vanilla ability kind runs its own port. {@code AbilityOracleTest}
 * holds them to vanilla frame by frame.
 */
class AbilityStateTest{
    static final Epoch VANILLA = VanillaWorlds.VANILLA;
    static final int SHARDED = 1, CRUX = 2;

    World world;

    @BeforeEach
    void world(){
        world = UnitWorlds.world(64, 64, 3);
    }

    Unit unit(String type, int team, float x, float y, float health){
        Unit unit = Unit.spawn(world, VANILLA.unit(type), team, x, y, 0f);
        unit.health = Math.min(health, unit.health);
        return unit;
    }

    void everything(){
        unit("quasar", SHARDED, 200f, 200f, 300f);
        unit("oct", SHARDED, 260f, 200f, 1e9f);
        unit("nova", SHARDED, 210f, 230f, 50f);
        unit("pulsar", SHARDED, 190f, 220f, 100f);
        unit("tecta", SHARDED, 160f, 160f, 1e9f);
        unit("quell", SHARDED, 100f, 300f, 1e9f);
        world.buildings().setBlock(20, 38, VANILLA.block("mender"), CRUX, 0, Cause.SERVER);
        unit("latum", CRUX, 300f, 300f, 1e9f);
        for(int i = 0; i < 4; i++) unit("dagger", CRUX, 260f + i * 10f, 150f, 1e9f);
    }

    @Test
    void everyVanillaKindRunsItsOwnPort(){
        for(int id = 0; id < VANILLA.units(); id++){
            Abilities abilities = world.unitTable().abilities(VANILLA.unit(id));
            assertEquals(java.util.List.of(), abilities.unsupported(), VANILLA.unit(id).name());
        }
    }

    @Test
    void aForkTicksOnAsTheWorld(){
        everything();
        for(int i = 0; i < 200; i++) world.tick();
        World fork = world.fork();
        for(int tick = 0; tick < 300; tick++){
            if(tick == 20) kill("latum");
            world.tick();
            if(tick == 20) killIn(fork, "latum");
            fork.tick();
            int t = tick;
            assertEquals(world.hash(), fork.hash(), () -> "tick " + t + ": " + world.hash().firstDifference(fork.hash()).orElse("?"));
        }
    }

    @Test
    void aCheckpointTicksOnAsTheWorld(){
        everything();
        for(int i = 0; i < 150; i++) world.tick();
        kill("latum");
        assertEquals(5, count("renale"), "latum died into five renales");
        for(int i = 0; i < 100; i++) world.tick();
        World restored = Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
        assertEquals(world.hash(), restored.hash(), () -> world.hash().firstDifference(restored.hash()).orElse("?"));
        for(int tick = 0; tick < 300; tick++){
            world.tick();
            restored.tick();
            int t = tick;
            assertEquals(world.hash(), restored.hash(), () -> "tick " + t + ": " + world.hash().firstDifference(restored.hash()).orElse("?"));
        }
    }

    int count(String type){
        int n = 0;
        for(int i = 0; i < world.units().size(); i++) if(world.units().get(i).type().name().equals(type)) n++;
        return n;
    }

    void kill(String type){
        killIn(world, type);
    }

    static void killIn(World world, String type){
        for(int i = 0; i < world.units().size(); i++){
            Unit unit = world.units().get(i);
            if(unit.type().name().equals(type)){
                unit.kill();
                return;
            }
        }
    }
}
