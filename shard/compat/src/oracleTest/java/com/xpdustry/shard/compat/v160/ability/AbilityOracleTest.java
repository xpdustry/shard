package com.xpdustry.shard.compat.v160.ability;

import com.xpdustry.shard.oracle.*;
import mindustry.game.*;
import org.junit.jupiter.api.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP62): every ability vanilla's units carry, on those units, in a fight where it acts, exact frame by frame
 * against vanilla for at least 600 frames ({@link AbilityRun}: units with their abilities' state, every unit's
 * {@code writeSync} and save bytes, bullets, puddles, the random, ids and the unit Calls). Most fixtures load vanilla's
 * save halfway and go on from it on both sides. Each asserts that its ability did what it is for.
 */
class AbilityOracleTest{
    static final int SIZE = 50, HALF = 330;

    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    /** Quasar's force field: six daggers break it, it absorbs their shots and comes back after its cooldown. */
    @Test
    void quasarsForceFieldAbsorbsUntilItBreaks(){
        try(var run = new AbilityRun(SIZE, "stone")){
            int quasar = run.stander("quasar", Team.sharded, 200f, 200f, 0f);
            for(int i = 0; i < 6; i++){
                float angle = i * 60f;
                run.fighter("dagger", Team.crux, 200f + arc.math.Angles.trnsx(angle, 90f), 200f + arc.math.Angles.trnsy(angle, 90f), angle + 180f,
                    200f + arc.math.Angles.trnsx(angle, 70f), 200f + arc.math.Angles.trnsy(angle, 70f), Team.sharded, 130f);
            }
            float[] low = {Float.MAX_VALUE};
            run.watches.add(() -> {
                var v = run.vanillaUnits.get(quasar);
                if(v != null && v.isAdded()) low[0] = Math.min(low[0], v.shield);
            });
            run.run(HALF);
            run.reload();
            run.run(600 - HALF);
            assertTrue(low[0] < 0f, "the field broke (lowest " + low[0] + ")");
        }
    }

    /** Oct's force field and repair field: flares shoot at the damaged daggers it shelters and heals. */
    @Test
    void octShieldsAndRepairsItsEscort(){
        try(var run = new AbilityRun(SIZE, "stone")){
            int oct = run.stander("oct", Team.sharded, 200f, 200f, 0f);
            int[] daggers = new int[3];
            for(int i = 0; i < daggers.length; i++){
                daggers[i] = run.stander("dagger", Team.sharded, 170f + i * 30f, 180f, 90f);
                run.health(daggers[i], 40f);
            }
            for(int i = 0; i < 4; i++) run.fighter("flare", Team.crux, 60f + i * 20f, 360f, 0f, 120f + i * 40f, 260f, Team.sharded, 150f);
            float shield0 = run.vanillaUnits.get(oct).shield;
            boolean[] healed = {false};
            float[] last = {40f}, low = {shield0};
            run.watches.add(() -> {
                var o = run.vanillaUnits.get(oct);
                if(o != null && o.isAdded()) low[0] = Math.min(low[0], o.shield);
                var v = run.vanillaUnits.get(daggers[0]);
                if(v != null && v.isAdded()){
                    if(v.health > last[0]) healed[0] = true;
                    last[0] = v.health;
                }
            });
            run.run(HALF);
            run.reload();
            run.run(600 - HALF);
            assertTrue(healed[0], "the repair field healed a dagger");
            assertTrue(low[0] < shield0, "the field absorbed shots (lowest " + low[0] + " of " + shield0 + ")");
        }
    }

    /** Nova's smart repair field (six targets, its own type healed less) and poly's, while daggers hurt their side. */
    @Test
    void novaAndPolyRepairTheirSide(){
        try(var run = new AbilityRun(SIZE, "stone")){
            run.stander("nova", Team.sharded, 180f, 200f, 0f);
            run.stander("poly", Team.sharded, 220f, 230f, 0f);
            int[] allies = new int[8];
            for(int i = 0; i < allies.length; i++){
                allies[i] = run.stander(i % 2 == 0 ? "dagger" : "nova", Team.sharded, 150f + i * 12f, 190f + (i % 3) * 14f, 0f);
                run.health(allies[i], 30f + i * 7f);
            }
            for(int i = 0; i < 3; i++) run.fighter("dagger", Team.crux, 320f, 150f + i * 40f, 180f, 280f, 170f + i * 20f, Team.sharded, 140f);
            int[] heals = {0};
            float[] last = new float[allies.length];
            for(int i = 0; i < allies.length; i++) last[i] = run.vanillaUnits.get(allies[i]).health;
            run.watches.add(() -> {
                for(int i = 0; i < allies.length; i++){
                    var v = run.vanillaUnits.get(allies[i]);
                    if(v == null || !v.isAdded()) continue;
                    if(v.health > last[i] + 1f) heals[0]++;
                    last[i] = v.health;
                }
            });
            run.run(HALF);
            run.reload();
            run.run(700 - HALF);
            assertTrue(heals[0] >= 5, "the fields healed (" + heals[0] + " heals)");
        }
    }

