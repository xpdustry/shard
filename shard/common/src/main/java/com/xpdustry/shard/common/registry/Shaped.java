package com.xpdustry.shard.common.registry;

import java.lang.annotation.*;

/**
 * Every record that implements a type carrying this annotation gets its shape in the registration index: its
 * components, read in order, and its canonical constructor ({@link Registries#shape(Class)}). Code that copies or
 * hashes such records generically, as {@code :sim}'s timer tasks are, reads them through the shape instead of
 * reflection. The record must not be private or local, so the generated code in its package can reach it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Shaped{
}
