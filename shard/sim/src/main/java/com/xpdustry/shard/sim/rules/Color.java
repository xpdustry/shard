package com.xpdustry.shard.sim.rules;

/**
 * An RGBA colour as Arc's {@code Color} holds it: four floats. Two colours are equal when their 8-bit packing is,
 * as in Arc ({@code Color.equals} compares {@code abgr()}), which is what decides whether the rules JSON omits a
 * colour as default.
 */
public record Color(float r, float g, float b, float a){
    /** Arc's {@code Color.valueOf(hex)}: {@code rrggbb} or {@code rrggbbaa}, with an optional {@code #}. */
    public static Color valueOf(String hex){
        int offset = hex.startsWith("#") ? 1 : 0;
        int r = hex(hex, offset), g = hex(hex, offset + 2), b = hex(hex, offset + 4);
        int a = hex.length() - offset != 8 ? 255 : hex(hex, offset + 6);
        return new Color(r / 255f, g / 255f, b / 255f, a / 255f);
    }

    private static int hex(String string, int from){
        return Character.digit(string.charAt(from), 16) * 16 + Character.digit(string.charAt(from + 1), 16);
    }

    /** Arc's {@code rgba8888}: each channel truncated to 8 bits. */
    public int rgba(){
        return (int)(r * 255) << 24 | (int)(g * 255) << 16 | (int)(b * 255) << 8 | (int)(a * 255);
    }

    /** Arc's {@code toString()}: {@code rrggbbaa}, eight lowercase hex digits. */
    public String hex(){
        return String.format("%08x", (int)(255 * r) << 24 | (int)(255 * g) << 16 | (int)(255 * b) << 8 | (int)(255 * a));
    }

    @Override
    public boolean equals(Object o){
        return o instanceof Color c && rgba() == c.rgba();
    }

    @Override
    public int hashCode(){
        return rgba();
    }

    @Override
    public String toString(){
        return hex();
    }
}
