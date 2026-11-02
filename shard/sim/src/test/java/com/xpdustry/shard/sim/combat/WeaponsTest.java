package com.xpdustry.shard.sim.combat;

import com.xpdustry.shard.sim.bullet.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.tile.VanillaWorlds;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Weapons in a Shard world: every vanilla weapon gets its kind's class, a world of shooting units checkpoints and
 * forks mid-burst (delayed shots and helix movers wait in the timers), aiming keeps the type's aim distance, and a
 * point-defense mount keeps a removed bullet as its target until the pool frees it.
 */
class WeaponsTest{
    /**
     * Shooters: a burst with a shot delay (scepter), a helix (obviate), alternating barrels (dagger), a charge (corvus),
     * artillery (fortress), missiles (zenith), point defense (cleroi), a laser (quasar).
     */
    static final List<String> SHOOTERS = List.of("scepter", "obviate", "dagger", "corvus", "fortress", "zenith", "cleroi", "quasar");

    @Test
    void everyWeaponRunsItsKindsClass(){
        World world = UnitWorlds.world(16, 16, 1);
        Armory armory = Armory.of(world);
        assertEquals(List.of(), armory.unsupported(), "every weapon kind in the pack is ported");
        var classes = new TreeMap<String, String>();
        int weapons = 0;
        for(int id = 0; id < world.content().units(); id++){
            Arms arms = armory.arms(world.content().unit(id));
            for(Weapon weapon : arms.weapons()){
                weapons++;
                classes.put(weapon.def().kind().toString(), weapon.getClass().getSimpleName());
                assertTrue(weapon.supported());
                assertSame(weapon.bullet, world.bullets().types(world).get(weapon.def().bullet()));
            }
        }
        assertTrue(weapons >= 149, weapons + " weapons");
        assertEquals("PointDefenseWeapon", classes.get("point-defense-weapon"));
        assertEquals("RepairBeamWeapon", classes.get("repair-beam-weapon"));
        assertEquals("BuildWeapon", classes.get("build-weapon"));
        assertEquals("Weapon", classes.get("weapon"));
    }

    @Test
    void aWorldRestoredMidBurstContinuesLikeItsSourceAndFork(){
        World world = shooting(7);
        for(int i = 0; i < 30 || (i < 400 && world.timers().pending().isEmpty()); i++) world.tick();
        assertFalse(world.timers().pending().isEmpty(), "delayed shots are queued");
        assertTrue(world.bullets().size() > 0);
        World restored = Checkpoint.decode(Checkpoint.capture(world).encode(Compression.FAST)).restore(world.config());
        World fork = world.fork();
        assertEquals(world.hash(), restored.hash(), () -> world.hash().firstDifference(restored.hash()).orElse("?"));
        for(int i = 0; i < 240; i++){
            world.tick();
            restored.tick();
            fork.tick();
            int tick = i;
            assertEquals(world.hash(), restored.hash(), () -> "tick " + tick + ": " + world.hash().firstDifference(restored.hash()).orElse("?"));
            assertEquals(world.hash(), fork.hash(), () -> "tick " + tick + ": " + world.hash().firstDifference(fork.hash()).orElse("?"));
        }
        int shots = 0;
        for(int i = 0; i < world.units().size(); i++){
            for(WeaponMount mount : world.units().get(i).mounts) shots += mount.totalShots;
        }
        assertTrue(shots > 100, shots + " shots");
    }

    @Test
    void aimingKeepsTheAimDistance(){
        World world = UnitWorlds.world(16, 16, 2);
        Unit dagger = Unit.spawn(world, VanillaWorlds.VANILLA.unit("dagger"), 1, 60f, 60f, 0f);
        float aimDst = Arms.of(dagger).aimDst();
        assertTrue(aimDst > 0f);
        Weapons.aim(dagger, 61f, 60f);
        assertEquals(60f + aimDst, dagger.aimX, 1e-4f);
        assertEquals(60f, dagger.aimY, 1e-4f);
        for(WeaponMount mount : dagger.mounts) assertEquals(dagger.aimX, mount.aimX);
        Weapons.controlWeapons(dagger, true);
        assertTrue(Weapons.isShooting(dagger));
        assertTrue(dagger.mounts[0].shoot);
    }

    @Test
    void pointDefenseKeepsARemovedBulletUntilThePoolFreesIt(){
        World world = UnitWorlds.world(32, 32, 3);
        Unit cleroi = Unit.spawn(world, VanillaWorlds.VANILLA.unit("cleroi"), 1, 100f, 100f, 0f);
        int defense = -1;
        // the first of the pair: it shoots the bullet down, and its mirror then finds an empty group
        for(int i = cleroi.mounts.length - 1; i >= 0; i--) if(Weapon.of(cleroi, cleroi.mounts[i]) instanceof PointDefenseWeapon) defense = i;
        assertTrue(defense >= 0);
        WeaponMount mount = cleroi.mounts[defense];
        BulletType slow = world.bullets().types(world).get(VanillaWorlds.VANILLA.unit("dagger").weapons().getFirst().bullet());
        Bullet threat = slow.create(world, null, 2, 140f, 100f, 180f);
        assertNotNull(threat);
        int object = threat.object();
        for(int i = 0; i < 12 && mount.targetBullet != object; i++) world.tick();
        assertEquals(object, mount.targetBullet, "it targets the bullet");
        Bullet.ofObject(world, object).remove();
        Bullet removed = Bullet.ofObject(world, object);
        assertNotNull(removed, "the removed bullet waits in the limbo");
        assertFalse(removed.isAdded());
        assertEquals(1, world.bullets().limbo().size());
        world.tick();
        assertNull(Bullet.ofObject(world, object), "the pool freed it");
        assertEquals(-1, mount.targetBullet, "a freed bullet is no target");
    }

    /** Shooting units of both teams, firing at each other's side of a small map. */
    static World shooting(long seed){
        World world = UnitWorlds.world(64, 64, seed);
        for(int i = 0; i < SHOOTERS.size(); i++){
            int team = 1 + i % 2;
            Unit unit = Unit.spawn(world, VanillaWorlds.VANILLA.unit(SHOOTERS.get(i)), team, 160f + i * 30f, team == 1 ? 180f : 330f, 0f);
            unit.controller(new Aim(250f, team == 1 ? 330f : 180f));
        }
        return world;
    }

    /** Turns to a point and fires every controllable weapon at it. */
    record Aim(float x, float y) implements UnitController{
        @Override
        public void updateUnit(Unit unit){
            unit.lookAt(x, y);
            Weapons.aim(unit, x, y);
            Weapons.controlWeapons(unit, true);
        }

        @Override
        public UnitController copy(CopyContext context){
            return this;
        }

        @Override
        public void hash(Hasher hasher){
            hasher.f(x).f(y);
        }
    }
}
