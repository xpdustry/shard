package com.xpdustry.shard.sim.waves;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.content.*;
import com.xpdustry.shard.sim.path.*;
import com.xpdustry.shard.sim.rules.*;
import com.xpdustry.shard.sim.unit.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;
import java.util.function.*;

/**
 * Running waves ({@code Logic.runWave}, {@code skipWave}) and vanilla's wave lists ({@code game.Waves}): the default
 * list maps get when theirs is empty, and the generator sectors use, with its seeded random.
 */
public final class Waves{
    /** {@code Waves.waveVersion}. */
    public static final int WAVE_VERSION = 7;

    private Waves(){
    }

    /**
     * {@code Logic.runWave()}: spawns the current wave, moves to the next, restarts the countdown, then
     * {@code WaveEvent} ({@link WaveEvent.Started}). The campaign's wave time multiplier doesn't apply.
     */
    public static void runWave(World world){
        Rules rules = UnitCalls.rules(world);
        WaveState state = WaveState.of(world);
        WaveSpawner spawner = WaveSpawner.of(world);
        if(spawner != null) spawner.spawnEnemies(world);
        if(state == null) return;
        state.wave++;
        state.wavetime = rules.waveSpacing;
        for(WorldPart part : world.parts()){
            // the path layer reads state.wave for randomWaveAI's targets
            if(part instanceof PathService paths && paths.rules().wave() != state.wave) paths.rules(paths.rules().withWave(state.wave));
        }
        world.publish(new WaveEvent.Started(state.wave));
    }

    /** {@code Logic.skipWave()}: the admin's "next wave", which is {@link #runWave}. */
    public static void skipWave(World world){
        runWave(world);
    }

    /** An admin's or the console's wave skip, applied at admission. */
    public record SkipWave() implements Command.Admin{
        @Override
        public Verdict apply(World world, Cause cause){
            skipWave(world);
            return Verdict.ADMITTED;
        }
    }

    // region vanilla's lists

