package com.xpdustry.shard.common.registry.fixtures.b;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.common.registry.fixtures.*;

@Register
public final class Router implements Sample{
    @Override
    public String name(){
        return "router";
    }
}
