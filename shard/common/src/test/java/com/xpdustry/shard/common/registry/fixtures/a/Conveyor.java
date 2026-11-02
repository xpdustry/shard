package com.xpdustry.shard.common.registry.fixtures.a;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.common.registry.fixtures.*;

@Register
public final class Conveyor implements Sample{
    @Override
    public String name(){
        return "conveyor";
    }
}
