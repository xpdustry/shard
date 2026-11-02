package com.xpdustry.shard.sim.waves;

import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.rules.WorldRules;
import com.xpdustry.shard.sim.tile.*;
import com.xpdustry.shard.sim.unit.Unit;
import com.xpdustry.shard.sim.world.*;
import com.xpdustry.shard.sim.world.Observer;
import arc.math.*;
import arc.struct.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;

import java.util.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP47): waves 1 to 100 on five bundled maps, one in attack mode, and a map with spawn groups that use every
 * {@code SpawnGroup} field. After each {@code runWave}, the units vanilla and Shard spawned are the same, in the same
 * order: entity id, type, team, position, rotation, shield, health, item stack, statuses and their times, payloads. The
 * {@code Call.spawnEffect}s vanilla sends are Shard's {@link WaveEvent.UnitSpawned}s, the random is in the same state,
 * and the next entity id is the same. Units are removed between waves, so every wave starts from an empty map.
 */
class WaveSpawnOracleTest{
    static final int WAVES = 100;

    @ParameterizedTest
    @CsvSource({"fortress, survival", "maze, survival", "islands, survival", "shattered, survival", "veins, attack"})
    void wavesOneToAHundredSpawnAsVanilla(String map, String mode){
        Vanilla.host(map, Gamemode.valueOf(mode));
        state.rules.waves = true;
        World world = VanillaWaves.world();
        assertTrue(state.rules.spawns.size > 0, map + " has spawn groups");
        if(mode.equals("attack")){
            assertTrue(spawner.countGroundSpawns() > spawner.countSpawns(), map + " spawns ground units at wave team cores");
            assertTrue(spawner.countFlyerSpawns() > spawner.countSpawns(), map + " spawns flyers at wave team cores");
        }
        Seen seen = runWaves(map, world, WAVES);
        assertTrue(seen.units() > 500 && seen.flying() > 0 && seen.shielded() > 0, map + " spawned ground, air and shielded units: " + seen);
    }

    /**
     * The first bundled map with several spawns, with groups that use payloads, items, a team, a spawn filter, an
     * effect, shields and spacing 0.
     */
    @Test
    void everySpawnGroupFieldSpawnsAsVanilla(){
        String map = null;
        for(String candidate : List.of("fortress", "maze", "islands", "shattered", "tendrils", "wasteland", "caldera", "fork")){
            Vanilla.host(candidate);
            if(spawner.getSpawns().size > 1){
                map = candidate;
                break;
            }
        }
        assertNotNull(map, "a bundled map has several spawns");
        state.rules.waves = true;
        var groups = new Seq<SpawnGroup>();
        groups.add(new SpawnGroup(content.unit("mega")){{
            payloads = Seq.with(content.unit("dagger"), content.unit("flare"));
            items = new ItemStack(content.item("copper"), 7);
            spawn = spawner.getSpawns().first().pos();
            unitScaling = 3;
        }});
        groups.add(new SpawnGroup(content.unit("quad")){{
            payloads = Seq.with(content.unit("mono"));
            begin = 2;
            spacing = 3;
        }});
        groups.add(new SpawnGroup(content.unit("dagger")){{
            effect = content.statusEffect("boss");
            shields = 40;
            shieldScaling = 12.5f;
            team = Team.blue;
            unitAmount = 2;
            unitScaling = 2.5f;
            max = 5;
        }});
        groups.add(new SpawnGroup(content.unit("crawler")){{
            spacing = 0;
            unitAmount = 3;
            items = new ItemStack(content.item("blast-compound"), 500);
        }});
        groups.add(new SpawnGroup(content.unit("atrax")){{
            begin = 1;
            end = 8;
            effect = content.statusEffect("overdrive");
        }});
        groups.add(new SpawnGroup(content.unit("flare")){{
            spawn = spawner.getSpawns().peek().pos();
            unitAmount = 2;
        }});
        state.rules.spawns = groups;
        World world = VanillaWaves.world();
        Seen seen = runWaves(map + " (custom groups)", world, 20);
        assertTrue(seen.carriers() > 0 && seen.types().containsAll(List.of("mega", "quad", "dagger", "crawler", "atrax", "flare")),
            "every custom group spawned: " + seen);
        state.rules.airUseSpawns = true;
        world.part(WorldRules.class).rules.airUseSpawns = true;
        runWaves(map + " (custom groups, air at spawns)", world, 10);
    }

    /** Units spawned, flying, carrying payloads, shielded, and the types seen, across a scenario. */
    record Seen(int units, int flying, int carriers, int shielded, Set<String> types){
    }

