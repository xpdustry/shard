package com.xpdustry.shard.compat.v160.blocks.crafting;

import com.xpdustry.shard.common.registry.*;

/** {@code AttributeCrafterBuild}: the crafter base's fields only ({@link CrafterCodec}). */
@Register
public final class AttributeCrafterCodec extends CrafterCodec{
    public AttributeCrafterCodec(){
        super("attribute-crafter");
    }
}
