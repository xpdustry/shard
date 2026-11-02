package com.xpdustry.shard.sim.ai;

import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.team.Team;
import org.junit.jupiter.api.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate WP54: a wave fight with the real AIs on both sides. Two daggers ({@code GroundAI}), two flares
 * ({@code FlyingAI}) and a crawler ({@code SuicideAI}) of the wave team come at a core behind a wall of copper with a
 * gap; for {@value #FRAMES} frames every unit (position, rotation, velocity, health, elevation, its AI's target, each
 * mount's reload, rotation, shots, aim, trigger and target), every building's health, the bullets made and alive, the
 * random state and the entity id counter equal vanilla's exactly, frame by frame. Vanilla's pathfinder runs to
 * completion after each frame ({@link VanillaPathfinder}). The fixture then asserts what it exercised: the ground
 * units walked the field, everyone fired, walls and the core took damage, the crawler blew up.
 */
class WaveFightOracleTest{
    static final int SIZE = 48, FRAMES = 900;
    static final int CORE_X = 40, CORE_Y = 24, WALL_X = 30;

    @Test
    void daggersFlaresAndACrawlerAttackACoreAsInVanilla(){
        Vanilla.boot();
        try(var o = new AiOracle(SIZE, "stone")){
            o.place("core-shard", Team.SHARDED, CORE_X, CORE_Y);
            for(int y = 16; y <= 32; y++){
                if(y == 27 || y == 28) continue;
                o.place("copper-wall", Team.SHARDED, WALL_X, y);
            }
            o.preloadPaths();
            o.spawn("dagger", Team.CRUX, 6 * 8f, 20 * 8f);
            o.spawn("dagger", Team.CRUX, 6 * 8f, 28 * 8f);
            o.spawn("flare", Team.CRUX, 4 * 8f, 8 * 8f);
            o.spawn("flare", Team.CRUX, 4 * 8f, 40 * 8f);
            o.spawn("crawler", Team.CRUX, 8 * 8f, 24 * 8f);
            o.same("spawned");

            int shots = 0, frame = 0;
            float coreHealth = coreHealth();
            boolean crawlerDied = false;
            float startX = 6 * 8f, farthest = 0f;
            for(; frame < FRAMES; frame++){
                o.frame();
                o.same("frame " + frame);
                boolean crawler = false;
                for(mindustry.gen.Unit u : mindustry.gen.Groups.unit){
                    if(u.type.name.equals("crawler")) crawler = true;
                    if(u.type.name.equals("dagger")) farthest = Math.max(farthest, u.x - startX);
                }
                crawlerDied |= !crawler;
            }
            for(mindustry.gen.Unit u : mindustry.gen.Groups.unit) for(var m : u.mounts) shots += m.totalShots;
            int damagedWalls = 0, wallsLeft = 0;
            for(var tile : world.tiles){
                var b = tile.build;
                if(b != null && b.tile == tile && b.block.name.equals("copper-wall")){
                    wallsLeft++;
                    if(b.health < b.maxHealth) damagedWalls++;
                }
            }
            System.out.println("wave fight: " + frame + " frames, " + shots + " shots by the living, daggers walked " + farthest
                + ", core " + coreHealth + " -> " + coreHealth() + ", walls left " + wallsLeft + " (" + damagedWalls + " damaged), crawler died "
                + crawlerDied + ", units left " + mindustry.gen.Groups.unit.size());
            assertTrue(farthest > 8 * 8f, "the daggers walked the flowfield");
            assertTrue(shots > 0, "units fired");
            assertTrue(damagedWalls > 0 || wallsLeft < 15, "the walls took fire");
            assertTrue(coreHealth() < coreHealth, "the core took fire");
            assertTrue(crawlerDied, "the crawler blew up");
        }
    }

    static float coreHealth(){
        var core = world.build(CORE_X, CORE_Y);
        return core == null ? 0f : core.health;
    }
}
