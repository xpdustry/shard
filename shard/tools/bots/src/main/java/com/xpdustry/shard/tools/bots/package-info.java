/**
 * Headless protocol bots and the load generator (note 24 §6): {@link com.xpdustry.shard.tools.bots.LoadGen} starts N
 * bots that connect, load the world stream, confirm, send LZ4 {@code clientSnapshot}s every 66 ms, ping and chat.
 * The few packets they send are encoded by hand until {@code :wire}'s generated codecs land.
 *
 * <p>Owner: WP15.
 */
package com.xpdustry.shard.tools.bots;
