package com.xpdustry.shard.compat.v160.blocks;

import com.xpdustry.shard.oracle.*;
import com.xpdustry.shard.sim.blocks.power.*;
import com.xpdustry.shard.sim.rules.Color;
import mindustry.content.*;
import mindustry.entities.bullet.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.world.meta.*;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP38): generators and reactors as vanilla's, every frame ({@link PowerRun}): fuel, liquids, production,
 * warmup, reactor heat, the random draws of their effects, the graphs they feed, and their save chunks. A reactor's
 * meltdown runs in step through the frame it explodes with the explosion recorded, not applied
 * ({@link ExplosionRecorder}): what it would damage is compared as data ({@link ExplosionDamage} against vanilla's
 * tile pass). The explosion applied, its damage and fireballs included, is {@link GeneratorExplosionOracleTest}'s.
 */
class GeneratorOracleTest{
    @BeforeAll
    static void boot(){
        Vanilla.boot();
    }

    /**
     * Combustion, steam, differential, RTG, pyrolysis and chemical combustion generators, each fed by a source or by
     * hand and lighting an illuminator: fuels switch (pyratite and phase fabric last longer), sources stop, liquids
     * run out and a generator is disabled for a while.
     */
    @Test
    void consumeGeneratorsBurnWhatTheyAreFed(){
        var run = new PowerRun(24, 16);
        run.place("item-source", 1, 1);
        run.source(1, 1, "coal");
        run.place("combustion-generator", 2, 1);
        run.place("illuminator", 3, 1);
        run.at(100, () -> run.source(1, 1, "spore-pod"));
        run.at(200, () -> run.source(1, 1, "pyratite"));
        run.at(300, () -> run.enabled(1, 1, false));
        run.at(420, () -> run.enabled(2, 1, false));
        run.at(470, () -> run.enabled(2, 1, true));

        run.place("item-source", 1, 4);
        run.source(1, 4, "coal");
        run.place("steam-generator", 2, 4);
        run.place("illuminator", 4, 4);
        run.every(0, 300, 20, () -> run.liquid(2, 4, "water", 3f));
        run.at(150, () -> run.source(1, 4, "pyratite"));

        run.place("item-source", 1, 8);
        run.source(1, 8, "pyratite");
        run.place("differential-generator", 3, 8);
        run.place("illuminator", 5, 8);
        run.every(0, 250, 25, () -> run.liquid(3, 8, "cryofluid", 4f));

        run.place("item-source", 1, 12);
        run.source(1, 12, "thorium");
        run.place("rtg-generator", 2, 12);
        run.place("illuminator", 4, 12);
        run.at(200, () -> run.source(1, 12, "phase-fabric"));
        run.at(260, () -> run.enabled(1, 12, false));

        run.place("pyrolysis-generator", 9, 2);
        run.place("illuminator", 11, 2);
        run.every(0, 400, 15, () -> {
            run.liquid(9, 2, "arkycite", 4f);
            run.liquid(9, 2, "slag", 2f);
        });

        run.place("chemical-combustion-chamber", 9, 6);
        run.place("illuminator", 11, 6);
        run.every(0, 350, 15, () -> {
            run.liquid(9, 6, "ozone", 2f);
            run.liquid(9, 6, "arkycite", 4f);
        });
        run.run(600);
        assertTrue(run.ours(3, 8) instanceof ConsumeGenerator g && g.totalTime > 0f, "the differential generator ran");
    }

    /** Solar panels under the rules' solar multiplier, lighting with an ambient alpha, a light attribute and rain. */
    /**
     * A neoplasia reactor (heater generator) fed arkycite, water and phase fabric warms its heat up, makes power and
     * neoplasm, then cools when its water stops; 200 frames, before its neoplasm fills it.
     */
    @Test
    void heaterGeneratorsHeatUpAndCool(){
        var run = new PowerRun(20, 14);
        run.place("neoplasia-reactor", 4, 4);
        run.items(4, 4, "phase-fabric", 10);
        run.every(0, 200, 10, () -> run.liquid(4, 4, "arkycite", 13f));
        run.every(0, 120, 10, () -> run.liquid(4, 4, "water", 2f));
        run.place("battery", 7, 4);
        run.place("illuminator", 7, 5);
        run.at(150, () -> run.enabled(4, 4, false));
        run.at(170, () -> run.enabled(4, 4, true));
        run.run(200);
        assertTrue(((mindustry.world.blocks.power.HeaterGenerator.HeaterGeneratorBuild)run.vanilla(4, 4)).heat > 1f, "the reactor heated");
    }

