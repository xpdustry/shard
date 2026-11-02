package com.xpdustry.shard.sim.blocks.power;

import com.xpdustry.shard.common.math.*;
import com.xpdustry.shard.logic.lang.*;
import com.xpdustry.shard.sim.building.*;
import com.xpdustry.shard.sim.entity.*;
import com.xpdustry.shard.sim.persist.*;
import com.xpdustry.shard.sim.world.*;

/**
 * Vanilla's {@code LightBlock.LightBuild}: the illuminator, a power consumer that only lights. Its {@link #color} is
 * its config ({@code Integer}, RGBA8888) and logic's {@code control color}; {@link #smoothTime} eases toward its
 * time scale.
 */
@Kind("light-block")
public final class LightBlock extends Building implements Checkpointable{
    /** {@code Pal.accent.rgba()}. */
    public static final int DEFAULT_COLOR = 0xffd37fff;

    static final Configs<LightBlock> CONFIGS = Configs.of(LightBlock.class).on(Integer.class, (light, value) -> light.color = value);

    public int color = DEFAULT_COLOR;
    public float smoothTime = 1f;

    @Override
    public void updateTile(){
        smoothTime = Mathf.lerpDelta(smoothTime, timeScale, 0.1f);
    }

    @Override
    protected Configs<?> configs(){
        return CONFIGS;
    }

    /** {@code sensor @color}: its colour, not its team's (LightBlock.java:90). */
    @Override
    public double sense(LAccess sensor){
        if(sensor == LAccess.color) return TeamColors.rgba(color);
        return super.sense(sensor);
    }

    @Override
    public Object config(){
        return color;
    }

    /** {@code control color}: the double's low 32 bits as RGBA8888, through Arc's float channels. */
    @Override
    public void control(LAccess type, double p1, double p2, double p3, double p4){
        if(type == LAccess.color) color = throughFloats((int)Double.doubleToRawLongBits(p1));
        super.control(type, p1, p2, p3, p4);
    }

    /**
     * {@code Tmp.c1.fromDouble(p1).rgba8888()}: each channel divided by 255 and multiplied back, which can truncate
     * one lower (vanilla quirk kept).
     */
    static int throughFloats(int value){
        float r = ((value & 0xff000000) >>> 24) / 255f, g = ((value & 0x00ff0000) >>> 16) / 255f;
        float b = ((value & 0x0000ff00) >>> 8) / 255f, a = (value & 0x000000ff) / 255f;
        return ((int)(r * 255) << 24) | ((int)(g * 255) << 16) | ((int)(b * 255) << 8) | (int)(a * 255);
    }

    @Override
    public void write(CheckpointOut out){
        out.i("color", color).f("smoothTime", smoothTime);
    }

    @Override
    public void read(CheckpointIn in){
        color = in.i("color");
        smoothTime = in.f("smoothTime");
    }

    @Override
    protected void copyState(Entity into, CopyContext context){
        super.copyState(into, context);
        var out = (LightBlock)into;
        out.color = color;
        out.smoothTime = smoothTime;
    }

    @Override
    public void hashState(Hasher hasher){
        super.hashState(hasher);
        hasher.i(color).f(smoothTime);
    }
}
