package com.xpdustry.shard.sim.unit;

import com.xpdustry.shard.common.registry.*;
import com.xpdustry.shard.sim.world.*;

import java.util.*;

/**
 * The AI kinds a package ports: each registered implementation makes the controllers of the kinds it knows, by the
 * content's name ({@code ground-ai}, {@code flying-ai}…), the way vanilla's constructors do, timers' draws included.
 * {@link Controllers} asks them in class name order; a kind none of them makes is an inert {@link AiController}, which
 * only draws its timers. Register with {@code @Register}.
 */
@Registry
public interface AiKinds{
    /** A new controller of {@code kind}, as vanilla's {@code new XAI()}, or null if this package doesn't port it. */
    AiController create(World world, String kind);

    /** {@code new <kind>()}: the registered kind's controller, else an inert AI of that kind. */
    static AiController make(World world, String kind){
        for(AiKinds kinds : Registered.KINDS){
            AiController made = kinds.create(world, kind);
            if(made != null) return made;
        }
        return new AiController(world, kind);
    }

    final class Registered{
        static final List<AiKinds> KINDS = List.copyOf(Registries.instances(AiKinds.class));

        private Registered(){
        }
    }
}