    @Test
    void solarPanelsFollowTheLight(){
        var run = new PowerRun(12, 8);
        run.place("solar-panel", 1, 1);
        run.place("solar-panel", 2, 1);
        run.place("solar-panel-large", 2, 4);
        run.place("battery", 3, 1);
        run.place("illuminator", 4, 1);
        run.place("illuminator", 4, 4);
        run.at(40, () -> run.rules(r -> r.solarMultiplier = 2.5f, r -> r.solarMultiplier = 2.5f));
        run.at(80, () -> run.rules(r -> {
            r.lighting = true;
            r.ambientLight.a = 0.6f;
        }, r -> {
            r.lighting = true;
            r.ambientLight = new Color(r.ambientLight.r(), r.ambientLight.g(), r.ambientLight.b(), 0.6f);
        }));
        run.at(120, () -> run.rules(r -> r.attributes.set(Attribute.light, -0.25f),
            r -> r.attributes.set(com.xpdustry.shard.sim.rules.Attributes.id("light"), -0.25f)));
        run.at(160, () -> run.rules(r -> r.attributes.set(Attribute.light, 0.4f),
            r -> r.attributes.set(com.xpdustry.shard.sim.rules.Attributes.id("light"), 0.4f)));
        run.at(200, () -> run.enabled(2, 1, false));
        // rain darkens: its light attribute fades in with its opacity (state.envAttrs)
        run.at(220, () -> {
            mindustry.gen.Call.createWeather(Weathers.rain, 1f, 120f, 1f, 0f);
            com.xpdustry.shard.sim.weather.Weathers.createWeather(run.shard, PowerRun.EPOCH.id(com.xpdustry.shard.content.ContentType.WEATHER, "rain"),
                1f, 120f, 1f, 0f);
        });
        run.run(400);
    }

    /** Thermal generators over heat floors, and a turbine condenser on a vent, which fills with water. */
    @Test
    void thermalGeneratorsSumTheirFloors(){
        var run = new PowerRun(16, 10);
        run.floor(2, 2, "hotrock");
        run.floor(3, 2, "magmarock");
        run.floor(3, 3, "hotrock");
        run.place("thermal-generator", 2, 2);
        run.place("illuminator", 4, 2);
        run.floor(6, 2, "basalt");
        run.floor(7, 2, "magmarock");
        run.floor(6, 3, "magmarock");
        run.floor(7, 3, "magmarock");
        run.place("thermal-generator", 6, 2);
        run.place("illuminator", 8, 3);
        for(int x = 10; x <= 12; x++) for(int y = 4; y <= 6; y++) run.floor(x, y, "rhyolite-vent");
        run.place("turbine-condenser", 11, 5);
        run.place("illuminator", 13, 5);
        run.at(60, () -> run.place("illuminator", 4, 3));
        run.at(120, () -> run.enabled(2, 2, false));
        run.at(150, () -> run.enabled(2, 2, true));
        run.run(400);
    }

    /**
     * A thorium reactor fed thorium, cooled with too little cryofluid, then none: the heat climbs past the smoke
     * threshold; then enough coolant returns and cools it. It powers a battery and illuminators.
     */
    @Test
    void thoriumReactorHeatsAndCools(){
        var run = new PowerRun(16, 10);
        run.place("item-source", 1, 4);
        run.source(1, 4, "thorium");
        run.place("thorium-reactor", 3, 4);
        run.place("battery", 5, 4);
        run.place("illuminator", 5, 5);
        run.place("illuminator", 5, 3);
        run.every(0, 150, 10, () -> run.liquid(3, 4, "cryofluid", 0.3f));
        run.at(229, () -> assertTrue(((NuclearReactor)run.ours(3, 4)).heat > 0.3f, "past the smoke threshold"));
        run.every(230, 600, 10, () -> run.liquid(3, 4, "cryofluid", 4f));
        run.at(500, () -> run.enabled(1, 4, false));
        run.run(600);
        assertTrue(((NuclearReactor)run.ours(3, 4)).heat < 0.1f, "cooled again");
    }

