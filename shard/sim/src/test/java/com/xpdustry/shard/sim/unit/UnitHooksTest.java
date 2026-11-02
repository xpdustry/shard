package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.sim.combat.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.tile.VanillaWorlds;
import com.xpdustry.shard.sim.world.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The unit core runs its {@link UnitHooks} where vanilla's {@code UnitComp} runs their bodies: the ability update after
 * the environment check and before drag (and before the controller), the destroyed hook between the death explosion
 * and the crash damage, the abilities' death after the crash damage, the removed hook last in removal. A probe part stands in for the combat part and logs.
 */
class UnitHooksTest{
    @Test
    void theAbilityUpdateRunsAfterTheEnvironmentCheckAndBeforeDrag(){
        World world = probed(1);
        Probe probe = world.part(Probe.class);
        Unit dagger = Unit.spawn(world, VanillaWorlds.VANILLA.unit("dagger"), 1, 60f, 60f, 0f);
        dagger.controller(new Logging(probe.log));
        dagger.drag = 123f;
        world.tick();
        assertEquals(List.of("abilities drag 123.0 dead false", "controller"), probe.log, "before drag, before the controller");
        assertNotEquals(123f, dagger.drag, "drag was set after the hook");

        // a dagger can't live in space: the environment check kills it before its abilities update
        probe.log.clear();
        world.part(WorldRules.class).rules.env = Env.SPACE;
        float drag = dagger.drag;
        world.tick();
        assertEquals(List.of("abilities drag " + drag + " dead true"), probe.log.subList(0, 1), "after the environment death");
    }

    @Test
    void theDestroyedHookRunsBetweenTheExplosionAndTheCrash(){
        World world = probed(2);
        Probe probe = world.part(Probe.class);
        Unit flare = Unit.spawn(world, VanillaWorlds.VANILLA.unit("flare"), 1, 60f, 60f, 0f);
        Unit dagger = Unit.spawn(world, VanillaWorlds.VANILLA.unit("dagger"), 1, 90f, 60f, 0f);
        flare.destroy();
        assertEquals(List.of("explosion", "destroyed", "crash", "abilities died", "removed"), probe.log, "a flying wreck");
        probe.log.clear();
        dagger.spawnedByCore = true;
        dagger.destroy();
        assertEquals(List.of("destroyed", "abilities died", "removed"), probe.log, "a core unit: no explosion, and it doesn't fly");
    }

    @Test
    void theCombatPartAnswersTheHooks(){
        World world = UnitWorlds.world(16, 16, 3);
        assertInstanceOf(Combat.class, UnitHooks.of(world));
    }

    /** A world with the built-in systems, the probe in the combat part's place. */
    static World probed(long seed){
        var handlers = new ArrayList<PhaseHandler>();
        for(Phase phase : Phase.values()) handlers.addAll(Systems.builtIn().handlers(phase));
        var parts = new ArrayList<>(Systems.builtIn().parts());
        parts.remove(Combat.class);
        return UnitWorlds.world(16, 16, seed, Systems.of(handlers, parts).with(Probe.class, Probe::new));
    }

    /** Logs the hooks and the death damage seams. */
    public static final class Probe implements WorldPart, UnitHooks, UnitDamage{
        final List<String> log = new ArrayList<>();

        @Override
        public void updateAbilities(Unit unit){
            log.add("abilities drag " + unit.drag + " dead " + unit.dead);
        }

        @Override
        public void destroyed(Unit unit){
            log.add("destroyed");
        }

        @Override
        public void abilitiesDied(Unit unit){
            log.add("abilities died");
        }

        @Override
        public void removed(Unit unit){
            log.add("removed");
        }

        @Override
        public void deathExplosion(Unit unit, float flammability, float explosiveness, float power, float radius){
            log.add("explosion");
        }

        @Override
        public void crash(Unit unit){
            log.add("crash");
        }

        @Override
        public WorldPart copy(CopyContext context){
            return new Probe();
        }

        @Override
        public void hash(Hasher hasher){
        }
    }

    record Logging(List<String> log) implements UnitController{
        @Override
        public void updateUnit(Unit unit){
            log.add("controller");
        }

        @Override
        public UnitController copy(CopyContext context){
            return this;
        }

        @Override
        public void hash(Hasher hasher){
        }
    }
}
