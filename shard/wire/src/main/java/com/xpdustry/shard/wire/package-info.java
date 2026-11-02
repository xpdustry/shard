/**
 * Vanilla's wire format, without sockets (note 25, ARCHITECTURE.md §6). The codecs generated from
 * {@code wire/schema/v160.7.yaml} live in {@code com.xpdustry.shard.wire.v160_7}: the packet registry and records, the
 * entity class table and layouts. This package holds what they call, written by hand: {@link Codecs} (vanilla's
 * {@code TypeIO}) and {@link WireObjects} ({@code writeObject} and its two readers) over {@code :common}'s
 * {@code Writes} and {@code Reads}, {@link Frame} headers, the pack-independent {@link Bootstrap} reader, and the
 * {@link WireContext} lookups decoders need. Decoders throw only {@link WireException}. Never imports {@code :sim}.
 *
 * <p>Owner: WP14.
 */
package com.xpdustry.shard.wire;