    /** An impact reactor on a power source warms up on blast compound and cryofluid, then loses its power and cools. */
    @Test
    void impactReactorWarmsUp(){
        var run = new PowerRun(16, 10);
        run.place("power-source", 1, 4);
        run.place("item-source", 3, 2);
        run.source(3, 2, "blast-compound");
        run.place("impact-reactor", 3, 4);
        run.place("battery", 6, 3);
        run.place("illuminator", 6, 5);
        run.every(0, 600, 10, () -> run.liquid(3, 4, "cryofluid", 4f));
        run.at(400, () -> run.enabled(1, 4, false));
        run.at(500, () -> run.enabled(1, 4, true));
        run.at(540, () -> run.enabled(3, 2, false));
        run.run(600);
        assertTrue(run.vanilla(3, 4).warmup() > 0f, "it warmed up");
    }

    /**
     * A thorium reactor without coolant melts down: in step through the frame it explodes (props in the radius break,
     * ignitions are scheduled, fireballs launched), then the damage the explosion's tile pass deals, as data. Fires
     * and explosion damage of plain buildings are off, so vanilla's other buildings draw nothing when they die.
     */
    @Test
    void thoriumReactorMeltsDown(){
        var fireballs = new ArrayList<String>();
        BulletType fireball = Bullets.fireball;
        Bullets.fireball = new BulletType(){
            @Override
            public Bullet create(Entityc owner, Team team, float x, float y, float angle, float damage, float velocityScl, float lifetimeScl, Object data){
                fireballs.add("fireball " + x + " " + y + " " + angle + " " + velocityScl);
                return null;
            }
        };
        try{
            var run = PowerRun.withExplosions(48, 48);
            run.timers = true;
            run.rules(r -> {
                r.fire = false;
                r.damageExplosions = false;
            }, r -> {
                r.fire = false;
                r.damageExplosions = false;
            });
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
            for(int[] at : new int[][]{{20, 20}, {30, 27}, {24, 40}, {24, 45}, {5, 5}}) run.place("boulder", at[0], at[1]);
            int died = run.until(400, () -> run.vanilla(24, 24) == null);
            assertTrue(died < 400, "the reactor melted down");

            var recorder = run.recorder();
            assertEquals(fireballs, recorder.calls.stream().filter(c -> c.startsWith("fireball")).toList(), "fireballs");
            assertFalse(fireballs.isEmpty());
            assertEquals(List.of("damage 192.0 192.0 152.0 5000.0"), recorder.calls.stream().filter(c -> c.startsWith("damage")).toList());

            run.stepUnchecked();
            assertEquals(vanillaHits(), ourHits(recorder.hits), "the tile pass's damage");
            assertTrue(recorder.hits.size() > 10, "the explosion hit the walls, batteries and node: " + recorder.hits);
        }finally{
            Bullets.fireball = fireball;
        }
    }

    /** {@code Damage.damages} after the last tile pass, by tile. */
    static List<String> vanillaHits(){
        var map = (arc.struct.IntFloatMap)PowerRun.staticField(mindustry.entities.Damage.class, "damages");
        var out = new ArrayList<String>();
        for(var entry : map) out.add(arc.math.geom.Point2.x(entry.key) + "," + arc.math.geom.Point2.y(entry.key) + "=" + entry.value);
        out.sort(null);
        return out;
    }

    static List<String> ourHits(List<ExplosionDamage.Hit> hits){
        assertNotNull(hits, "the tile pass ran");
        var out = new ArrayList<String>();
        for(var hit : hits) out.add(com.xpdustry.shard.common.geom.Point2.x(hit.pos()) + "," + com.xpdustry.shard.common.geom.Point2.y(hit.pos()) + "=" + hit.damage());
        out.sort(null);
        return out;
    }
}