    Seen runWaves(String name, World world, int waves){
        WaveSpawner shardSpawner = WaveSpawner.of(world);
        assertEquals(spawner.countSpawns(), shardSpawner.countSpawns(world), name + ": spawn tiles");
        assertEquals(spawner.countGroundSpawns(), shardSpawner.countGroundSpawns(world), name + ": ground spawn points");
        assertEquals(spawner.countFlyerSpawns(), shardSpawner.countFlyerSpawns(world), name + ": flyer spawn points");
        var first = spawner.getFirstSpawn();
        assertEquals(first == null ? -1 : world.tiles().pos(first.x, first.y), shardSpawner.firstSpawn(world), name + ": first spawn");
        Observer observer = world.observe(1 << 16, List.of(WaveEvent.class), null);
        int units = 0, flying = 0, carriers = 0, shielded = 0;
        var types = new TreeSet<String>();
        for(int wave = 0; wave < waves; wave++){
            String at = name + " wave " + state.wave;
            List<SpawnEffectCallPacket> calls = new ArrayList<>();
            Vanilla.net(Vanilla.NetMode.server, () -> {
                Vanilla.captured().clear();
                logic.runWave();
                for(var sent : Vanilla.captured()){
                    if(sent.packet() instanceof SpawnEffectCallPacket p) calls.add(p);
                }
            });
            Waves.runWave(world);
            Delivery.commit(observer);

            var expected = new ArrayList<String>();
            for(mindustry.gen.Unit unit : Groups.unit) expected.add(VanillaWaves.describe(unit));
            var actual = new ArrayList<String>();
            for(int i = 0; i < world.units().size(); i++) actual.add(VanillaWaves.describe(world.units().get(i)));
            assertEquals(expected, actual, at + ": spawned units");

            var expectedCalls = new ArrayList<String>();
            for(var call : calls) expectedCalls.add(call.x + "," + call.y + " " + call.rotation + " " + call.u.id);
            var actualCalls = new ArrayList<String>();
            var started = new ArrayList<Integer>();
            for(WorldEvent event : observer.drain()){
                switch(event){
                    case WaveEvent.UnitSpawned e -> actualCalls.add(e.x() + "," + e.y() + " " + e.rotation() + " " + e.type());
                    case WaveEvent.Started e -> started.add(e.wave());
                    default -> fail("unexpected " + event);
                }
            }
            assertEquals(expectedCalls, actualCalls, at + ": spawnEffect calls");
            assertEquals(List.of(state.wave), started, at + ": one WaveEvent");

            WaveState waveState = WaveState.of(world);
            assertEquals(state.wave, waveState.wave, at + ": wave");
            assertEquals(state.wavetime, waveState.wavetime, at + ": wavetime");
            assertEquals(Mathf.rand.seed0, world.rand().getState(0), at + ": random");
            assertEquals(Mathf.rand.seed1, world.rand().getState(1), at + ": random");
            assertEquals(VanillaWaves.lastId(), world.ids().peek(), at + ": next entity id");

            for(mindustry.gen.Unit unit : Groups.unit){
                units++;
                if(unit.isFlying()) flying++;
                if(unit instanceof Payloadc carrier && carrier.hasPayload()) carriers++;
                if(unit.shield > 0) shielded++;
                types.add(unit.type.name);
            }
            for(mindustry.gen.Unit unit : Groups.unit.copy(new Seq<>())) unit.remove();
            for(Unit unit : world.units().toList()) unit.remove();
        }
        assertEquals(0, observer.dropped());
        var seen = new Seen(units, flying, carriers, shielded, types);
        System.out.println(name + ": " + seen);
        return seen;
    }

    /** {@code Waves.get()}: the default groups equal vanilla's, field by field. */
    @Test
    void theDefaultWavesAreVanillas(){
        Vanilla.reset();
        List<String> expected = waves.get().map(VanillaWaves::group).map(WaveSpawnOracleTest::fields).list();
        List<String> actual = Waves.defaults(VanillaWorlds.VANILLA).stream().map(WaveSpawnOracleTest::fields).toList();
        assertEquals(expected, actual);
    }

    /** {@code Waves.generate(difficulty, rand, attack, airOnly, naval)}: the same groups and draws for 200 settings. */
    @Test
    void generatedWavesAreVanillas(){
        Vanilla.reset();
        var settings = new Random(47);
        for(int i = 0; i < 200; i++){
            float difficulty = i < 10 ? i / 9f : settings.nextFloat() * 1.2f;
            long seed = settings.nextLong();
            boolean attack = settings.nextBoolean(), airOnly = i % 7 == 3, naval = i % 5 == 1;
            var vanillaRand = new arc.math.Rand(seed);
            var shardRand = new com.xpdustry.shard.common.math.Rand(seed);
            List<String> expected = Waves_generate(difficulty, vanillaRand, attack, airOnly, naval);
            List<String> actual = Waves.generate(VanillaWorlds.VANILLA, difficulty, shardRand, attack, airOnly, naval).stream()
                .map(WaveSpawnOracleTest::fields).toList();
            String at = "difficulty " + difficulty + " seed " + seed + " attack " + attack + " air " + airOnly + " naval " + naval;
            assertEquals(expected, actual, at);
            assertEquals(vanillaRand.seed0, shardRand.getState(0), at + ": random");
            assertEquals(vanillaRand.seed1, shardRand.getState(1), at + ": random");
        }
    }

    static List<String> Waves_generate(float difficulty, arc.math.Rand rand, boolean attack, boolean airOnly, boolean naval){
        return mindustry.game.Waves.generate(difficulty, rand, attack, airOnly, naval).map(VanillaWaves::group).map(WaveSpawnOracleTest::fields).list();
    }

    static String fields(com.xpdustry.shard.sim.rules.SpawnGroup g){
        return "type " + g.type + " begin " + g.begin + " end " + g.end + " spacing " + g.spacing + " max " + g.max
            + " scaling " + VanillaWaves.bits(g.unitScaling) + " shields " + VanillaWaves.bits(g.shields) + " shieldScaling "
            + VanillaWaves.bits(g.shieldScaling) + " amount " + g.unitAmount + " spawn " + g.spawn + " payloads " + g.payloads
            + " effect " + g.effect + " items " + g.items + " team " + g.team;
    }
}