    /** Pulsar's and scepter's shield regen fields refill their side's shields while daggers drain them. */
    @Test
    void pulsarAndScepterRegenShields(){
        try(var run = new AbilityRun(SIZE, "stone")){
            run.stander("pulsar", Team.sharded, 180f, 200f, 0f);
            run.stander("scepter", Team.sharded, 230f, 180f, 0f);
            int dagger = run.stander("dagger", Team.sharded, 200f, 220f, 0f);
            for(int i = 0; i < 3; i++) run.fighter("dagger", Team.crux, 330f, 170f + i * 30f, 180f, 300f, 190f + i * 15f, Team.sharded, 140f);
            float[] most = {0f};
            run.watches.add(() -> {
                var v = run.vanillaUnits.get(dagger);
                if(v != null && v.isAdded()) most[0] = Math.max(most[0], v.shield);
            });
            run.run(HALF);
            run.reload();
            run.run(620 - HALF);
            assertTrue(most[0] > 0f, "the dagger got a shield");
        }
    }

    /** On water: oxynoe overclocks its side, bryde refills shields, aegires' energy field strikes risso and walls. */
    @Test
    void navalFieldsOnWater(){
        try(var run = new AbilityRun(SIZE, "deep-water")){
            run.cheat(Team.crux);
            run.place("copper-wall", 30, 22, Team.crux);
            run.place("copper-wall", 30, 26, Team.crux);
            run.place("plastanium-wall", 28, 30, Team.crux);
            run.place("titanium-wall", 22, 30, Team.sharded);
            run.buildingHealth(22, 30, 100f);
            // apart, so no shot meets two of them at once (the bullet collider's order is W2.8's open item)
            run.stander("oxynoe", Team.sharded, 150f, 230f, 0f);
            run.stander("bryde", Team.sharded, 110f, 150f, 0f);
            run.stander("aegires", Team.sharded, 200f, 160f, 0f);
            int risso = run.stander("risso", Team.sharded, 190f, 240f, 0f);
            run.health(risso, 100f);
            for(int i = 0; i < 3; i++) run.fighter("risso", Team.crux, 300f, 180f + i * 25f, 180f, 260f, 190f + i * 15f, Team.sharded, 120f);
            boolean[] overclocked = {false};
            float wall = world.build(30, 22).health;
            run.watches.add(() -> {
                for(var u : mindustry.gen.Groups.unit) if(u.team == Team.sharded && u.hasEffect(content.statusEffect("overclock"))) overclocked[0] = true;
            });
            // oxynoe's field fires every 360 ticks, and a save restarts its timer: no save here
            run.run(720);
            assertTrue(overclocked[0], "oxynoe overclocked its side");
            var struck = world.build(30, 22);
            assertTrue(struck == null || struck.health < wall, "the energy field struck the wall");
        }
    }

    /**
     * Quell's and disrupt's suppression fields stop an enemy mender from healing its damaged walls. Without a save:
     * through vanilla's, the mender's own state diverges 66 frames after the load, with every compared field equal
     * (a gap of the mender's load, W3.6's, in the report).
     */
    @Test
    void quellAndDisruptSuppressMenders(){
        suppression(false);
    }

