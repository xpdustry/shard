package com.xpdustry.shard.sim.bullet;

import com.xpdustry.shard.common.registry.*;

import java.lang.annotation.*;

/**
 * Registers a {@link BulletType} subclass as the behaviour of a bullet kind:
 * {@code @BulletKind("flak-bullet-type") public class FlakBulletType extends BasicBulletType}. Bullets whose kind is
 * that key, or extends it without a class of its own, run it ({@link BulletTypes}). The class must be public and
 * concrete with a public no-argument constructor; the indexer checks that at compile time.
 */
@Register
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface BulletKind{
    /** The kebab-case kind key, as the content names it. */
    String value();
}