    /**
     * {@code Waves.get()}: the default spawn groups, which {@code SaveVersion.readRules} and {@code Map.rules} give a map
     * whose own list is empty.
     */
    public static List<SpawnGroup> defaults(ContentEpoch content){
        var ids = new Ids(content);
        var out = new ArrayList<SpawnGroup>();
        out.add(group(ids.unit("dagger"), g -> {
            g.end = 10;
            g.unitScaling = 2f;
            g.max = 30;
        }));
        out.add(group(ids.unit("crawler"), g -> {
            g.begin = 4;
            g.end = 13;
            g.unitAmount = 2;
            g.unitScaling = 1.5f;
        }));
        out.add(group(ids.unit("flare"), g -> {
            g.begin = 12;
            g.end = 16;
            g.unitScaling = 1f;
        }));
        out.add(group(ids.unit("dagger"), g -> {
            g.begin = 11;
            g.unitScaling = 1.7f;
            g.spacing = 2;
            g.max = 4;
            g.shieldScaling = 25f;
        }));
        out.add(group(ids.unit("pulsar"), g -> {
            g.begin = 13;
            g.spacing = 3;
            g.unitScaling = 0.5f;
            g.max = 25;
        }));
        out.add(group(ids.unit("mace"), g -> {
            g.begin = 7;
            g.spacing = 3;
            g.unitScaling = 2;
            g.end = 30;
        }));
        out.add(group(ids.unit("dagger"), g -> {
            g.begin = 12;
            g.unitScaling = 1;
            g.unitAmount = 4;
            g.spacing = 2;
            g.shieldScaling = 20f;
            g.max = 14;
        }));
        out.add(group(ids.unit("mace"), g -> {
            g.begin = 28;
            g.spacing = 3;
            g.unitScaling = 1;
            g.end = 40;
            g.shieldScaling = 20f;
        }));
        out.add(group(ids.unit("spiroct"), g -> {
            g.begin = 45;
            g.spacing = 3;
            g.unitScaling = 1;
            g.max = 10;
            g.shieldScaling = 30f;
            g.shields = 100;
            g.effect = ids.status("overdrive");
        }));
        out.add(group(ids.unit("pulsar"), g -> {
            g.begin = 120;
            g.spacing = 2;
            g.unitScaling = 3;
            g.unitAmount = 5;
            g.effect = ids.status("overdrive");
        }));
        out.add(group(ids.unit("flare"), g -> {
            g.begin = 16;
            g.unitScaling = 1;
            g.spacing = 2;
            g.shieldScaling = 20f;
            g.max = 20;
        }));
        out.add(group(ids.unit("quasar"), g -> {
            g.begin = 82;
            g.spacing = 3;
            g.unitAmount = 4;
            g.unitScaling = 3;
            g.shieldScaling = 30f;
            g.effect = ids.status("overdrive");
        }));
        out.add(group(ids.unit("pulsar"), g -> {
            g.begin = 41;
            g.spacing = 5;
            g.unitAmount = 1;
            g.unitScaling = 3;
            g.shields = 640f;
            g.max = 25;
        }));
        out.add(group(ids.unit("fortress"), g -> {
            g.begin = 40;
            g.spacing = 5;
            g.unitAmount = 2;
            g.unitScaling = 2;
            g.max = 20;
            g.shieldScaling = 30;
        }));
        out.add(group(ids.unit("nova"), g -> {
            g.begin = 35;
            g.spacing = 3;
            g.unitAmount = 4;
            g.effect = ids.status("overdrive");
            g.items = new ItemStack(ids.item("blast-compound"), 60);
            g.end = 60;
        }));
        out.add(group(ids.unit("dagger"), g -> {
            g.begin = 42;
            g.spacing = 3;
            g.unitAmount = 4;
            g.effect = ids.status("overdrive");
            g.items = new ItemStack(ids.item("pyratite"), 100);
            g.end = 130;
            g.max = 30;
        }));
        out.add(group(ids.unit("horizon"), g -> {
            g.begin = 40;
            g.unitAmount = 2;
            g.spacing = 2;
            g.unitScaling = 2;
            g.shieldScaling = 20;
        }));
        out.add(group(ids.unit("flare"), g -> {
            g.begin = 50;
            g.unitAmount = 4;
            g.unitScaling = 3;
            g.spacing = 5;
            g.shields = 100f;
            g.shieldScaling = 10f;
            g.effect = ids.status("overdrive");
            g.max = 20;
        }));
        out.add(group(ids.unit("zenith"), g -> {
            g.begin = 50;
            g.unitAmount = 2;
            g.unitScaling = 3;
            g.spacing = 5;
            g.max = 16;
            g.shieldScaling = 30;
        }));
        out.add(group(ids.unit("nova"), g -> {
            g.begin = 53;
            g.unitAmount = 2;
            g.unitScaling = 3;
            g.spacing = 4;
            g.shieldScaling = 30;
        }));
        out.add(group(ids.unit("atrax"), g -> {
            g.begin = 31;
            g.unitAmount = 4;
            g.unitScaling = 1;
            g.spacing = 3;
            g.shieldScaling = 10f;
        }));
        out.add(group(ids.unit("scepter"), g -> {
            g.begin = 41;
            g.unitAmount = 1;
            g.unitScaling = 1;
            g.spacing = 30;
            g.shieldScaling = 30f;
        }));
        out.add(group(ids.unit("reign"), g -> {
            g.begin = 81;
            g.unitAmount = 1;
            g.unitScaling = 1;
            g.spacing = 40;
            g.shieldScaling = 30f;
        }));
        out.add(group(ids.unit("antumbra"), g -> {
            g.begin = 120;
            g.unitAmount = 1;
            g.unitScaling = 1;
            g.spacing = 40;
            g.shieldScaling = 30f;
        }));
        out.add(group(ids.unit("vela"), g -> {
            g.begin = 100;
            g.unitAmount = 1;
            g.unitScaling = 1;
            g.spacing = 30;
            g.shieldScaling = 30f;
        }));
        out.add(group(ids.unit("corvus"), g -> {
            g.begin = 145;
            g.unitAmount = 1;
            g.unitScaling = 1;
            g.spacing = 35;
            g.shieldScaling = 30f;
            g.shields = 100;
        }));
        out.add(group(ids.unit("horizon"), g -> {
            g.begin = 90;
            g.unitAmount = 2;
            g.unitScaling = 3;
            g.spacing = 4;
            g.shields = 40f;
            g.shieldScaling = 30f;
        }));
        out.add(group(ids.unit("toxopid"), g -> {
            g.begin = 210;
            g.unitAmount = 1;
            g.unitScaling = 1;
            g.spacing = 35;
            g.shields = 1000;
            g.shieldScaling = 35f;
        }));
        return out;
    }

