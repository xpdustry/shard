package com.xpdustry.shard.sim.building;

import com.xpdustry.shard.common.registry.*;

import java.lang.annotation.*;

/**
 * Registers a {@link Building} subclass as the behaviour of a kind: {@code @Kind("conveyor") public final class
 * Conveyor extends Building}. Blocks whose definition names that kind key get one instance of the class each. The
 * class must be public and concrete with a public no-argument constructor; the indexer checks that at compile time.
 */
@Register
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Kind{
    /** The kebab-case kind key ({@link com.xpdustry.shard.content.KindKey}). */
    String value();
}
