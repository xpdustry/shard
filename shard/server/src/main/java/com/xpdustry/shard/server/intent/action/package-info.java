/**
 * Vanilla's action filter (Administration.java:163-200, 674-772): the 16
 * {@link com.xpdustry.shard.server.intent.action.ActionType}s, the immutable
 * {@link com.xpdustry.shard.server.intent.action.PlayerAction} filters see, the registered
 * {@link com.xpdustry.shard.server.intent.action.ActionFilter}s (vanilla's interaction rate limit first), and the
 * admin and plugin deny rules ({@link com.xpdustry.shard.server.intent.action.ActionRules}), which are world state set
 * by logged commands.
 *
 * <p>Owner: WP26.
 */
package com.xpdustry.shard.server.intent.action;
