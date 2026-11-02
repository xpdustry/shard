package com.xpdustry.shard.common.registry;

import java.lang.annotation.*;

/**
 * Marks an SPI type as a registry. Every class that extends or implements it, directly or not, and carries
 * {@link Register} is listed in the registration index under this type, without a hand-edited list.
 * {@link Registries#classes(Class)} reads the index back.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Registry{
}
