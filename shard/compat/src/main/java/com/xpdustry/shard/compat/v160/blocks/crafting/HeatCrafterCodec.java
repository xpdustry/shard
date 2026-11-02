package com.xpdustry.shard.compat.v160.blocks.crafting;

import com.xpdustry.shard.common.registry.*;

/** {@code HeatCrafterBuild}: the crafter base's fields only ({@link CrafterCodec}). */
@Register
public final class HeatCrafterCodec extends CrafterCodec{
    public HeatCrafterCodec(){
        super("heat-crafter");
    }
}
