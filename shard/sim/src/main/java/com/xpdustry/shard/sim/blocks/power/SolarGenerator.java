package com.xpdustry.shard.sim.blocks.power;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.sim.blocks.environment.*;
import com.xpdustry.shard.sim.building.*;

/**
 * Vanilla's {@code SolarGenerator.SolarGeneratorBuild}: the solar panels. Their efficiency is the rules' solar
 * multiplier times the light: the environment's {@code light} attribute plus full light, or what the ambient light's
 * alpha leaves with {@code lighting} on.
 *
 * <p>The environment's attribute is {@code state.envAttrs}: the rules' plus the weather's ({@link #env}).
 */
@Kind("solar-generator")
public final class SolarGenerator extends Generator{
    @Override
    public void updateTile(){
        var rules = rules();
        productionEfficiency = enabled ? rules.solarMultiplier * Mathf.maxZero(env(Attributes.LIGHT)
            + (rules.lighting ? 1f - rules.ambientLight.a() : 1f)) : 0f;
    }
}
