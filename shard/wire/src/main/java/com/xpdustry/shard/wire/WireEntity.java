package com.xpdustry.shard.wire;

/**
 * An entity's fields as one of the generated layouts reads them: every {@code <Layout>Layout.Source} in
 * {@code com.xpdustry.shard.wire.v160_7} extends this. Unit payloads and unit containers carry one, and the generated
 * {@code EntityClasses} encodes it with the layout of its class id.
 */
public interface WireEntity{}
