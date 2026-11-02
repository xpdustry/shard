/**
 * Layered, typed YAML configuration (note 34 "Shard configuration").
 *
 * <ul>
 *   <li>{@link com.xpdustry.shard.server.config.ConfigKeys}: every key, defined once: path, type, default, scope
 *       (host or world) and reload policy (live or restart).</li>
 *   <li>{@link com.xpdustry.shard.server.config.ConfigLoader}: {@code shard.yml}, then {@code SHARD__} environment
 *       variables, then per-world overrides; every problem reported with its {@code file:line:column}.</li>
 *   <li>{@link com.xpdustry.shard.server.config.ConfigService}: reloads. Live keys apply; restart keys stay
 *       pending.</li>
 *   <li>{@link com.xpdustry.shard.server.config.ConfigSchema}: the JSON Schemas, reference and example committed in
 *       resources.</li>
 *   <li>{@link com.xpdustry.shard.server.config.VanillaKeys}: what became of each vanilla setting.</li>
 * </ul>
 *
 * <p>Owner: WP51.
 */
package com.xpdustry.shard.server.config;
