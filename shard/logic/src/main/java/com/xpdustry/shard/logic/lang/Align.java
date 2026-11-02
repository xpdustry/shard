package com.xpdustry.shard.logic.lang;

/** Arc's {@code Align} bits, which mlog exposes as {@code @center}, {@code @topLeft}… for {@code draw print}. */
public final class Align{
    public static final int center = 1, top = 2, bottom = 4, left = 8, right = 16;
    public static final int topLeft = top | left, topRight = top | right, bottomLeft = bottom | left,
    bottomRight = bottom | right;

    /** The global names in vanilla's {@code LStatement.nameToAlign}, and their values. */
    public static final String[] names = {"center", "top", "bottom", "left", "right", "topLeft", "topRight", "bottomLeft", "bottomRight"};
    public static final int[] values = {center, top, bottom, left, right, topLeft, topRight, bottomLeft, bottomRight};

    private Align(){
    }

    /** The align value for {@code name}, or -1. */
    public static int of(String name){
        for(int i = 0; i < names.length; i++){
            if(names[i].equals(name)) return values[i];
        }
        return -1;
    }
}