    /**
     * {@code Waves.generate(difficulty, rand, attack, airOnly, naval)}: a sector's waves, from progressions of one
     * species tier by tier, boss waves and, for harder attack sectors, a few healing megas. Draws from {@code rand} in
     * vanilla's order.
     */
    public static List<SpawnGroup> generate(ContentEpoch content, float difficulty, Rand rand, boolean attack, boolean airOnly, boolean naval){
        var ids = new Ids(content);
        // array initializers evaluate in order: the 0.5 chance first
        int[][] all = {
            ids.units("dagger", "mace", "fortress", "scepter", "reign"),
            ids.units("nova", "pulsar", "quasar", "vela", "corvus"),
            ids.units("crawler", "atrax", "spiroct", "arkyid", "toxopid"),
            ids.units("risso", "minke", "bryde", "sei", "omura"),
            ids.units("retusa", "oxynoe", "cyerce", "aegires", "navanax"),
            {ids.unit("flare"), ids.unit("horizon"), ids.unit("zenith"), rand.chance(0.5) ? ids.unit("quad") : ids.unit("antumbra"),
                rand.chance(0.1) ? ids.unit("quad") : ids.unit("eclipse")}
        };
        var kept = new ArrayList<int[]>();
        for(int[] row : all){
            UnitDef first = content.unit(row[0]);
            if(airOnly && !first.flying()) continue;
            if(naval ? !(first.flying() || first.naval()) : first.naval()) continue;
            kept.add(row);
        }
        int[][] species = kept.toArray(int[][]::new);

        var out = new ArrayList<SpawnGroup>();
        // max reasonable wave, after which everything gets boring
        int cap = 150;
        float shieldStart = 30, shieldsPerWave = 20 + difficulty * 30f;
        float[] scaling = {1, 2f, 3f, 4f, 5f};

        IntConsumer createProgression = start -> {
            int[] curSpecies = species[rand.random(species.length - 1)];
            int curTier = 0;

            for(int i = start; i < cap;){
                int f = i;
                int next = rand.random(8, 16) + (int)Mathf.lerp(5f, 0f, difficulty) + curTier * 4;
                float shieldAmount = Math.max((i - shieldStart) * shieldsPerWave, 0);
                int space = start == 0 ? 1 : rand.random(1, 2);
                int ctier = curTier;
                int type = curSpecies[Math.min(curTier, curSpecies.length - 1)];

                // main progression
                out.add(group(type, g -> {
                    g.unitAmount = f == start ? 1 : 6 / (int)scaling[ctier];
                    g.begin = f;
                    g.end = f + next >= cap ? SpawnGroup.NEVER : f + next;
                    g.max = 13;
                    g.unitScaling = (difficulty < 0.4f ? rand.random(2.5f, 5f) : rand.random(1f, 4f)) * scaling[ctier];
                    g.shields = shieldAmount;
                    g.shieldScaling = shieldsPerWave;
                    g.spacing = space;
                }));

                // extra progression that tails out, blends in
                out.add(group(type, g -> {
                    g.unitAmount = 3 / (int)scaling[ctier];
                    g.begin = f + next - 1;
                    g.end = f + next + rand.random(6, 10);
                    g.max = 6;
                    g.unitScaling = rand.random(2f, 4f);
                    g.spacing = rand.random(2, 4);
                    g.shields = shieldAmount / 2f;
                    g.shieldScaling = shieldsPerWave;
                }));

                i += next + 1;
                if(curTier < 3 || (rand.chance(0.05) && difficulty > 0.8)) curTier++;
                // do not spawn bosses
                curTier = Math.min(curTier, 3);
                // small chance to switch species
                if(rand.chance(0.3)) curSpecies = species[rand.random(species.length - 1)];
            }
        };

        createProgression.accept(0);

        int step = 5 + rand.random(5);
        while(step <= cap){
            createProgression.accept(step);
            step += (int)(rand.random(15, 30) * Mathf.lerp(1f, 0.5f, difficulty));
        }

        int bossWave = (int)(rand.random(50, 70) * Mathf.lerp(1f, 0.5f, difficulty));
        int bossSpacing = (int)(rand.random(25, 40) * Mathf.lerp(1f, 0.5f, difficulty));
        int bossTier = difficulty < 0.6 ? 3 : 4;
        int boss = ids.status("boss");

        // main boss progression
        out.add(group(species[rand.random(species.length - 1)][bossTier], g -> {
            g.unitAmount = 1;
            g.begin = bossWave;
            g.spacing = bossSpacing;
            g.end = SpawnGroup.NEVER;
            g.max = 16;
            g.unitScaling = bossSpacing;
            g.shieldScaling = shieldsPerWave;
            g.effect = boss;
        }));

        // alt boss progression
        out.add(group(species[rand.random(species.length - 1)][bossTier], g -> {
            g.unitAmount = 1;
            g.begin = bossWave + rand.random(3, 5) * bossSpacing;
            g.spacing = bossSpacing;
            g.end = SpawnGroup.NEVER;
            g.max = 16;
            g.unitScaling = bossSpacing;
            g.shieldScaling = shieldsPerWave;
            g.effect = boss;
        }));

        int finalBossStart = 120 + rand.random(30);

        // final boss waves, and their alternate
        for(int offset : new int[]{0, 15}){
            out.add(group(species[rand.random(species.length - 1)][bossTier], g -> {
                g.unitAmount = 1;
                g.begin = finalBossStart + offset;
                g.spacing = bossSpacing / 2;
                g.end = SpawnGroup.NEVER;
                g.unitScaling = bossSpacing;
                g.shields = 500;
                g.shieldScaling = shieldsPerWave * 4;
                g.effect = boss;
            }));
        }

        // add megas to heal the base
        if(attack && difficulty >= 0.5){
            int amount = rand.random(1, 3 + (int)(difficulty * 2));
            for(int i = 0; i < amount; i++){
                int wave = rand.random(3, 20);
                out.add(group(ids.unit("mega"), g -> {
                    g.unitAmount = 1;
                    g.begin = wave;
                    g.end = wave;
                    g.max = 16;
                }));
            }
        }

        // shift back waves on higher difficulty for a harder start
        int shift = Math.max((int)(difficulty * 14 - 5), 0);
        for(SpawnGroup group : out){
            group.begin -= shift;
            group.end -= shift;
        }
        return out;
    }

    private static SpawnGroup group(int type, Consumer<SpawnGroup> init){
        var group = new SpawnGroup(type);
        init.accept(group);
        return group;
    }

    /** Content ids by name; a missing name is an error, as vanilla's static fields would be null. */
    private record Ids(ContentEpoch content){
        int unit(String name){
            return id(ContentType.UNIT, name);
        }

        int[] units(String... names){
            int[] out = new int[names.length];
            for(int i = 0; i < names.length; i++) out[i] = unit(names[i]);
            return out;
        }

        int status(String name){
            return id(ContentType.STATUS, name);
        }

        int item(String name){
            return id(ContentType.ITEM, name);
        }

        private int id(ContentType type, String name){
            int id = content.id(type, name);
            if(id == -1) throw new IllegalArgumentException("content " + content.hash() + " has no " + type.yamlName() + " " + name);
            return id;
        }
    }

    // endregion
}
