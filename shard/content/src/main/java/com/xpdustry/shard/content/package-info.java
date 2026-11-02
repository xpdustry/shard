/**
 * Content as data (ARCHITECTURE.md §5): the typed definitions the simulation reads ({@link
 * com.xpdustry.shard.content.BlockDef}, {@link com.xpdustry.shard.content.UnitDef}, {@link
 * com.xpdustry.shard.content.ItemDef}…), wire ids, kind keys, and the frozen {@link
 * com.xpdustry.shard.content.ContentEpoch} that every world pins. No dependency on {@code :sim}.
 *
 * <p>The pipeline, one package each: {@code yaml} (parsing with marks), {@code schema} (kinds and typed structs),
 * {@code load} (packs, inheritance, overlays, ids), {@code bake} (vanilla's {@code init()} values), {@code epoch}
 * (the frozen snapshot and its hash), {@code patch} (vanilla content patches for worlds that differ) and
 * {@code reload} (checked reload candidates). The vanilla pack is generated into
 * {@code resources/content/vanilla} by {@code :tools:content-extract}.
 *
 * <p>Owner: WP10.
 */
package com.xpdustry.shard.content;
