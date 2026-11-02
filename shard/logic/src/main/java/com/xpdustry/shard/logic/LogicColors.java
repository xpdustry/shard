package com.xpdustry.shard.logic;

import java.util.*;

/**
 * Arc's {@code Colors} table as a v160.7 server has it: Arc's defaults, their lowercase copies without underscores, and
 * the five Mindustry adds in {@code UI.loadColors} ({@code accent}, {@code unlaunched}, {@code highlight}, {@code stat},
 * {@code negstat}). mlog reads it twice: {@code %[name]} literals, and the {@code @color<Name>} globals. Values are
 * packed as {@code Color.toDoubleBits}. {@code GlobalsTest} compares the table with vanilla's.
 */
public final class LogicColors{
    private static final TreeMap<String, Double> colors = new TreeMap<>();

    static{
        // Colors.reset(), in its order
        float[] white = {1, 1, 1, 1}, royal = rgba(0x4169e1ff), lightGray = rgba(0xbfbfbfff), gray = rgba(0x7f7f7fff),
        darkGray = rgba(0x3f3f3fff);
        put("CLEAR", 0, 0, 0, 0);
        put("BLACK", 0, 0, 0, 1);
        put("WHITE", white);
        put("LIGHT_GRAY", lightGray);
        put("GRAY", gray);
        put("DARK_GRAY", darkGray);
        put("LIGHT_GREY", lightGray);
        put("GREY", gray);
        put("DARK_GREY", darkGray);
        put("BLUE", royal); // Arc's override "for better visuals"
        put("NAVY", 0, 0, 0.5f, 1);
        put("ROYAL", royal);
        put("SLATE", rgba(0x708090ff));
        put("SKY", rgba(0x87ceebff));
        put("CYAN", 0, 1, 1, 1);
        put("TEAL", 0, 0.5f, 0.5f, 1);
        put("GREEN", hex("38d667"));
        put("ACID", rgba(0x7fff00ff));
        put("LIME", rgba(0x32cd32ff));
        put("FOREST", rgba(0x228b22ff));
        put("OLIVE", rgba(0x6b8e23ff));
        put("YELLOW", rgba(0xffff00ff));
        put("GOLD", rgba(0xffd700ff));
        put("GOLDENROD", rgba(0xdaa520ff));
        put("ORANGE", rgba(0xffa500ff));
        put("BROWN", rgba(0x8b4513ff));
        put("TAN", rgba(0xd2b48cff));
        put("BRICK", rgba(0xb22222ff));
        put("RED", hex("e55454"));
        put("SCARLET", rgba(0xff341cff));
        put("CRIMSON", rgba(0xdc143cff));
        put("CORAL", rgba(0xff7f50ff));
        put("SALMON", rgba(0xfa8072ff));
        put("PINK", rgba(0xff69b4ff));
        put("MAGENTA", 1, 0, 1, 1);
        put("PURPLE", rgba(0xa020f0ff));
        put("VIOLET", rgba(0xee82eeff));
        put("MAROON", rgba(0xb03060ff));
        for(var entry : new ArrayList<>(colors.entrySet())){
            colors.put(entry.getKey().toLowerCase(Locale.ROOT).replace("_", ""), entry.getValue());
        }

        // UI.loadColors()
        float[] accent = hex("ffd37f");
        put("accent", accent);
        put("unlaunched", hex("8982ed"));
        put("highlight", lerp(accent, white, 0.3f));
        put("stat", hex("ffd37f"));
        put("negstat", hex("e55454"));
    }

    private LogicColors(){
    }

    /** {@code Colors.get(name)} packed with {@code toDoubleBits}, or null. */
    public static Double get(String name){
        return colors.get(name);
    }

    /** Every name, sorted: a copy. */
    public static SortedMap<String, Double> all(){
        return new TreeMap<>(colors);
    }

    private static void put(String name, float... rgba){
        colors.put(name, MlogMath.packColor(rgba[0], rgba[1], rgba[2], rgba[3]));
    }

    /** {@code new Color(int rgba8888)}. */
    private static float[] rgba(int value){
        return new float[]{((value & 0xff000000) >>> 24) / 255f, ((value & 0x00ff0000) >>> 16) / 255f,
        ((value & 0x0000ff00) >>> 8) / 255f, (value & 0x000000ff) / 255f};
    }

    /** {@code Color.valueOf("rrggbb")}. */
    private static float[] hex(String hex){
        int value = (int)Long.parseLong(hex + "ff", 16);
        return rgba(value);
    }

    /** {@code Color.lerp(target, t)}, which clamps. */
    private static float[] lerp(float[] from, float[] to, float t){
        float[] out = new float[4];
        for(int i = 0; i < 4; i++){
            float v = from[i] + t * (to[i] - from[i]);
            out[i] = v < 0 ? 0 : v > 1 ? 1 : v;
        }
        return out;
    }
}
