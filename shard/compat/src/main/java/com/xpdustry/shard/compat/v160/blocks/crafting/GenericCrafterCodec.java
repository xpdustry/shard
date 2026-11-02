package com.xpdustry.shard.compat.v160.blocks.crafting;

import com.xpdustry.shard.common.registry.*;

/** {@code GenericCrafterBuild}: the crafter base's fields only ({@link CrafterCodec}). */
@Register
public final class GenericCrafterCodec extends CrafterCodec{
    public GenericCrafterCodec(){
        super("generic-crafter");
    }
}
