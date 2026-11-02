package com.xpdustry.shard.sim.unit;

import org.junit.jupiter.api.*;

import static com.xpdustry.shard.sim.unit.ShapeTraceOracleTest.*;
import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate (WP42 with WP44): movement through water with the floors' statuses on. {@code ShapeTraceOracleTest} switches
 * vanilla's {@code wet} off; this class runs its fixture and scripts with water as vanilla has it, so units wading in
 * shallow and sand water get {@code wet} (speed 0.94) from {@code StatusComp} every frame. Mechs walk through the
 * shallow and sand water and into the deep pool, a boosting mech lands in it, and naval units, which are immune, sail
 * all of it dry. Each frame, on top of the shape gate's comparisons (position, rotation, velocity, drag, elevation,
 * drown time, shape state, buildings, boulders, the random), every unit's statuses, multipliers and {@code hasEffect}
 * bits equal vanilla's ({@link StatusOracleTest#describe}). Tolerance: bit for bit.
 */
class WetShapeTraceOracleTest{
    @Test
    void mechsWadeThroughWetWaterAsVanilla(){
        var scene = new Scene();
        // up and down the shallow water, into the sand water and back
        scene.spawn("mace", 16, 25, 90f, new int[]{PREF, ROTATE, AT}, false, w(15, 33), w(17, 36), w(14, 30), w(16, 24));
        scene.spawn("fortress", 16, 30, 270f, new int[]{ROTATE, PREF}, false, w(15, 26), w(17, 35), w(15, 29));
        // from dry stone through the shallow water into the deep pool
        scene.spawn("dagger", 18, 27, 180f, new int[]{PREF, ROTATE, AT}, false, w(14, 27), w(11, 27), w(10, 30), w(12, 25));
        // a boosting mech that lands in the shallow water: wet only once it is down
        scene.spawn("nova", 22, 32, 180f, new int[]{PREF, ROTATE}, true, w(15, 32), w(16, 27), w(14, 35), w(19, 33));
        int wet = run(scene);
        assertTrue(wet > 300, "units waded wet: " + wet + " unit-frames");
        assertTrue(scene.facts(2).shallow && scene.facts(2).deep, "the dagger reached the deep pool");
        assertTrue(scene.facts(3).flew && scene.facts(3).landed, "the nova boosted and landed");
    }

    @Test
    void navalUnitsSailDryAsVanilla(){
        var scene = new Scene();
        scene.spawn("risso", 6, 26, 0f, new int[]{PREF, AT}, false, w(16, 26), w(9, 28), w(10, 36), w(4, 33), w(15, 30));
        scene.spawn("retusa", 15, 33, 180f, new int[]{PREF, ROTATE}, false, w(5, 29), w(16, 35), w(8, 26), w(14, 25));
        scene.spawn("minke", 15, 36, 90f, new int[]{AT, PREF}, false, w(6, 36), w(12, 30), w(16, 25), w(4, 24));
        for(String type : new String[]{"risso", "retusa", "minke"}){
            assertTrue(content.unit(type).immunities.contains(mindustry.content.StatusEffects.wet), type + " is immune to wet");
        }
        assertEquals(0, run(scene), "naval units never get wet");
    }

    /** {@code Scene.run}, with the statuses compared each frame; returns how many unit-frames were wet in vanilla. */
    static int run(Scene scene){
        var wetEffect = mindustry.content.StatusEffects.wet;
        assertSame(wetEffect, ((mindustry.world.blocks.environment.Floor)content.block("shallow-water")).status, "vanilla's water is wet here");
        com.xpdustry.shard.oracle.Vanilla.runPosted();
        scene.compare("spawned");
        int wet = 0;
        for(int frame = 0; frame < FRAMES; frame++){
            Script.frame = frame;
            com.xpdustry.shard.oracle.Vanilla.frame();
            scene.world.tick();
            scene.compare("frame " + frame);
            for(int i = 0; i < scene.shard.size(); i++){
                var v = scene.vanilla.get(i);
                assertEquals(StatusOracleTest.describe(v), StatusOracleTest.describe(scene.shard.get(i)),
                    "frame " + frame + " " + v.type.name + " #" + i + " statuses");
                if(v.isAdded() && v.hasEffect(wetEffect)) wet++;
                scene.seen.get(i).see(scene.shard.get(i), frame);
            }
        }
        return wet;
    }
}
