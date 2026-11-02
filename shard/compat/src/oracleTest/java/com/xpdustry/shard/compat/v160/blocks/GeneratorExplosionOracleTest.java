package com.xpdustry.shard.compat.v160.blocks;

import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.bullet.*;
import mindustry.gen.*;
import org.junit.jupiter.api.*;

import java.lang.reflect.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP41 with WP38): a generator's explosion applied, frame by frame as in vanilla. A thorium reactor without
 * coolant melts down among walls, batteries, a node and boulders with explosion damage on: the area damage and its
 * tile pass in the order of vanilla's map, the buildings it destroys and their own explosions (a battery's lightning
 * and damage wave), the reactor's base explosion, and the fireballs it throws. Besides {@link PowerRun}'s comparisons
 * (random state, entity ids, pending timers, graphs, buildings), every frame compares each building's health and every
 * live bullet.
 *
 * <p>Fire is on: the ignitions it schedules and the fireballs' trails start real fires, which burn the buildings
 * left and throw fireballs of their own.
 */
class GeneratorExplosionOracleTest{
    static final int AFTER = 300;

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    @Test
    void thoriumReactorMeltdownDamagesItsSurroundings(){
        var run = new PowerRun(48, 48);
        HazardPools.empty();
        run.timers = true;
        // vanilla's lightning seed is one counter per JVM, Shard's one per world
        setStatic(mindustry.entities.Lightning.class, "lastSeed", 0);
        run.place("item-source", 22, 24);
        run.source(22, 24, "thorium");
        run.place("thorium-reactor", 24, 24);
        run.place("battery", 26, 24);
        run.place("illuminator", 26, 25);
        run.place("power-node", 30, 24);
        run.place("illuminator", 33, 24);
        for(int x = 18; x <= 30; x += 3) run.place("copper-wall", x, 30);
        run.place("thorium-wall-large", 28, 18);
        run.place("titanium-wall", 24, 36);
        run.place("titanium-wall", 24, 44);
        run.place("battery", 10, 24);
        run.place("battery", 19, 22);
        run.place("battery-large", 14, 28);
        // only the ray straight up reaches it, after a copper and a titanium wall: it survives, damaged
        run.place("thorium-wall-large", 24, 42);
        for(int[] at : new int[][]{{20, 20}, {30, 27}, {24, 40}, {24, 45}, {5, 5}}) run.place("boulder", at[0], at[1]);
        run.at(1, () -> {
            run.status(10, 24, 1f);
            run.status(19, 22, 1f);
            run.status(14, 28, 1f);
        });
        int died = run.until(400, () -> run.vanilla(24, 24) == null);
        assertTrue(died < 400, "the reactor melted down");

        int before = run.buildings().size();
        var seen = new TreeSet<String>();
        for(int i = 0; i < AFTER; i++){
            run.stepUnchecked();
            String when = "frame " + run.frame;
            run.compare("after " + when);
            assertEquals(String.join("\n", vanillaHealth(run)), String.join("\n", shardHealth(run)), "health after " + when);
            List<String> bullets = vanillaBullets();
            assertEquals(String.join("\n", bullets), String.join("\n", shardBullets(run)), "bullets after " + when);
            for(var b : Groups.bullet){
                if(b.type == mindustry.content.Bullets.fireball) seen.add("fireball");
                else if(b.type == mindustry.content.Bullets.damageLightning) seen.add("lightning");
                else seen.add("bullet type " + b.type.id);
            }
        }
        int after = run.buildings().size();
        System.out.println("generator explosion: " + (before - after) + " buildings destroyed, bullet kinds " + seen);
        assertTrue(after < before, "the explosion destroyed buildings");
        assertTrue(seen.contains("fireball"), "fireballs flew: " + seen);
        assertTrue(seen.contains("lightning"), "a battery's lightning struck: " + seen);
        assertTrue(run.buildings().stream().anyMatch(b -> b.health < b.maxHealth), "a building survived damaged");
    }

    static List<String> vanillaHealth(PowerRun run){
        var out = new ArrayList<String>();
        for(var build : run.buildings()) out.add(build.block.name + " (" + build.tile.x + ", " + build.tile.y + ") " + build.health);
        return out;
    }

    /** By vanilla's buildings, which {@link PowerRun#compare} has matched to Shard's. */
    static List<String> shardHealth(PowerRun run){
        var out = new ArrayList<String>();
        for(var build : run.buildings()){
            var ours = run.ours(build.tile.x, build.tile.y);
            out.add(build.block.name + " (" + build.tile.x + ", " + build.tile.y + ") " + (ours == null ? "missing" : ours.health));
        }
        return out;
    }

    static List<String> vanillaBullets(){
        var out = new ArrayList<String>();
        for(var b : Groups.bullet){
            out.add(bullet(b.id, b.type.id, b.team.id, b.x, b.y, b.vel.x, b.vel.y, b.time, b.lifetime, b.damage));
        }
        return out;
    }

    static List<String> shardBullets(PowerRun run){
        var out = new ArrayList<String>();
        Bullets t = run.shard.bullets();
        for(int r = 0; r < t.size(); r++){
            out.add(bullet(t.id[r], t.type[r], t.team[r], t.x[r], t.y[r], t.velX[r], t.velY[r], t.time[r], t.lifetime[r], t.damage[r]));
        }
        return out;
    }

    static String bullet(int id, int type, int team, float x, float y, float vx, float vy, float time, float lifetime, float damage){
        return id + " t" + type + " team" + team + " (" + x + ", " + y + ") v(" + vx + ", " + vy + ") time " + time + "/" + lifetime
            + " dmg " + damage;
    }

    static void setStatic(Class<?> owner, String name, int value){
        try{
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            field.setInt(null, value);
        }catch(ReflectiveOperationException e){
            throw new AssertionError(e);
        }
    }
}
