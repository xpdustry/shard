package com.xpdustry.shard.compat.v160.blocks.power;

import com.xpdustry.shard.common.registry.*;

/** {@code ThermalGeneratorBuild}: the generator base's fields only; {@code sum} is taken again when proximity is. */
@Register
public final class ThermalGeneratorCodec extends GeneratorCodec{
    public ThermalGeneratorCodec(){
        super("thermal-generator");
    }
}
