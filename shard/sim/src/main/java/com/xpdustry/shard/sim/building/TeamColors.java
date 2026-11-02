package com.xpdustry.shard.sim.building;

import com.xpdustry.shard.common.math.*;

/**
 * Vanilla's team colours, as {@code sensor @color} gives them ({@code Color.toDoubleBits(r, g, b, 1)}): the seven
 * named teams' (Team.java:21-31), and the placeholders 7 to 255, which {@code Team}'s static block draws in id order
 * as {@code HSVtoRGB(360 * random(), 100 * random(0.4, 1), 100 * random(0.6, 1))} from {@code Mathf.rand} seeded with 8,
 * after three draws.
 */
public final class TeamColors{
    private static final double[] COLORS = new double[256];

    static{
        String[] named = {"4d4e58", "ffd37f", "f25555", "a27ce5", "54d67d", "6c87fd", "e05438"};
        for(int id = 0; id < named.length; id++){
            int hex = Integer.parseInt(named[id], 16);
            COLORS[id] = pack((hex >>> 16 & 0xff) / 255f, (hex >>> 8 & 0xff) / 255f, (hex & 0xff) / 255f);
        }
        var rand = new Rand(0);
        rand.setSeed(8);
        for(int i = 0; i < 3; i++) Mathf.random(rand);
        for(int id = named.length; id < COLORS.length; id++){
            float h = 360f * Mathf.random(rand), s = 100f * Mathf.random(rand, 0.4f, 1f), v = 100f * Mathf.random(rand, 0.6f, 1f);
            COLORS[id] = hsv(h, s, v);
        }
    }

    private TeamColors(){
    }

    /** {@code team.color} as a packed logic colour. */
    public static double color(int team){
        return COLORS[team & 0xff];
    }

    /**
     * {@code Tmp.c1.set(rgba).toDoubleBits()}: an RGBA8888 colour as a packed logic colour, through Arc's float
     * channels, so a channel can come back one lower than it went in, as vanilla's does.
     */
    public static double rgba(int rgba){
        float r = ((rgba & 0xff000000) >>> 24) / 255f, g = ((rgba & 0x00ff0000) >>> 16) / 255f,
            b = ((rgba & 0x0000ff00) >>> 8) / 255f, a = (rgba & 0x000000ff) / 255f;
        int packed = ((int)(r * 255) << 24) | ((int)(g * 255) << 16) | ((int)(b * 255) << 8) | (int)(a * 255);
        return Double.longBitsToDouble(packed & 0x00000000_ffffffffL);
    }

    /** Arc's {@code Color.HSVtoRGB(h, s, v)}, then {@code toDoubleBits} at full alpha. */
    private static double hsv(float h, float s, float v){
        if(h == 360) h = 359;
        h = (float)Math.max(0.0, Math.min(360.0, h));
        s = (float)Math.max(0.0, Math.min(100.0, s));
        v = (float)Math.max(0.0, Math.min(100.0, v));
        s /= 100;
        v /= 100;
        h /= 60;
        int i = Mathf.floor(h);
        float f = h - i, p = v * (1 - s), q = v * (1 - s * f), t = v * (1 - s * (1 - f));
        return switch(i){
            case 0 -> pack(v, t, p);
            case 1 -> pack(q, v, p);
            case 2 -> pack(p, v, t);
            case 3 -> pack(p, q, v);
            case 4 -> pack(t, p, v);
            default -> pack(v, p, q);
        };
    }

    /** {@code Color.set} clamps, then {@code toDoubleBits(r, g, b, 1)}. */
    private static double pack(float r, float g, float b){
        r = Mathf.clamp(r);
        g = Mathf.clamp(g);
        b = Mathf.clamp(b);
        int rgba = ((int)(r * 255) << 24) | ((int)(g * 255) << 16) | ((int)(b * 255) << 8) | 255;
        return Double.longBitsToDouble(rgba & 0x00000000_ffffffffL);
    }
}
