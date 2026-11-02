package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.rules.Rules;
import com.xpdustry.shard.sim.rules.WorldRules;
import com.xpdustry.shard.sim.tile.VanillaWorlds;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.sim.world.Observer;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.world.blocks.environment.*;
import mindustry.world.meta.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate: the lifecycle {@code Call}s a vanilla server sends ({@code unitDeath}, {@code unitDestroy}, {@code unitDespawn},
 * {@code unitEnvDeath}, {@code unitCapDeath}, {@code unitSafeDeath}), captured from its net in server mode, are the
 * {@link UnitEvent}s Shard publishes, in the same order and the same frames. The script covers kills (ground, and a
 * flying wreck that falls), lethal damage, a second kill of a dead unit, a destroy of a removed unit, a core unit's
 * despawn, a safe death, units buried by a wall in update order, drowning, environment deaths and their posted
 * destroys, and a cap death and its posted destroy.
 */
class UnitCallOrderOracleTest{
    static final int SIZE = 48, FRAMES = 400;

    final List<mindustry.gen.Unit> vanilla = new ArrayList<>();
    final List<Unit> shard = new ArrayList<>();
    World world;

    @Test
    void lifecycleCallsArePublishedInVanillasOrder(){
        Vanilla.emptyWorld(SIZE, SIZE);
        state.rules.disableUnitCap = true;
        state.rules.waves = false;
        world = UnitWorlds.world(SIZE, SIZE, 7);
        Rules rules = world.part(WorldRules.class).rules;
        rules.waves = false;
        Observer observer = world.observe(4096, List.of(UnitEvent.class), null);
        for(int tx = 40; tx < 46; tx++) for(int ty = 40; ty < 46; ty++) deepWater(tx, ty);
        int vanillaEnv = state.rules.env, shardEnv = rules.env;

        Vanilla.net(Vanilla.NetMode.server, () -> {
            Vanilla.captured().clear();
            int calls = 0;
            for(int frame = 0; frame < FRAMES; frame++){
                switch(frame){
                    case 0 -> {
                        spawn("dagger", 4, 4, false); // 0: killed
                        spawn("flare", 8, 4, false); // 1: killed, falls, destroyed on landing
                        spawn("dagger", 12, 4, false); // 2: lethal damage
                        spawn("dagger", 16, 4, true); // 3: a core unit nobody controls, despawned
                        spawn("dagger", 20, 4, false); // 4: safe death
                        spawn("dagger", 24, 4, false); // 5: destroyed
                        spawn("dagger", 4, 20, false); // 6, 7, 8: buried together
                        spawn("dagger", 4, 21, false);
                        spawn("dagger", 4, 22, false);
                        spawn("dagger", 43, 43, false); // 9: drowns
                    }
                    case 2 -> {
                        vanilla.get(0).kill();
                        shard.get(0).kill();
                        vanilla.get(1).kill();
                        shard.get(1).kill();
                    }
                    case 3 -> {
                        vanilla.get(2).damage(1000f);
                        shard.get(2).damage(1000f);
                        vanilla.get(1).kill(); // already dead: no call
                        shard.get(1).kill();
                    }
                    case 4 -> {
                        Call.unitSafeDeath(vanilla.get(4));
                        shard.get(4).safeDeath();
                        Call.unitDestroy(vanilla.get(5).id);
                        UnitCalls.destroy(shard.get(5));
                        Call.unitDestroy(vanilla.get(0).id); // removed: the packet still goes out
                        UnitCalls.destroy(shard.get(0));
                    }
                    case 6 -> {
                        for(int ty = 20; ty <= 22; ty++){
                            mindustry.Vars.world.tile(4, ty).setBlock(content.block("stone-wall"), Team.derelict, 0);
                            world.buildings().setBlock(4, ty, VanillaWorlds.VANILLA.block("stone-wall"), 0, 0, Cause.SERVER);
                        }
                    }
                    case 300 -> {
                        spawn("dagger", 30, 10, false); // 10, 12: environment deaths
                        spawn("flare", 34, 10, false); // 11: flying types live in space
                        spawn("dagger", 38, 10, false);
                    }
                    case 302 -> {
                        state.rules.env = Env.space;
                        rules.env = Env.space;
                    }
                    case 304 -> {
                        state.rules.env = vanillaEnv;
                        rules.env = shardEnv;
                    }
                    case 360 -> {
                        state.rules.disableUnitCap = false;
                        state.rules.unitCapVariable = false;
                        state.rules.unitCap = 1;
                        UnitWorlds.cap(world, 1);
                        spawn("dagger", 30, 20, false); // 13: within the cap
                        spawn("dagger", 34, 20, false); // 14: over it, cap death
                        spawn("dagger", 38, 20, true); // 15: a core unit, exempt, despawned
                    }
                    default -> {}
                }
                Vanilla.frame();
                world.tick();

                var expected = new ArrayList<String>();
                List<Vanilla.Capture.Sent> sent = Vanilla.captured();
                for(; calls < sent.size(); calls++){
                    String call = call(sent.get(calls).packet());
                    if(call != null) expected.add(call);
                }
                var actual = new ArrayList<String>();
                for(WorldEvent event : observer.drain()) actual.add(call((UnitEvent)event));
                assertEquals(expected, actual, "frame " + frame);
            }
        });

        assertEquals(0, observer.dropped());
        // every scripted death happened, in both
        for(int i : new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 14, 15}){
            assertFalse(vanilla.get(i).isAdded(), "vanilla unit " + i + " is gone");
            assertFalse(shard.get(i).isAdded(), "unit " + i + " is gone");
        }
        for(int i : new int[]{11, 13}){
            assertTrue(vanilla.get(i).isAdded(), "vanilla unit " + i + " lives");
            assertTrue(shard.get(i).isAdded(), "unit " + i + " lives");
        }
    }

    void spawn(String type, int tx, int ty, boolean byCore){
        float x = tx * 8f, y = ty * 8f;
        mindustry.gen.Unit v = content.unit(type).create(Team.sharded);
        v.spawnedByCore = byCore;
        v.set(x, y);
        vanilla.add(v);
        v.add();
        Unit s = Unit.create(world, VanillaWorlds.VANILLA.unit(type), Team.sharded.id);
        s.spawnedByCore = byCore;
        s.set(x, y);
        shard.add(s);
        s.add();
    }

    void deepWater(int tx, int ty){
        mindustry.Vars.world.tile(tx, ty).setFloor((Floor)content.block("deep-water"));
        world.buildings().setFloor(tx, ty, VanillaWorlds.VANILLA.block("deep-water"), Cause.SERVER);
    }

    /** {@code "Death 3"}: the call and the index of its unit in the script, or null for other packets. */
    String call(Object packet){
        return switch(packet){
            case UnitDeathCallPacket p -> "Death " + vanillaIndex(p.uid);
            case UnitDestroyCallPacket p -> "Destroy " + vanillaIndex(p.uid);
            case UnitDespawnCallPacket p -> "Despawn " + vanillaIndex(p.unit.id);
            case UnitEnvDeathCallPacket p -> "EnvDeath " + vanillaIndex(p.unit.id);
            case UnitCapDeathCallPacket p -> "CapDeath " + vanillaIndex(p.unit.id);
            case UnitSafeDeathCallPacket p -> "SafeDeath " + vanillaIndex(p.unit.id);
            default -> null;
        };
    }

    String call(UnitEvent event){
        int index = -1;
        for(int i = 0; i < shard.size(); i++) if(shard.get(i).id() == event.unit()) index = i;
        return event.getClass().getSimpleName() + " " + index;
    }

    int vanillaIndex(int id){
        for(int i = 0; i < vanilla.size(); i++) if(vanilla.get(i).id == id) return i;
        return -1;
    }
}
