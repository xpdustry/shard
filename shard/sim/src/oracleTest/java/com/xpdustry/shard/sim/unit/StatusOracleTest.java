package com.xpdustry.shard.sim.unit;

import arc.math.*;
import arc.struct.*;
import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.rules.WorldRules;
import com.xpdustry.shard.sim.tile.VanillaWorlds;
import com.xpdustry.shard.sim.world.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.type.*;
import mindustry.world.blocks.environment.*;
import org.junit.jupiter.api.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP42): status application, transitions and the {@code StatusComp} update against vanilla, frame by frame for
 * 400 frames. Scripted: plain applications and extensions, opposites that weaken and then replace an entry, the six
 * affinity reactions (with their damage and their effect's random draws), interval damage, permanent effects,
 * {@code setDuration} shortening and removing, {@code unapply}, {@code clearStatuses}, an immune type, the floor's
 * status on shallow water (and none for a flyer above it), invincibility, and dynamic statuses. Each frame every unit's
 * entries (effect, time, interval time) in order, its multipliers, armor override, disarm, {@code hasEffect} bits,
 * health and shield equal vanilla's, and so does the random.
 */
class StatusOracleTest{
    static final int SIZE = 64, FRAMES = 400;

    final List<mindustry.gen.Unit> vanilla = new ArrayList<>();
    final List<Unit> shard = new ArrayList<>();
    World world;

    @Test
    void statusesApplyTransitionAndTickAsVanilla(){
        Vanilla.emptyWorld(SIZE, SIZE);
        state.rules.disableUnitCap = true;
        world = UnitWorlds.world(SIZE, SIZE, 3);
        world.part(WorldRules.class).rules.disableUnitCap = true;
        for(int tx = 50; tx < 60; tx++) for(int ty = 50; ty < 60; ty++) floor(tx, ty, "shallow-water");

        UnitType immune = content.units().find(t -> t.immunities.contains(StatusEffects.burning) && !t.flying && !t.internal);
        assertNotNull(immune, "a ground type is immune to burning");
        String[] types = {"fortress", "fortress", "fortress", "fortress", "fortress", "fortress", "fortress", "fortress",
            immune.name, "dagger", "flare", "fortress", "fortress"};
        for(int i = 0; i < types.length; i++){
            boolean wet = i == 9 || i == 10;
            spawn(types[i], wet ? 52 + (i - 9) * 4 : 4 + (i % 6) * 7, wet ? 54 : 4 + (i / 6) * 7);
        }
        VanillaWaves.sync(world);

        for(int frame = 0; frame < FRAMES; frame++){
            switch(frame){
                case 5 -> {
                    apply(0, "burning", 300f);
                    apply(1, "wet", 200f);
                    apply(5, "overdrive", 999999f);
                    apply(5, "boss", 999999f);
                    apply(6, "corroded", 120f);
                    apply(8, "burning", 300f);
                }
                case 10 -> {
                    apply(0, "burning", 120f); // shorter: no change
                    apply(0, "burning", 420f); // longer: extended
                    apply(0, "tarred", 100f); // burning meets tarred
                }
                case 12 -> apply(2, "tarred", 200f);
                case 14 -> apply(2, "burning", 50f); // tarred meets burning
                case 20 -> apply(1, "burning", 150f); // wet loses 75
                case 22 -> apply(1, "burning", 400f); // wet runs out: becomes burning
                case 25 -> apply(3, "wet", 300f);
                case 26 -> apply(3, "shocked", 10f); // wet meets shocked; shocked is reactive, never kept
                case 27 -> apply(3, "shocked", 10f);
                case 30 -> apply(4, "freezing", 300f);
                case 31 -> apply(4, "blasted", 1f); // freezing meets blasted
                case 32 -> apply(4, "burning", 100f); // freezing loses 50
                case 40 -> {
                    apply(7, "tarred", 50f);
                    apply(7, "melting", 30f); // tarred meets melting
                }
                case 45 -> {
                    run(0, (v, s) -> v.setDuration(StatusEffects.burning, 0f), (u, s) -> u.statuses.setDuration(u, s.id("burning"), 0f));
                    run(5, (v, s) -> v.setDuration(StatusEffects.overdrive, 30f), (u, s) -> u.statuses.setDuration(u, s.id("overdrive"), 30f));
                }
                case 46 -> run(5, (v, s) -> v.setDuration(StatusEffects.fast, 0f), (u, s) -> u.statuses.setDuration(u, s.id("fast"), 0f));
                case 50 -> run(2, (v, s) -> v.unapply(StatusEffects.burning), (u, s) -> u.statuses.unapply(s.id("burning")));
                case 55 -> {
                    apply(0, "melting", 100f);
                    apply(0, "tarred", 60f); // melting meets tarred
                }
                case 60 -> {
                    apply(11, "slow", 200f);
                    apply(11, "fast", 300f); // slow loses 150
                    apply(11, "fast", 300f); // slow runs out: becomes fast
                }
                case 70 -> run(1, (v, s) -> v.clearStatuses(), (u, s) -> u.statuses.clear());
                case 80 -> {
                    apply(3, "none", 100f);
                    apply(3, "invincible", 60f);
                    apply(12, "disarmed", 90f);
                    apply(12, "electrified", 90f);
                    apply(12, "sapped", 90f);
                    apply(12, "spore-slowed", 90f);
                    apply(12, "muddy", 90f);
                    apply(12, "overclock", 90f);
                    apply(12, "shielded", 90f);
                    apply(12, "unmoving", 90f);
                    apply(12, "melting", 90f);
                }
                case 81 -> run(3, (v, s) -> v.damage(500f), (u, s) -> u.damage(500f));
                case 90 -> {
                    run(11, (v, s) -> v.statusSpeed(2f), (u, s) -> u.statuses.statusSpeed(u, 2f));
                    run(11, (v, s) -> v.statusArmor(5f), (u, s) -> u.statuses.statusArmor(u, 5f));
                    run(11, (v, s) -> v.statusDamageMultiplier(1.5f), (u, s) -> u.statuses.statusDamageMultiplier(u, 1.5f));
                    run(11, (v, s) -> v.statusReloadMultiplier(0.5f), (u, s) -> u.statuses.statusReloadMultiplier(u, 0.5f));
                    run(11, (v, s) -> v.statusMaxHealth(2000f), (u, s) -> u.statuses.statusMaxHealth(u, 2000f));
                    run(11, (v, s) -> v.statusDrag(0.2f), (u, s) -> u.statuses.statusDrag(u, 0.2f));
                    run(11, (v, s) -> v.statusBuildSpeed(3f), (u, s) -> u.statuses.statusBuildSpeed(u, 3f));
                }
                case 95 -> run(11, (v, s) -> v.damage(100f), (u, s) -> u.damage(100f));
                default -> {}
            }
            Vanilla.frame();
            world.tick();
            for(int i = 0; i < vanilla.size(); i++){
                assertEquals(describe(vanilla.get(i)), describe(shard.get(i)), "frame " + frame + " unit " + i + " (" + types[i] + ")");
            }
            assertEquals(Mathf.rand.seed0, world.rand().getState(0), "frame " + frame + ": random");
            assertEquals(Mathf.rand.seed1, world.rand().getState(1), "frame " + frame + ": random");
        }
        // the script reached every case it meant to
        assertFalse(vanilla.get(8).hasEffect(StatusEffects.burning), "the immune type never burned");
        assertTrue(vanilla.get(9).hasEffect(StatusEffects.wet), "the ground unit on water is wet");
        assertFalse(vanilla.get(10).hasEffect(StatusEffects.wet), "the flyer over water is dry");
        assertTrue(vanilla.get(5).isAdded() && vanilla.get(6).isAdded(), "the scripted units live");
    }

    void spawn(String type, int tx, int ty){
        float x = tx * 8f, y = ty * 8f;
        mindustry.gen.Unit v = content.unit(type).create(Team.sharded);
        v.set(x, y);
        vanilla.add(v);
        v.add();
        Unit s = Unit.create(world, VanillaWorlds.VANILLA.unit(type), Team.sharded.id);
        s.set(x, y);
        shard.add(s);
        s.add();
    }

    void floor(int tx, int ty, String name){
        mindustry.Vars.world.tile(tx, ty).setFloor((Floor)content.block(name));
        world.buildings().setFloor(tx, ty, VanillaWorlds.VANILLA.block(name), Cause.SERVER);
    }

    void apply(int unit, String effect, float duration){
        run(unit, (v, s) -> v.apply(content.statusEffect(effect), duration), (u, s) -> u.statuses.apply(u, s.id(effect), duration));
    }

    void run(int unit, BiConsumer<mindustry.gen.Unit, StatusTable> vanillaAction, BiConsumer<Unit, StatusTable> shardAction){
        vanillaAction.accept(vanilla.get(unit), null);
        shardAction.accept(shard.get(unit), StatusTable.of(world));
    }

    static String describe(mindustry.gen.Unit unit){
        var out = new StringBuilder("[");
        for(mindustry.entities.units.StatusEntry entry : statuses(unit)){
            out.append(entry.effect == null ? -1 : entry.effect.id).append(':').append(bits(entry.time)).append('/').append(bits(entry.damageTime)).append(' ');
        }
        out.append("] speed ").append(bits(unit.speedMultiplier())).append(" damage ").append(bits(unit.damageMultiplier()))
            .append(" health ").append(bits(unit.healthMultiplier())).append(" reload ").append(bits(unit.reloadMultiplier()))
            .append(" build ").append(bits(unit.buildSpeedMultiplier())).append(" drag ").append(bits(unit.dragMultiplier()))
            .append(" armor ").append(bits(unit.armorOverride())).append(" disarmed ").append(unit.disarmed()).append(" has [");
        for(var effect : content.statusEffects()) if(unit.hasEffect(effect)) out.append(effect.id).append(' ');
        out.append("] hp ").append(bits(unit.health)).append(" shield ").append(bits(unit.shield)).append(" added ").append(unit.isAdded());
        return out.toString();
    }

    static String describe(Unit unit){
        var out = new StringBuilder("[");
        Statuses statuses = unit.statuses;
        for(int i = 0; i < statuses.size(); i++){
            StatusEntry entry = statuses.get(i);
            out.append(entry.effect).append(':').append(bits(entry.time)).append('/').append(bits(entry.damageTime)).append(' ');
        }
        out.append("] speed ").append(bits(statuses.speedMultiplier)).append(" damage ").append(bits(statuses.damageMultiplier))
            .append(" health ").append(bits(statuses.healthMultiplier)).append(" reload ").append(bits(statuses.reloadMultiplier))
            .append(" build ").append(bits(statuses.buildSpeedMultiplier)).append(" drag ").append(bits(statuses.dragMultiplier))
            .append(" armor ").append(bits(statuses.armorOverride)).append(" disarmed ").append(statuses.disarmed).append(" has [");
        for(int id = 0; id < StatusTable.of(unit.world()).size(); id++) if(statuses.hasEffect(id)) out.append(id).append(' ');
        out.append("] hp ").append(bits(unit.health)).append(" shield ").append(bits(unit.shield)).append(" added ").append(unit.isAdded());
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    static Seq<mindustry.entities.units.StatusEntry> statuses(mindustry.gen.Unit unit){
        for(Class<?> type = unit.getClass(); type != null; type = type.getSuperclass()){
            try{
                Field field = type.getDeclaredField("statuses");
                field.setAccessible(true);
                return (Seq<mindustry.entities.units.StatusEntry>)field.get(unit);
            }catch(NoSuchFieldException ignored){
            }catch(IllegalAccessException e){
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException(unit + " has no statuses");
    }

    static String bits(float value){
        return value + "(" + Integer.toHexString(Float.floatToRawIntBits(value)) + ")";
    }

    /** {@code sim.waves}' test helper, for the random. */
    static final class VanillaWaves{
        static void sync(World world){
            world.rand().setState(Mathf.rand.seed0, Mathf.rand.seed1);
        }
    }
}
