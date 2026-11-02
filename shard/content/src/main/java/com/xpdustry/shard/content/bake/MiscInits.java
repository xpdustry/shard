package com.xpdustry.shard.content.bake;

import static com.xpdustry.shard.content.bake.Bake.*;

/** Items, liquids, status effects and weathers. Status reactions ({@code init(Runnable)}) are extracted, not baked. */
final class MiscInits{
    private MiscInits(){
    }

    static void register(){
        // Liquid.java:83
        init("liquid", (bake, l, sup) -> {
            sup.run();
            if(l.b("gas")){
                // always "boils", it's a gas
                l.set("boilPoint", -1f);
                // all gases are transparent: Color.a = 0.6f, written back as (int)(0.6f * 255)
                String color = l.s("color").substring(0, 7) + Integer.toHexString(0x100 | (int)(0.6f * 255)).substring(1);
                l.set("color", color);
                // for gases, gas color is implicitly their color
                l.set("gasColor", color);
                if(l.get("barColor") == null) l.set("barColor", color.substring(0, 7) + "ff");
            }
        });
    }
}