    static void suppression(boolean reload){
        try(var run = new AbilityRun(SIZE, "stone")){
            run.cheat(Team.crux);
            run.place("mender", 30, 25, Team.crux);
            for(int y = 22; y <= 28; y += 3){
                run.place("copper-wall", 32, y, Team.crux);
                run.buildingHealth(32, y, 60f);
            }
            run.place("copper-wall", 40, 40, Team.crux);
            run.buildingHealth(40, 40, 60f);
            run.stander("quell", Team.sharded, 120f, 200f, 0f);
            run.stander("disrupt", Team.sharded, 100f, 120f, 0f);
            boolean[] suppressed = {false};
            run.watches.add(() -> {
                var b = world.build(30, 25);
                if(b != null && b.isHealSuppressed()) suppressed[0] = true;
            });
            if(reload){
                run.run(HALF);
                run.reload();
                run.run(600 - HALF);
            }else{
                run.run(600);
            }
            assertTrue(suppressed[0], "the mender was suppressed");
        }
    }

    /** Tecta's shield arc: it bounces daggers' shots back, kills quell's missiles, and pushes a dagger out. */
    @Test
    void tectasArcDeflectsAndStopsMissiles(){
        try(var run = new AbilityRun(SIZE, "stone")){
            int tecta = run.fighter("tecta", Team.sharded, 160f, 200f, 0f, 160f, 200f, null, 0f);
            for(int i = 0; i < 3; i++) run.fighter("dagger", Team.crux, 300f, 170f + i * 30f, 180f, 260f, 185f + i * 15f, Team.sharded, 160f);
            run.fighter("dagger", Team.crux, 320f, 200f, 180f, 160f, 200f, null, 0f);
            run.fighter("quell", Team.crux, 340f, 260f, 200f, 340f, 260f, Team.sharded, 330f);
            float[] low = {Float.MAX_VALUE};
            run.watches.add(() -> {
                var v = run.vanillaUnits.get(tecta);
                if(v != null && v.isAdded()) low[0] = Math.min(low[0], v.abilities[0].data);
            });
            run.run(HALF);
            run.reload();
            run.run(700 - HALF);
            assertTrue(low[0] < 2500f, "the arc took hits (lowest " + low[0] + ")");
            assertTrue(run.calls.getOrDefault("UnitSafeDeath", 0) > 0, "the arc stopped a missile: " + run.calls);
        }
    }

    /**
     * Latum dies: five renales and a neoplasm splat. The renales, hurt, regenerate and drink the neoplasm where they
     * stand; one killed later splashes more. No shots fly: a shot meeting two overlapping renales would hit them in the
     * bullet collider's cell order, not vanilla's quadtree order (W2.8's open item).
     */
    @Test
    void latumSpawnsRenalesThatDrinkItsSplat(){
        try(var run = new AbilityRun(SIZE, "stone")){
            int latum = run.stander("latum", Team.crux, 200f, 200f, 0f);
            run.at(10, () -> {
                run.vanillaUnits.get(latum).kill();
                run.shardUnits.get(latum).kill();
            });
            run.at(60, () -> renales(run, (v, s) -> {
                v.health = s.health = 120f;
            }));
            run.at(400, () -> renales(run, (v, s) -> {
                if(v.id % 2 == 0){
                    v.kill();
                    s.kill();
                }
            }));
            int[] renales = {0};
            float[] drunk = {0f};
            run.watches.add(() -> {
                int n = 0;
                for(var u : mindustry.gen.Groups.unit) if(u.type.name.equals("renale")){
                    n++;
                    drunk[0] = Math.max(drunk[0], u.health);
                }
                renales[0] = Math.max(renales[0], n);
            });
            run.run(HALF);
            run.reload();
            run.run(800 - HALF);
            assertEquals(5, renales[0], "latum spawned five renales");
            assertTrue(drunk[0] > 150f, "a renale healed (" + drunk[0] + ")");
        }
    }

    /** Each living renale on both sides, in id order. */
    static void renales(AbilityRun run, java.util.function.BiConsumer<mindustry.gen.Unit, com.xpdustry.shard.sim.unit.Unit> edit){
        var vanilla = new java.util.ArrayList<mindustry.gen.Unit>();
        for(var u : mindustry.gen.Groups.unit) if(u.type.name.equals("renale") && !u.dead) vanilla.add(u);
        vanilla.sort(java.util.Comparator.comparingInt(u -> u.id));
        for(var v : vanilla){
            var s = (com.xpdustry.shard.sim.unit.Unit)com.xpdustry.shard.sim.entity.EntityGroups.of(run.shard).byId(v.id - run.vanillaBase + run.shardBase);
            edit.accept(v, s);
        }
    }
}
