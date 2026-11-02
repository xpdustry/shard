package com.xpdustry.shard.common.registry;

import java.lang.annotation.*;

/**
 * Lists this class in the registration index of every {@link Registry} type it extends or implements. The processor
 * in {@code :tools:indexer} checks at compile time that the class is public, concrete, static if nested, has a public
 * no-argument constructor and implements at least one registry; anything else is a compile error.
 *
 * <p>On an annotation type, it makes that annotation a registration annotation: a class carrying it is registered as
 * if it carried {@code @Register}, and the index records the annotation's {@code value()}, which
 * {@link Registries#key} returns. {@code :sim}'s {@code @Kind("conveyor")} works this way. Such an annotation must have
 * {@code RUNTIME} retention, and no element but a {@code String value()}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Register{
}
